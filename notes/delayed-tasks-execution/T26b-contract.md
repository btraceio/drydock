# T26b — Per-invocation skill flag acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T26 in
docs/plans/DelayedTasksImplementationPlan.md. No commit/push, auth
changes, gh, nested agents, unowned edits or other checkout. One serial
writer; AGENTS.md changes are somebody else's. ASCII-only NEW test
source. Whole-suite regression baseline: **3127 tests / 0 failures**
(plain `test` FROM intellij-plugin/, ~2.75 min).

## Context (accepted production semantics — read the deltas first)
T26a COMPLETE: ClaudeAgentProvider.java --plugin-dir after --add-dir,
before any --/positional prompt, in buildCreateCommand (:329-330) and
the resume suffix (:363-365); PiAgentProvider.java --skill via
appendSkillFlag inside the 6-arg common piCommand (:506) so the
initial-prompt injection path also works. GATE: launchResources()
.taskGuidance() present AND MCP credentialFile present; the TASK_TOOLS
intersection stays upstream (no list in providers). Guidance absent /
MCP absent -> byte-identical command (omission, never a refusal).
Requested-but-unservable -> explicit refusal via the provider's
established unsupported surface: Claude remote AND eval refuse; pi
remote refuses (pre-existing), pi eval INCLUDES --skill (local
process). Codex carries only T24a's addDirFlags — untouched by T26a.
Every resume path gets the flag. pi byte-identity is modulo the
per-launch random PI_SESSION_ID.
T24b's committed provider tests (ClaudeAgentProviderTest,
CodexAgentProviderTest) are the harness precedent — same idioms, exact
command-string assertEquals, nasty quoting.

OWN ONLY TWO NEW test files (per plan T26):
- intellij-plugin/src/test/java/app/drydock/agent/providers/claude/
  ClaudeAgentProviderTaskGuidanceTest.java
- intellij-plugin/src/test/java/app/drydock/agent/providers/pi/
  PiAgentProviderTaskGuidanceTest.java
(Verify actual test roots; <=300 lines each / 120 cols; zero
production edits.) Matrix (plan T26): paths with spaces (the guidance
directory/skill path quoted correctly on both providers), prompt
injection AND fallback (pi's initial-prompt injection path carries
--skill; the fallback/bare-spawn path too), repeated resumes (flag on
every resume, no inheritance surprises), MCP absent (byte-identical,
no flag), no remote/eval host path leakage (Claude remote+eval ->
refusal when guidance requested; pi remote -> refusal/pre-existing,
pi eval -> --skill included; assert the guidance path NEVER appears in
any remote/container command form). Also cover: no-task-tool /
guidance-absent byte-identity (omission NOT refusal), flag ordering
(Claude: after --add-dir, before --/prompt), Codex untouched-by-T26a
is NOT re-tested here (T24b owns Codex) — state that boundary.

## Verification
Honest red-green: first run with a deliberate flaw in the TEST setup
documented, then fixed. Never fake a red by breaking production.
Then the WHOLE plugin suite as the LAST adapter invocation: plain
`test` FROM intellij-plugin/ (its OWN Gradle build; repo-root build is
:app) — cite an executed-:test log; ACTUAL XML counts (3127 baseline +
new; state the exact new total). No jar scans. No modal-dialog probes.
No commits, pushes, nested agents or unowned edits.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/main/java/app/drydock/agent/providers/claude/ClaudeAgentProvider.java intellij-plugin/src/main/java/app/drydock/agent/providers/pi/PiAgentProvider.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the two new test files against this block and the
T26a accepted production semantics. Verify: placement/bounds/zero
production edits, full matrix traced to production line numbers (no
restated behavior), exact command-string assertions, leakage
assertions (guidance path never in remote/container forms), pi
injection-path coverage, honest red-green. Independently parse the
cited XML on disk and confirm the executed-:test log (plugin build,
not :app). DELIVERY: your final response text is the authoritative
report (output files are deleted by a cleanup). Read-only: no Gradle,
source/doc/state edits, git mutation or agents. Return <=12 lines:
VERDICT first; defects with exact file:line + required outcome;
confirmed counts/log evidence; accepted deviations; memory ids. Do
not review the full feature.
