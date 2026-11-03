# T24a — Claude and Codex extra directories, source, fresh context (SAME LIVE CODER)

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T24 in
docs/plans/DelayedTasksImplementationPlan.md. No commit/push, auth
changes, gh, nested agents, unowned edits or other checkout. One serial
writer; AGENTS.md changes are somebody else's. ASCII-only source.
Whole-suite regression baseline: **3110 tests / 0 failures** (plain
`test`, ~2.75 min).

## Context
T23 (accepted) added agent/api/LaunchResources.java (record:
List<Path> extraDirectories, Optional<Path> taskGuidance; dedup via
LinkedHashSet; CreateContext.launchResources()/ResumeContext
.launchResources() carry it) whose javadoc (LaunchResources.java:18-31)
states the consumer contract: providers must render each directory
through their own surface (--add-dir for Claude and Codex), refuse a
launch when they cannot serve a requested resource rather than
silently dropping it, and duplicates collapse to the first occurrence.
VERIFIED THIS SESSION: NO provider renders extraDirectories yet —
ClaudeAgentProvider.java and CodexAgentProvider.java are unmodified at
HEAD; --add-dir appears nowhere in src/. T24 was a missed W02 task.
You implement the SOURCE half; T24b owns the committed tests.

OWN ONLY bounded deltas to EXISTING files:
- intellij-plugin/src/main/java/app/drydock/agent/providers/claude/
  ClaudeAgentProvider.java
- intellij-plugin/src/main/java/app/drydock/agent/providers/codex/
  CodexAgentProvider.java
For BOTH providers, on create AND resume, when the context carries
launchResources().extraDirectories():
- Render each extra directory as a SAFELY QUOTED --add-dir argument,
  positioned BEFORE the positional prompt (prompt stays last),
  preserving first-occurrence order from LaunchResources (it already
  dedups).
- NO implicit remote/container host-path passthrough: a launch in a
  context where the CLI runs remotely/in a container cannot see host
  state paths — request the refusal, do not silently drop.
- Unsupported context (cannot serve requested extra directories):
  explicit refusal via the provider's existing unsupported surface
  (LaunchPlan.unsupported() or the established equivalent — reuse
  what the provider already returns for a context it cannot serve,
  e.g. remote).
- Local eval/container contexts: apply the same visibility rule as
  remote — if the CLI's process cannot see the host paths, refuse;
  do not guess (state what the existing eval path does in the report).
- Pi is NOT in this task (the plan scopes T24 to Claude+Codex; pi's
  prompt-path rendering is later work — leave pi untouched).
- Preserve byte-identical no-extra-dir output: contexts with empty
  extraDirectories produce EXACTLY today's command lines. Existing
  MCP/model flags and their ordering preserved.
- taskGuidance is NOT rendered in this task (T26a's job) — do not
  touch it.
Bounded reviewable deltas — additions + minimal seams, no unrelated
rewrites. No committed test file (T24b).

## Verification
Artifact-local smoke (randomized durable dir under
intellij-plugin/build/logs/, >=3 green runs, plain JVM, NO windows):
headless-constructible parts — build Create/Resume contexts with
extraDirectories (incl. multi-dir, duplicate-collapse, spaces/quotes/
dashes in paths) and assert the RESULTING COMMAND LINES: quoted
--add-dir set before the prompt, prompt last, no-extra-dir
byte-identity, unsupported-context refusal, local-vs-remote/eval
distinction per the implemented rule. State honestly that
installed-CLI integration behavior is out of unit scope.
Then the WHOLE plugin suite as the LAST adapter invocation: plain
`test` (no filter) — cite an executed-:test log; ACTUAL XML counts
(3110 baseline, no new tests). No jar scans. No modal-dialog probes.
No commits, pushes, nested agents or unowned edits.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/main/java/app/drydock/agent/providers/claude/ClaudeAgentProvider.java intellij-plugin/src/main/java/app/drydock/agent/providers/codex/CodexAgentProvider.java intellij-plugin/src/main/java/app/drydock/agent/api/LaunchResources.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T24b/T26 or later tasks are
complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the two bounded deltas (git diff per file) against
this block, LaunchResources.java's consumer contract, and the existing
provider launch surfaces. Verify: quoted --add-dir before the prompt on
create+resume in BOTH providers; first-occurrence order preserved;
refusal (not silent drop) in non-visible contexts, reusing the
providers' established unsupported surface; no-extra-dir byte-identity;
MCP/model flags untouched; pi untouched; taskGuidance untouched;
bounded deltas; ASCII-only; durable honest smoke; whole-suite
executed-:test regression. Independently parse writer logs/XML.
DELIVERY: your final response text is the authoritative report (output
files are deleted by a cleanup). Read-only: no Gradle, source/doc/
state edits, git mutation or agents. Return <=12 lines: VERDICT first;
defects with exact file:line + required outcome; confirmed counts/log
evidence; accepted deviations; memory ids. Do not review the full
feature.
