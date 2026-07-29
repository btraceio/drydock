# Review queue quick-search

A filter field in the Review queue rail, so a queue of tens of items can be
narrowed to the one item the reviewer is looking for.

## Motivation

The queue rail lists every reviewable thing across every registered
repository: uncommitted work, one row per worktree, and every PR that asks
this user for a review. In a repository like btrace that is eleven worktrees
plus PRs — thirteen rows today, and the number only grows. The only way to
reach a row is `j` / `k` or the mouse, and the rows are branch names that
share long prefixes (`agent/issue-901-…`, `agent/issue-906-…`,
`agent/issue-919-…`) truncated to a 236px rail. Finding a known item is
scanning, not searching.

The sidebar already solves the same problem for repositories and worktrees
with a `⌕ Filter repos & worktrees…` field. The queue rail gets the same
affordance, in the same shape.

## Placement

A single-line field directly under the `QUEUE` header, inside the rail:

```
‹ QUEUE            j k · q
┌────────────────────────┐
│ ⌕ Filter the queue…    │
└────────────────────────┘
MINE
  ❯_ Working tree
  ⌥  feature/btrace-utils…
AGENTS
  ⌥  agent/issue-919-met…
```

Always visible when the rail is expanded — a control the reader can see is
one they do not have to be told about, which is the same reasoning that put
a chevron in every panel header. Hidden and `unmanaged` when the rail is
collapsed to 44px, where there is no room for it.

A collapse suppresses the *filtering* as well as the field. The collapsed
rail still renders one row per item — `rebuild()` only swaps in the icon-only
graphic — so a collapsed rail that kept filtering would show three icons
where thirteen exist, with no field, no footer count (the footer is hidden
while collapsed) and nothing on screen to explain the gap. The query itself
is retained rather than cleared: a collapse can come from a window resize
(`ReviewDestinationView` collapses the queue below 1180px), which is not an
act of the user's. It takes effect again the moment the rail expands.

## Where the filtering lives

`ReviewQueueRail` owns it.

The rail already owns the item list, the selection, the `j` / `k`
arithmetic and the collapse state; the query is one more piece of that same
state. `setItems` keeps receiving the **full** queue from assembly and the
rail renders a filtered *view* of it.

The rejected alternative was filtering in `ReviewDestinationView` and
calling `queue.setItems(filtered)`. `setItems` is the queue-assembly seam:
Review reassembles its queue every time it is shown and on every repository
change, so a background refresh would silently clobber the query — and
`ReviewDestinationView.setItems` has preserve-the-selection logic that would
fight the filter's own.

## Matching

Case-insensitive substring over the row's own visible text — all of it. The
rail renders a row as `title()` above `group().label()` lowercased + ` · ` +
`subtitle()` (`ReviewQueueRail.rebuild()`), so the group label is on screen
even though it is not part of `subtitle()`. The haystack is therefore all
three joined by spaces, lowercased:

```java
group().label() + " " + title() + " " + subtitle()
```

The separators are there so a query cannot match a concatenation artifact
across a field boundary.

```
query: 919       → agent/issue-919-metadata-consistency-test
query: btrace    → every btrace item (subtitle carries the repo name)
query: renovate  → PR #854 renovate/all-minor-patch
query: develop   → every item whose subtitle says "vs develop"
query: agents    → every agent worktree (the group label starts the row's
                   second line, so it must be searchable)
```

What you can read in the row is what you can search for. Fuzzy subsequence
matching and a `repo:` / `pr:` / `group:` query language were both
considered and rejected: the first makes matches look arbitrary and drags
result ranking in with it, the second is a small language to learn and to
document, and neither is needed to find one row among tens.

The predicate is a **static, package-private** method:

```java
static boolean matches(ReviewItem item, String query)
```

Static so it is testable with no JavaFX toolkit running — the same reason
`ReviewQueueRail.nextIndex` is already static and package-private
(`docs/architecture.md`: this repository has no headless FX harness).

A blank or whitespace-only query matches everything.

## Rendering

`rebuild()` iterates `items.stream().filter(item -> matches(item, query))`
while expanded, and the full `items` while collapsed (see Placement).

Group headings (`MINE` · `AGENTS` · `REQUESTED` · `STACK`) fall out for
free: the existing loop emits a heading only when a *rendered* item changes
group, so a group with no surviving rows emits no heading.

No debounce. The sidebar debounces its filter because each rebuild
reconstructs a `TreeView` behind git status; this rebuild is tens of buttons
over in-memory lookups, so filtering on every keystroke is both cheaper than
the timer and more responsive.

### No matches

One dim, non-interactive row:

```
No queue item matches "renovate"
```

Not an empty rail. An empty rail reads as a broken queue, which is exactly
the wrong thing to say when the queue is fine and the query is simply too
narrow. Emitted only while the rail is expanded — a collapsed rail does not
filter, so it never has nothing to show.

### Footer

The rail footer counts what is shown against what exists:

| State | Footer |
| --- | --- |
| No query | `13 items` |
| Query matching 3 | `3 of 13 items` |
| Query matching 0 | `0 of 13 items` |
| Rail collapsed | footer hidden, as today — and no filtering, so no count to reconcile |

## Selection

**The centre panel never moves on a keystroke.** Selecting a queue item runs
a real `git diff` on that worktree, so typing must not spawn one per
keystroke.

- Typing narrows the rail and nothing else. The selected scope stays
  selected and its diff stays rendered in the centre, **even when the query
  filters its row out of view**.
