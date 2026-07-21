# SSH Remote Repositories — Design

**Date:** 2026-07-21
**Status:** Approved

## Goal

Let Drydock register and work with repositories that live on a remote machine,
accessed over SSH — remote-development style. In v1 a remote repo supports
**Claude/Terminal sessions on the remote host** and **live git indicators**
(branch, dirty, ahead/behind) in the sidebar. Worktrees, diffs, changed lines,
Finder/editor open, and the `gh` PR chip are out of scope for remote repos in
v1 and are gated off in the UI.

Authentication is **ambient SSH only**: the user's `~/.ssh/config`, keys, and
`ssh-agent`. Drydock stores no credentials and manages no keys.

## Domain model & persistence

- New record `SshRemote(String host, String remotePath)` in `app.drydock.domain`.
  - `host` is passed to `ssh` verbatim — an `~/.ssh/config` alias or
    `user@hostname` — so ports, identities, and jump hosts come from the user's
    SSH config.
  - `remotePath` is the repo root on the host, stored as a `String` (remote
    paths are not local `Path`s). Always the *resolved* toplevel from
    validation (see Add flow).
- `Repository` gains a nullable `SshRemote remote` component.
  - `remote == null` → local repo; behavior unchanged everywhere.
  - `remote != null` → `root` is not used for filesystem access. A convenience
    `isRemote()` accessor is added. `root` keeps a placeholder value derived
    from host+path solely to satisfy the record's non-null contract; no code
    may resolve it against the local filesystem (enforced by gating, §
    Feature gating).
- `ApplicationStateCodec`: bump `SCHEMA_VERSION` 2 → 3. Per-repo JSON gains an
  optional `"remote": {"host": "...", "path": "..."}` object. Version-2 files
  load unchanged (absent field → local repo).
- `RepositoryManager` duplicate detection: local repos dedupe by canonical
  root (unchanged); remote repos dedupe by `(host, remotePath)` after
  toplevel resolution. A new `DuplicateRepositoryException` path covers the
  remote case with a matching message.

## Remote command execution

New class `SshCommandBuilder` (package `app.drydock.process`) that wraps a git
invocation for a given `SshRemote` into a local `ssh` invocation:

```
ssh -o BatchMode=yes -o ConnectTimeout=5 \
    -o ControlMaster=auto -o ControlPath=<socket> -o ControlPersist=60s \
    <host> -- git -C <remotePath> <args…>
```

- `BatchMode=yes` — background polling must never hang on an interactive
  prompt; it fails fast and surfaces as an unreachable state instead.
- `ControlMaster=auto` + `ControlPersist=60s` — sidebar polls reuse one
  authenticated connection instead of a full SSH handshake per poll.
- `ControlPath` uses the `%C` token (hash of host/port/user), rooted in a
  short directory (`$TMPDIR` or `~/.ssh`) to stay under the ~104-char unix
  socket path limit, e.g. `~/.ssh/drydock-%C`.
- **Escaping:** ssh concatenates remote arguments into a single remote shell
  command line. `SshCommandBuilder` single-quotes each remote argument
  (POSIX `'…'` with `'\''` for embedded quotes) centrally; this is the one
  escaping hazard of the feature and is unit-tested exhaustively.
- The `ssh` binary is taken from the standard PATH (`/usr/bin/ssh` fallback),
  injectable for tests, mirroring `GitExecutableLocator`.

`ProcessRunner` is untouched — `ssh` is just another local process. Its
working directory is irrelevant for remote commands (use the user home).

`GitStatusService` changes:
- `resolveRepositoryRoot` and `getStatus` gain remote branches: when the
  target repo is remote, run the ssh-wrapped `git rev-parse --show-toplevel`
  / `git status --porcelain=v2 --branch` instead of the local invocation.
  Parsing is shared — porcelain output is identical.
- Execution stays on the existing virtual-thread executor.

## Add flow

New sidebar menu item **“Add remote repository…”** under the existing
`＋ Add repository` menu (`RepositorySidebar`), opening a new
`RemoteRepositoryModal` (patterned on `GitHubCloneModal`'s async/error
handling):

- **Host** — editable combo box pre-populated with `Host` aliases parsed from
  `~/.ssh/config` (a small parser in the app; wildcard patterns such as
  `Host *` and negations are skipped; `Include` directives are not followed in
  v1). Free text (`user@example.com`) is accepted.
- **Path** — plain text field for the repo path on the host.
- **Confirm** — async validation:
  `ssh <BatchMode opts> <host> -- git -C <path> rev-parse --show-toplevel`.
  - Success → register via `RepositoryManager` with the resolved toplevel as
    `remotePath`; display name defaults to the last path segment.
  - Failure → friendly, specific errors: connection/auth failure (“check
    ssh-agent and ~/.ssh/config”), `git` not found on the host, path is not a
    git repository, timeout.

## Sessions

For a remote repo, session tabs spawn ssh in the embedded Ghostty terminal
instead of a local shell:

- **Claude tab:** `ssh -t <host> 'cd <remotePath> && exec claude <args…>'`
  — session flags (`--resume`, etc.) pass through inside the remote command.
- **Terminal sub-tab:** `ssh -t <host> 'cd <remotePath> && exec "$SHELL" -l'`
  — the single-quoted command means `$SHELL` expands on the *remote* side, so
  the user's remote login shell is used.
- These are interactive: **no BatchMode**, so passphrase/password prompts
  render in the terminal and work normally.
- Requirement (documented in README and surfaced in the add-modal helper
  text): `claude` must be installed on the remote host.

## Feature gating & error states

For `isRemote()` repos, the following are hidden or disabled with an
explanatory tooltip: worktree create/delete, diff view, changed-lines view,
“Open in Finder”, “Open in external editor”, `gh` PR chip.

Status-poll failures at the **ssh layer** (timeout, auth, unreachable) put the
sidebar entry into an *unreachable* state: grayed indicator plus tooltip with
the underlying error. No dialogs. Polling continues on the normal cadence and
the entry recovers silently on the next success. **Git-layer** errors keep
existing handling.

## Testing

- **Unit:** `SshCommandBuilder` argument construction + remote-quote escaping
  (spaces, single quotes, `$`, globs); `~/.ssh/config` host parsing (aliases,
  wildcards skipped, malformed lines); codec round-trip at schema 3 and
  migration of schema-2 files.
- **Service:** `GitStatusService` remote paths using a fake `ssh` executable
  (script injected via the locator seam) replaying canned porcelain output,
  auth failure, and timeout.
- **Manual smoke checklist:** add a remote repo via the modal; sidebar
  indicators update after remote commits/edits; Claude session runs over ssh;
  kill connectivity → unreachable state → restore → recovery; duplicate add
  rejected.

## Out of scope (v1)

- Worktrees, diffs, changed lines, PR chips for remote repos.
- App-managed SSH keys/credentials, known-hosts management.
- Following `Include` in `~/.ssh/config`.
- Remote directory browsing in the add modal.
- SSH-URL *cloning* (the existing GitHub clone flow still rewrites
  `git@github.com:` forms to HTTPS; unchanged by this feature).
