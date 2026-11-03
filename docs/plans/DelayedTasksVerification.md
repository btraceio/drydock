# Delayed tasks — verification and acceptance gates

Companion to [DelayedTasksImplementationPlan.md](DelayedTasksImplementationPlan.md).
Commands below define the gates, not a claim that they passed. The complete
feature is not verified; focused execution evidence is in build reports.
Task ids/path abbreviations refer to the plan; T02/T03/T04 mean their a–c/a–b
subtasks.

## Execution discipline

- Read repository rules before building. Plugin source-set mapping is defined
  in `intellij-plugin/build.gradle.kts`, not the root app build.
- One writer and one Gradle execution at a time in this checkout. The checkout
  contains unrelated edits, so managed clean-worktree isolation is unavailable
  without committing/stashing somebody else's work. Execute each wave's coders
  serially; keep the wave dependency/ownership boundaries. Each coder captures
  its red/green tests, then the coordinator arranges fresh review/log summaries.
- The prescribed wrapper and gradle-logs-analyst agent are absent. The operator
  approved a session-local `.claude/commands/build-and-summarize` capture adapter
  outside tracked source and cairn-checker for log summaries. The adapter runs
  plugin Gradle and retains randomized full logs; no general build-system change.
- Capture full stdout/stderr in a randomized file under
  `intellij-plugin/build/logs/`; report command, exit status and result counts.
  Never use a fixed shared /tmp filename. If log parsing is delegated, it is a
  bounded read-only task and its output is a separately named report.
- The plugin currently isolates shared projects for test/runIde, not task
  storage (which does not exist yet). T12 adds task-root isolation before the
  first platform test can initialize a task service. Every new core fixture
  injects its own temporary task and plan root; tests never touch ~/.drydock/tasks.
- Core tests use JUnit 5. Platform fixture tests use BasePlatformTestCase and
  the existing Vintage engine. Inspect real XML reports; do not accept
  BUILD SUCCESSFUL with zero selected tests as a passing case.

## Source and task budgets

A task targets ~300 changed lines including tests; it is not a lifetime
300-line limit on the file. Serialized phases may extend the same class rather
than manufacture extra abstractions. New files stay under ~1,000 lines/~50 KB;
existing files approaching 2,500 lines/~125 KB need a split proposal.

Largest existing affected sources at the planning baseline: McpToolRouter
1,751 lines/95,738 bytes, DrydockSessionService 1,407/74,648 and TaskTreePanel
1,299/63,053. Re-measure before enlarging them; keep feature logic in focused
helpers instead of growing these hosts indiscriminately.

## Narrow commands

After the capture route is approved, each task's selector expands to:

```sh
cd intellij-plugin
./gradlew test --tests '<selector from the task block>'
```

The command is the underlying invocation, not permission to bypass log capture.
When several task classes share a wave, pass multiple `--tests` selectors in
one serial invocation. Existing provider/session/router tests run alongside new
ones whenever their production file changes. For fixture commits use the
build's existing git isolation; never alter the user's git authentication or
commit signing to make a test fixture work.

## Full gates

| Gate | Underlying command/check | Required result |
|---|---|---|
| Source-set boundary | `cd intellij-plugin && ./gradlew compileCoreJava dependencies --configuration coreCompileClasspath` | Core compiles on its own classpath; no IntelliJ/JavaFX artifact added. |
| Import boundary | Inspect `rg -n '^import (com\.intellij|javafx\.)' intellij-plugin/src/main/java` | No matches. A match is a failure, not an ignored grep status. |
| Plugin suite | `cd intellij-plugin && ./gradlew test` | All existing and new tests pass; record executed/skipped/failure counts from XML. |
| Packaging | `cd intellij-plugin && ./gradlew buildPlugin` | Zip contains one plugin descriptor, task resources and core YAML dependency. |
| Compatibility | `cd intellij-plugin && ./gradlew verifyPlugin` | Inspect verifier report; report any unresolved API rather than equating a warning-free build with compatibility. |
| Scope/size | `git diff --check`; inspect `git status --short`; `wc -lc` changed sources | No whitespace errors, accidental host data, oversized source or unrelated edits. |
| Resource loading | Inspect packaged jar/resources and launch fake-provider tests | Claude plugin manifest+skill and pi skill paths exist; no host-global config changes. |

