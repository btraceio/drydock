# T17a — tree snapshot and lifecycle wiring, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T17a/T17b
in docs/plans/DelayedTasksImplementationPlan.md, requirements
D9/D10/P5. No commit/push, auth changes, gh, nested agents, unowned
edits or other checkout. One serial writer; AGENTS.md changes are
somebody else's. ASCII-only source. Whole-suite regression baseline:
**3092 tests / 0 failures** (plain `test`, ~2.5 min).

Accepted (do not edit): T13a TaskStoreWatcher/DelayedTaskService
(watchedSnapshot composition, scanForWatch/publishWatchedSnapshot),
T15a/b rows/model (150ms coalesced background derivation, View enum),
T16a/b renderer + ribbon. Read the EXISTING P/tasks/TaskTreeInputs.java
and TaskTreePanel.java FIRST — both are EXISTING files (bounded deltas;
the decisions table: TaskTreePanel.scheduleRebuild rebuilds off EDT
and coalesces; currently session rows are leaves). AGENTS.md UI rules
apply (no render-thread I/O, coalesced rebuilds, lifecycle disposal).

OWN ONLY two bounded deltas to EXISTING files:
- intellij-plugin/src/platform/java/app/drydock/intellij/tasks/
  TaskTreeInputs.java
- intellij-plugin/src/platform/java/app/drydock/intellij/tasks/
  TaskTreePanel.java
Deltas bounded and reviewable — additions + minimal seams, no
unrelated rewrites. No committed test file (T17b).

## Task block (approved plan T17a)
TaskTreeInputs (delta):
1. Gather the cached task snapshot (the service's watchedSnapshot
   composition) in the EXISTING background pass — no new pass, no
   render-thread I/O; a missing/unpublished snapshot degrades to
   session-only input.
2. Subscribe to task changes (the service's snapshot publication) and
   UNSUBSCRIBE on dispose — no listener leak.
TaskTreePanel (delta):
3. Build task children below session rows (the T15a row derivation
   feeding the tree through the existing build path).
4. PRESERVE task/session selection and expansion through coalesced
   rebuilds (the existing rebuild preserves it for session rows; the
   delta must carry the same preservation across the task rows —
   snapshot/restore keyed on the row identity the tree uses).
5. One burst -> bounded rebuilds: the existing coalescing must extend
   over task-change events too (a burst of snapshot publications
   results in one rebuild, not one per event).

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE — plain JVM where possible, NO windows): headless-constructible
parts — input gathering with a fake publication source (session-only
fallback when absent, snapshot consumed when present), subscription
lifecycle (dispose removes the listener; no further deliveries),
coalescing bound (N publications -> <=1 rebuild request, assert through
the panel's request seam if reachable headless). State honestly what
needs the platform fixture (live tree, selection/expansion through
real rebuilds) for T17b. >=3 consecutive green runs; record commands/
log paths.
Then the approved adapter WHOLE plugin suite as the LAST invocation:
plain `test` (no filter) — cite an executed-:test log; ACTUAL XML
counts (3092 baseline, no new tests). Tooling/provider failure =
blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T17b or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the two bounded deltas (git diff per file) against
this block and the accepted watcher/rows/panel production code.
Verify: snapshot gathered in the existing background pass with
session-only fallback, subscribe/unsubscribe on dispose (no leak),
task children through the existing build path, selection/expansion
preservation across coalesced rebuilds (keyed on the tree's row
identity), burst -> bounded rebuilds, no render-thread I/O, no
unrelated rewrites, bounded reviewable deltas, ASCII-only, durable
honest smoke, whole-suite executed-:test regression. Independently
parse writer logs/XML. DELIVERY: your final response text is the
authoritative report (output files are deleted by a cleanup).
Read-only: no Gradle, source/doc/state edits, git mutation or agents.
Return <=12 lines: VERDICT first; defects with exact file:line +
required outcome; confirmed counts/log evidence; accepted deviations;
memory ids. Do not review the full feature.
