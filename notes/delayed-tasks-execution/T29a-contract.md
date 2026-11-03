# T29a — Resources across launch, resume and provider transfer, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T29 in
docs/plans/DelayedTasksImplementationPlan.md, requirements D2/D17/P7.
No commit/push, auth changes, gh, nested agents, unowned edits or
other checkout. One serial writer; AGENTS.md changes are somebody
else's. ASCII-only source. Whole-suite regression baseline: **3153
tests / 0 failures** (plain `test` FROM intellij-plugin/, ~2.75 min).

## Context (accepted, read before writing)
- T23 LaunchResources (record: List<Path> extraDirectories,
  Optional<Path> taskGuidance; dedup first-occurrence) carried on
  CreateContext/ResumeContext; its javadoc states the consumer
  contract.
- T24a: ClaudeAgentProvider/CodexAgentProvider render extra dirs as
  quoted --add-dir; refusal-not-drop in non-visible contexts (Claude
  remote+eval refuse; Codex remote refused outright, eval serves).
- T26a: Claude --plugin-dir / pi --skill from the T25a
  TaskGuidance.claudePluginDirectory()/piSkillFile() seams; gate =
  taskGuidance present AND MCP credential present; guidance-absent
  omission, requested-unservable refusal.
- T28a: TaskLaunchBinding binds reservations + session links
  pre-spawn; LaunchHandle exposed.
- T42a: MCP task-tool gate source of truth (TASK_TOOLS intersection
  lives upstream — do not restate lists in providers).
- T12a: DrydockPluginService/DelayedTaskService service seams.

OWN ONLY these files:
- NEW intellij-plugin/src/platform/java/app/drydock/delayedtasks/
  TaskLaunchResources.java — the resolver/factory type that resolves
  receipt extra directories + installed guidance into the
  LaunchResources carried by contexts (<=250 lines).
- BOUNDED delta to intellij-plugin/src/platform/java/app/drydock/
  session/DrydockSessionService.java (VERIFY actual path) — resolve
  resources before context construction in startLaunch, resume, and
  transfer create.
Behavior requirements (plan T29):
- Resolve receipt extra directories and installed guidance BEFORE
  context construction; use it in startLaunch, resume, and transfer
  create (all three paths).
- Verify paths OFF EDT (directory existence checks are I/O — AGENTS.md
  forbids FS I/O on the FX thread; do them in the existing background
  launch path).
- Preserve ordinary sessions (no launchResources -> byte-identical
  behavior) and claim cleanup semantics.
- "Guidanced sessions are those actually receiving task-tool-capable
  MCP access, not merely any agent process" — the taskGuidance value
  is attached ONLY when the launch actually carries task-tool-capable
  MCP access (reuse the T26a/T29-upstream gate semantics; no tool
  list restated).
- Missing required context fails VISIBLY (a receipt whose extra
  directories cannot be resolved is a visible refusal, not a silent
  empty-resources launch).
- Do not pretend --add-dir is read-only: never attach a
  read-only/permission claim to the extra directories (LaunchResources
  javadoc: a fact about what the launch may read, not a permission).
- Retained context after prerequisite forget: this task resolves
  resources at launch/resume/transfer time; the retained-context
  semantics for already-created sessions belong to the accepted
  T26/T28 behavior — do not change it (T29b's matrix asserts the
  behavior, this task wires resolution).
Bounded reviewable deltas — additions + minimal seams. No committed
test file (T29b).

## Verification
Artifact-local smoke (randomized durable dir under
intellij-plugin/build/logs/, >=3 green runs, plain JVM, NO windows):
headless-constructible parts — resolver output for receipt-backed
extra dirs + installed guidance (present/absent/missing-required),
off-EDT resolution (assert resolution does not touch the EDT), missing
required context visible refusal, ordinary-session byte-identity,
transfer path carrying the same resolved resources. State honestly
that installed-CLI/provider rendering is T24a/T26a-accepted and
integration stays out of unit scope.
Then the WHOLE plugin suite as the LAST adapter invocation: plain
`test` FROM intellij-plugin/ (its OWN Gradle build; repo-root is
:app) — cite an executed-:test log; ACTUAL XML counts (3153 baseline,
no new tests). No jar scans. No commits, pushes, nested agents or
unowned edits.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/main/java/app/drydock/agent/api/LaunchResources.java intellij-plugin/src/main/java/app/drydock/tasks/TaskRuns.java intellij-plugin/src/main/java/app/drydock/tasks/TaskGuidance.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T29b or later tasks are
complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new file + bounded delta (git diff per file)
against this block, the T23/T24a/T26a/T28a accepted semantics. Verify:
resolve-before-context-construction on startLaunch/resume/transfer
(all three), off-EDT path verification, ordinary-session
byte-identity, taskGuidance attached only on task-tool-capable MCP
launches (no restated list), missing-required visible refusal,
no read-only pretense, bounded deltas + <=250 new file, ASCII-only,
durable honest smoke, whole-suite executed-:test regression FROM
intellij-plugin/ (independently parse the XML). DELIVERY: your final
response text is the authoritative report (output files are deleted
by a cleanup). Read-only: no Gradle, source/doc/state edits, git
mutation or agents. Return <=12 lines: VERDICT first; defects with
exact file:line + required outcome; confirmed counts/log evidence;
accepted deviations; memory ids. Do not review the full feature.
