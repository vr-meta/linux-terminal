// Command linux-vr-server serves shells on this machine to a VR headset as text.
//
// The streamed desktop rasterises a terminal, compresses it with H.264 and scales
// it into a panel; every stage of that can only lose sharpness. Here the bytes the
// shell produces are sent as bytes, and the headset draws the glyphs itself at the
// panel's own resolution.
//
// Beyond a socket-to-pty pipe it serves context: which directory a shell is in and
// which program is in front of it, turned into a list of buttons for the client to
// draw. That is not a guess — a pty has a foreground process group, so the answer
// is read from /proc.
//
// It also answers discovery probes, so a headset on the same network finds it
// without anyone typing an address.
//
// This replaces an earlier Python agent. The reason is installation, not speed: a
// server is something you put on a machine and forget, and one static binary with
// a systemd unit is a different proposition from a script plus an interpreter plus
// whatever the distribution decided python3 means this year.
package main

import (
	"flag"
	"fmt"
	"log"
	"net"
	"net/url"
	"os"
	"os/user"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Port carries both the TCP sessions and the UDP discovery probes. Different
// protocols, so one number is enough and one is easier to remember.
const DefaultPort = 9103

// Set by the release build: -ldflags "-X main.version=..." . A build from a
// working tree says so rather than claiming a version it is not.
var version = "dev"

var (
	flagPort    = flag.Int("port", DefaultPort, "TCP and UDP port to serve on")
	flagCwd     = flag.String("cwd", "", "where new shells start (default: home)")
	flagShell   = flag.String("shell", "", "shell to run (default: $SHELL)")
	flagName    = flag.String("name", "", "name shown in the headset (default: hostname)")
	flagBind    = flag.String("bind", "0.0.0.0", "address to listen on")
	flagDump    = flag.String("dump-context", "", "print the context for a directory and exit")
	flagQuiet   = flag.Bool("quiet", false, "log sessions only, not every connection detail")
	flagVersion = flag.Bool("version", false, "print the version and exit")

	// Dictation. The headset records; this machine transcribes, so the key never
	// leaves it. Any OpenAI-compatible endpoint works — the official one, or a
	// Whisper you run yourself.
	flagASRURL      = flag.String("asr-url", "", "transcription endpoint (default: OpenAI when a key is set)")
	flagASRModel    = flag.String("asr-model", "", "transcription model (default: whisper-1)")
	flagASRLanguage = flag.String("asr-language", "", "language hint, e.g. ru — improves accuracy noticeably")

	// Off by default, and bound to localhost when on: the console can close
	// sessions and set the transcription key, and has no authentication. Reach it
	// from elsewhere with an ssh tunnel, not by widening the bind address.
	flagHTTP     = flag.Int("http", 0, "serve the web console on this port (0 disables it)")
	flagHTTPBind = flag.String("http-bind", "127.0.0.1", "address the web console listens on")
)

func main() {
	flag.Parse()
	log.SetFlags(log.Ltime)

	if *flagVersion {
		fmt.Println(version)
		return
	}

	home, _ := os.UserHomeDir()

	if *flagDump != "" {
		dumpContext(*flagDump)
		return
	}

	cwd := *flagCwd
	if cwd == "" {
		cwd = home
	}
	if expanded, err := filepath.Abs(expandHome(cwd, home)); err == nil {
		cwd = expanded
	}

	shell := *flagShell
	if shell == "" {
		shell = os.Getenv("SHELL")
	}
	if shell == "" {
		shell = "/bin/bash"
	}

	name := *flagName
	if name == "" {
		name, _ = os.Hostname()
	}

	config := loadConfig()
	server := &Server{
		Shell: shell, Cwd: cwd, Name: name, Port: *flagPort,
		Quiet: *flagQuiet, ASR: config.ASR, Started: time.Now(),
	}

	if server.ASR.configured() {
		log.Printf("dictation via %s (%s)", hostOf(server.ASR.URL), server.ASR.Model)
	} else {
		log.Printf("dictation is off — no endpoint configured, see docs/voice.md")
	}

	go server.serveDiscovery(*flagBind)
	if *flagHTTP > 0 {
		go server.serveHTTP(fmt.Sprintf("%s:%d", *flagHTTPBind, *flagHTTP))
	}

	address := fmt.Sprintf("%s:%d", *flagBind, *flagPort)
	listener, err := net.Listen("tcp", address)
	if err != nil {
		log.Fatalf("cannot listen on %s: %v", address, err)
	}
	log.Printf("%s %s on %s, shells start in %s (%s)", name, version, address, cwd, shell)

	for {
		conn, err := listener.Accept()
		if err != nil {
			log.Printf("accept failed: %v", err)
			continue
		}
		if tcp, ok := conn.(*net.TCPConn); ok {
			tcp.SetNoDelay(true)
		}
		go server.serveSession(conn)
	}
}

// Server is what every session shares: how to start a shell, what to call itself,
// and where to send audio.
type Server struct {
	Shell   string
	Cwd     string
	Name    string
	Port    int
	Quiet   bool
	ASR     ASR
	Started time.Time

	mu       sync.Mutex
	sessions []*Session
	nextID   int
}

func (s *Server) track(session *Session) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.nextID++
	session.ID = s.nextID
	s.sessions = append(s.sessions, session)
}

func (s *Server) untrack(session *Session) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, known := range s.sessions {
		if known == session {
			s.sessions = append(s.sessions[:i], s.sessions[i+1:]...)
			return
		}
	}
}

func (s *Server) liveSessions() []*Session {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]*Session, len(s.sessions))
	copy(out, s.sessions)
	return out
}

// hostOf is for logging an endpoint without repeating a path that might carry
// anything sensitive.
func hostOf(raw string) string {
	if parsed, err := url.Parse(raw); err == nil && parsed.Host != "" {
		return parsed.Host
	}
	return raw
}

func expandHome(path, home string) string {
	if path == "~" {
		return home
	}
	if strings.HasPrefix(path, "~/") {
		return filepath.Join(home, path[2:])
	}
	return path
}

func currentUser() string {
	if u, err := user.Current(); err == nil {
		return u.Username
	}
	return os.Getenv("USER")
}

// osRelease reads PRETTY_NAME so the headset can tell two machines apart by more
// than their hostname.
func osRelease() string {
	data, err := os.ReadFile("/etc/os-release")
	if err != nil {
		return "linux"
	}
	for _, line := range strings.Split(string(data), "\n") {
		if strings.HasPrefix(line, "PRETTY_NAME=") {
			return strings.Trim(strings.TrimPrefix(line, "PRETTY_NAME="), `"`)
		}
	}
	return "linux"
}
