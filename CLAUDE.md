# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Language

**Everything committed here is written in English** — docs, code comments, log
strings, commit messages. The repository is public-facing and mixed language
makes it useless to an outside reader. Conversation with the repository owner
happens in Russian; that does not change what goes into the files.

## What this is

A Linux shell in a Quest 3, sent as text rather than as video: a Go **server**
on the Linux machine and an Android **client** in the headset.

It was built as a personal tool and is now **being prepared for the Meta Horizon
Store**, which changes what is in scope: see [`docs/publishing.md`](docs/publishing.md)
for what the store actually requires and what we do not have yet. The short
version is that the artwork is the easy half — a reviewer installs the client,
finds no server, and sees nothing work at all.

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
| `server/asr.go` | speech to text, proxied to whatever endpoint is configured |
| `server/console.go` + `web/` | the localhost web console: sessions, voice settings |
| `server/tray.go` | the desktop indicator, StatusNotifierItem over D-Bus |
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

**Nobody gets a shell without being let in.** TLS 1.3 always, the client pins the
server's self-signed certificate at pairing, and a 160-bit token is checked in
constant time. Six digits are traded for that token once and are safe only
because they are fragile on purpose — five minutes, five wrong answers, single
use, and non-existent until a window is opened at the machine. `docs/security.md`
argues the design and what was ruled out; the **pairing** skill covers using it.
Anything added to the discovery answer is given to strangers, so ask first.

**Keys and audio stay on the server side of the trust boundary.** The
transcription key lives in `~/.config/linux-terminal/config.json` (0600) and is
never sent to the headset, never logged, and never accepted as a command-line
argument — `ps` shows those to every user on the machine. The web console can
set it and can close sessions, which is why it binds to localhost by default.

**CGO stays off.** The server is one static binary that runs on a distribution
older than whatever built it, and that is worth more than any library which
would take it away. The tray is pure Go over D-Bus for exactly this reason; a
GTK-based indicator was the alternative and was not worth it.

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

## Releases

A tag starting with `v` builds both halves and attaches them to a GitHub
release: static server binaries for amd64 and arm64, and a signed APK.
`workflow_dispatch` runs the same build without cutting a release, which is how
a broken workflow is found without a throwaway tag.

```sh
git tag v0.2.0 && git push origin v0.2.0
gh workflow run release.yml          # or just check that it still builds
```

**The signing key lives at `~/.config/linux-terminal/release.keystore`**, outside
the repository, with its passwords in `signing.env` beside it (both 0600). The
same key is in the repository secrets so CI signs identically. Losing it means
every headset has to uninstall before the next release will install — Android
refuses an update signed by a different key.

Without the secrets the build still works and falls back to a debug key; the CI
log says so with a warning, and that APK cannot upgrade a properly signed one.

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

**The tray library is the thing that froze this desktop — not the menu.**
`slytomcat/systray` froze it four times, hard enough to need a reset each time,
and the diagnosis went wrong twice before it went right. First the timer was
blamed, then the menu; the menu was removed and the desktop froze anyway.

What settled it was a working counter-example on the same machine: the sibling
project **linux-vr has a tray with a nine-item menu, repainted on a one-second
tick, and it has never frozen anything.** It uses `fyne.io/systray`. That is the
whole difference, and `server/tray.go` is now the same library with the same two
habits, which are worth keeping whatever library you use:

- items are allocated **once** and hidden when unused, never rebuilt — systray
  cannot remove an item, so a rebuilt menu grows without bound;
- nothing is repainted unless the state would actually read differently.

Measured after the change: gnome-shell at 15.5–15.7% of one core with the full
menu registered, against a 16.2% baseline with no tray at all. The broken
version measured **100%**, pinned.

**Measure with `/proc/<pid>/stat` deltas.** `ps -o pcpu` reports an average over
the process's whole life, which read as a comfortable 15.9% while the shell was
in fact pinned at 100%. That one mistake cost most of an afternoon.

**`Ctrl+Alt+F3` does not rescue these freezes.** A VT switch is handled below
the compositor, so what hung was the kernel's display stack — on a Radeon 680M,
the same DCN 3.1 engine linux-vr's `docs/gotchas.md` warns about. There is no
lifeline from the machine itself; only ssh from another one.

Red herrings that cost time and are not the cause: `PropertyNotFound` from
`ubuntu-appindicators` fires every 15 seconds with our server dead, from
`netbird-ui` and `weekstat-tray`; every boot logs one `optc31_disable_crtc`
REG_WAIT timeout, including boots that never froze; Sunshine was already on
`vaapi` and had quit beforehand; and the Quest is not an MTP device in adb mode,
so gvfs never touches it.

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
