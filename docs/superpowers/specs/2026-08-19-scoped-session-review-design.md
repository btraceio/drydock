# Scoped session review

Review stops being a place you go and becomes something a session has.

## 1. Why

The Review destination is a pinned, always-on tab holding a cross-repo queue.
Three things are wrong with it:

- It is present whether or not anything is being reviewed, and it is the only
  tab that cannot be closed.
- Its queue re-derives, in its own vocabulary, a list the sidebar already
  shows: worktrees, sessions, and the pull requests attached to them. Two
  lists of the same things, kept in sync by nothing.
- Getting from "this worktree looks done" to "show me its diff" means leaving
  the session, finding the same worktree again in a second list, and losing
  the tab you were on.

What replaces it: review is invoked **from the row you are already looking
at**, opens **inside that session's tab**, and is scoped to **exactly that
worktree or its pull request**. Pull requests that have no local checkout yet
appear in the sidebar as virtual rows, and opening review on one materializes
the worktree and the session on the spot.

## 2. Scope of this change

In scope: the review entry points, where the review surface lives, the
per-session scope model, the sidebar's pull-request rows and badges, the
materialize flow, and the reviewer dispatch.

Out of scope: the review board's own behaviour — the diff column, intent
rail, findings margin, verdict bar, submit sheet and the `review_*` MCP tool
schema are all carried over unchanged. This change moves the board; it does
not redesign it.

## 3. Model

### 3.1 Scopes are unchanged

`ReviewScope` and `ReviewScopeRegistry` survive exactly as they are. Scope
identity is (kind, repository root, worktree, PR number); every finding,
thread, draft and verdict is keyed by scope id. Changing identity would
orphan every review ever recorded, so it is not changed.

`ReviewScope.Kind` keeps every constant. `WORKTREE`, `WORKING_TREE` and `PR`
are minted as described in §3.2; `BRANCH` and `STACK` become unreachable
(nothing mints them, and `BRANCH` already was) but are not removed —
persisted annotations may still name them, and a lenient decode must not fail
on one.

### 3.2 Two narrow services replace the queue

`ReviewQueueService`, `QueueAssembly` and `ReviewItem` are deleted. What they
did splits in two:

**`SessionReviewScopes`** — given a checkout root and its repository,
resolves the scopes one session offers:

- a **local scope**: everything this checkout has that its base does not —
  the branch against its merge-base, uncommitted work included. One scope,
  not two: committed-versus-uncommitted is not a choice a reviewer wants to
  make on every visit. On a session running in the repository's *main*
  checkout, where there is no feature branch to diff, it is the working tree
  alone.
- a **PR scope**, when the checkout's head branch (or the `pr-<n>` alias)
  carries an open, non-draft pull request.

**The kinds minted are exactly the ones already in use**, which is what makes
the survival claim in §9 true rather than hopeful: a local scope on a
worktree mints `WORKTREE`, a local scope on the main checkout mints
`WORKING_TREE`, and a PR scope mints `PR` carrying its worktree — the same
three the queue and `openCheckedOutPr` mint today, for the same places.

One easily-missed part of that: scope identity includes the PR number, and
the queue mints a PR-holding worktree as `WORKTREE` **carrying its
`PullRequestRef`** — but only when the checkout's branch is the `pr-<n>`
alias drydock's own checkout creates (`PrCheckoutService.pullRequestNumberOf`
requires a literal `pr-` plus digits). A worktree on `feat/x` that happens to
have an open pull request is minted with an **empty** ref; only its *base* is
taken from the PR.

The local scope reproduces that rule exactly: it carries the ref when the
branch is the `pr-<n>` alias, and not otherwise. The wider rule — carry the
ref whenever a PR exists — was considered and is wrong in a way worth
recording, because it looks safer: identity would then flip the moment
someone *opened* a pull request on a branch, and flip back when they merged
it, so a session's findings would vanish and reappear on events that have
nothing to do with the worktree. A `WORKING_TREE` scope on the main checkout
never carries a ref at all, matching the queue.

