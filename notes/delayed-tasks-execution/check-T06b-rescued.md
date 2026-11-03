# Check T06b — TaskGraphTest.java (fresh)

**Verdict**: pass

- All 8 matrix items covered: (1) lines 100–118, (2) 123–144 + 149–162, (3) 170–188, (4) 193–254, (5) 259–281, (6) 286–298, (7) immutability via 14 tests; (8) shares the invalid-text method with matrix-2 text forms (lines 147–162) — covered, deviation accepted.
- 1000-node chain committed (lines 286–298): 999 resolved refs, acyclic, dependents at chain-0500 asserted.
- Exact cycle paths asserted: 1/2/3-node (202, 213–214, 224–226) plus closedWalk verification for overlapping SCCs (245–253).
- Real-behavior assertions, no tautologies; snapshots via public constructors, no mocking.
- 299 lines ≤ 300 cap. No production edits: `git status --short` shows only the owned test file new (`intellij-plugin/src/test/java/app/drydock/tasks/`); modified files (AGENTS.md, build.gradle.kts, CreateContext, ResumeContext, LaunchResources) predate this task and are not the writer's.
- Independent XML parse: TaskGraphTest tests=14/failures=0/errors=0/skipped=0 (fresh, Oct 30 13:19); full-suite XML sums 81 = 67 prior + 14 new across 8 task suites; `delayed-tasks-SQlDoX4Y.log` shows executed (non-UP-TO-DATE) `> Task :test`, BUILD SUCCESSFUL.
- brief (investigation.py): no matching memories; none contradicted.

Deviations accepted: matrix-8 shares the invalid-text test method with matrix-2 (evidence: writer report, coverage asserted complete); 356→299 formatting compaction with assertions unchanged.
Memories: none found (kb empty for path).
