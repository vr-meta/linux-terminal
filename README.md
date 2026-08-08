<img src="docs/img/icon.png" width="128" align="right" alt="">

# linux-terminal

**Have you ever tried to work in a terminal — or vibe-code — inside a VR
headset?** Then you already know how that ends, and you do not need this README
to describe it back to you in a caring voice.

Here is the part that changed, though. The typing left. With Claude Code or Codex
in the session you are not writing code so much as reading what it did and saying
what to do next, which is mostly "yes", sometimes "no, not like that", and almost
never anything you would call touch-typing. Hands were the bottleneck. Hands were
also the *only* argument the headset ever lost on.

Take that argument away and the thing stops being a costume. As many terminals as
you want, any size you want, parked wherever you left them in the room, and a
desk that does not exist.

So: a **server** on any Linux machine you own — the box under the desk, the work
machine, that VPS you keep for reasons you no longer remember — and a **client**
in the Quest that talks to it. No video anywhere in the pipeline. The shell makes
text, the wire carries text, the headset draws the glyphs itself, at the panel's
own resolution. Revolutionary, I know.

Then you just talk. The headset records, the server runs it through Whisper —
yours, or OpenAI's — and the words turn up at the cursor of whichever session you
are looking at. Floating next to the terminal is a **bar of keys**: one half is
everything the Quest's own keyboard forgot to include (Escape, `^C`, `^D`, Tab,
the arrows, Enter), and the other half rearranges itself around whatever is
running, so a permission prompt, `/compact` or a skill in that project is one
press instead of a spelling contest.

**Vibe-coding from the couch**, with a real shell on a real machine at the other
end.

Two things fall out of that, and both are the point. It is **client-server**, not
a companion app for one blessed desktop: every machine you own, listed side by
side, each one a press away. And it moves **text**, not pixels — so a shell
reached over a phone tether from another city behaves exactly like one sitting on
the LAN, which is not a sentence anybody streaming a desktop gets to write.

## Why not simply stream the desktop

