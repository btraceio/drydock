# Sidebar session row layout

At the default 320px sidebar width, a session row shows about nine
characters of its name. The name is the one thing on the row a human
authored — `session_rename` exists to produce it — and it is the thing the
row shows least of.

Measured, at that width, on a row nested one level under its repository:

| Consumer | Width |
| --- | --- |
| Tree indent + `.child-row` padding | ~50px |
| Status + agent gutter | 30px |
| Hover action buttons (3 × 22px) | 70px |
| `Resume` ghost pill | ~62px |
| **Name + timestamp** | **~84px** |

Roughly half the row is spent on two things: a control that is invisible
until you hover, and a pill that advertises what clicking the row already
does. The agent mark added in the harness-marks work costs 14px of that and
is not the cause; it pushed an already-badly-budgeted row past legibility.

This design rebuilds the row around the name.

## Scope

In scope:

- The session row becomes single-line, with the name taking the remaining
  width.
- Hover actions move to an overlay layer so they cost no layout width.
- The same overlay treatment for the repo row's `⟳`/`+` and the
  unopened-worktree row's actions, which have the identical problem.
- The shared child-row gutter becomes left-aligned.
- The three row builders move out of `RepositorySidebar` into a companion
  class.

Out of scope:

- The filter chip row, the agent glyphs, and the agent colour tokens. They
  are recent, reviewed work and this design does not disturb them. The chip
  row wraps to two lines at 320px; that is accepted — it sits above the
  list, not inside every row.
- Any change to session ordering. The banding this design relies on already
  exists (see below).

## A. The row

One line, left to right:

```
resting          hovered
( ) ✳  refactor the parser generator     ◫ feat/parser
(●) ◈  fix the flaky worktree test  PR #42  ◫ fix/flaky
( ) π  spike: a new lexer approach       ◫ spike/lexer
```

1. The leading gutter: status dot, then agent mark.
2. The session name, taking all remaining width.
3. Any informational chip that applies: `PR #n` / `merged`, the `◨n`
   findings badge, the `waiting` attention badge.
4. The branch tag — `◫ <branch>` for a worktree checkout, `⎇ <branch>` for
   the current one.

**The branch tag truncates first.** The name keeps its `setMinWidth(0)` and
ellipsis overrun (that clamp is load-bearing: without it a long
agent-authored title sets the row's minimum width and holds the whole
window open, a bug this codebase has shipped). The branch tag gets the same
clamp, so when the row is tight the branch loses characters before the name
does. This is the inversion of today's behavior and the point of the
redesign.

### What leaves the row

**The `8m ago` line.** It costs a full row of height to restate the sort
order. `SidebarChildren` already bands live sessions first — with
`NEEDS_ATTENTION` pinned to the front of the live band — then idle, each
band sorted most-recently-opened first. The most recent session is already
at the top of its band; the timestamp says nothing the position doesn't.

**The `Resume` ghost pill.** ~62px on every non-running session to advertise
that the session can be resumed, when clicking anywhere on the row already
resumes it.

Both move into the row tooltip, which already carries status, agent,
last-opened and working directory.

### The arithmetic

Name + branch go from ~84px to ~232px at the default width. Row height goes
from two lines to one, so roughly a third more sessions fit on screen.

## B. Actions float instead of reserving space

Today `actions.visibleProperty().bind(hoverProperty())` binds visibility
only. The buttons stay **managed**, so they reserve 70px of layout width on
every row, always — visible or not.

The row's `HBox` is wrapped in a `StackPane` whose second child is the
actions strip, aligned `CENTER_RIGHT`. The strip keeps its hover-bound
visibility, but in the overlay layer it occupies no layout width. The name
keeps those 70px whether or not the cursor is on the row, and nothing
reflows as the cursor crosses rows — which matters most when scanning a
long list.

Three mechanics have to be right:

- **Hit-testing.** The overlay pane sets `pickOnBounds = false`, so only the
  buttons are click targets and the rest of the strip's area passes clicks
  through to the row beneath (which resumes the session). Without it, the
  right third of every row silently stops being clickable.
