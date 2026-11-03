# T28b — Pre-spawn binding acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T28 in
docs/plans/DelayedTasksImplementationPlan.md. No commit/push, auth
changes, gh, nested agents, unowned edits or other checkout. One serial
writer; AGENTS.md changes are somebody else's. ASCII-only NEW test
source. Whole-suite regression baseline: **3147 tests / 0 failures**
(plain `test` FROM intellij-plugin/, ~2.75 min).

## Context (accepted T28a semantics — read the production deltas first)
T28a COMPLETE: NEW app/drydock/delayedtasks/TaskLaunchBinding.java
(229 lines, overage coordinator-ratified): bind (:81-102) reserves +
writes the session link BEFORE showConsoleTab/spawn;
SessionDialogs.bindingBound (:322-334) completes the future
exceptionally on refusal = no tab, no spawn; failure after reservation
releases it (releaseReserved :166-176); T27b refused-release only
logged, never worked around (:172-174); no-op identity (null-binding
+ default PreparedSessionCallback; failed callback logged+swallowed
SessionDialogs :76-80 per T22a); reconcileFailed (:133-156) reverts
only while status==STARTED, keeps later done/review (T07/T10a);
LaunchHandle.staleAt exposed (:64-68) with no plan logic; single-writer
via fresh TaskStore/TaskRuns; DelayedTaskService.launchBinding additive
(:205-211). Its smoke (build/logs/t28a-smoke-Kr9wXb2mQv4t/, 33 checks
x3) was never committed. THIS task ports the matrix.

OWN ONLY ONE new file: intellij-plugin/src/test/java/app/drydock/
intellij/delayedtasks/TaskLaunchBindingTest.java (package
app.drydock.intellij.delayedtasks — in-package; check the actual
platform-test root and T12b/T19b platform-fixture idioms; note
TaskLaunchBinding lives in app.drydock.delayedtasks (platform), so
either in-package-with-the-PRODUCTION test placement
(intellij-plugin/src/test/java/app/drydock/delayedtasks/) or an
intellij.delayedtasks placement with accessible seams — verify what is
accessible and state the choice). <=300 lines / 120 cols. NO
production edits. Matrix (plan T28 tests line):
1. Fake harness updates through MCP immediately on spawn: the
   session-link write is visible to a fresh TaskStore BEFORE the
   spawn seam fires (ordering assertion, store-backed, zero mocking).
2. Bind-save failure means zero spawns: failure on the bind write ->
   future completes exceptionally, reservation released, spawn seam
   never invoked, store shows no partial state.
3. Failed launch release: reconcileFailed reverts while status==STARTED;
   a later agent done/review is NOT clobbered (assert the transition
   survives reconcile).
4. Shutdown race: a close/cancel during the bind window resolves
   without partial state (future completes, no spawn, no orphan
   reservation).
5. No roll-back of newer human/agent edits: stale-callback refusals
   are logged, never silent rewrites; T27b refused-release semantics
   pinned AS-IS.
6. No-op hook byte-identity: default callback / null binding keeps
   old-caller behavior (assert the launch chain shape unchanged).
State honestly what remains smoke-covered (real spawn, cross-process).

## Platform-fixture discipline
Pump-park waits only; NO bare Thread.sleep; NEVER show(); real
store-backed service over mocking (T10b/T12b precedent); honest
red-green (documented deliberate TEST-setup flaw, then green — never
fake a red by breaking production). Budget discipline: the suite
alone is ~2m45s — one whole-suite run only.

## Verification
Then the WHOLE plugin suite as the LAST adapter invocation: plain
`test` FROM intellij-plugin/ (its OWN Gradle build; repo-root is
:app) — cite an executed-:test log; ACTUAL XML counts (3147 baseline +
new; state the exact new total). No jar scans. No commits, pushes,
nested agents or unowned edits.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/platform/java/app/drydock/delayedtasks/TaskLaunchBinding.java intellij-plugin/src/platform/java/app/drydock/intellij/actions/SessionDialogs.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new fixture against this block and the T28a
accepted semantics. Verify: placement choice stated and valid,
bounds/zero production edits, all six matrix items with real
assertions traced to production line numbers (merged items disclosed
in javadoc — flag silently dropped items as defects), zero mocking,
pump-park only, never show(), honest red-green, T27b semantics pinned
not fixed. Independently parse the cited XML on disk and confirm the
executed-:test log (plugin build, not :app). DELIVERY: your final
response text is the authoritative report (output files are deleted
by a cleanup). Read-only: no Gradle, source/doc/state edits, git
mutation or agents. Return <=12 lines: VERDICT first; defects with
exact file:line + required outcome; confirmed counts/log evidence;
accepted deviations; memory ids. Do not review the full feature.