You can, and the [other repository](https://github.com/butschster/linux-vr) does
exactly that. But for a terminal it is a strange thing to do: the shell produces
text, and rasterising it, compressing it with H.264, sending it and scaling it
back can only make that text worse. Here the bytes are sent as bytes and the
headset draws the glyphs itself, at the panel's own resolution.

| | Streamed desktop | This |
|---|---|---|
| Text sharpness | limited by encode, scale and optics | limited by optics alone |
| Bandwidth | ~2 Mbit/s idle, tens under motion | bytes per keystroke |
| Latency | capture + encode + network + decode | network only |
| Over a tether, or from another city | no | yes |
| A browser, a video, a GUI | yes | no — that is what the other one is for |

## What is actually interesting here

**The bar follows what is running.** At a shell prompt it offers the directory
above, the directories inside, and the projects you open most often. Start `vim`
and it becomes `:w`, `:q!`, `/`. Start Claude Code and it becomes mode cycling,
`1`/`2`/`3` for its permission prompts, `/compact`, and one button per skill in
that project.

**That is read, not guessed.** A pty has a foreground process group, so the
server asks the kernel which program is in front and reads `/proc` for the rest.
No window titles, no watching for prompts. It matters because a bar that guesses
wrong is worse than a fixed one: keys that move at the moment you reach for them
are a hazard, not a convenience.

**And you can teach it your own programs.** Each one is a small YAML file — what
the key says, what it types, what colour it is — dropped in
`~/.config/linux-terminal/tools/`. The server re-reads them within a second and
pushes the new bar to every open session: no restart, no reconnection, no new
APK. `linux-terminal-server --tools` says what loaded and from where.

```yaml
match: [lazygit]
keys:
  - {label: "c", send: "c", style: cmd,  hint: commit}
  - {label: "P", send: "P", style: cmd,  hint: push}
  - {label: "q", send: "q", style: warn, hint: quit}
```

**The keys that must always be there never move.** Escape, `^C`, `^D`, Tab,
backspace, `^W`, `^U`, the arrows and Enter live on a fixed half, built once and
never rebuilt. The Quest's own keyboard has none of them; without them a
terminal cannot be used at all.

**Dictation goes straight into the pty**, and the **server** does the
recognition. The headset records; this machine transcribes and hands the text
back over the connection that already exists. A key shipped to a headset is a
key on a device you carry into other people's houses; on the server it sits in a
file only your account can read. Point it at OpenAI with a key, or at a Whisper
you run yourself — the difference is a URL. See [`docs/voice.md`](docs/voice.md).

**It can put an icon in the tray** on a machine you sit at — `--tray` — showing
how many headsets are connected and what each is doing, with a click through to
the console. On where there is a desktop session, and `--tray=false` where you
do not want it. It was off by default for a while for a good reason: an earlier
version refreshed its menu on a timer, which GNOME answers by re-reading the
whole menu, and that froze the desktop. The refresh is edge-triggered now and
has been quiet since.

**The server has a small web console.** It runs otherwise invisibly as a user
service, and "is anyone connected, and where does dictation go" should not
require reading a log over ssh. On `http://localhost:9104`: live sessions with
their directory and foreground program, a button to disconnect one, and the
transcription settings. Localhost-only — it has no authentication, so reach it
from elsewhere with `ssh -L 9104:localhost:9104`.

## Install the server

**Take it from the [latest release](https://github.com/vr-meta/linux-terminal/releases/latest)
— there is nothing to build.** Every release carries a static binary for amd64
and arm64 and a signed APK, so a machine you install on needs no Go, no JDK and
no Android SDK.

```sh
case "$(uname -m)" in
  x86_64)  arch=amd64 ;;
  aarch64) arch=arm64 ;;
  *) echo "no release build for $(uname -m)"; exit 1 ;;
esac

base=https://github.com/vr-meta/linux-terminal/releases/latest/download
curl -fL -O "$base/linux-terminal-server-linux-$arch"
curl -fL -O "$base/checksums.txt"
sha256sum --ignore-missing -c checksums.txt        # must say: OK

sudo install -m 0755 "linux-terminal-server-linux-$arch" /usr/local/bin/linux-terminal-server
linux-terminal-server --version                    # a version, not a shell error
```

`-f` on curl is not decoration. Without it a 404 writes GitHub's HTML error page
into the file and you install a 400-byte "binary" that debugs as nonsense.

No `sudo` available: put it in `~/.local/bin` instead, and make sure that is on
`PATH` in the shell the service will run under.

### Make it survive a reboot

As a **systemd user service**, on purpose: the shells it opens are yours, and
they inherit your environment, your PATH and your `~/.bashrc`, none of which
survives being run as root.

```sh
mkdir -p ~/.config/systemd/user
curl -fL https://raw.githubusercontent.com/vr-meta/linux-terminal/main/packaging/linux-terminal-server.service \
  | sed -e "s|@BINDIR@|/usr/local/bin|" -e "s|@HOME@|$HOME|" \
  > ~/.config/systemd/user/linux-terminal-server.service

systemctl --user daemon-reload
systemctl --user enable --now linux-terminal-server
systemctl --user is-active linux-terminal-server   # active
```

Two things are skipped here and then cost an evening. **Lingering** — a user
service dies at logout, and logged out is exactly the state a machine is in when
you want to reach it from a headset:

```sh
sudo loginctl enable-linger $USER
```

And the **firewall**, if there is one: TCP *and* UDP on 9103. TCP carries the
sessions, UDP carries discovery, so if the headset only connects when you type
the address by hand, UDP is what is blocked.

```sh
sudo ufw allow 9103/tcp && sudo ufw allow 9103/udp
```

Install it on every machine you want to reach. They all announce themselves the
same way.

**The server is Linux-only, and not by oversight.** It reads the foreground
process group off the pty and the working directory out of `/proc`, and neither
has an equivalent on Windows — the cross-compile is four errors away from
building and much further away from working.
[`docs/windows.md`](docs/windows.md) says exactly what a port would have to
solve, and what to do meanwhile if the machine you want a shell on runs Windows.

### Ports, chosen once at startup

```sh
linux-terminal-server --port 9103 --http 9104 --cwd ~/projects
```

| | |
|---|---|
| `--port` | sessions (TCP) and discovery (UDP), both on this one number |
| `--http` | the web console; `0`, the default, turns it off entirely |
| `--http-bind` | what the console listens on — `127.0.0.1`, and leave it there |
| `--cwd` | where shells start |
| `--tray` | the desktop indicator; on by default where there is a desktop |

The console is off unless asked for, but the service unit above asks for it —
that is why `http://localhost:9104` works on a machine installed the usual way
and not on one started by hand without the flag.

The console binds to localhost because it can open a pairing window and revoke a
headset's access. Reach it from another machine by tunnelling, not by widening
it:

```sh
ssh -L 9104:localhost:9104 you@the-machine
```

## Install the client

The APK is in the same release, signed with the release key, so it needs only
`adb` and a headset in developer mode.

```sh
curl -fL -O https://github.com/vr-meta/linux-terminal/releases/latest/download/linux-terminal.apk
adb install -r linux-terminal.apk                  # look for: Success
```

`Performing Streamed Install` on its own means it did **not** finish; `Success`
is the line that counts. `-r` keeps the servers the app already knows.

If it is refused with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the copy on the
headset was signed with a different key — almost always a locally built debug
APK. Uninstalling first is the only way through, and it loses the saved servers:

```sh
adb uninstall dev.butschster.linuxterminal && adb install linux-terminal.apk
```

Sideloaded apps live under **Unknown Sources** in the Quest's app library.

### Building both from a clone instead

Only worth it if you are working on it. Go for the server, JDK 21 and Android
SDK 34 for the client, with `sdk.dir=` in `client/local.properties`.

```sh
git clone https://github.com/vr-meta/linux-terminal && cd linux-terminal
./install.sh         # builds, installs, starts the service
make apk             # builds the client and installs it on the headset
make run             # server in this terminal, no service
```

`PREFIX=~/.local ./install.sh` avoids `sudo`; `./install.sh --no-service` leaves
you just the binary; `./install.sh --uninstall` takes it back out.

## Let Claude Code do it

The repository carries **skills** — written instructions for the tasks above,
each with the checks that establish a step actually worked rather than merely
exited 0. Point Claude Code at a machine and say what you want in words.

| Say | Skill | What it knows |
|---|---|---|
| "install linux-terminal here" | `install` | fetch the right arch from the latest release, service, lingering, firewall, the APK |
| "pair my headset" / "why is it refusing" | `pairing` | the six digits, the fingerprint, revoking, and what each failure looks like |
| "nothing appears in the app" | `diagnose` | rule zero is which binary is actually running; then the port, the headset's wakefulness, the bar, the keys, dictation |
| "connect to the headset over Wi-Fi" | `headset` | adb by cable and over the network, logs before they are evicted, screenshots off the device |
| "test this without the headset" | `emulator` | an AVD shaped like a panel, and the whole flow — discover, pair, open a shell — driven from the command line |
| "add buttons for lazygit" | `new-tool` | the tool-file format, the escapes, hot reload, and why a new file is being ignored |
| "cut a release" | `release` | dry-run the workflow, tag, verify every artifact and that the binary reports the version |

They live in `.claude/skills/` and are picked up automatically inside a clone.
To use them on a machine you are only installing onto, copy them into your own
skills directory — one shallow clone, no build:

```sh
git clone --depth 1 https://github.com/vr-meta/linux-terminal /tmp/lt
mkdir -p ~/.claude/skills && cp -r /tmp/lt/.claude/skills/* ~/.claude/skills/
```

They go further than this page does. A README can say "install it"; a skill has
to say what to check afterwards, because an agent will otherwise report success
from a command that exited 0 and left a zero-byte binary behind. Every trap in
them was hit at least once here first.

## Pairing a headset, and what it protects

A machine on your network can be *seen* by anyone on it. It can only be *used*
by a headset you have deliberately let in.

**The first time**, the headset offers `pair` rather than `connect`. Open a
window on the server — the console has a button under **Headsets**, or from a
terminal:

```sh
linux-terminal-server --pair
```

Six digits appear, along with a fingerprint. Type the digits into the headset,
and check that the fingerprint it shows is the same one. That is the whole
ceremony, and it happens once per headset.

### What is actually going on

Three things are true after pairing, and each closes a different hole.

**The connection is encrypted.** TLS 1.3, always — the server refuses a plain
socket rather than accepting one quietly. Without this, everything below is
theatre: a password read off the wire by anyone on the café Wi-Fi protects
nobody.

**The headset knows which machine it is talking to.** The server signs its own
certificate, which proves nothing on its own — anyone can make one. What makes
it an identity is that pairing writes its fingerprint down and every connection
afterwards checks it. A machine that presents a different certificate is not
that machine, and the headset refuses it and says so. This is why the
fingerprint is shown twice and why comparing them is worth the two seconds.

**The server knows which headset is talking to it.** Six digits are traded, once,
for a 160-bit token that the two ends keep and you never see. The short code is
enough because it is deliberately fragile: it expires in five minutes, dies after
five wrong answers, is spent by the first success, and does not exist at all
until somebody at the machine opened a window. Take any one of those away and six
digits would not be enough.

Until a headset is paired, the network learns almost nothing: the discovery
answer is a name, a port and a version. Who is logged in, which Linux it runs and
where its shells start used to be broadcast to whoever asked, and now they travel
over the session, after the headset has proved who it is.

### If a headset is lost

Revoke the token in the console. Every paired headset must pair again, which is
the point: there is no way to invalidate one and not the others, and pretending
otherwise would be a worse answer than saying so.

The certificate is deliberately *not* rotated by that — changing it would make
every headset refuse the machine as an impostor, which is right when the machine
really has changed and wrong when you only meant to replace a token.

### What this does not protect against

Somebody wearing your headset. The token is on the device, and a device someone
else is holding is a device that can open a shell. That is the same threat model
as an unlocked laptop and has the same answer.

## Using it

Open **linux terminal**. Machines on the same network appear by themselves — the
server answers a UDP probe on port 9103. A machine somewhere else is typed in
once by address and remembered.

Pick one and you get a terminal window with tabs, and a bar window beside it.
Both are ordinary Horizon OS windows: the shell places them, resizes them, and
lets them sit next to a browser or anything else.

| | |
|---|---|
| new tab, in the current directory | `+` on the tab strip |
| close a tab | `×` on the tab itself |
| bring the bar back | `bar` on the tab strip |
| another machine | pick another server; it opens its own window |
| text size | `A−` / `A+` on the bar |
| the on-screen keyboard | the keyboard button |
| dictation | the microphone button |
| which build each side is running | beside the app's name, and on every server's card |
| who is connected, where voice goes | `http://localhost:9104` on the server |
| pair another headset | `Headsets` in the console, or `--pair` |
| unpair a machine | `unpair` on its card in the headset |

## How it is put together

```
Linux                                        Quest 3
┌───────────────────────────┐                ┌──────────────────────────┐
│ linux-terminal-server     │                │ ServersActivity          │
│   ├─ pty ── bash ── claude│◄──── TCP ─────►│ TermActivity  (tabs)     │
│   ├─ tcgetpgrp → /proc    │  framed msgs   │ BarActivity   (buttons)  │
│   ├─ builds the buttons   │◄──── UDP ──────┤ Discovery                │
│   └─ transcribes speech   │◄──── PCM ──────┤ Dictation                │
├───────────────────────────┤     text ─────►└──────────────────────────┘
│ web console :9104         │
└───────────────────────────┘
```

The buttons are computed on the **server**, not in the app. The server is the
side that knows what is running and what is installed, so teaching the bar a new
tool is an edit to one Go table and never a new APK.

Protocol, layout and the reasoning behind both:
[`docs/terminal-app.md`](docs/terminal-app.md).

## Text size

Readability in a headset is governed by the **angular size of a glyph**, not by
pixel density. Measured on this hardware, comfort begins at about **0.39° per
glyph** — the derivation is in [`docs/readability.md`](docs/readability.md), and
the client's defaults come from it.

## Where this came from

It grew out of [linux-vr](https://github.com/butschster/linux-vr), a VR desktop
that streams Ubuntu into the headset as video. The terminal turned out to be a
different product with a different shape — a client and a server, not a screen —
so it lives here. The streamed desktop stays there, and the two are good at
different things.

## Licence

MIT, except `client/app/src/main/java/com/termux/`, vendored from
[termux/termux-app](https://github.com/termux/termux-app) under Apache 2.0. See
[`client/app/NOTICE.md`](client/app/NOTICE.md) for what was changed and why.
