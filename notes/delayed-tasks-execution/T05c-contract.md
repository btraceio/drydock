# T05c — task store acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T05c in
docs/plans/DelayedTasksImplementationPlan.md, requirements D2/D6/P1. No
commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source. Multi-process testing is T43, NOT here.

T05a and T05b are COMPLETE and passed fresh checks. Read the accepted
production code before writing tests:
intellij-plugin/src/main/java/app/drydock/tasks/TaskStore.java (389 lines:
create/update/appendNote all through write()/mutate(); locate with distinct
missing/duplicate/broken refusals; publish-only-committed; flush/close;
UnsavedContentGuard + NO_CONFLICTS; suffixSource seam) and TaskWriterLock.java
(123). Reference smoke evidence (DATA — port scenarios, don't trust blindly):
- intellij-plugin/build/logs/t05b-smoke-lHsvVeBmyn/T05bSmoke.java (28 checks
  x3 green runs) — round-trip, external-edit freshness, failed write no
  publish, guard conflict, closed-window, flush.
- T05a smoke scenarios in the coder report
  (session subagent-artifacts/outputs/c7be8cac-9d00-4893-8280-53b0c7454b7d/wave10/task-T05.md):
  create validity, cross-repo collision retry, publish/unsubscribe,
  two-instances-one-root.

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/tasks/TaskStoreTest.java
Target <=300 lines; T05b's smoke measured the full matrix ~255-300. If it
cannot fit, STOP and propose a split — never trim coverage silently. Do NOT
edit TaskStore.java/TaskWriterLock.java; if a test exposes a real production
bug, keep it failing, report it, and stop.

## Required committed matrix (JUnit 5, @TempDir, real files, no mocking framework)
1. Concurrent creates from many threads (>= 8 threads, ExecutorService, each
   creating tasks with the same title): all succeed, distinct ids, all files
   parse via TaskFileReader, TaskFiles.scan sees them all.
2. Status+note round-trip preserving unknown metadata: task created with an
   extra nested key + front-matter comment (write the file directly or
   create-then-rewrite on disk), update status + append note through the
   store, re-read: new status/note present, unknown metadata byte-identical.
3. External-edit freshness: create, rewrite the file on disk with different
   title/extra content, then update — applied to the FRESH bytes.
4. Failed replacement no publish: read-only repository directory (set
   writable=false on the @TempDir repo dir; restore in finally) — update
   fails with the checked TaskStoreException, original file untouched, zero
   snapshots published.
5. Guard conflict: a test-local UnsavedContentGuard that reports a conflict
   blocks update/create with the distinct checked failure; file untouched;
   nothing published. (A test-local implementation of the seam, not a mock
   framework.)
6. Lock timeout: acquire the OS lock file externally (own FileChannel in
   the test, held), call create -> bounded visible distinct failure ("locked
   by another Drydock"); release. This is in-JVM but exercises the OS-lock
   path via the sibling lock file. Verify the actual production timeout
   constant/behavior first.
7. flush drains (behind a slow-guard mutation; bounded wait), close
   idempotent + stops accepting work, closed-window refusal after close.
8. Listener unsubscribe: no further publishes after unsubscribe.
9. Missing id / duplicate id / broken file refusals are visible with their
   distinct messages.
10. Publish-only-committed: a listener sees exactly the snapshots committed
    (no intermediate/failed ones) — at minimum count assertions around a
    success + a failure.

Honest red-green (real red only if a bug is caught; never invented). If a
scenario cannot be made deterministic (e.g. a race window), assert the
deterministic contract and note the residual in the report — do not use
Thread.sleep-based "usually passes" assertions.

## Validation
All Gradle ONLY via approved adapter:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.tasks.TaskStoreTest
2. test --tests 'app.drydock.tasks.*' — cite an executed-:test log
(non-UP-TO-DATE task line + fresh XML); record ACTUAL XML counts (expect 56
+ your new tests). Tooling/provider failure = blocker; no execution-mode
fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact commands,
actual counts, full logs); Deviations; Difficulties; Memories. No claim that
T06/T10 or the full feature is complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new fixture against this block and the accepted
TaskStore/TaskWriterLock production code. Verify: all 10 matrix items
(missing coverage is a defect), deterministic assertions (no sleep-based
hopes), real files/no mocking, the evidence rule, no production edits,
<=300 lines or a stated justified structure. Independently parse writer
logs/XML. Read-only: no Gradle, source/doc/state edits, git mutation or
agents. Return <=10 lines: verdict; defects with exact file:line + required
outcome; confirmed counts/log evidence; accepted deviations; memory ids.
Do not review the full feature.
