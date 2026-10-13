#!/bin/sh
# Heartbeat update helper (macOS), started by the app right before it quits (DesktopUpdateInstaller).
# Usage: apply-macos.sh <pid of the app> <installed .app bundle> <prepared .app bundle>
# Waits up to ten minutes for the app to quit, moves the installed bundle aside, moves the prepared one in its place
# and opens it. A failed move restores the installed bundle and opens it unchanged. Output goes to helper.log.
set -u

pid=$1
app=$2
staged=$3
backup="$app.previous"

say() {
    printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1"
}

waited=0
while kill -0 "$pid" 2>/dev/null; do
    if [ "$waited" -ge 600 ]; then
        say "the app did not quit; the update is not installed"
        exit 1
    fi
    sleep 1
    waited=$((waited + 1))
done

say "replacing the app"
rm -rf "$backup"
if ! mv "$app" "$backup"; then
    say "the installed app could not be moved aside"
    open "$app"
    exit 1
fi
if ! mv "$staged" "$app"; then
    say "the update could not be moved in place; restoring the installed app"
    mv "$backup" "$app"
    open "$app"
    exit 1
fi
rm -rf "$backup"
say "update installed"
open "$app"
