# T25b — Guidance materialization acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T25b in
docs/plans/DelayedTasksImplementationPlan.md. No commit/push, auth
changes, gh, nested agents, unowned edits or other checkout. One serial
writer; AGENTS.md changes are somebody else's. ASCII-only NEW test
source. Whole-suite regression baseline: **3092 tests / 0 failures**
(plain `test`, ~2.5 min).

## Context
T25a COMPLETE and accepted: NEW app/drydock/tasks/TaskGuidance.java
(319 lines) — versioned layout .claude-plugin/plugin.json +
skills/drydock-tasks/SKILL.md under <state>/tasks-guidance/<version>/;
claudePluginDirectory()/piSkillFile() seams (T26-consumable);
byte-stable verify-only re-install; atomic staging+fsync+ATOMIC_MOVE
with temp discard on failure; no writes outside the injected state
root; previews pure; injected-root ctor keeps home untouched. Its
SMOKE (build/logs/t25a-smoke-*/) runs 1-2 were RED — a REAL race
defect (concurrent install) caught and FIXED; race-loser classified
by live target state instead of exception type (macOS bare
FileSystemException; documented at TaskGuidance.java:225-233); parity
byte-verified vs the classpath resource. A coordinator-ratified
deviation on TaskLauncher.java (accepted string ellipsis, T22a) is NOT
part of this task.

OWN ONLY ONE new file: intellij-plugin/src/test/java/app/drydock/tasks/
TaskGuidanceTest.java (package app.drydock.tasks — in-package seam
access; check the ACTUAL test-source root of an existing app.drydock
core test first). <=300 lines / 120 cols. NO production edits. Port
the T25a smoke matrix into committed tests:
1. Resource parity: materialized SKILL.md/plugin.json byte-equal the
   classpath resources.
2. Concurrent install: the race T25a fixed — two concurrent installs
   end with one coherent versioned layout (loser classified by live
   target state, not exception type); assert the documented behavior.
3. Read-only preview: preview is pure / makes zero writes (state-root
   tree comparison, path+mtime).
4. Stable path within one version: repeated installs/queries within a
   version yield identical paths (byte-stable verify-only re-install).
5. No home writes: injected-root ctor leaves the user home untouched
   (scoped-home listing snapshot or equivalent honest evidence).

## Verification
Honest red-green: first run with a deliberate flaw in the TEST setup
documented, then fixed — do NOT fake a red by breaking production.
Platform-unfriendly parts (real ATOMIC_MOVE cross-device, fsync
failure injection) stay smoke-covered — say so honestly.
Then the WHOLE plugin suite as the LAST adapter invocation: plain
`test` (no filter) — cite an executed-:test log; ACTUAL XML counts
(3092 baseline + new tests; state the exact new total). No jar scans.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/main/java/app/drydock/tasks/TaskGuidance.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that T26 or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of TaskGuidanceTest.java against this block, the T25a
accepted semantics (TaskGuidance.java production source), and the
five-item matrix above. Verify: in-package placement at the ACTUAL
test root, <=300 lines/120 cols, zero production edits, all five
matrix items with real assertions (parity byte-equality, race
end-state coherence, zero-write proof, path stability, home
untouched), honest red-green with no faked red, no production
behavior restated from memory — every expected string/path traced to
TaskGuidance.java. Independently parse the writer's cited XML on disk
and confirm the executed-:test log. DELIVERY: your final response
text is the authoritative report (output files are deleted by a
cleanup). Read-only: no Gradle, source/doc/state edits, git mutation
or agents. Return <=12 lines: VERDICT first; defects with exact
file:line + required outcome; confirmed counts/log evidence; accepted
deviations; memory ids. Do not review the full feature.
