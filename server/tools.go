package main

import (
	"embed"
	"fmt"
	"io/fs"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"gopkg.in/yaml.v3"
)

// What the bar offers for a given program, as data rather than as Go.
//
// This used to be a map of functions in actions.go, which meant that teaching the
// bar about a new program was an edit, a rebuild and a restart — and the restart
// is the step that gets forgotten, so the change appears not to have worked. A
// file is edited, saved and pressed.
//
// Only the *tables* moved. What the bar offers at a shell prompt — the directories
// around you, the projects you open, the skills in this checkout — is computed
// from the machine and stays in Go, because it is not a table and pretending it is
// one would mean inventing a language to express it.
//
// A file describes one program, and its name is the tool's identity:
// `claude.yaml` is the tool `claude`. No id field, because an id inside a file is
// a second name that can disagree with the first.

//go:embed tools/*.yaml
var builtinTools embed.FS

// Where a machine's own tool files live. Anything here overrides a built-in of the
// same name, so a person who dislikes our Claude table replaces it rather than
// arguing with it.
const userToolsDir = "tools"

// How often the directory is re-read at most. The context poll runs four times a
// second per session; stat-ing a directory that often for the sake of an edit
// nobody is making would be the tail wagging the dog.
const toolsReloadInterval = time.Second

// The styles the client knows how to colour. A file naming anything else is a
// button that will be drawn, silently, in the wrong colour — so the loader says
// so instead, naming the file and the key.
var knownStyles = map[string]bool{
	"key": true, "cmd": true, "warn": true, "dir": true, "git": true,
	"fav": true, "skill": true, "skill-global": true, "up": true, "arrow": true,
}

type toolWhen struct {
	// Offered only when this command exists on this machine. A button for
	// something that is not installed looks like a feature and answers with
	// "command not found".
	Installed string `yaml:"installed"`
}

type toolKey struct {
	Label string    `yaml:"label"`
	Send  string    `yaml:"send"`
	Style string    `yaml:"style"`
	Enter bool      `yaml:"enter"`
	Hint  string    `yaml:"hint"`
	When  *toolWhen `yaml:"when"`
}

type toolFile struct {
	// The program basenames that mean this tool. Matched against every argument
	// of the command line, not only the first — tools are routinely launched
	// through an interpreter and the name that matters is further along.
	Match []string  `yaml:"match"`
	Keys  []toolKey `yaml:"keys"`

	kind   string // the file's base name
	source string // where it was read from, for --tools and for error messages
}

type toolSet struct {
	mu    sync.RWMutex
	kinds map[string]*toolFile
	match map[string]string // program basename -> kind

	checked time.Time
	stamp   string // names and mtimes of the user's files, as last seen

	// Bumped on every successful reload. A session compares it with what it last
	// pushed and forces a context out when it differs; without that, an edited
	// file would not reach the bar until the directory or the running program
	// changed, which is exactly when nobody is looking.
	generation atomic.Uint64
}

var tools = &toolSet{kinds: map[string]*toolFile{}, match: map[string]string{}}

// load reads the built-in files and then the user's, and reports what went wrong
// without refusing to serve: a broken file loses its own buttons, not the bar.
func (t *toolSet) load() []string {
	kinds := map[string]*toolFile{}
	var problems []string

	entries, _ := fs.ReadDir(builtinTools, "tools")
	for _, entry := range entries {
		data, err := builtinTools.ReadFile("tools/" + entry.Name())
		if err != nil {
			continue
		}
		file, errs := parseTool(entry.Name(), "built-in", data)
		problems = append(problems, errs...)
		if file != nil {
			kinds[file.kind] = file
		}
	}

	dir := userToolsPath()
	if dir != "" {
		names, _ := filepath.Glob(filepath.Join(dir, "*.yaml"))
		more, _ := filepath.Glob(filepath.Join(dir, "*.yml"))
		names = append(names, more...)
		sort.Strings(names)
		for _, name := range names {
			data, err := os.ReadFile(name)
			if err != nil {
				problems = append(problems, fmt.Sprintf("%s: %v", name, err))
				continue
			}
			file, errs := parseTool(filepath.Base(name), name, data)
			problems = append(problems, errs...)
			if file != nil {
				kinds[file.kind] = file
			}
		}
	}

	match := map[string]string{}
	for kind, file := range kinds {
		for _, name := range file.Match {
			match[name] = kind
		}
	}

	t.mu.Lock()
	t.kinds, t.match, t.checked = kinds, match, time.Now()
	t.mu.Unlock()
	t.generation.Add(1)
	return problems
}

