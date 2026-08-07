package main

import (
	"encoding/binary"
	"encoding/json"
	"io"
	"log"
	"net"
	"os"
	"os/exec"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"github.com/creack/pty"
)

// Wire protocol. One TCP connection is one terminal session; every frame is
//
//	type (1 byte) | length (4 bytes, big endian) | payload
const (
	msgData    = 0x01 // client -> server: raw bytes for the pty
	msgResize  = 0x02 // client -> server: {"cols":N,"rows":N}
	msgRequest = 0x03 // client -> server: {"op":...}
	msgAudio   = 0x04 // client -> server: 4-byte JSON length, JSON header, then PCM
	msgOut     = 0x81 // server -> client: raw bytes from the pty
	msgContext = 0x82 // server -> client: context JSON
	msgExit    = 0x83 // server -> client: {"code":N}
	msgSpeech  = 0x84 // server -> client: {"text":...} or {"error":...}
)

// How often the foreground process is re-read. Cheap — two /proc reads — and the
// listings behind it are recomputed only when the answer changes.
const pollInterval = 250 * time.Millisecond

// Session is one pty, one client, and the context that follows them.
type Session struct {
	ID     int
	server *Server
	conn   net.Conn

	Since  time.Time
	Remote string

	ptmx *os.File
	cmd  *exec.Cmd

	writeMu sync.Mutex

	// Cached so the 4 Hz poll costs two /proc reads and nothing else.
	lastCwd  string
	lastTool string
	cache    *listings
}

func (s *Server) serveSession(conn net.Conn) {
	log.Printf("session from %s", conn.RemoteAddr())
	session := &Session{
		server: s, conn: conn,
		Since: time.Now(), Remote: conn.RemoteAddr().String(),
	}
	s.track(session)
	defer func() {
		s.untrack(session)
		session.close()
		log.Printf("session from %s closed", conn.RemoteAddr())
	}()

	if err := session.start(); err != nil {
		log.Printf("cannot start a shell: %v", err)
		return
	}
	session.run()
}

// start forks the shell on a pty of its own.
func (s *Session) start() error {
	cmd := exec.Command(s.server.Shell)
	cmd.Dir = s.server.Cwd
	if _, err := os.Stat(cmd.Dir); err != nil {
		home, _ := os.UserHomeDir()
		cmd.Dir = home
	}
	cmd.Env = shellEnv()

	ptmx, err := pty.Start(cmd)
	if err != nil {
		return err
	}
	s.ptmx = ptmx
	s.cmd = cmd
	s.resize(200, 50)
	return nil
}

// shellEnv is the environment a terminal on this machine should have.
//
// Not a login shell and not a stripped one: on a pty the shell is interactive
// already and reads .bashrc, where the prompt and the aliases live.
func shellEnv() []string {
	env := make([]string, 0, len(os.Environ())+3)
	for _, entry := range os.Environ() {
		name := entry
		if i := strings.IndexByte(entry, '='); i >= 0 {
			name = entry[:i]
		}
		switch {
		case name == "TERM" || name == "COLORTERM" || name == "LINES" || name == "COLUMNS":
			continue
		// This server is often started from a terminal that is itself inside a
		// Claude Code session, which exports markers making the next `claude`
		// behave as a child of it: transcript saving off, a warning on the
		// banner. A shell in the headset is not a child of anything.
		case name == "CLAUDECODE" || strings.HasPrefix(name, "CLAUDE_CODE_"):
			continue
		}
		env = append(env, entry)
	}
	// The client renders 256 colours and knows nothing about terminfo it was not
	// told; xterm-256color is the common ground its emulator was written against.
	return append(env,
		"TERM=xterm-256color",
		"COLORTERM=truecolor",
		"LINUXVR_TERM=1",
	)
}

func (s *Session) resize(cols, rows int) {
	if s.ptmx == nil {
		return
	}
	cols = clamp(cols, 20, 500)
	rows = clamp(rows, 4, 200)
	_ = pty.Setsize(s.ptmx, &pty.Winsize{Rows: uint16(rows), Cols: uint16(cols)})
}

func clamp(value, low, high int) int {
	if value < low {
		return low
	}
	if value > high {
		return high
	}
	return value
}

// ---------------------------------------------------------------------- frames

func (s *Session) send(kind byte, payload []byte) {
	header := make([]byte, 5)
	header[0] = kind
	binary.BigEndian.PutUint32(header[1:], uint32(len(payload)))

	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	if _, err := s.conn.Write(header); err != nil {
		return
	}
	_, _ = s.conn.Write(payload)
}

func (s *Session) sendJSON(kind byte, value any) {
	payload, err := json.Marshal(value)
	if err != nil {
		return
	}
	s.send(kind, payload)
}

// ------------------------------------------------------------------- the loop

