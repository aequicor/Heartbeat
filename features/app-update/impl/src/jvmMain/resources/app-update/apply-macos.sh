#!/bin/sh
# Heartbeat update helper (macOS), started by the app right before it quits (DesktopUpdateInstaller).
# Usage: apply-macos.sh <pid of the app> <installed .app bundle> <prepared .app bundle>
# Waits up to ten minutes for the app to quit, stages on the installation volume, then swaps and opens the bundle.
# A failed swap restores the installed bundle; a failed restoration preserves its backup. Output goes to helper.log.
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

# A cross-volume move can leave a partial destination. Finish it privately before touching the working app.
if [ ! -d "$app" ]; then
    say "the installed app is missing; preserving any previous backup"
    exit 1
fi
if ! temporary=$(mktemp -d "$app.update.XXXXXX"); then
    say "the update could not be staged on the installation volume"
    open "$app"
    exit 1
fi
cleanup() {
    if ! rm -rf "$temporary"; then
        say "the temporary update files could not be removed"
    fi
}
trap cleanup 0
prepared="$temporary/prepared.app"
if ! mv "$staged" "$prepared"; then
    say "the update could not be staged; the installed app is unchanged"
    open "$app"
    exit 1
fi

say "replacing the app"
if ! rm -rf "$backup"; then
    say "the previous backup could not be removed; the installed app is unchanged"
    open "$app"
    exit 1
fi
if ! mv "$app" "$backup"; then
    say "the installed app could not be moved aside"
    open "$app"
    exit 1
fi
if ! mv "$prepared" "$app"; then
    say "the update could not be moved in place; restoring the installed app"
    # Remove only this attempt's destination before restoring, never nest the backup in a partial bundle.
    if ! rm -rf "$app"; then
        say "the partial update could not be removed; the installed app remains in its backup"
        exit 1
    fi
    if ! mv "$backup" "$app"; then
        say "the installed app could not be restored; preserving its backup"
        exit 1
    fi
    open "$app"
    exit 1
fi
if ! rm -rf "$backup"; then
    say "the update is installed but its previous backup could not be removed"
fi
say "update installed"
open "$app"
