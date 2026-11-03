# T43a — Adversarial multi-process fixtures, sources, fresh context
# (Wave D of the parallel tail-batch — runs in an ISOLATED WORKTREE)

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD e4c94cb5 (T36/T38/T39/T40 sources AND
their Wave C siblings COMMITTED — read them from git, not a worktree).
Read AGENTS.md, plan T43 in docs/plans/DelayedTasksImplementationPlan.md.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. Sibling tasks work in separate worktrees on DISJOINT files
(the T43 process fixture sources vs the two platform test files) — stay strictly in YOUR owned files. ASCII-only NEW test
source. Whole-suite regression baseline: **3197 tests / 0 failures** (EXACTLY
`cd intellij-plugin && ./gradlew test --console=plain`; any ':app:' or
':buildSrc:' task line = WRONG build, kill immediately — the
repo-root build launches a real app window).

## Context (accepted, read before writing)
- T05c: TaskStore/TaskWriterLock — the OS lock file sits OUTSIDE the
  store root (sibling <root-name>.lock); same-JVM tryLock throws
  OverlappingFileLockException instead of returning null, so ONLY a
  real second process reaches the bounded foreign-lock failure (the
  deferred T05c case THIS task delivers).
- T33a: PlanStore claim plans/<id>.claim separate from TaskWriterLock;
  T27a: TaskRuns receipts; T14a: the MCP task-tool surface; T38a/T39a/
  T40a: the confirmed plan lifecycle committed.
- The repo ProcessRunner contract (AGENTS.md) governs child processes:
  argument list only, never a shell; timeouts; no PTY. Your fixture
  JVMs are run-to-completion workers, so ProcessRunner semantics
  apply.

## OWN ONLY
TWO new files (the plan's T43a source half; T43b owns the
end-to-end IDE-driven suite):
- intellij-plugin/src/test/java/app/drydock/tasks/
  TaskStoreProcessFixture.java (the subprocess harness: builds a temp
  task root, launches a child JVM via ProcessRunner semantics with an
  explicit classpath, collects outcomes; <=250 lines).
- intellij-plugin/src/test/java/app/drydock/tasks/
  TaskStoreProcessTest.java (the committed cross-process tests; <=300
  lines). Matrix:
1. Unique create + status/note updates across two JVMs contending on
   one temporary task root — no lost update, no torn document.
2. The OS-lock foreign-holder timeout path: a child holding the lock
   makes the other process fail with the bounded foreign-lock failure
   (the T05c-deferred case — NOT OverlappingFileLockException, which
   only same-JVM reaches).
3. Single driver: two processes attempting to drive one plan — one
   wins the claim, the other refuses visibly.
4. Crash/restart: a child killed mid-operation leaves a state the
   next process recovers per the T36a semantics (no duplicate launch).
Each case: real temp roots, no home store, no network. State honestly
what stays for T43b (the end-to-end IDE-driven suite).

## Verification
Honest red-green (documented deliberate TEST-setup flaw, then green —
never fake a red by breaking production).
Then the WHOLE plugin suite as the LAST adapter invocation — EXACTLY
`cd intellij-plugin && ./gradlew test --console=plain` in ONE bash
call — citing an executed-:test log; ACTUAL XML counts (3197 baseline, no new tests (T43b owns the end-to-end suite)).
No jar scans. No commits, pushes, nested agents or unowned edits.
BUDGET DISCIPLINE: read the committed sources first, then write,
red-green, ONE whole-suite run, report.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/main/java/app/drydock/tasks/TaskWriterLock.java intellij-plugin/src/main/java/app/drydock/tasks/TaskStore.java intellij-plugin/src/main/java/app/drydock/tasks/PlanStore.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check against this block and the accepted semantics named
above. Independently parse the cited XML on disk and confirm the
executed-:test log (plugin build, no :app: lines). DELIVERY: your
final response text is the authoritative report (output files are
deleted by a cleanup). Read-only: no Gradle, source/doc/state edits,
git mutation or agents. Return <=12 lines: VERDICT first; defects
with exact file:line + required outcome; confirmed counts/log
evidence; accepted deviations; memory ids. Do not review the full
feature.
