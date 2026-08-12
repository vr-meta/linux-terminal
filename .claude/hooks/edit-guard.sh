#!/usr/bin/env bash
# What must not appear in a file the moment it is saved.
#
# Every check below is a trap already recorded in CLAUDE.md or docs/, and every
# one of them is silent on the code as it stands. The measurements are against
# the whole tracked tree, not against a sample:
#
#   setForeground            0 hits   — not drawn on these TextViews; trying it
#                                       emptied every arrow and Backspace once
#   BlurMaskFilter (used)    0 hits   — 3 more in javadoc explaining why not,
#                                       which is why the pattern is `new`/`set`
#                                       and not the bare word
#   androidx / material      0 hits   — the client has no dependencies at all
#   import "C"               0 hits   — CGO stays off; one static binary is worth
#                                       more than any library that would end it
#   vendored com/termux/    12 files  — Apache-2.0, changes belong in NOTICE.md
#
# PostToolUse on Edit|Write: a warning, never a block. It is a note next to the
# save, and the model decides — some of these are legitimate in a comment.
set -uo pipefail

command -v jq >/dev/null 2>&1 || exit 0

input=$(cat)
file=$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty')
[ -n "$file" ] || exit 0
[ -f "$file" ] || exit 0

findings=""
note() { findings="${findings}${findings:+$'\n'}- $1"; }

# A reminder is not a violation, and saying so matters: the vendored check fires
# on every save under com/termux/, where editing is allowed and merely has a
# consequence. Measured separately: 0 rule findings and 12 reminders across 99
# tracked source files, and all 12 are the vendored emulator.
reminders=""
remind() { reminders="${reminders}${reminders:+$'\n'}- $1"; }

case "$file" in
    *.java)
        # A drawn glyph lives in the key's background. The obvious fix is a
        # foreground, and it is simply not drawn here — this cost a session.
        if grep -qE '\.setForeground\(' "$file"; then
            note 'setForeground on a View: not drawn on these TextViews. It emptied every arrow and the backspace key while looking correct. Keep the glyph in the background and re-stack it (Buttons keeps a WeakHashMap for exactly this).'
        fi
        # Matched as a call, not as a word: the three javadoc mentions of the
        # class are the argument for avoiding it and must stay quiet.
        if grep -qE 'new BlurMaskFilter|\.setMaskFilter\(' "$file"; then
            note 'BlurMaskFilter forces the view onto a software layer, and the left half of the bar is rebuilt whenever the foreground process changes. Stack translucent copies instead — see PhysicalKey.'
        fi
        if grep -qE '^\s*import\s+(androidx|com\.google\.android)' "$file"; then
            note 'The client has zero dependencies and no Compose — an import from androidx or the material library adds one. Draw it, or ask first.'
        fi
        ;;
    *.go)
        if grep -qE '^\s*import\s+"C"' "$file"; then
            note 'import "C" turns CGO on. The server is one static binary that runs on a distribution older than whatever built it, and that is worth more than any library which would take it away.'
        fi
        ;;
esac

case "$file" in
    */com/termux/*)
        remind 'This is vendored Apache-2.0 code from Termux. It stays as close to upstream as it can, and a change here is recorded in client/app/NOTICE.md.'
        ;;
esac

# Cyrillic outside a quoted string. Everything committed here is written in
# English; the one existing instance is a quoted Russian word inside an English
# comment in Glyphs.java, which is a citation and stays legal — hence the check
# for text that is NOT inside quotes.
case "$file" in
    *.go|*.java|*.md|*.kts|*.yaml|*.yml|*.sh|*.html|*.css|*.js)
        if grep -nP '[\x{0400}-\x{04FF}]' "$file" 2>/dev/null \
            | grep -vP '["«][^"»]*[\x{0400}-\x{04FF}][^"»]*["»]' | grep -q .; then
            note 'Cyrillic outside a quotation. Everything committed here is written in English — docs, comments, log strings, commit messages — because the repository is public and mixed language makes it useless to an outside reader. Conversation stays Russian; files do not.'
        fi
        ;;
esac

message=""
[ -n "$findings" ] && message="A saved file crossed one of this repository's standing rules:"$'\n'"$findings"
if [ -n "$reminders" ]; then
    [ -n "$message" ] && message="$message"$'\n\n'
    message="${message}Worth knowing about what was just saved:"$'\n'"$reminders"
fi
[ -n "$message" ] || exit 0

jq -nc --arg text "$message" \
    '{hookSpecificOutput: {hookEventName: "PostToolUse", additionalContext: $text}}'
exit 0
