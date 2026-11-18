# Operator approvals

Recorded 2026-11-16, after presentation of the specification, decision contract, revised implementation plan and planning review.

## Approved

- Default reusable editor-area host implementation plan: 33 tasks, 18 sequential single-writer waves, subject to the plan's baseline and verification gates.
- Preserve explicit Enter/double-click Start/Resume for stopped navigator rows.
- One held different-session secondary pane inside the same Sessions document; holding leaves primary empty, occupied secondary must be closed before another hold, selecting held session focuses that pane, and closing it only hides the view.
- In-memory Previous Session action, without persistent MRU or a default shortcut; stopped previous targets are shown without launching.
- Disclosed restoration/attention defaults: remembered new-format selected ID or honest no-selection on first upgrade; held/previous state not restored across IDE restart; only actually visible owned console content counts as viewed, not placeholders.
- T28's explicitly isolated coherent removal of the obsolete 734-line editor plus narrowly owned cleanup; no added behavior in that removal task.
- Build the unmodified pinned baseline and prepare isolated sandbox observation before source changes. Use a separate sandbox/profile; do not operate on the user's running IDE or live sessions. Operator observation/sign-off is still required before behavior changes.
- Explicit verification-route exception: the prescribed build-and-summarize wrapper and gradle-logs-analyst are absent. Use the independent plugin Gradle wrapper, uniquely named captured full logs and concise coordinator summaries. Do not add replacement tooling.

## Not approved or not yet satisfied

- No commits, pushes, authentication changes or modifications to the user's IDE settings/live sessions.
- The initial pre-edit manual-baseline gate is superseded by the explicit ordering amendment below; no manual result or operator sign-off is inferred.
- No claim that builds, baseline observations or manual validation have passed merely because this plan was approved.
- Full verification still requires the documented provider/state/focus/attention/native-restore/lifecycle/routing/manual gates and operator acceptance.

## Implementation references

- `Specification.md`
- `DecisionContract.md`
- `ImplementationPlan.md`
- `PlanningReview.md`

The captured-log route above resolves the build-route approval item in the implementation plan. It does not waive any test or manual acceptance criterion.

## Approved ordering amendment

After T0 built and preserved the unmodified checksum-pinned baseline, the operator explicitly selected **Implement now, compare later**. T0's pre-edit prerequisite is now satisfied by the preserved baseline artifact and source identity; manual baseline observations need not precede source changes. Production implementation is authorized to proceed under the approved bounded plan. Baseline/candidate manual comparison, runtime isolation checks before any interactive launch, provider/state/focus/native-restore checks and operator sign-off remain mandatory final gates. The baseline matrix is still unobserved/unsigned, not passed. No interactive IDE launch, provider authentication, copying credentials or starting real provider sessions is authorized by this amendment.


## Coordinator decomposition record
After the T4 draft hit the approved 360-line gate, the planner settled readiness/failure boundaries and the expander split loader work into T4, T35, T33, T36 and T34. The implementation sequence is now 37 bounded tasks / 22 waves. This is coordinator task decomposition within the approved behavior, not a new operator feature choice, cap waiver or claim of manual verification. The original 441-line draft and snapshot are preserved; meaningful tests move with the APIs they exercise.


## Approved T6 size exception
The operator explicitly approved up to **450 incremental changed lines across the same two T6 files** for the coherent Host-interface, truthful-comment, retained-body refresh and regression-test fix. No new feature or file ownership is added. Fresh review, actual tests and all other task/source-size limits remain mandatory. This exception does not apply to any other task.


## Coordinator activation of pre-declared T36 test split
Measured T36 size triggered the plan's approved test-only split. T37 restores deferred AC-23/25 assertions in the existing initialization test file before T34 can run. The sequence is38 tasks /23 waves. No production scope, cap, manual gate or operator feature choice changed; a bounded sub-stage pass is not full test/manual acceptance.


## Approved T34 probe-error correction
After two review-driven rounds, coordinator source review found unapproved error-policy drift: a RuntimeException from transcript-existence discovery was converted to unknown capability, which keeps Resume for a known agent ID. The operator explicitly selected **Restore failed-probe behavior** and authorized one additional bounded T34 correction/review round. Existence-read failures must propagate into the existing FAILED loader path; only absent capability is unknown, and cosmetic token-estimate failure remains optional. Same three owned files and360-line task cap; no new feature, auth/provider action or other policy change.


