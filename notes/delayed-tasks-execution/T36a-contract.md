# T36a — Plan recovery and application lifecycle, source, fresh context
# (Wave A of the parallel tail-batch: dispatched ALONE — T38+ chain behind T36)

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 15c4e41e (accepted work now COMMITTED —
9 thematic commits; the working tree is clean apart from AGENTS.md
foreign edits and session notes you must not touch). Read AGENTS.md,
plan T36 in docs/plans/DelayedTasksImplementationPlan.md, P4.10.
No commit/push, auth changes, gh, nested agents, unowned edits or
other checkout. One serial writer (you); the next wave runs in
parallel worktrees AFTER you land, so stay strictly within your owned
files. ASCII-only source. Whole-suite regression baseline: **3183
tests / 0 failures** (EXACTLY `cd intellij-plugin && ./gradlew test
--console=plain`; any ':app:' or ':buildSrc:' task line = WRONG build,
kill immediately — the repo-root build launches a real app window).

## Context (accepted, read before writing)
- T35a: PlanDriver executes reducer effects on the service executor;
  failure halts future work and reports started siblings.
- T33a: PlanStore — OS claim plans/<id>.claim; re-acquisition durably
  pauses; failed save = no launch permission.
- T13a: DelayedTaskService init/close lifecycle + TaskStoreWatcher
  disposal ordering (watcher disposed first, isolated).
- T27a/b: TaskRuns receipts survive catalog deletion; refused-release
  semantics PINNED AS-IS.

OWN ONLY these files:
- NEW intellij-plugin/src/platform/java/app/drydock/delayedtasks/
  PlanRecovery.java (<=250 lines).
- BOUNDED delta to intellij-plugin/src/platform/java/app/drydock/
  delayedtasks/DelayedTaskService.java — recovery wiring on
  init/close; own subscriptions; stop driver + flush stores BEFORE
  the shared executor closes (ordering per T13a precedent).

Behavior requirements (plan T36):
- Recover plans/receipts after catalog normalization on init.
- Publish Continue requirements but NEVER launch during project
  restoration (zero startup launches — a restart is a human
  checkpoint, not a duplicate-launch trap).
- On explicit Continue: reconcile persisted launch ids with
  catalog/receipts; revalidate graph/choices; ambiguous intent stays
  HALTED with remediation explained — never auto-retry.
- Crash-before/after intent, spawn-and-receipt-save, completed-stage-
  while-closed: each recovers to the documented state (visible, not
  guessed).
- Known existing session reused, foreign driver refused, close/init
  race resolved without partial state.
Bounded reviewable deltas. No committed test file (T36b).

## Verification
Artifact-local smoke (randomized durable dir under
intellij-plugin/build/logs/, >=3 green runs, plain JVM, NO windows):
headless-constructible parts — recovery after simulated crashes
(before intent / after intent / after spawn before receipt), the
zero-startup-launch invariant, explicit-Continue reconciliation,
ambiguous-intent halt, foreign-driver refusal, close/init race. State
honestly what T36b's fixture will cover.
Then the WHOLE plugin suite as the LAST adapter invocation — EXACTLY
`cd intellij-plugin && ./gradlew test --console=plain` in ONE bash
call — citing an executed-:test log; ACTUAL XML counts (3183 baseline,
no new tests). No jar scans. No commits, pushes, nested agents or
unowned edits. BUDGET DISCIPLINE: write the file FIRST, consult
accepted sources narrowly (PlanStore/PlanDriver/DelayedTaskService
signatures only), smoke, ONE whole-suite run, report.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/main/java/app/drydock/tasks/PlanStore.java intellij-plugin/src/platform/java/app/drydock/delayedtasks/PlanDriver.java intellij-plugin/src/platform/java/app/drydock/delayedtasks/DelayedTaskService.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T36b or later tasks are
complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new file + bounded delta (git diff per file)
against this block and the T35a/T33a/T13a/T27a accepted semantics.
Verify: zero startup launches, explicit-Continue-only reconciliation
with launch-id reconciliation and graph/choices revalidation,
ambiguous-intent halt with remediation, foreign-driver refusal,
close/init race, driver-stopped + stores-flushed before executor
close, T27b refused-release untouched, bounds, ASCII-only, durable
honest smoke, whole-suite executed-:test regression FROM
intellij-plugin/ (independently parse the XML; no :app: lines in the
log). DELIVERY: your final response text is the authoritative report
(output files are deleted by a cleanup). Read-only: no Gradle,
source/doc/state edits, git mutation or agents. Return <=12 lines:
VERDICT first; defects with exact file:line + required outcome;
confirmed counts/log evidence; accepted deviations; memory ids. Do
not review the full feature.
