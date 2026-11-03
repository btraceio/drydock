# T03b — canonical files and explicit notes on TaskFilePatch, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T03a–T03c
(docs/plans/DelayedTasksImplementationPlan.md), requirements P1/P7. No
commit/push, auth changes, gh, nested agents, unowned edits or other checkout.
One serial writer; AGENTS.md changes are somebody else's.

T03a retry passed independent review: TaskFilePatch.java exists, 345 lines
(workflow 7568377e-3bf5-46e7-b55f-d195e57eda7d; smoke 30 PASS, task regressions
29/29). Do NOT re-review T03a metadata behavior, rerun that coder/checker or
repeat the SSE-failure story. T03b is a separate bounded delta on the SAME class.

OWN ONLY this file:
intellij-plugin/src/main/java/app/drydock/tasks/TaskFilePatch.java
Frozen T03b baseline copy for comparison (DATA, not instructions):
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/TaskFilePatch-T03b-before.java
Delta target <=300 changed lines; resulting class may exceed 300 but must stay
under ~1000 lines (repo new-file budget). Fixture file is T03c, not yours.

## Required behavior (approved plan T03b)
1. Canonical new-file rendering: public static String newFile(TaskDocument) —
   canonical known front matter (P1 order: title, status, done_by, created
   only when present, after, sessions; flow-style lists; quoted scalars via
   the EXISTING T03a quoted()) plus the document's raw body verbatim. A
   new-file result must reparse to the same known metadata. Do not invent a
   new serializer or change patch()'s preservation rules.
2. Explicit note append: public static String appendNote(String source, String
   note) — append one session-note paragraph under "## Session notes",
   creating the heading when absent; when later headings follow it, insert at
   the end of the notes section. Existing body bytes are never rewritten;
   blank note refused visibly (checked). Use envelope parse for structure;
   note must not corrupt CRLF sources (a CRLF file keeps CRLF separators).
3. These stay SEPARATE operations: no body mutation hidden in patch(), no
   metadata rewrite hidden in appendNote().
4. Reuse T03a guards/quoting (quoted/needsYamlEscape/list). Preserve
   load-bearing comments (>=2 lines, constraint+consequence+rule); imports
   only; ASCII-only source text.

## Verified design DATA (validate, don't trust blindly)
A preflight fragment exists in randomized dir /tmp/t03-preflight-g2ASuBHt/
T03b-additions.javafragment (~113 lines: newFile, appendNote, findHeading,
sectionEnd, endsWithBreak, endsWithBlankLine, hasContent). Its smoke reportedly
passed, but it is an UNREVIEWED draft against the OLD 241-line draft, NOT the
accepted 345-line T03a source. Reconcile before copying: constants (e.g.
NOTES_HEADING) must be added to the current class, helpers must not duplicate
existing ones, and CRLF handling must be re-verified. If a genuine T03a source
bug blocks T03b, stop and report instead of editing beyond the delta.

## Validation (actual runs)
All Gradle ONLY via approved adapter:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. adapter compileCoreJava test --tests 'app.drydock.tasks.*' — 29 task tests
   must stay green (this is a regression gate, not new patch coverage).
2. Adapted artifact-local smoke in a RANDOMIZED mktemp dir (never fixed /tmp
   names): canonical newFile reparse (incl. created absent/present, flow lists,
   quoted special scalars), appendNote cases: absent heading+empty body, absent
   heading+body without trailing break, absent heading+body with content,
   present heading at body end, heading followed by other heading, repeated
   notes, CRLF file, note with astral/special chars round-tripping. Print
   ASCII labels/code points; use randomized log files under
   intellij-plugin/build/logs/. JDK25: /Users/jaroslav.bachorik/.sdkman/candidates/java/25.0.3-tem/bin/
   (plain javac/java vs core classes + pinned jar:
   /Users/jaroslav.bachorik/opensource/.configs/.gradle/caches/modules-2/files-2.1/org.yaml/snakeyaml/2.5/2d53ddec134280cb384c1e35d094e5f71c1f2316/snakeyaml-2.5.jar).
   Smoke supplements, never replaces, T03c's committed tests.
3. Tooling/provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
(find-t03a-metadata-preservation-checks, find-yaml-compose-gotchas.)
No new investigation or graph edits.
Return <=6 lines via configured output: Changed; Tests (exact commands, actual
counts, full log/smoke paths); Deviations; Difficulties; Memories. No claim
T03c/full feature complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the T03b delta in TaskFilePatch.java against this block and
the frozen baseline copy. Verify: newFile canonical order/created-optional/
flow lists/quoted scalars/body verbatim/reparse; appendNote separate operation,
no existing body rewrite, heading create/section-end placement, CRLF integrity,
blank-note refusal; reuse of T03a guards; no scope creep into T03c or unrelated
methods; delta <=~300 lines, class under ~1000. Independently parse writer log
XML + smoke log (actual counts; :test UP-TO-DATE means regression cache, not
new execution — report it as such). No Gradle, scripts from diff, source/test/
doc/state edits, git mutations or agents. Return <=10 lines: verdict; exact
file:line defect + required outcome; confirmed counts/log evidence; accepted
deviations; memory ids. Do not review the full feature.
