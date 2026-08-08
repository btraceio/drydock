# Porting the GitHub PR review onto main's composer

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development to execute this task-by-task.

**Goal:** land stage 1's GitHub review submission on `main`, and give `main`'s
own inline composer the line-range anchoring it lacks — without reverting the
composer `main` already ships.

**Why this plan exists instead of a merge:** `feat/intents` (31 commits, green)
and `main` independently grew a comment composer. `main`'s is single-line and
rendered as a row inside the diff (`ReviewDiffRow.Composer(file, lineKey)`,
inserted under its anchor by `insertComposerRow`); `feat/intents` built a
range composer in the findings margin plus the entire GitHub submission path.
They collide on the gutter and on the `.review-composer` CSS name, so `git
merge` produces two composers fighting over one affordance. `main`'s composer
also does not fix the reported bug — it cannot comment on a range.

**The decision:** `main`'s composer survives and becomes range-aware;
`feat/intents`'s submission layer lands on top; `feat/intents`'s margin
composer is dropped.

## What is already known (measured, not assumed)

- **The GitHub layer ports untouched.** Checking out `GitHubLineAnchor`,
  `GitHubReviewRequest`, `GitHubReviewService`, `SubmitPlan`,
  `DiffLineSelection` and `ReviewDiagFxThread` onto `main` and compiling
  produced exactly ONE error: `ReviewAnnotation.postToPr()` does not exist
  yet. Nothing else in those six classes depends on anything `feat/intents`
  changed.
- **The range model is already in `main`'s storage.** `ReviewAnnotation` has
  carried `startKey`/`endKey` all along (`ReviewAnnotation.java:32-33`);
  `main`'s composer simply passes the same key for both. Range support is an
  extension of the row record and the gutter, not a storage change.
- **`main` posts nothing to GitHub.** No `GitHubReviewService`, no
  `pulls/{n}/reviews` call anywhere in `app/src/main`.

## Source of every ported artefact

Branch `feat/intents`, tip `2ad4fb7`. Take files with
`git checkout feat/intents -- <path>`; do not retype them. Their tests come
with them.

## Tasks

### Task 1 — `ReviewAnnotation` gains `github` + `postToPr`

Port the two components, the nested `GitHubComment(long id, String url,
boolean resolved)` record, the `withGithub`/`withPostToPr` withers, and the
six-argument `human(...)` overload. Persist them in `main`'s
`AnnotationStore.findingToJson` (:638) / `findingFromJson` (:702) — note
those line numbers differ from `feat/intents` because `main`'s store gained
`migrateLegacyVerdicts` in #11.

Traps, all previously paid for: `JsonBoolean` is a plain record (`new`, no
`of`); `JsonNumber` has `asLong()` and NO `value()`; `AnnotationStore` must
import `JsonBoolean`. Decode leniently — an entry with neither key loads as
`github` empty, `postToPr` false. Every other construction site passes
`Optional.empty(), false`; `human(...)` mints `postToPr = true`.

Update every construction site in ONE commit. Count them on `main` first —
the count differs from `feat/intents`, and at least one site is a
fully-qualified `new app.drydock.review.ReviewAnnotation(...)` that a grep for
`new ReviewAnnotation(` will not find.

Tests: port `AnnotationStoreTest`'s two cases (round-trip, and an entry
written before these keys existed).

### Task 2 — the GitHub layer, ported wholesale

`git checkout feat/intents --` the six production classes and their four test
classes (`GitHubLineAnchorTest`, `GitHubReviewRequestTest`, `SubmitPlanTest`,
`DiffLineSelectionTest`). After Task 1 they compile as-is; this task is
verification, not authorship. Run those four suites.

Do not modify them to "fit". If something does not compile, the fault is in
Task 1's port, not in these classes.

### Task 3 — `GitHubReviewService` wiring

Construct it in `DrydockApplication`, close it in `stop()` with the same
per-service isolation as its neighbours, and add the constructor parameter and
field to `main`'s `MainWorkspace`. `main`'s constructor arity differs from
`feat/intents`'s — read it rather than assuming.

