# Review tab: virtualization and honest annotation state

## Problem

`ReviewView` (app/src/main/java/app/cpm/ui/review/ReviewView.java) renders a
unified diff as up to 3000 fully-built `HBox` rows (~8 nodes each, plus a
hover-bound "open in Explorer" button and three mouse handlers per row) inside
a non-virtualized `VBox` wrapped in a `ScrollPane`. Every one of these events
throws all of that away and rebuilds it from scratch:

- selecting a file in the changed-files list,
- switching diff scope,
- adding / resolving / reopening / replying to an annotation,
- **toggling the line-number gutter** (a pure presentation flag).

On a large diff that is ~24 000 nodes created per interaction, plus a full CSS
pass and layout. Composer and annotation-card insertion also locate their
anchor with `diffBox.getChildren().indexOf(...)` — an O(n) scan over up to
3000+ scene-graph children.

Separately, the send-to-Claude loop **fabricates data**: 8 seconds after the
prompt is posted to the terminal, a `PauseTransition` appends a fake
`"Claude"`-authored reply ("Addressed in the terminal…") to every open thread
and flips it to `FIXED` — regardless of whether Claude did anything, and even
if the user has switched file or scope meanwhile. Only that timer's success
path restores the send button.

## Chosen design

### 1. Virtualized diff via `ListView` over a pure row model

The diff pane becomes a `ListView<ReviewRow>` whose items are **plain data
records** (no `Node`s), built by a pure, headless-testable builder:

```
sealed interface ReviewRow
  Breadcrumb(String path)
  HunkHeader(String text)
  DiffLine(UnifiedDiff.Line line, int ordinal)   // ordinal = diff-line index for range selection
  AnnotationCard(ReviewAnnotation annotation)
  Composer(int startOrdinal, int endOrdinal)
  Truncation(int limit)
  Message(String text)                            // "Diffing…", errors, empty states
```

`ReviewRowModels.build(file, annotations, maxRows)` produces the full items
list in one pass: breadcrumb, then per hunk a header and its lines, with each
annotation card placed directly after its anchor line (end key, falling back
to start key; cards whose range is no longer in the diff are skipped, exactly
as today), and the truncation notice at the cap.

A custom `ListCell` renders each row type, reusing all of the existing style
classes (`review-diff-row`, `row-add/del/context`, `review-annotate-handle`,
`review-line-number`, `review-hunk-header`, `review-thread-card`, …) so the
look is unchanged. Only the ~30 visible cells exist at a time.

**ListView vs Flowless `VirtualFlow`.** RichTextFX 0.11.5 already puts
Flowless on the classpath, and Flowless handles variable-height cells with
less measurement jank. `ListView` wins anyway: it is standard JavaFX (no
third-party API surface to own in a core view; today Flowless is only a
transitive dependency, not something our code imports), it supports
heterogeneous variable-height cells fine at this scale (≤ ~3100 items),
`scrollTo(int)` gives us composer reveal, and its cell lifecycle is
well-understood for the stateful-node caching described below. Flowless would
buy smoother pixel-scrolling at the cost of a new direct dependency and a less
familiar API; not worth it for a diff list capped at 3000 rows.

### 2. Cell-level updates instead of full re-renders

- **Gutter toggle** flips a `BooleanProperty`; each diff-line cell binds its
  line-number labels' `visible`/`managed` to it. No rebuild.
- **Range-selection styling** is driven by two selection-bound properties
  (anchor / head ordinals). Each cell observes them and toggles its own
  `row-selected` style class. Dragging the `+` handle across rows updates the
  properties only.
- **Composer** open/close is a model insert/remove at
  `rowItemIndex[endOrdinal] + 1` — an index into the items list maintained in
  an `int[]` alongside the model (adjusted on extra-row insert/remove), never
  a scene-graph `getChildren().indexOf`.
- **Annotation mutations** (resolve/reopen/reply/status) replace the single
  affected `AnnotationCard` item in place (`items.set(i, …)`); adding an
  annotation inserts one card row. The file list re-render (cheap, tens of
  rows) and footer summary still refresh, but the 3000-row diff model is
  untouched.

