# Review: intents that mean something, and a destination that explains itself

Date: 2026-08-03
Status: approved, ready for planning

## Why

Three reports from using Review on real repositories:

1. The intent rail almost never describes the changed files of the selected
   item. It shows "changes against some arbitrary other branch" -- infra
   commits that landed a long time ago.
2. On a narrow window only the queue and the intent rail are visible, and
   clicking an intent card is the only way to make anything happen. There is
   no obvious next move.
3. Landing on Review gives an empty surface that reads as if it belongs to
   the session you came from, without saying what Review is or why it is
   empty.

Each has a distinct cause. None is a matter of taste.

## What was measured

**`java-profiler`**: one checkout, on `main`, clean, no non-main worktrees.
It therefore contributes no diffable queue item at all -- only
not-checked-out PRs, which render the checkout gate. Any file list its
intent rail shows must have come from a different repository.

**`btrace`**: `origin/HEAD` is `master`, but every branch is cut from
`develop`. Measured over its four worktrees:

| worktree | vs `master` | vs `develop` |
|---|---|---|
| `feature/btrace-console-tui` | 1382 files | 45 files |
| `feature/btrace-utils-split` | 1231 files | 23 files |
| `agent/harden-integration-test-teardown` | 1436 files | 5 files |
| `btrace-tck-impl` | 1370 files | 108 files |

A base of `master` yields exactly the reported symptom: over a thousand
files of long-since-committed infrastructure. `GitStatusService.reviewBaseBlocking`
is meant to pick `develop` here, and its candidate loop would -- but when
`commitsAhead` fails for every candidate it returns `defaultBranch`
(`master`) and says nothing.

## Part 1 -- intents that describe the thing you selected

### The defect

`MainWorkspace.ReviewHost.intents(scope)` resolves intents as:

```java
intentGrouping.intentsFor(scope.id(), reviewDestination.currentDiff());
```

`currentDiff()` is the diff column's last-rendered diff -- one global,
belonging to whichever scope most recently loaded one. Nothing ties it to
`scope`. Consequences:

- A `Kind.PR` scope with no worktree renders the checkout gate, so
  `diffColumn.setScope` is never called: its rail shows the previously
  selected scope's files, permanently.
- Any scope shows the previous scope's files for as long as its own diff is
  loading, because `showItem` renders the rail synchronously and the diff
  arrives off-thread.
- Across repositories the two need not be related at all.

### The design

The diff a scope's intents are derived from is **that scope's diff, or
nothing**.

1. `ReviewDiffColumn` publishes `(scopeId, outcome)` when a diff resolves,
   rather than notifying that *a* diff landed. `showDiff` (the patch-only
   path) publishes on the same channel with the scope it was read for. The
   outcome carries failure as well as success: today the failure branch
   returns without calling `onDiffLoaded` at all
   (`ReviewDiffColumn.java:196-201`), which is what would otherwise leave a
   failed scope indistinguishable from one still loading.
2. The workspace holds the per-scope diffs. `intents(scope)` looks up that
   scope's entry. A scope with no entry has no diff, and there is no global
   to fall back to.
3. `IntentGrouping.intentsFor(scopeId, diff)` returns the reviewer's
   grouping when there is one; otherwise the by-file fallback over the given
   diff; otherwise **empty**. It never returns another scope's grouping and
   never sees another scope's diff.
4. `ReviewIntentRail` renders the empty case honestly rather than as a blank
   rail. "Empty" is four situations, and the rail must not render one as
   another:

   | situation | rail says |
   |---|---|
   | a diff is in flight | `Diffing…` |
   | gate item, no diff will ever run | `Not checked out — check out to group changes` |
   | the diff failed | `Could not diff — see the message beside this` |
   | the diff succeeded with no files | `No changes` |

   The last two are why the rail cannot infer its state from "no entry in
   the per-scope map". A failed diff is not in flight, and a successful
   empty diff *does* publish an entry -- with zero files. The rail is told
   which situation it is in; it does not deduce it from an absence.

