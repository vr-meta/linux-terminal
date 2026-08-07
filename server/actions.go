package main

import (
	"fmt"
	"strings"
)

// Action is one button. The client draws label, colours it by style, and on a press
// sends `send` — plus a carriage return when `enter` is set. It knows nothing about
// what any particular tool means, which is why teaching the bar a new tool is an
// edit to this file and never a new APK.
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

// toolActions is the whole point of the server: what the bar offers follows what is
// actually running, so the keys that matter are one press away and the ones that do
// not are not on screen at all.
//
// The first entry of every set is "get out of this program" — the button reached for
// under mild panic, and panic is when position is all you have.
//
// Keys every program needs — Esc, Enter, Tab, ^C, ^D, backspace, arrows — are NOT
// here. The client owns those and keeps them on a side that context never rebuilds.
// Duplicating them would put one key in two places, and then neither is the one the
// hand learns.
var toolActions = map[string]func(*Context) []Action{
	"claude": func(c *Context) []Action {
		return []Action{
			key("⇧Tab", esc+"[Z", "key", false, "cycle mode"),
			key("1", "1", "key", false, "permission prompt: yes"),
			key("2", "2", "key", false, "permission prompt: yes, don't ask again"),
			key("3", "3", "key", false, "permission prompt: no"),
			key("/", "/", "key", false, "commands"),
			key("!", "!", "key", false, "bash"),
			key("#", "#", "key", false, "memory"),
			key("/clear", "/clear", "cmd", true, "wipe the conversation"),
			key("/compact", "/compact", "cmd", true, ""),
			key("/resume", "/resume", "cmd", true, ""),
		}
	},
	"codex": func(c *Context) []Action {
		return []Action{
			key("/", "/", "key", false, ""),
			key("1", "1", "key", false, ""),
			key("2", "2", "key", false, ""),
			key("3", "3", "key", false, ""),
		}
	},
	"vim": func(c *Context) []Action {
		return []Action{
			key(":q!", ":q!", "warn", true, "quit, discarding changes"),
			key(":w", ":w", "cmd", true, "write"),
			key(":wq", ":wq", "cmd", true, "write and quit"),
			key("/", "/", "key", false, "search"),
			key("u", "u", "key", false, "undo"),
			key("dd", "dd", "key", false, "delete a line"),
			key("gg", "gg", "key", false, "top"),
			key("G", "G", "key", false, "bottom"),
		}
	},
	"pager": func(c *Context) []Action {
		return []Action{
			key("q", "q", "warn", false, "quit"),
			key("Space", " ", "key", false, "page down"),
			key("b", "b", "key", false, "page up"),
			key("g", "g", "key", false, "top"),
			key("G", "G", "key", false, "bottom"),
			key("/", "/", "key", false, "search"),
		}
	},
	"tui": func(c *Context) []Action {
		return []Action{
			key("q", "q", "warn", false, "quit"),
			key("/", "/", "key", false, "filter"),
			key("r", "r", "key", false, "reload"),
		}
	},
	"python": func(c *Context) []Action {
		return []Action{key("exit()", "exit()", "cmd", true, "quit")}
	},
	"nano": func(c *Context) []Action {
		return []Action{
			key("^X", "\x18", "warn", false, "exit"),
			key("^O", "\x0f", "cmd", false, "write"),
			key("^W", "\x17", "key", false, "search"),
		}
	},
}

// git pipes almost everything through a pager, so `git log` with an empty bar is a
// screen you cannot leave. The same holds for anything unrecognised: a program that
// took over the terminal is far more likely to be pager-like than not.
const fallbackTool = "pager"

func groupsFor(c *Context) []Group {
	var primary []Action
	name := "go to"
	if c.Tool != nil {
		name = c.ToolName
		build, ok := toolActions[*c.Tool]
		if !ok {
			build = toolActions[fallbackTool]
		}
		primary = build(c)
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
		out = append(out, key("↑ ..", "cd "+quote(c.Up), "up", true, short(c.Up)))
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
		out = append(out, key("/"+skill.Name, "/"+skill.Name+" ", "key", false,
			"global · "+trim(skill.Desc, 90)))
	}
	return out
}

// runActions are things to run, not keys — which is why they sit with the content
// and not with the keyboard.
func runActions(c *Context) []Action {
	out := []Action{
		key("claude", "claude", "cmd", true, ""),
		key("ls -la", "ls -la", "cmd", true, ""),
		key("^R", "\x12", "key", false, "history search"),
	}
	// Offered when there is something to look at, and not otherwise.
	if c.Git != nil && c.Git.Dirty > 0 {
		out = append(out,
			key("git status", "git status", "cmd", true, ""),
			key("git diff", "git diff", "cmd", true, ""),
		)
	}
	out = append(out,
		key("cch", "cch", "cmd", true, "Claude Code panel"),
		key("cgit", "cgit", "cmd", true, "git panel"),
	)
	return out
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
