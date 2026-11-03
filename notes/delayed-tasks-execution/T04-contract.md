# T04 — store scan and immutable index, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T04 in
docs/plans/DelayedTasksImplementationPlan.md, requirements D2/D4/P2. No
commit/push, auth changes, gh, nested agents, unowned edits or other checkout.
One serial writer; AGENTS.md changes are somebody else's. ASCII-only source
text (special data through Java escapes).

Accepted so far (do not re-review or edit): TaskId/TaskDocument/TaskStatus/
DoneBy (T01), TaskFileEnvelope + TaskFileReader with Parsed/Span (T02a-c),
TaskFilePatch patch/newFile/appendNote + committed TaskFilePatchTest (T03a-c,
46 task-group tests green). Consult their javadoc for exact APIs:
repository is a domain RepositoryId (shared-registry identity, D2); after
entries are reference TEXTS (bare or <project-slug>:<id>); a missing created
needs a filesystem creation-time fallback from THIS layer.

OWN ONLY these three new files (core, no platform imports — enforced by the
import grep/classpath gates):
- intellij-plugin/src/main/java/app/drydock/tasks/TaskSnapshot.java
- intellij-plugin/src/main/java/app/drydock/tasks/TaskFiles.java
- intellij-plugin/src/test/java/app/drydock/tasks/TaskFilesTest.java
Target <=300 changed lines total; split rather than trim coverage if it
cannot fit, and ask before widening ownership.

## Task block (approved plan)
- TaskSnapshot: ONE immutable result of scanning the store: the parsed
  TaskDocuments, per-file errors (a bad file never disappears — it is listed
  with its reason), and indexes by TaskId and by session reference. Include a
  way to ask about duplicate ids and which files collided. No volatile/mutable
  maps exposed; copies made at construction.
- TaskFiles: scanning one store root. Layout: <root>/<repository-slug>/<task-id>.md
  plus the reserved metadata dirs `plans/` and `runs/` (D2/P4; they are NOT
  repositories — do not scan their contents as tasks, and do not let a
  repository directory named `plans` or `runs` be mistaken for metadata:
  validate against the actual reserved names exactly). Discover repository
  directories, read each *.md through TaskFileReader (bounded), supply the
  filesystem creation-time fallback for a missing created (use
  Files.getLastModifiedTime or readAttributes' creationTime when available —
  verify the actual JDK25 API; file attributes, not parsing). Retain per-file
  failures as TaskFormatException + path. NO following of symlinks (verify the
  actual API choice that avoids or detects them — e.g. Files.newDirectoryStream
  + BasicFileAttributes.isSymbolicLink or a NOFOLLOW option; state what you
  verified). Paths must stay within the supplied root: a discovered entry that
  escapes the root (symlink, `..`-bearing name) is a per-file error, not a
  crash. Include ORPHAN repository directories (a repo dir with zero valid
  tasks is still a repository identity in the snapshot for collision purposes —
  its stem still claims ids? NO: an empty repo dir claims no ids but must not
  crash the scan; document the choice you make).
- Collision policy (D4: ids unique across all projects): two files with the
  same TaskId stem (same or different repos) = a per-file error on BOTH (or
  the clearly documented equivalent), never a silent winner; the snapshot must
  expose it. A directory whose stem is not a valid TaskId: per-file error for
  the repository, do not treat as a task.
- No graph work (T06), no mutations (T05), no watcher (T13) — read-only scan
  only. State the cost: O(files) + parsed bytes, indexes built once per scan.

## Tests (TaskFilesTest, JUnit 5, @TempDir)
Two repositories in one store root; a valid task in each; duplicate stem
across repos (both flagged); malformed sibling (visible error, other tasks
still load); empty/orphan repository dir (scan survives, documented policy);
symlinked file or symlinked dir escape (flagged, not followed, no
NoSuchFileException crash); creation-time fallback applied when created absent
(and absent when attribute unavailable — use a real file, don't fake); file
name that is not a valid TaskId; reserved dirs plans/ and runs/ skipped as
repositories (a task placed directly under root/ outside a repo dir is an
error, not a task). Real files, real attributes; no mocking. ASCII labels.

## Validation
All Gradle ONLY via approved adapter:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.tasks.TaskFilesTest (honest red->green)
2. test --tests 'app.drydock.tasks.*' — expect 46 existing + your new tests.
Record exact args, full randomized log paths, ACTUAL XML counts.
Tooling/provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>
No new investigation or graph edits.
Return <=6 lines via configured output: Changed; Tests (exact commands, actual
counts, full logs); Deviations; Difficulties; Memories. No claim that T05,
T06 or the full feature is complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the three new files against this block and current reader
APIs. Verify: immutable snapshot (no external mutation paths), per-file error
retention (bad file visible, scan survives), duplicate-id policy explicit and
consistent, symlink/escape handling verified against the actual API used (not
assumed), reserved dirs and repo-slug validation, creation fallback via real
attributes, no graph/mutation/watch scope creep, cost statement accurate,
platform-import gate respected, bounds. Independently parse writer logs/XML.
Read the test bodies: real files/attributes, no tautologies, genuine-failure
discipline. No Gradle, no source/doc/state edits, git mutation or agents.
Return <=10 lines: verdict; defects with exact file:line + required outcome;
confirmed counts/log evidence; accepted deviations; memory ids. Do not review
the full feature.
