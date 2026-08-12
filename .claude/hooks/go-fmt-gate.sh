#!/usr/bin/env bash
# Do not end a turn with Go that gofmt would rewrite.
#
# `make fmt` runs gofmt and vet, and it is the first thing CI does. Formatting is
# the cheapest possible failure to leave behind: the diff it produces is noise in
# a review, and it is caught by a command that takes milliseconds.
#
# Only looks at files that this working tree has actually changed, so a session
# that never touched the server is silent — which is most of them.
#
# Measured: 0 findings against the tree as it stands (gofmt -l is empty).
#
# Stop hook. Exit 2 keeps the turn open and hands stderr to the model.
set -uo pipefail

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
command -v gofmt >/dev/null 2>&1 || exit 0
[ -d server ] || exit 0

changed=$(git status --porcelain -- 'server/*.go' 2>/dev/null | awk '{print $NF}')
[ -n "$changed" ] || exit 0

unformatted=$(gofmt -l $changed 2>/dev/null)
[ -n "$unformatted" ] || exit 0

{
    echo "gofmt would rewrite these, and CI runs it:"
    echo
    printf '%s\n' "$unformatted" | sed 's/^/  /'
    echo
    echo "Run: make fmt"
} >&2
exit 2
