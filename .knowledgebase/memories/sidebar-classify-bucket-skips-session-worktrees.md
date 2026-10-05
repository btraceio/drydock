---
id: sidebar-classify-bucket-skips-session-worktrees
title: SidebarChildren.bucket() only classifies session-less worktrees; a worktree with a session renders as a session row and skips the bucket path
kind: finding
tags: [sidebar, worktree, session, SidebarChildren, classification]
applies_to: [app/src/main/java/app/drydock/ui/SidebarChildren.java]
source: impr_session_mgmg/find-stale-count-discrepancy
check: grep -n "bucket(" app/src/main/java/app/drydock/ui/SidebarChildren.java | grep -q "else"
status: active
recorded: 2026-09-25
valid_at: 2026-09-25
---


`SidebarChildren.classify()` loops over worktrees and, for each, looks for a
matching session. **Only worktrees with no matching session fall through to
`bucket()`**; a worktree that has a session is added to `sessionRows` and
`placed`, and never enters `bucket()`. `bucket()` is where the
merged/prunable/detached/locked/main tests live, so any per-worktree
classification or count computed there applies to **session-less worktrees
only**.

Consequence for counts: the repo header's `· N stale` (and `N wt`, `N
locked`) reflects only session-less worktrees. A session sitting on a
fully-merged (or prunable/detached) branch renders as a session row and is
invisible to those counts — so the stale count can read far lower than the
number of sessions a user can actually clean up by hand.

If you add a new worktree-based facet or count in `bucket()`, session-backed
worktrees will not get it unless you also extend the classification onto the
session-row path (the `staleSessions` component does this for the
session-staleness case: idle OR merged/prunable/detached, non-main,
non-locked). The merged-branch detection itself (`WorktreeService.markMerged`
/ `git branch --merged`) is correct — the gap is purely that its result is
not consulted for session-backed worktrees on the bucket path.
