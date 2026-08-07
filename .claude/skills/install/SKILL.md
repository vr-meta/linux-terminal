---
name: install
description: Install, update or remove linux-terminal from its GitHub releases — the Go server on a Linux machine and the Android client on a Quest headset, without cloning or building anything. Use when asked to install it, set it up on a new machine, update to the latest version, or take it back off.
---

# Installing linux-terminal

Two halves, installed separately, neither needing the other first. **Nothing
here is built** — the releases carry a static server binary for amd64 and arm64
and a signed APK, so a target machine needs no Go, no JDK and no Android SDK.

Building from a clone is the *development* path and is at the bottom.

Do not report success from a command exiting 0. Every step has a check that
establishes the fact.

```sh
REPO=vr-meta/linux-terminal
```

## Server

### Fetch the latest release

The arch matters and guessing it wrong produces a binary that will not run:

```sh
case "$(uname -m)" in
  x86_64)  arch=amd64 ;;
  aarch64) arch=arm64 ;;
  *) echo "no release build for $(uname -m)"; exit 1 ;;
esac

tmp=$(mktemp -d) && cd "$tmp"
curl -fL -O "https://github.com/$REPO/releases/latest/download/linux-terminal-server-linux-$arch"
curl -fL -O "https://github.com/$REPO/releases/latest/download/checksums.txt"
sha256sum --ignore-missing -c checksums.txt        # must say: OK
```

`-f` matters: without it curl writes GitHub's HTML error page into the file and
you install a 400-byte "binary" that debugs as nonsense.

### Put it in place

```sh
chmod +x "linux-terminal-server-linux-$arch"
sudo install -m 0755 "linux-terminal-server-linux-$arch" /usr/local/bin/linux-terminal-server
linux-terminal-server --version                    # must print a version, not a shell error
```

No root available:

```sh
mkdir -p ~/.local/bin && install -m 0755 "linux-terminal-server-linux-$arch" ~/.local/bin/linux-terminal-server
```

…and make sure `~/.local/bin` is on `PATH` in the shell the service will use.

### Make it survive a reboot

The unit template lives in the repository and can be taken without cloning:

```sh
mkdir -p ~/.config/systemd/user
curl -fL "https://raw.githubusercontent.com/$REPO/main/packaging/linux-terminal-server.service" \
  | sed -e "s|@BINDIR@|/usr/local/bin|" -e "s|@HOME@|$HOME|" \
  > ~/.config/systemd/user/linux-terminal-server.service

systemctl --user daemon-reload
systemctl --user enable --now linux-terminal-server
```

Verify by fact:

```sh
systemctl --user is-active linux-terminal-server     # active
ss -ltnp | grep 9103                                 # exactly one listener
```

If it is not active, the service's own words are the answer, not a guess:

```sh
journalctl --user -u linux-terminal-server -n 30 --no-pager
```

### Lingering — the step that is skipped and then costs an evening

A user service dies at logout, and logged out is exactly the state a machine is
in when you want to reach it from a headset:

```sh
sudo loginctl enable-linger $USER
loginctl show-user $USER -p Linger --value      # yes
```

### Firewall

TCP **and** UDP on 9103 — TCP carries sessions, UDP carries discovery. If the
headset connects only when you type the address by hand, UDP is what is blocked.

```sh
sudo ufw status | head -1                       # skip the rest if inactive
sudo ufw allow 9103/tcp && sudo ufw allow 9103/udp
```

## Client

Needs only `adb`. See the **headset** skill for connecting one.

```sh
curl -fL -O "https://github.com/$REPO/releases/latest/download/linux-terminal.apk"
adb install -r linux-terminal.apk               # look for: Success
```

`-r` keeps the app's saved servers. Verify:

```sh
adb shell pm list packages | grep linuxterminal
adb shell am start -n dev.butschster.linuxterminal/.ServersActivity
adb shell pidof dev.butschster.linuxterminal && echo ALIVE || echo DEAD
```

**`adb install` printing only `Performing Streamed Install` means it did not
finish.** `Success` is the line that counts.

### If the install is refused

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the installed copy was signed with a
different key — typically a locally built debug APK being replaced by a released
one. Uninstall first, losing the saved servers:

```sh
adb uninstall dev.butschster.linuxterminal && adb install linux-terminal.apk
```

## Pairing the headset

A freshly installed server lets nobody in until a headset is paired: the card in
the app offers **pair** rather than **connect**. Open a window on the server and
type the six digits it shows.

```sh
linux-terminal-server --pair          # or the console at http://localhost:9104
```

The **pairing** skill covers the whole procedure, what it protects, and how to
take access back.

## Dictation

Optional and off until an endpoint is set. Open `http://localhost:9104` and
choose:

- **OpenAI** — paste a key, nothing else.
- **Self-hosted** — a Whisper of your own, so the audio never leaves the
  building. `docs/voice.md` has a working recipe and measured numbers.

The key is stored server-side at `~/.config/linux-terminal/config.json`, mode
0600, and is never sent to the headset. The console binds to localhost because
it can close sessions and set that key; reach it from elsewhere by tunnelling,
never by widening it:

```sh
ssh -L 9104:localhost:9104 user@machine
```

## Updating

Same download, then restart. Server and client speak a versioned protocol and
are not lock-stepped; if they disagree, update the older one.

```sh
systemctl --user stop linux-terminal-server
# …re-run the fetch and install steps above…
systemctl --user start linux-terminal-server
linux-terminal-server --version

adb install -r linux-terminal.apk
```

## Removing it

```sh
systemctl --user disable --now linux-terminal-server
rm ~/.config/systemd/user/linux-terminal-server.service && systemctl --user daemon-reload
sudo rm /usr/local/bin/linux-terminal-server
rm -rf ~/.config/linux-terminal          # the transcription key lives here
adb uninstall dev.butschster.linuxterminal
```

## Building from a clone instead

Only for working on it. Needs Go for the server, and JDK 21 plus an Android SDK
with `client/local.properties` containing `sdk.dir` for the client.

```sh
git clone https://github.com/vr-meta/linux-terminal && cd linux-terminal
./install.sh              # builds, installs, starts the service
make apk                  # builds the client and installs it on the headset
```

| Situation | Command |
|---|---|
| no root at all | `PREFIX=~/.local ./install.sh` |
| no service, run by hand | `./install.sh --no-service` |
| take it back out | `./install.sh --uninstall` |

## Traps with a receipt

**A zero-byte binary debugs as silence.** An install interrupted partway leaves
`/usr/local/bin/linux-terminal-server` at 0 bytes, owned by root, and the
service then fails with nothing useful to say. Check the size before believing
anything else:

```sh
ls -la /usr/local/bin/linux-terminal-server
```

**Check who holds the port before diagnosing anything.** A second server fails
to bind and dies quietly while the first keeps serving, so a fresh install looks
like it changed nothing:

```sh
ss -ltnp | grep 9103
```

Kill it **by pid**. `pkill -f linux-terminal` matches the command line of the
shell running the `pkill` and kills that instead — this has happened twice.
