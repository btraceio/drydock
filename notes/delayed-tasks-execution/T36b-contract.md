# T36b — Plan recovery acceptance fixtures, fresh context
# (Wave B of the parallel tail-batch — runs in an ISOLATED WORKTREE alongside T38a)

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD f8c8859a (T36a plan recovery is
COMMITTED — read PlanRecovery.java and the DelayedTaskService delta).
Read AGENTS.md, plan T36 in docs/plans/DelayedTasksImplementationPlan.md.
No commit/push, auth changes, gh, nested agents, unowned edits or
other checkout. A sibling task (T38a) works in a separate worktree on
DelayedTaskStart/DelayedTaskService — YOUR file is disjoint; do not
touch anything else. ASCII-only NEW test source. Whole-suite
regression baseline: **3183 tests / 0 failures** (EXACTLY
`cd intellij-plugin && ./gradlew test --console=plain`; any ':app:' or
':buildSrc:' task line = WRONG build, kill immediately — the
repo-root build launches a real app window).

## Context (accepted T36a semantics — read the production sources first)
PlanRecovery.java (exactly 250 lines): scan is read-only and publishes
Continue requirements only (recoverPlans/refreshRecovery never launch);
the only driving path is continuePlan -> Proceed -> service drive()
via the caller-supplied SegmentLauncher; ambiguous PREPARE receipt
with dead session and unfinished work -> Halted with release
remediation (:219-226), never auto-retried; foreign/second driver
refused via acquireClaim -> Refused (P4.13); close/init race guarded
by synchronized publishPlansOrClose/publishRecovery against closed;
close() stops drivers -> disposes watcher -> flushes/closes task store
-> flushes/closes plan store, before the shared executor; graph/choices
revalidation at Continue (invalidGraph); launch-id reconciliation via
receipts (assess); unknown plan refused with "persists it first (T38)".
Its smoke (t36a-smoke-cqyiE3qz5a/, 42 checks x3) was never committed.
THIS task ports the matrix.

OWN ONLY ONE new file: intellij-plugin/src/test/java/app/drydock/
intellij/delayedtasks/PlanRecoveryTest.java (verify seam accessibility;
TaskWorktreeUse precedent: public production class works from
intellij.delayedtasks; reflective glue only where NO seam exists,
park/restore in finally, disclosed). <=300 lines / 120 cols. NO
production edits. Matrix (plan T36 tests line):
1. Crash before intent: no plan persisted -> nothing to recover, no
   launch.
2. Crash after intent: persisted plan with reserved stages recovers
   to the documented state and requires explicit Continue.
3. Spawn and receipt save: a crash between spawn and receipt save
   reconciles via receipts (launch-id reconciliation).
4. Completed stage while closed: a stage that finished while the IDE
   was closed is recognized (not relaunched).
5. Zero startup launches: init recovery publishes requirements only —
   no launch path fires (assert via the fake launcher's call count
   staying zero).
6. Known existing session reused; foreign driver refused (P4.13).
7. Close/init race: recovery during close resolves without partial
   state.
8. Ambiguous intent stays halted with remediation text (verbatim
   refusal strings traced to PlanRecovery.java).
State honestly what remains smoke-covered (real spawn).

## Platform-fixture discipline
Pump-park waits only; NO bare Thread.sleep; NEVER show(); real
PlanStore/TaskRuns over @TempDir (zero mocking of stores; fakes only
for the launcher); honest red-green (documented deliberate TEST-setup
flaw, then green — never fake a red by breaking production). Budget
discipline: the suite alone is ~2m45s — ONE whole-suite run only, in
ONE bash call, exactly the command above.

## Verification
Then the WHOLE plugin suite as the LAST adapter invocation — the
exact command above — citing an executed-:test log; ACTUAL XML counts
(3183 baseline + new; state the exact new total). No jar scans. No
commits, pushes, nested agents or unowned edits.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/platform/java/app/drydock/delayedtasks/PlanRecovery.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new fixture against this block and the T36a
accepted semantics. Verify: placement/bounds/zero production edits,
all eight matrix items with real assertions traced to PlanRecovery.java
line numbers (silently dropped items = defects), zero store mocking,
pump-park only, never show(), honest red-green. Independently parse
the cited XML on disk and confirm the executed-:test log (plugin
build, no :app: lines). DELIVERY: your final response text is the
authoritative report (output files are deleted by a cleanup).
Read-only: no Gradle, source/doc/state edits, git mutation or agents.
Return <=12 lines: VERDICT first; defects with exact file:line +
required outcome; confirmed counts/log evidence; accepted deviations;
memory ids. Do not review the full feature.
