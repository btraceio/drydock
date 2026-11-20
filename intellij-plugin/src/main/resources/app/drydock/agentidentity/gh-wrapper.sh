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

# Remove this wrapper's own directory by value. PATH may have been modified
# by the launched agent, so assuming the wrapper is still the first component
# could instead remove a user's real gh.
wrapper_path=$0
case "$wrapper_path" in
    */*) ;;
    *) wrapper_path=$(command -v "$wrapper_path" 2>/dev/null || printf '%s' "$wrapper_path") ;;
esac
wrapper_dir=${wrapper_path%/*}
if [ "$wrapper_dir" = "$wrapper_path" ]; then
    wrapper_dir=.
fi
case "$wrapper_dir" in
    /*) ;;
    *) wrapper_dir=$(CDPATH= cd "$wrapper_dir" 2>/dev/null && pwd) || {
        echo "drydock gh wrapper: cannot resolve its own directory; refusing to run gh under an unknown identity" >&2
        exit 97
    } ;;
esac
path_without_wrapper=
first_path_component=true
remaining_path=${PATH:-}
while :; do
    case "$remaining_path" in
        *:*) path_component=${remaining_path%%:*}; remaining_path=${remaining_path#*:}; has_more=true ;;
        *) path_component=$remaining_path; has_more=false ;;
    esac
    comparable_component=$path_component
    if [ -z "$comparable_component" ]; then
        comparable_component=.
    fi
    case "$comparable_component" in
        /*) ;;
        *) comparable_component=$(CDPATH= cd "$comparable_component" 2>/dev/null && pwd) \
                || comparable_component=$path_component ;;
    esac
    if [ "$comparable_component" != "$wrapper_dir" ]; then
        if [ "$first_path_component" = true ]; then
            path_without_wrapper=$path_component
            first_path_component=false
        else
            path_without_wrapper=$path_without_wrapper:$path_component
        fi
    fi
    [ "$has_more" = true ] || break
done
PATH=$path_without_wrapper
export PATH

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

# The credential command prints KEY=VALUE lines. Run it with stdin at EOF,
# capture its stdout (the token must not echo to the terminal), and take the
# LAST GH_TOKEN line — later assignments win, the same rule the launch-time
# `set -a; . envfile` applies. A watchdog bounds the rerun without depending
# on a non-POSIX `timeout` utility. The command runs in the background so its
# non-zero status can be preserved without a pipeline (whose status would be
# only that of the final command).
credential_timed_out=false
trap 'credential_timed_out=true' USR1
if identity_output=$(
    (
        exec 0</dev/null
        exec /bin/sh -c "$DRYDOCK_AGENT_CREDENTIAL_COMMAND"
    ) &
    credential_pid=$!
    wrapper_pid=$$
    (
        timer_pid=
        trap 'if [ -n "$timer_pid" ]; then kill "$timer_pid" 2>/dev/null || :; fi; exit 0' TERM INT
        sleep 30 &
        timer_pid=$!
        if wait "$timer_pid"; then :; fi
        if kill -0 "$credential_pid" 2>/dev/null; then
            kill -s USR1 "$wrapper_pid" 2>/dev/null || :
            kill -TERM "$credential_pid" 2>/dev/null || :
            sleep 1
            kill -KILL "$credential_pid" 2>/dev/null || :
        fi
    ) >/dev/null 2>&1 &
    watchdog_pid=$!
    if wait "$credential_pid"; then
        credential_status=0
    else
        credential_status=$?
    fi
    kill -TERM "$watchdog_pid" 2>/dev/null || :
    if wait "$watchdog_pid" 2>/dev/null; then :; fi
    exit "$credential_status"
); then
    :
else
    credential_status=$?
    if [ "$credential_timed_out" = true ]; then
        echo "drydock gh wrapper: the credential command did not finish within 30s; refusing to run gh without a fresh token" >&2
    else
        echo "drydock gh wrapper: the credential command failed; refusing to run gh without a fresh token" >&2
    fi
    exit 97
fi
trap - USR1
token=$(printf '%s\n' "$identity_output" | sed -n 's/^GH_TOKEN=//p' | tail -n 1)
if [ -z "$token" ]; then
    echo "drydock gh wrapper: the credential command printed no GH_TOKEN line; refusing to run gh without a fresh token" >&2
    exit 97
fi

GH_TOKEN="$token" exec "$real_gh" "$@"
