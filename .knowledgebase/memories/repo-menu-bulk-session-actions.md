---
id: repo-menu-bulk-session-actions
title: Repo context-menu bulk session actions — stale/inactive definitions and the live-count-label invariant
kind: finding
tags: [sidebar, session, context-menu, bulk-actions, stale, RepositorySidebar]
applies_to: [app/src/main/java/app/drydock/ui/RepositorySidebar.java, app/src/main/java/app/drydock/ui/SidebarChildren.java]
source: impr_session_mgmg/find-stale-session-definition
status: active
recorded: 2026-09-25
valid_at: 2026-09-25
---


The repository right-click menu (`RepositorySidebar.repoMenu`) carries two
bulk session actions. Their definitions are deliberate and must be preserved
when editing them:

- **Close inactive sessions** — closes terminal surfaces of sessions in the
  `SessionStatusFacet.IDLE` band (INACTIVE/EXITED only — NOT ERROR). Reversible:
  keeps metadata, a resume reopens the tab, never stops a running process.
  Uses `WorkspaceNavigator.closeSession` (no-op future if no surface open).
- **Delete all stale sessions** — deletes sessions that are stale per
  `SidebarChildren.staleSessions()`: **idle OR on a merged/prunable/detached
  (non-main, non-locked) worktree**. `SessionManager.deleteSession` stops a
  running session first, then removes metadata. The agent's on-disk
  conversation history is intentionally NOT deleted.

Invariants that make the feature honest:

- **The label count is the consistency contract.** Each item's label carries
  a live count (`Delete all stale sessions (8)`) relabelled on every
  `menu.setOnShowing` — the menu instance is cached for the row's life while
  the set moves underneath it. The count is computed from the *same*
  `staleSessions()` / idle set the action operates on, so the number always
  matches the rows deletable by hand. Do not introduce a second, divergent
  count source.
- **The active session is excluded from both sets** so the gesture never
  yanks the focused tab. The delete confirm names how many targets are still
  running and notes when the active session is itself stale but kept.
- **Locked precedes stale; main is never stale-by-branch** (mirrors
  `bucket()`): a session on a locked+merged worktree is not stale-by-branch
  (still stale if idle); the main-checkout session is stale only via the idle
  arm.
- **The header `· N stale` count is intentionally left as a worktree count**
  (session-less only). Do not fold sessions into it — it mixes units
  (`· N wt · N locked · N stale`). The menu label is what carries the
  session count.
- Items are added for all repos including remote (remote has no worktree
  discovery, so stale = idle-only there) and disabled when their target set
  is empty.

"Inactive" deliberately excludes ERROR (FAILED / MISSING_WORKING_DIRECTORY /
UNSUPPORTED_AGENT): those may be worth inspecting before closing.
