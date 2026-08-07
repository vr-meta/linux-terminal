# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Language

**Everything committed here is written in English** — docs, code comments, log
strings, commit messages. The repository is public-facing and mixed language
makes it useless to an outside reader. Conversation with the repository owner
happens in Russian; that does not change what goes into the files.

## What this is

A Linux shell in a Quest 3, sent as text rather than as video: a Go **server**
on the Linux machine and an Android **client** in the headset. A personal tool,
**not a Horizon Store product** — VRC compliance, entitlement checks and the
rest of the store scaffolding are out of scope.

It was split out of [linux-vr](https://github.com/butschster/linux-vr), which
streams the whole desktop as H.264. That is a different product and stays there.

## Layout

```
server/       the Go server: pty, protocol, context, discovery
client/       the Android client (Gradle, plain Java, no Compose)
  app/src/main/java/dev/butschster/linuxterminal/   ours
  app/src/main/java/com/termux/                     vendored emulator, Apache-2.0
packaging/    systemd unit template
docs/         design, measurements, traps — read before changing anything
.claude/      skills: installing, developing, diagnosing
```

### Key files

| | |
|---|---|
| `server/session.go` | one pty, one client, the framing |
| `server/context.go` | cwd, foreground process, listings, skills, git |
| `server/actions.go` | **the button tables** — this is where a new tool is taught |
| `server/discovery.go` | the UDP probe answer |
| `client/.../ServersActivity.java` | the connection manager, the app's front door |
| `client/.../TermActivity.java` | tabs; one socket and one shell each |
| `client/.../TermView.java` | drawing and input, written against the emulator |
| `client/.../ContextBar.java` | draws whatever buttons the server sent; knows no tools |
| `client/.../KeyPad.java` | the keys that never move |
| `client/.../HostSession.java` | socket transport in place of Termux's JNI pty |

## Principles that must not be broken

**The server decides what the bar offers; the client only draws it.** The server
owns the pty, so it can read the foreground process group rather than guess, and
it has the filesystem the buttons refer to. A tool is added by editing
`server/actions.go`, never by rebuilding the APK.

**Detection is read, not guessed.** `TIOCGPGRP` on the pty gives the foreground
process group; `/proc/<pgid>/cmdline` says what it is. No window titles, no
prompt sniffing. A bar that guesses wrong is worse than a fixed one.

**The fixed half of the bar is built once and never rebuilt.** `KeyPad` is
constructed in the constructor and context never touches it. An earlier version
had those keys in an ordinary group at the end of a list whose earlier groups
changed length, so starting a program moved Enter and `^C` to another row. That
is the Touch Bar's failure, committed against the keys that matter most.

**Text is drawn from the font, never decoded.** Nothing here encodes or scales a
picture. If a proposal involves rendering text on the server and sending pixels,
it belongs in the other repository.

**Measure, don't assume.** Every number in `docs/` came from this hardware. A
new claim about performance or readability rests on a measurement — including
the ones from the person wearing the headset, which outrank a derivation.

## Building and running

```sh
make server      # server/linux-terminal-server
make run         # server in this terminal
make install     # binary into /usr/local/bin plus a systemd user service
make client      # the APK
make apk         # build it and install on the attached headset
make dev         # apk + launch + server in the foreground
```

The client needs JDK 21, Android SDK 34, and `client/local.properties` with
`sdk.dir` (not in git). The server needs Go and nothing else.

Inspect what the bar would show without a headset in reach:

```sh
./server/linux-terminal-server --dump-context ~/some/project
```

## Traps that have already cost time

**Check who actually holds the port before diagnosing anything.** A second
server fails to bind and dies quietly while the first keeps serving, so a code
change looks like it did nothing:

```sh
ss -ltnp | grep 9103
```

Kill it by pid. `pkill -f linux-terminal` matches the command line of the shell
running the `pkill` and kills that instead — this has happened twice.

**A successful `am start` means nothing.** Verify by fact:

```sh
adb shell pidof dev.butschster.linuxterminal && echo ALIVE || echo DEAD
```

**The headset sleeps when taken off.** `mWakefulness=Asleep`, the activity
pauses, nothing connects. Check it before looking for the cause in code:

```sh
adb shell dumpsys power | grep mWakefulness=
```

**adb shows "no permissions"** without a udev rule for the Quest:

```sh
echo 'SUBSYSTEM=="usb", ATTR{idVendor}=="2833", MODE="0666", GROUP="plugdev"' \
  | sudo tee /etc/udev/rules.d/51-android.rules
sudo udevadm control --reload-rules && sudo udevadm trigger && adb kill-server
```

**Log lines get evicted from logcat's ring buffer** — the Horizon OS compositor
floods it in seconds. Capture live by tag, started **before** launching:

```sh
adb logcat -c && adb logcat -s linux-terminal > log.txt &
```

**Signal dispositions survive `exec`.** An earlier server set `SIGCHLD` to
`SIG_IGN` to avoid zombies; every descendant that reaps its own children then
got `ECHILD` from `waitpid`, and Claude Code's hooks failed with exactly that.
Go's runtime does not have this problem, but do not reintroduce it.

**Claude Code exports session markers** (`CLAUDECODE`, `CLAUDE_CODE_*`). The
server is often started from a terminal inside a Claude Code session, and
without stripping them every `claude` opened in the headset runs as a child of
that session, with transcript saving off. `shellEnv()` in `server/session.go`
drops them.

## Never answer platform questions from memory

Horizon OS changes fast, gates capabilities behind manifest declarations that
are easy to forget, and diverges from base Android. Claims made from memory in
this project have been wrong three times.

Settled and sourced in [`docs/horizon.md`](docs/horizon.md):

- A 2D app **cannot** place, move or orient its own window. Size is the only
  window property it controls. Do not look for an API.
- `FLAG_ACTIVITY_LAUNCH_ADJACENT` is the one placement Meta documents.
- `ActivityOptions.setLaunchBounds` is inert here — this Quest 3 reports neither
  `FEATURE_FREEFORM_WINDOW_MANAGEMENT` nor `FEATURE_PICTURE_IN_PICTURE`.

Where to look for anything else:
[meta-quest/agentic-tools](https://github.com/meta-quest/agentic-tools) first —
its skills are plain markdown and readable from a clone. The `metavr` CLI does
**not** run on Linux.

## Style

- A comment explains **why**, not what. "Read, not guessed — a pty has a
  foreground process group" is useful; "get the process group" is not.
- Numbers in comments and docs carry their source: a measurement, a conclusion
  drawn from one, or a specification.
- Do not add a dependency when what is there will do. The server has exactly one
  (`creack/pty`) and the client has none.
