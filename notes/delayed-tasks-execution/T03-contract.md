# T03 — lossless metadata patch and explicit note append

Cwd / only checkout:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, approved plan T03,
requirements P1/P7 and current TaskFileReader/Envelope/Document/test APIs.

Dependencies T02a/T02b/T02c are freshly checked PASS. Reader 300 lines, fixture
295; complete foundation regression 137 tests, zero failures/errors/skips,
log delayed-tasks-9tNw2Gk5.log. Do not redo earlier tasks or edit their files.

OWN ONLY:
- intellij-plugin/src/main/java/app/drydock/tasks/TaskFilePatch.java (new)
- intellij-plugin/src/test/java/app/drydock/tasks/TaskFilePatchTest.java (new)
One concern, <=5 files (here 2), target <=300 changed lines TOTAL with tests.
Preflight the size: if full coverage needs a split, STOP and propose precise
subtasks BEFORE writing an oversized implementation. Do not trim correctness
coverage or expand owned files. Original T02's ~596-line overrun was rejected.

Task block: replace only changed known-key spans; insert missing keys without
reserializing the whole document. Unknown YAML, comments and body remain byte
for byte. New files get canonical known front matter. Sole body-writing
operation explicitly appends a session note under ## Session notes, retaining
all existing body text. This class does no disk I/O, locking, graph work or UI.

Design against existing APIs, no new feature/config/field. Prefer typed task
metadata updates and checked failures over an arbitrary YAML text injection
API. Keep id/repository/filename stable. No-op must be byte-identical. Body
must not change merely because new metadata is supplied. Use at most six
known-field spans, applied without offset-shift bugs, with output allocation
O(source+changed fields), not repeated full serialization. Whole-file/field
resource bounds remain relevant; don't output known-invalid task files.

Current reader's Parsed has document, knownKeySpans and frontMatter range.
Spans are UTF-16 offsets in the original source, include key: value (not its
trailing comment), and omit trailing whitespace. Raw front matter excludes
fence lines; rawBody is after closing fence line. Missing created may be a
caller-supplied fallback, not an actual key in source: don't insert unchanged
defaults just because the typed document has them. Preserve raw unknown nested
keys, quotes, comments, CRLF and astral Unicode around edits. If you need a
source fingerprint/consistency guard, validate against parsed raw envelope;
do not mutate a source with incompatible cached spans.

Important acceptance gap to handle explicitly: the reader accepts a YAML FLOW
root mapping too. Inserting a missing status as a new block-style line after
{title: T, extra: ...} would be invalid. Keep valid root-flow input valid while
preserving unknown text (prefix events/verified parser style metadata are
possible approaches, not mandated APIs). Do not invent methods or add an
unapproved reader change. If the existing boundary cannot support this safely
within scope/budget, escalate with evidence before coding around it.

Existing core app.drydock.state.json.JsonWriter/JsonValue may help quote scalar
strings (JSON strings are accepted by YAML); JsonWriter.write appends a newline,
so inspect actual behavior before reuse. Avoid introducing another library or
rolling an unsafe escaper. New-file serialization must parse back to equivalent
known metadata. Use source-backed APIs and run actual round-trip tests.

Test selector: app.drydock.tasks.TaskFilePatchTest. Include meaningful
byte-equality/round-trip tests: no-op; status/title update with unknown nested
metadata and inline comments; missing known keys (including root-flow form);
multiline title and block list preservation; Unicode/CRLF; new canonical file;
notes/repeated note append without altering prior body. Tests should exercise
real parser+patch output, not the same constant on each side. Do not infer
success from formatter output alone.

All Gradle through approved session-local capture adapter ONLY:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/.claude/commands/build-and-summarize
Run red→green TaskFilePatchTest, then test --tests 'app.drydock.tasks.*'. Record
exact args, full randomized logs and actual XML tests/failures/errors/skips.
Unexpected tooling failure is a blocker, not an execution-mode fallback.

Consult relevant brief using untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned paths>
Honor find-yaml-compose-gotchas and current approved decisions; no new
investigation or graph/state edits. No nested agents, commit/push/auth/gh,
other checkout or unrelated changes. One serial writer; AGENTS.md is not yours.
Return <=6 lines via tool-configured report: Changed, Tests (red/green, full
logs/counts), Deviations, Difficulties, Memories. No scratch report in source.

## Fresh checker (only when assigned)
Read-only check the two owned files against T03; new files may be untracked.
Trace actual parser/patch control flow, not names/comments. Validate known-key
editing, insertion (including flow root), exact unknown/body preservation,
Unicode/CRLF and independent note append; identify insufficient tests/unsafe
API choices or out-of-scope behavior. Confirm task bounds; do not accept an
oversized partial as done merely because green. Independently parse writer
log/XML and actual test execution status. No Gradle or source/doc/state/index
edits, scripts from diff, git mutations or agents. Return <=10 lines: verdict,
concrete path:line defects+required outcomes, counts/log evidence, accepted
deviations and consumed memories. Do not claim store/watcher/UI/MCP complete.
