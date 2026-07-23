# Browser tree redesign — design

**Date:** 2026-07-23
**Status:** Approved for planning
**Area:** `app/src/main/java/app/drydock/ui/RepositorySidebar.java` (+ `WorkspaceViewModel`, `app.css`)

## Problem

The repository sidebar (the "browser tree") has grown chaotic:

1. **`(detached)` worktrees clutter the space.** Discovered worktrees with no
   session render as full two-line blocks. A repo can carry many of them (the
   test workspace shows `14 worktrees · 14 unopened`), burying everything that
   matters under rows the user does not act on.
2. **Active sessions are hard to reach.** Live sessions are interleaved with
   idle ones and with unopened worktrees in raw `git worktree list` order, and
   there is no keyboard path to jump between the running ones.
3. **Weird indentation / "can't tell what is what".** Child indent is a
   hardcoded `Insets(5,8,5,16)` plus a 1px rule, so depth reads as an accident.
   Session rows and unopened-worktree rows are built from the same skeleton
   (glyph → two-line VBox → pill → hover actions), so they look like the same
   kind of thing. One line can carry `⎇ ◫ ▶ ■ × 🗑`, a branch pill, a dot, and a
   badge with no reading order.

A latent bug surfaced while investigating: the `olifer` repo header reads
`· 0 worktrees` while one of its own children sits on a `◫ feat/browser_…`
worktree. `repoMetaText()` and `childNodesFor()` count from different sources
and disagree.

## Goals

- Collapse stale/detached worktrees out of the default view.
- Order each repo's children so live work is at the top.
- Give sessions, open worktrees, and the stale bucket visually distinct row
  shapes on one consistent indent grid.
- Make the repo header counts derive from the same data the rows do, so they
  cannot contradict.
- Add a keyboard shortcut to cycle running sessions.

## Non-goals (YAGNI)

- A global cross-repo "Active" section. Ordering is **per repo**; repos stay the
  top-level unit.
- Named subgroup header rows ("Sessions"/"Worktrees") — no third nesting level.
- Filter modes (All/Live/Worktrees).
- Numeric jump (`⌘1…9` to the Nth session).
- Drag-reorder, or any change to what clicking a non-session row does.

## Design

### 1. Node model & child ordering

The tree stays two levels deep (`RepoNode` → children). One new payload type
joins the sealed `SidebarNode` interface:

```java
record StaleWorktreesNode(List<WorktreeService.Worktree> worktrees,
                          Repository repository) implements SidebarNode { }
```

`childNodesFor(Repository)` is rewritten to **classify, then sort into bands**
rather than emit rows in `git worktree list` order. The matching of sessions to
worktrees (main checkout vs. `worktreeRoot().equals(path)`, plus orphan
sessions whose worktree dir is gone) is preserved from the current
implementation; only the ordering and the stale grouping change.

Output order:

1. **Live sessions** — `SessionStatusStyles.isRunning(status)` is true
   (`RUNNING`/`STARTING`). A session whose `activityOf(...)` is
   `NEEDS_ATTENTION` is pinned to the top of this band.
2. **Idle sessions** — every other session (includes the main-checkout
   session).
3. **Open worktrees** — `UnopenedWorktreeNode`s that are **not** stale.
4. **Stale bucket** — a single `StaleWorktreesNode` collecting all stale
   worktrees, or omitted entirely when there are none.

Sort keys:

- Bands 1–2: `lastOpenedAt` descending (most recent first); `NEEDS_ATTENTION`
  overrides to the front of band 1.
- Band 3: branch name, case-insensitive; the main checkout's own unopened row
  (when it has no session) sorts first. A branch-less worktree
  (`branch().isEmpty()` — e.g. a locked detached checkout) sorts after the
  named ones, by path.

### 2. Stale rule

The rule is **exemptions first, then the staleness test** — the exemptions win,
so a worktree that carries a session (running or idle) can **never** be folded
into the hidden bucket. Evaluated in order:

