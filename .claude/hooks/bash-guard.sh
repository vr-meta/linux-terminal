#!/usr/bin/env bash
# The shell commands this repository has already been bitten by.
#
# Three checks, each from a trap that cost real time here:
#
#   1. `pkill -f linux-terminal` matches the command line of the shell running
#      the pkill and kills that instead. CLAUDE.md records this happening twice.
#      Blocked, with the command that actually works.
#
#   2. A signed build asked for without the signing environment produces an APK
#      signed with a debug key. It installs nowhere the release key is already
#      installed — INSTALL_FAILED_UPDATE_INCOMPATIBLE — and the build itself
#      succeeds, so nothing says anything until adb refuses. Warned, not blocked:
#      a debug-signed concept build is legitimate on a fresh device.
#
#   3. `adb` with two devices attached — the headset and an emulator, which is
#      the normal state while a client change is being checked — picks neither
#      and fails, or worse, picks the one you did not mean. Warned when more than
#      one device is present and no -s is given.
#
# Measured: 0 findings against ordinary commands in this session's history
# (git, make, gradlew, ss, adb -s ...); fires on the three shapes above.
#
# PreToolUse on Bash. Check 1 exits 2 (blocks); 2 and 3 exit 0 with context.
set -uo pipefail

command -v jq >/dev/null 2>&1 || exit 0

input=$(cat)
cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // empty')
[ -n "$cmd" ] || exit 0

# Only the part that is executed. A heredoc is data — a commit message, a config
# file being written — and the first version of this hook blocked the very commit
# that introduced it, because the message explains the `pkill` trap by naming it.
# That is the shape of false positive that teaches people to ignore a hook, so it
# is cut off here rather than argued with later.
run=${cmd%%<<*}

warn() {
    jq -nc --arg text "$1" \
        '{hookSpecificOutput: {hookEventName: "PreToolUse", additionalContext: $text}}'
    exit 0
}

# ---------------------------------------------------------------- 1. pkill
# Anchored to the start of a command segment, so `pkill` written about rather
# than run — in a message, a path, a grep pattern — goes past.
if printf '%s' "$run" | grep -qE '(^|[;&|]|&&|\|\|)[[:space:]]*(sudo[[:space:]]+)?pkill[^;&|]*linux-terminal'; then
    cat >&2 <<'EOF'
Blocked: `pkill -f linux-terminal` matches the command line of the shell that is
running the pkill, and kills that instead. This has happened twice in this
repository.

Find who actually holds the port, then kill it by pid:

    ss -ltnp | grep 9103
    kill <pid>

Or, when it is the installed service: systemctl --user restart linux-terminal-server
EOF
    exit 2
fi

# ------------------------------------------------------- 2. signing environment
if printf '%s' "$run" | grep -qE 'assemble(Concept|Release)'; then
    if ! printf '%s' "$run" | grep -qE 'signing\.env|ANDROID_KEYSTORE'; then
        warn 'This build produces a signed APK, but the signing environment is not in the command, so Gradle will fall back to the debug key. That APK cannot upgrade an install signed by the release key — adb answers INSTALL_FAILED_UPDATE_INCOMPATIBLE, and only at install time. Prefix it with:  set -a && . ~/.config/linux-terminal/signing.env && export ANDROID_KEYSTORE=$HOME/.config/linux-terminal/release.keystore && set +a'
    fi
fi

# ------------------------------------------------------------- 3. adb target
if printf '%s' "$run" | grep -qE '(^|[;&|] *)adb '; then
    if ! printf '%s' "$run" | grep -qE 'adb +(-s|-e|-d)\b|adb +devices|adb +kill-server|adb +start-server'; then
        if command -v adb >/dev/null 2>&1; then
            count=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device"' | wc -l)
            if [ "$count" -gt 1 ]; then
                names=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {printf "%s ", $1}')
                warn "More than one device is attached ($names) and this adb command names none of them, so it will fail or reach the wrong one. Add -s <serial>. The headset is the serial that is not emulator-*."
            fi
        fi
    fi
fi

exit 0
