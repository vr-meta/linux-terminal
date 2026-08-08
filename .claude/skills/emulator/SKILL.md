---
name: emulator
description: Test the linux-terminal client on an Android emulator instead of a headset — bring an AVD up, install the APK, and drive the whole flow (discovery, pairing, a live shell) from the command line. Use when no headset is attached, when the headset is in use or asleep, or to check a client change quickly.
---

# Testing without a headset

Most of this client is ordinary Android. The servers screen, the pairing dialog,
the tabs, the bar and the terminal view are the same code in an emulator as in
the headset, and all of it can be driven from a shell — tap, type, screenshot,
read the result. That is faster than putting a headset on, and it works while
somebody else is wearing it.

Everything below was run on this machine. Where a number appears it was measured
here, not quoted.

## What the emulator cannot tell you

Say this out loud before reporting anything as verified:

| | |
|---|---|
| **Window placement, the three-window limit, tabs vs new windows** | that is the Horizon OS shell, and the emulator runs the ordinary one |
| **`documentLaunchMode` behaviour** | same reason — the emulator stacks activities where the headset opens windows |
| **Readability, text size, comfort** | governed by angular size through optics; a monitor cannot stand in |
| **Anything immersive or OpenXR** | not in this app at all, and not in this AVD |

Everything else — a layout, a crash, a protocol change, a button that does
nothing, the pairing flow — the emulator answers honestly.

## The AVD

One already exists here, called `linuxterm`: android-34, `google_apis`, x86_64,
**2560×1600 at 320 dpi**. The shape is the point. The client's layouts are wide
and landscape because a headset panel is; a phone-shaped AVD reflows them into
something you will then "fix" for no reason.

```sh
SDK=$(sed -n 's/^sdk.dir=//p' client/local.properties)   # /home/butschster/Dev/Android/Sdk here
"$SDK/emulator/emulator" -list-avds                      # linuxterm
```

If it is missing, build it with the same shape:

```sh
"$SDK/cmdline-tools/latest/bin/sdkmanager" "system-images;android-34;google_apis;x86_64"
"$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n linuxterm \
  -k "system-images;android-34;google_apis;x86_64" -d "10.1in WXGA (Tablet)"
```

then set `hw.lcd.width = 2560`, `hw.lcd.height = 1600`, `hw.lcd.density = 320`
in `~/.android/avd/linuxterm.avd/config.ini`.

## Bring it up

**It will not stay up, so check that it is there before every batch of work.**
An emulator launched from an agent's tool call shuts itself down after some
minutes — cleanly, saving a snapshot, with nothing in the log to say why.
`setsid` bought longer (six minutes rather than two) and did not fix it. Treat
the emulator as something that goes away and can be brought back in ten seconds
from its snapshot, not as something you start once:

```sh
adb devices | grep -q emulator-5554 || echo "gone — relaunch"
```

```sh
setsid nohup "$SDK/emulator/emulator" -avd linuxterm \
  -gpu host -memory 2048 -cores 4 > /tmp/emu.log 2>&1 < /dev/null & disown
```

`-gpu host` on the command line overrides `hw.gpu.enabled = no` in the AVD
config, and is what you want on this machine — it has a working GPU, and
`swiftshader_indirect` renders on the CPU and drags the desktop with it.

Wait for the fact, not for a duration. Cold boot took **25 s** here, a snapshot
boot **11 s**, so any fixed `sleep` is either a lie or a waste:

```sh
until [ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]
do sleep 3; done
```

Confirm it is alive before blaming your APK for anything — `adb: device
'emulator-5554' not found` on four commands in a row is the emulator having
gone, not your build:

```sh
adb devices                     # emulator-5554  device
ps -o pid,etimes= -p $(pgrep -f "qemu-system-x86_64.*linuxterm" | head -1)
```

Group the work accordingly: install, launch, tap and screenshot in one batch
rather than one command per call.

## Two devices attached — the trap that wastes the first ten minutes

With a headset plugged in and an emulator running, **bare `adb` refuses**:

```
adb: more than one device/emulator
```

Every command needs `-s emulator-5554`. And the refusal hides easily — piping
adb into `head` or `tail` reports the *pipe's* exit status, so this prints
`exit=0` on a command that did nothing:

```sh
adb install -r app.apk | head -3 ; echo "exit=$?"      # measured: exit=0, install failed
```

Read the output. `Success` is the only line that means it worked.

## Install the client

