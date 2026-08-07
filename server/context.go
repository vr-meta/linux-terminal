package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// Context is everything the bar in the headset needs to draw itself.
type Context struct {
	Cwd      string    `json:"cwd"`
	CwdLabel string    `json:"cwd_label"`
	Home     string    `json:"home"`
	Up       string    `json:"up,omitempty"`
	Tool     *string   `json:"tool"`
	ToolName string    `json:"tool_name"`
	Pid      int       `json:"pid"`
	Root     string    `json:"root,omitempty"`
	Git      *GitState `json:"git"`
	Dirs     []Dir     `json:"dirs"`
	Projects []Project `json:"favorites"`
	Skills   []Skill   `json:"skills"`
	Groups   []Group   `json:"groups"`
}

type GitState struct {
	Branch string `json:"branch"`
	Dirty  int    `json:"dirty"`
}

type Dir struct {
	Name string `json:"name"`
	Git  bool   `json:"git"`
}

type Project struct {
	Path  string `json:"path"`
	Label string `json:"label"`
	Count int    `json:"count"`
	Last  int64  `json:"last"`
}

type Skill struct {
	Name   string `json:"name"`
	Desc   string `json:"desc"`
	Origin string `json:"origin"`
}

// listings are the expensive parts: recomputed only when the directory changes.
type listings struct {
	dirs     []Dir
	projects []Project
	skills   []Skill
	git      *GitState
	root     string
}

// pushContext sends the context, but only when it differs from the last one —
// or when the client asked for it.
func (s *Session) pushContext(force bool) {
	pgid, _ := s.foreground()
	argv := procArgv(pgid)
	isShell := pgid == s.cmd.Process.Pid || len(argv) == 0

	var tool *string
	toolName := "shell"
	if !isShell {
		kind, name := classify(argv)
		toolName = name
		if kind == "shell" {
			isShell = true
		} else if kind != "" {
			tool = &kind
		}
	}

	pid := pgid
	if pid <= 0 {
		pid = s.cmd.Process.Pid
	}
	cwd := procCwd(pid)
	if cwd == "" {
		cwd = procCwd(s.cmd.Process.Pid)
	}
	if cwd == "" {
		cwd, _ = os.UserHomeDir()
	}

	toolKey := ""
	if tool != nil {
		toolKey = *tool
	}
	if !force && cwd == s.lastCwd && toolKey == s.lastTool {
		return
	}
	changedDir := cwd != s.lastCwd
	s.lastCwd, s.lastTool = cwd, toolKey

	if changedDir || force || s.cache == nil {
		root := projectRoot(cwd)
		s.cache = &listings{
			dirs:     subdirs(cwd),
			projects: favorites(),
			skills:   skills(root),
			git:      gitHead(cwd),
			root:     root,
		}
	}

	home, _ := os.UserHomeDir()
	up := filepath.Dir(cwd)
	if cwd == "/" {
		up = ""
	}

	ctx := &Context{
		Cwd: cwd, CwdLabel: short(cwd), Home: home, Up: up,
		Tool: tool, ToolName: toolName, Pid: pgid,
		Root: short(s.cache.root), Git: s.cache.git,
		Dirs: s.cache.dirs, Projects: s.cache.projects, Skills: s.cache.skills,
	}
	ctx.Groups = groupsFor(ctx)
	s.sendJSON(msgContext, ctx)
}

// ---------------------------------------------------------------- classifying

// A tool is recognised by the basename of any argument in its command line, not
// only argv[0]: tools are routinely launched through an interpreter, and the name
// that matters is further along.
var toolNames = map[string]string{
	"claude": "claude", "codex": "codex", "aider": "aider",
	"vim": "vim", "nvim": "vim", "vi": "vim", "nano": "nano",
	"less": "pager", "more": "pager", "man": "pager",
	"git":     "git",
	"lazygit": "tui", "htop": "tui", "top": "tui", "btop": "tui",
	"cch": "tui", "cgit": "tui",
	"ssh": "ssh", "psql": "psql",
	"python": "python", "python3": "python",
	"node": "node", "npm": "node",
	"bash": "shell", "zsh": "shell", "sh": "shell", "fish": "shell",
}

func classify(argv []string) (string, string) {
	for _, arg := range argv {
		if strings.HasPrefix(arg, "-") {
			continue
		}
		name := filepath.Base(arg)
		if kind, ok := toolNames[name]; ok {
			// "node" hosts other tools far more often than it is the tool;
			// only report it when nothing more specific was found.
			if kind == "node" {
				continue
			}
			return kind, name
		}
	}
	for _, arg := range argv {
		if filepath.Base(arg) == "node" {
			return "node", "node"
		}
	}
	if len(argv) > 0 {
		return "", filepath.Base(argv[0])
	}
	return "", "?"
}

func procArgv(pid int) []string {
	if pid <= 0 {
		return nil
	}
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", pid))
	if err != nil {
		return nil
	}
	parts := strings.Split(string(data), "\x00")
	out := parts[:0]
	for _, part := range parts {
		if part != "" {
			out = append(out, part)
		}
	}
	return out
}

func procCwd(pid int) string {
	if pid <= 0 {
		return ""
	}
	path, err := os.Readlink(fmt.Sprintf("/proc/%d/cwd", pid))
	if err != nil {
		return ""
	}
	return path
}

// ------------------------------------------------------------------- listings

func short(path string) string {
	if path == "" {
		return ""
	}
	home, _ := os.UserHomeDir()
	if path == home {
		return "~"
	}
	if strings.HasPrefix(path, home+"/") {
		return "~/" + path[len(home)+1:]
	}
	return path
}

