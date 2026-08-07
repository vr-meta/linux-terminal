package main

import (
	"fmt"
	"os/exec"
	"strings"
	"sync"
)

// Action is one button. The client draws label, colours it by style, and on a press
// sends `send` — plus a carriage return when `enter` is set. It knows nothing about
// what any particular tool means, which is why teaching the bar a new tool is a
// file on the server and never a new APK.
type Action struct {
	Label string `json:"label"`
	Send  string `json:"send"`
	Style string `json:"style"`
	Enter bool   `json:"enter"`
	Hint  string `json:"hint,omitempty"`
}

type Group struct {
	Name    string   `json:"name"`
	Actions []Action `json:"actions"`
}

const esc = "\x1b"

func key(label, send, style string, enter bool, hint string) Action {
	return Action{Label: label, Send: send, Style: style, Enter: enter, Hint: hint}
}

// The per-tool tables used to live here, as Go. They are now files —
// `server/tools/*.yaml`, embedded, and overridable per machine from
// ~/.config/linux-terminal/tools. See tools.go for why, and the `new-tool` skill
// for how to add one.
//
// What stayed is everything below: the groups that are computed from the machine
// rather than looked up, because a directory listing is not a table.

// git pipes almost everything through a pager, so `git log` with an empty bar is a
// screen you cannot leave. The same holds for anything unrecognised: a program that
// took over the terminal is far more likely to be pager-like than not.
const fallbackTool = "pager"

func groupsFor(c *Context) []Group {
	var primary []Action
	name := "go to"
	if c.Tool != nil {
		name = c.ToolName
		keys, ok := tools.keysFor(*c.Tool)
		if !ok {
			// A tool that classified but has no file of its own. Not an error:
			// ssh, psql and git are recognised so the bar can say their name,
			// and pager keys are the right guess for all of them.
			keys, _ = tools.keysFor(fallbackTool)
		}
		primary = keys
	} else {
		primary = placeActions(c)
	}

	groups := []Group{{Name: name, Actions: primary}}

	if c.Tool != nil && *c.Tool == "claude" {
		groups = append(groups, Group{Name: "skills", Actions: skillActions(c)})
	}
	if c.Tool == nil {
		groups = append(groups,
			Group{Name: "projects", Actions: projectActions(c)},
			Group{Name: "run", Actions: runActions(c)},
		)
	}
	return groups
}

// placeActions: at a prompt the bar is a way to get somewhere.
func placeActions(c *Context) []Action {
	var out []Action
	if c.Up != "" {
		// No arrow in the text. The client draws every other direction itself, at
		// one stroke weight, and a font's arrow beside those looks like it wandered
		// in from another program. The "up" style is the signal; the picture is the
		// client's business.
		out = append(out, key("..", "cd "+quote(c.Up), "up", true, short(c.Up)))
	}
	for _, dir := range c.Dirs {
		style := "dir"
		if dir.Git {
			style = "git"
		}
		out = append(out, key(dir.Name, "cd "+quote(dir.Name), style, true, ""))
	}
	return out
}

func projectActions(c *Context) []Action {
	var out []Action
	for _, project := range c.Projects {
		hint := fmt.Sprintf("%s  ·  %d×", short(project.Path), project.Count)
		out = append(out, key(project.Label, "cd "+quote(project.Path), "fav", true, hint))
	}
	return out
}

// skillActions: the project's own skills first and marked as such — the global ones
// are not this project, and FR-13 is about this project.
func skillActions(c *Context) []Action {
	var out []Action
	for _, skill := range c.Skills {
		if skill.Origin != "project" {
			continue
		}
		out = append(out, key("/"+skill.Name, "/"+skill.Name+" ", "skill", false, trim(skill.Desc, 100)))
	}
	for _, skill := range c.Skills {
		if skill.Origin == "project" {
			continue
		}
		// Its own style rather than "key", though it is drawn the same muted way.
		// "key" means a keystroke to the client now, and a keystroke is filed with
		// the keyboard — which sent every global skill to the wrong side of the bar.
		out = append(out, key("/"+skill.Name, "/"+skill.Name+" ", "skill-global", false,
			"global · "+trim(skill.Desc, 90)))
	}
	return out
}

// runActions are things to run, not keys — which is why they sit with the content
// and not with the keyboard.
func runActions(c *Context) []Action {
	// Only what this machine can actually run. A button for a command that is not
	// installed is worse than no button: it looks like a feature and answers with
	// "command not found". cch and cgit in particular are one person's own
	// scripts, and they were offered to everybody.
	var out []Action
	for _, name := range []string{"claude", "codex"} {
		if installed(name) {
			out = append(out, key(name, name, "cmd", true, ""))
		}
	}
	out = append(out,
		key("ls -la", "ls -la", "cmd", true, ""),
		key("^R", "\x12", "key", false, "history search"),
	)
	// Offered when there is something to look at, and not otherwise.
	if c.Git != nil && c.Git.Dirty > 0 {
		out = append(out,
			key("git status", "git status", "cmd", true, ""),
			key("git diff", "git diff", "cmd", true, ""),
		)
	}
	if installed("cch") {
		out = append(out, key("cch", "cch", "cmd", true, "Claude Code panel"))
	}
	if installed("cgit") {
		out = append(out, key("cgit", "cgit", "cmd", true, "git panel"))
	}
	return out
}

// installed reports whether a command exists on this machine.
//
// Looked up once and remembered: this runs on every context refresh, and a PATH
// search per button per refresh is a filesystem walk nobody asked for. A command
// installed while the server is running therefore needs a restart to appear,
// which is the right trade — the alternative is scanning PATH several times a
// second forever.
var installedCache sync.Map

func installed(name string) bool {
	if cached, ok := installedCache.Load(name); ok {
		return cached.(bool)
	}
	_, err := exec.LookPath(name)
	installedCache.Store(name, err == nil)
	return err == nil
}

func trim(text string, limit int) string {
	if len(text) <= limit {
		return text
	}
	return text[:limit]
}

// quote is shell single-quoting: paths with spaces are normal and a directory
// called "it's" should not break the button that opens it.
func quote(value string) string {
	return "'" + strings.ReplaceAll(value, "'", `'\''`) + "'"
}
