# T37a — Multi-stage confirmation dialog, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T37 in
docs/plans/DelayedTasksImplementationPlan.md, requirements D8/D16/P4.5.
No commit/push, auth changes, gh, nested agents, unowned edits or
other checkout. One serial writer; AGENTS.md changes are somebody
else's. ASCII-only source. Whole-suite regression baseline: **3158
tests / 0 failures** (plain `test` FROM intellij-plugin/, ~2.75 min).

## Context (accepted, read before writing)
- T08a/b: StartPlanner produces the staged plan (segments, per-stage
  composition, graph-text refusals); StartPlan.java is the immutable
  DTO. The dialog DISPLAYS this plan — never re-derive staging.
- T32a/b: PlanLaunch/RunningPlan — origin-window descriptor, per-stage
  StageState, cancellation generation. The dialog COLLECTS launch
  choices; T32a's immutable shapes govern what a choice looks like.
- T18a/b: NewDelayedTaskDialog + NewSession dialog conventions
  (per-segment agent/model/tiers/mode/branch/directory/window Configure
  reuses the EXISTING New Session dialog — no duplicate field editor;
  Configure returns a Choice only, NEVER starts a session).
- T30a: SessionDoneDialog/SessionTaskCompletion dialog conventions
  (committing before side effects, per-unit failures, cancel =
  capture-only zero writes) — the same discipline applies here.

OWN ONLY these files:
- NEW intellij-plugin/src/platform/java/app/drydock/delayedtasks/
  StartPlanDialog.java (<=350 lines).
- NEW intellij-plugin/src/platform/java/app/drydock/delayedtasks/
  PlanLaunchChoices.java (the immutable choices result — <=150 lines).
Behavior requirements (plan T37):
- Display stages/task edges and per-task agent-may-close checkboxes.
- Collect per-segment agent/model/tiers/mode/branch/directory/window
  via the existing New Session dialog Configure actions (returns
  Choice only, never starts a session; no duplicate field editor).
- The dialog explains the FULL plan including target auto-start.
- Human default: the default path requires informed human
  confirmation (D8/D16/P4.5 — nothing starts until the human
  confirms).
- Launch choices IMMUTABLE after close (PlanLaunchChoices with
  defensive copies / List.copyOf — T18a Result precedent).
- Cancel leaves files AND launches untouched: zero writes, zero
  reservations, zero session starts (capture-only).
- Checkbox changes are STAGED in the dialog state and written only on
  OK — never on Cancel.
- No I/O on the EDT beyond the sanctioned dialog seams; every launch
  write goes through the accepted service seams (T28a binding/T29a
  resources — do not duplicate).

Bounded by the two new files only — NO deltas to existing files. No
committed test file (T37b).

## Verification
Artifact-local smoke (randomized durable dir under
intellij-plugin/build/logs/, >=3 green runs, plain JVM, NO windows —
never show(); drive constructed-dialog seams like T18b/T20b):
all-segments-configured shape, human default present, choices
immutable-after-close proof (mutating the returned collections fails
/ copies), canceled configuration zero-write (settle-then-decode tree
comparison), checkbox staging not written on Cancel, Configure
returns-Choice-only assertion. State honestly that real keystroke
dispatch + on-screen behavior is T37b's platform fixture.
Then the WHOLE plugin suite as the LAST adapter invocation: plain
`test` FROM intellij-plugin/ (its OWN Gradle build; repo-root is
:app) — cite an executed-:test log; ACTUAL XML counts (3158 baseline,
no new tests). No jar scans. No commits, pushes, nested agents or
unowned edits.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/main/java/app/drydock/tasks/StartPlan.java intellij-plugin/src/main/java/app/drydock/tasks/PlanLaunch.java intellij-plugin/src/platform/java/app/drydock/delayedtasks/NewDelayedTaskDialog.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T37b or later tasks are
complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the two new files against this block, the
T08a/T32a/T18a/T30a accepted semantics. Verify: plan displayed from
StartPlanner shapes (no re-derivation), Configure reuses the New
Session dialog and returns Choice only (never starts), human default
+ informed confirmation, choices immutable after close (defensive
copies), cancel zero-write, checkbox staging, no EDT I/O, existing
launch seams not duplicated, <=350/<=150 bounds, ASCII-only, durable
honest smoke, whole-suite executed-:test regression FROM
intellij-plugin/ (independently parse the XML). DELIVERY: your final
response text is the authoritative report (output files are deleted
by a cleanup). Read-only: no Gradle, source/doc/state edits, git
mutation or agents. Return <=12 lines: VERDICT first; defects with
exact file:line + required outcome; confirmed counts/log evidence;
accepted deviations; memory ids. Do not review the full feature.