This is the failure the registry's Javadoc was written about, and the narrow
rule is the one that avoids it.

That a checked-out PR therefore has *two* handles — `WORKTREE` for the
checkout and `PR` for the pull request — is today an accident of two code
paths minting the same place differently. Here it is the point: they are the
switcher's two chips.

Resolution is async (git and `gh`), cached per checkout, and invalidated by
the same signals that already invalidate git status.

**`RepositoryPullRequests`** — per repository, the open non-draft pull
requests that have **no** local worktree. These are the sidebar's virtual
rows. A PR whose branch is checked out locally is deliberately absent here:
it is a badge on the real worktree row, and appearing in both places is how
two lists of the same thing start diverging again.

`GhCliService.OpenPullRequest` grows `title`, `isDraft`, `author` and `url`;
the `--json` field list widens on a call that is already being made.

### 3.3 The view model carries the scan

`WorkspaceViewModel` gains a per-repository pull-request list, with the same
diffed-event treatment every other collection gets: a changed list re-renders
the affected rows, not the tree. The sidebar renders from the model; nothing
in the sidebar calls `gh` directly.

## 4. Where review lives

### 4.1 A fourth sub-tab

`OpenSessionTab.SubTab` gains `REVIEW`: `Claude | Terminal | Explorer |
Review`, on `⌘4`. It is an FX view, so selecting it hides the native
terminal surface through the existing visibility path — structurally the
same as `EXPLORER`, which is why no new native-surface work is needed.

`Shortcut.REVIEW_SUB_TAB` already exists and is already intercepted by the
terminal surfaces. It stops escaping to the global tab and becomes
`showSubTab(SubTab.REVIEW)`. `⌘4` is no longer a toggle to somewhere else;
it is the fourth of four consistent sub-tab keys.

The sub-tab button carries an `◨n` badge: open findings for the selected
scope. Absent rather than zero when no reviewer has run — a confident zero
reads as "reviewed, nothing found".

### 4.2 Built late, disposed with the tab

The review view is constructed on first visit to the sub-tab, not at tab
creation: a diff column per open session, eagerly, is a cost nobody asked
for. It is registered in the workspace's tab tracking the moment it exists,
so close-one, close-all and shutdown all find it.

### 4.3 `SessionReviewView`

Extracted from `ReviewDestinationView` rather than rewritten — that file
records a series of fixes (the scroll restore, the base/head identity bug,
the rebuild on late diff arrival) that would otherwise be re-derived the
hard way.

What moves in: the intent rail, diff column, findings margin, verdict bar,
submit sheet, MCP activity panel, and the shortcut handling for all of them.

What does not: the queue rail, the queue title bar and its back-target, and
the narrow-width `BROWSE`/`DETAIL` paging. That paging exists because a
fourth column (the queue) made the layout impossible below 980px; with three
columns `RailLayout` shrinks rails the way it was designed to. The minimum
width falls out of the view with the column that caused it.

What is new: the scope switcher.

### 4.4 The scope switcher

At the top of the view, chips rather than a dropdown, so the existence of a
pull request is visible without opening anything:

```
( Local changes ) ( PR #42 ◨3 )
```

One chip when there is no PR. Switching re-renders the board against the
other scope; each scope keeps its own findings, verdicts and scroll
position, because each is a separate scope id and always was. The chip a
session last had selected is persisted in `WorkspaceUiState` (lenient
cosmetic decoding), so `⌘4` returns where you were.

## 5. Entry points

Four gestures — right-click, the PR chip, the findings badge, `⌘4` — all
resolving to one destination: *open-or-focus the session tab, select the
Review sub-tab, select a scope*.

| Gesture | Scope selected |
|---|---|
| Right-click a row → `Review ▸ Local changes` | local |
| Right-click a row → `Review ▸ PR #42` (only when a non-draft PR exists) | PR |
| Click the `◨PR#42` chip on the row | PR |
| Click the `◨n` findings badge on the row | local |
| `⌘4` inside a session | last used, local by default |