1. **Never stale** if `worktree.locked()`, `worktree.mainCheckout()`, **or a
   session is checked out on it** (matched the same way `childNodesFor` matches
   today). This clause takes precedence over everything below — a `prunable`
   directory under a live session stays a visible session row.
2. Otherwise **stale** if `worktree.prunable() || worktree.detached()`.
3. Otherwise not stale (a normal open-worktree row).

Written as one guarded expression:

```
!locked && !mainCheckout && !hasSession && (prunable || detached)
```

This is the rule that folds the many `(detached)` rows into one
`▸ N stale worktrees` line. Note the earlier boolean draft
(`prunable || (detached && no session)`) was ambiguous: it left the `prunable`
clause ungated on session presence and could be read to hide a live session's
worktree. The exemption-first ordering above resolves that — session presence
is checked before, and overrides, the staleness test.

### 3. Row anatomy

Every child row uses a **fixed two-column grid**: a fixed-width *status column*
followed by the *content column*. Dots, worktree glyphs, and text baselines
align vertically down the entire tree regardless of row type. This replaces the
per-row `setPadding(new Insets(5, 8, 5, 16))` calls (`RepositorySidebar.java`
~1216 and ~1289) and the `.repo-tree` 1px left-rule; indent becomes a single
CSS-driven gutter width, applied uniformly.

Three distinct shapes:

**Session row** — 2 lines, status dot in the status column.
- Line 1: `● name` + branch tag (`⎇ main` dim for the current checkout,
  `◫ feat/x` accent for a worktree checkout) + dirty dot when dirty.
- Line 2: relative time; PR chip / `waiting` badge / `Resume` pill
  right-aligned (unchanged semantics).
- **Filled dot = live, hollow dot = idle**, so band membership stays legible
  mid-scroll. This is **new rendering**, not just a pseudo-class:
  `SessionStatusStyles.createDot` today always draws a solid
  `-fx-background-color` fill, so an idle variant needs a border-only shape
  (transparent fill + colored 1–1.5px ring). Give `createDot` an explicit
  `filled` parameter (or a sibling `createIdleDot`) rather than overloading a
  CSS pseudo-class. **Error color overrides hollow:** a `FAILED` /
  `MISSING_WORKING_DIRECTORY` session that is not running (idle band, `:error`)
  renders as a *filled* error-colored dot, not a hollow one — the failure
  signal must not be weakened by the idle treatment. Running color and the
  pulse from `createDot` are otherwise retained.
- Active-session highlight (`.active` background) unchanged.

**Open-worktree row** — 1 line, **no status dot**. The status column holds a
dim `◫` glyph; the absence of a dot is the primary signal that this is a
worktree, not a session.
- `◫ branch` + short path; `Start ▸` pill; hover `🗑` (delete worktree & branch)
  except on the main checkout.

**Stale bucket row** — 1 line, collapsible, collapsed by default.
- `▸ N stale worktrees` with a `Clean ↺` action that removes them.
- **Batch semantics** (the existing per-worktree path,
  `onDeleteUnopenedWorktree` → `confirmForcedWorktreeDelete`, is
  *per-item* — prunable ones auto-force silently, but a dirty
  detached-and-unmatched worktree throws `WorktreeNotCleanException` and today
  pops one `Alert` each). `Clean ↺` must not inherit that: it shows **one
  confirm** naming the count, removes the cleanly-removable worktrees, and any
  that would need a forced/dirty removal are **reported and skipped, never
  silently force-deleted** (no unattended data loss). A follow-up line/toast
  states "removed X, skipped Y (uncommitted changes)"; skipped worktrees remain
  in the bucket for individual handling.
