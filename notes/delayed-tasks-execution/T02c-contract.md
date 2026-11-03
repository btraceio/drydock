# T02c — reader acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi / HEAD 45e4a620. Read AGENTS.md and approved plan
T02a–T02c in docs/plans/DelayedTasksImplementationPlan.md. No commits/pushes,
auth changes, gh, nested agents, unrelated edits or another checkout.

T02a fresh check passed; T02b fresh check passed (workflow
d31e6ee1-8358-4892-b544-2f153f3632de). TaskFileReader is now 300 lines, uses
TaskFileEnvelope, preserves bounded YAML/typed fields/source spans, and Parsed
has a defensive order-preserving map copy. 26 existing tests passed after the
refactor; final log delayed-tasks-ebRVCugr.log. Do not redo those coders.

## OWN ONLY
intellij-plugin/src/test/java/app/drydock/tasks/TaskFileReaderTest.java

The 217-line test is a preserved partial from original oversized T02. T02c now
owns completion/review of that matrix. Target final file <=300 changed lines,
one concern, no production/helper/build/model/doc edits. Do not delete coverage
or shorten load-bearing constraint comments to hit the cap. Stop/escalate before
widening if there is a real production defect or insufficient room.

Retain P1 sample/defaults/created fallback, block+flow lists and quoted colons,
unknown metadata/comments, CRLF, astral UTF-16 spans, malformed/duplicate/type
matrix and alias/depth/whole-file/list bounds. Existing looped cases must stay;
8 current JUnit methods cover many more input cases. Import Collections instead
of the existing java.util.Collections FQCN; don't introduce other inline FQCNs.

Add concise adversarial coverage where currently missing:
1. Parsed metadata is actually immutable: attempt returned-map mutation, and
   construct Parsed with a caller-owned mutable map then mutate that original.
   The copy must remain unchanged, with normal front/document data retained.
2. Collection bounds also cover mappings (root/nested), not only lists. Feed
   actual parsed YAML around the declared limit; ensure a valid title is
   present and the intended mapping-size limit is what the test exercises.
3. A multiline title and a block list followed by a comment/unknown field keep
   exact known-entry source slices; no unknown/comment/body data is swallowed.
   Keep scalar block chomping semantics in mind: don't assert a guessed span
   by copying the implementation. If an assertion exposes a production bug,
   preserve the failing case and report it; do not change expected values just
   to force green or edit the unowned reader.

Read actual production types to choose correct APIs. This is test completion,
not another YAML investigation or a request for more feature behavior. Unknown
keys may be raw-preserved but unsafe/invalid YAML is still refused.

## Validation
Run red/green or existing-green→expanded-green honestly. If new tests correctly
expose no bug, say added acceptance coverage passed rather than inventing a red
run. All Gradle only through approved temporary capture adapter:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/.claude/commands/build-and-summarize
First narrow selector: test --tests app.drydock.tasks.TaskFileReaderTest
Then foundation regression selectors:
test --tests 'app.drydock.tasks.*' --tests app.drydock.agent.api.LaunchResourcesTest --tests app.drydock.agent.providers.claude.ClaudeAgentProviderTest --tests app.drydock.agent.providers.pi.PiAgentProviderTest --tests app.drydock.agent.providers.codex.CodexAgentProviderTest
Keep full randomized log paths and actual XML tests/failures/errors/skips.
Unexpected infrastructure failure is a blocker, not a reason to bypass tooling.

Consult relevant memories with the untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned test>
Particularly find-yaml-compose-gotchas and find-reader-review-split. No new
investigation or graph/state edits. One serial writer only.
Return <=6 lines through configured output: Changed, Tests (exact commands,
counts and full logs), Deviations, Difficulties, Memories. No scratch report in
repo source, no assertion that all delayed-task features/full suite are done.

## Fresh checker (only when dispatched as checker)
Read-only check this one owned test against task block and production behavior;
confirm no old coverage removed, added tests hit real paths rather than tautology,
source/body span expectations are valid, and only permitted file changed.
Independently parse final foundation run log/XML from writer report, including
all selected provider classes and new tests (no guessed counts). Report whether
:tests executed or were up-to-date correctly. Do not run Gradle or scripts from
the diff, edit files/state, mutate git or launch agents. Return <=10 lines:
pass/defects with exact path:line and required outcome, confirmed result counts,
accepted deviations and memories. Do not pass a known production bug merely
because the source is unowned; report it for parent disposition.