- **Legibility under the buttons.** Floating buttons over ellipsized text is
  unreadable, so the strip carries a short horizontal gradient fading from
  transparent into the row's own background. That background differs by
  state — resting, `:hover`, `.active` — so it is expressed as solid tokens
  rather than guessed: `-drydock-row-fade` and `-drydock-row-fade-active`,
  defined in **both** theme sheets (`ThemeTokenContractTest` enforces name
  parity).
- **The cell's width contract.** `SidebarTreeCell.computePrefWidth` returns
  1 deliberately, so the virtual flow sizes every cell to the viewport and
  long names ellipsize instead of growing a horizontal scrollbar — which
  previously pushed the action buttons out of view and broke single-click
  repo expansion. The new `StackPane` must not reintroduce a preferred
  width: the inner row gets `maxWidth = Double.MAX_VALUE`, and a
  `StackPane` failing to stretch its child is a documented trap here, so
  this is verified in the running app rather than assumed.

The repo row's `⟳` and `+` buttons and the unopened-worktree row's actions
get the same treatment. All three child-row types then behave alike, which
is the reason to do them together rather than one at a time.

## C. Knock-ons

**Gutter alignment.** `.child-row-status` becomes left-aligned instead of
centered, so the session row's status dot, the unopened-worktree icon and
the stale/locked bucket carets all begin at the same x. With single-line
rows this is what makes the list scan as a column. It also resolves the
alignment question deferred from the harness-marks work: a centered
two-glyph pair does not land where a centered single glyph does, and the
visible drift in that build is the evidence.

**The parked `-fx-min-width: -fx-pref-width` rule** on `.agent-mark` is
replaced with a literal min-width. JavaFX resolves `-fx-pref-width` as a
stylesheet lookup, and nothing declares it for that node, so the rule was
almost certainly doing nothing. A literal guarantees the glyph is never
squeezed and closes the item rather than carrying it further.

**`SidebarRows`.** The three row builders move out of `RepositorySidebar`
(~1,900 lines) into a companion class in the same package. This is not
speculative refactoring: the overlay change has to be made identically in
three builders at once, and that is precisely the edit that goes wrong in a
file this size. The builders move as-is; behavior changes only where this
design says so.

## Testing

`RepositorySidebar` cannot be constructed in a test — seven collaborators,
two of which spawn real `git`, plus a `refreshAllStatuses()` call and a 30s
`INDEFINITE` timeline in its constructor. So:

- The existing `app.drydock.ui.*` suites plus a clean compile are the
  automated half. `ThemeTokenContractTest` covers the two new tokens' name
  parity across both sheets and the bare-`px` font-size contract.
- Anything extractable as a pure function gets a unit test. The row builders
  are FX-node factories and are not; if the branch-truncation rule can be
  expressed as a pure width computation, it should be, and tested.
- The real check is the visual pass, and it must be a **before/after pair
  captured at the same 320px width**. "The rows are readable now" is a claim
  only a comparison supports. Capture: the unfiltered sidebar; a hovered row
  (showing the overlay and the fade); a row with a `PR #n` chip and one with
  the `waiting` badge; the gutter alignment across a session row, an
  unopened-worktree row and an expanded stale bucket; and the same in both
  themes.

## Risks

- **The overlay fade against three row states.** Resting, hover and active
  backgrounds differ, and a fade that matches only one of them will look
  like a seam on the other two. The two tokens exist for this; the visual
  pass has to check all three states, not just the hovered one.
- **`StackPane` and the width contract.** The cell's `computePrefWidth` of 1
  and the name's `setMinWidth(0)` are the two clamps holding the sidebar's
  width behavior together. The wrapper sits directly between them.
- **Losing the timestamp is a real loss for someone.** The tooltip keeps it,
  but a user who scanned that column loses a glance-level signal. The
  banding is the argument that they do not need it; if that turns out to be
  wrong, the fix is a right-aligned relative time in place of the branch
  tag, not the second line coming back.
