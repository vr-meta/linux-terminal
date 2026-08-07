package main

import (
	"bytes"
	_ "embed"
	"fmt"
	"log"
	"net"
	"os"
	"os/exec"
	"strings"
	"time"

	"fyne.io/systray"
)

// An indicator in the desktop's tray, for when the server runs on a machine you
// are sitting at. The server is otherwise invisible — a user service with no
// window — and "is it running, and is anyone connected" should not need a
// terminal to answer. The icon answers the first by existing, the menu answers
// the rest.
//
// This has been written three times and the history is worth keeping, because
// two of the three conclusions were wrong.
//
// The first version used slytomcat/systray and froze the desktop four times,
// hard enough to need a reset each time. The second blamed the menu — GNOME
// re-reads a dbusmenu whenever it changes — and threw it away, publishing a
// bare item with no menu at all over raw godbus. That was wrong: the sibling
// project linux-vr has a tray on this same desktop, with a nine-item menu it
// repaints on a one-second tick, and it has never frozen anything.
//
// The difference was the library, not the menu. So this is the third version:
// fyne.io/systray, the one that demonstrably works here, with the two habits
// that sibling proved out —
//
//	items are allocated ONCE and hidden when unused, never rebuilt, and
//	nothing is repainted unless the state would actually read differently.
//
// A menu rebuilt per change grows without bound, since systray cannot remove an
// item; a menu repainted per tick is the flood that started all this.
//
// fyne.io/systray is pure Go on Linux — cgo only on macOS, which this never
// builds for — so the static binary and the arm64 cross-build survive.

//go:embed web/tray.png
var trayIcon []byte

// Where this came from. In the menu because a machine running a server someone
// installed six months ago should be able to say where to read about it.
const repoURL = "https://github.com/vr-meta/linux-terminal"

// How many connections the menu lists. Past a handful of lines a menu stops
// being glanceable, and the console is one click away.
const trayMaxSessions = 6

// trayState is everything the icon and menu show. Comparing two of these is how
// the tray stays silent while nothing is happening.
type trayState struct {
	Address  string
	Sessions []string
}

func (s *Server) trayStatus() trayState {
	sessions := s.liveSessions()
	state := trayState{Address: fmt.Sprintf("%s:%d", localAddress(), s.Port)}
	for _, session := range sessions {
		state.Sessions = append(state.Sessions, describe(session))
	}
	return state
}

// trayAvailable reports whether there is a desktop to put an icon on. Without
// this the library waits for a bus that will never answer.
func trayAvailable() bool {
	return os.Getenv("DBUS_SESSION_BUS_ADDRESS") != "" &&
		(os.Getenv("WAYLAND_DISPLAY") != "" || os.Getenv("DISPLAY") != "")
}

// runTray blocks — systray owns the goroutine it is given.
func (s *Server) runTray() {
	systray.Run(s.trayReady, func() {})
}

func (s *Server) trayReady() {
	state := s.trayStatus()

	systray.SetIcon(trayIcon)
	systray.SetTitle("")
	systray.SetTooltip(trayTooltip(state))

	console := systray.AddMenuItem("Console and settings…", "sessions and dictation, in a browser")
	if s.HTTPPort == 0 {
		console.SetTitle("Console is off (--http)")
		console.Disable()
	}

	systray.AddSeparator()

	// The lines that report rather than offer. Allocated once and hidden when
	// unused: systray cannot remove an item, so a menu rebuilt on every change
	// grows without bound.
	heading := systray.AddMenuItem("", "")
	heading.Disable()
	lines := make([]*systray.MenuItem, trayMaxSessions)
	for i := range lines {
		lines[i] = systray.AddMenuItem("", "")
		lines[i].Disable()
		lines[i].Hide()
	}
	more := systray.AddMenuItem("", "")
	more.Disable()
	more.Hide()

	systray.AddSeparator()
	copyAddress := systray.AddMenuItem("Copy the address",
		"put it in the clipboard for typing into a headset")
	project := systray.AddMenuItem("linux-terminal "+version+" on GitHub", repoURL)

	systray.AddSeparator()
	quit := systray.AddMenuItem("Stop the server", "closes every session")

	go func() {
		for {
			select {
			case <-console.ClickedCh:
				trayOpen(fmt.Sprintf("http://127.0.0.1:%d", s.HTTPPort))
			case <-copyAddress.ClickedCh:
				trayClipboard(s.trayStatus().Address)
			case <-project.ClickedCh:
				trayOpen(repoURL)
			case <-quit.ClickedCh:
				log.Printf("stopped from the tray")
				systray.Quit()
				os.Exit(0)
			}
		}
	}()

	go s.trayFollow(state, heading, lines, more)
}

