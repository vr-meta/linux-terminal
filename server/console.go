package main

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// A small web console for the server: who is connected, and where dictation goes.
//
// It exists because the server is otherwise invisible. It runs as a user service
// on a machine you may not be sitting at, and "is anyone connected, and did the
// transcription endpoint ever work" are questions worth being able to answer
// without reading a log over ssh.
//
// It is **off by default** and binds to localhost when on. It can close sessions
// and set the transcription key, and it has no authentication of its own — the
// right way to reach it from elsewhere is an ssh tunnel, not a bind address.

//go:embed web/index.html
var consolePage []byte

func (s *Server) serveHTTP(address string) {
	mux := http.NewServeMux()

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(consolePage)
	})

	mux.HandleFunc("/api/state", s.handleState)
	mux.HandleFunc("/api/close", s.handleClose)
	mux.HandleFunc("/api/asr", s.handleASR)
	mux.HandleFunc("/api/pairing", s.handlePairing)

	if !strings.HasPrefix(address, "127.0.0.1:") && !strings.HasPrefix(address, "localhost:") {
		log.Printf("WARNING: the web console on %s has no authentication and can "+
			"close sessions and set the transcription key", address)
	}
	log.Printf("web console on http://%s", address)
	if err := http.ListenAndServe(address, mux); err != nil {
		log.Printf("web console stopped: %v", err)
	}
}

type sessionView struct {
	ID      int    `json:"id"`
	Remote  string `json:"remote"`
	Since   string `json:"since"`
	Seconds int    `json:"seconds"`
	Pid     int    `json:"pid"`
	Cwd     string `json:"cwd"`
	Tool    string `json:"tool"`
	Grid    string `json:"grid"`
}

func (s *Server) handleState(w http.ResponseWriter, r *http.Request) {
	var sessions []sessionView
	for _, session := range s.liveSessions() {
		tool := session.lastTool
		if tool == "" {
			tool = "shell"
		}
		pid := 0
		if session.cmd != nil && session.cmd.Process != nil {
			pid = session.cmd.Process.Pid
		}
		sessions = append(sessions, sessionView{
			ID:      session.ID,
			Remote:  session.Remote,
			Since:   session.Since.Format("15:04:05"),
			Seconds: int(time.Since(session.Since).Seconds()),
			Pid:     pid,
			Cwd:     short(session.lastCwd),
			Tool:    tool,
		})
	}

	writeJSON(w, map[string]any{
		"name":     s.Name,
		"version":  version,
		"port":     s.Port,
		"shell":    s.Shell,
		"cwd":      short(s.Cwd),
		"user":     currentUser(),
		"os":       osRelease(),
		"uptime":   int(time.Since(s.Started).Seconds()),
		"sessions": sessions,
		// The token in full, because this console is bound to localhost and a
		// pairing secret you cannot read is a pairing secret you cannot use. The
		// fingerprint beside it is what makes pairing a comparison rather than an
		// act of faith — the headset shows the same string.
		"pairing": map[string]any{
			"token":       s.Identity.Token,
			"fingerprint": s.Identity.Fingerprint(),
			"plain":       *flagAllowPlain,
		},
		"asr": map[string]any{
			"configured": s.ASR.configured(),
			"url":        s.ASR.URL,
			"model":      s.ASR.Model,
			"language":   s.ASR.Language,
			// Never the key itself — only whether there is one.
			"key_set": s.ASR.Key != "",
		},
	})
}

func (s *Server) handleClose(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	id, _ := strconv.Atoi(r.URL.Query().Get("id"))
	for _, session := range s.liveSessions() {
		if session.ID == id {
			log.Printf("web console closed session %d (%s)", id, session.Remote)
			session.close()
			writeJSON(w, map[string]any{"closed": id})
			return
		}
	}
	http.Error(w, "no such session", http.StatusNotFound)
}

// handleASR changes where dictation goes, and persists it. An empty key in the
// request leaves the existing one alone — so the form can be saved without the
// key having been sent to the browser in the first place.
func (s *Server) handleASR(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	var incoming ASR
	if err := json.NewDecoder(r.Body).Decode(&incoming); err != nil {
		http.Error(w, "bad JSON", http.StatusBadRequest)
		return
	}

	s.ASR.URL = strings.TrimSpace(incoming.URL)
	s.ASR.Model = strings.TrimSpace(incoming.Model)
	s.ASR.Language = strings.TrimSpace(incoming.Language)
	if strings.TrimSpace(incoming.Key) != "" {
		s.ASR.Key = strings.TrimSpace(incoming.Key)
	}
	if s.ASR.URL == "" && s.ASR.Key != "" {
		s.ASR.URL = openAIEndpoint
	}
	if s.ASR.Model == "" {
		s.ASR.Model = defaultModel
	}

	// The identity rides along: saving dictation settings must not be the thing
	// that erases the certificate this machine is known by.
	if err := saveConfig(Config{ASR: s.ASR, Identity: s.Identity}); err != nil {
		writeJSON(w, map[string]any{"saved": false, "error": err.Error()})
		return
	}
	log.Printf("dictation now via %s (%s)", hostOf(s.ASR.URL), s.ASR.Model)
	writeJSON(w, map[string]any{"saved": true})
}

// handlePairing issues a new token, which is how a headset that was lost or lent
// out stops being able to open a shell.
//
// The certificate is deliberately left alone: rotating it would make every
// paired headset refuse the machine, which is the correct response to a changed
// identity and the wrong outcome for a token you meant to replace.
func (s *Server) handlePairing(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}

	s.Identity.Token = newToken()
	if err := saveConfig(Config{ASR: s.ASR, Identity: s.Identity}); err != nil {
		writeJSON(w, map[string]any{"saved": false, "error": err.Error()})
		return
	}
	log.Printf("pairing token replaced — every paired headset must be paired again")
	writeJSON(w, map[string]any{"saved": true, "token": s.Identity.Token})
}

// saveConfig writes the settings file with the key in it, so it is 0600 and the
// directory is 0700. Anything looser is a key readable by every process you run.
func saveConfig(config Config) error {
	path := configPath()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(config, "", "  ")
	if err != nil {
		return err
	}
	temporary := path + ".tmp"
	if err := os.WriteFile(temporary, append(data, '\n'), 0o600); err != nil {
		return err
	}
	return os.Rename(temporary, path)
}

func writeJSON(w http.ResponseWriter, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	if err := json.NewEncoder(w).Encode(value); err != nil {
		fmt.Fprintf(os.Stderr, "console: %v\n", err)
	}
}
