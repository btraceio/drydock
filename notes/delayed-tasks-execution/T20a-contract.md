# T20a — Tree task actions and session terminology, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T20 in
docs/plans/DelayedTasksImplementationPlan.md, requirements D9-D11 +
P7-P9. No commit/push, auth changes, gh, nested agents, unowned edits
or other checkout. One serial writer; AGENTS.md changes are somebody
else's. ASCII-only source. Whole-suite regression baseline: **3143
tests / 0 failures** (plain `test` FROM intellij-plugin/, ~2.75 min).

## Context (accepted, read before writing)
- T17a/T17b: TaskTreePanel.java renders task children below session
  rows through the existing build path; selection keyed on TaskId;
  task changes feed the existing coalescer. YOUR actions plug into
  THIS panel (bounded delta).
- T07a/b: TaskState.validateUpdate — actor-aware transitions
  (permitted XOR refusalReason), human-only Drop, agent done->REVIEW/
  DONE by done_by, Confirm/Reopen semantics. Actions MUST go through
  the same rules via the service/store seam — no local re-derivation.
- T18a/b: NewDelayedTaskDialog (platform, app/drydock/intellij/
  delayedtasks/) is the New Task creation form — reuse it, never
  duplicate the editor.
- T21a: TaskGotoDeclarationHandler resolves [[id]] via
  watchedSnapshot() — the same snapshot accessor pattern applies for
  Go-to-blocker-session resolution.
- T15a/b: DelayedTaskRows/TaskNode/DelayedTaskTreeModel own placement
  (D9-D11); blocker texts come from TaskGraph diagnostics.
- Existing action scheme: plugin.xml at
  intellij-plugin/src/platform/resources/META-INF/plugin.xml:161+
  (Drydock.NewTask :165 -> app/drydock/intellij/tasks/NewTaskAction.java,
  42 lines). Session-creating actions/dialog-facing labels rename to
  "New Session"; action IDs, class/package names and shortcuts are a
  WIRE CONTRACT — never rename them.

OWN these files ONLY:
- NEW intellij-plugin/src/platform/java/app/drydock/intellij/
  delayedtasks/DelayedTaskActions.java (the task-node action group:
  Edit, Drop, Confirm, Reopen, Go to blocker session) — <=350 lines.
- BOUNDED delta to intellij-plugin/src/platform/java/app/drydock/
  intellij/tasks/TaskTreePanel.java (wire the actions into the tree:
  context menu + double-click Edit on task nodes only; existing
  session-row behavior untouched).
- BOUNDED delta to intellij-plugin/src/platform/java/app/drydock/
  intellij/tasks/NewTaskAction.java AND plugin.xml: label/wording
  changes ONLY for the New Session rename (no id/class/package/
  shortcut changes); register the new task actions in plugin.xml.
- NO Start action (T31's job). NO new dialog construction beyond
  reusing NewDelayedTaskDialog.

Behavior requirements:
- Every write (Drop/Confirm/Reopen via TaskStore) has IMMEDIATE
  progress indication and visible error surfacing; off-EDT per
  AGENTS.md; failures never strand a spinner.
- Drop is HUMAN-only (T07 rule) — the action is disabled/refused for
  agent-authored attempts with the T07 refusal reason.
- Confirm/Reopen follow TaskState exactly (review gate: agent
  done->REVIEW waits for the human's Confirm).
- Go to blocker session: resolve the blocker via the watched snapshot
  (T21a pattern); unresolvable/ambiguous/foreign -> visible no-op
  refusal message, never a crash or silent nothing.
- Double-click on a task node opens Edit (NewDelayedTaskDialog in
  edit mode if supported, else the plan's intended behavior — verify
  what the dialog supports and state the boundary honestly).
- Canceled dialog -> zero writes.
- The Tasks node offers New Task (NewDelayedTaskDialog) + context
  actions; existing session rows' menus untouched.

## Verification
Artifact-local smoke (randomized durable dir under
intellij-plugin/build/logs/, >=3 green runs, plain JVM, NO windows —
never show() a dialog; drive action enablement/selection seams on
constructed components like T16b/T17b did): enablement per selection
type (task node vs session row vs section), refusal reasons for
agent-authored Drop, canceled-dialog zero-write (tree comparison),
plugin.xml registration well-formed (parse the XML). State honestly
that real dialog interaction + EDT action dispatch is T20b's platform
fixture.
Then the WHOLE plugin suite as the LAST adapter invocation: plain
`test` FROM intellij-plugin/ (its OWN Gradle build; repo-root build is
:app) — cite an executed-:test log; ACTUAL XML counts (3143 baseline,
no new tests). No jar scans. No commits, pushes, nested agents or
unowned edits.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/platform/java/app/drydock/intellij/tasks/TaskTreePanel.java intellij-plugin/src/platform/java/app/drydock/intellij/tasks/NewTaskAction.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T20b or later tasks are
complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new file + bounded deltas (git diff per file)
against this block, the T07/T18a/T21a accepted semantics, and the
existing action scheme. Verify: wire contract preserved (action ids/
class/package/shortcuts unchanged; only wording renamed), actions
route through TaskState rules (no local re-derivation), human-only
Drop with refusal reason, immediate progress + visible errors +
off-EDT, canceled-dialog zero-write, Go-to-blocker snapshot
resolution with visible refusals, no Start action, no duplicated
dialog editor, plugin.xml registration well-formed, existing session
menus untouched, <=350 for the new file + bounded deltas, ASCII-only,
durable honest smoke, whole-suite executed-:test regression FROM
intellij-plugin/ (independently parse the XML). DELIVERY: your final
response text is the authoritative report (output files are deleted
by a cleanup). Read-only: no Gradle, source/doc/state edits, git
mutation or agents. Return <=12 lines: VERDICT first; defects with
exact file:line + required outcome; confirmed counts/log evidence;
accepted deviations; memory ids. Do not review the full feature.