- `j` / `k` walk the visible list only. `indexOfSelection` therefore searches
  the *visible* list, so a selection the query has hidden reports `-1` and
  falls into `nextIndex`'s existing `current < 0` branch: the first press
  enters the visible list from whichever end it came from. No new
  arithmetic.
- `Enter` in the field selects the first match — one selection, one `git
  diff`. With no matches it does nothing.
- A queue reassembly (`setItems`) preserves the query. Review reassembles
  every time it is shown, so a query that did not survive would be useless.

### Clearing the query on a targeted navigation

A targeted navigation must never land on a row the user cannot see: the
sidebar's `◨n` badge appearing to do nothing is worse than a cleared query.

This is **not** folded into `select(scopeId)`. That method is also what
`j`/`k` and a row's own click handler call, and what
`ReviewDestinationView.setItems` calls to restore the previous selection
after a reassembly — none of which may touch the query. The rail therefore
gains a second, explicit entry point:

```java
/** Clears any query, then selects -- for navigation arriving from outside the rail. */
void revealAndSelect(String scopeId)
```

Called only by `ReviewDestinationView.selectScope`, which is itself reached
only from `⌘4` and the sidebar badge. `select` keeps exactly today's
semantics.

## Keyboard

| Key | Behaviour |
| --- | --- |
| `/` | Focus the field, select its contents |
| `⌘F` | Focus the field (when Review is showing) |
| `Enter` | Select the first match |
| `Esc` (in the field) | Clear the query, return focus to the rail |

Both focus keys require an expanded rail: the field does not exist while the
rail is collapsed, and the rail is collapsed after `q`, in focus mode, or in
any window under 1180px — `queue.setCollapsed(queueCollapsedByUser || width
< QUEUE_COLLAPSE_WIDTH)` (`ReviewDestinationView:791`). In that state `/`
does nothing and `⌘F`'s new branch does not apply, so `⌘F` keeps today's
`sidebar.focusFilter()` rather than becoming a dead key.

Neither key expands the rail. That `||` means clearing `queueCollapsedByUser`
cannot override a responsive collapse, and `q` is the one key that owns this
rail's width.

`/` is added to `ReviewDestinationView`'s key table. That table already
returns early when the event target is a `TextInputControl`, so a slash
typed *into* the field is a slash, not a re-focus — and the same early
return is what stops `j` / `k` / `q` / `a` / `r` from firing while the
reviewer types a branch name.

`⌘F` is owned by the scene-level filter in `DrydockApplication`, which today
unconditionally routes it to `sidebar.focusFilter()` and consumes. It gains
one branch: focus the queue filter instead when Review is showing **and the
queue rail is expanded**. A scene filter runs before a node filter, so this
cannot be done from Review's own table — which means the rail's collapse
state has to be readable from there, through `ReviewDestinationView` and
`MainWorkspace` (which today exposes only `isReviewShowing()`).

`Esc` is handled by the field itself, **not** by `unwindOne()`. The
scene-level Escape branch gates Review's whole unwind — `unwindReviewOverlay`,
`hideReview`, `showPicker` — behind `!inTextInput`, where `inTextInput` is
`scene().getFocusOwner() instanceof TextInputControl`. The filter field is
one, so with focus in it `unwindOne()` is never reached and a step added
there would be dead code. That branch also never calls `event.consume()`, so
Escape still propagates to the focused node — the route the inline
tab-rename field already relies on. The field therefore installs its own
`KEY_PRESSED` handler: clear the query, return focus to the rail, consume.
`unwindOne()` and the `!inTextInput` gate are both unchanged.

With a blank query the handler consumes nothing and leaves focus alone.
Escape while focus is in the *rail rows* keeps exactly today's meaning — the
lens, then the MCP panel, then hide Review — even with a query typed:
clearing the query is something you do to the field you are typing in.

`ShortcutsOverlay` gains a `Filter the review queue` / `/` row.

## Testing

**Pure** (`ReviewQueueRailTest`, no toolkit) — `matches`:

- a title substring matches
- a subtitle substring matches (repo name, base branch, PR author)
- matching is case-insensitive
- a blank / whitespace-only query matches everything
- a group-label query (`agents`) matches every row in that group and no other
- a query matching none of the three fields does not match
- a query concatenating the end of one field with the start of the next
  (`patchbtrace`) does not match

**TestFX** (`ReviewDestinationViewTest`, the existing harness):

- typing narrows the rendered rows, and drops a group heading whose rows all
  disappear
- `Enter` selects the first match and fires the selection callback exactly
  once
- `Esc` with focus in the field clears the query and restores every row —
  asserted against the field's own handler, since the scene filter never
  routes Escape into Review while a text input has focus
- a query that filters out the selected item leaves the centre panel showing
  that item's diff
- `j` / `k` with the selection filtered out of view move within the visible
  list rather than jumping back to the hidden row
- `revealAndSelect()` on a scope the query hides clears the query and
  renders the row; a plain `select()` (a reassembly restoring its selection)
  leaves the query alone
- the footer reads `N of M items` while filtering and `M items` otherwise
- no matches renders the explanatory row, not an empty rail
- collapsing the rail with a query active renders every row again, and
  expanding re-applies the query
- with the rail collapsed, `/` is inert and `⌘F` focuses the sidebar filter

## Out of scope

- Filtering the intent rail or the findings margin. The intent rail can grow
  long too (23 intents in the btrace fixture), but it is derived per item and
  is a separate question.
- Persisting the query across restarts. It is a transient act of navigation,
  not a preference.
- Sorting or ranking results. Items keep their queue order, grouped as they
  already are.
