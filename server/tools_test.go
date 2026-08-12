package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The tool tables stopped being Go and became files, which trades a compiler for
// a loader. These are the checks the compiler used to do for free — plus the ones
// it never could, like "no two files quietly claim the same program".

// useTempConfig points configPath() — and therefore the user tools directory — at
// a directory this test owns, so a test never reads or writes the real one.
func useTempConfig(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	t.Setenv("LINUX_TERMINAL_CONFIG", filepath.Join(dir, "config.json"))
	tools := filepath.Join(dir, userToolsDir)
	if err := os.MkdirAll(tools, 0o755); err != nil {
		t.Fatal(err)
	}
	return tools
}

func write(t *testing.T, dir, name, body string) string {
	t.Helper()
	path := filepath.Join(dir, name)
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

// The built-in files are compiled in, so a typo in one is a broken release rather
// than a broken machine. Everything here is about that.
func TestBuiltinToolsAreValid(t *testing.T) {
	useTempConfig(t)
	set := &toolSet{kinds: map[string]*toolFile{}, match: map[string]string{}}
	if problems := set.load(); len(problems) != 0 {
		t.Fatalf("built-in tool files are not clean: %v", problems)
	}

	if len(set.kinds) == 0 {
		t.Fatal("no tools loaded at all — the go:embed pattern probably stopped matching")
	}

	for kind, file := range set.kinds {
		if len(file.Match) == 0 {
			t.Errorf("%s: no match list, so nothing can ever select it", kind)
		}
		if len(file.Keys) == 0 {
			t.Errorf("%s: no keys", kind)
		}
		for _, key := range file.Keys {
			if key.Label == "" || key.Send == "" {
				t.Errorf("%s: a key survived loading with an empty label or send", kind)
			}
			if !knownStyles[key.Style] {
				t.Errorf("%s: key %q has style %q, which the client cannot draw",
					kind, key.Label, key.Style)
			}
		}
	}
}

// Two files claiming the same program is the failure with no symptom: one of them
// simply never appears, and which one depends on map iteration order.
func TestBuiltinMatchesDoNotCollide(t *testing.T) {
	useTempConfig(t)
	set := &toolSet{kinds: map[string]*toolFile{}, match: map[string]string{}}
	set.load()

	claimed := map[string]string{}
	for kind, file := range set.kinds {
		for _, name := range file.Match {
			if other, taken := claimed[name]; taken {
				t.Errorf("%q is claimed by both %s and %s", name, other, kind)
			}
			claimed[name] = kind
		}
	}
}

// The whole point of the classifier moving into the files: a program named in a
// file must select that file, with nothing added to Go.
func TestMatchListDrivesClassification(t *testing.T) {
	useTempConfig(t)
	tools = &toolSet{kinds: map[string]*toolFile{}, match: map[string]string{}}
	tools.load()

	kind, name := classify([]string{"/usr/bin/vi", "notes.txt"})
	if kind != "vim" {
		t.Errorf("vi classified as %q, want vim — the match list in vim.yaml is not being consulted", kind)
	}
	if name != "vi" {
		t.Errorf("display name %q, want vi", name)
	}
}

// YAML processes escapes in double-quoted scalars and not in single-quoted ones,
// which is invisible in a diff and sends the literal characters \ e [ Z into the
// shell. Claude's Shift-Tab is the canary.
func TestEscapesDecodeToControlBytes(t *testing.T) {
	useTempConfig(t)
	set := &toolSet{kinds: map[string]*toolFile{}, match: map[string]string{}}
	set.load()

	keys, ok := set.keysFor("claude")
	if !ok {
		t.Fatal("claude.yaml did not load")
	}
	for _, key := range keys {
		if key.Label != "⇧Tab" {
			continue
		}
		if key.Send != "\x1b[Z" {
			t.Fatalf("⇧Tab sends %q, want the escape sequence — the scalar is probably single-quoted", key.Send)
		}
		return
	}
	t.Fatal("no ⇧Tab key in the claude table")
}

// The keys the client draws on its fixed half must not also arrive from a file:
// one key in two places is a key the hand never learns.
func TestNoToolDuplicatesTheFixedHalf(t *testing.T) {
	useTempConfig(t)
	set := &toolSet{kinds: map[string]*toolFile{}, match: map[string]string{}}
	set.load()

	owned := map[string]string{
		"\x1b":   "Escape",
		"\x03":   "^C",
		"\x04":   "^D",
		"\x7f":   "backspace",
		"\t":     "Tab",
		"\x17":   "^W",
		"\x15":   "^U",
		"\x1b[A": "up",
		"\x1b[B": "down",
		"\x1b[C": "right",
		"\x1b[D": "left",
	}
	for kind, file := range set.kinds {
		for _, key := range file.Keys {
			if which, clash := owned[key.Send]; clash {
				t.Errorf("%s: key %q sends %s, which the client already owns",
					kind, key.Label, which)
			}
		}
	}
}

func TestUserFileOverridesBuiltin(t *testing.T) {
	dir := useTempConfig(t)
	write(t, dir, "vim.yaml", "match: [vim, vi]\nkeys:\n  - {label: \"only\", send: \"x\"}\n")

	set := &toolSet{kinds: map[string]*toolFile{}, match: map[string]string{}}
	set.load()

	keys, ok := set.keysFor("vim")
	if !ok {
		t.Fatal("vim did not load")
	}
	if len(keys) != 1 || keys[0].Label != "only" {
		t.Fatalf("got %d keys (%v), want the single key from the user's file — it did not replace the built-in",
			len(keys), keys)
	}
}

// A machine's file disappearing must bring ours back, or "let me undo that" means
// restarting the server.
func TestRemovingUserFileRestoresBuiltin(t *testing.T) {
	dir := useTempConfig(t)
	path := write(t, dir, "vim.yaml", "match: [vim]\nkeys:\n  - {label: \"only\", send: \"x\"}\n")

	set := &toolSet{kinds: map[string]*toolFile{}, match: map[string]string{}}
	set.load()
	if keys, _ := set.keysFor("vim"); len(keys) != 1 {
		t.Fatalf("override did not take: %d keys", len(keys))
	}

	os.Remove(path)
	set.load()
	if keys, _ := set.keysFor("vim"); len(keys) < 2 {
		t.Fatalf("after removing the override, vim has %d keys — the built-in did not come back", len(keys))
	}
}

func TestBadKeysAreDroppedAndReported(t *testing.T) {
	dir := useTempConfig(t)
	write(t, dir, "broken.yaml", strings.Join([]string{
		"match: [broken]",
		"keys:",
		`  - {label: "fine", send: "x"}`,
		`  - {label: "styled", send: "y", style: chartreuse}`,
		`  - {label: "", send: "z"}`,
		`  - {label: "silent", send: ""}`,
		"",
	}, "\n"))

	set := &toolSet{kinds: map[string]*toolFile{}, match: map[string]string{}}
	problems := set.load()

	keys, ok := set.keysFor("broken")
	if !ok {
		t.Fatal("broken.yaml did not load at all — a bad key should cost its own line, not the file")
	}
	if len(keys) != 2 {
		t.Errorf("kept %d keys, want 2 (the two nameless ones must go)", len(keys))
	}
	for _, key := range keys {
		if !knownStyles[key.Style] {
			t.Errorf("key %q kept an unknown style", key.Label)
		}
	}

	joined := strings.Join(problems, "\n")
	for _, want := range []string{"chartreuse", "broken.yaml"} {
		if !strings.Contains(joined, want) {
			t.Errorf("the report does not mention %q — it said: %s", want, joined)
		}
	}
}

// A file that describes nothing is a typo, and a silent one: it loads, it is
// valid, and it can never be selected.
func TestMissingMatchIsReported(t *testing.T) {
	dir := useTempConfig(t)
	write(t, dir, "nomatch.yaml", "keys:\n  - {label: \"a\", send: \"b\"}\n")

	set := &toolSet{kinds: map[string]*toolFile{}, match: map[string]string{}}
	problems := set.load()

	if !strings.Contains(strings.Join(problems, "\n"), "match") {
		t.Errorf("a file with no match list was accepted in silence: %v", problems)
	}
}

func TestWhenInstalledFiltersKeys(t *testing.T) {
	dir := useTempConfig(t)
	write(t, dir, "gated.yaml", strings.Join([]string{
		"match: [gated]",
		"keys:",
		`  - {label: "always", send: "a"}`,
		`  - {label: "present", send: "b", when: {installed: sh}}`,
		`  - {label: "absent", send: "c", when: {installed: definitely-not-a-real-command-xyzzy}}`,
		"",
	}, "\n"))

	set := &toolSet{kinds: map[string]*toolFile{}, match: map[string]string{}}
	set.load()

	keys, _ := set.keysFor("gated")
	var labels []string
	for _, key := range keys {
		labels = append(labels, key.Label)
	}
	got := strings.Join(labels, ",")
	if got != "always,present" {
		t.Errorf("kept %q, want \"always,present\" — a button for a command that is not installed answers with 'command not found'", got)
	}
}

// Sessions decide whether to re-send the bar by comparing this number. If a
// reload does not move it, an edited file reaches nobody until the directory
// changes — which is never, while somebody is editing and watching.
func TestReloadMovesTheGeneration(t *testing.T) {
	dir := useTempConfig(t)
	set := &toolSet{kinds: map[string]*toolFile{}, match: map[string]string{}}
	set.load()
	before := set.generation.Load()

	write(t, dir, "later.yaml", "match: [later]\nkeys:\n  - {label: \"k\", send: \"x\"}\n")
	set.load()

	if set.generation.Load() == before {
		t.Error("the generation did not move across a reload")
	}
	if _, ok := set.keysFor("later"); !ok {
		t.Error("a file added after the first load was not picked up")
	}
}

// The bar falls back to pager keys for a program that classified but has no table
// of its own — git, ssh, psql. An empty bar on a screen you cannot leave is the
// failure this prevents.
func TestFallbackToolExists(t *testing.T) {
	useTempConfig(t)
	tools = &toolSet{kinds: map[string]*toolFile{}, match: map[string]string{}}
	tools.load()

	if _, ok := tools.keysFor(fallbackTool); !ok {
		t.Fatalf("the fallback tool %q has no file", fallbackTool)
	}

	kind := "definitely-unknown"
	groups := groupsFor(&Context{Tool: &kind, ToolName: "psql"})
	if len(groups) == 0 || len(groups[0].Actions) == 0 {
		t.Fatal("an unrecognised tool produced an empty bar")
	}
}

func TestUserToolsPathFollowsTheConfigFile(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("LINUX_TERMINAL_CONFIG", filepath.Join(dir, "elsewhere.json"))

	want := filepath.Join(dir, userToolsDir)
	if got := userToolsPath(); got != want {
		t.Errorf("user tools path is %q, want %q — a second server on one machine would share the first one's tools", got, want)
	}
}
