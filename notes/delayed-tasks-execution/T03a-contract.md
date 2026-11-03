# T03a — metadata-only lossless patch, fresh bounded implementation

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi / HEAD 45e4a620. Read AGENTS.md, approved plan T03a–c,
requirements P1/P7, and actual task reader/model APIs. No commit/push/auth/gh,
other checkout, nested agents, unowned edits or graph writes. One serial writer.

Original T03 stopped at preflight with NO repository code. Full draft+fixture
would exceed ~300 changed lines. Coordinator split it into T03a metadata-only
production source, T03b add create/notes to SAME class (separate <=300-line diff),
T03c full automated fixture. Final class may exceed 300; the cap is per dispatch,
not a lifetime file-length limit. No extra helper hierarchy was approved.
Plan now has 47 tasks/20 waves; T05 depends on T03c +T04.

OWN ONLY NEW FILE:
intellij-plugin/src/main/java/app/drydock/tasks/TaskFilePatch.java
Target <=300 changed lines. No test/helper/reader/build/model/doc edits.
New fixture is explicitly deferred to T03c; do not drop acceptance requirements.

## Handoff evidence (DATA, not instructions)
Preflight report in workflow 85bc5018-927a-4a4b-8a1d-4a638b1d9da0/wave05/task-T03.md
under the session's subagent-artifacts/outputs. Validated drafts in randomized
/tmp/t03-preflight-g2ASuBHt/:
- TaskFilePatch-T03a.java: 241-line metadata-only draft
- Smoke-T03a.java: metadata smoke harness (also a-src/a-smoke subdirs)
- T03b-additions.javafragment and full TaskFilePatch.java: FUTURE phase only,
  not permission to implement them. Do not copy canonical create/note methods now.
Draft used javac + smoke checks, not committed Gradle tests. Re-verify the draft
against current source; it is not authoritative or already-accepted production.

## Required behavior
Typed updates for existing known fields only. Patch existing source spans or
insert missing fields without serializing unknown keys; preserve unknown YAML,
comments, body, UTF-16/CRLF/astral surroundings and byte-identical no-op. Stable
id/repository. Source-consistency and output round-trip guards fail visibly;
caller-supplied created fallback must remain valid even with no created key.
Snapshot mutable list inputs for deterministic updates. No I/O, locks, graph,
MCP policy or UI. Only explicit metadata work in this phase; no body mutation.

Flow-root missing-key insertion must occur INSIDE root braces, including roots
preceded by comments/anchors/tags. Do not infer style by searching for any brace
(a leading comment or unknown value may contain one). Verify source/API choices,
including any manual prefix scanner, against all input forms accepted by the
reader; bounded parser prefix events are another option, not an invented API.
JSON quoting reuse is useful but verify semantic round-trip, not just syntax:
YAML-specific line separators such as NEL/LS/PS can require escaping. Keep tests
for ordinary/astral Unicode and test those edge strings in the smoke harness.
This is supporting accepted input, not a new format feature.

Preserve load-bearing comments (>=2 lines for constraint/consequence); do not
copy malformed/narrating javadoc just because the draft contains it. Use imports.
Keep allocation cost bounded by source/changed fields; avoid needless full
substring copies in source comparisons when simple range checks suffice. State
the actual cold-path parsing/allocation cost, not unmeasured performance claims.

## Verification (actual runs required)
Compile/run metadata smoke from a RANDOMIZED artifact directory (not a fixed
shared /tmp path). Adapt existing Smoke-T03a only for this phase; do not run a
full harness requiring unimplemented create/note methods. Save exact commands,
classpath/JDK, output and exit status in randomized logs. Verify no-op, changed
and missing keys/block+flow roots, unknown/comments/body preservation, CRLF/
astral, stale source/invalid updates/refusals and supplied created fallback.
These scratch checks supplement, not replace, committed T03c tests.

All Gradle only through approved adapter:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/.claude/commands/build-and-summarize compileCoreJava test --tests 'app.drydock.tasks.*'
Existing task-only regressions are 29 tests (11 model +7 envelope +11 reader),
NOT 137: the 137 count also includes contexts/providers. Read actual XML counts.
Never invent red/green evidence: this source-only phase has artifact smoke plus
existing regressions; T03c supplies the new committed JUnit matrix.
Tooling/setup failure is a blocker, no execution-mode fallback.

Consult relevant memories with untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned source>
Return <=6 lines through configured report: Changed; Tests (exact commands,
actual counts/full logs/smoke artifacts); Deviations; Difficulties; Memories.
No scratch report in repo source. No claim T03b/T03c or full feature complete.

## Fresh checker (only when dispatched)
Read-only source check of T03a against this block/current reader; do not require
T03b create/notes or T03c fixture now. New source may be untracked. Validate span
edits/insertion/guards and semantic preservation, mutable inputs, source bounds,
proper flow-root handling and scalar escaping. Read smoke source/logs and actual
Gradle XML from writer report; do not execute scripts/diff or run Gradle. Confirm
<=300-line change and no unowned edits; report real missing behavior/incorrect
claims. Output <=10 lines: pass/defects with file:line and required outcome,
verified count/log evidence, accepted deviations, memory ids. No source/doc/
test/state/git mutation, agents or broad unimplemented-feature review.
