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
  (when it has no session) sorts first.

### 2. Stale rule

A worktree is **stale** when:

```
worktree.prunable() || (worktree.detached() && no matching session)
```

and is **never** stale when `worktree.locked()` or `worktree.mainCheckout()`,
or when a session is checked out on it. This is the rule that folds the many
`(detached)` rows into one `▸ N stale worktrees` line.

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
  mid-scroll. (Running/error coloring and the pulse from
  `SessionStatusStyles.createDot` are retained; "filled vs hollow" is the new
  idle distinction.)
- Active-session highlight (`.active` background) unchanged.

**Open-worktree row** — 1 line, **no status dot**. The status column holds a
dim `◫` glyph; the absence of a dot is the primary signal that this is a
worktree, not a session.
- `◫ branch` + short path; `Start ▸` pill; hover `🗑` (delete worktree & branch)
  except on the main checkout.

**Stale bucket row** — 1 line, collapsible, collapsed by default.
- `▸ N stale worktrees` with a `Clean ↺` action that prunes them (wired to the
  existing worktree-removal path; a single confirm covers the batch).
- Expands **in place** to plain dim `path` rows (no pills, no per-row actions).
- Expansion follows the existing hand-rolled caret + `collapsed` set pattern;
  it does not use a nested `TreeItem` level.

The glyph budget per row drops: the **dot** carries live/idle, the
**`◫`/`⎇`** carries worktree-vs-checkout, and caret/pills stay. No single row
renders all of `⎇ ◫ ▶ ■ × 🗑` simultaneously.

### 4. Repo header & count agreement

`repoMetaText(Repository)` is rederived from the **same classified child list**
produced in §1, so header numbers and visible rows cannot disagree (this closes
the `olifer` `0 worktrees` bug). The meta line reads:

```
⎇ main *  ·  3 wt · 1 stale
```

- base branch + dirty `*` marker (existing `branchTextFor`);
- worktree count (open worktrees + worktree-backed sessions, i.e. non-main
  checkouts);
- ` · N stale` **only when N > 0**.

**Width priority:** the counts are what a collapsed repo needs to advertise, so
when the line cannot fit, the **branch label ellipsizes first and the counts
never do**. (Today the branch eats the counts — see `btrace`'s
`⎇ agent/issue-881-release-gates * · 14...`.)

The right-edge numeric badge stays as the repo's total session count and is
kept out of the ellipsize path.

### 5. Keyboard navigation

`⌘↓` / `⌘↑` cycle selection through **live sessions only** (band 1),
top-to-bottom across all repos, wrapping at the ends. Landing on a session
expands its repo, scrolls it into view, and opens it (identical to clicking the
row). Idle sessions, worktrees, and stale buckets are skipped.

Registered in `ShortcutsOverlay` as `Next / previous live session — ⌘↑ / ⌘↓`
and actually bound (AGENTS.md: advertised ⟺ bound). No collision: view
switching uses `⌘1–4`, tab cycling uses `⌘⇧[ / ⌘⇧]`, `⌘↑/⌘↓` are free.

## Testing

The sidebar has no unit tests and its FX cells are awkward to test. Extract the
two **pure** pieces into static, toolkit-free helpers and cover them with plain
JUnit:

- `classifyChildren(worktrees, sessions, activity)` → the four ordered bands +
  stale bucket. Tests:
  - band order (live → idle → open worktree → stale);
  - `lastOpenedAt` descending within a band;
  - `NEEDS_ATTENTION` pinned to the front of band 1;
  - stale rule: prunable → stale; detached-and-no-session → stale;
    locked → never; main checkout → never; session present → never;
  - header/row count agreement derived from one classification.
- `repoMetaText(...)` computed from that same classification — a regression
  test that the `olifer` 0-vs-1 mismatch cannot recur.

Row construction stays visual and is **verified by running the app and
inspecting the sidebar on screen** (per standing preference — launch it,
expand a repo with many worktrees, confirm the stale bucket, banding, grid
alignment, and `⌘↑/⌘↓` cycling), compared against the captured
before-screenshot.

## Files touched

- `app/src/main/java/app/drydock/ui/RepositorySidebar.java` — new
  `StaleWorktreesNode`, rewritten `childNodesFor`, extracted `classifyChildren`,
  three row builders reworked onto the grid, `repoMetaText` rederivation, caret
  handling for the stale bucket, `⌘↑/⌘↓` binding.
- `app/src/main/java/app/drydock/ui/model/WorkspaceViewModel.java` — only if the
  classification needs a new accessor; no state-model change expected.
- `app/src/main/java/app/drydock/ui/ShortcutsOverlay.java` — the new shortcut row.
- `app/src/main/resources/app/drydock/ui/app.css` (+ theme files) — the fixed
  gutter/grid, filled-vs-hollow dot, stale-bucket and open-worktree row styles;
  removal of the inline-padding-driven indent.
- New test class under `app/src/test/java/...` for `classifyChildren` /
  `repoMetaText`.