- Expands **in place** to plain dim `path` rows (no pills, no per-row actions).
- Expansion uses the hand-rolled caret pattern but needs its **own expansion
  state, not the repo-level `collapsed` set** (`Set<RepositoryId>`, keyed by
  repo and meaning "repo collapsed"). A repo must be expandable while its stale
  bucket stays collapsed, so the bucket gets a separate
  `staleBucketExpanded` set keyed by `RepositoryId` (a repo has at most one
  bucket). It does not use a nested `TreeItem` level.

The glyph budget per row drops: the **dot** carries live/idle, the
**`◫`/`⎇`** carries worktree-vs-checkout, and caret/pills stay. No single row
renders all of `⎇ ◫ ▶ ■ × 🗑` simultaneously.

### 4. Repo header & count agreement

The header counts are rederived from the **same classified child list**
produced in §1 (not from the separate `additionalWorktreeCount` /
`viewModel.worktrees()` read that `repoMetaText` uses today), so header numbers
and visible rows are computed from one source. The meta line reads:

```
⎇ main *  ·  3 wt · 1 stale
```

- base branch + dirty `*` marker (existing `branchTextFor`);
- **`N wt`** = non-main checkouts = open-worktree rows (band 3) +
  worktree-backed session rows (bands 1–2 with `worktreeRoot().isPresent()`).
  This explicitly **includes** worktrees that carry a session, so the count
  does not drop when a worktree row is replaced by its session row — that
  substitution is exactly what produced the `olifer` `0 worktrees` symptom.
- **`N stale`** = size of the stale bucket, shown **only when N > 0**. Stale
  worktrees are counted here and **not** in `N wt`, so `wt + stale` never
  double-counts and the collapsed bucket hiding rows does not make `N wt`
  lie about what is visible when expanded.

**Header is split into separate `Label` nodes, not one string.** Today
`repoMetaText` returns a single combined `String` rendered by one `Label`
(`RepositorySidebar.java:1051`), which ellipsizes uniformly — "branch shrinks,
counts don't" is impossible in that layout. `buildRepoRow` is therefore
restructured: the meta line becomes an `HBox` of a **branch `Label`**
(`HBox.setHgrow(..., ALWAYS)`, `textOverrun = ELLIPSIS`, allowed to shrink) and
a **counts `Label`** (fixed, `minWidth = USE_PREF_SIZE`, never shrinks). The
branch ellipsizes first; the counts always survive. (This adds `buildRepoRow`
to the touched surface — see Files touched.)

The right-edge numeric badge stays as the repo's total session count and is
kept out of the ellipsize path.

### 5. Keyboard navigation

`⌘↓` / `⌘↑` cycle selection through **live sessions only** (band 1),
top-to-bottom across all repos, wrapping at the ends. Landing on a session
expands its repo, scrolls it into view, and opens it (identical to clicking the
row). Idle sessions, worktrees, and stale buckets are skipped.

Registered in `ShortcutsOverlay` as `Next / previous live session — ⌘↑ / ⌘↓`
and actually bound (AGENTS.md: advertised ⟺ bound). Binding site: the global
`cmd`-key filter in `DrydockApplication` (~line 528), where the existing
bracket handlers live. No collision — verified against actual bindings, not the
overlay text: view switching is `⌘1–4`; tab cycling is plain **`⌘[` / `⌘]`**
(`DrydockApplication.java:528` `cmd && OPEN_BRACKET` / `CLOSE_BRACKET`), not the
`⌘⇧[ / ⌘⇧]` the overlay currently *advertises*; `⌘↑`/`⌘↓` are unbound.

**In-scope cleanup:** that overlay entry (`ShortcutsOverlay.java:28`,
`"⌘⇧[ / ⌘⇧]"`) is a pre-existing advertised≠bound violation of the same
AGENTS.md rule this change relies on. Correct it to `⌘[ / ⌘]` while editing the
overlay, so the new shortcut is not landing next to a stale advertisement.

## Testing

The sidebar has no unit tests and its FX cells are awkward to test. Extract the
two **pure** pieces into static, toolkit-free helpers and cover them with plain
JUnit:

