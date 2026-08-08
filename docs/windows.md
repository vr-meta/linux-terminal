# Windows: what a server port would have to solve

Somebody will ask for a Windows build, and the honest answer is longer than yes
or no. The server **cross-compiles today with four errors**, and fixing those
four would produce a binary that starts, answers discovery, accepts a TLS
connection, and then opens no shell at all. That is a worse outcome than no
build: it looks finished.

This document says what was checked, how, and what a real port would have to
answer. It is written so that whoever picks this up starts from facts rather
than from an afternoon of discovery.

## What was checked, and with what

```sh
cd server && GOOS=windows GOARCH=amd64 go build .
```

Four errors, all in `session.go`:

| Line | What |
|---|---|
| `session.go:381` | `syscall.Kill` — undefined |
| `session.go:412` | `syscall.SYS_IOCTL` — undefined |
| `session.go:413` | `syscall.TIOCGPGRP` — undefined |
| `session.go:413` | `syscall.Syscall` — different signature |

Everything else in the server compiles for Windows unchanged. **CGO is not
needed for any of this**, which matters: the Windows APIs below are reachable
through `golang.org/x/sys/windows`, so the one-static-binary rule survives a
port.

## The thing that decides it: there is no pty

`github.com/creack/pty` v1.1.24 ships a Windows file, and it is a stub:

```go
// start_windows.go
func StartWithSize(cmd *exec.Cmd, ws *Winsize) (*os.File, error) {
	return nil, ErrUnsupported
}

// winsize_unsupported.go
func Setsize(*os.File, *Winsize) error { return ErrUnsupported }
```

So `session.go:100` and `session.go:148` compile and then fail at runtime, every
time. This is why "make the four errors go away" is a trap — the build turns
green and the product does not exist.

The replacement is **ConPTY**: `CreatePseudoConsole` / `ResizePseudoConsole` /
`ClosePseudoConsole` in `kernel32.dll`, present since Windows 10 1809. It is
plain Go over `golang.org/x/sys/windows`, so nothing about the dependency rules
changes — but it is a rewrite of how a session is started, resized and closed,
not a substitution.

## The principle that does not survive the crossing

`server/session.go:410` reads the foreground process group off the pty:

```go
syscall.Syscall(syscall.SYS_IOCTL, s.ptmx.Fd(), syscall.TIOCGPGRP, ...)
```

**ConPTY has no concept of a foreground process group.** There is no call that
answers this question, so there is nothing to port. `context.go:67` is the only
caller, and it is what the entire context bar is built on: what the moment is
about, which buttons the server offers, whether you are in a shell or inside
`claude`.

What is available instead is a snapshot of the process tree — `Toolhelp32Snapshot`
and its relatives — from which one could pick a likely descendant and call it the
foreground. That is **guessing**, which `CLAUDE.md` rules out for exactly the
reason that applies here: a bar that guesses wrong is worse than a fixed one.

So a Windows port has to make a decision that a Linux reader never has to:

- **offer less, honestly** — the fixed keypad, plus whatever can be known for
  certain, and no tool groups; or
- **change the rule for one platform**, and write down that on Windows the
  detection is inferred rather than read, so nobody later reads the principle and
  assumes it holds everywhere.

The first is the smaller lie. Neither is free.

## Everything else, in the order it will bite

**`/proc` is not there.** `context.go:180` reads `/proc/<pid>/cmdline` and
`context.go:198` reads `/proc/<pid>/cwd`.

- The command line has a Win32 answer that is merely tedious.
- **The working directory of another process does not.** The documented route is
  reading the target's PEB through `ReadProcessMemory` — fragile, privilege-
  sensitive, and version-dependent. And cwd is not a detail here: the directory
  buttons, the git group and the project shortcuts are all built from it
  (`context.go:92` onward).
- The alternative worth costing before writing any PEB code is **OSC 7**, where
  the shell announces its own directory in an escape sequence and the server
  reads it off the stream it already owns. It moves the burden to the user's
  prompt configuration, and it works the same on both platforms.

