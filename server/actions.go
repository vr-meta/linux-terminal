package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
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
	// Claude Code. The keys chosen are the ones a headset cannot press: a
	// chord needs two hands on a keyboard that is not there, and Esc twice in a
	// row is worse than one key with a name. Slash commands earn their place by
	// being the ones reached for mid-flow, not by existing — there are eighty.
	"claude": func(c *Context) []Action {
		return []Action{
			key("⇧Tab", esc+"[Z", "key", false, "cycle permission mode"),
			key("1", "1", "key", false, "permission prompt: yes"),
			key("2", "2", "key", false, "permission prompt: yes, don't ask again"),
			key("3", "3", "key", false, "permission prompt: no"),
			key("Esc Esc", esc+esc, "warn", false, "clear the draft, or rewind"),
			key("^T", "\x14", "key", false, "show the task checklist"),
			key("^O", "\x0f", "key", false, "transcript viewer"),
			key("^B", "\x02", "key", false, "background the running task"),
			key("^S", "\x13", "key", false, "stash or restore the prompt"),
			key("/", "/", "key", false, "commands"),
			key("!", "!", "key", false, "bash"),
			key("#", "#", "key", false, "memory"),
			key("/plan", "/plan", "cmd", true, "plan before a large change"),
			key("/rewind", "/rewind", "cmd", true, "roll code and chat back to a checkpoint"),
			key("/branch", "/branch", "cmd", true, "branch the conversation to try another way"),
			key("/context", "/context", "cmd", true, "what is filling the context"),
			key("/review", "/review", "cmd", true, "review the diff"),
			key("/verify", "/verify", "cmd", true, "check the changes actually work"),
			key("/compact", "/compact", "cmd", true, "summarise to free context"),
			key("/usage", "/usage", "cmd", true, "tokens and cost this session"),
			key("/resume", "/resume", "cmd", true, "return to an earlier conversation"),
			key("/clear", "/clear", "warn", true, "wipe the conversation"),
		}
	},
	// Codex. Its own vocabulary, not Claude's translated: @ searches the
	// workspace, Tab queues a follow-up while it is still working, and Esc twice
	// forks the chat from the previous message rather than rewinding it.
	"codex": func(c *Context) []Action {
		return []Action{
			key("@", "@", "key", false, "insert a file from the workspace"),
			key("!", "!", "key", false, "run a shell command"),
			key("Esc Esc", esc+esc, "key", false, "edit the previous message and fork"),
			key("Tab", "\t", "key", false, "queue this for the next turn"),
			key("^O", "\x0f", "key", false, "copy the last output"),
			key("/", "/", "key", false, "commands"),
			key("1", "1", "key", false, "approval prompt: yes"),
			key("2", "2", "key", false, "approval prompt: no"),
			key("/diff", "/diff", "cmd", true, "the git diff, untracked files included"),
			key("/compact", "/compact", "cmd", true, "summarise to free tokens"),
			key("/copy", "/copy", "cmd", true, "copy the last completed output"),
			key("/skills", "/skills", "cmd", true, "browse and use skills"),
			key("/permissions", "/permissions", "cmd", true, "what Codex may do unasked"),
			key("/init", "/init", "cmd", true, "scaffold an AGENTS.md here"),
			key("/clear", "/clear", "warn", true, "clear and start a new chat"),
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
	found := false
	for _, dir := range sessionPath() {
		if dir == "" {
			continue
		}
		// Stat, not LookPath: the search list is the shell's, not this process's,
		// and LookPath can only be told about the second. Following the symlink is
		// wanted — every one of these tools installs itself as one.
		info, err := os.Stat(filepath.Join(dir, name))
		if err == nil && !info.IsDir() && info.Mode()&0o111 != 0 {
			found = true
			break
		}
	}
	installedCache.Store(name, found)
	return found
}

// sessionShell is the shell sessions are opened with. Set once at start-up,
// because the button tables have to ask the same shell the session will get.
var sessionShell = "/bin/bash"

// sessionPath is the PATH a shell opened here would search, which is emphatically
// not the PATH this process has.
//
// This was `exec.LookPath`, and it was wrong in the one place it matters. The
// server usually runs as a `systemd --user` service, whose PATH is the bare
// system one — /usr/bin and friends. Every tool this machine's owner actually
// installed lives in ~/.local/bin, which is put on the PATH by their .bashrc.
// So `claude`, `codex` and the rest were plainly there, plainly runnable in the
// session the button would have typed into, and the bar refused to offer them
// because the *service* could not see them. Started from a terminal instead,
// the same server offered all of them — which is why this survived so long.
//
// Asked the way a session is opened: interactive, not a login shell. That is what
// pty.Start gives you, and on this machine it is the difference between a PATH
// with ~/.local/bin on it and one without.
//
// Read once. A shell is forked for it, so it must not happen per refresh — and a
// tool installed while the server runs still needs a restart to appear, which is
// the same trade the cache above already made.
var sessionPath = sync.OnceValue(func() []string {
	own := filepath.SplitList(os.Getenv("PATH"))

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	// The leading newline is deliberate: an interactive shell may greet, and the
	// answer is whatever the last line is.
	cmd := exec.CommandContext(ctx, sessionShell, "-ic", `printf '\n%s\n' "$PATH"`)
	cmd.Env = shellEnv()
	out, err := cmd.Output()
	if err != nil {
		log.Printf("cannot read the shell's PATH (%v) — offering only what this process can see", err)
		return own
	}

	lines := strings.Split(strings.TrimSpace(string(out)), "\n")
	answer := strings.TrimSpace(lines[len(lines)-1])
	if !strings.Contains(answer, string(filepath.ListSeparator)) {
		// Not a PATH. A shell whose printf takes a list rather than a string —
		// fish — lands here, and the process's own PATH is a better answer than
		// a fragment of one.
		return own
	}
	return filepath.SplitList(answer)
})

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