`WorkspaceNavigator.showReview()` and `showReviewForCheckout(Path)` are
replaced by one method taking a session id and a scope preference.

A row with **no session** (an unopened worktree) invoking review goes through
the Start-session modal first, then lands in the sub-tab — the same path as a
virtual PR row, minus the checkout step.

## 6. Pull requests in the sidebar

### 6.1 The group

Inside each local repository, below its worktrees:

```
▾ drydock                      3 wt
  ▸ my-feature       ● claude  ◨PR#42
  ▸ fix/parser       ○ codex
  ▸ PULL REQUESTS (7)
```

Collapsed by default, so a busy repository does not bury the worktrees.
Remote repositories are skipped, as they are everywhere else.

### 6.2 The scan

`gh pr list --state open --json number,title,headRefName,baseRefName,isDraft,author,url`,
per local repository, on the triggers the sidebar already has: repository
added, repository expanded, the `⟳` rescan, and once after a session's PR
state changes. No polling.

Filtered out: drafts, and any PR whose head branch or `pr-<n>` alias already
has a local worktree.

Degradation is explicit, because absent and broken must not look the same:

- `gh` not installed → no group at all, logged at DEBUG.
- `gh` present and failing → the group renders carrying `PRs unavailable ·
  retry`, with the stderr excerpt logged at WARNING.

### 6.3 Materialize

Opening review on a virtual PR row:

1. **Start-session modal**, prefilled: harness picker, name from the PR
   title, branch `pr-<n>` (`PrCheckoutService.localBranchFor`).
2. **Busy modal** — `Checking out PR #42…` — running the existing
   `PrCheckoutService`: `git worktree add --detach` into the repository's
   configured worktree location, then `gh pr checkout <n> --branch pr-<n>`
   *inside* that worktree. The main checkout is never touched.
3. **Session starts** with the chosen harness, through the existing
   `SessionManager` path.
4. **The PR scope is minted and granted** to the new session, so its agent
   can address it over MCP.
5. **The tab opens** on its Review sub-tab with the PR scope selected. The
   virtual row is gone — dedup now sees a worktree — and a real session row
   with a `◨PR#42` badge takes its place.

The row is disabled while its own materialize is in flight, so a double
click cannot start two. Every step clears the busy modal on success, failure
and early return.

Failure is partial, and honestly so:

- **Checkout fails** → nothing is left behind (`PrCheckoutService` already
  removes the half-made worktree before reporting), error via `UiErrors`.
- **Session fails to start** → the worktree stays and appears as an ordinary
  unopened worktree row. It is a valid worktree and one click from being
  started again; rolling it back silently would throw away a completed
  network fetch.

`ReviewCheckoutGate` is deleted: this flow is its job, reached from the row
instead of from inside a queue. The "read the patch only" second door goes
with it — reviewing a pull request always gets a real checkout.

## 7. Who reviews

The session's own agent, dispatched **as a subagent where the harness
supports it**.

`AgentCapabilities` gains `supportsSubagents` — Claude `true`; Codex and Pi
`false` today. It is provider-declared, so a harness that gains subagents
later is a one-line change.

`reviewInstruction(scope)` becomes two forms, chosen by that flag:

- **Subagent form**: dispatch a code-review subagent for the scope handle;
  it calls `review_state` first, then posts `review_intents` and
  `review_finding`. The review is read in a context that never held the
  authoring conversation, and the main session's context does not absorb the
  whole diff.
- **Inline form**: today's single-line instruction, unchanged.

Both go through `TerminalBridge.sendPrompt`, as today. A harness whose
`mcpDelivery` is `NONE` still reviews through its terminal but cannot post
findings back — unchanged, and unchanged in being stated rather than hidden.

## 8. MCP surface

The `review_*` tool schema does not change. What changes is who may address
what: **a session may address the scopes of its own checkout**.
`McpSessionContext.reviewScope(scopeId, caller)` loses its "plus any scope a
human granted with Run review" branch.

