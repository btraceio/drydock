# T26a — Per-invocation skill flags, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T26 in
docs/plans/DelayedTasksImplementationPlan.md, requirements D17/P6/P7.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source. Whole-suite regression baseline: **3110 tests / 0
failures** (plain `test`, ~2.75 min).

## Context (all prerequisites accepted)
- T25a: app/drydock/tasks/TaskGuidance.java (319 lines) materializes
  the versioned layout .claude-plugin/plugin.json +
  skills/drydock-tasks/SKILL.md under <state>/tasks-guidance/<version>/
  and exposes the seams T26 consumes: claudePluginDirectory() and
  piSkillFile() (TaskGuidance.java:72-77/:96). Per-invocation wiring is
  YOUR job; the materialized files are immutable — never mutate them.
- T24 (accepted, W02): Claude/Codex extra directories render as safely
  quoted --add-dir BEFORE the positional prompt on create and resume;
  no implicit remote/container host-path passthrough; unsupported
  context -> explicit refusal. Preserve no-extra-dir output and
  existing MCP/model flags. Study that delta as the positional-argument
  precedent.
- T42a: McpServer serves the TASK_INSTRUCTIONS handshake paragraph iff
  the served tool set intersects TASK_TOOLS — "task-tool-enabled" for
  this task means exactly that intersection (reuse/derive from the
  same source of truth, do not restate the list).
- find-harness-multi-dir-support: Claude/Codex have --add-dir; pi has
  NONE. Pi gets --skill (harness skill mechanism) instead.

OWN ONLY TWO bounded deltas to EXISTING files:
- intellij-plugin/src/main/java/app/drydock/agent/providers/claude/
  ClaudeAgentProvider.java — for task-tool-enabled creates AND
  resumes, add Claude's --plugin-dir <guidance.claudePluginDirectory()>
  (T25a seam). Position BEFORE any --/prompt and consistent with the
  T24 --add-dir ordering precedent.
- intellij-plugin/src/main/java/app/drydock/agent/providers/pi/
  PiAgentProvider.java — for task-tool-enabled creates AND resumes,
  add pi's --skill <guidance.piSkillFile()> in the COMMON command
  construction so the initial-prompt injection path also works (the
  plan's explicit clause: the flag must survive pi's initial-prompt
  injection path, not only the bare-spawn path).

In BOTH providers:
- Flags positioned before any -- end-of-options marker / positional
  prompt; never after (they would be eaten as prompt text).
- GATE (coordinator-granted decision, 2026-11-02): task-tool-enabled =
  launchResources().taskGuidance() present AND the launch carries MCP
  access (credentialFile present). The TASK_TOOLS intersection itself
  stays upstream (T29 attaches taskGuidance only to launches actually
  receiving task-tool-capable MCP access) — NO tool list restated in
  providers. MCP absent -> no flag, byte-identical command.
- Guidance ABSENT is NOT a refusal (LaunchResources.java:26-29: empty
  means nothing was requested): omit the flag, launch proceeds
  byte-identically. Only a REQUESTED resource that cannot be served is
  refused. Launches WITHOUT task tools are byte-identical to today —
  no flag, no guidance argument, no wording change.
- Codex remains handshake-only: do NOT touch CodexAgentProvider.
- No remote/eval host path leakage: the guidance directory/skill path
  is a LOCAL state-root path; a launch in a context where the CLI
  cannot see host paths (Claude remote/eval per T24a's verified rule;
  pi remote) must REFUSE via the provider's established unsupported
  surface when guidance IS requested — never pass it through any
  remote/container mapping, never silently drop (T24a precedent:
  Claude remote+eval refuse, Codex eval serves because the CLI stays
  a local process; pi eval is a local process so --skill is included,
  pi remote is already unsupported).
- Guidance absent/unavailable (T25a not materialized / service not
  ready): launch proceeds WITHOUT the flag — the flag is additive,
  never a launch blocker. No silent path changes otherwise.
- Existing repeated-resume behavior preserved (resume paths get the
  flag on every resume).

Bounded reviewable deltas — additions + minimal seams, no unrelated
rewrites, existing callers/output preserved. No committed test file
(T26b owns both provider fixtures).

## Verification
Artifact-local smoke (randomized durable dir under
intellij-plugin/build/logs/, >=3 green runs, plain JVM, NO windows):
headless-constructible parts — build the Create/Resume contexts the
providers consume (same seams T24's tests used) and assert the
RESULTING COMMAND LINE: flag present + position for task-tool-enabled
create/resume on BOTH providers; byte-identical command when no task
tools; flag omitted when guidance unavailable; --skill survives the
pi initial-prompt injection path (construct that exact path too).
State honestly that installed-CLI integration behavior is out of
scope for a unit smoke (T24 documented the same boundary).
Then the WHOLE plugin suite as the LAST adapter invocation: plain
`test` (no filter) — cite an executed-:test log; ACTUAL XML counts
baseline + new tests). No jar scans. No modal-dialog probes.
No commits, pushes, nested agents or unowned edits.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/main/java/app/drydock/agent/providers/claude/ClaudeAgentProvider.java intellij-plugin/src/main/java/app/drydock/agent/providers/pi/PiAgentProvider.java intellij-plugin/src/main/java/app/drydock/tasks/TaskGuidance.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T26b or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the two bounded deltas (git diff per file) against
this block, the T25a seams (TaskGuidance.java), the T42a task-tool
gate (McpServer.java), and the T24 ordering precedent. Verify: flag
position before --/prompt in BOTH providers; pi --skill in COMMON
construction covering the initial-prompt injection path; task-tool
gate derived from the same TASK_TOOLS truth (no restated list);
no-guidance launches byte-identical; guidance-absent launches proceed
without the flag; Codex untouched; no remote/eval host-path leakage;
resume paths flag every resume; bounded deltas; ASCII-only; durable
honest smoke; whole-suite executed-:test regression. Independently
parse writer logs/XML. DELIVERY: your final response text is the
authoritative report (output files are deleted by a cleanup).
Read-only: no Gradle, source/doc/state edits, git mutation or agents.
Return <=12 lines: VERDICT first; defects with exact file:line +
required outcome; confirmed counts/log evidence; accepted deviations;
memory ids. Do not review the full feature.
