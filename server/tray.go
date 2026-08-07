package main

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"time"

	"github.com/godbus/dbus/v5"
	"github.com/godbus/dbus/v5/introspect"
	"github.com/godbus/dbus/v5/prop"
)

// An indicator in the desktop's tray, for when the server runs on a machine you
// are sitting at. The server is otherwise invisible — a user service with no
// window — and "is it running, and is anyone connected" should not need a
// terminal to answer.
//
// This is a StatusNotifierItem spoken directly over D-Bus, and the shape is
// copied deliberately from a tray app on this same desktop that has run for
// months without incident. The shape is the whole point:
//
//	ONE object, /StatusNotifierItem. NO com.canonical.dbusmenu anywhere.
//
// The first version used a wrapper library that exported a ten-item menu and
// emitted LayoutUpdated whenever any of it changed. GNOME answers that signal by
// re-reading the entire menu, on the one thread its JavaScript runs on, and the
// desktop froze hard enough to need a reset — four times, including once with
// the refresh rate fixed, so the rate was never the problem. The working
// neighbour has no menu at all: Menu points at a path that does not exist and
// ItemIsMenu is true, which leaves the host nothing to walk.
//
// So there is no menu here, and there is nothing a menu was doing that is now
// missing. A click opens the console, which already lists the sessions and owns
// the settings. State the icon can carry on its own goes in the tooltip.
//
// CGO stays off: godbus is pure Go, which is why the binary is still one file.

const (
	trayPath    = dbus.ObjectPath("/StatusNotifierItem")
	trayIface   = "org.kde.StatusNotifierItem"
	watcherName = "org.kde.StatusNotifierWatcher"
	watcherPath = dbus.ObjectPath("/StatusNotifierWatcher")

	// A themed name rather than pixmap bytes we would have to decode and hand
	// over. Every icon theme ships this one, and a wrong pixmap is a class of
	// bug this file is in no position to afford.
	trayIconName = "utilities-terminal"
)

// trayTooltip is the SNI ToolTip type, (sa(iiay)ss): an icon name, pixmaps we
// do not supply, a title and a body.
type trayTooltip struct {
	IconName    string
	Pixmaps     []trayPixmap
	Title       string
	Description string
}

type trayPixmap struct {
	Width  int32
	Height int32
	Data   []byte
}

// trayItem answers the clicks. Every one of them opens the console, because
// every one of them used to open a menu whose only useful entry did that.
type trayItem struct {
	server *Server
}

func (t *trayItem) Activate(x, y int32) *dbus.Error {
	t.server.openConsole()
	return nil
}

func (t *trayItem) SecondaryActivate(x, y int32) *dbus.Error {
	t.server.openConsole()
	return nil
}

func (t *trayItem) ContextMenu(x, y int32) *dbus.Error {
	t.server.openConsole()
	return nil
}

func (t *trayItem) Scroll(delta int32, orientation string) *dbus.Error {
	return nil
}

// trayAvailable reports whether there is a desktop to put an icon on. Without
// this the item waits for a bus that will never answer.
func trayAvailable() bool {
	return os.Getenv("DBUS_SESSION_BUS_ADDRESS") != "" &&
		(os.Getenv("WAYLAND_DISPLAY") != "" || os.Getenv("DISPLAY") != "")
}