// trayFollow repaints when the state changes and does nothing when it does not.
//
// A second is the right period: this is a status light, not an instrument, and
// nothing it reports changes faster than a headset connecting.
func (s *Server) trayFollow(previous trayState, heading *systray.MenuItem,
	lines []*systray.MenuItem, more *systray.MenuItem) {

	trayPaint(previous, heading, lines, more)
	for range time.Tick(time.Second) {
		current := s.trayStatus()
		if traySame(previous, current) {
			continue
		}
		systray.SetTooltip(trayTooltip(current))
		trayPaint(current, heading, lines, more)
		previous = current
	}
}

func trayPaint(state trayState, heading *systray.MenuItem,
	lines []*systray.MenuItem, more *systray.MenuItem) {

	switch len(state.Sessions) {
	case 0:
		heading.SetTitle(state.Address + "   ·   nobody connected")
	case 1:
		heading.SetTitle(state.Address + "   ·   1 connection")
	default:
		heading.SetTitle(fmt.Sprintf("%s   ·   %d connections", state.Address, len(state.Sessions)))
	}

	for i, line := range lines {
		if i >= len(state.Sessions) {
			line.Hide()
			continue
		}
		line.SetTitle("   " + state.Sessions[i])
		line.Show()
	}

	if extra := len(state.Sessions) - len(lines); extra > 0 {
		more.SetTitle(fmt.Sprintf("   and %d more — see the console", extra))
		more.Show()
	} else {
		more.Hide()
	}
}

func traySame(a, b trayState) bool {
	if a.Address != b.Address || len(a.Sessions) != len(b.Sessions) {
		return false
	}
	for i := range a.Sessions {
		if a.Sessions[i] != b.Sessions[i] {
			return false
		}
	}
	return true
}

func trayTooltip(state trayState) string {
	parts := []string{"linux-terminal  " + state.Address}
	if len(state.Sessions) == 0 {
		parts = append(parts, "nobody connected")
	} else {
		parts = append(parts, state.Sessions...)
	}
	return strings.Join(parts, "\n")
}

// describe is one line about a session: where it is from, where it is, and what
// it is running. Enough to recognise which of your own windows it is.
func describe(session *Session) string {
	host := session.Remote
	if index := strings.LastIndex(host, ":"); index > 0 {
		host = host[:index]
	}
	where := short(session.lastCwd)
	if where == "" {
		where = "?"
	}
	tool := session.lastTool
	if tool == "" {
		tool = "shell"
	}
	return fmt.Sprintf("%s · %s · %s", host, where, tool)
}

// localAddress is the address a headset would type. The route to a public
// address is asked for rather than resolved, which is how the interface that
// actually carries traffic is picked without listing them all.
func localAddress() string {
	conn, err := net.Dial("udp", "8.8.8.8:80")
	if err != nil {
		return "this machine"
	}
	defer conn.Close()
	if addr, ok := conn.LocalAddr().(*net.UDPAddr); ok {
		return addr.IP.String()
	}
	return "this machine"
}

func trayOpen(url string) {
	// xdg-open rather than a browser by name: which browser is the user's
	// business, and the desktop already knows the answer.
	if err := exec.Command("xdg-open", url).Start(); err != nil {
		log.Printf("tray: cannot open %s: %v", url, err)
	}
}

func trayClipboard(text string) {
	for _, candidate := range [][]string{{"wl-copy"}, {"xclip", "-selection", "clipboard"}} {
		cmd := exec.Command(candidate[0], candidate[1:]...)
		cmd.Stdin = bytes.NewReader([]byte(text))
		if err := cmd.Run(); err == nil {
			return
		}
	}
	log.Printf("tray: cannot copy the address — neither wl-copy nor xclip worked")
}
