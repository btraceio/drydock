# T22a — observable existing launch path, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T22a/T22b
in docs/plans/DelayedTasksImplementationPlan.md, requirements D8/P4.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source. Whole-suite regression baseline: **3092 tests / 0
failures** (plain `test`, ~2.5 min).

Read the find-launch-and-mcp-seams cairn node FIRST — it documents the
exact seams: TaskLauncher.start returns void and early-exits;
prepare/prepareAndShow are private; NewTaskDialog/NewTaskPreset are
package-private; SessionDialogs.launchSession already has prepare ->
tab -> async prompt resolver -> DrydockSessionService.launchSession;
RUNNING future is too late for a fast MCP call; the plan requires a
public preparation/choice facade, not cross-package calls to
inaccessible types. Accepted (do not edit): T19a TaskLauncher saveTask
delta (read how it was added additively). AGENTS.md UI rules apply.

OWN ONLY two bounded deltas to EXISTING files:
- intellij-plugin/src/platform/java/app/drydock/intellij/tasks/
  TaskLauncher.java
- intellij-plugin/src/platform/java/app/drydock/actions/
  SessionDialogs.java (verify the actual package/path first)
Deltas bounded and reviewable — additions + minimal seams, no
unrelated rewrites, existing callers preserved. No committed test
file (T22b).

## Task block (approved plan T22a)
1. Expose BACKGROUND PREPARATION and a public CHOOSE-WITHOUT-LAUNCH
   facade (NewTaskDialog/NewTaskPreset stay package-private — the
   facade returns an opaque choice/result type instead of exposing
   them cross-package).
2. Return a FUTURE from start/launchNewSession (the current void +
   early-exit becomes a completed-future path; every refusal/cancel/
   failure/disposed path completes its future — NEVER a permanently
   pending one; progress and old callers preserved).
3. Plumb a no-op-compatible PREPARED-SESSION callback for T28 (a
   listener/default-method seam that defaults to doing nothing).
4. Chain SessionDialogs.launchSession — NOT a new process path; the
   observable result derives from the existing chain's outcomes
   (launch claims, FAILED persistence, session id).
5. Timeout does not prove a late launch cannot still complete: a
   timed-out wait on the future must not cancel or mark failed the
   underlying launch — the future stays accurate to the actual
   outcome.

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE — plain JVM, NO windows, never show()): headless-constructible
parts — the facade's result type construction, future completion on
each refusal/failure/disposed path (assert no path leaves it pending),
the no-op callback default, timeout-wait semantics (a wait that times
out leaves the future untouched). State honestly that the real
launch-chain completion (spawn, session ids) is T22b's fixture.
>=3 consecutive green runs; record commands/log paths.
Then the approved adapter WHOLE plugin suite as the LAST invocation:
plain `test` (no filter) — cite an executed-:test log; ACTUAL XML
counts (3092 baseline, no new tests). Tooling/provider failure =
blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T22b or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the two bounded deltas (git diff per file) against
this block, the find-launch-and-mcp-seams finding, and the existing
TaskLauncher/SessionDialogs/DrydockSessionService production code.
Verify: public preparation + choose-without-launch facade WITHOUT
exposing package-private types, future returned with EVERY path
completing (refusal/cancel/failure/disposed — no permanently pending),
no-op-compatible prepared-session callback for T28, chaining through
launchSession (no duplicated session/process path), timeout leaves the
underlying launch accurate, progress + old callers preserved, bounded
reviewable deltas, ASCII-only, durable honest smoke, whole-suite
executed-:test regression. Independently parse writer logs/XML.
DELIVERY: your final response text is the authoritative report
(output files are deleted by a cleanup). Read-only: no Gradle,
source/doc/state edits, git mutation or agents. Return <=12 lines:
VERDICT first; defects with exact file:line + required outcome;
confirmed counts/log evidence; accepted deviations; memory ids. Do
not review the full feature.
