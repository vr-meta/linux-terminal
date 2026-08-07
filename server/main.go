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
	"os"
	"os/user"
	"path/filepath"
	"strings"
)

// Port carries both the TCP sessions and the UDP discovery probes. Different
// protocols, so one number is enough and one is easier to remember.
const DefaultPort = 9103

var (
	flagPort  = flag.Int("port", DefaultPort, "TCP and UDP port to serve on")
	flagCwd   = flag.String("cwd", "", "where new shells start (default: home)")
	flagShell = flag.String("shell", "", "shell to run (default: $SHELL)")
	flagName  = flag.String("name", "", "name shown in the headset (default: hostname)")
	flagBind  = flag.String("bind", "0.0.0.0", "address to listen on")
	flagDump  = flag.String("dump-context", "", "print the context for a directory and exit")
	flagQuiet = flag.Bool("quiet", false, "log sessions only, not every connection detail")
)

func main() {
	flag.Parse()
	log.SetFlags(log.Ltime)

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

	server := &Server{Shell: shell, Cwd: cwd, Name: name, Port: *flagPort, Quiet: *flagQuiet}

	go server.serveDiscovery(*flagBind)

	address := fmt.Sprintf("%s:%d", *flagBind, *flagPort)
	listener, err := net.Listen("tcp", address)
	if err != nil {
		log.Fatalf("cannot listen on %s: %v", address, err)
	}
	log.Printf("%s on %s, shells start in %s (%s)", name, address, cwd, shell)

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

// Server is what every session shares: how to start a shell and what to call itself.
type Server struct {
	Shell string
	Cwd   string
	Name  string
	Port  int
	Quiet bool
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
