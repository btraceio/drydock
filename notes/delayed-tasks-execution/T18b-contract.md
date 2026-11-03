# T18b — creation form acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T18b in
docs/plans/DelayedTasksImplementationPlan.md, requirement P5. No
commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source.

T18a is COMPLETE (fresh checker 8e7962e5 pass): TaskPrerequisitePicker
196 (Index.of frozen snapshot+slug map, in-memory search with 150ms
coalescing, canonicalReference qualifying slug:id only when unique,
refusals planted-synthetic-doc composition over TaskGraph) +
NewDelayedTaskDialog 349 (immutable Result with defensive copies +
null->HUMAN default, required title with visible reason, honest
done_by control, zero disk writes; caller supplies the prebuilt index
so there is no async path). Read both files for the exact API before
writing tests. Do NOT edit any production file; if a test exposes a
REAL production bug, keep it failing, report it, and stop.

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/intellij/delayedtasks/
NewDelayedTaskDialogPlatformTest.java
Target <=300 lines. Platform fixture test (BasePlatformTestCase per
the repo's platform-test conventions — read TaskStoreWatcherPlatformTest
for the harness patterns); VfsRootAccess allowlist test-only.

## Required committed matrix (port of the T18a smoke + the dialog
surface T18a deferred to the fixture)
1. Title requirement: empty/blank title blocks OK with a visible
   reason; a valid title passes doValidate.
2. Search/selection: picker index search returns cross-project
   results (project + title + id shown); selecting builds the
   canonical ref; ambiguous and broken refs reported by validation
   with the graph's exact reasons.
3. Policy default: done_by control defaults HUMAN; the Result carries
   the chosen value; body and after-refs round-trip immutably
   (defensive copies).
4. Keyboard operation/cancel: real focusable buttons; Enter confirms /
   Esc cancels; cancel returns nothing and performs ZERO writes (no
   store files created — assert on the temp root).
5. Zero-write proof: completing the whole dialog never touches the
   store root or any worktree/agent path (P5).

Platform-test idioms: pumped waits where async is involved (the
repo's dispatchAllEventsInIdeEventQueue pump-park idiom), no
sleep-only synchronization; fixture-safe dialog instantiation (or an
honest headless split: logic-level assertions via the dialog's
value/validation seams if the fixture cannot show the dialog — state
exactly which parts ran where).

Honest red-green (real red only if a bug is caught; never invented).

## Validation
All Gradle ONLY via approved adapter:
notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.intellij.delayedtasks.NewDelayedTaskDialogPlatformTest
2. FULL regression as the LAST invocation — the WHOLE plugin suite
now runs in ~1 minute, so the regression is the plain `test` task
(NO --tests filter): every suite green is the evidence. Cite an
executed-:test log; ACTUAL XML counts (full-suite total, expect the
877-test baseline + your new tests; report the exact number).
Tooling/provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of NewDelayedTaskDialogPlatformTest.java against this
block and the accepted picker/dialog production code. Verify all 5
matrix items (missing coverage is a defect — especially the zero-write
assertion), pumped-wait discipline (no sleep-only), test-only
allowlist, no production edits, <=300 lines, evidence rule (whole-
suite LAST invocation, XML on disk). Independently parse writer logs/
XML. DELIVERY: your final response text is the authoritative report
(output files are deleted by a cleanup). Read-only: no Gradle, source/
doc/state edits, git mutation or agents. Return <=12 lines: VERDICT
first; defects with exact file:line + required outcome; confirmed
counts/log evidence; accepted deviations; memory ids. Do not review
the full feature.
