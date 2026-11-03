# T33a fresh check

**Verdict**: pass

- No defects found.
- PlanFiles.java (189 ln): schemaVersion-first envelope, 4 MiB pre-read bound, atomic temp+fsync+ATOMIC_MOVE writes with temp cleanup, torn→.torn sidecar quarantine; unknown/newer schema refused loudly and NOT quarantinable (PlanFiles.java:110-112). Torn handling blocks only the affected plan.
- PlanStore.java (346 ln): CAS revision saves with visible stale refusal (PlanStore.java:203-206), failed save = visible exception (no-launch-permission), one OS claim per plan at plans/<id>.claim — separate from TaskWriterLock's <root>.lock sibling (TaskWriterLock.java:41); second driver refused with concrete reason (in-JVM + OS-lock-null paths); re-acquisition durably pauses RUNNING/unpaused plans (pauseForRestart), no auto-resume, explicit Continue = paused=false save; no lock held across external work (lock acquired/released per op inside the writer body); no parallel task-ownership state (TaskRuns referenced in docs only, store tracks plans only); bounded ArrayBlockingQueue(256) single writer with CompletableFuture per op; close() releases claims, flush bounded.
- ASCII-only: 0 non-ASCII bytes in both files. Line counts 189/346 ≤350.
- Smoke: 3 green runs, `SMOKE GREEN checks=60 failures=0` (run-{1,2,3}.log), real child-JVM claim holder logs present (claim-child-*.log, commands.log documents the matrix).
- Regression independently parsed from build/test-results/test/*.xml: 161 tests / 0 failures / 0 errors (= baseline, no new tests); log delayed-tasks-PTgHjgcq.log shows executed `> Task :test`, BUILD SUCCESSFUL.

**Deviations accepted**: claim-parameterized saves, envelope carrying paused/revision, plan writes riding the root TaskWriterLock while claims form a separate family — consistent with the block's "same discipline as TaskWriterLock but SEPARATE lock files".

**Memories**: find-delayed-tasks-decisions, find-foundation-verification, find-reader-review-split honored; dead-build-on-ij-tasks not reintroduced (no LocalTask/TaskManager usage).
