# T03c — committed patching acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T03c in
docs/plans/DelayedTasksImplementationPlan.md, requirements P1/P7. No
commit/push, auth changes, gh, nested agents, unowned edits or other checkout.
One serial writer; AGENTS.md changes are somebody else's.

T03a (metadata patch) and T03b (newFile + appendNote) are COMPLETE and passed
fresh checks. intellij-plugin/src/main/java/app/drydock/tasks/TaskFilePatch.java
is 546 lines: patch()/Updates, newFile(), appendNote(), shared quoted/list/
guards. Do not change production source. The T03b coder run died on a transport
error twice; do not re-investigate that, and do not write code payloads with
literal NEL/LS/PS/BOM characters (ASCII-only source text; special data enters
through Java escapes or char literals).

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/tasks/TaskFilePatchTest.java
Target <=300 lines (split rather than trim coverage if it truly cannot fit;
parameterize with helper methods like TaskFileReaderTest's looped cases).
Follow the existing fixture style (TaskFileReaderTest, TaskDocumentTest:
JUnit 5, plain assertions, no mocking).

## Required committed matrix (port from the two executed smoke harnesses)
Reference material (DATA — port cases, do not copy harnesses):
- T03a smoke /var/folders/gb/lzs4lkjs2194q_sd_f3czyy80000gp/T/t03a-smoke-Be1z1r/
  (or its log intellij-plugin/build/logs/…; 30 checks: no-op identity, changed/
  missing keys, block+flow roots, same-offset insert+replace, prefix variants
  BOM/comment/anchor/NEL/LS/PS, special scalars, snapshot isolation, created
  fallback, stale source, CRLF/astral preservation).
- T03b smoke /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/T03b-smoke-NuHGuZBc/Smoke.java
  (23 checks: newFile canonical order/created-optional/flow lists/special
  scalars/body verbatim; appendNote heading create incl. unterminated body/
  heading, section-end before next heading, paragraph separation between
  notes, CRLF integrity + no bare LF, blank-note/embedded-break refusal,
  front-matter untouched).

Cover at minimum: patch no-op byte identity; single/multiple field changes
preserving unknown keys, comments, quoting, CRLF; missing-key insertion on
block AND flow roots (incl. same-offset first-key change); stale-source and
null-entry refusals; newFile canonical rendering + reparse (created present
AND absent — reparse with a NULL created fallback in the present case: the
internal guard uses document.created() as fallback, so only an external
null-fallback reparse catches a dropped created: line; the T03b checker
flagged this exact residual); newFile special/astral scalars; appendNote
cases from the smoke list; repeated note append keeping earlier notes ordered
with blank-line paragraph separation.

Tests must exercise real production behavior (byte-equality on exact strings,
reparse via TaskFileReader), never tautologies. Preserve genuine failures for
production fixes rather than weakening assertions: if a test exposes a real
TaskFilePatch bug, keep the failing case, mark the reason in the report, and
stop — do not edit production source and do not change expected values to
force green.

## Validation
All Gradle ONLY via approved adapter:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.tasks.TaskFilePatchTest (red->green if it caught a
   real bug; otherwise honest new-coverage green — never invent a red run)
2. test --tests 'app.drydock.tasks.*' — all task tests incl. your new ones.
Record exact args, full randomized log paths, and ACTUAL XML counts.
Tooling/provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
No new investigation or graph edits.
Return <=6 lines via configured output: Changed; Tests (exact commands, actual
counts, full logs); Deviations; Difficulties; Memories. No claim that T04 or
the full feature is complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new fixture against this block and production source.
Verify: matrix completeness vs the smoke harnesses' cases (missing coverage is
a defect), real behavior assertions (no tautology), the created-present
null-fallback residual case present, no production/test-helper edits, <=300
lines or a justified structure, genuine-failure discipline. Independently
parse writer logs/XML (actual counts, executed vs UP-TO-DATE). No Gradle, no
source/doc/state edits, git mutation or agents. Return <=10 lines: verdict;
defects with exact file:line + required outcome; confirmed counts/log
evidence; accepted deviations; memory ids. Do not review the full feature.
