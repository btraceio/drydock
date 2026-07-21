#!/bin/bash
# The whole runtime image lives under Contents/ of this bundle;
# the real launcher resolves APP_HOME relative to itself, so a
# plain exec one level up is all that is needed.
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$DIR/../bin/drydock" "$@"