```sh
adb -s emulator-5554 install -r client/app/build/outputs/apk/debug/app-debug.apk
```

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the copy already there was signed
with a different key — a release APK over a debug build or the reverse. There is
no way through but uninstalling, which loses the emulator's saved servers and
its pairing:

```sh
adb -s emulator-5554 uninstall dev.butschster.linuxterminal
adb -s emulator-5554 install linux-terminal.apk
```

**Grant the microphone before the flow asks for it**, otherwise the permission
dialog lands on top of the bar at the moment you are trying to tap it:

```sh
adb -s emulator-5554 shell pm grant dev.butschster.linuxterminal android.permission.RECORD_AUDIO
```

## What the guest can reach — measured, because it is not obvious

The emulator is behind QEMU's NAT on 10.0.2.0/24. Two things follow and both
were checked here:

**The host machine is at `10.0.2.2`, and discovery finds it.** The servers
screen listed `butschster-pc` at `10.0.2.2` with no help — the broadcast probe
reaches the host's listener and the answer comes back. So the ordinary path
works; you do not have to type an address.

```sh
# the announcement, straight from the guest
adb -s emulator-5554 shell 'echo -n "LINUXVR-DISCOVER 1" | timeout 4 nc -u 10.0.2.2 9103'
# {"proto":"linux-vr","version":2,"name":"butschster-pc","port":9103,"release":"dev"}
```

**Other machines on the physical LAN will not appear**, because the guest's
broadcast domain is the virtual one. Add those by address in the app's own
field. (Reasoned from the NAT, not measured — there was one server to find.)

**The session looks like it comes from the host.** The console showed the
emulator's session with the host's own LAN address, not 10.0.2.x, which is what
NAT does. Do not read that as a headset connecting.

**`nc` exit codes in the guest are worthless.** A closed port exits 0 exactly
like an open one — measured against port 9199 with nothing on it. Judge by the
bytes that come back.

## Drive the whole flow from the command line

This is the loop worth having, and it was run start to finish: discovered the
server, paired it, opened a shell, ran a command and read the output — no
headset, no hands.

```sh
E="adb -s emulator-5554"

$E shell am start -n dev.butschster.linuxterminal/.ServersActivity
$E exec-out screencap -p > /tmp/shot.png        # look, then tap what you saw
```

**Screenshots come back at the panel's own 2560×1600.** If you are reading one
scaled down, multiply the coordinates back up before tapping — a tap at the
coordinates you *read* lands somewhere else entirely.

```sh
$E shell input tap 2417 307                     # pair
```

Get the code from the server side, in another terminal:

```sh
./server/linux-terminal-server --pair           # or the installed binary
```

The fingerprint it prints must match the one the dialog shows; that is the check
the ceremony exists for, and it holds in the emulator too.

```sh
$E shell input text 159398                      # the six digits; the card turns to unpair / connect
$E shell input tap 2390 307                     # connect
```

Then type into the shell itself. **A space is `%s`** in `input text` — a literal
space silently ends the argument:

```sh
$E shell input text "uname%s-r"
$E shell input keyevent KEYCODE_ENTER
$E exec-out screencap -p > /tmp/after.png       # 7.0.0-28-generic, from the host
```

Prove which activity is in front rather than guessing from a picture:

```sh
$E shell dumpsys activity activities | grep -o "linuxterminal/\.[A-Za-z]*" | sort -u
```

## Reading the log

**The app logs under two tags.** `linux-terminal` for discovery, dictation and
the terminal activity; `linux-vr`, left over from the project this grew out of,
for `HostSession` and `TermView` — which is to say for the connection and the
rendering, the two things you actually want when a terminal is blank. Filtering
on one tag hides half of it:

```sh
adb -s emulator-5554 logcat -c
adb -s emulator-5554 logcat -s linux-terminal linux-vr AndroidRuntime
```

## Traps with a receipt

**Check free memory before launching.** A 2 GB guest on a machine already
holding a large VM is how the desktop was lost once here: systemd-oomd killed
the biggest process, gnome-shell restarted, and the emulator got the blame it
did not deserve.

```sh
free -g          # want several GB available, not "free"
```

**The emulator is a separate paired device.** Pairing it spends a six-digit
code and stores a token on it, exactly like a headset. That is harmless, but if
you then revoke the token in the console to clean up, **every real headset must
pair again** — a single token is shared.

**A snapshot is saved on exit and loaded next time.** That is why the second
boot is 11 s and also why yesterday's state comes back. `-no-snapshot-load`
starts clean when you need to test first-run behaviour, such as the servers
screen with nothing saved.
