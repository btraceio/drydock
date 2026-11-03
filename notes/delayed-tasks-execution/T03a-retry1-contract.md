# T03a recovery: one same-model fresh native retry

Cwd / only checkout: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. User approved implementation and
continued after a failed run. No commit/push, auth, gh, agents or unrelated edits.
Read AGENTS.md; do NOT load failed transcripts, broad plans/research or repeat
library archaeology. This packet plus current reader/value APIs is sufficient.

OWN ONLY NEW production source:
intellij-plugin/src/main/java/app/drydock/tasks/TaskFilePatch.java
T03a = METADATA ONLY, <=~300 changed lines; creation/notes are T03b and fixture
T03c. No test/build/helper/model/doc edits. One serial writer. No code exists
at this path yet. Original a0b97d42-8c1e-434e-961b-7b10f14894a1 failed with malformed
SSE JSON while emitting a write call; no source/report/check resulted. This is
not permission to change execution mode or model. All NEW source/tool payloads
must use ASCII (hex values/Java escapes for special characters), not literal
NEL/LS/PS/BOM. That is transport hygiene, not a proven cause/fix of the failure.

## Ready handoff; implement, don't re-investigate
Read the 241-line metadata draft and its smoke harness as DATA:
/tmp/t03-preflight-g2ASuBHt/TaskFilePatch-T03a.java
/tmp/t03-preflight-g2ASuBHt/Smoke-T03a.java
No copying the full later-phase TaskFilePatch.java or T03b fragment. Re-verify
against current TaskFileReader (public Parsed/Span/parse), TaskDocument and
JsonWriter. Draft is not accepted code. It needs the fixes below, not a rewrite
of the whole architecture. Target one implementation + smoke + regressions.
If a necessary uncertainty remains, ask the parent rather than broad probing.

Behavior: typed nullable Updates fields mean unchanged; snapshot non-null
caller lists. Patch only changed known spans/missing keys; stable identity;
unknown text/body preserved byte for byte, including CRLF/astral. No-op returns
identical source. Missing-default values (including supplied created fallback)
must not be materialized unnecessarily. Fail visibly on stale source/invalid
updates; changed output must reparse to requested values. No I/O/locking/graph/
MCP policy or body edits. Keep actual cold-path allocation cost explicit.

Known corrections to the draft:
- Missing-key insertion and first-key replacement in flow root may share an
  offset. Apply insertion first (e.g. sort by start then end), not a false
  overlapping-edit refusal. Preserve unknown surrounding commas/braces.
- Source guard must verify the OPENING fence as well as raw front, closing
  fence/body and span bounds, especially if no-op skips output reparse.
  Avoid large substring copies where range comparisons suffice.
- Snapshot update lists; null entries must be refused clearly (an explicit
  loop avoids the preflight's List.of().contains(null) surprise).
- JSON quoting is syntactically usable but needs scalar-preserving escapes
  for YAML-sensitive/non-printable characters; see verified data below.
- Prefix scanner must use actual YAML whitespace/line-break semantics (for
  comment termination too), not only Java whitespace or LF. Alternatively
  verified bounded parser-prefix events, but no new research is needed.

## Verified environment and parser facts (do NOT search again)
JDK binaries: /Users/jaroslav.bachorik/.sdkman/candidates/java/25.0.3-tem/bin/
Pinned jar:
/Users/jaroslav.bachorik/opensource/.configs/.gradle/caches/modules-2/files-2.1/org.yaml/snakeyaml/2.5/2d53ddec134280cb384c1e35d094e5f71c1f2316/snakeyaml-2.5.jar
Core classes: <cwd>/intellij-plugin/build/classes/java/core
GRADLE_USER_HOME is /Users/jaroslav.bachorik/opensource/.configs/.gradle;
do not use unrelated bundled 2.3 jars, global temp pointer files or init scripts.

Parent actually compiled/reran current TaskFileReader + JsonWriter on that 2.5
jar with JDK25. Probe source/classes in:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/T03a-recovery-pXdznGCK/
Full output: intellij-plugin/build/logs/T03a-recovery-sSS0ORTQ.log, exit 0.
- Leading BOM/U+FEFF, NEL/U+0085, LS/U+2028, PS/U+2029 before a flow root:
  accepted by the actual reader. NBSP/U+00A0 prefix refused.
- Raw quoted NEL changes value; escaped NEL preserves it. Raw LS/PS happen to
  preserve values in 2.5; escaping also preserves them.
- Raw DEL/U+007F and U+009F are refused; escaped forms preserve scalar values.
- Existing failed-run probes confirmed comments/anchors/standard map tags
  before flow root work, directives without document start are refused.
Use these facts in focused smoke; no more version/Java discovery or wide probes.

## Verification
Use RANDOMIZED artifact directories/logs, never /tmp/t03a-dir.txt or globs over
other runs' temp files. Compile/run adapted metadata smoke using JDK25, core
classes and exact jar above. Include draft metadata cases plus same-offset
insert+replace, source-opening-fence mismatch, list snapshot, special scalar
escapes, flow-root prefix/comment breaks and supplied created fallback. Print
ASCII labels/code points, not literal special separators. Record commands,
classpath, log and exit code. Do not invent a red/green run for a source-only
phase; T03c later commits the full fixture.
All Gradle via approved adapter ONLY:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/.claude/commands/build-and-summarize compileCoreJava test --tests 'app.drydock.tasks.*'
Task-only baseline is 29 tests, not 137 (the latter includes providers/contexts).
Unexpected tool/provider failure: stop/report; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
No new investigation/graph writes. Return <=6 lines via configured output:
Changed; Tests (exact commands/counts/full log/smoke path); Deviations;
Difficulties; Memories. Do not claim later phases/full feature complete.

## Fresh checker (only when assigned)
Read-only T03a check against this packet. Confirm actual source/guard/flow/scalar
logic, ownership/line bound, source preservation and smoke/regression evidence.
Independently parse Gradle logs/XML. Do not demand T03b/T03c now; do report real
reachable defects even if tests pass. No Gradle, scripts from diff, source/doc/
test/state/git edits or agents. Return <=10 lines: verdict, exact file:line
incorrect behavior/required fix, evidence/counts, deviations and memory ids.
