<img src="docs/img/icon.png" width="128" align="right" alt="">

# linux-terminal

A Linux shell in a VR headset, as **text** rather than as video.

The usual way to reach a desktop from a Quest is to capture the screen, compress
it with H.264 and show the result on a panel. For a terminal that is a strange
thing to do: the shell produces text, and every step of encoding, sending and
scaling it can only make that text worse. Here the bytes are sent as bytes and
the headset draws the glyphs itself, at the panel's own resolution.

| | Streamed desktop | This |
|---|---|---|
| Text sharpness | limited by encode, scale and optics | limited by optics alone |
| Bandwidth | ~2 Mbit/s idle, tens under motion | bytes per keystroke |
| Latency | capture + encode + network + decode | network only |
| Works over a tether, or from another city | no | yes |

Two halves: a **server** on the Linux machine, and a **client** in the headset.

## What is actually interesting here

**The bar follows what is running.** Under a shell it offers the directory
above, the directories inside, and the projects you open most often. Start
`vim` and it becomes `:w`, `:q!`, `/`. Start Claude Code and it becomes `Esc`,
mode cycling, `/compact`, and one button per skill in that project.

**That is read, not guessed.** A pty has a foreground process group, so the
server asks the kernel which program is in front and reads `/proc` for the rest.
No window titles, no watching for prompts. It matters because a bar that guesses
wrong is worse than a fixed one: keys that move at the moment you reach for them
are a hazard, not a convenience.

**The keys that must always be there never move.** Escape, `^C`, `^D`, Tab,
backspace, `^W`, `^U`, the arrows and Enter live on a fixed half built once and
never rebuilt. The Quest's own keyboard has none of them; without them a
terminal cannot be used at all.

**Dictation goes straight into the pty.** Speak, and the text lands at the
cursor without being submitted, so a misheard word is fixed on the line it
landed on. (Requires a Whisper-compatible endpoint — see
[`docs/voice.md`](docs/voice.md).)

## Install the server

```sh
git clone https://github.com/vr-meta/linux-terminal
cd linux-terminal
./install.sh
```

That builds a static Go binary, puts it in `/usr/local/bin`, and starts it as a
**systemd user service** — a user service on purpose: the shells it opens are
yours, and they inherit your environment, your PATH and your `~/.bashrc`.

Needs Go and nothing else. `PREFIX=~/.local ./install.sh` avoids `sudo`
entirely; `./install.sh --no-service` just leaves you the binary.

Or without the script:

```sh
make server          # server/linux-terminal-server
make install         # binary + service
make run             # in this terminal, no service
```

## Install the client

```sh
make apk             # builds and installs on the attached headset
```

Or by hand:

```sh
cd client && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 21 and Android SDK 34; put `sdk.dir=` in `client/local.properties`.

## Using it

Open **linux terminal** in the headset. Machines on the same network appear by
themselves — the server answers a UDP probe on port 9103. A machine somewhere
else is typed in once by address and remembered.

Pick one, and you get a terminal window with tabs and a bar window beside it.
Both are ordinary Horizon OS windows: the shell places them, resizes them, and
lets them sit next to a browser or anything else.

| | |
|---|---|
| new tab, in the current directory | `+` on the tab strip |
| close a tab | `×` on the tab itself |
| a second machine | pick another server; it opens its own window |
| text size | `A−` / `A+` on the bar |
| the on-screen keyboard | the keyboard button on the bar |
| dictation | the microphone button |

## How it is put together

```
Linux                                        Quest 3
┌───────────────────────────┐                ┌──────────────────────────┐
│ linux-terminal-server     │                │ ServersActivity          │
│   ├─ pty ── bash ── claude│◄──── TCP ─────►│ TermActivity  (tabs)     │
│   ├─ tcgetpgrp → /proc    │  framed msgs   │ BarActivity   (buttons)  │
│   └─ builds the buttons   │◄──── UDP ──────┤ Discovery                │
└───────────────────────────┘   discovery    └──────────────────────────┘
```

The buttons are computed on the **server**, not in the app. The server is the
side that knows what is running and what is installed, so teaching the bar a new
tool is an edit to one Go table and never a new APK.

Protocol, layout and the reasoning behind both:
[`docs/terminal-app.md`](docs/terminal-app.md).

## Text size

Readability in a headset is governed by the **angular size of a glyph**, not by
pixel density. Measured on this hardware, comfort begins at about **0.39° per
glyph** — the derivation and the numbers are in
[`docs/readability.md`](docs/readability.md), and the client's defaults come
from it.

## Where this came from

It grew out of [linux-vr](https://github.com/butschster/linux-vr), a VR desktop
that streams Ubuntu into the headset as video. The terminal turned out to be a
different product with a different shape — a client and a server, not a screen —
so it lives here. The streamed desktop stays there.

## Licence

MIT, except `client/app/src/main/java/com/termux/`, which is vendored from
[termux/termux-app](https://github.com/termux/termux-app) under Apache 2.0. See
[`client/app/NOTICE.md`](client/app/NOTICE.md) for what was changed and why.
