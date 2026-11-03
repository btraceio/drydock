# T21b — navigation acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T21b in
docs/plans/DelayedTasksImplementationPlan.md, requirement P5 and the S1
finding find-s1-wikilink-gotodeclaration. No commit/push, auth changes,
gh, nested agents, unowned edits or other checkout. One serial writer;
AGENTS.md changes are somebody else's. ASCII-only source.

T21a is COMPLETE (fresh checker e6199dde pass):
TaskGotoDeclarationHandler.java 292 — snapshot-only resolution via
watchedSnapshot() (null -> no navigation), isTaskDocument restricted
to the store root minus plans//runs/, ±512-char caret window with no
copy, missing/ambiguous/broken -> refusal logged + empty targets,
catch(Throwable)-level no-crash; plugin.xml registration +10. Read the
handler for the exact API (targets()/getGotoDeclarationTargets or the
SDK's GotoDeclarationHandler surface) before writing tests. Do NOT
edit any production file; if a test exposes a REAL production bug,
keep it failing, report it, and stop.

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/intellij/delayedtasks/
TaskGotoDeclarationPlatformTest.java
Target <=300 lines. Platform fixture test (BasePlatformTestCase per
the repo's conventions); VfsRootAccess allowlist test-only.

## Required committed matrix (the live-editor part T21a deferred)
1. Non-project md: an .md file outside the store root — the handler
   offers nothing for it (store-root restriction; the S1 finding's
   non-project doc path stays untouched by this handler).
2. Cursor inside/outside link: a task document with a [[id]] ref —
   cursor inside the brackets -> targets resolve to the task file;
   cursor outside (adjacent line/text) -> no targets.
3. Missing/ambiguous id: [[id]] refs that resolve to nothing
   (missing) or an ambiguous slug -> no targets, no crash.
4. Unrelated Markdown untouched: a non-task .md under the store root
   is out of the handler's document scope ONLY if that's what
   isTaskDocument does (verify against the production code — the root
   restriction is directory-based; write the test to the ACTUAL
   behavior and say which files it covers).
5. [[project:id]] qualified form: resolves to the right task file.
6. No-snapshot: when the service has no published snapshot, the
   handler offers nothing (no blocking fetch).

Platform idioms: the repo's pump-park await where async is involved;
editor/caret fixture setup per the repo's existing editor platform
tests (grep for Editor fixtures in platform tests); no sleep-only
synchronization. If real caret/editor fixtures prove impossible
headless for some item, document honestly which seam the assertion
used instead (the handler's public matcher/resolution seams) — but
try the live path first.

Honest red-green (real red only if a bug is caught; never invented).

## Validation
All Gradle ONLY via approved adapter:
notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.intellij.delayedtasks.TaskGotoDeclarationPlatformTest
2. WHOLE plugin suite as the LAST invocation: plain `test` (no
filter) — cite an executed-:test log; ACTUAL XML counts (whole-suite
total; expect ~3070 + your new tests; report the exact number).
Tooling/provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of TaskGotoDeclarationPlatformTest.java against this
block and the accepted handler production code. Verify all 6 matrix
items (missing coverage is a defect), assertions against ACTUAL
production behavior (root restriction verified, not assumed), pump
discipline, test-only allowlist, no production edits, <=300 lines,
evidence rule (whole-suite LAST invocation, XML on disk).
Independently parse writer logs/XML. DELIVERY: your final response
text is the authoritative report (output files are deleted by a
cleanup). Read-only: no Gradle, source/doc/state edits, git mutation
or agents. Return <=12 lines: VERDICT first; defects with exact
file:line + required outcome; confirmed counts/log evidence; accepted
deviations; memory ids. Do not review the full feature.