// runTray exports the item and, unless this is a self-test, tells the watcher it
// exists. It returns after that: unlike the library it replaces, nothing here
// needs to own a goroutine forever.
func (s *Server) runTray(register bool) error {
	conn, err := dbus.ConnectSessionBus()
	if err != nil {
		return fmt.Errorf("session bus: %w", err)
	}

	item := &trayItem{server: s}
	if err := conn.Export(item, trayPath, trayIface); err != nil {
		return fmt.Errorf("export item: %w", err)
	}

	tooltip := trayTooltip{
		IconName:    trayIconName,
		Title:       "linux-terminal",
		Description: "nobody connected",
	}

	// Menu names a path that is never exported, and ItemIsMenu says the click is
	// ours to handle. This is exactly what the neighbour that never froze does.
	table := map[string]map[string]*prop.Prop{
		trayIface: {
			"Category":   {Value: "ApplicationStatus", Emit: prop.EmitFalse},
			"Id":         {Value: "linux-terminal", Emit: prop.EmitFalse},
			"Title":      {Value: "linux-terminal", Emit: prop.EmitFalse},
			"Status":     {Value: "Active", Emit: prop.EmitFalse},
			"IconName":   {Value: trayIconName, Emit: prop.EmitFalse},
			"ToolTip":    {Value: tooltip, Emit: prop.EmitFalse},
			"ItemIsMenu": {Value: true, Emit: prop.EmitFalse},
			"Menu":       {Value: dbus.ObjectPath("/StatusNotifierItem/menu"), Emit: prop.EmitFalse},
		},
	}
	properties, err := prop.Export(conn, trayPath, table)
	if err != nil {
		return fmt.Errorf("export properties: %w", err)
	}

	node := &introspect.Node{
		Name: string(trayPath),
		Interfaces: []introspect.Interface{
			introspect.IntrospectData,
			prop.IntrospectData,
			{
				Name:       trayIface,
				Methods:    introspect.Methods(item),
				Properties: properties.Introspection(trayIface),
			},
		},
	}
	if err := conn.Export(introspect.NewIntrospectable(node), trayPath, "org.freedesktop.DBus.Introspectable"); err != nil {
		return fmt.Errorf("export introspection: %w", err)
	}

	// The name the watcher is given. The suffix is the convention every host
	// expects; the pid keeps two servers on one desktop from colliding.
	name := fmt.Sprintf("org.kde.StatusNotifierItem-%d-1", os.Getpid())
	reply, err := conn.RequestName(name, dbus.NameFlagDoNotQueue)
	if err != nil {
		return fmt.Errorf("request name: %w", err)
	}
	if reply != dbus.RequestNameReplyPrimaryOwner {
		return fmt.Errorf("name %s is taken", name)
	}

	if !register {
		log.Printf("tray self-test: %s exported, NOT registered with the watcher", name)
		log.Printf("inspect it with: busctl --user introspect %s %s", name, trayPath)
		go s.trayWatch(conn, properties)
		return nil
	}

	watcher := conn.Object(watcherName, watcherPath)
	if call := watcher.Call(watcherName+".RegisterStatusNotifierItem", 0, name); call.Err != nil {
		return fmt.Errorf("no tray host: %w", call.Err)
	}

	log.Printf("tray icon registered as %s", name)
	go s.trayWatch(conn, properties)
	return nil
}

// trayWatch keeps the tooltip honest. It emits only when the sentence would
// read differently — a few times an hour — and it emits NewToolTip, which asks
// the host to re-read one property, not to walk anything.
func (s *Server) trayWatch(conn *dbus.Conn, properties *prop.Properties) {
	previous := ""
	for {
		description := trayDescription(len(s.liveSessions()))
		if description != previous {
			previous = description
			properties.SetMust(trayIface, "ToolTip", trayTooltip{
				IconName:    trayIconName,
				Title:       "linux-terminal",
				Description: description,
			})
			if err := conn.Emit(trayPath, trayIface+".NewToolTip"); err != nil {
				log.Printf("tray: %v", err)
			}
		}
		time.Sleep(2 * time.Second)
	}
}

func trayDescription(sessions int) string {
	switch sessions {
	case 0:
		return "nobody connected"
	case 1:
		return "1 connection"
	default:
		return fmt.Sprintf("%d connections", sessions)
	}
}

// openConsole hands the URL to the desktop rather than choosing a browser. On a
// machine with a tray there is always something behind xdg-open.
func (s *Server) openConsole() {
	url := fmt.Sprintf("http://127.0.0.1:%d", s.HTTPPort)
	if err := exec.Command("xdg-open", url).Start(); err != nil {
		log.Printf("cannot open %s: %v", url, err)
	}
}