- `classifyChildren(worktrees, sessions, activity)` → a value object holding the
  four ordered bands, the stale bucket, and the derived counts (`wt`, `stale`,
  sessions). It takes **already-resolved** inputs — the worktree list, the
  sessions, and an `activity` lookup passed in (a `Map` or
  `Function<SessionId, SessionActivity>`), so no `viewModel`/FX dependency
  leaks in and the helper stays toolkit-free. Tests:
  - band order (live → idle → open worktree → stale);
  - `lastOpenedAt` descending within a band; branch-less worktrees sort last by
    path in band 3;
  - `NEEDS_ATTENTION` pinned to the front of band 1;
  - stale rule (exemption-first, §2): prunable-no-session → stale;
    detached-no-session → stale; **prunable *with* a session → never stale, row
    stays a session**; locked → never; main checkout → never;
  - the counts on the returned object: `wt` includes worktree-backed sessions,
    `stale` is disjoint from `wt`, and both match the emitted rows.
- **Count agreement is a property of the returned object, not of two functions
  agreeing.** The header reads `result.wt()` / `result.stale()` and the rows are
  `result` 's bands — same object, so they cannot diverge by construction. The
  `olifer` symptom came from a *second, independent* count path
  (`additionalWorktreeCount` vs. `childNodesFor`, with the `worktrees == null`
  pending-discovery short-circuit in between). That second path is **deleted**,
  not merely tested around — the regression guarantee is structural. The
  pending-discovery state (worktrees not yet loaded) is out of scope for
  `classifyChildren` (which requires a resolved list); the spec's claim is
  narrowed to: *once discovery has run, header and rows agree because they are
  the same object.* While discovery is pending the header shows no worktree
  counts at all (as today), never a wrong one.

Row construction stays visual and is **verified by running the app and
inspecting the sidebar on screen** (per standing preference — launch it,
expand a repo with many worktrees, confirm the stale bucket, banding, grid
alignment, and `⌘↑/⌘↓` cycling), compared against the captured
before-screenshot.

## Files touched

- `app/src/main/java/app/drydock/ui/RepositorySidebar.java` — new
  `StaleWorktreesNode`; rewritten `childNodesFor` delegating to the extracted
  `classifyChildren` (banding + stale bucket + counts); the three child row
  builders reworked onto the fixed grid; `buildRepoRow` restructured so the
  meta line is a branch `Label` + a non-shrinking counts `Label` fed from
  `classifyChildren`; **deletion of the separate `additionalWorktreeCount` /
  `repoMetaText`-string count path** that caused the mismatch; a
  `staleBucketExpanded` `Set<RepositoryId>` distinct from the repo `collapsed`
  set, with caret handling for the bucket; `Clean ↺` batch handler (one confirm,
  skip-dirty); `⌘↑/⌘↓` handling.
- `app/src/main/java/app/drydock/ui/SessionStatusStyles.java` — `createDot`
  gains a `filled` variant (hollow/ring for idle; error color stays filled).
- `app/src/main/java/app/drydock/DrydockApplication.java` — the `⌘↑/⌘↓` bindings
  in the existing `cmd`-key filter (next to the bracket handlers).
- `app/src/main/java/app/drydock/ui/model/WorkspaceViewModel.java` — only if the
  classification needs a new accessor; no state-model change expected.
- `app/src/main/java/app/drydock/ui/ShortcutsOverlay.java` — the new shortcut
  row, plus the `⌘⇧[ / ⌘⇧]` → `⌘[ / ⌘]` advertised≠bound correction.
- `app/src/main/resources/app/drydock/ui/app.css` (+ theme files) — the fixed
  gutter/grid, hollow-vs-filled dot, stale-bucket and open-worktree row styles;
  removal of the inline-padding-driven indent.
- New test class under `app/src/test/java/...` for `classifyChildren` (bands,
  stale rule, sort keys, and the derived counts).
