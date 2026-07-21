# SSH Remote Repositories — Design

**Date:** 2026-07-21 (revised same day after adversarial review)
**Status:** Approved approach; revision pending user review

## Goal

Let Drydock register and work with repositories that live on a remote machine,
accessed over SSH. In v1 a remote repo supports **Claude/Terminal sessions on
the remote host** (with a deliberately degraded contract, below) and **live
git indicators** (branch, dirty, ahead/behind) in the sidebar. Everything else
is gated off (see Feature gating for the exhaustive list).

Authentication is **ambient SSH only**: the user's `~/.ssh/config`, keys, and
`ssh-agent`. Drydock stores no credentials and manages no keys. On macOS,
GUI apps inherit the launchd-provided `SSH_AUTH_SOCK`, so agent auth works
for a Finder/Dock-launched app; keys must actually be loaded in the agent
(Keychain-backed passphrases work).

**v1 remote-host requirements** (documented in README and the add-modal help
text): `git` and `claude` on the *non-interactive* SSH PATH, a
POSIX-compatible login shell (sh/bash/zsh — fish/nushell are unsupported in
v1 because sshd runs remote commands through the account's login shell), and
a host key already accepted (see Add flow).

## Domain model & persistence

- New record `SshRemote(String host, String remotePath)` in `app.drydock.domain`.
  - `host` is an `~/.ssh/config` alias or `user@hostname`, passed to `ssh` as
    the destination (always after a `--` argument terminator — see Remote
    command execution). Ports, identities, and jump hosts come from SSH config.
  - `remotePath` is the repo root on the host as a `String` — always the
    *resolved* toplevel from add-time validation.
- `Repository` gains a nullable `SshRemote remote` component and an
  `isRemote()` accessor. `remote == null` → local repo, behavior unchanged.
- **Placeholder root scheme.** `Repository.root` must stay a non-null absolute
  normalized `Path`. For remote repos it is a *virtual* path, derived
  deterministically as:

  ```
  /.drydock-remote/<percent-encoded host>/<remotePath without leading slash>
  ```

  Percent-encoding makes the host path-safe (`user@h.example.com` →
  `user%40h.example.com`). Properties this buys:
  - Unique per `(host, remotePath)` — so the existing canonical-root
    duplicate detection in `RepositoryCatalog` works for remote repos with
    no changes (nonexistent paths fall back to normalized-string comparison).
  - Collision with a real local repo requires a user to own a literal
    `/.drydock-remote` tree; accepted as a documented non-case.
  - The path must never be resolved against the local filesystem. This is
    enforced by the exhaustive Feature gating list plus the session contract
    below — every known consumer of `root` is either routed remotely or gated.
- `ManagedClaudeSession` is unchanged structurally: remote sessions store the
  placeholder root as `workingDirectory`. Remote-ness is always derived from
  the owning `Repository` (sessions are keyed by `repositoryId`); no session
  schema change.
- **Persistence: no schema bump.** Following the codec's own precedent
  (`prState`, `theme` were added leniently within version 2), the per-repo
  JSON gains an optional `"remote": {"host": "...", "path": "..."}` object,
  read leniently, `SCHEMA_VERSION` stays 2. Rationale: a version bump makes a
  *downgrade* silently wipe all state (older codec rejects version 3, the
  repository backs the file up and persists `ApplicationState.empty()`).
  With the lenient field, an older build shows the remote repo as a broken
  local repo — ugly but non-destructive, and removable by the user.
- Duplicate detection: falls out of the placeholder scheme (above). The same
  physical repo reachable via two host aliases (`myserver` vs
  `user@myserver.example.com`) registers twice; this is accepted and
  documented — canonicalizing aliases would require resolving SSH config
  (`ssh -G`) and is out of scope.

## Remote command execution

New class `SshCommandBuilder` (package `app.drydock.process`), the **single**
place ssh command lines are constructed. Two output forms:

1. **Argv form** (for `ProcessRunner`): a `List<String>` —

   ```
   ssh -o BatchMode=yes -o ConnectTimeout=5
       -o ServerAliveInterval=3 -o ServerAliveCountMax=2
       -- <host> <remote command string>
   ```

2. **Shell-string form** (for terminal sessions, which libghostty runs via
   `/bin/sh -c`): the same invocation rendered as one string with every
   element quoted for the *local* POSIX shell.

Rules, all unit-tested:

- **`--` comes immediately before the destination.** Everything after the
  destination is the remote command; a `--` placed after the host would be
  handed to the remote shell (and breaks it). Placing it before the host also
  closes option injection: a host string beginning with `-`
  (e.g. `-oProxyCommand=…`) can never be parsed as an ssh option. A host
  starting with `-` is additionally rejected at input validation.
- **`BatchMode=yes` on all non-interactive commands** so background execution
  never blocks on a prompt. `ConnectTimeout` bounds only the TCP connect;
  `ServerAliveInterval=3` / `ServerAliveCountMax=2` bound post-connect stalls
  (dead links, auth hangs). A dedicated **10s timeout** is used for remote
  git commands (vs the 15s local default), with `ProcessRunner`'s
  `destroyForcibly` as backstop.
- **No connection multiplexing in v1.** ControlMaster/ControlPersist is
  dropped: a background mux master inherits the client's stderr pipe and
  `ProcessRunner`'s unbounded post-exit stream joins would park on it, stale
  sockets after sleep/network change hang subsequent attaches, and master
  lifecycle management (health checks, teardown) isn't worth it at v1's poll
  cadence. Each remote command is a full ssh connection. Revisit with an
  explicitly managed master (`ssh -M -f -N` + `-O check`/`-O exit`) if poll
  latency proves painful.
- **Remote-side quoting**: each remote argument is POSIX single-quoted
  (`'…'` with `'\''` splicing), targeting sh/bash/zsh (the documented v1
  shell requirement). This applies to *every* remote command — git commands
  and session command lines alike; no remote command is hand-assembled
  outside the builder. Tested inputs include spaces, single quotes, `$`,
  globs, and newlines.

`ProcessRunner` itself is untouched (true now that multiplexing is gone: no
background child inherits the pipes). Working directory for ssh invocations
is the user home.

### GitStatusService routing

`getStatus`/`resolveRepositoryRoot` are currently `Path`-keyed with no
repository in scope, so remote awareness is an **API change, not an internal
branch**. A small sealed type is introduced:

```java
sealed interface GitTarget { record Local(Path root); record Remote(SshRemote remote); }
```

`GitStatusService.getStatus(GitTarget)` / `resolveRepositoryRoot(GitTarget)`
replace the `Path` overloads; the ~7 call sites (`RepositorySidebar`,
`MainWorkspace` ×2, `NewWorktreeModal`, `WorktreeLifecycleController`,
`ReviewView`, `RepositoryManager`) are updated — most already hold a
`Repository`, which converts via `GitTarget.of(repository)`. Worktree paths
remain `Local` targets. Remote targets run
`git -C <remotePath> status --porcelain=v2 --branch` through the builder;
porcelain parsing is shared and unchanged.

### Error taxonomy

New exception `SshUnreachableException` (extends `GitException`), raised when
the ssh client exits with **255** (ssh-layer failure: DNS, connect, auth,
host key) — distinguishing it from git-layer failures, which keep the
existing `GitCommandFailedException` handling. Stderr is attached for
classification in the add flow (host-key vs auth vs timeout messages).

## Status polling

Today sidebar git status refreshes only on events (add/remove, session
changes, manual refresh) — there is no periodic git poller. Remote
indicators therefore get a new one:

- A **30-second periodic poller for remote repos only** (virtual-thread
  scheduled, one in-flight poll per repo max, skipped while a previous poll
  is running). Local repos keep today's event-driven behavior.
- Event-driven refresh triggers apply to remote repos too, routed through the
  remote target.
- `SshUnreachableException` from a poll puts the sidebar entry into an
  **unreachable** state: grayed indicator + tooltip with the error. No
  dialogs. The next successful poll clears it silently.
- Ahead/behind for a remote repo reflects the *remote machine's* last fetch —
  and on a headless box nobody fetches, so it can freeze at stale values
  indefinitely. The indicator tooltip carries a qualifier
  ("vs. last fetch on <host>"). Drydock does **not** auto-`git fetch`
  (network and credential side effects on a machine we don't own).

## Add flow

New sidebar menu item **“Add remote repository…”** under `＋ Add repository`
(`RepositorySidebar`), opening `RemoteRepositoryModal` (patterned on
`GitHubCloneModal`'s async/error handling):

- **Host** — editable combo box pre-populated from `~/.ssh/config` `Host`
  aliases. Parser rules (unit-tested): `Host` keyword case-insensitive;
  multiple patterns per line split into separate entries; wildcard/negated
  patterns and `Match` blocks skipped; `=`-delimited and quoted forms
  handled; `Include` **not** followed in v1 — the combo box shows an
  empty-state hint ("type a host; included config files aren't listed").
  Free text (`user@example.com`) accepted; input starting with `-` rejected.
- **Path** — text field for the repo path on the host.
- **Confirm** — async validation via the builder:
  `ssh … -- <host> git -C <path> rev-parse --show-toplevel`.
  - Success → register with the resolved toplevel as `remotePath`; display
    name defaults to the last path segment.
  - Failure → specific errors, classified from exit code + stderr:
    - **Host key verification failed** (the guaranteed first-connection case
      under BatchMode): "Drydock can't accept new host keys. Run
      `ssh <host>` once in a terminal to accept the key, then retry."
      Decision: Drydock does **not** use `StrictHostKeyChecking=accept-new`
      — silently trusting first-seen keys on the user's behalf is worse than
      one manual step.
    - Auth failure → "check ssh-agent and ~/.ssh/config".
    - `git` not found → notes it must be on the **non-interactive** PATH
      (`.bashrc`/`.profile` may not be sourced for `ssh host cmd`).
    - Not a git repository; timeout/unreachable.

New `RepositoryManager.addRemoteRepository(SshRemote)` entry point alongside
`addRepository(Path)`; dedupe via the placeholder canonical root.

## Sessions — degraded remote contract (v1)

Remote sessions run the real `claude` on the remote host inside the embedded
Ghostty terminal, but the local session pipeline (capability probing, hook
injection, activity watching, transcript preflight) is local-filesystem-bound
by design, so remote sessions run a deliberately reduced contract:

- **Command construction** goes through `SshCommandBuilder`'s shell-string
  form (never hand-assembled — the libghostty command is a `/bin/sh -c`
  string, so this is a *second* quoting layer, covered by dedicated tests):
  - Claude tab:
    `ssh -t -- <host> 'export TERM=xterm-256color; cd <qpath> && exec claude <args…>'`
  - Terminal sub-tab:
    `ssh -t -- <host> 'export TERM=xterm-256color; cd <qpath> && exec "${SHELL:-sh}" -l'`
    (`$SHELL` may be unset in sshd's non-interactive context, hence the
    fallback; the POSIX-login-shell requirement makes the outer syntax safe).
  - `<qpath>` is builder-quoted. Interactive: **no BatchMode**, prompts render
    in the terminal. `TERM` is forced to `xterm-256color` because
    `xterm-ghostty` terminfo won't exist on remote hosts and would break the
    Claude TUI.
- **No local machinery in the command:** no `--settings <local hook file>`,
  no local-capability-derived flags. Remote launches use the pessimistic
  flag set: plain `claude` for new sessions, `claude --resume '<id>'` for
  resume. Local `claude` need not be installed to use remote repos
  (`ClaudeExecutableNotFoundException` is bypassed for remote launches).
- **No activity badges:** the busy/idle/notify pipeline is local file-watched
  hooks that a remote `claude` never triggers. Remote session tabs show a
  distinct "no activity data" state (not stale/blank pretending otherwise).
- **Resume trusts the stored id:** for remote sessions, `SessionManager`
  skips both local preflights — the `Files.notExists(workingDirectory)`
  check (the placeholder never exists) and the local
  `~/.claude/projects/**.jsonl` transcript check (transcripts live
  remotely). If the remote conversation is gone, `claude --resume` fails
  visibly in the terminal; that is the accepted v1 recovery UX.
- **Working directory:** persisted session `workingDirectory` is the
  placeholder root (unchanged schema); the Ghostty spawn cwd for remote
  sessions is the **user home**, never the placeholder.
- **Connection drop:** ssh exits 255 on transport failure (sleep, wifi
  change). For remote sessions, exit code 255 maps to a distinct
  **"connection lost"** session state instead of a failure exit badge.
  Recovery is manual resume. No auto-reconnect in v1.
- **Conversation discovery gated:** the resume picker's local
  `~/.claude/projects` scan and external-conversation adoption
  (`ConversationCatalog`) are disabled for remote repos — only new sessions
  and resuming Drydock-tracked session ids are offered.

## Feature gating

For `isRemote()` repos, hidden or disabled with an explanatory tooltip —
this list was built by auditing every consumer of `Repository.root` /
`ManagedClaudeSession.workingDirectory` and must stay exhaustive:

- Worktrees entirely: create, delete, **discovery/list** (`refreshWorktrees`
  runs on every sidebar refresh — it must skip remote repos), and the
  "Rescan worktrees" menu items.
- Diff view, changed-lines view, and the **Review sub-tab**.
- **Session Explorer sub-tab** (local file search/browse rooted at
  `repo.root()`).
- Resume-picker conversation scan and conversation adoption (see Sessions).
- "Open in Finder", "Open in external editor".
- `gh` PR chip.
- The remove-repository confirm dialog shows `host:remotePath`, not the
  placeholder path.

Still working for remote repos: sidebar git indicators, the per-tab status
chip (routed through `GitTarget.Remote`), sessions per the degraded contract.

**No edit flow in v1** (documented consequence): if a host alias is renamed
or the repo moves on the remote, the only recovery is remove + re-add, which
allocates a new `RepositoryId` and orphans persisted sessions for that repo.
Accepted for v1; a minimal "Edit remote…" is the first follow-up candidate.

## Testing

- **Unit — `SshCommandBuilder`:** argv and shell-string forms; `--`-before-host
  invariant; host beginning with `-` rejected; remote quoting (spaces, single
  quotes, `$`, globs, newlines) at both layers; session command lines
  (TERM export, cd, exec, resume id) as rendered strings.
- **Unit — ssh-config parser:** multi-pattern `Host` lines, `Match` blocks
  ignored, wildcards/negations skipped, `=`/quoted forms, case-insensitive
  keyword, malformed lines.
- **Unit — codec:** round-trip of the optional `remote` field within schema 2;
  files without the field decode as local repos; placeholder-root derivation
  is stable and unique.
- **Service:** `GitStatusService` with a fake `ssh` executable (locator seam)
  replaying canned porcelain output, exit-255 classification →
  `SshUnreachableException`, git-error passthrough, and timeout.
- **Manual smoke checklist:** first-connection host-key error shows the
  specific message; add remote repo; indicators update after remote commits
  (30s poll); Claude session over ssh renders correctly (TERM override) and
  resizes with the window; kill connectivity → session shows "connection
  lost" + sidebar unreachable → restore → sidebar recovers, manual resume
  works; duplicate add rejected; repo with spaces/quote in remote path.

## Out of scope (v1)

- Worktrees, diffs, changed lines, Review, Session Explorer, PR chips for
  remote repos.
- Activity badges and local-hook injection for remote sessions; auto-reconnect.
- App-managed SSH keys/credentials; known-hosts management (beyond the
  specific host-key error message); `accept-new` trust-on-first-use.
- Connection multiplexing (ControlMaster) — revisit if poll latency hurts.
- Following `Include` in `~/.ssh/config`; host-alias canonicalization
  (`ssh -G`) for dedupe.
- Non-POSIX remote login shells (fish, nushell).
- Editing a remote repo's host/path; remote directory browsing; auto-fetch.
- SSH-URL *cloning* (the GitHub clone flow still rewrites `git@github.com:`
  forms to HTTPS; unchanged by this feature).
