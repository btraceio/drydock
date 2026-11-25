# Babysitting sessions for PR-carrying sessions — requirements

Status: REQUIREMENTS (not yet implemented). Decisions confirmed in
conversation 2026-11-18. TARGET: the plugin tree at the repository root
(headless core `src/main/java`, IDE glue `src/platform/java`).

## Goal

Any managed session whose work has an open pull request can get a
*babysitting* session: a dedicated agent session that watches the PR while
the main session is idle or away — merge conflicts against the base branch
and test breakages in CI — fixes trivial cases itself, retriggers CI
failures that look like environment issues or flakes (bounded), and
escalates everything with wider impact (design decisions, deep
implementation changes) back to the main session.

## Decisions

| # | Decision |
|---|----------|
| B1 | The babysitter is a real managed agent session, driven by drydock: a plugin-side monitor polls the PR and wakes the babysitter with a prompt whenever something needs attention. The agent never idles in a poll loop. |
| B2 | The babysitter may push new commits to the PR branch AND rebase/force-push (`--force-with-lease`) it to resolve merge conflicts — only under the write guard (see Mechanics). |
| B3 | Escalation/post-back goes through a NEW structured escalation record (own `ApplicationState` field — NOT `handoffBriefs`, which is replace-wholesale per session and would clobber the watched session's own brief), surfaced in the UI. |
| B4 | The 3-retry cap for retriggering CI is per failed run; when the same failure survives 3 reruns, the babysitter escalates. The plugin enforces the cap (persisted counter); the agent judges flakiness. |
| B5 | Babysitting stops when the PR merges or closes, when the watched main session is deleted, or on explicit stop. If the IDE closes first, the engagement detaches (see Detached mode) instead of stopping. |
| B6 | The main-session ↔ babysitter pairing is persisted in `ApplicationState`; after restart the pairing restores and polling resumes. |
| B7 | When the IDE is closed, a detached watcher process keeps polling and resumes the babysitter agent headlessly per event; escalations queue in a state file the plugin ingests on restart. |
| B8 | UI: the engagement is visually distinct (the babysitter nests beneath the watched session's inbox entry with its own engagement chip; the watched entry wears `babysat · <state>`) with navigation both ways between babysitter and watched session. |

## Mechanics

### Eligibility, pairing, launch (B1, B6)

- Eligibility is a LIVE property, not a persisted one: the plugin's persisted
  `pr` field is essentially never OPEN. A session qualifies when it is local
  (a `workspaceBox` row is remote and excluded), its branch resolves to an
  open pull request through the existing join (`TaskTreeInputs.
  sessionPullRequests`: session → branch → `GhCliService.branchPullRequests
  (root, repo)`, with the `pr-<n>` branch convention and stored PR number as
  fallbacks), and no active babysitter exists for it. A session whose
  `targetPullRequest` is set skips the branch join and is handled
  explicitly: in this change the agent-start path (`babysit_start`)
  refuses it, since its PR is not one the session's own branch resolves;
  the UI start path may resolve the target PR itself later. The
  babysitter's own detached worktree resolves no PR and must never
  qualify.
- Start paths: a human action in the plugin UI, and an MCP tool mirroring
  `task_claim`'s shape (validate in one place, in-flight guard so two
  concurrent starts cannot both pass, specific refusal string, launch async
  and report launching). One active babysitter per watched session.
- The babysitter launches through `DrydockSessionService` with the watched
  session's agent kind, spawn denied (the plugin already mints every MCP
  credential `Spawn.FORBIDDEN` via `ReviewBackend.accessFor` — the
  babysitter inherits that; any resume path must NOT re-mint spawn rights
  for it), and its own dedicated worktree: `git worktree add --detach` at
  the remote tip of the PR branch (`PrCheckoutService.checkoutBlocking` is
  the detached precedent; `createWorktreeBlocking` has no detached option;
  `git fetch origin <branch>` runs first). All of its writes go through
  detached HEAD pushed to the PR branch. The branch stays owned by the
  watched session's worktree.
- The pairing (babysitter id ↔ watched session id ↔ PR number) is a new
  top-level list in `ApplicationState` following the `handoffBriefs`
  pattern: new field, `with…` method, canonical-constructor `List.copyOf`,
  lenient drop-bad-entry codec decoding, no schema bump. `ApplicationState`
  is constructed positionally in ~13 places — add a delegating constructor
  or update all sites.

### The monitor (B1)

