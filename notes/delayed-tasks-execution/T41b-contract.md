# T41b — Prerequisite worktree guard fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T41 in
docs/plans/DelayedTasksImplementationPlan.md. No commit/push, auth
changes, gh, nested agents, unowned edits or other checkout. One serial
writer; AGENTS.md changes are somebody else's. ASCII-only NEW test
source. Whole-suite regression baseline: **3174 tests / 0 failures**
(EXACTLY `cd intellij-plugin && ./gradlew test --console=plain`; any
':app:' or ':buildSrc:' task line = WRONG build, kill immediately —
the repo-root build launches a real app window; verified incident).

## Context (accepted T41a semantics — read the production deltas first)
T41a COMPLETE: NEW app/drydock/delayedtasks/TaskWorktreeUse.java (226):
receipt-based resolution via TaskRuns.byTask/bySession + TaskFiles.scan,
all FS I/O off EDT. TOCTOU: claim acquired AFTER the warning, guard
recheck under the claim at BOTH sites — SessionDone.java:139
(pre-dialog blocker) + :283 (removeWorktree recheck under the
FORGET_AND_REMOVE reservation) and TaskActions.java:517-524
(claimRemoval -> guard -> delete per target). FORGET-only release
(P4; stopped sessions still block); external deletion surfaces missing
context visibly (gone-path + promised-context refusal ordered first);
DONE-vs-remove separation (commit/forget stay committed on refusal);
T27b refused-release untouched; retained orphans non-blocking; claim
token via captureFailed. Residual: worktreeRemovalPending has no
consumer yet (later task); TOCTOU closure rests on the post-claim
receipt re-read. Its smoke (t41a-smoke-99130a28cfb1/, 22 checks x3)
was never committed. THIS task ports the matrix.

OWN ONLY ONE new file: intellij-plugin/src/test/java/app/drydock/
intellij/delayedtasks/TaskWorktreeUseTest.java (per plan T41; verify
seams — TaskWorktreeUse lives in app.drydock.delayedtasks; if it is
public, an intellij.delayedtasks placement works; else state the
choice per T28b). <=300 lines / 120 cols. NO production edits.
Matrix (plan T41 tests line):
1. Done-triggered advancement vs remove: DONE advances dependents;
   removal is separately gated.
2. Forgotten prereq history: a forgotten prerequisite remains
   resolvable for the guard (bounded T27a history).
3. Stopped/live consumers: a LIVE dependent blocks removal; a stopped
   one still blocks (P4 — release on explicit FORGET only, not
   idle/stop).
4. Main checkout: the main checkout path is treated per the accepted
   resolution semantics (verify what T41a does and pin it).
5. External removal: the path gone underneath -> visible
   missing-context refusal, no silent success.
6. No provenance: a path with no receipt provenance is refused
   visibly.
7. Dropped prereq: a prerequisite dropped from the task document
   releases its claims (per the accepted semantics — verify exactly
   what T41a does).
8. Two consumers: two dependent sessions both block until each is
   FORGOTTEN; after the last FORGET removal proceeds.
Assert the warn-then-claim ordering wherever the harness can reach it
(late-arriving consumer between warning and claim blocks removal).
State honestly what remains smoke-covered.

## Platform-fixture discipline
Pump-park waits only; NO bare Thread.sleep; NEVER show(); real
store-backed services over mocking; honest red-green (documented
deliberate TEST-setup flaw, then green — never fake a red by breaking
production). Budget discipline: the suite alone is ~2m45s — ONE
whole-suite run only, in ONE bash call, exactly the command above
(cwd resets across revives have put children in the wrong build three
times).

## Verification
Then the WHOLE plugin suite as the LAST adapter invocation — the
exact command above — citing an executed-:test log; ACTUAL XML counts
(3174 baseline + new; state the exact new total). No jar scans. No
commits, pushes, nested agents or unowned edits.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/platform/java/app/drydock/delayedtasks/TaskWorktreeUse.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new fixture against this block and the T41a
accepted semantics. Verify: placement/bounds/zero production edits,
all eight matrix items with real assertions traced to the T41a
production code (silently dropped items = defects), zero mocking,
pump-park only, never show(), honest red-green. Independently parse
the cited XML on disk and confirm the executed-:test log (plugin
build, no :app: lines). DELIVERY: your final response text is the
authoritative report (output files are deleted by a cleanup).
Read-only: no Gradle, source/doc/state edits, git mutation or agents.
Return <=12 lines: VERDICT first; defects with exact file:line +
required outcome; confirmed counts/log evidence; accepted deviations;
memory ids. Do not review the full feature.
