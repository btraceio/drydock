# T31a — Single-project Start Session, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T31 in
docs/plans/DelayedTasksImplementationPlan.md, requirements D8/D16/P4.
No commit/push, auth changes, gh, nested agents, unowned edits or
other checkout. One serial writer; AGENTS.md changes are somebody
else's. ASCII-only source. Whole-suite regression baseline: **3163
tests / 0 failures** (plain `test` FROM intellij-plugin/, ~2.75 min).

## Context (accepted, read before writing)
- T08a/b: StartPlanner/StartPlan — the plan for ONE segment (task
  composition, graph-text refusals). The Start action COMPUTES the
  plan off EDT — never re-derive staging.
- T09a/b: TaskPrompt — three harness shapes (Claude blockedBy
  mirroring, Codex ordered STEPs, Pi parent-dispatch/join-layers/
  updates-itself); prompt(Kind,...) single entry; harness switching
  recomposes ONLY unedited generated text; user edits survive.
- T20a/b: DelayedTaskActions — the Tasks-node actions; the Start
  action ADDS here (plan T20 deferred it to T31). Human-only Drop,
  visible refusals, off-EDT progress discipline live there.
- T28a: TaskLaunchBinding + PreparedSessionCallback — pass the
  binding through the EXISTING launcher; do not duplicate the launch
  chain.
- T29a: TaskLaunchResources — resolve resources before context
  construction; reuse, do not duplicate.
- T19a: NewTaskPreset/NewTaskDialog (existing, tasks package) — the
  dialog T31 OPENS for one segment (verify what exists; the plan file
  list owns P/tasks/NewTaskPreset.java + NewTaskDialog.java deltas).
- T37a: StartPlanDialog/PlanLaunchChoices are the MULTI-segment
  confirmation flow — cross-project plans delegate to T38 (NOT a
  partial first-segment launch); single-segment is T31's scope.

OWN ONLY these files (verify actual paths first):
- NEW intellij-plugin/src/platform/java/app/drydock/delayedtasks/
  DelayedTaskStart.java — the start orchestration (<=250 lines).
- BOUNDED deltas to NewTaskPreset.java / NewTaskDialog.java (tasks
  package) — open the dialog for one segment with task title +
  composed instructions; harness switching recomposes only unedited
  generated text.
- BOUNDED delta to DelayedTaskActions.java — add the Start action
  (single-project scope; cross-project routes to T38's flow — for now
  a visible "cross-project plans are not startable yet" refusal per
  plan, NOT a partial launch).

Behavior requirements (plan T31):
- Start computes the plan OFF EDT (background), then opens the
  existing dialog for one segment with task title + composed
  instructions.
- Harness switching recomposes only UNEDITED generated text; user
  edits survive (TaskPrompt contract).
- Reservation REVALIDATED after confirmation (the task may have moved
  between open and OK — T07/T10a rules govern; visible refusal if
  stale).
- Pass binding (T28a) and resources (T29a) through the existing
  launcher — no new launch path.
- Duplicate click / start elsewhere: second Start while one is in
  flight is a visible no-op refusal (no double reservation).
- Cycle/refusal surfaces the StartPlanner graph-text refusal verbatim.
- Canceled dialog leaves tasks unstarted (zero writes — T37a capture
  discipline).
- Cross-project plans: visible delegation refusal (T38's scope).
- All writes off-EDT with immediate progress + visible errors.

Bounded reviewable deltas. No committed test file (T31b).

## Verification
Artifact-local smoke (randomized durable dir under
intellij-plugin/build/logs/, >=3 green runs, plain JVM, NO windows —
never show(); drive the Swing-free seams like T37a's Staging):
open-chain composition for one segment, agent-switch recomposes only
unedited text (user edit survives), revalidated stale reservation
refusal, duplicate-click no-op, cycle refusal text, canceled-dialog
zero-write (settle-then-decode), cross-project delegation refusal.
State honestly that the real dialog interaction is T31b's platform
fixture.
Then the WHOLE plugin suite as the LAST adapter invocation: plain
`test` FROM intellij-plugin/ (its OWN Gradle build; repo-root is
:app) — cite an executed-:test log; ACTUAL XML counts (3163 baseline,
no new tests). No jar scans. No commits, pushes, nested agents or
unowned edits. BUDGET DISCIPLINE: write files FIRST (an over-exploring
run died on T37a); consult accepted sources narrowly.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/main/java/app/drydock/tasks/TaskPrompt.java intellij-plugin/src/platform/java/app/drydock/delayedtasks/DelayedTaskActions.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T31b or later tasks are
complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new file + bounded deltas (git diff per file)
against this block and the T08a/T09a/T20a/T28a/T29a accepted
semantics. Verify: plan computed off EDT from StartPlanner shapes;
harness-switch recomposition preserves user edits (TaskPrompt
contract); reservation revalidated after confirmation; binding +
resources through the EXISTING launcher (no duplicated chain);
duplicate-click no-op; cycle/refusal verbatim; canceled zero-write;
cross-project delegation refusal (no partial launch); bounds
(<=250 new + bounded deltas); ASCII-only; durable honest smoke;
whole-suite executed-:test regression FROM intellij-plugin/
(independently parse the XML). DELIVERY: your final response text is
the authoritative report (output files are deleted by a cleanup).
Read-only: no Gradle, source/doc/state edits, git mutation or agents.
Return <=12 lines: VERDICT first; defects with exact file:line +
required outcome; confirmed counts/log evidence; accepted deviations;
memory ids. Do not review the full feature.
