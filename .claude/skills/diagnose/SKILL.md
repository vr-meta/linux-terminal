---
name: diagnose
description: Work out why linux-terminal is not working — nothing appears in the app, a terminal window is blank or frozen, the bar shows the wrong buttons, keys do nothing, or dictation fails. Use before changing any code.
---

# Diagnosing linux-terminal

Every check here replaces a guess with a fact. Most of these symptoms were
misdiagnosed at least once before the check existed.

## Rule zero: check what you are actually running

A second server fails to bind and dies quietly while the first keeps serving —
so a code change appears to have done nothing, and you debug the wrong binary.

```sh
ss -ltnp | grep 9103          # who really holds the port
```

Kill it **by pid**. `pkill -f linux-terminal` matches the command line of the
shell running the `pkill` and kills that instead; this has happened twice.

Same on the client side: `adb install` printing nothing but "Performing Streamed
Install" means it did not finish. Look for `Success`.

## Nothing appears in the connection manager

Discovery is a UDP broadcast to port 9103, answered unicast.

1. **Is the server up?** `ss -ltnp | grep 9103` on the Linux machine.
2. **Did the probe arrive?** The server logs `discovery probe from <ip>` for
   every sweep. If that line never appears, nothing reached it.
3. **Same subnet?** Compare `hostname -I` on the machine with the headset's
   address (it shows in the server log once a probe lands).
4. **Prove the rest works** by adding the machine by address. If a typed address
   connects, only discovery is broken and the network is dropping broadcasts.

Test the server's answer from the Linux side, without a headset:

```sh
python3 - <<'EOF'
import socket, json
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
s.settimeout(1.5)
s.sendto(b'LINUXVR-DISCOVER 1', ('255.255.255.255', 9103))
try:
    while True:
        data, addr = s.recvfrom(2048)
        print(addr[0], json.loads(data))
except socket.timeout:
    pass
EOF
```

## The headset is asleep

Taken off, it sleeps: the activity pauses and nothing connects. This is not a
bug and it is the **first** thing to check when a working setup goes quiet.

```sh
adb shell dumpsys power | grep mWakefulness=      # expect: Awake
```

## The app is not running

```sh
adb shell pidof dev.butschster.linuxterminal && echo ALIVE || echo DEAD
```

A successful `am start` proves nothing on its own.

## Capture the log before it is gone

The Horizon OS compositor floods logcat and evicts everything within seconds.
Start the capture **before** launching:

```sh
adb logcat -c && adb logcat -s linux-terminal AndroidRuntime > /tmp/log.txt &
adb shell am start -n dev.butschster.linuxterminal/.ServersActivity
```

The client logs its grid on connect: `grid 165x49 font 20px cell 12.0x24`. If
that line is absent, the view never got a size or the session never connected.

## A blank or frozen terminal

- The bar says **"no host — retrying"**: the client is reconnecting every two
  seconds and reporting why. Read the reason before anything else.
- **Text but no prompt**: the shell started and printed nothing. Check the
  server log for the session line, and try `--shell /bin/bash` explicitly.
- **Frozen picture, everything else fine**: check wakefulness first.

## The bar shows the wrong buttons

The server reads `/proc/<pgid>/cmdline` of the pty's foreground process group.
Reproduce on the Linux side — the same code path, no headset needed:

```sh
./server/linux-terminal-server --dump-context ~/some/project
```

If that produces the right groups and the headset shows something else, the
fault is in the client. If it produces the wrong ones, look at `toolNames` and
`toolActions` in `server/`: a tool launched through an interpreter puts the name
that matters after `argv[0]`, which is what the classifier scans for.

## Keys do nothing

The fixed keys are built by the client and never leave it — if `Esc` does
nothing, the socket is the suspect, not the tables. The dynamic ones come from
the server; a missing group means the context did not arrive.

## The shell behaves oddly

No prompt colours, missing aliases, or Claude Code warning about an inherited
session — the environment is built in `shellEnv()` in `server/session.go`. It is
deliberately not a login shell (on a pty, bash is interactive already and reads
`.bashrc`), and it drops `CLAUDECODE` and `CLAUDE_CODE_*` so a `claude` started
in the headset is not a child of whatever session launched the server.

## The whole desktop froze

**You do not need to reboot.** Every frozen boot so far shut down cleanly,
which means the machine was alive under the frozen picture — only the GUI was
gone. Switch to a text console and you keep the session's work:

```sh
# Ctrl+Alt+F3, log in, then look while it is still broken:
ps -o pid,pcpu,etime,stat -C gnome-shell
journalctl --user -n 50 --no-pager
```

**The tray froze this desktop four times, and the library was why.**
`slytomcat/systray` did it; `fyne.io/systray` — what linux-vr has always used,
menu and all — does not. If a freeze happens while `--tray` is on, check which
library the binary was built against before blaming anything else, and remember
the server logs to a file, so grepping the journal for it proves nothing either
way.

A freeze during `install.sh` leaves zero-byte files behind. Check for them
before reinstalling, or you will run an empty binary and debug the silence:

```sh
ls -la /usr/local/bin/linux-terminal-server ~/.config/systemd/user/linux-terminal-server.service
```

Indicator errors in the journal are frequently somebody else's — take a
baseline before blaming ours:

```sh
# with the server NOT running, for 30 seconds
timeout 30 journalctl --user -f -n0 | grep -c PropertyNotFound
```

On this machine that is 2 per 30s from other indicators, with our server dead.

## No tray icon

Only appears where there is a desktop session. The server needs
`DBUS_SESSION_BUS_ADDRESS` and one of `WAYLAND_DISPLAY` / `DISPLAY`; as a
systemd user service those come from the session, so check what it actually
sees:

```sh
systemctl --user show-environment | grep -E 'WAYLAND_DISPLAY|DISPLAY|DBUS'
```

If the item is on the bus but nothing is drawn, the shell is the missing part —
GNOME renders these only through the AppIndicator extension:

```sh
busctl --user list | grep -i statusnotifier      # is our item registered?
gnome-extensions list | grep -i appindicator      # can the shell draw it?
```

## Dictation

Recognition happens on the server, over the session that already exists — there
is no second port and no separate agent. What there is, is an endpoint the
server posts to, and it may not be set:

```sh
curl -s localhost:9104/api/state | python3 -m json.tool | grep -A5 asr
```

The server also says so on startup: `dictation is off — no endpoint configured`.

When it is configured and still fails, the server logs the endpoint's **own**
words — a wrong key or a wrong model name announces itself there:

```sh
journalctl --user -u linux-terminal-server -n 30 --no-pager | grep -i dictation
```

Reading the meter in the headset:

| What you see | What it means |
|---|---|
| flat meter | the microphone never delivered samples — check the runtime permission |
| meter moves, then an error | the audio reached the endpoint; the endpoint is the suspect |
| meter moves, no text, no error | recognised silence — nothing was said loudly enough |
