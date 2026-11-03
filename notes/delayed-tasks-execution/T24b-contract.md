# T24b — Claude and Codex extra directories, acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T24 in
docs/plans/DelayedTasksImplementationPlan.md. No commit/push, auth
changes, gh, nested agents, unowned edits or other checkout. One serial
writer; AGENTS.md changes are somebody else's. ASCII-only test source.
Whole-suite regression baseline: **3110 tests / 0 failures** (plain
`test` FROM intellij-plugin/, ~3 min).

## Context
T24a COMPLETE and accepted (READ ITS PRODUCTION DELTAS FIRST):
- ClaudeAgentProvider.java +60/-1: requestsExtraDirectories (:498) +
  addDirFlags (:511, shell-quoted via AgentCommands.shellQuote) appended
  after planModeFlag in buildCreateCommand (:328) and the resume suffix
  (:362); explicit LaunchPlan.unsupported() refusals for remote AND eval
  on BOTH create (:288-300) and resume (:345-365) — eval justified by
  ClaudeEvalContainer.java:87-105 (container mounts only the session
  worktree/main repo/activity-hook/MCP config/~/.claude).
- CodexAgentProvider.java +33/-2: same helper in the common command
  construction (:138 create, :188 resume), global-option slot BEFORE the
  resume subcommand / -- / positional prompt; NO refusal branch — codex
  refuses remote outright already (:185) and evalMode keeps the CLI a
  LOCAL process, so requested dirs are SERVED in eval.
- Empty/absent extraDirectories -> byte-identical command on all paths.
- taskGuidance untouched (T26a), pi untouched, MCP/model flags/ordering
  untouched.
Its smoke (build/logs/t24a-smoke-OADIHBml/, 37 checks x3 green) was
never committed. THIS task ports that matrix to committed test deltas.

OWN ONLY TWO bounded deltas to EXISTING test files (per plan T24):
- intellij-plugin/src/test/java/app/drydock/agent/providers/claude/
  ClaudeAgentProviderTest.java
- intellij-plugin/src/test/java/app/drydock/agent/providers/codex/
  CodexAgentProviderTest.java
(Verify the actual test-file paths/names first; net-added budget <=300
per file / 120 cols; existing tests untouched except the additions.)
NO production edits. Matrix (from the T24a smoke, now committed):
Claude: quoted --add-dir per extra directory with spaces/quotes/dashes
in paths, first-occurrence order with duplicates collapsed, prompt
last, exact command strings, create AND resume, remote AND eval ->
LaunchPlan.unsupported() refusals, empty/absent -> byte-identical,
repeated resumes. Codex: same quoting/order/prompt-last/exact-string
coverage in create and resume, remote -> refusal (existing behavior —
assert it still holds with requested dirs), eval -> dirs SERVED (the
local-process rule), empty/absent byte-identity, dedup. Do NOT test
installed-CLI integration (out of unit scope; the plan records that
verification as integration work).

## Verification
Honest red-green: first run with a deliberate flaw in the TEST setup
documented, then fixed. Never fake a red by breaking production.
Then the WHOLE plugin suite as the LAST adapter invocation: plain
`test` FROM intellij-plugin/ (its own Gradle build — the repo-root
build is the :app module, NOT the plugin suite; do not repeat that
mistake) — cite an executed-:test log; ACTUAL XML counts (3110
baseline + new; state the exact new total). No jar scans. No
modal-dialog probes. No commits, pushes, nested agents or unowned
edits.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/main/java/app/drydock/agent/providers/claude/ClaudeAgentProvider.java intellij-plugin/src/main/java/app/drydock/agent/providers/codex/CodexAgentProvider.java intellij-plugin/src/test/java/app/drydock/agent/providers/claude/ClaudeAgentProviderTest.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that T26 or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the two test deltas (git diff per file) against
this block and the T24a accepted production semantics. Verify:
correct existing test files, net-added budget, zero production edits,
matrix coverage traced to production line numbers (no restated
behavior), exact command-string assertions (not weak contains-only
where exactness is claimed), refusal assertions for Claude
remote+eval on create AND resume, Codex eval-served + remote-refused,
byte-identity asserted, honest red-green. Independently parse the
cited XML on disk and confirm the executed-:test log (from
intellij-plugin/, not the repo-root :app build). DELIVERY: your final
response text is the authoritative report (output files are deleted
by a cleanup). Read-only: no Gradle, source/doc/state edits, git
mutation or agents. Return <=12 lines: VERDICT first; defects with
exact file:line + required outcome; confirmed counts/log evidence;
accepted deviations; memory ids. Do not review the full feature.