## Coordinator activation of the wave8 model-only split
The scoped expander estimated controller plus model integration at370 lines; its declared model-only split is activated as numeric T38 before T8. T7 remains the pane integration task with the small shared ID lookup helper. The sequence is39 tasks/24 waves. This is mechanical decomposition of approved passive restore/history and memory-lookup requirements, not a new operator feature or cap exception. Completed gates and all manual acceptance remain unchanged.

## Approved T7 one-line size exception
The operator explicitly selected **Approve 361-line exception** for the coherent T7 missing-catalog-row lifecycle-intent correction and its regression, across the same three owned files. The original baseline stays authoritative; meaningful tests/comments remain intact. This exception is T7-only, adds no feature or file ownership, and does not inherit or extend T6's450-line allowance. Fresh review and the corrected-tree full test gate remain mandatory. The earlier failed361/360 run is preserved, not retroactively relabeled successful.

## Coordinator activation of the bounded T8/T39 split
The unreviewed controller draft measured457 lines against360. The coordinator activated the gated expander's mechanical split: T8 controller/descriptor plus four core cases, then test-only T39 restoring seven mapped cases; both must pass before T9. The sequence is40tasks/25waves. Original baseline and immutable full-test preservation remain mandatory. Focus expectations are corrected to the already-approved passive/newer-request/departure fence in Wave9FocusFenceClarification.md, not frozen from the wrong draft. No feature, public API, manual-gate waiver or cap exception is added; standard360 applies to both.

## Approved pane/loader integration seams
The operator selected **Approve this seam**: every controller has a real Project, its one loader is lazy, and package-private composition may inject only the actual SessionFactsLoader factory for deterministic readiness/executor tests. The four existing fixture constructor calls move to the Project-bearing form; no project-less test mode, default-project fallback, fake Host policy or pane factory is authorized.

The operator selected **Show, focus and Previous** for retry of FAILED/absent metadata on an already retained pane, restricted to accepted intentional presentations. Cold panes load initially; repaint, timers, metadata/result callbacks and visibility changes do not retry or grant focus. The detailed internal contract is PaneControllerIntegrationContract.md. No real provider/IDE authority, feature or cap exception was added.

## Coordinator activation of bounded pane/metadata stages
The gated settled expansion is activated as T9 (Project/fixture migration, retained panes, full Host surface, lease and lifetime) followed by T40 (lazy real loader and keyed presentation). The sequence is41tasks/26waves; standard360 and every earlier/manual gate remain. T10/T12/T16 wait for T40. T9 is only a pane/lease substage; T40 additionally requires a fresh before-edit budget preflight against actual T9, with further decomposition if necessary. No additional cap or feature choice is implied.

## Coordinator activation of the measured T9 test split
The actual four-file T9 draft measured489 changed lines. The gated exact-extraction split keeps T9's three pane identity/registry/empty-cache cases and adds test-only T41 for five lease/focus/strip/disposal cases, both before T40. All eight cases and earlier eleven controller cases remain; the invalid Host notification pin is strengthened and the cleanup-expiry source guard is scoped to cleanup. The sequence is42tasks/27waves; original baseline and corrected complete-test snapshot/manifest are preserved. No cap/feature/API expansion is added. T9 alone is not full pane/lease acceptance; T40's source-bound pre-edit budget guard remains mandatory.

## Coordinator activation of measured metadata stages
The completed T40 preflight measured a 471-line complete scope in private scratch files, not in the repository. The gated split activates bounded T40 (137 production + 3 staged expectation + 153 core tests = 293) and test-only T42 (184) before all dependent UI/events. The sequence is43tasks/28waves, both standard360. The coordinator's source-matched T40 dispatch authorization permits only that bounded source task, not semantic acceptance. All seven metadata groups/eight methods survive through a corrected complete-test snapshot. Cancelled scratch mutation M2 is not passed/killed coverage; bounded waits and real mutation proof are implementation gates. No feature, public API, error policy or cap waiver is added.
