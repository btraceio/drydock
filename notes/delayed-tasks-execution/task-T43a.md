# T43a — Adversarial multi-process fixtures: report

Cwd: pi-worktree-a53ea523-d485-4141-bde2-5db31211bb75-s0-0 (sneaky-fluffy-corgi @ 94e36efc).
Owned files only, zero production edits, no commits.

## Changed
- `intellij-plugin/src/test/java/app/drydock/tasks/TaskStoreProcessFixture.java` (NEW, 215 lines <= 250):
  the subprocess harness + child-worker entry in one class. Parent side:
  `locate`/`spawn` (explicit classpath via @argfile, argument list only,
  never a shell, merged stdout/stderr redirected to a log), `Child`
  (`printed` bounded marker poll, `complete` bounded run-to-completion with
  destroyForcibly on expiry, `kill`). Child side `main` worker modes:
  writer / lock-holder / task-op / claim-holder / claim-attempt / plan-driver.
  ProcessRunner semantics restated (arg list, no shell, bounded waits,
  destroyForcibly on expiry); ProcessRunner itself is run-to-completion by
  construction and cannot express the intentional holders (the LSP-process
  exception class), same precedent as SharedProjectsFileTest.
- `intellij-plugin/src/test/java/app/drydock/tasks/TaskStoreProcessTest.java` (NEW, 160 lines <= 300):
  the four committed cross-process cases, each on a real temp root
  (`Files.createDirectories(dir/"tasks")`, no home store, no network):
  1. two JVMs (`writer`) create + status-update + note-append concurrently
     on one root: 16 OK lines, no ERR; `TaskFiles.scan` -> 16 documents,
     0 failures (no torn document), 0 id collisions, all STARTED, all notes
     present (no lost update).
  2. T05c-deferred foreign-holder path: a child holds the OS lock file
     directly (`lock-holder` -> HELD), the other process (`task-op`, 800 ms
     bound) fails with the bounded failure containing "is locked by another
     Drydock", NOT OverlappingFileLock (asserted), nothing written, 0
     documents in the untouched root.
  3. single driver: `claim-holder` takes `plans/alpha-target.claim` ->
     CLAIMED; `claim-attempt` in a second JVM is refused with "already
     driven by another Drydock process ... one driver per plan"; no
     CREATED-CLAIM line.
  4. crash/restart per T36a: `plan-driver` reserves the TaskRuns PREPARE
     receipt, saves the RUNNING plan (rev=1 paused=false), is then
     destroyForcibly killed mid-flight; the next process (the test JVM)
     re-acquires the claim, and the plan loads at rev=2 paused=true,
     status RUNNING (durable pause, no auto-resume), while a second
     `reserve` is refused "already reserved" -- no duplicate launch.

## Honest red-green (deliberate TEST-setup flaw, production untouched)
- RED (documented flaw): the first fixture version launched the child JVM
  WITHOUT the test classpath. All 4 tests failed; every child log showed
  `Error: Could not find or load main class
  app.drydock.tasks.TaskStoreProcessFixture` (ClassNotFoundException).
  Focused run: `cd intellij-plugin && ./gradlew test --tests
  'app.drydock.tasks.TaskStoreProcessTest' --console=plain` -> 4 completed,
  4 failed (XML: build/test-results/test/TEST-app.drydock.tasks.TaskStoreProcessTest.xml).
- GREEN fix: classpath travels in an @argfile (`-cp "<java.class.path>"`,
  backslash-escaped -- SharedProjectsFileTest's verified lesson). Same
  focused run -> BUILD SUCCESSFUL; XML: tests=4 failures=0 errors=0
  skipped=0, 3.6 s.
- Two further honest test-setup fixes during green-up (no production
  edits): (a) message-supplier lambdas cannot throw IOException -> eager
  evaluation; (b) the temp root is created up front: TaskStore's first
  mutation scans the root before any write creates it (NoSuchFileException
  from TaskFiles.list otherwise) -- the harness's documented "builds a
  temp task root" job.

## Whole-suite regression (LAST adapter invocation, one bash call)
- Command exactly: `cd intellij-plugin && ./gradlew test --console=plain`
  (executed `> Task :test`, plugin build; zero `:app:`/`:buildSrc:` task
  lines in the log).
- Full plain log: /tmp/t43a-whole-suite.log. BUILD SUCCESSFUL in 3m 47s.
- ACTUAL XML counts (parsed all build/test-results/test/TEST-*.xml):
  **tests=3201 failures=0 errors=0 skipped=0** = the 3197 baseline + the 4
  new TaskStoreProcessTest cases (no other suite changed; T43b's
  end-to-end IDE suite is NOT claimed here).

## Deviations
- None from the task block. Scope note: the raw-ProcessBuilder harness
  (not the production ProcessRunner class) follows the contract's own
  wording ("ProcessRunner semantics"); ProcessRunner is run-to-completion
  and cannot host the intentional holders/kill lever.

## Difficulties
- The suite is a platform test: `java.class.path` + @argfile + the JBR
  `java` binary (ProcessHandle.current) is the only portable way to give
  children the core/test classes -- validated by the SharedProjectsFileTest
  precedent and by the red round.

## Memories consulted
- kb brief via investigation.py --paths TaskWriterLock/TaskStore/PlanStore:
  `find-writerlock-overlap-escape` (TaskWriterLock's write() can leak an
  unchecked OverlappingFileLockException -- same-JVM only; this task's
  case 2 is exactly the cross-process complement, asserted NOT
  OverlappingFileLock), `find-delayed-tasks-decisions` (D1-D17/N1
  requirements), `find-reader-review-split` (bounded tasks), T01/T23
  foundations confirmed. No memory contradicted the code.

## Stays for T43b (not claimed)
Human validation through the tool surface, watcher refresh, the controlled
launcher, actual authenticated MCP, published tree state, source file edit,
review blocking, retained worktree context.
