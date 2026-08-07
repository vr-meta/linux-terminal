#!/usr/bin/env bash
# Install the linux-terminal server on this machine.
#
# One command from a clean checkout: builds the binary, puts it on the PATH, and
# starts it as a systemd user service so it comes back after a reboot.
#
#   ./install.sh              build, install and start
#   ./install.sh --no-service just the binary, run it yourself
#   ./install.sh --uninstall  take it all back out
#
# Everything happens under your own account. Nothing here needs root except
# writing into /usr/local/bin, and PREFIX=~/.local avoids even that.

set -euo pipefail

PREFIX="${PREFIX:-/usr/local}"
BINDIR="$PREFIX/bin"
BINARY="linux-terminal-server"
UNITDIR="$HOME/.config/systemd/user"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

say()  { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m==>\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m==>\033[0m %s\n' "$*" >&2; exit 1; }

uninstall() {
    say "Removing the service"
    systemctl --user disable --now "$BINARY.service" 2>/dev/null || true
    rm -f "$UNITDIR/$BINARY.service"
    systemctl --user daemon-reload 2>/dev/null || true
    say "Removing $BINDIR/$BINARY"
    if [ -w "$BINDIR" ]; then rm -f "$BINDIR/$BINARY"; else sudo rm -f "$BINDIR/$BINARY"; fi
    say "Done. Nothing of the server is left; the app in the headset is separate."
    exit 0
}

with_service=1
for argument in "$@"; do
    case "$argument" in
        --no-service) with_service=0 ;;
        --uninstall)  uninstall ;;
        -h|--help)    sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            die "unknown option: $argument" ;;
    esac
done

command -v go >/dev/null || die "Go is not installed. https://go.dev/dl/ or: sudo apt install golang-go"

say "Building the server"
(cd "$ROOT/server" && go build -trimpath -ldflags "-s -w" -o "$BINARY" .)

say "Installing into $BINDIR"
if [ -w "$BINDIR" ]; then
    install -Dm755 "$ROOT/server/$BINARY" "$BINDIR/$BINARY"
else
    sudo install -Dm755 "$ROOT/server/$BINARY" "$BINDIR/$BINARY"
fi

if [ "$with_service" -eq 1 ]; then
    if ! command -v systemctl >/dev/null; then
        warn "No systemd here — skipping the service. Run $BINARY yourself."
    else
        say "Installing the systemd user service"
        mkdir -p "$UNITDIR"
        sed "s|@BINDIR@|$BINDIR|g; s|@HOME@|$HOME|g" \
            "$ROOT/packaging/$BINARY.service" > "$UNITDIR/$BINARY.service"
        systemctl --user daemon-reload
        systemctl --user enable --now "$BINARY.service"

        # A service that failed to start is worse than no service: it looks
        # installed. Say so plainly rather than printing "Done".
        sleep 1
        if ! systemctl --user is-active --quiet "$BINARY.service"; then
            warn "The service is not running. Its own words:"
            systemctl --user --no-pager --lines=20 status "$BINARY.service" || true
            exit 1
        fi

        # Surviving a logout needs lingering, and it is off by default. Without
        # it the server dies when you close your session — which is exactly when
        # you would want to reach the machine from a headset.
        if ! loginctl show-user "$USER" -p Linger --value 2>/dev/null | grep -q yes; then
            warn "Enable lingering so the server survives logout:"
            warn "    sudo loginctl enable-linger $USER"
        fi
    fi
fi

address="$(hostname -I 2>/dev/null | awk '{print $1}')"
say "Installed."
echo
echo "  The headset should find this machine on its own — it answers discovery"
echo "  probes on udp/9103. If it does not appear, add it by address:"
echo
echo "      ${address:-<this machine's IP>}:9103"
echo
echo "  Logs:    journalctl --user -u $BINARY -f"
echo "  Stop:    systemctl --user stop $BINARY"
echo "  Remove:  ./install.sh --uninstall"
