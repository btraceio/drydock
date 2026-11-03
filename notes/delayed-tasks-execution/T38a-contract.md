# T38a — Confirm and start a cross-project plan, source, fresh context
# (Wave B of the parallel tail-batch — runs in an ISOLATED WORKTREE alongside T36b)

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD f8c8859a (T36a plan recovery is
COMMITTED — read it). Read AGENTS.md, plan T38 in
docs/plans/DelayedTasksImplementationPlan.md, D8/D12/D16/P4. No
commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. You share this checkout with NO other writer, but a
sibling task (T36b) works in a separate worktree on a disjoint file —
stay strictly within YOUR owned files so the merge stays clean.
ASCII-only source. Whole-suite regression baseline: **3183 tests / 0
failures** (EXACTLY `cd intellij-plugin && ./gradlew test
--console=plain`; any ':app:' or ':buildSrc:' task line = WRONG build,
kill immediately — the repo-root build launches a real app window).

## Context (accepted, read before writing)
- T31a: DelayedTaskStart (intellij/delayedtasks, 249 lines) — the
  single-project start orchestration: atomic IN_FLIGHT claim, off-EDT
  plan via StartPlanner, post-OK revalidation, launch through
  prepareTask/chooseTask/launchChosen -> SessionDialogs.launchSession
  with ALL-segment bindings; CROSS_PROJECT_REFUSAL delegates to T38
  (THIS task implements that routing).
- T37a: StartPlanDialog/PlanLaunchChoices — the multi-segment
  confirmation flow (choices immutable, capture() only in doOKAction,
  Configure returns Choice only).
- T36a: PlanRecovery — recovery publishes Continue requirements, never
  launches at startup; explicit Continue reconciles.
- T35a: PlanDriver executes effects; T33a: PlanStore save = launch
  permission; T28a: TaskLaunchBinding pre-spawn binding; T29a:
  TaskLaunchResources.
- LaunchResources javadoc: extra directories are facts about what a
  launch may read, never permissions.

OWN ONLY these files:
- BOUNDED delta to intellij-plugin/src/platform/java/app/drydock/
  delayedtasks/DelayedTaskStart.java (the plan file list puts the
  cross-project routing here; verify actual path — it currently lives
  in intellij/delayedtasks; if the plan means moving it, ASK instead
  of moving).
- BOUNDED delta to intellij-plugin/src/platform/java/app/drydock/
  delayedtasks/DelayedTaskService.java (T36a's file — additions-only
  on top of the committed recovery wiring).
No other files. No committed test file (T38b owns CrossProjectStartTest).

Behavior requirements (plan T38):
- Route a multi-project (multi-segment) plan to the T37a confirmation
  flow (replacing DelayedTaskStart's CROSS_PROJECT_REFUSAL for the
  dialog-available path; keep a visible refusal only where the dialog
  cannot run — state the boundary).
- Revalidate snapshot and availability after dialog close; persist
  approved policy changes + plan/reservations BEFORE requesting any
  launch effect (a failed save refuses the WHOLE start — never quietly
  launch only some user-confirmed work).
- Failure or stale graph refuses the whole start (visible).
- Normalize user-selected window behavior with the P4 origin/fallback
  policy (T35a driver honored confirmed choices — reuse the same
  normalization, do not re-derive).
- Duplicate start: second start while one is in flight is the
  existing atomic no-op (T31a IN_FLIGHT guard) extended to the
  cross-project path.
- Cancel: zero writes, zero reservations (T37a capture discipline).
Bounded reviewable deltas — additions + minimal seams. No committed
test file (T38b).

## Verification
Artifact-local smoke (randomized durable dir under
intellij-plugin/build/logs/, >=3 green runs, plain JVM, NO windows):
headless-constructible parts — routing to the confirmation flow for a
two-segment plan, revalidation-after-close (stale graph refuses whole
start), persistence-before-effects ordering (save failure = zero
launches, no partial reservations), duplicate-start no-op, cancel
zero-write. State honestly what T38b's fixture covers.
Then the WHOLE plugin suite as the LAST adapter invocation — EXACTLY
`cd intellij-plugin && ./gradlew test --console=plain` in ONE bash
call — citing an executed-:test log; ACTUAL XML counts (3183 baseline,
no new tests). No jar scans. No commits, pushes, nested agents or
unowned edits. BUDGET DISCIPLINE: write the deltas FIRST, consult
accepted sources narrowly (DelayedTaskStart/StartPlanDialog/PlanDriver
signatures only), smoke, ONE whole-suite run, report.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/platform/java/app/drydock/intellij/delayedtasks/DelayedTaskStart.java intellij-plugin/src/platform/java/app/drydock/delayedtasks/PlanDriver.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T38b or later tasks are
complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the bounded deltas (git diff per file) against
this block and the T31a/T37a/T36a/T35a accepted semantics. Verify:
multi-project routing to the T37a confirmation flow, revalidation
after close, persistence-before-effects with whole-start refusal on
save failure, no partial launches, P4 window normalization reused,
duplicate-start no-op, cancel zero-write, additions-only deltas,
ASCII-only, durable honest smoke, whole-suite executed-:test
regression FROM intellij-plugin/ (independently parse the XML; no
:app: lines). DELIVERY: your final response text is the authoritative
report (output files are deleted by a cleanup). Read-only: no Gradle,
source/doc/state edits, git mutation or agents. Return <=12 lines:
VERDICT first; defects with exact file:line + required outcome;
confirmed counts/log evidence; accepted deviations; memory ids. Do
not review the full feature.
