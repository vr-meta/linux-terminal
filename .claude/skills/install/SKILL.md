---
name: install
description: Install, update or remove linux-terminal — the Go server on a Linux machine and the Android client on a Quest headset. Use when the user asks to install it, set it up on a new machine, get it running, or take it back off.
---

# Installing linux-terminal

Two halves installed separately. The **server** goes on the Linux machine you
want a shell on; the **client** goes in the headset. Neither needs the other to
be installed first.

Do not report success from a command exiting 0. Every step below has a check
that establishes the fact.

## Server

### What has to be there first

```sh
command -v go || echo "install Go: sudo apt install golang-go"
```

Go is the only requirement. Everything else the server uses is in the standard
library plus one dependency it vendors at build time.

### Install

```sh
cd <repo>
./install.sh
```

That builds a static binary, installs it to `/usr/local/bin` (with `sudo` only
if that directory is not writable), and starts it as a **systemd user service**.

Variants worth knowing:

| Situation | Command |
|---|---|
| no root at all | `PREFIX=~/.local ./install.sh` |
| no service, run it by hand | `./install.sh --no-service` |
| take it back out | `./install.sh --uninstall` |

### Verify — by fact, not by exit code

```sh
systemctl --user is-active linux-terminal-server     # expect: active
ss -ltnp | grep 9103                                 # expect: one listener
```

If it is not active, the service's own words are the answer:

```sh
journalctl --user -u linux-terminal-server -n 30 --no-pager
```

### The one thing install.sh cannot do for you

A user service dies when you log out unless lingering is enabled — and logged
out is exactly the state a machine is in when you want to reach it from a
headset. The installer warns; enabling it needs root:

```sh
sudo loginctl enable-linger $USER
loginctl show-user $USER -p Linger --value      # expect: yes
```

### Firewall

The server needs TCP **and** UDP on 9103 — TCP carries the sessions, UDP carries
discovery. If the headset can connect only after typing the address by hand, UDP
is what is blocked.

```sh
sudo ufw allow 9103/tcp && sudo ufw allow 9103/udp    # only if ufw is active
```

## Client

### What has to be there first

```sh
java -version                    # JDK 21; a JRE is not enough, javac must exist
ls "$ANDROID_HOME" 2>/dev/null || cat client/local.properties
adb devices                      # the headset, with developer mode on
```

`client/local.properties` is not in git and must contain `sdk.dir=/path/to/Android/Sdk`.

### Install

```sh
make apk
```

### Verify

```sh
adb shell pm list packages | grep linuxterminal
adb shell am start -n dev.butschster.linuxterminal/.ServersActivity
adb shell pidof dev.butschster.linuxterminal && echo ALIVE || echo DEAD
```

`am start` printing "Starting:" means nothing on its own — check `pidof`.

### If adb says "no permissions"

There is no udev rule for the Quest. This is not an adb problem and restarting
the server will not fix it:

```sh
echo 'SUBSYSTEM=="usb", ATTR{idVendor}=="2833", MODE="0666", GROUP="plugdev"' \
  | sudo tee /etc/udev/rules.d/51-android.rules
sudo udevadm control --reload-rules && sudo udevadm trigger
adb kill-server && adb devices
```

### If nothing appears in the app

Discovery is a UDP broadcast, and plenty of networks drop those. In order:

1. Is the server running at all — `ss -ltnp | grep 9103` on the Linux machine.
2. Are they on the same subnet — compare `hostname -I` with the headset's IP.
3. Is UDP blocked — add the machine by address in the app; if that works,
   discovery is the only thing broken and the firewall is where to look.

## Updating

```sh
git pull
./install.sh          # rebuilds, replaces the binary, restarts the service
make apk              # replaces the app; -r keeps its saved servers
```

Server and client speak a versioned protocol but are not lock-stepped. If they
disagree, the newest of the two is the one to update the other to.

## Removing everything

```sh
./install.sh --uninstall     # binary, service, unit file
adb uninstall dev.butschster.linuxterminal
```

Saved servers live in the app's own storage and go with it. Nothing is left on
the Linux machine outside `/usr/local/bin` and `~/.config/systemd/user`.
