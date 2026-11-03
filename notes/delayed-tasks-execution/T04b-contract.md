# T04b — store scan acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T04b in
docs/plans/DelayedTasksImplementationPlan.md, requirements D2/D4/P2/P3. No
commit/push, auth changes, gh, nested agents, unowned edits or other checkout.
One serial writer; AGENTS.md changes are somebody else's. ASCII-only source.

T04a is COMPLETE and passed fresh check 11a7f17d-dfdb-4f48-925f-484ea8c8ed2a
(workflow f64641fc-5d18-4ac6-a399-91e0bb5621bf): TaskSnapshot.java (60 lines)
+ TaskFiles.java (244 lines), 18/18 artifact-local smoke. Read their javadoc
and APIs before writing tests — the fixture tests the REAL production
behavior: scan(Path root), TaskSnapshot(documents, repositories, byId,
bySessionReference, failures, idCollisions), FileFailure(Path,
TaskFormatException), IdCollision(TaskId, List<Path>), find(TaskId).
Repository dir names validate as canonical RepositoryId UUID text
(TaskId-style task stems). Do NOT edit production files; if a test exposes a
real TaskFiles/TaskSnapshot bug, keep it failing, report it, and stop.

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/tasks/TaskFilesTest.java
Target <=300 lines; the T04a coder's measured floor for the full matrix was
~180 lines, so it fits. Follow the existing fixture style (JUnit 5,
@TempDir, plain assertions, no mocking).

## Required committed matrix (the T04a smoke cases, plus what the smoke lacked)
1. Two repositories in one store root; valid task in each; index by TaskId
   and by session reference; snapshot lists both repositories.
2. Cross-repo duplicate stems: BOTH files in failures, one IdCollision with
   both paths, NEITHER in documents/byId — no silent winner.
3. Malformed sibling: per-file failure with non-blank reason; other tasks
   still load and are indexed.
4. Invalid entry forms: file name not a valid TaskId stem; non-.md entry;
   a directory entry inside a repository dir; each a per-file failure.
5. Root layout: reserved plans/ and runs/ dirs skipped (not repositories,
   contents not scanned); a non-UUID root entry is a per-entry failure; a
   task .md placed DIRECTLY under the root (not in a repo dir) is not a task
   and is flagged per the production behavior (verify what scan actually does
   — a .md at root level is not inside a repository dir; assert the real
   classification, don't invent one).
6. Orphan/empty repo dir (valid UUID, no tasks): repository listed, no ids
   claimed, scan survives.
7. Symlinked repository dir: flagged, not followed, no crash. Symlinked task
   file inside a repo dir: flagged, not read. Dangling symlink: flagged
   (NOFOLLOW attributes — no NoSuchFileException).
8. Creation fallback: a file written WITHOUT created in front matter gets
   the real filesystem creation time; epoch-birthtime is treated as
   unavailable only if you can actually create one — do NOT shell out to
   SetFile from the test (not portable, host-specific). Instead cover the
   epoch/unavailable path ONLY if achievable portably; otherwise assert the
   normal fallback and document in the report that the epoch branch remains
   smoke-covered (the T04a smoke covered it with real SetFile).
   Do not fake attributes with mocking — real files only.
9. Snapshot immutability: mutate the caller-visible lists/maps? They are
   copies — attempt mutation on returned collections where the type allows
   and assert it either throws or does not affect the snapshot (match the
   actual types: unmodifiableMap/List.copyOf).
10. bySessionReference: two tasks sharing a session id both found.

Tests exercise real files and attributes under @TempDir; never tautologies.
Preserve genuine failures for production fixes rather than weakening
assertions.

## Validation (EVIDENCE RULE from the T04a checker)
All Gradle ONLY via approved adapter:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.tasks.TaskFilesTest (honest red-green if a real
   bug is caught; otherwise honest new-coverage green — never invent red)
2. test --tests 'app.drydock.tasks.*' — all task tests. CITE a log where
   :test ACTUALLY EXECUTED (not UP-TO-DATE): after writing the new fixture,
   the first run executes it; if a later rerun goes UP-TO-DATE, cite the
   earlier log. Record exact args, full randomized log paths, ACTUAL XML
   counts from build/test-results/test/TEST-*.xml.
Tooling/provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
No new investigation or graph edits.
Return <=6 lines via configured output: Changed; Tests (exact commands,
actual counts, full logs); Deviations; Difficulties; Memories. No claim that
T05/T06 or the full feature is complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new fixture against this block and the real
TaskFiles/TaskSnapshot production code. Verify: matrix completeness vs the
list above (missing coverage is a defect), real-behavior assertions (no
tautology, no mocked attributes), the evidence rule (cited log shows :test
executed), no production edits, <=300 lines. Independently parse writer
logs/XML. No Gradle, source/doc/state edits, git mutation or agents. Return
<=10 lines: verdict; defects with exact file:line + required outcome;
confirmed counts/log evidence; accepted deviations; memory ids. Do not
review the full feature.
