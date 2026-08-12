package main

import (
	"bytes"
	"fmt"
	"log"
	"net"
	"os"
	"os/exec"
	"strings"
	"time"

	"fyne.io/systray"
	"github.com/godbus/dbus/v5"
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

// The name a desktop takes on the session bus when it is willing to host tray
// icons. GNOME's AppIndicator extension owns it; so does KDE, and so does any
// other implementation of the same specification.
const trayWatcher = "org.kde.StatusNotifierWatcher"

// trayPossible reports whether there is a session bus at all — which is the one
// thing that cannot appear later. Without a bus the library would wait for an
// answer that will never come.
func trayPossible() bool {
	return os.Getenv("DBUS_SESSION_BUS_ADDRESS") != ""
}

// awaitTrayHost blocks until a desktop is willing to hold an icon, and reports
// false only if the bus itself cannot be used.
//
// This replaced a check for DISPLAY or WAYLAND_DISPLAY, and the difference is
// the whole bug. A user service is started by `systemd --user` at login, which
// is BEFORE the graphical session has imported those variables into it — so the
// server's own environment has neither, forever, no matter how many screens are
// plugged in. `systemctl --user show-environment` on this machine lists
// WAYLAND_DISPLAY while /proc/<pid>/environ of a server started three days ago
// does not, which is exactly that ordering. The old check therefore answered
// "no desktop here" on a desktop machine and the icon was never even attempted.
//
// Asking the bus is both correct and later-proof: the question is not whether an
// X server exists, it is whether anything is offering to show an icon, and that
// is a name on the bus. Waiting for it costs nothing — this runs on a goroutine
// that would otherwise be blocked inside systray anyway — and it means the icon
// appears at login on a machine where the server was started before there was a
// session, which is the ordinary case for a service enabled with `WantedBy=
// default.target`.
func awaitTrayHost() bool {
	conn, err := dbus.SessionBusPrivate()
	if err != nil {
		log.Printf("tray: no session bus: %v", err)
		return false
	}
	// Private, not shared: systray opens its own connection and closing a shared
	// one under it would take the icon with it.
	defer conn.Close()
	if err := conn.Auth(nil); err != nil {
		log.Printf("tray: session bus refused us: %v", err)
		return false
	}
	if err := conn.Hello(); err != nil {
		log.Printf("tray: session bus refused us: %v", err)
		return false
	}

	if trayHosted(conn) {
		return true
	}

	signals := make(chan *dbus.Signal, 8)
	conn.Signal(signals)
	if err := conn.AddMatchSignal(
		dbus.WithMatchObjectPath("/org/freedesktop/DBus"),
		dbus.WithMatchInterface("org.freedesktop.DBus"),
		dbus.WithMatchSender("org.freedesktop.DBus"),
		dbus.WithMatchMember("NameOwnerChanged"),
		dbus.WithMatchArg(0, trayWatcher),
	); err != nil {
		log.Printf("tray: cannot watch for a desktop: %v", err)
		return false
	}

	// Asked again after subscribing, because a desktop that arrived between the
	// first question and the match rule would otherwise never be noticed.
	if trayHosted(conn) {
		return true
	}

	log.Printf("tray: waiting for a desktop to offer a tray")
	for signal := range signals {
		// NameOwnerChanged carries [name, old owner, new owner]; a non-empty new
		// owner is somebody taking the name.
		if len(signal.Body) < 3 {
			continue
		}
		if owner, ok := signal.Body[2].(string); ok && owner != "" {
			return true
		}
	}
	return false
}

func trayHosted(conn *dbus.Conn) bool {
	var owned bool
	err := conn.BusObject().Call("org.freedesktop.DBus.NameHasOwner", 0, trayWatcher).
		Store(&owned)
	return err == nil && owned
}

// runTray blocks — systray owns the goroutine it is given.
func (s *Server) runTray() {
	systray.Run(s.trayReady, func() {})
}

func (s *Server) trayReady() {
	state := s.trayStatus()

	systray.SetIcon(trayIconPNG())
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
