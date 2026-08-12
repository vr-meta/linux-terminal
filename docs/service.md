# The server as a user service, and what that costs

`make install` puts the server behind `systemd --user`, which is the right place
for it: the shells it opens are yours, they inherit your environment, and nothing
about it wants root. The cost is that a service's environment is **not** a
terminal's environment, and two things that worked when the server was started by
hand quietly stopped working when it was installed.

Both were found the same way, and it is worth doing that way again:

```sh
tr '\0' '\n' < /proc/$(pgrep -f linux-terminal-server | head -1)/environ
systemctl --user show-environment
```

The first prints what the running server actually has. The second prints what
`systemd --user` has **now**. When those two disagree, something started before
the desktop did.

## The service's PATH is not the shell's PATH

A service enabled with `WantedBy=default.target` starts at login with the bare
system PATH — `/usr/local/bin`, `/usr/bin`, and so on. Nothing in `~/.bashrc` has
run, so `~/.local/bin` is not on it.

That is where the interesting tools live. `claude`, `codex`, and this machine's
own `cch` and `cgit` are all installed there, and `runActions` in
`server/actions.go` offers a button only for a command that exists — a button
that answers with `command not found` is worse than no button.

So the bar dropped four of its six commands, and only when the server was
installed properly. Started from a terminal for a day's work, the same binary
offered all of them, because a terminal's PATH is the one `.bashrc` built.

The fix is to ask the shell rather than to ask this process:

```
$SHELL -ic 'printf "\n%s\n" "$PATH"'
```

Interactive and **not** a login shell, because that is exactly what `pty.Start`
opens for a session. On this machine the difference is real: `-lc` reads
`.profile` and returns a PATH without `~/.nvm` and half the rest; `-ic` reads
`.bashrc` and returns what a session will actually search. It is read once and
cached, because it forks a shell.

## The tray icon needs a bus, not a display

`trayAvailable` used to require `DISPLAY` or `WAYLAND_DISPLAY`. Neither is in a
user service's environment at the moment it starts, and neither ever appears in
it afterwards — the graphical session imports them into `systemd --user` after
the service is already running, which is why `systemctl --user show-environment`
lists `WAYLAND_DISPLAY` while `/proc/<pid>/environ` of the server does not.

The result was a server that had been running for three days on a desktop machine
and had never once tried to show an icon.

What the tray actually needs is somebody willing to hold one, and that is a name
on the session bus:

```sh
busctl --user list | grep StatusNotifierWatcher
```

So the server now waits for `org.kde.StatusNotifierWatcher` to be owned and only
then hands the goroutine to systray. Waiting is free — that goroutine would be
blocked inside the library anyway — and it means the icon appears when you log in
rather than never. `fyne.io/systray` already re-registers by itself if the
desktop restarts later; what it cannot do is start on a desktop we refused to
look for.

To see whether the icon is registered, and which process owns it:

```sh
busctl --user list | grep StatusNotifierItem
busctl --user status :1.1458 | grep -E '^(PID|Comm)='
```
