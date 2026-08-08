---
name: new-tool
description: Teach the bar a new program — the buttons shown while lazygit, psql, aider or anything else is in the foreground. Covers the file format, where the files live, how the escape sequences work, hot reload, and what to check when a new file appears to do nothing. Use when asked to add buttons for a tool, change the keys an existing one offers, or work out why a tool file is being ignored.
---

# Teaching the bar a program

The bar has two halves and only one of them is a file.

**Computed, and still Go.** At a shell prompt the bar offers the directory above,
the directories inside, the projects you open most, the skills in this checkout,
and the commands this machine can actually run. None of that is a table — it is
read from the filesystem on every refresh — and it lives in `server/actions.go`.

**Tabulated, and now a file.** What a *program* offers while it is in the
foreground — `:wq` for vi, `/compact` for Claude Code, `q` for a pager. One YAML
file per program. That is what this skill is about.

## Where the files live

| | |
|---|---|
| Shipped with the server | `server/tools/*.yaml` in this repository, compiled in with `go:embed` |
| A machine's own | `~/.config/linux-terminal/tools/*.yaml` |

Same name wins: a `vim.yaml` of yours replaces ours entirely rather than merging
with it. **The file's name is the tool's identity** — `lazygit.yaml` is the tool
`lazygit` — so there is no id field inside to disagree with it.

The directory does not exist on a fresh install. Creating it is enough; nothing
needs restarting.

## The format

```yaml
# Which foreground programs this file describes. Basenames, matched against every
# argument of the command line and not only the first — tools get launched
# through interpreters and the name that matters is further along.
match: [lazygit]

keys:
  - {label: "?",  send: "?",     hint: keybindings}
  - {label: "c",  send: "c",     style: cmd, hint: commit}
  - {label: "P",  send: "P",     style: cmd, hint: push}
  - {label: "q",  send: "q",     style: warn, hint: quit}
  - {label: "gh", send: "gh%s", style: cmd, enter: true, when: {installed: gh}}
```

| field | |
|---|---|
| `label` | what is drawn on the key. Required |
| `send` | the bytes sent to the pty. Required |
| `style` | the colour, and which half of the bar it lands on. Default `key` |
| `enter` | append a carriage return, so the line runs |
| `hint` | the tooltip, and what a screen reader says |
| `when.installed` | offer it only if that command exists on this machine |

**Styles**, and they are not decoration — `key` means "a keystroke" and files the
button with the keyboard, everything else files it with the content:

`key` · `cmd` · `warn` · `dir` · `git` · `fav` · `skill` · `skill-global` · `up`
· `arrow`

An unknown style is not fatal: the button is drawn as an ordinary key and the
loader says which file and which label, at startup and on reload.

## Escapes — the trap that costs an hour

**Double quotes, always.** YAML processes escapes in double-quoted scalars and
leaves them alone in single-quoted ones, so the two differ in a way nothing warns
you about — measured:

```yaml
send: "\e[Z"     # 1b 5b 5a   — Shift-Tab, correct
send: '\e[Z'     # 5c 65 5b 5a — the literal characters \ e [ Z, typed into your shell
```

The ones actually needed here:

| | |
|---|---|
| `"\e"` | Escape (0x1b). `"\e\e"` is Esc twice, which several agents treat as its own thing |
| `"\x14"` | any control character — `^T` here, `^C` is `"\x03"`, `^W` is `"\x17"` |
| `"\t"` | Tab |
| `"\r"` | carriage return — but prefer `enter: true`, which reads as what it is |
| `"%s"` | a space, in `send`; a bare space also works, quoting handles it |

## What does NOT need a restart

Editing a file in `~/.config/linux-terminal/tools/`. The server re-reads the
directory at most once a second, and pushes the new bar to every open session —
**no restart, no reconnection, the terminal keeps its scrollback**. Measured
end-to-end: edit the file, the bar changes about three seconds later.

Deleting your file brings the built-in back, on the same terms.

## What DOES need a restart

**Editing `server/tools/*.yaml` in the repository.** Those are compiled into the
binary by `go:embed`, so the file on disk is not the file being used. Rebuild and
restart, or copy it to `~/.config/linux-terminal/tools/` while you are iterating
and move it back when it is right.

**Adding a command that `when: {installed: …}` looks for.** The PATH lookup is
cached for the life of the process, deliberately — the alternative is walking
PATH several times a second forever.

```sh
systemctl --user restart linux-terminal-server     # installed as a service
# or, if it is running in a terminal, stop it by pid and start it again — never
# `pkill -f linux-terminal`, which matches the shell running the pkill
```

## When the new file appears to do nothing

Ask the server what it loaded. This is the whole diagnosis most of the time:

```sh
linux-terminal-server --tools
```

It prints every tool, how many keys survived validation, which program names
select it, and whether it came from the repository or from your directory. Then,
in order:

**Is the file even being read?** If it is not in that list, the name is wrong
(`.yaml` or `.yml`, nothing else) or it is in the wrong directory. The command
prints the directory it looked in.

**Does `match:` name the program as the kernel sees it?** Not what you type — the
basename of an argument in `/proc/<pid>/cmdline`. Check while it is running:

```sh
linux-terminal-server --dump-context ~/some/project    # what the bar would show
```

and the console at `http://localhost:9104` names the foreground tool of every
live session, which is the same answer from the other end.

**Did a key get dropped?** A key with no `label` or no `send` is discarded and
said out loud, with the file name and the position. Look at the server's log:

```sh
journalctl --user -u linux-terminal-server | grep '^.*tools:'
```

**Is something else claiming the program first?** A few names are recognised
without having a table — `git`, `ssh`, `psql`, `aider`, `node` — and get pager
keys. Your file wins over those, since files are consulted first; if it does not,
the file did not load. See the first check.

## Choosing what goes on it

Worth saying because a bar of thirty buttons is a bar nobody reads:

**Only what a headset cannot press.** Chords need two hands on a keyboard that is
not there. Plain letters a Quest keyboard can type do not need a button unless
they are the ones reached for constantly.

**First key is the way out.** `q`, `:q!`, `^X` — the button pressed under mild
panic, and under panic position is all you have.

**Never duplicate the fixed half.** Escape, Enter, Tab, `^C`, `^D`, backspace,
`^W`, `^U` and the arrows are drawn by the client and never move. Putting one in
a tool file puts that key in two places, and then neither is the one the hand
learns.

**Hints are not optional.** They are the tooltip and the accessibility label, and
for a single-letter key they are the only thing that says what it does.

## A tool file is executable, in the way that matters

It types into your shell. A file you wrote is exactly as dangerous as your own
keyboard, which is to say not at all — but a file *someone sent you* is a
button labelled `/compact` that can send anything, and the label is the only
thing you see. Read a downloaded tool file before dropping it in, the same way
you would read a shell script.

## Testing it without putting the headset on

The whole loop runs from a command line — see the **emulator** skill. Connect,
start the program, watch the bar change, edit the file, watch it change again.
That is how the hot reload above was measured.