**Stateful nodes survive recycling.** A recycled cell would destroy typed
text, so nodes that hold user input are *owned by the view*, not the cell:
the composer node is built once per open composer, and annotation-card nodes
are cached per annotation id (invalidated when that annotation changes). The
cell for such a row just mounts the cached node as its graphic; each row
appears exactly once so single-parenting holds. Diff-line/header cells build
their (cheap, stateless) content on `updateItem`.

ListView's own selection/focus decoration is neutralized with CSS
(transparent `list-cell` backgrounds, zero cell padding) so rows look
identical to the old VBox children.

### 3. Honest annotation state — BEHAVIOR CHANGE

> **The fabricated validation loop is removed.** Previously, "Send to
> Claude" waited 8 seconds and then unconditionally appended a fake reply
> authored as "Claude" ("Addressed in the terminal — re-run the diff to
> verify.") and flipped every sent thread to `FIXED`, whether or not Claude
> had addressed anything. **The app no longer writes messages on Claude's
> behalf and no longer claims annotations are fixed.**

New behavior of `sendToClaude()`:

- posts the prompt to the live terminal exactly as before;
- immediately (synchronously, no timer) marks each sent annotation with the
  new status **`SENT`** — no fabricated thread reply;
- immediately restores the send button and shows the banner
  ("N annotation(s) sent to Claude") with the existing "Re-run diff →"
  action, which remains the honest way to see what actually changed;
- there is no code path left that sets `FIXED` — the enum value remains for
  persisted files from older builds (lenient decode keeps working), and a
  `SENT` thread can be reopened or resolved by hand like any other.

`AnnotationStatus` gains `SENT` ("handed to the terminal; outcome unknown"),
rendered with its own status-pill style. Summary line counts sent threads
instead of pretending they are fixed. Because the button is restored
immediately, the stuck-button failure mode (timer path being the only
restorer) disappears, and switching file/scope mid-send can no longer be
retroactively rewritten by a stale timer.

## Alternatives considered

- **Flowless `VirtualFlow<ReviewRow, Cell>`** — rejected above (new direct
  dependency, unfamiliar API, no need at ≤3100 rows).
- **Keep the VBox but window it** (render only a slice + spacer regions) —
  hand-rolled virtualization, scroll math and reuse bugs for free; rejected.
- **One `StyledTextArea` (RichTextFX) for the whole diff** — good for text,
  hostile to inline interactive cards/composer (would need paragraph-graphic
  tricks); rejected.
- **Timer-with-cancellation instead of removing it** (cancel the
  PauseTransition on scope/file switch) — still fabricates a Claude reply and
  a FIXED claim; the dishonesty is the bug, not the timer's lifetime;
  rejected.
- **Asking the Claude session for confirmation before flipping state** — no
  such feedback channel exists today (`promptSender` is fire-and-forget into
  the terminal); out of scope.

## Risks

- **ListView chrome leaking through** (selection highlight, focus ring, cell
  padding) — mitigated with dedicated CSS; verified visually.
- **Cell recycling vs stateful nodes** — mitigated by view-owned cached nodes
  for composer/cards; the cache is invalidated on annotation change and
  cleared on rebuild, so no stale UI or unbounded growth (cards ≤ annotation
  count).
- **Full-press-drag-release across cells** — `startFullDrag` +
  `MouseDragEntered` must keep working when rows live in cells; the handlers
  move unchanged onto cell content and the view-level `onMouseReleased`
  backstop remains. Verified manually.
- **Persisted `SENT` read by an older build** — older `fromPersisted` falls
  back to `OPEN` for unknown text: safe (an annotation degrades to open,
  nothing is lost or fabricated).
- **Behavior change visibility** — users accustomed to threads auto-flipping
  to "fixed" now see "sent"; this is the point, and the banner + re-run diff
  path communicates the honest next step.