Do not claim Plugin Verifier success if IDE downloads, network access or the
verifier itself prevented completion. The app/JavaFX build and native Ghostty
build are not feature gates: no app production source is in scope.

## Requirement-to-evidence checklist

| Requirement | Main tasks | Evidence required before acceptance |
|---|---|---|
| D1 own model; no Tasks plugin | T01–T08, T21 | Core compile/import gate; no Tasks plugin dependency in build or plugin.xml. |
| D2 shared per-user files | T04–T05, T12–T13, T43 | Two worktree windows see same files; two JVM writers do not lose mutations; sandbox tests do not access real store. |
| D3 one-direction graph | T06, T08 | Inverse follow-ups derived from after; no persisted inverse field; nested/diamond graph tests. |
| D4 stable globally unique ids | T01, T04–T06, T43 | Same-title concurrent creation across stores; retitle keeps filename; project qualification/ambiguity tests. |
| D5 New Session + save later | T18–T20 | Save with no harness installed creates only a task file; launch mode unchanged; old action id/shortcut retained. |
| D6 four status entry paths | T05, T10–T14, T28, T30 | Agent, human action, launch hook and external edit publish committed status; failed writes remain errors. |
| D7 same-project chain | T08–T09, T31 | All open prerequisite levels in one session, done context, direct follow-ups explicitly out of scope. |
| D8 cross-project stages | T08, T22, T32–T38, T43 | Fake-launcher barriers prove independent segments parallel and next stage waits; real two-repo manual scenario. |
| D9 ready-first tree/filter/age | T07, T15–T17, T20 | Fixed-clock sorting/stale test, completed filter, error rows, keyboard actions, open tasks remain visible. |
| D10 session task children | T07, T15–T17, T27–T30 | Assignment survives idle/stop, deletion returns unfinished tasks, last session link/history retained; no process-exit-as-done. |
| D11 running blocker | T07, T15–T17, T20 | Blocker includes session identity and navigation opens the correct hosted session. |
| D12 auto target / ready outside plan | T34–T40 | Target starts once inside confirmed plan; outside plan only a balloon; no competing start notification. |
| D13 worktree context | T24, T27, T29, T41 | Inspect actual CLI args; forgotten prerequisite still has provenance; both removal paths protect active consumers. |
| D14 agent creates tasks | T10–T14, T43 | Authenticated task_create writes correct project/after and defaults human; failed write not acknowledged as success. |
| D15 harness owns in-segment order | T09, T31, T42 | Prompt tests plus harness smoke test; no central per-task scheduler; dependent work waits at review. |
| D16 human gate | T07, T10, T18–T20, T30, T34, T37, T39 | Agent done→review, no stage advancement; Confirm→done; Reopen halts; policy changes only via approved human path. |
| D17 guidance | T23, T25–T26, T29, T42 | Handshake subset tests; installed Claude/pi skill; Codex handshake-only; resume receives guidance again. |
| N1 bounded coordinator | T34–T36, T38–T40 | No retry/priority queue/autonomous scheduling; Cancel stops future starts and startup never launches before Continue. |

P1 byte-preservation/note exceptions: T02–T05. P2/P3 malformed relations:
T06/T08. P4 persistence/recovery/lifetime: T27/T32–T41. P5 navigation and external
editing: T13/T18–T21. P6 prompt and binding: T09/T28/T31. P7/P7a protocol and
skills: T10–T14/T25–T26/T42. P8 human Done: T30/T41.

## Manual acceptance scenarios

Run in the plugin sandbox with a temporary task root and scratch git repos,
not this repository's real worktrees. Record actual CLI versions and results in
`intellij-plugin/build/reports/delayed-tasks/` using a randomized report filename.
Do not assume the installed binaries match earlier research versions.

1. **Definition, no process.** In an unregistered scratch repository, save from
   New Session with no selected/available harness. Inspect the file, title/id,
   disabled controls and absence of new worktree/session. Create another task
   from the Tasks node with a cross-project prerequisite.
