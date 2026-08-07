---
name: headset
description: Work with a Quest headset from a Linux machine over adb — connect it by cable or over Wi-Fi, see whether it is awake and whether an app is alive, capture logs before they are evicted, and copy screenshots and recordings off it. Use for any headset-side operation that is not installing the app.
---

# Talking to a Quest from Linux

Everything here is `adb`. The headset is an Android device that lies about being
a normal one, so each check below establishes a fact rather than trusting an
exit code.

## Is it there at all

```sh
adb devices          # expect: <serial>	device
```

| What it says | What it means |
|---|---|
| `unauthorized` | the "allow USB debugging" dialog is waiting **inside** the headset — put it on |
| `no permissions` | no udev rule, see below; restarting adb will not fix it |
| nothing listed | cable, developer mode, or the headset is off |

### "no permissions"

```sh
echo 'SUBSYSTEM=="usb", ATTR{idVendor}=="2833", MODE="0666", GROUP="plugdev"' \
  | sudo tee /etc/udev/rules.d/51-android.rules
sudo udevadm control --reload-rules && sudo udevadm trigger
adb kill-server && adb devices
```

`2833` is Oculus. The rule is per machine, not per headset.

### adb drops the device without saying so

`adb devices` has gone from listing the headset to `no devices/emulators found`
mid-session, while the app on it was demonstrably running — the server was
logging discovery probes from its address the whole time. **A negative from adb
is not evidence that something is wrong in the headset.** Confirm from the other
side before believing it:

```sh
adb kill-server && adb devices          # usually enough
```

## Is it awake

The first thing to check when a working setup goes quiet, and not a bug:

```sh
adb shell dumpsys power | grep -m1 mWakefulness=      # expect: Awake
```

Taken off, it sleeps: activities pause, nothing connects, streams stop. `adb`
itself keeps working — you can read files and install packages on a sleeping
headset, you just cannot expect anything on screen to be running.

## Is an app alive

```sh
adb shell pidof dev.butschster.linuxterminal && echo ALIVE || echo DEAD
```

**A successful `am start` proves nothing.** It prints `Starting:` whether or not
the process survives the next second.

```sh
adb shell am start -n dev.butschster.linuxterminal/.ServersActivity
adb shell am force-stop dev.butschster.linuxterminal
```

## Logs, before they are gone

The Horizon OS compositor floods logcat and evicts everything within seconds, so
`logcat -d` after the fact gives you the flood and not your crash. Capture live,
**started before** the thing you want to see:

```sh
adb logcat -c && adb logcat -s linux-terminal AndroidRuntime > /tmp/log.txt &
adb shell am start -n dev.butschster.linuxterminal/.ServersActivity
```

`AndroidRuntime` is what carries a Java crash; without it a dead app looks like
silence.

## Getting files off it — no mounting involved

**The headset does not appear as a mountable filesystem while adb is on.** Its
`mtp-probe` verdict on this machine was literally `was not an MTP device`, so
looking for a mount point or a gvfs entry is looking for something that is not
there. Nothing needs to be mounted — `adb` reads the filesystem directly, and it
works even while the headset is asleep:

```sh
adb shell ls /sdcard/Oculus/            # Avatars  Screenshots  VideoShots
adb shell ls -la /sdcard/Oculus/VideoShots
adb pull /sdcard/Oculus/VideoShots/<name>.mp4 ~/Videos/
adb pull /sdcard/Oculus/Screenshots ~/Pictures/quest/     # a whole directory
```

Recordings are large — a few minutes of capture is tens of megabytes — so pull
by name rather than the whole directory unless you mean it.

Taking one from the Linux side instead:

```sh
adb exec-out screencap -p > /tmp/shot.png          # a flat 2D grab
adb shell screenrecord --time-limit 20 /sdcard/rec.mp4 && adb pull /sdcard/rec.mp4
```

## Untethered, over Wi-Fi

Useful because the headset is a thing you wear and the cable is a thing you trip
over. Start attached, then switch:

```sh
adb tcpip 5555
adb shell ip route | grep -oE 'src [0-9.]+'        # the headset's address
adb connect <that address>:5555
adb devices                                        # expect the ip:5555 entry
```

Unplug the cable after `adb connect` says connected. Going back:

```sh
adb disconnect && adb usb
```

The headset forgets tcpip mode when it reboots. Both machines must be on the
same subnet — the same requirement discovery has, so if the app finds the
server, adb will reach the headset.

## What this project runs

| | |
|---|---|
| package | `dev.butschster.linuxterminal` |
| front door | `.ServersActivity` |
| what it talks to | tcp/9103 for sessions, udp/9103 for discovery |

```sh
adb shell pm list packages | grep linuxterminal
adb shell dumpsys package dev.butschster.linuxterminal | grep -m1 versionName
```

## Traps

**Horizon OS reports API 34 but ships a reduced set of system services.** Check a
service by fact, not by `Build.VERSION`. Every version of Moonlight crashes on
this, because `GameManager` returns `null`.

**The headset sleeping is the most common cause of "it stopped working"** and
costs nothing to rule out. Check it first, every time.
