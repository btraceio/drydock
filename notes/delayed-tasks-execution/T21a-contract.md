# T21a — [[id]] editor navigation, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T21a/T21b
in docs/plans/DelayedTasksImplementationPlan.md, requirement P5 and the
S1 spike finding find-s1-wikilink-gotodeclaration (GotoDeclarationHandler
resolves [[id]] in non-project .md — read it first). No commit/push,
auth changes, gh, nested agents, unowned edits or other checkout. One
serial writer; AGENTS.md changes are somebody else's. ASCII-only
source.

Accepted (do not edit): T06 TaskGraph (P2 resolution incl.
qualified-last-colon, ambiguous/broken diagnostics with concrete
texts), T04 TaskSnapshot, T12a DelayedTaskService (async snapshots,
root()), T18a picker (canonicalReference). Regression baseline:
**153 tests / 0 failures**. Read the EXISTING plugin registration
layout (X/META-INF/plugin.xml — find how extension points are
registered today) and any existing GotoDeclarationHandler in the tree
(the S1 spike may have left a scratch — check notes/ first).

OWN ONLY:
- NEW intellij-plugin/src/platform/java/app/drydock/delayedtasks/
  TaskGotoDeclarationHandler.java
- BOUNDED REGISTRATION DELTA to intellij-plugin/src/main/resources/
  META-INF/plugin.xml (or wherever the registration file actually
  lives — verify the path first): one gotoDeclarationHandler
  extension entry. Nothing else changes.
Target <=350 lines for the handler. No committed test file (T21b).

## Task block (approved plan T21a)
TaskGotoDeclarationHandler:
1. Resolves [[id]] and [[project:id]] references at the caret through
   the cached store graph (the service's snapshot; NEVER a synchronous
   store scan on the resolving path) to the task's FILE (the store
   .md path) and its location (offset of the title line if cheaply
   known from the snapshot; else the file alone).
2. RESTRICTED to task documents: the handler only offers navigation
   when the document at the caret is itself under the store root (a
   task document) — non-project/other Markdown must be untouched by
   THIS handler (the S1 finding covers non-project .md — follow its
   reasoning; if the S1 spike shows the handler receives non-project
   docs, keep the restriction to store-root documents per the plan).
3. Bounded caret-local match: the [[...]] pattern is matched only in
   a small window around the caret (e.g. the current line ± a bounded
   char window) — NO full-file scan, no disk load, no editor-text
   copy beyond the window.
4. Missing/ambiguous/broken ids: no navigation offered (return empty)
   or a refused action with the graph's concrete reason where the
   API allows; never a crash on malformed brackets.
5. No Markdown compile/runtime dependency — plain character scanning
   for the bracket pattern only.
6. Async discipline: navigation requests are served from the cached
   snapshot; if no snapshot is published yet, offer nothing (no
   blocking fetch).

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE): headless logic smoke (window matcher: caret inside/outside/
adjacent-to [[id]], [[project:id]], malformed brackets, nested brackets
in one line, long line stays bounded, line-boundary window behavior;
graph-side resolution: hit -> file path, missing id -> no result,
ambiguous slug -> no result with reason, broken ref -> no result) plus
an honest statement that live editor interaction is T21b's platform
fixture. >=3 consecutive green runs; record commands/log paths.
Then the approved adapter FULL regression as the LAST invocation:
test --tests 'app.drydock.tasks.*' --tests app.drydock.mcp.TaskToolsTest
--tests app.drydock.mcp.McpToolRouterSubsetTest --tests
app.drydock.mcp.McpToolRouterTaskTest --tests
app.drydock.intellij.DrydockPluginServiceTest --tests
app.drydock.delayedtasks.DelayedTaskServiceTest — cite an executed-
:test log; ACTUAL XML counts (153 baseline, no new tests). Tooling/
provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T21b or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new handler + the plugin.xml registration
delta against this block and the accepted TaskGraph/DelayedTaskService
production code plus the S1 finding. Verify: snapshot-only resolution
(no synchronous store scan), store-root document restriction, bounded
caret window (no full-file scan, no editor copy beyond the window),
missing/ambiguous/broken -> no navigation/no crash, no Markdown
dependency, empty-when-no-snapshot, bounded registration delta,
<=350 lines, ASCII-only, durable honest smoke (headless-stated),
executed-:test regression. Independently parse writer logs/XML.
DELIVERY: your final response text is the authoritative report
(output files are deleted by a cleanup). Read-only: no Gradle, source/
doc/state edits, git mutation or agents. Return <=12 lines: VERDICT
first; defects with exact file:line + required outcome; confirmed
counts/log evidence; accepted deviations; memory ids. Do not review
the full feature.