The grant mechanism goes entirely — `grant`, `revokeGrant`, `grantsFor` and
the registry's `grants` map. An earlier draft of this section kept it for the
materialize flow, on the theory that a freshly checked-out PR needed its
session granted the new scope. That was wrong: `SessionReviewScopes.forCheckout`
mints both scopes with the session id already bound, so the grant was handing a
session a scope it was bound to anyway. `isAddressableBy` reduces to
`scope.sessionId().equals(caller)`.

This is a tightening: an agent could previously be handed a handle to a
checkout it does not live in.

## 9. Existing reviews

Findings survive. They are keyed by scope id, and scope identity is
untouched, so every finding recorded against a worktree or a checked-out PR
reappears in that session's Review sub-tab.

Annotations recorded against queue-only scopes — a `STACK`, or a PR that was
never checked out — remain on disk unreferenced. Harmless, and
re-materializing that PR mints the same handle, which brings them back.

## 10. Deletions and additions

**Deleted**: `ReviewDestinationView`, `ReviewQueueRail`, `ReviewCheckoutGate`,
`ReviewEmptyState`, `ReviewQueueService`, `QueueAssembly`, `ReviewItem`, the
pinned review tab with its badge and back-target machinery,
`WorkspaceNavigator.showReview()` and `showReviewForCheckout(Path)`, the whole
grant mechanism in `ReviewScopeRegistry`, and — with their last callers —
`GhCliService.listReviewRequests`, `prDiff` and `listOpenPullRequests`.

**A capability this removes, stated plainly:** "pull requests where a review
was requested of *me*, aggregated across every repository" was sourced from
`listReviewRequests` and has no replacement. The sidebar lists all open
non-draft pull requests per repository — a superset within one repository, but
the requested-of-me signal and the cross-repo roll-up are gone. Reaching an
un-added repository's pull requests means adding that repository first.

**Added**: `SessionReviewView`, `SessionReviewScopes`,
`RepositoryPullRequests`, `SidebarNode.PullRequestNode`, `SubTab.REVIEW`,
`AgentCapabilities.supportsSubagents`, and the pull-request list on
`WorkspaceViewModel`.

**Changed**: `GhCliService.OpenPullRequest` (four more fields),
`OpenSessionTab` (a fourth sub-tab), `RepositorySidebar` (the group, the
rows, the context menu, the badge targets), `MainWorkspace` (loses the review
tab, gains per-session review hosting).

## 11. Verification

Headless tests:

- `SessionReviewScopes`: local-only, local + PR, PR alias branch, no `gh`.
- `RepositoryPullRequests`: drafts excluded, worktree-backed PRs excluded,
  `gh` missing vs `gh` failing as distinct outcomes.
- Sidebar node assembly: the group appears, collapses, dedups, and disappears
  when empty.
- Materialize: both failure paths leave the documented state, and the
  in-flight row cannot be started twice.
- Reviewer dispatch: subagent form for a capable provider, inline form
  otherwise.
- Scope persistence round-trips through `WorkspaceUiState`, and a malformed
  entry is skipped rather than failing the load.

In the running app, with screenshots rather than assertions about them:

- The four-button sub-tab strip at a realistic window width — the strip has
  truncated before.
- The collapsed `PULL REQUESTS (n)` group, and one expanded.
- One materialize, end to end, from virtual row to review board.

## 12. Risks

- **`ReviewDestinationView` is 2095 lines.** The extraction is the largest
  single piece of work here and the easiest place to drop a behaviour. It is
  a move, file by file, not a rewrite.
- **`gh` latency** on repositories with many pull requests. Mitigated by the
  group being collapsed (the rows are built but not laid out), the scan being
  event-driven rather than polled, and the result living in the view model
  rather than being re-fetched per render.
- **Sidebar density.** A repository now carries worktrees *and* a PR group.
  The group is collapsed by default and counts in its label, so the cost when
  closed is one row.