**Signals.** `session.go:381` sends `SIGINT` to the negated process group and
`session.go:401` sends `SIGHUP` on close. Windows has neither.
`GenerateConsoleCtrlEvent` exists but is attached-console-scoped; for an
interactive program, writing `0x03` into the ConPTY input is usually the same
thing from the program's point of view. Closing is `ClosePseudoConsole` plus
terminating what is left.

**Secrets stop being protected.** `console.go:234` writes the config with the
transcription key and the pairing tokens at 0600 in a 0700 directory. On Windows
`os.Chmod` is close to a no-op — access is an ACL, not a mode — so the file lands
readable by more than intended. `docs/security.md` says keys stay on the server
side of the trust boundary; on Windows that sentence requires explicit ACL work
(`golang.org/x/sys/windows`), or it is not true. **This is the one item on the
list that is a security regression rather than a missing feature**, so it cannot
be deferred to "later".

**Small, and each about an hour:**

| Where | Now | On Windows |
|---|---|---|
| `main.go:115` | `/bin/bash` fallback | `pwsh` / `powershell.exe` / `cmd.exe` |
| `main.go:271` | `/etc/os-release` → `PRETTY_NAME` | the Windows version, or the card in the headset lies about the machine |
| `asr.go:55` | `~/.config/linux-terminal/` | `%APPDATA%`, if it is to look native |
| `packaging/` | a systemd user unit | a service or a Task Scheduler entry |
| `actions.go:237` | `ls -la`, `git status` | `ls -la` is not a PowerShell command; the run group needs its own table |

`exec.LookPath` (`actions.go:269`) works on Windows and keeps its promise —
a button is only offered where the command exists — so the mechanism survives
even though its contents do not.

## What is untouched

TLS, pairing, the token check, discovery over UDP, the web console, and the ASR
proxy are ordinary Go and need nothing. The tray does too: `fyne.io/systray`
v1.12.2 ships `systray_windows.go`, so the desktop indicator is not a blocker.

**The client does not change by a single line.** The server decides what the bar
offers and the client draws it, which is precisely the property that makes a
second server platform a server-side project.

## The answer available today: WSL2

If the question is "I want to work on my Windows machine from the headset"
rather than "I want a PowerShell prompt in the headset", then the Linux binary
running inside WSL2 already does it — real pty, real `/proc`, the whole context
bar, no new code.

Two things to expect, neither verified on hardware for this document:

- WSL2 is behind its own NAT, so the port has to be reachable from the LAN —
  a port proxy on the Windows side, or whichever networking mode the installed
  WSL offers. Check what the current version does rather than trusting a
  half-remembered recipe.
- A discovery broadcast will not cross that boundary, so the machine is **added
  by address** in the connection manager instead of being found.

This is worth saying out loud to anyone who asks, because for most of the people
who would ask, it is the actual answer.

## If someone does write the port

An order that keeps the thing honest at every step:

1. **ConPTY first, alone.** A session that opens, echoes, resizes and closes.
   Until that works, nothing else can be tested, and the four compile errors are
   not worth touching.
2. **Shell default, OS version, config location, ACLs on the config file.** The
   ACL work belongs here and not later, because shipping without it publishes a
   key.
3. **A Windows action table.** The bar's fixed half already works; give the run
   group commands that exist.
4. **Context, as its own decision.** Foreground detection and cwd — pick honest
   scarcity or documented inference, and write down which.
5. **Only then, CI.** `GOOS=windows` joins the release matrix *after* a session
   opens on a real Windows machine — not before. The workflow currently builds
   linux/amd64 and linux/arm64, and an `.exe` on the release page that cannot
   open a shell is the failure mode `.claude/skills/release/SKILL.md` exists to
   prevent.

## What this document does not know

- Whether ConPTY alone is enough for the programs this is used with — a
  full-screen TUI redrawing several times a second is the case that matters, and
  it has not been tried.
- What the process-tree approach would actually return in practice, and how often
  it would be wrong.
- The current WSL2 networking behaviour, which changes between releases.

Everything above about the code, the stubs and the build errors was checked
against this tree; everything in this section was not.