func parseTool(fileName, source string, data []byte) (*toolFile, []string) {
	kind := strings.TrimSuffix(strings.TrimSuffix(fileName, ".yaml"), ".yml")

	var file toolFile
	if err := yaml.Unmarshal(data, &file); err != nil {
		return nil, []string{fmt.Sprintf("%s: %v", source, err)}
	}
	file.kind, file.source = kind, source

	var problems []string
	kept := file.Keys[:0]
	for i, key := range file.Keys {
		if key.Label == "" || key.Send == "" {
			problems = append(problems,
				fmt.Sprintf("%s: key %d has no label or nothing to send — dropped", source, i+1))
			continue
		}
		if key.Style == "" {
			key.Style = "key"
		}
		if !knownStyles[key.Style] {
			problems = append(problems,
				fmt.Sprintf("%s: key %q has unknown style %q — drawn as an ordinary key",
					source, key.Label, key.Style))
			key.Style = "key"
		}
		kept = append(kept, key)
	}
	file.Keys = kept

	// A file with no match list still works when something else classifies the
	// program — but it is almost always a typo, and a silent one.
	if len(file.Match) == 0 {
		problems = append(problems,
			fmt.Sprintf("%s: no `match:` list, so nothing will ever select it", source))
	}
	return &file, problems
}

// maybeReload re-reads the user's directory when it has changed. Cheap enough to
// call from the poll loop: one stat, at most once a second.
func (t *toolSet) maybeReload() {
	t.mu.RLock()
	checked := t.checked
	t.mu.RUnlock()
	if time.Since(checked) < toolsReloadInterval {
		return
	}

	dir := userToolsPath()
	if dir == "" {
		t.mu.Lock()
		t.checked = time.Now()
		t.mu.Unlock()
		return
	}

	// The directory's own mtime moves when a file is added, removed or renamed;
	// each file's moves when it is edited. Both are needed, and both are one stat.
	stamp := ""
	if info, err := os.Stat(dir); err == nil {
		stamp = info.ModTime().String()
	}
	names, _ := filepath.Glob(filepath.Join(dir, "*.y*ml"))
	sort.Strings(names)
	for _, name := range names {
		if info, err := os.Stat(name); err == nil {
			stamp += "|" + name + info.ModTime().String()
		}
	}

	t.mu.Lock()
	changed := stamp != t.stamp
	t.stamp = stamp
	t.checked = time.Now()
	t.mu.Unlock()

	if !changed {
		return
	}
	for _, problem := range t.load() {
		log.Printf("tools: %s", problem)
	}
}

// keysFor builds the buttons for a tool, dropping the ones this machine cannot
// honour.
func (t *toolSet) keysFor(kind string) ([]Action, bool) {
	t.mu.RLock()
	file, ok := t.kinds[kind]
	t.mu.RUnlock()
	if !ok {
		return nil, false
	}

	out := make([]Action, 0, len(file.Keys))
	for _, k := range file.Keys {
		if k.When != nil && k.When.Installed != "" && !installed(k.When.Installed) {
			continue
		}
		out = append(out, Action{
			Label: k.Label, Send: k.Send, Style: k.Style, Enter: k.Enter, Hint: k.Hint,
		})
	}
	return out, true
}

// kindFor maps a program's basename to a tool, or "" when no file claims it.
func (t *toolSet) kindFor(name string) string {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.match[name]
}

// Beside the settings file, wherever that is — including when it was moved with
// LINUX_TERMINAL_CONFIG, so a second server on one machine keeps its own tools.
func userToolsPath() string {
	return filepath.Join(filepath.Dir(configPath()), userToolsDir)
}

// describe is what `--tools` prints: what is loaded, where each came from, and
// which programs select it. The question it answers is "why is my file not
// working", and the usual answer is visible here — it was never read, or the
// name it matches is not the name of the program.
func (t *toolSet) describe() string {
	t.mu.RLock()
	defer t.mu.RUnlock()

	kinds := make([]string, 0, len(t.kinds))
	for kind := range t.kinds {
		kinds = append(kinds, kind)
	}
	sort.Strings(kinds)

	var b strings.Builder
	fmt.Fprintf(&b, "%d tools loaded\n\n", len(kinds))
	for _, kind := range kinds {
		file := t.kinds[kind]
		fmt.Fprintf(&b, "  %-10s %2d keys   matches %s\n",
			kind, len(file.Keys), strings.Join(file.Match, ", "))
		fmt.Fprintf(&b, "  %-10s          %s\n", "", file.source)
	}
	dir := userToolsPath()
	fmt.Fprintf(&b, "\nYour own files go in %s\n", dir)
	if _, err := os.Stat(dir); err != nil {
		fmt.Fprintf(&b, "(which does not exist yet — creating it is enough, no restart)\n")
	}
	return b.String()
}
