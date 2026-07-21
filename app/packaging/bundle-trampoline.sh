#!/bin/bash
# Thin trampoline: the bundle lives inside the runtime image, so
# the real launcher is three levels up. exec keeps the pid (and
# therefore the Dock entry) on the java process's ancestry.
DIR="$(dirname "${BASH_SOURCE[0]}")"
exec "$DIR/../../../bin/drydock" "$@"
