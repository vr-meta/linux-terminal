package main

import (
	"encoding/json"
	"log"
	"net"
	"os"
	"path/filepath"
	"strings"
)

// Discovery: the headset asks who is out there, and servers answer.
//
// A UDP broadcast rather than mDNS, on purpose. mDNS would mean avahi running on
// the host and NsdManager on the client — two moving parts and two failure modes
// for a question with one line in it. This is a probe and a reply, it works on a
// machine with nothing installed, and a server outside the network is reached by
// typing its address, which is what that case needs anyway.
const (
	probePrefix  = "LINUXVR-DISCOVER"
	discoveryTag = "linux-vr"
)

// Announcement is what a server says about itself. Deliberately small: this is a
// datagram, and everything here has to fit in one.
// What a stranger is told, and nothing more.
//
// This answers an unauthenticated broadcast from anyone on the network, so it
// carries only what a headset needs in order to draw a card and decide whether
// to pair: which protocol, which machine, which port. It used to include the
// login name, the operating system and the working directory — a description of
// a machine and its user, handed to whoever asked first.
//
// Everything else now arrives after the client has proved who it is, over the
// session, where it belongs.
type Announcement struct {
	Proto   string `json:"proto"`
	Version int    `json:"version"`
	Name    string `json:"name"`
	Port    int    `json:"port"`
	Release string `json:"release"`
}

func (s *Server) serveDiscovery(bind string) {
	address := &net.UDPAddr{IP: net.ParseIP(bind), Port: s.Port}
	conn, err := net.ListenUDP("udp4", address)
	if err != nil {
		// Not fatal: a server that cannot be discovered can still be typed in.
		log.Printf("discovery unavailable on udp/%d: %v", s.Port, err)
		return
	}
	defer conn.Close()

	reply, err := json.Marshal(Announcement{
		Proto: discoveryTag, Version: 2, Name: s.Name, Port: s.Port, Release: version,
	})
	if err != nil {
		return
	}

	log.Printf("answering discovery probes on udp/%d", s.Port)
	buffer := make([]byte, 512)
	for {
		n, from, err := conn.ReadFromUDP(buffer)
		if err != nil {
			return
		}
		if !strings.HasPrefix(string(buffer[:n]), probePrefix) {
			continue
		}
		if _, err := conn.WriteToUDP(reply, from); err != nil {
			log.Printf("cannot answer %s: %v", from, err)
		} else if !s.Quiet {
			log.Printf("discovery probe from %s", from.IP)
		}
	}
}

// dumpContext prints what the client would receive for a directory, so the bar can
// be inspected without a headset in reach.
func dumpContext(dir string) {
	home, _ := os.UserHomeDir()
	cwd := expandHome(dir, home)
	if absolute, err := filepath.Abs(cwd); err == nil {
		cwd = absolute
	}
	root := projectRoot(cwd)
	ctx := &Context{
		Cwd: cwd, CwdLabel: short(cwd), Home: home, Up: filepath.Dir(cwd),
		ToolName: "shell", Root: short(root), Git: gitHead(cwd),
		Dirs: subdirs(cwd), Projects: favorites(), Skills: skills(root),
	}
	ctx.Groups = groupsFor(ctx)

	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	_ = encoder.Encode(ctx)
}
