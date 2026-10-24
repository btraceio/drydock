# Spec: resume-prompt-review-fixes

run_id: 2026-sphinx-resume-prompt-review

## Problem
Three defects in the uncommitted "resume prompt" change set:

1. **F-cf88c48dd5d7.** In the plugin, `CodexAgentProvider.buildResumeCommand`
   (`intellij-plugin/src/main/java/app/drydock/agent/providers/codex/CodexAgentProvider.java:152-154`)
   appends the resume prompt after `codex resume '<id>'` as a bare positional. It has no
   `--` end-of-options marker. Codex's CLI is built on clap
   (`Usage: codex resume [OPTIONS] [SESSION_ID] [PROMPT]`), so it would parse a prompt
   that starts with a dash as an option. The claude provider (`ClaudeAgentProvider.java:323`),
   the pi provider (`PiAgentProvider.java:246`) and Codex's own create path
   (`CodexAgentProvider.java:136`) all emit ` -- ` before the prompt.
2. **F-8b956de69f3e.** In the app, the PR-review flow persists `startedWithTask=true` on
   intent, not on delivery. `runReviewWhenSessionReady` (`app/src/main/java/app/drydock/ui/MainWorkspace.java:3512`)
   and the scope-resolution failure branch (`:1498`) call
   `sessionManager.markStartedWithTask(session)` before `sendWhenSessionReady` polls for
   the tab. The tab may never appear (launch failed, or the user closed the pending tab).
   The bridge may also refuse the prompt (`TerminalBridge.sendPrompt` returns `false`).
   In either case the session keeps a marker for a prompt it never got. On a later
   explicit resume, `handleResumeResult` (`:4727-4728`) then types `RESUME_PROMPT` into a
   harness that has nothing to continue.
3. **F-2f9f22517349.** The plugin `ApplicationStateCodec` class doc
   (`intellij-plugin/src/main/java/app/drydock/state/ApplicationStateCodec.java:82-83`) leaves
   out the `workflowId` key, although its own `toJson` writes it (`:298-300`). The app doc
   (`app/src/main/java/app/drydock/state/ApplicationStateCodec.java:80-82`) lists it.

## Correct behaviour

### Cluster A — resume command construction (plugin providers)
- R-A1: With a pinned `agentSessionId` and a present (non-blank) `resumePrompt`, the prompt
  is the last token of the command, preceded by ` -- `, and shell-quoted via
  `AgentCommands.shellQuote`. Codex renders `... resume '<id>' -- '<prompt>'`. The same rule
  already holds for claude and pi.
- R-A2: With no pinned `agentSessionId` (the picker path), the command never carries the
  resume prompt and never emits `--`. The command tail is exactly `... resume`, never `--last`.
- R-A3: With a pinned id and an empty `resumePrompt`, the command ends exactly with
  `resume '<id>'`, with no trailing `--`.
- R-A4: The global `-c` overrides and `--sandbox <mode>` stay before the `resume`
  subcommand (unchanged).
- R-A5: `ResumeContext` turns a blank prompt into an empty one, so providers do not check
  for blankness again. A blank prompt therefore behaves like R-A3.

### Cluster B — `startedWithTask` marker lifecycle (app review flow)
- Ownership: `SessionManager` owns the persisted marker. `markStartedWithTask` uses
  `updateSession` under the state store's lock and throws `UnknownSessionException` when the
  session is gone. `MainWorkspace` decides when to set it, on the FX thread.
- The marker only ever moves from false to true. Nothing clears it.
- T1: On the create path with a task (`openNewSession`, `openPreparedSession`), the marker is
  stamped before launch. **Unchanged.**
- T2: On the create path without a task, the marker stays false.
- T3: In the review/PR flow (`runReviewWhenSessionReady`), the marker becomes true **only when
  the review prompt is actually handed to a live terminal**, i.e. `OpenSessionTab.sendPrompt`
  returned `true`.
- T4: In the scope-resolution failure branch (bare typed task), the same delivery rule as T3
  applies.
- T5: If the poll expires with no tab, nothing is typed and the marker stays false.
- T6: If the poll expires with a pending tab and a fallback task, marking is best-effort. It
  runs only if the fallback `sendPrompt` returns true. Because the tab is still pending,
  `SessionManager.finalizeCreate` may later persist an `initial`-derived snapshot
  (`startedWithTask=false`) over the marker, or the marker write may throw
  `UnknownSessionException` (swallowed). Losing the marker this way is fail-safe. No test may
  expect the marker to be true after the launch completes in T6.
