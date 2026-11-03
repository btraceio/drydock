# Fresh coder: T02a bounded envelope extraction

Repo/cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md and the updated approved
plan docs/plans/DelayedTasksImplementationPlan.md, specifically T02a–T02c.

T01/T23 passed fresh review and 119 combined tests. Original T02 produced green
code but exceeded the ~300-line task cap: 347 reader +19 exception +217 test +
build edit, ~596 total. Parent rejected it as complete and split it into:
T02a envelope/dependency/errors, T02b production reader, T02c test matrix.
Do not resume the previous coder's context; this is a fresh bounded task.

OWN ONLY these four paths:
- intellij-plugin/src/main/java/app/drydock/tasks/TaskFileEnvelope.java (new)
- intellij-plugin/src/main/java/app/drydock/tasks/TaskFormatException.java (already partial)
- intellij-plugin/src/test/java/app/drydock/tasks/TaskFileEnvelopeTest.java (new)
- intellij-plugin/build.gradle.kts (already partial)
Target <=300 changed lines total. No edits to TaskFileReader.java or its test;
T02b will wire the helper and remove the duplicate implementation. This helper
is temporarily unwired, not a permitted committed dead-code endpoint.

Read current TaskFileReader.splitFences/endOfOpeningFenceLine and its initial
whole-file size check as source material. Extract the exact raw-envelope
behavior into a package-private TaskFileEnvelope value/helper carrying:
frontMatter (without fence lines), frontStart (absolute UTF-16 source offset),
body (after closing fence line), plus the code-point file bound. A package-
private record with static parse(String) and MAX_FILE_CODE_POINTS is suitable;
no requirement to invent a public extension API. Keep pure, no I/O/YAML imports.

Preserve raw LF/CRLF/Unicode text and closing-fence-at-EOF behavior. Invalid
opening/closing fence or excess whole-file code points throws checked
TaskFormatException. Preserve constraint comments by moving them with the code;
no shortening an invariant to clean the diff. Do not trim tests for budget.

Retain/verify the checked TaskFormatException and explicit
coreImplementation("org.yaml:snakeyaml:2.5") build edit. The dependency comment
must stay accurate and multi-line, and existing git fixture/classpath isolation
must survive. They already exist in the partial implementation; do not rewrite
unrelated build sections. No new library or YAML feature beyond this split.

Add TaskFileEnvelopeTest: exact raw source slices (LF/CRLF and astral Unicode),
body intact, final fence without newline, malformed delimiters and size bound.
Prefer concise independent tests over duplicated parser behavior. Keep within
four owned files and <=300 lines; ask BEFORE widening if impossible.

Cairn (consult only, no investigation or graph-file edits):
python3 /Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>

RED/GREEN through approved capture adapter ONLY:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/.claude/commands/build-and-summarize test --tests app.drydock.tasks.TaskFileEnvelopeTest
Then include TaskFileReaderTest and TaskDocumentTest as regression selectors.
Record full randomized log paths and actual XML counts, not guessed method
counts; test loops include more input cases than JUnit method count.

No commits, push, auth changes, gh, nested agents, edits to AGENTS.md, docs,
other model/provider files or other checkouts. One writer only. Stop on an
infrastructure blocker rather than silently changing execution mode.

Return <=6 lines through tool-configured output artifact: Changed; Tests
(exact args, red/green exit and full log paths); Deviations; Difficulties;
Memories. Do not create a scratch report in repo source. No claim that T02b,
T02c, all reader behavior or the complete delayed-task feature is finished.
