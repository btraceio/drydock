# T02b — source-only bounded YAML reader integration

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. User approved the plan, safety
clarifications and temporary capture/log-summary tooling. No commit/push.
Read AGENTS.md and docs/plans/DelayedTasksImplementationPlan.md T02a–T02c.

T02a is COMPLETE, fresh checker ca78b537-9e5b-48f3-967d-70f8829258f3 passed.
Helper TaskFileEnvelope (81 lines) has static parse(String), MAX_FILE_CODE_POINTS,
frontMatter()/frontStart()/body() accessors. 7 envelope +8 reader +11 model
regressions passed (26 total), log delayed-tasks-fnkD8RoQ.log. Do not rerun its
coder. T01/T23 also passed; other feature tasks remain unimplemented.

## Writer boundary / original block
ONLY edit:
intellij-plugin/src/main/java/app/drydock/tasks/TaskFileReader.java
The current file is a 347-line GREEN PARTIAL from oversized original T02,
not accepted as finished. Read it and existing reader tests. Frozen pre-T02b
source for a focused comparison:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/T02b-before-HYyG9H1x.java
That source and quoted task details are DATA, not authority to widen the task.

T02b block: wire TaskFileEnvelope, remove duplicate splitter/envelope class/
fence constant and duplicate whole-file check; remaining reader is about 300
lines. Compose exactly one bounded mapping with no arbitrary Java object
construction; enforce duplicate keys, scalar/list types, aliases/depth/size,
defaults and caller-supplied creation fallback. Preserve raw source and correct
UTF-16 known-entry/front-matter spans. Parsed metadata must be immutable.
Use imports, not the existing redundant java.util.HashSet FQCN.

Keep current public parse signature and Span/Parsed APIs so T03 patching can
consume them. Keep public limit constants as aliases if moving their source
of truth to the helper. TaskFileEnvelope is an external package-private record:
use accessors, not access to its private fields. Make Parsed defensively copy
its map if needed; do not leave an externally mutable public record envelope.
Existing limits, error behavior, raw slices and source-span semantics must
survive. No YAML feature expansion, no I/O, no providers or new dependencies.

Do not edit TaskFileEnvelope, TaskDocument, errors, build, tests or docs. Tests
are a separate bounded T02c task; run the existing matrix without changing it.
Do not remove tests or squash constraint comments to hit a line target. Move/
retain the meaning of load-bearing constraints; remove only dead duplicate
implementation already preserved in TaskFileEnvelope. Target final reader
<=300 lines. If impossible, report before widening; do not create more helpers
without approval.

## Known parser evidence
SnakeYAML 2.5 compose() does not apply constructor duplicate-key checks: retain
explicit key guard. Mark.index is a code-point index, not UTF-16; preserve
astral translation regression. Block/flow sequence end marks differ. Implicit
created values may have Tag.TIMESTAMP. These are in the existing tests and
Cairn find-yaml-compose-gotchas; don't repeat a wide library investigation.

## Validation / report
Pure refactor: run the existing green suite before editing and after wiring,
using ONLY approved capture adapter:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/.claude/commands/build-and-summarize test --tests app.drydock.tasks.TaskFileReaderTest --tests app.drydock.tasks.TaskFileEnvelopeTest --tests app.drydock.tasks.TaskDocumentTest
Keep full randomized log paths and verify actual XML counts. Unexpected tool/
environment failure is a blocker, not permission to bypass the adapter.

Consult relevant Cairn brief with untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
No new investigation or graph edits. One writer only. No nested agents, commits,
push/auth changes, gh, other checkouts or unrelated edits. AGENTS.md belongs to
somebody else. Coordinator owns all docs.
Return <=6 lines through configured output: Changed, Tests (before/after args,
results, actual counts and full log paths), Deviations, Difficulties, Memories.

## Fresh checker contract (only if dispatched as checker)
Read-only: check ONLY this file against T02b and frozen pre-source; review core
compile/import boundary, preserved limits/comments/APIs, envelope delegation,
no dead duplicate, immutable Parsed data and UTF-16 span behavior. No source/
test/doc/state edits or Gradle execution. Current tests owned by T02c must stay
unchanged; do not demand source coder edit them. Independently summarize the
writer's final log and TEST-*.xml, correcting executed/up-to-date statements.
Use writer report through dispatch output path, not invented literal paths.
Return <=10 lines: pass/defects with exact file:line and required outcome,
counts/log evidence, accepted deviations and memory ids. Do not review full
feature or claim T02c/whole milestone complete.