2. **External editing/navigation.** Open a stored file outside the project;
   edit after/title in an external editor using atomic save. Both IDE windows
   update without opening the file first. Ctrl/Cmd-hover and click [[id]] in
   source navigates. Record Markdown unresolved-label inspection noise; preview
   clicks remain outside v1. A dirty editor buffer is not overwritten by MCP.
3. **One session, human gate.** Start a two-task chain. Inspect prompt and child
   rows. Agent marks the first done: it becomes review, dependent work must not
   start. Confirm from the balloon, return to the harness and continue. Reopen
   a review task and verify the applicable confirmed plan halts. Stop/idle alone
   does not mark anything done; forgetting without Done returns unfinished work.
4. **Unattended cross-project plan.** Two prerequisite repos have independent
   agent-close tasks; target is in a third repo. Confirm choices once. Observe
   concurrent stage-1 starts, wait for both, then target automatic start. Inspect
   paths passed to target. Repeat with one human-gated prerequisite; nothing
   advances before Confirm. Mutate an edge during a stage and observe halt.
5. **Deletion race.** Complete prerequisite via Session Done with worktree
   removal requested while a dependent is eligible to start. Assert retained
   paths cannot be deleted beneath the dependent. Repeat via tree removal and
   with an idle/stopped dependent still in the catalog. After it is forgotten,
   removal is allowed subject to existing git safety checks.
6. **Failure/restart.** Fail one launch in a parallel stage, cancel during launch,
   close the origin window, quit between completion and next-stage intent and
   restart with a completed stage. Record no startup agent before Continue and
   no blind repeat launch for a persisted session id. Explain ambiguous launch
   intent instead of claiming exactly-once recovery.
7. **Skills and tools, each harness.** For Claude/pi/Codex inspect actual task
   tool discovery and safe status round trip. Invoke the Claude/pi skill and
   verify fresh and resumed sessions can find it. Codex gets the handshake, not
   an invented skill flag. Check CLI help for --add-dir/--plugin-dir/--skill.
   For pi without pi-subagents installed, report the missing capability and do
   not silently flatten a dependency plan into unsafe parallel work.

## Not verified at planning time

- Production Ctrl-hover/click and inspection behavior; S1 tested the declaration
  resolution API only. Preview clicking is explicitly outside v1.
- A Codex per-launch skill mechanism; none found in the earlier 0.163.0 check.
- YAML library version/API choice and its packaged dependency footprint.
- External-directory native watcher behavior on the target IU build; T13 is the
  required integration evidence, including unopened files and atomic saves.
- Crash/duplicate-launch and active-worktree deletion races; these are required
  tests/manual gates, not guarantees inferred from the proposed state machine.
- No performance numbers measured. Complexity/allocation bounds in the plan are
  design estimates; record large-store refresh timings if implementation changes
  them or makes full scans recurrent.

## Pipeline accounting and review

Planning recovered to the coordinator with explicit operator approval after two
failed planner dispatches. First dispatch metadata: model
`datadog-ai-gateway-anthropic/claude-opus-5-5:high`, 73 turns, 97,740 reported
output tokens, 11,918,571 cache-read tokens, $5.8589662. It wrote no plan. Second
planner dispatch was refused by the provider: model
`datadog-ai-gateway-anthropic/claude-opus-5-5:medium`, 3 turns, 365 reported output
tokens, 27,321 cache-read tokens, $0.1147262. Combined reported child cost:
$5.9736924. Coordinator session cost is not reported here. The plan here was
written from the requirements and current source, not accepted from those runs.

During implementation record one row per coder/checker/fix dispatch: run id,
role, model, reported tokens/cost and report path. Fresh checker per task; at
most two fresh-coder fix rounds, then stop dependent work and report.

Recommend an in-depth review of actor/validation rules, per-root writer and
plan-driver claims, pre-spawn binding, crash intent reconciliation, and the
Done→next-stage/removal race. Review these boundaries together before enabling
auto-proceed, even if each task's checker passes.