- A plugin-owned background poller per engagement, ~60 s cadence with
  backoff, reading via `gh`. The read carries the head SHA (push detection),
  the per-check status detail, the PR lifecycle CLOSED, and the PR's
  actionable reviews (APPROVED, CHANGES_REQUESTED, COMMENTED — a PENDING
  review is invisible to others and a DISMISSED one is withdrawn, the
  latest 50 are kept). A gh failure, timeout or unavailable result is never
  read as "no change" or "PR gone": keep a last-known-good state and back
  off on errors.
- The engagement's first read is always delivered: a first-status delta
  that states the PR as it stands (checks, mergeability, review counts)
  even when nothing is wrong, so the babysitter orients itself instead of
  waiting for a delta that clean news may never produce. An engagement the
  monitor restored after a restart keeps its silent first baseline: what
  the PR shows then predates the engagement's memory.
- A review that was not at the previous read (identified by author, state
  and submission time, so a re-submission is news again) wakes the
  babysitter with the author, verdict and a capped body excerpt. The
  babysitter reads the review and escalates it to the watched session; it
  never acts on review feedback itself. A push that keeps everything green
  and mergeable is no event: the head is recorded (the push lease's base)
  and the babysitter waits for the conflicts or failures it may have to
  fix.
- On a state change the monitor wakes the babysitter: if the babysitter is
  idle, via a resume with a custom prompt (the `ResumeContext.resumePrompt`
  path needs an overload that threads the prompt; honoured only when an
  agent session id is bound — Claude binds at launch, Codex/Pi bind late,
  so a pre-binding wake-up retries later); if it is running, via
  `AgentExecutionRegistry.submitPrompt` (single-line pointer prompt, e.g.
  "Read <delta file>"), which writes bytes to the PTY and returns DELIVERED
  without a busy check. The delta prompt references a seed/delta file the
  monitor wrote, keeping prompts single-line and small.
- A PR with no CI configured still gets conflict watching; the check half
  is simply absent.

### What the babysitter may fix (B2, B5)

- **Merge conflicts**: may rebase the PR branch onto the updated base and
  resolve trivially mechanical conflicts; pushes with `--force-with-lease`.
  Non-mechanical resolutions escalate.
- **Trivial test/code breakages**: may push small mechanical fix commits
  (import churn, renamed API, an assertion the PR's own change invalidated)
  limited to the PR's changed scope.
- **Write guard** (both cases), as implemented: a write-granting delta is
  delivered only after (a) a POINT-IN-TIME liveness check that the watched
  session has no agent turn in flight (its activity is not reported BUSY —
  a live agent PROCESS alone is not busy: the interactive TUI sits at its
  prompt for the session's whole life, and a guard that read that as busy
  never let a single delta through) and no launch, resume or removal in
  flight (`AgentExecutionRegistry`/launch claims — NOT the persisted
  `SessionStatus`, which can lie after a crash/restart), taken before and
  again after the fetch, and (b) a fresh one-branch fetch whose remote tip
  equals the PR head the monitor last read (the babysitter's known base);
  the delta then tells the babysitter to push with
  `--force-with-lease=<branch>:<that tip>`. What this guarantees: the push
  cannot clobber a remote commit the guard did not see (the lease). What it
  does NOT guarantee: it does not exclude the watched session's LOCAL,
  unpushed work (nothing locks the watched worktree; only the remote ref is
  leased), and it holds no permit for the babysitter's turn — the watched
  agent can be started after the check and work concurrently with the
  babysitter's turn (the residual mid-turn race; the lease still refuses a
  push over anything that agent pushed first). Otherwise (busy, moved head,
  failed fetch) it waits and re-checks.
- Anything requiring a design decision or a deep implementation change is
  escalated, not attempted.

### CI rerun policy (B4)

- The babysitter reads the failing jobs' output (`gh run view --log-failed`)
  and classifies: environment/flake signatures (timeouts, network, runner
  infra) vs real failures.
- Reruns go through a new MCP tool that executes
  `gh api --method POST repos/{owner}/{repo}/actions/runs/{run_id}/
  rerun-failed-jobs` (empty body, exit 0 = success — do not demand a JSON
  body) and enforces the cap: at most 3 reruns per failed run. The counter
  is keyed on the workflow run ID and PERSISTED — not the in-memory
  `McpSessionRegistry` budgets, which die on restart. It must be shared
  between in-app and detached operation. Increment happens inside a
  single-writer transform (the gh call runs outside the lock); a timed-out
  write whose outcome is unknown counts as consumed. At the cap the tool
  refuses and the babysitter escalates instead.

### Escalation and post-back (B3)

- A new structured escalation record against the pairing: what happened,
  what the babysitter already did, what the main session needs to decide or
  do. One `stateStore.update` transform, keyed on the watched id, appending
  into its own `ApplicationState` field. The watched session's UI shows a
  badge/notification; the latest escalation is also visible where the
  handoff brief is shown so it is actionable when the human returns.
  Injecting text into the WATCHED session's conversation is out of scope —
  only the babysitter itself is nudged programmatically.
- An escalation call cap in the registry bounds loops. Refund the charge only
  when the context explicitly refuses the write or dispatch fails synchronously
  before returning a future. Once dispatched, a timeout, interruption,
  cancellation, or exceptional completion has an unknown outcome and keeps the
  charge. `session_handoff` retains its separate any-failure refund policy.

### Detached mode (B7)

- On IDE shutdown with an active engagement, the plugin spawns a detached
  watcher process carrying the same monitor core. On events it resumes the
  babysitter agent headlessly (no MCP server is running) and records
  escalations in the shared state file. Detached Codex runs use the
  `workspace-write` sandbox with the shared babysit directory as an added
  writable root. Codex `git`/`gh` operations are limited by that sandbox:
  network access and writes under the main repository's `.git` directory for
  linked worktrees may be refused. A bounded capability probe requires the
  installed CLI to advertise `--add-dir` and `workspace-write`; if either is
  unavailable or the probe fails, the detached run is skipped and events wait
  for the IDE rather than being dropped. The existing
  `buildTransferSummaryCommand` precedent is read-only
  (`--permission-mode plan`) and serves as the shape, not the safety flags.
- Single-writer rule for that state file: the watcher owns it while
  detached; on restart the plugin stops/reclaims the watcher, ingests the
  queued escalations and rerun counters, and re-attaches the engagement
  in-process. The cross-process file lock follows the `SharedProjectsFile`
  pattern (per-JVM lock, then `FileChannel.tryLock` on a sibling `.lock`
  file, re-read under lock, atomic write). The state file lives outside the
  plugin's `state.json`, which must never be written from a second process.
- `ProcessRunner` is run-to-completion; the detached watcher spawn is a
  new, documented exception to the child-process rule (only
  `LspServerProcess` is sanctioned today).
- Stop conditions still apply while detached (PR resolved, watched session
  deleted → engagement ends; the deletion is observed on the next poll or
  at re-attach).
- Accepted duplicate nudge after re-attach: delivered-observation state is
  not persisted, so a restored engagement's first read is a silent
  baseline. If that first read samples `mergeable=UNKNOWN` (GitHub
  recomputing) while the PR is in fact still conflicting, the next read's
  `CONFLICTING` is an edge and the babysitter is woken once more for a
  conflict it was already told of. A duplicate nudge is harmless (the
  babysitter re-reads the PR and finds nothing new to do); this is
  accepted rather than persisting per-engagement observation state.

### UI (B8, as shipped)

- An active engagement's babysitter session is never a session row of its
  own. In the tool window's Inbox (the view that shows sessions), it
  renders as an indented child row beneath the watched session's entry,
  wearing its engagement chip (`watching #N` / `escalated #N`); the
  watched session's entry wears `babysat · <state>`. An engagement is
  visible through its watched session or not at all: a babysitter whose
  watched session's row is built nowhere is hidden. A running babysitter
  lifts an otherwise idle watched entry into Running and counts as the
  project's activity.
- The tool window's Projects view lists no session rows at all — only
  registered projects with their issues, pull requests, worktrees and
  branches. Sessions live in the Inbox view; the Projects view has shown
  no Sessions section since the inbox rewrite (#151), which is why a
  filter or search hit on a nested babysitter keeps the watched session's
  Inbox entry (a hit keeps its container).
- Navigation: a jump affordance from a babysitter to its watched session's
  console, and from the watched session to its babysitter.
- Engagement state (watching / acting / escalated / stopped) is visible at
  a glance on the babysitter's chip, fed from the monitor through the
  plugin's normal UI update paths. Rerun counts are not shown: the
  persisted counter is keyed by workflow run only, so no per-engagement
  count exists to display.
- When an engagement ends, the babysitter session is removed by every
  end path (explicit stop, monitor-detected merge/close, watched-session
  deletion, reattach ingest); a stopped pairing's lingering row places
  like any ordinary session.

## Out of scope

- Injecting messages into the watched session's conversation (only the
  babysitter is nudged programmatically).
- A standalone desktop app — Drydock is the IntelliJ plugin only.
- Non-GitHub remotes.
- The plugin pushing anything itself except the sanctioned rerun API call
  (agents push; drydock reruns CI).