func (s *Session) run() {
	done := make(chan struct{})

	// The pty is read on its own goroutine: a read blocks until the shell says
	// something, which may be minutes.
	go func() {
		defer close(done)
		buffer := make([]byte, 65536)
		for {
			n, err := s.ptmx.Read(buffer)
			if n > 0 {
				payload := make([]byte, n)
				copy(payload, buffer[:n])
				s.send(msgOut, payload)
			}
			if err != nil {
				return
			}
		}
	}()

	go s.pollContext(done)

	if err := s.readClient(); err != nil && err != io.EOF {
		log.Printf("client gone: %v", err)
	}

	code := 0
	if s.cmd != nil {
		if state, err := s.cmd.Process.Wait(); err == nil {
			code = state.ExitCode()
		}
	}
	s.sendJSON(msgExit, map[string]int{"code": code})
}

func (s *Session) readClient() error {
	header := make([]byte, 5)
	for {
		if _, err := io.ReadFull(s.conn, header); err != nil {
			return err
		}
		kind := header[0]
		length := binary.BigEndian.Uint32(header[1:])
		if length > 8*1024*1024 {
			return io.ErrUnexpectedEOF
		}
		payload := make([]byte, length)
		if _, err := io.ReadFull(s.conn, payload); err != nil {
			return err
		}
		s.handle(kind, payload)
	}
}

func (s *Session) handle(kind byte, payload []byte) {
	switch kind {
	case msgData:
		_, _ = s.ptmx.Write(payload)

	case msgResize:
		var size struct {
			Cols int `json:"cols"`
			Rows int `json:"rows"`
		}
		if json.Unmarshal(payload, &size) == nil {
			s.resize(size.Cols, size.Rows)
		}

	case msgAudio:
		// Recognition takes seconds and must not stall the pty behind it.
		go s.transcribe(payload)

	case msgRequest:
		var request struct {
			Op   string `json:"op"`
			Name string `json:"name"`
		}
		if json.Unmarshal(payload, &request) != nil {
			return
		}
		switch request.Op {
		case "refresh":
			s.pushContext(true)
		case "signal":
			s.signal(request.Name)
		}
	}
}

// transcribe: [4-byte JSON length][JSON header][raw PCM]. One frame rather than
// two, so a client that dies mid-upload cannot leave a half-read stream behind.
func (s *Session) transcribe(payload []byte) {
	if len(payload) < 4 {
		return
	}
	headerLength := int(binary.BigEndian.Uint32(payload[:4]))
	if headerLength < 0 || 4+headerLength > len(payload) {
		return
	}
	var header struct {
		Rate     int `json:"rate"`
		Channels int `json:"channels"`
	}
	if json.Unmarshal(payload[4:4+headerLength], &header) != nil {
		return
	}
	if header.Rate <= 0 {
		header.Rate = 16000
	}
	if header.Channels <= 0 {
		header.Channels = 1
	}
	pcm := payload[4+headerLength:]

	seconds := float64(len(pcm)) / float64(header.Rate*header.Channels*2)
	log.Printf("dictation: %.1fs from %s", seconds, s.Remote)
	if seconds < 0.2 {
		s.sendJSON(msgSpeech, map[string]string{"error": "too short to be speech"})
		return
	}

	text, err := s.server.ASR.transcribe(pcm, header.Rate, header.Channels)
	if err != nil {
		log.Printf("dictation failed: %v", err)
		s.sendJSON(msgSpeech, map[string]string{"error": err.Error()})
		return
	}
	s.sendJSON(msgSpeech, map[string]string{"text": text})
}

func (s *Session) signal(name string) {
	numbers := map[string]syscall.Signal{
		"int": syscall.SIGINT, "term": syscall.SIGTERM,
		"quit": syscall.SIGQUIT, "hup": syscall.SIGHUP,
	}
	number, ok := numbers[name]
	if !ok {
		return
	}
	if pgid, err := s.foreground(); err == nil && pgid > 0 {
		_ = syscall.Kill(-pgid, number)
	}
}

func (s *Session) pollContext(done <-chan struct{}) {
	s.pushContext(true)
	ticker := time.NewTicker(pollInterval)
	defer ticker.Stop()
	for {
		select {
		case <-done:
			return
		case <-ticker.C:
			s.pushContext(false)
		}
	}
}

func (s *Session) close() {
	if s.cmd != nil && s.cmd.Process != nil {
		_ = s.cmd.Process.Signal(syscall.SIGHUP)
	}
	if s.ptmx != nil {
		_ = s.ptmx.Close()
	}
	_ = s.conn.Close()
}

// foreground returns the process group in front of the pty — read, not guessed.
func (s *Session) foreground() (int, error) {
	var pgrp int32
	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, s.ptmx.Fd(),
		syscall.TIOCGPGRP, uintptr(unsafe.Pointer(&pgrp)))
	if errno != 0 {
		return 0, errno
	}
	return int(pgrp), nil
}
