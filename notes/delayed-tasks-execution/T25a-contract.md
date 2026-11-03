# T25a — task guidance materialization, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T25a/T25b
in docs/plans/DelayedTasksImplementationPlan.md, requirement D17. No
commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source. Whole-suite regression baseline: **3083 tests / 0
failures** (plain `test`, ~2.5 min).

Accepted (do not edit): T42a/b R/tasks/SKILL.md + McpServer handshake
(the guidance CONTENT exists in the plugin resources), T23 launch
resources (read what it added — LaunchResources.java in agent/api),
T12a DelayedTaskService. Read how plugin-owned state dirs are resolved
in existing code (grep for state dirs / plugin path utilities) and the
SKILL.md resource path (app/drydock — resource /tasks/SKILL.md on the
classpath).

OWN ONLY this new file:
intellij-plugin/src/main/java/app/drydock/tasks/TaskGuidance.java
Target <=350 lines. No committed test file (T25b).

## Task block (approved plan T25a)
TaskGuidance (core, no platform imports):
1. Materialize VERSIONED bundled guidance (the /tasks/SKILL.md
   resource — plus any companion resources T42a shipped with it; read
   what exists) under PLUGIN-OWNED state (e.g. <state>/tasks-guidance/
   <version>/), with a Claude plugin manifest and skill layout plus a
   pi skill path — the layout the later T26 per-invocation flags
   consume. Versioned directory per version.
2. Immutable bytes per version: the same version always materializes
   byte-identical files (content hash-stable; a re-install of the same
   version is a no-op or a verify, never a rewrite that churns bytes).
3. Atomic install: write temp + fsync + move (the TaskRunFiles
   discipline); a failed install leaves NO partial directory (temp
   cleaned, target absent); concurrent installs of the same version
   converge (one wins, the other verifies — never both writing).
4. NO global harness config writes: nothing outside the plugin-owned
   state dir is ever written (no ~/.claude, no harness config files —
   D17/P6; per-invocation wiring is T26's job, not file mutation).
5. Launch preview reads cause NO filesystem mutation: reading the
   guidance path for preview/install decisions does not create
   anything (lazy materialization only on explicit install, which
   happens off the EDT earlier in the launch flow — the install API
   itself is sync/async callable, not EDT-bound).
6. Stable path within one version: once materialized, the path for a
   version never changes (idempotent lookups).
7. No home writes: with an injected state root (tests/temp), nothing
   lands under the real user home (assertable).

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE — plain JVM, NO windows, never show()): resource parity
(materialized bytes == classpath resource bytes for every versioned
file), same-version re-install no-op (bytes and mtimes unchanged),
concurrent install (two threads racing the same version — one writes,
both verify, no corruption), failed install (unwritable target ->
temp cleaned, no partial dir, visible failure), read-only preview
(preview reads create nothing), stable path (two lookups same
version -> same path), no home writes (injected root, home untouched),
new version materializes alongside (old untouched). >=3 consecutive
green runs; record commands/log paths.
Then the approved adapter WHOLE plugin suite as the LAST invocation:
plain `test` (no filter) — cite an executed-:test log; ACTUAL XML
counts (3083 baseline, no new tests). Tooling/provider failure =
blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T25b or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of TaskGuidance.java against this block and the
accepted SKILL.md resource + TaskRunFiles write discipline. Verify:
versioned materialization with the T26-consumable layout, byte-stable
same-version installs (no-op/verify), atomic temp+fsync+move with
no partial dirs on failure, concurrent-install convergence, NO writes
outside the plugin state dir (no harness config), preview reads
mutate nothing, stable per-version paths, injected-root no-home-writes
seam, <=350 lines, ASCII-only, durable smoke evidence (independently
read the run logs), whole-suite executed-:test regression
(independently parse the XML on disk). DELIVERY: your final response
text is the authoritative report (output files are deleted by a
cleanup). Read-only: no Gradle (XML on disk is the evidence), no jar
scans, no source/doc/state edits, git mutation or agents. Return
<=12 lines: VERDICT first; defects with exact file:line + required
outcome; confirmed counts/log evidence; accepted deviations; memory
ids. Do not review the full feature.