- T7: If the send is attempted but `sendPrompt` returns false (bridge disposed or closing),
  the marker stays false.
- T8: On an explicit resume, `handleResumeResult` reads the marker and types `RESUME_PROMPT`
  only when it is true and the resume is not a startup restore. **Unchanged.**
- Edge policy:
  - The delivery callback runs inside the 1.5 s `PauseTransition`, and the session may have
    been deleted by then. The callback catches and logs `UnknownSessionException` (DEBUG)
    and does not rethrow it.
  - Marking never prevents or comes before the prompt send.
  - `onDelivered` runs at most once per `sendTaskWhenReady` call.
- Concurrency: every marker decision runs on the FX thread, in the poll `Timeline` and
  `PauseTransition` handlers. The state store's lock serializes the write. No new threads.

### Cluster C — persisted-schema documentation parity
- R-C1: The plugin class doc lists every session key the plugin `toJson` writes. Keys shared
  with the app doc appear in the same order: `... "evalMode", "workflowId", "startedWithTask"`.
- R-C2: This is a doc-only change. Code and `schemaVersion` do not change.

## Constraints
- The persisted shape and `schemaVersion` do not change. Both codec trees keep reading the
  same flat file.
- The create-path pre-launch stamp (T1: `MainWorkspace.java:3474-3476`, `:4164`; plugin
  `DrydockSessionService.java:495`) must not move. The reviewer instruction says it is
  correct as-is.
- `sendTaskWhenReady` keeps its single-line normalization and its 1.5 s settle delay. The
  existing 2-arg call sites (`:3486`, `:4175`, `:4728`) behave exactly as before.
- No new threads and no FX-thread changes. Logging uses the existing `System.Logger LOG`.
- Code style: explanatory `//` comments of the "why" kind, as in the surrounding code.
- The app-side `CodexAgentProvider` has no `resumePrompt` support (the app delivers the
  resume prompt by typing it), so it is not touched.

## Scope

### Primary fixes
- `intellij-plugin/src/main/java/app/drydock/agent/providers/codex/CodexAgentProvider.java:152`
  — missing end-of-options marker before a positional prompt — append
  `" -- " + shellQuote(prompt)`, only in the pinned-id branch, and update the comment.
- `app/src/main/java/app/drydock/ui/MainWorkspace.java:3512` (cited line `:4164` is the
  create path, correct as-is) — persisted "delivered" marker set before delivery — move
  `markStartedWithTask` into a delivery callback of `sendWhenSessionReady` /
  `sendTaskWhenReady` that runs only when `sendPrompt` returns true. Update the javadoc of
  `SessionManager.markStartedWithTask`.
- `intellij-plugin/src/main/java/app/drydock/state/ApplicationStateCodec.java:82` — doc drift
  between parallel codec trees — insert the `"workflowId": "<uuid>" | null,` line.

### Auto-expanded sibling fixes
- `app/src/main/java/app/drydock/ui/MainWorkspace.java:1498` — persisted marker set before
  delivery (scope-resolution failure branch) — evidence:
  `sessionManager.markStartedWithTask(session); sendWhenSessionReady(session, () -> typed, ...)`.
  FLOW: PR materialization → `whenComplete` → `failure != null` → `task.ifPresent` → mark →
  poll. PRECONDITION: the tab never appears, or `sendPrompt` returns false. REACHABLE: yes.
  CONCLUSION: MEDIUM, same defect as the primary. Fixed by deleting the eager call; marking now
  happens inside `sendWhenSessionReady` on delivery.

## Assumptions
- clap's `--` ends option parsing, after which `ID -- PROMPT` binds `[SESSION_ID]` and
  `[PROMPT]` in order. Codex create already relies on `--` (≥90%).
- "Delivered" means `OpenSessionTab.sendPrompt(...)` returned `true`: either typed now, or
  parked in the bridge's hold slot and delivered on attach. This is the strongest delivery
  signal the code exposes.
- The pending-tab fallback (T6) counts as delivery when `sendPrompt` returns true, but only
  as best effort. `finalizeCreate` may overwrite it, and that loss is fail-safe.
- The FX code has no harness, so the marker timing is covered by a pure static seam in
  `MainWorkspace` (package-private, like `confirmedForPosting`) plus review by inspection.