// subdirs are the directories you would cd into next. Dot-directories are skipped
// except .claude: it is the one hidden directory this work is regularly inside.
func subdirs(cwd string) []Dir {
	entries, err := os.ReadDir(cwd)
	if err != nil {
		return nil
	}
	var out []Dir
	for _, entry := range entries {
		if len(out) >= 40 {
			break
		}
		if !entry.IsDir() {
			continue
		}
		name := entry.Name()
		if strings.HasPrefix(name, ".") && name != ".claude" {
			continue
		}
		_, err := os.Stat(filepath.Join(cwd, name, ".git"))
		out = append(out, Dir{Name: name, Git: err == nil})
	}
	sort.Slice(out, func(i, j int) bool {
		return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name)
	})
	return out
}

// usageFile belongs to the terminal fork in ~/repos/home/terminal, which writes it
// on every project switch. Reusing it rather than starting a second history means
// the headset opens the directories the desk already uses.
func usageFile() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".config/wezterm/project_usage.json")
}

func favorites() []Project {
	data, err := os.ReadFile(usageFile())
	if err != nil {
		return nil
	}
	var usage map[string]struct {
		Count int   `json:"count"`
		Last  int64 `json:"last"`
	}
	if json.Unmarshal(data, &usage) != nil {
		return nil
	}

	var rows []Project
	for path, entry := range usage {
		path = strings.TrimRight(path, "/")
		if info, err := os.Stat(path); err != nil || !info.IsDir() {
			continue
		}
		rows = append(rows, Project{
			Path: path, Label: filepath.Base(path),
			Count: entry.Count, Last: entry.Last,
		})
	}
	sort.Slice(rows, func(i, j int) bool {
		if rows[i].Count != rows[j].Count {
			return rows[i].Count > rows[j].Count
		}
		return rows[i].Last > rows[j].Last
	})
	if len(rows) > 14 {
		rows = rows[:14]
	}

	// Two projects can share a basename — this machine has home/terminal and
	// cve-tools/terminal — and two identical buttons are worse than a long one.
	byLabel := map[string]int{}
	for _, row := range rows {
		byLabel[row.Label]++
	}
	for i, row := range rows {
		if byLabel[row.Label] > 1 {
			parent := filepath.Base(filepath.Dir(row.Path))
			if parent != "" && parent != "." {
				rows[i].Label = parent + "/" + row.Label
			}
		}
	}
	return rows
}

// projectRoot is the nearest ancestor that looks like a project.
func projectRoot(cwd string) string {
	path := cwd
	for {
		if isDir(filepath.Join(path, ".claude")) || isDir(filepath.Join(path, ".git")) {
			return path
		}
		parent := filepath.Dir(path)
		if parent == path {
			return ""
		}
		path = parent
	}
}

func isDir(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}

func skills(root string) []Skill {
	var out []Skill
	if root != "" {
		out = append(out, skillsIn(filepath.Join(root, ".claude/skills"), "project")...)
	}
	home, _ := os.UserHomeDir()
	out = append(out, skillsIn(filepath.Join(home, ".claude/skills"), "user")...)
	return out
}

func skillsIn(dir, origin string) []Skill {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var out []Skill
	for _, entry := range entries {
		manifest := filepath.Join(dir, entry.Name(), "SKILL.md")
		if info, err := os.Stat(manifest); err != nil || info.IsDir() {
			continue
		}
		fields := frontmatter(manifest)
		name := fields["name"]
		if name == "" {
			name = entry.Name()
		}
		out = append(out, Skill{Name: name, Desc: fields["description"], Origin: origin})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out
}

// frontmatter reads name/description out of a SKILL.md header, without a YAML
// dependency: the header is two or three flat keys and never more.
func frontmatter(path string) map[string]string {
	fields := map[string]string{}
	file, err := os.Open(path)
	if err != nil {
		return fields
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	if !scanner.Scan() || strings.TrimSpace(scanner.Text()) != "---" {
		return fields
	}
	for scanner.Scan() {
		line := scanner.Text()
		if strings.TrimSpace(line) == "---" {
			break
		}
		if strings.HasPrefix(line, " ") || strings.HasPrefix(line, "\t") ||
			strings.HasPrefix(line, "-") {
			continue
		}
		key, value, found := strings.Cut(line, ":")
		if !found {
			continue
		}
		fields[strings.TrimSpace(key)] = strings.Trim(strings.TrimSpace(value), `'"`)
	}
	return fields
}

func gitHead(cwd string) *GitState {
	branch, err := gitOutput(cwd, 2*time.Second, "rev-parse", "--abbrev-ref", "HEAD")
	if err != nil {
		return nil
	}
	status, err := gitOutput(cwd, 3*time.Second, "status", "--porcelain")
	dirty := 0
	if err == nil {
		for _, line := range strings.Split(status, "\n") {
			if strings.TrimSpace(line) != "" {
				dirty++
			}
		}
	}
	return &GitState{Branch: strings.TrimSpace(branch), Dirty: dirty}
}

func gitOutput(cwd string, timeout time.Duration, args ...string) (string, error) {
	cmd := exec.Command("git", append([]string{"-C", cwd}, args...)...)
	done := make(chan struct{})
	var out []byte
	var err error
	go func() {
		out, err = cmd.Output()
		close(done)
	}()
	select {
	case <-done:
		return string(out), err
	case <-time.After(timeout):
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
		}
		return "", os.ErrDeadlineExceeded
	}
}
