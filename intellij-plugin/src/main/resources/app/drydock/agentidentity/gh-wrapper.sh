#!/bin/sh
# The session's `gh` wrapper for the agent-identity pipeline.
#
# Drydock puts this script's directory first on the session's PATH, so an
# agent CLI that runs `gh` reaches this wrapper first. The wrapper re-invokes
# the session's credential command (short-lived tokens must never strand a
# long session, so the token is minted per invocation, not read from the
# environment once), takes the fresh GH_TOKEN out of its KEY=VALUE output,
# and execs the next `gh` on PATH with that token exported.
#
# This script is generic: it knows nothing about who issued the token or how
# the credential command gets it. That glue lives in the user's own
# credential command, outside Drydock.
#
# Failure is loud: no credential command in the environment, a credential
# command that fails or prints no GH_TOKEN, or no other `gh` on PATH all
# abort with a message on stderr and exit 97, never a silent fall-through to
# the caller's own identity.

set -eu

if [ -z "${DRYDOCK_AGENT_CREDENTIAL_COMMAND:-}" ]; then
    echo "drydock gh wrapper: DRYDOCK_AGENT_CREDENTIAL_COMMAND is not set; refusing to run gh under an unknown identity" >&2
    exit 97
fi

# The launch prefix put this wrapper's directory first on PATH; drop it so
# the gh below resolves to the next gh in line (the real one, or the user's
# own routing shim). Exported PATH changes do not survive exec'd children
# otherwise, but exec replaces this process wholesale, so mutating PATH here
# is enough.
PATH=${PATH#*:}

if command -v gh >/dev/null 2>&1; then
    real_gh=$(command -v gh)
else
    echo "drydock gh wrapper: no gh found on PATH beyond the wrapper itself" >&2
    exit 97
fi
if [ "$real_gh" = "$0" ]; then
    echo "drydock gh wrapper: the only gh on PATH is this wrapper; refusing to recurse" >&2
    exit 97
fi

# The credential command prints KEY=VALUE lines. Run it with the same shell
# Drydock launches it with at session start, capture its stdout (the token
# must not echo to the terminal), and take the LAST GH_TOKEN line — later
# assignments win, the same rule the launch-time `set -a; . envfile` applies.
# A command substitution (not a pipeline) so its non-zero exit reaches this
# script: in POSIX sh a pipeline's status is its LAST command's, and a sed
# would happily report success over a failed credential command. A command
# that fails or prints no GH_TOKEN is a loud failure, never a fall-through.
if ! identity_output=$(/bin/sh -c "$DRYDOCK_AGENT_CREDENTIAL_COMMAND"); then
    echo "drydock gh wrapper: the credential command failed; refusing to run gh without a fresh token" >&2
    exit 97
fi
token=$(printf '%s\n' "$identity_output" | sed -n 's/^GH_TOKEN=//p' | tail -n 1)
if [ -z "$token" ]; then
    echo "drydock gh wrapper: the credential command printed no GH_TOKEN line; refusing to run gh without a fresh token" >&2
    exit 97
fi

GH_TOKEN="$token" exec "$real_gh" "$@"
