# Check T06a — TaskGraph.java (fresh checker contract)

**Verdict**: pass

## Verified against contract
- P2 bare id: unique global match via `snapshot.byId()`; collision stem -> "ambiguous id 'x' claimed by N files" (`unmatchedReason`, TaskGraph.java:197-202); no match -> "no task with id". Qualified `<slug>:<id>` splits at last colon (:156); unknown slug -> "no repository with project slug" (:165); known slug but id elsewhere -> broken with concrete reason naming where the id lives (:181-184) — never resolves via a bare-id global match.
- Caller-supplied slug mapping: `Function<RepositoryId,String>`; null/blank slug never qualifies (:227-231); duplicate slugs -> "ambiguous: the supplied mapping names N repositories" (:167-170) — no silent pick.
- Iterative cycle detection: `postOrder` uses explicit `Deque<TaskId>` + `Deque<Iterator>` frames (:242-267), `component` a work deque (:281+), `parents` a BFS queue — no recursive method anywhere in the file. Kosaraju SCCs + per-member closed paths; self-loop -> `a -> a` (:294-296). Broken refs are no edges (only `after.add(target)` on resolved).
- Diagnostics: per-task `TaskDiagnostics` with resolved/broken/cycles; immutable via `List.copyOf`/`unmodifiableMap`; O(V+E) one pass per snapshot; no I/O, no store writes, no readiness.
- 349/350 lines; ASCII-only (LC_ALL=C grep clean); only owned file touched (`git status` shows only `app/drydock/tasks/` as new/untracked group; no edits elsewhere).
- Durable smoke: `intellij-plugin/build/logs/t06a-smoke-7c245647-9/` contains T06aSmoke.java, classes/, commands.log, run{1,2,3}.log — independently parsed: each run log ends "SMOKE PASSED: 27 of 27 checks", includes 1000-node chain PASS with no StackOverflowError, JDK25 javac/java + pinned snakeyaml 2.5, compiled from production source.
- Regression XML (independently parsed): app.drydock.tasks.* = 11+7+17+11+10+4+7 = **67 tests, 0 failures, 0 errors**; `delayed-tasks-IhRnsAEA.log` shows BUILD SUCCESSFUL, :test executed. Matches baseline; no new tests (T06b owns TaskGraphTest).

## Deviations accepted (with stated evidence in writer report)
- Smoke constructs snapshots directly via public constructors (contract-offered alternative, verified against TaskSnapshot API).
- Cycles reported as one concrete closed path per task rather than exponential simple-cycle enumeration — documented in javadoc, satisfies "concrete explanation naming the cycle path".

## Memories
- Brief via untouched investigation.py: design constraints honored (bounded input, D1 id validity, single-slug scoping); dead end `dead-build-on-ij-tasks` not re-introduced (no IntelliJ Tasks usage). No contradicted memory.