The point is structural: after this, "the rail shows a different scope's
files" is not a bug that was fixed, it is a state that cannot be
constructed.

### The base, and making a wrong one visible

5. `reviewBaseBlocking` logs, at a level that reaches the log by default,
   when every candidate's `commitsAhead` failed and it is falling back to
   the default branch. The fallback stays -- a base is always needed -- but
   it stops being silent.
6. A resolved base carries how it was chosen: the PR declared it, the
   checkout was forked from it, or it is the default branch because nothing
   could be measured. The item header shows this, so a base of `master` in a
   `develop` repository is legible before the reader counts the files.
7. A `Kind.PR` scope with no worktree carries no diffable base. Today it
   pairs the PR's `baseRefName` with `diffRoot()` = the repository's main
   checkout, which can only produce "the main checkout's HEAD vs the PR's
   base" -- wrong by construction, never the PR's diff. Nothing may run a
   diff for such a scope; the gate and the patch-only path are its only
   ways to show code.

### Tests

End-to-end over real git fixture repositories, driving
`ReviewQueueService` -> scope -> diff -> intents. These are the cases:

- **Local changes.** A dirty checkout produces one working-tree item whose
  intents are exactly the dirty files.
- **A `develop`-cut branch in a `master`-default repository.** The base
  resolves to `develop`; intents are the branch's files, not the whole of
  `develop`.
- **Base resolution failure.** When no candidate can be measured, the base
  falls back to the default branch *and* the fallback is recorded, so the
  header can say so.
- **A sentinel PR with a checkout.** Intents are the PR's changed files.
- **The same PR without a checkout.** Intents are empty and the gate is
  shown -- and, the regression that matters, selecting it immediately after
  a worktree item leaves the rail empty rather than inheriting the
  worktree's files.
- **Cross-repository.** Selecting an item in a second repository never
  shows the first repository's files.

## Part 2 -- a narrow window with an obvious next move

### The defect

`ReviewDestinationView.applyResponsiveLayout` decides each rail's collapse
from an independent width threshold (`NARROW_WIDTH` 1320,
`QUEUE_COLLAPSE_WIDTH` 1180, `INTENT_COLLAPSE_WIDTH` 1040,
`MARGIN_COLLAPSE_WIDTH` 880). Each rail pins `minWidth`, `prefWidth` and
`maxWidth` to its own target; the code column has no floor. At ~1200px the
rails take 236 + 232 + 336 = 804px and the code column absorbs every
shortfall. The rails are then the only thing on screen, and the intent rail
is the only interactive surface -- which is why clicking a card feels like
the only available action.

### The design

**a. One invariant instead of four thresholds.** The code column gets a
minimum width of 560px -- wide enough for a unified diff line at the default
density without wrapping, which is the narrowest width at which the column
is still doing its job. Rails collapse in priority order -- findings margin, then
intents, then queue -- until the code column clears it, and re-expand in the
reverse order as the window grows. A user's manual collapse still wins, as
it does now, and is still remembered independently. The four
`*_COLLAPSE_WIDTH` constants are removed. What replaces them is a rule that
can be stated in one line: there is always readable code on screen.

**b. Selecting a queue item lands on intent 1.** `showItem` sets
`intentIndex = 0` but never calls `revealCurrentIntent()`, so the centre
stays where the previous item left it. Selecting an item reveals intent 1's
hunk; so does the arrival of that item's diff, since the intents do not
exist before it.

**c. The verdict bar is the standing action.** It is already always present
and always bottom-most. It gains the current intent's number and title, so a
collapsed intent rail still says where you are, and visible previous / next
/ next-unsettled controls, so `[`, `]` and `n` are accelerators rather than
the only route. With every rail collapsed the bar is then a complete review
loop on its own: read, approve or request changes, advance.

### Tests

