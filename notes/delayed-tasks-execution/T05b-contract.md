# T05b — task store mutations, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T05b in
docs/plans/DelayedTasksImplementationPlan.md, requirements D6/P1/P7. No
commit/push, auth changes, gh, nested agents, unowned edits or other checkout.
One serial writer; AGENTS.md changes are somebody else's. ASCII-only source.

T05a is COMPLETE and passed fresh check b8d00b1a (workflow c7be8cac):
TaskWriterLock.java (123) + TaskStore.java (250) with the create path —
write queue, per-root JVM ReentrantLock + sibling OS lock file, fresh
in-lock TaskFiles.scan, collision-safe create with bounded suffix retry,
temp+fsync+ATOMIC_MOVE, publish-only-committed listeners with unsubscribe,
flush/close, UnsavedContentGuard seam with NO_CONFLICTS default, honest
cost javadoc, package-private suffixSource seam. Do not re-review T05a; do
not change its create path except as the noted fix below.

OWN ONLY this one file:
intellij-plugin/src/main/java/app/drydock/tasks/TaskStore.java
Per-dispatch delta target <=300 changed lines; the class may exceed 300
(repo new-file budget ~1000; it is currently 250). TaskStoreTest is T05c —
do NOT write any test file.

## Task block (approved plan T05b)
Add the two mutation operations on the same single-writer path as create:
1. update(TaskId, Updates): under the SAME queue + both locks, fresh-read
   the task file (never a cached parse), locate the file across the store's
   repositories (TaskFiles.scan under the lock gives byId — a missing id is
   a visible checked failure), parse via TaskFileReader, apply
   TaskFilePatch.patch with the caller's Updates, atomic replace, publish
   committed snapshot to listeners exactly as create does.
2. appendNote(TaskId, String note): same path, TaskFilePatch.appendNote,
   visible refusal for a blank note, publish committed.
3. Carry the T05a checker's non-blocking note as a REQUIRED fix in this
   delta: TaskWriterLock's closed-check happens before queue acquisition, so
   a mutation that passed the check can still run after close()'s drain —
   re-check closed AFTER acquiring the queue in the write path (you own
   TaskStore.java; if the re-check must live in TaskWriterLock.java, that is
   a ONE-line change in an unowned file — STOP and report it as a proposed
   minimal cross-file fix instead of editing it silently; the coordinator
   will decide).
4. Consult the UnsavedContentGuard before every write like create does;
   conflict = checked TaskStoreException, no file touched, nothing published.
5. Keep the honest cost javadoc accurate for mutations (two O(store) scans:
   fresh read + publish). No new scope: no graph, watcher, MCP, UI.

## Verification
Artifact-local smoke (randomized dir), 4+ scenarios, run repeatedly (>=3
consecutive green runs):
- status+note round-trip: create a task with unknown metadata (extra key
  with nested value, comment) via direct file write, then update status +
  append a note through the store; re-read with TaskFileReader: new
  status/note present, unknown metadata/comments byte-identical.
- external-edit freshness: after creating, rewrite the file ON DISK with
  different title/extra content (outside the store), then update status —
  the update must apply to the FRESH content (patch's source guard rejects
  nothing; the new title/extra survive), proving no cached parse was used.
- failed write no publish (read-only dir on update), guard conflict on
  update, close-after-mutation refuses, flush drains behind a slow mutation.
- keep evidence durable: copy the smoke harness + log into randomized files
  under intellij-plugin/build/logs/ (the T05a smoke dir was deleted; do not
  repeat that — the log must survive for the checker).
All Gradle ONLY via approved adapter:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/.claude/commands/build-and-summarize
test --tests 'app.drydock.tasks.*' — cite a log where :test executed (the
adapter has no literal "EXECUTED" string; evidence = non-UP-TO-DATE task
line + fresh XML). Record ACTUAL XML counts (56 baseline).
Tooling/provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact commands,
actual counts, full logs incl. smoke log path); Deviations; Difficulties;
Memories. No claim that T05c/T06 or the full feature is complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the TaskStore.java delta against this block and the
accepted T05a source (frozen copy provided in dispatch). Verify: mutations
ride the same queue/locks as create, fresh read under lock (no cached
parse), missing-id visible failure, atomic replace + publish-only-committed
for both operations, guard consulted, the closed-window fix applied (or
correctly escalated as a cross-file proposal), honest cost javadoc, delta
<=300, no unowned edits. Independently parse writer logs/XML and confirm
the smoke evidence exists. No Gradle, source/doc/state edits, git mutation
or agents. Return <=10 lines: verdict; defects with exact file:line +
required outcome; confirmed counts/log evidence; accepted deviations;
memory ids. Do not review the full feature.
