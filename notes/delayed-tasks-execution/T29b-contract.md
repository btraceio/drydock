# T29b — Resources across launch/resume/transfer fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T29 in
docs/plans/DelayedTasksImplementationPlan.md. No commit/push, auth
changes, gh, nested agents, unowned edits or other checkout. One serial
writer; AGENTS.md changes are somebody else's. ASCII-only NEW test
source. Whole-suite regression baseline: **3153 tests / 0 failures**
(plain `test` FROM intellij-plugin/, ~2.75 min).

## Context (accepted T29a semantics — read the production deltas first)
T29a COMPLETE: NEW app/drydock/delayedtasks/TaskLaunchResources.java
(150 lines): installedGuidance requires credentialFile present AND
plugin::mcpServesTaskTools -> ReviewBackend.servesTaskTools() AND
guidance.isInstalled() — no tool list restated; ResourcesRefusal
visible (:99-103); unreadable registry also refused (:92-95); javadoc
explicitly denies permission claims (no --add-dir read-only pretense).
DrydockSessionService (intellij/session) delta +79/-1: resolution
BEFORE context construction on transfer create (:399-408), startLaunch
(:647-657), resume (:995-1005) — all inside executor-stage pipelines
(off-EDT); null resolver seam -> Optional.empty() =
ordinary-session byte-identity (launchResourcesFor :750-756).
Coordinator-granted unowned one-liners: ReviewBackend.servesTaskTools()
+ DrydockPluginService.mcpServesTaskTools() (degraded backend reads
FALSE). Its smoke (build/logs/t29a-smoke-8rEHhKpwos/, 17+18 checks x3)
was never committed. THIS task ports the matrix.

OWN ONLY ONE new file: intellij-plugin/src/test/java/app/drydock/
intellij/session/DrydockSessionTaskResourcesTest.java (per plan T29;
verify the actual platform-test root and whether an existing
DrydockSessionService test offers in-package seams — T22b/T28b
reflection precedents if needed, park/restore in finally). <=300
lines / 120 cols. NO production edits. Matrix (plan T29 tests line):
1. Create: startLaunch resolves receipt extra directories + installed
   guidance into the CreateContext BEFORE construction (ordering
   assert; store-backed, zero mocking).
2. Restart + resume: the resume path carries the same resolved
   resources (assert the ResumeContext received them; guidance gate
   re-evaluated, not cached stale).
3. Transfer to each provider: transfer create resolves the same
   resources for the replacement context; ordinary-session
   (no-resources) transfer byte-identical.
4. Retained context after prerequisite forget: an already-created
   session's context is NOT retroactively changed when a prerequisite
   task is forgotten (assert the accepted T26/T28 retained semantics —
   resolution happens at launch/resume/transfer time only).
5. Missing required context fails visibly: a receipt whose extra
   directories cannot be resolved -> visible ResourcesRefusal, no
   launch; unreadable registry likewise.
6. Off-EDT resolution: directory existence checks never run on the
   EDT (assert resolution runs in the executor pipeline; pump-park
   for completion).
State honestly what remains smoke-covered (real spawn, provider CLI
rendering — T24a/T26a-accepted; degraded-backend gate FALSE case if
not constructible without the real plugin service).

## Platform-fixture discipline
Pump-park waits only; NO bare Thread.sleep; NEVER show(); real
store-backed services over mocking; honest red-green (documented
deliberate TEST-setup flaw, then green — never fake a red by breaking
production). Budget discipline: the suite alone is ~2m45s — ONE
whole-suite run only.

## Verification
Then the WHOLE plugin suite as the LAST adapter invocation: plain
`test` FROM intellij-plugin/ (its OWN Gradle build; repo-root is
:app) — cite an executed-:test log; ACTUAL XML counts (3153 baseline +
new; state the exact new total). No jar scans. No commits, pushes,
nested agents or unowned edits.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/main/java/app/drydock/session/DrydockSessionService.java intellij-plugin/src/platform/java/app/drydock/delayedtasks/TaskLaunchResources.java
(verify the actual DrydockSessionService path first)
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new fixture against this block and the T29a
accepted semantics. Verify: placement stated and valid, bounds/zero
production edits, all six matrix items with real assertions traced to
production line numbers (silently dropped items = defects), zero
mocking, pump-park only, never show(), honest red-green. Independently
parse the cited XML on disk and confirm the executed-:test log
(plugin build, not :app). DELIVERY: your final response text is the
authoritative report (output files are deleted by a cleanup).
Read-only: no Gradle, source/doc/state edits, git mutation or agents.
Return <=12 lines: VERDICT first; defects with exact file:line +
required outcome; confirmed counts/log evidence; accepted deviations;
memory ids. Do not review the full feature.