2a is arithmetic and gets a real unit test: drive the width down and assert
the code column never falls below its floor and that rails collapse in
priority order, then drive it back up and assert the reverse. 2b and 2c go
through the existing `FakeReviewHost`: selecting an item reveals intent 1;
the verdict bar names the current intent; its controls move the intent the
same way the keys do. The visual result goes through the `shot:` scene
snapshot harness.

## Part 3 -- a Review destination that explains itself

Four distinct causes behind one impression.

**a. It looks like a session tab.** It is not one -- `setReviewShowing`
hides the tab pane outright -- but it is reached with `⌘4` from a session
and the chrome does not mark the transition. The title bar states what it is
showing across which repositories, and carries a visible way out, so Review
is a place entered rather than a tab occupied.

The way out is `Esc`, and only `Esc`. `⌘4` is the way *in*:
`DrydockApplication.java:826` routes it unconditionally to
`showReviewForCurrentSession()`, and pressed while Review is already showing
it re-enters and re-selects the origin session's scope
(`MainWorkspace.java:758-764`). Labelling it as an exit would advertise a key
that does nothing, two rows above the existing hint that correctly calls it
the way back in (`ReviewDestinationView.java:192`). Making `⌘4` a toggle is
a defensible separate change; this spec does not make it.

**b. The header claims a session you are not in.** `showItem(null)` still
renders the session row, dot and all, reading "no items in the queue". With
no item there is no session to describe, so the row is not rendered. With an
item, the line describes *that item's* session and says so, never the
session Review was opened from.

**c. Empty when it should not be.** A failed or slow `gh` leaves "Nothing to
review" on screen indefinitely with the reason in a log file.

The seam matters here, because the obvious one is the wrong one.
`refreshReviewQueue` does log an assembly failure and return without touching
the view (`MainWorkspace.java:796`) -- but that branch is very nearly dead:
`ReviewQueueService.assemble` absorbs every underlying failure into
`Fetch(value, complete = false)` (`ReviewQueueService.java:154-191`), so the
future almost never completes exceptionally. A `gh` that is missing, slow or
unauthenticated produces a *successful* assembly with fewer items and
`requestsComplete = false`.

That completeness is the real signal, and today it never leaves the service:
`assemble` returns a bare `List<ReviewItem>`, and `localComplete` /
`requestsComplete` are consumed internally to decide scope revocation
(`ReviewQueueService.java:432`). So `assemble` returns them alongside the
items, and the view drives its state from them. The `failure != null` branch
stays as a backstop and stops being silent, but it is not the mechanism.

The empty surface becomes four distinguishable states:

| state | what it says |
|---|---|
| scanning | which repositories are being scanned |
| nothing reviewable | what was scanned, and that it found nothing |
| scan incomplete | which source did not answer, and a Retry |
| no repositories | that none is configured, and how to add one |

*Scan incomplete* is the `complete = false` case -- "git answered, `gh` did
not; PRs may be missing" -- not only the outright exception. It is reachable
in normal use, which the exception is not.

**d. Unclear what Review is for.** The *nothing reviewable* state names what
lands in the queue -- uncommitted changes, agent worktrees, your branches,
PRs that ask you for a review -- and how to produce one.

### Tests

The four-state machine is testable through `FakeReviewHost`: an incomplete
scan (`gh` did not answer) reaches the view as the incomplete state rather
than as "nothing to review"; an outright assembly failure does too; Retry
re-runs the assembly; an empty successful scan is distinguishable from one
that never completed. The session row's absence with no item selected is a
view assertion. Appearance goes through the `shot:` harness.

## Out of scope

- Reviewer-supplied groupings going stale relative to a branch that has
  moved. Real, separate, and not what was reported.
- Any change to the MCP review schema.
- Virtualising the diff column (`docs/plans/review-virtualization-design.md`).

## Order

Part 1 first: it is the correctness defect, and parts 2 and 3 are about
presenting a state that Part 1 makes trustworthy. Part 3 before Part 2 is
also viable; Part 2 before Part 1 is not, because "land on intent 1" is
harmful while intent 1 belongs to another scope.
