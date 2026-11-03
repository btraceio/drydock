# T28a — Pre-spawn task binding, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T28 in
docs/plans/DelayedTasksImplementationPlan.md, requirements D10/D13/P4.
No commit/push, auth changes, gh, nested agents, unowned edits or
other checkout. One serial writer; AGENTS.md changes are somebody
else's. ASCII-only source. Whole-suite regression baseline: **3147
tests / 0 failures** (plain `test` FROM intellij-plugin/, ~2.75 min).

## Context (accepted, read before writing)
- T22a: SessionDialogs.launchSession is the observable launch chain;
  SessionDialogs.PreparedSessionCallback is the no-op-compatible
  prepared-session seam T28 wires (default does nothing; failing
  callback logged+swallowed at SessionDialogs.java:79). TaskLauncher
  returns futures that complete on every path.
- T27a/b: TaskRuns/TaskRun/TaskRunFiles — receipts reserve task ids
  under the TaskWriterLock, record prepare/launch/failure/forget
  phases, tolerate stale callbacks (visible no-op refusals), survive
  catalog deletion for lookups; T27b pinned the refused-release
  semantics for a vanished-owner live PREPARE row AS-IS (do not fix).
- T12a: DelayedTaskService exposes the TaskToolContext seam the MCP
  tools drive; T10a: TaskTools commit transitions incl. the
  CompletionException channel.

OWN ONLY these files:
- NEW intellij-plugin/src/platform/java/app/drydock/delayedtasks/
  TaskLaunchBinding.java — the binding type (<=200 lines).
- BOUNDED delta to intellij-plugin/src/platform/java/app/drydock/
  intellij/actions/SessionDialogs.java — wire the binding hook into
  launchSession BEFORE the spawn; old callers keep the no-op hook.
- BOUNDED delta to intellij-plugin/src/platform/java/app/drydock/
  delay edtasks/DelayedTaskService.java — expose the binding
  operations through the same single-writer path (no parallel
  mutation path; CONFIRMED actual path: intellij-plugin/src/platform/
  java/app/drydock/delayedtasks/DelayedTaskService.java — the platform
  source set, NOT main).

Behavior requirements (plan T28):
- The hook binds a RESERVED task segment and writes the run receipt +
  task sessions BEFORE launchSession can spawn. Failure to bind
  PREVENTS the spawn (visible refusal; no partial state left behind —
  release the reservation on the failure path).
- Old callers unaffected: the hook defaults to no-op; existing
  launches byte-identical when no binding is requested.
- Failed/late launch reconciles BY ITS KNOWN ID: reconcile must not
  undo an agent's successful done/review committed after the binding
  (T07/T10a rules govern — a stale bind-write must not clobber).
- Expose a launch handle early enough for plan intent persistence and
  cancellation-generation checks (T32a RunningPlan.withCancellation
Generation/canceled(); do not implement plan logic — just expose the
  handle).
- The T27b refused-release semantics stay AS-IS: a vanished-owner
  live PREPARE row still refuses release; do not "fix" it.
- All writes off-EDT, immediate progress + visible errors per
  AGENTS.md.

Bounded reviewable deltas — additions + minimal seams. No committed
test file (T28b).

## Verification
Artifact-local smoke (randomized durable dir under
intellij-plugin/build/logs/, >=3 green runs, plain JVM, NO windows):
headless-constructible parts — bind-before-spawn ordering (bind write
completes before the spawn seam is invoked; failure prevents spawn and
releases the reservation), no-op-hook byte-identity for old callers,
reconcile-by-id never clobbering a later agent done/review, stale
callback refusals, launch-handle exposure. State honestly that the
real spawn path stays covered by T22b/manual.
Then the WHOLE plugin suite as the LAST adapter invocation: plain
`test` FROM intellij-plugin/ (its OWN Gradle build; repo-root is
:app) — cite an executed-:test log; ACTUAL XML counts (3147 baseline,
no new tests). No jar scans. No commits, pushes, nested agents or
unowned edits.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/platform/java/app/drydock/intellij/actions/SessionDialogs.java intellij-plugin/src/main/java/app/drydock/tasks/TaskRuns.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T28b or later tasks are
complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new file + bounded deltas (git diff per file)
against this block, the T22a/T27a accepted semantics, and the T07/T10a
transition rules. Verify: bind-before-spawn ordering with
failure-prevents-spawn + reservation release; no-op-hook
byte-identity; reconcile-by-id never clobbering later agent
done/review; launch-handle exposure without plan logic; T27b
refused-release semantics untouched; single-writer path only; off-EDT
+ progress + visible errors; bounded deltas + <=200 new file;
ASCII-only; durable honest smoke; whole-suite executed-:test
regression FROM intellij-plugin/ (independently parse the XML).
DELIVERY: your final response text is the authoritative report
(output files are deleted by a cleanup). Read-only: no Gradle,
source/doc/state edits, git mutation or agents. Return <=12 lines:
VERDICT first; defects with exact file:line + required outcome;
confirmed counts/log evidence; accepted deviations; memory ids. Do
not review the full feature.