### Task 4 — `main`'s composer becomes range-aware

This is the only genuinely new design work.

- `ReviewDiffRow.Composer(String file, String lineKey)` gains an end:
  `Composer(String file, String startKey, String endKey)`. A single-line
  comment sets both to the same key, which is exactly what `GitHubLineAnchor`
  already expects.
- `insertComposerRow` (:437) anchors under the range's END line; its existing
  "anchor line is not rendered" abandonment path stays.
- The gutter gains selection: keep `main`'s `.commentable` hover and its
  click-toggles-composer behaviour for a plain click, and add shift-click and
  drag to extend, resolving through the ported `DiffLineSelection` (which
  already clamps to one file AND one hunk — GitHub rejects a cross-hunk
  comment and the whole atomic review with it). `startFullDrag()` must be
  called from `setOnDragDetected`, never from the press handler, or the first
  click throws.
- Guard every `rows.indexOf(row)` on a gesture path with `< 0` — a `-1` is
  coerced to `0` downstream and silently re-anchors the comment.
- `submitComposer()` mints `ReviewAnnotation.human(scope, file, startKey,
  endKey, message, severity)`.
- Paint the selected range (`review-line-selected`), keyed by
  `file + " " + lineKey` — a bare line key tints the same numbers in every
  file.

Tests (TestFX, harness as in `ReviewDiffColumnTest`): a plain gutter click
still opens the composer on one line; shift-click widens it to a range and the
composer re-anchors; a range crossing a hunk header clamps; a stale row index
leaves the selection untouched; the composer's `TextArea` takes a newline from
a real `KeyCode.ENTER`.

### Task 5 — the submit sheet and the fork

Port `ReviewSubmitSheet` and its test. Then, in `main`'s `MainWorkspace`,
fork `submit(ReviewScope)` (:1335): a scope with a PR opens the sheet;
everything else keeps `main`'s current behaviour byte-for-byte.

Carry over every correction the original earned: a busy modal before the async
`gh` check with an in-flight guard cleared on every terminal path; the
`DiffIndex` built from `displayedDiff` (NOT the rendered rows — collapsed runs
and truncation hide lines GitHub accepts); decisions collected in
`submitReview()`; `Host.submit` widened to carry both, with `FakeReviewHost`
updated in the same commit; on success `markSubmitted`, clear `postToPr` over
`plan.posting()`, close only if `sheet.getScene() != null`, and REVIEW STAYS
OPEN; on failure route to an `Alert` when the sheet was detached by Esc.

### Task 6 — diag driver reconciliation

`main` already has `diagOpenComposer()`. Extend it for ranges rather than
replacing it, and port the `sheet` verb WITH its guard: on a non-PR scope
`submit()` is the merge-and-finish path, and a diagnostic driver must never be
able to start a merge. Port `approveall` with the same guard. Any accessor
reading FX state off-thread goes through `ReviewDiagFxThread`.

## Global constraints

Unchanged from stage 1: no blocking work on the FX thread; every user-triggered
async op shows progress immediately and clears it on every path; child
processes through `ProcessRunner` with timeouts; `AnnotationStore` is the
single writer (`upsert`/`mutate`, no new mutator); lenient decode; imports not
fully-qualified names; Javadoc explains why.

Test command per task: `./gradlew :app:test --tests '<class>'`. NEVER the full
suite in a subagent — it exceeds the time limit. The controller runs
`./gradlew :app:test` at the end.

## What is deliberately dropped

`feat/intents`'s margin composer (`ReviewFindingsMargin`'s composer slot, its
`openComposer`/`closeComposer`, `ReviewComposerTest`), and the margin-collapse
and re-anchor fixes that existed only to serve it. `main`'s in-diff composer
replaces it. `feat/intents` stays on disk as the record of what those fixes
were, and `docs/superpowers/plans/2026-08-06-github-pr-review-stage-1-followups.md`
records the reasoning behind each.
