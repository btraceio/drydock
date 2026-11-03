# T35a — Launch effect driver, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T35 in
docs/plans/DelayedTasksImplementationPlan.md, requirements P4.4-P4.8.
No commit/push, auth changes, gh, nested agents, unowned edits or
other checkout. One serial writer; AGENTS.md changes are somebody
else's. ASCII-only source. Whole-suite regression baseline: **3168
tests / 0 failures** (plain `test` FROM intellij-plugin/, ~2.75 min).

## Context (accepted, read before writing)
- T34a: PlanProgress is the PURE reducer — effects (LaunchStage/
  AwaitingConfirmation/Halted/Refused/PlanCanceled/PlanCompleted)
  are RETURNED, never executed. YOUR driver EXECUTES them.
- T33a: PlanStore — CAS revisions with visible stale refusal; one OS
  claim plans/<id>.claim separate from TaskWriterLock; re-acquisition
  durably pauses (no auto-resume — composes T34a's Continue); locks
  per-op never across external work; failed save = visible exception
  = no launch permission.
- T32a: PlanLaunch/RunningPlan — per-stage StageState with
  finished-implies-launched invariants, cancellation generation
  (withCancellationGeneration/canceled()).
- T28a: TaskLaunchBinding — pre-spawn binding; T29a:
  TaskLaunchResources — resolution before context construction;
  T22a: TaskLauncher futures complete on every path.
- T09a: TaskPrompt for segment composition (do not re-derive).

OWN ONLY ONE new file: intellij-plugin/src/platform/java/app/drydock/
delayedtasks/PlanDriver.java (<=350 lines). NO deltas to existing
files unless a signature genuinely must widen (then ask; do not edit
silently).

Behavior requirements (plan T35):
- Serialize reducer effects on the service executor (the accepted
  DelayedTaskService executor seam; never the EDT).
- Acquire the plan claim, reserve the stage, persist per-segment
  intent/id (PlanStore save = launch permission), THEN launch stage
  segments in PARALLEL via TaskLauncher.
- Select origin/available window; honor confirmed window choices
  (PlanLaunch origin-window descriptor).
- Re-prepare current Inputs OFF EDT, validate branch/path/agent/
  context, NEVER launch from a stale Inputs closure (re-read before
  each launch, not a captured closure from plan start).
- Stale completions cannot advance the generation (T32a
  cancellation-generation check before applying any effect; a
  completion whose generation is stale is a logged no-op, never an
  advance).
- Failure halts future work and reports already-started siblings
  (halt state returned/composed, started stages left running —
  cancellation does NOT kill launched segments per T34a Cancel
  semantics).
- LaunchStage effects map 1:1 to TaskLauncher launches; other effects
  (Halted/Refused/PlanCanceled/PlanCompleted) are surfaced visibly,
  never swallowed.
- No double-launch: reserve-then-launch with the reservation visible
  in the plan state (duplicate events are no-ops, per the PlanProgress
  target-starts-once rule).

## Verification
Artifact-local smoke (randomized durable dir under
intellij-plugin/build/logs/, >=3 green runs, plain JVM, NO windows):
headless-constructible parts — the driver over a FAKE launcher
(recording call counts/order/barriers, no real spawn): parallel stage
segments, stage barrier (stage N+1 waits for stage N), duplicate
event no-op, stale-completion no-advance, failure halts + reports
started siblings, claim loss refusal, save-failure no-launch,
canceled/disposed window paths. State honestly that real launches
stay covered by T22b/manual.
Then the WHOLE plugin suite as the LAST adapter invocation: plain
`test` FROM intellij-plugin/ (its OWN Gradle build; repo-root is
:app) — cite an executed-:test log; ACTUAL XML counts (3168 baseline,
no new tests). No jar scans. No commits, pushes, nested agents or
unowned edits. BUDGET DISCIPLINE: write the file FIRST, consult
accepted sources narrowly (PlanProgress/PlanStore/PlanLaunch/
TaskLauncher signatures only), smoke, ONE whole-suite run, report.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/main/java/app/drydock/tasks/PlanProgress.java intellij-plugin/src/main/java/app/drydock/tasks/PlanStore.java intellij-plugin/src/main/java/app/drydock/tasks/PlanLaunch.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T35b or later tasks are
complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new file against this block and the
T34a/T33a/T32a/T28a/T29a accepted semantics. Verify: effects executed
on the service executor (never EDT); claim -> reserve -> persist ->
launch ordering; parallel segments per stage; fresh Inputs re-read
(no stale closure launches); generation-checked completions (stale =
logged no-op); failure halts + reports started siblings without
killing launched stages; visible surfacing of non-launch effects;
reserve-then-launch (no double-launch); <=350; ASCII-only; durable
honest smoke over a fake launcher; whole-suite executed-:test
regression FROM intellij-plugin/ (independently parse the XML).
DELIVERY: your final response text is the authoritative report
(output files are deleted by a cleanup). Read-only: no Gradle,
source/doc/state edits, git mutation or agents. Return <=12 lines:
VERDICT first; defects with exact file:line + required outcome;
confirmed counts/log evidence; accepted deviations; memory ids. Do
not review the full feature.
