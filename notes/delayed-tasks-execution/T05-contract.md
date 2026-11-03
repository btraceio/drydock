# T05 — serialized task mutations, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md (esp. "One writer for
persistent state" and SharedProjectsFile's OS-lock pattern), plan T05 in
docs/plans/DelayedTasksImplementationPlan.md, requirements D2/D6/P1/P7. No
commit/push, auth changes, gh, nested agents, unowned edits or other checkout.
One serial writer; AGENTS.md changes are somebody else's. ASCII-only source.

Accepted so far (do not re-review or edit): T01 model, T02a-c reader/envelope
(Parsed/Span, TaskFormatException), T03a-c patch (patch/Updates/newFile/
appendNote), T04a-b store scan (TaskFiles.scan, TaskSnapshot with documents/
repositories/byId/bySessionReference/failures/idCollisions, FileFailure,
IdCollision). 56 task-group tests green. Read their javadoc for exact APIs.

OWN ONLY these three new files:
- intellij-plugin/src/main/java/app/drydock/tasks/TaskWriterLock.java
- intellij-plugin/src/main/java/app/drydock/tasks/TaskStore.java
- intellij-plugin/src/test/java/app/drydock/tasks/TaskStoreTest.java
Target <=300 changed lines total; if the full test matrix cannot fit, STOP and
propose a T05a/T05b split (T02/T03/T04 precedent) BEFORE writing oversized
code — never trim coverage silently.

## Task block (approved plan)
TaskStore is the single writer for the per-user task store (D2):
- ONE root-owned write queue: all mutations (create, status/known-field
  update, note append) serialize through it; callers hand in transform
  functions, never load-then-save themselves (the ApplicationStateStore/
  SharedProjectsFile single-writer rule; two independent load/save cycles are
  a documented data-loss bug).
- TaskWriterLock: bounded JVM-internal lock (single-thread executor or
  ReentrantLock with timeout -> visible failure, never an unbounded hang),
  PLUS a bounded OS file lock on a sibling `<root>/.lock` (or equivalent)
  following SharedProjectsFile's pattern: JVM-wide lock first, then
  FileChannel tryLock with a bounded wait; a foreign live lock surfaces a
  distinct visible failure ("locked by another Drydock") — never a silent
  empty result, never an overlapping-lock crash inside one JVM. Verify the
  actual JDK25 API against SharedProjectsFile.java's implementation.
- Fresh read + patch + atomic replace: under the lock, re-read the target
  file (never patch a cached parse), apply TaskFilePatch.patch/newFile/
  appendNote, write via temp file + ATOMIC_MOVE (same directory), fsync the
  file before the move. An external edit between read and write is naturally
  serialized by the lock; a source that changed outside the lock's fresh read
  window cannot occur because the fresh read IS under the lock — document
  this invariant.
- Create with collision-safe reservation/retry: new TaskId stem from title
  (D4 slug + short random suffix, TaskId validation), check the id is free
  ACROSS ALL repositories in the store (TaskFiles.scan under the same lock),
  retry with a fresh suffix on collision (bounded attempts); the created file
  uses TaskFilePatch.newFile canonical rendering. Do not trust a pre-scan
  outside the lock.
- Mutations publish only committed snapshots: after a successful write,
  re-scan (or incrementally verify) and hand the committed TaskSnapshot to a
  registered listener; a failed save publishes nothing. Listener registration
  returns an unsubscribe runnable; listeners never called on a failed write.
- Expose flush() (drain pending queue; bounded wait) and close() (stop
  accepting work, drain, release locks; idempotent, exception-isolated).
- Injectable conflict guard: a small interface (e.g. UnsavedContentGuard) the
  PLATFORM layer will later implement (FileDocumentManager check). Core
  default: no conflicts. Before writing a file whose content would clobber
  an unsaved in-editor version, the guard is consulted; a conflict surfaces a
  visible checked failure. Keep the seam minimal — no platform imports.
- No watcher (T13), no graph (T06), no MCP (T10+), no UI. Cost statement:
  each mutation is O(file) read + O(changed fields) patch + one atomic move
  + one scan on publish; the scan is O(store) — state it honestly (the
  incremental-verify alternative may be listed as a future optimization, not
  implemented).

## Tests (TaskStoreTest, JUnit 5, @TempDir; multi-process test is T43, NOT here)
Concurrent creates from many threads: all succeed, distinct ids, all files
valid; status+note mutation round-trip preserves unknown metadata (use
TaskFileReader to verify); external edit (write the file directly on disk,
then mutate) — the mutation applies to the FRESH content, not a stale cached
parse; failed replacement (make the target directory read-only or the
atomic move fail — verify what is achievable portably on macOS/APFS;
unwritable parent dir works) -> visible failure, original file untouched, NO
snapshot published; lock timeout -> visible distinct failure; flush drains;
close is idempotent and stops accepting work; conflict guard injection: a
stub guard reporting a conflict blocks the write with the checked failure
(the stub is a test-local implementation of the seam, not a production mock
framework). Real files, no mocking framework. Honest red-green.

## Validation
All Gradle ONLY via approved adapter:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.tasks.TaskStoreTest
2. test --tests 'app.drydock.tasks.*' — cite a log where :test EXECUTED
(evidence rule); record ACTUAL XML counts (expect 56 + your new tests).
Tooling/provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>
No new investigation or graph edits.
Return <=6 lines via configured output: Changed; Tests (exact commands,
actual counts, full logs); Deviations; Difficulties; Memories. No claim that
T06/T10 or the full feature is complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the three new files against this block and the accepted
reader/patch/scan production code. Verify: single-writer invariant (no
load-then-save outside the queue), lock ordering and bounded waits, atomic
replace with fsync, collision-safe create across all repositories, publish-
only-committed, flush/close lifecycle, minimal conflict-guard seam with no
platform imports, honest cost statement, no watcher/graph/MCP scope creep,
bounds. Independently parse writer logs/XML; verify the evidence rule. Read
test bodies: real files, no tautologies, genuine-failure discipline.
No Gradle, source/doc/state edits, git mutation or agents. Return <=10 lines:
verdict; defects with exact file:line + required outcome; confirmed counts/
log evidence; accepted deviations; memory ids. Do not review the full feature.
