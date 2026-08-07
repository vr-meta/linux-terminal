package main

import (
	_ "embed"
	"fmt"
	"log"
	"os"
	"os/exec"
	"strings"
	"time"

	"github.com/slytomcat/systray"
)

// An indicator in the desktop's tray, for when the server runs on a machine you
// are sitting at.
//
// The server is otherwise invisible: a user service with no window, and "is it
// running, and is anyone connected" is a question that should not need a
// terminal to answer. The icon answers it by existing; its tooltip and menu
// answer the rest.
//
// On GNOME this is a StatusNotifierItem over D-Bus, which the Ubuntu
// AppIndicator extension renders. The library is pure Go, so the binary stays
// static and CGO stays off — a tray icon is not worth giving that up for.
//
// On a machine with no session bus — a VPS, a container, anything headless —
// there is nothing to attach to, and the server says so once and carries on.

//go:embed web/tray.png
var trayIcon []byte

// How many connections the menu lists before it stops. A menu is not a console;
// past a handful of lines it stops being glanceable, and the console is one
// click away.
const trayMaxSessions = 6

func (s *Server) runTray() {
	systray.Run(s.trayReady, func() {})
}

// trayAvailable reports whether there is a desktop to put an icon on. Without
// this the library waits for a bus that will never answer.
func trayAvailable() bool {
	return os.Getenv("DBUS_SESSION_BUS_ADDRESS") != "" &&
		(os.Getenv("WAYLAND_DISPLAY") != "" || os.Getenv("DISPLAY") != "")
}

func (s *Server) trayReady() {
	systray.SetIcon(trayIcon)
	systray.SetTitle("")
	systray.SetTooltip("linux-terminal")

	heading := systray.AddMenuItem(fmt.Sprintf("linux-terminal %s", version), "")
	heading.Disable()
	address := systray.AddMenuItem(fmt.Sprintf("%s:%d", s.Name, s.Port), "the address the headset connects to")
	address.Disable()

	systray.AddSeparator()

	console := systray.AddMenuItem("Open the console", "sessions and dictation settings")
	if s.HTTPPort == 0 {
		console.SetTitle("Console is off (--http)")
		console.Disable()
	}

	systray.AddSeparator()

	// Slots, created once and shown or hidden as sessions come and go. Rebuilding
	// a menu while it is open is how items end up being clicked after they moved.
	summary := systray.AddMenuItem("No connections", "")
	summary.Disable()
	slots := make([]*systray.MenuItem, trayMaxSessions)
	for i := range slots {
		slots[i] = systray.AddMenuItem("", "")
		slots[i].Disable()
		slots[i].Hide()
	}
	more := systray.AddMenuItem("", "")
	more.Disable()
	more.Hide()

	systray.AddSeparator()
	quit := systray.AddMenuItem("Stop the server", "closes every session")

	go func() {
		for {
			select {
			case <-console.ClickedCh:
				s.openConsole()
			case <-quit.ClickedCh:
				log.Printf("stopped from the tray")
				systray.Quit()
				os.Exit(0)
			}
		}
	}()

	go s.trayRefresh(summary, slots, more)
}

func (s *Server) trayRefresh(summary *systray.MenuItem, slots []*systray.MenuItem, more *systray.MenuItem) {
	for {
		sessions := s.liveSessions()

		switch len(sessions) {
		case 0:
			summary.SetTitle("No connections")
			systray.SetTooltip("linux-terminal — nobody connected")
		case 1:
			summary.SetTitle("1 connection")
			systray.SetTooltip("linux-terminal — 1 connection")
		default:
			summary.SetTitle(fmt.Sprintf("%d connections", len(sessions)))
			systray.SetTooltip(fmt.Sprintf("linux-terminal — %d connections", len(sessions)))
		}

		for i, slot := range slots {
			if i >= len(sessions) {
				slot.Hide()
				continue
			}
			slot.SetTitle("   " + describe(sessions[i]))
			slot.Show()
		}
		if len(sessions) > len(slots) {
			more.SetTitle(fmt.Sprintf("   and %d more — see the console", len(sessions)-len(slots)))
			more.Show()
		} else {
			more.Hide()
		}

		time.Sleep(2 * time.Second)
	}
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

// openConsole hands the URL to the desktop rather than choosing a browser. On a
// machine with a tray there is always something behind xdg-open.
func (s *Server) openConsole() {
	url := fmt.Sprintf("http://127.0.0.1:%d", s.HTTPPort)
	if err := exec.Command("xdg-open", url).Start(); err != nil {
		log.Printf("cannot open %s: %v", url, err)
	}
}
