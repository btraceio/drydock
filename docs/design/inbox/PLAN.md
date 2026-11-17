# Attention Inbox — implementation plan

Scope: `intellij-plugin/` tool window only (spec: `SPEC.md`). View rewrite over
the existing `TaskTreeInputs` gather, `TaskTreeModel` rows and the delayed-task
snapshot; no new services except one project-level state component.

## Decisions (confirmed 2026-11-13)

| Question | Decision |
|---|---|
| Flagged | Pinned **Flagged** group above Running; a flagged session shows there instead of its state group. |
| Inactive | **Idle** group after Ready to start, collapsed by default: stopped, not-done sessions. |
| Done today | New `done_at` front-matter timestamp, written by Drydock's own transition to `done`. Tasks without it (older, or hand-edited to `done`) appear only via `is:done`. |
| Project counts | "N issues · M PRs" shows only once the repo's listing was loaded (cached); nothing eager. |
| Other windows | Running sessions from every IDE window are shown; location goes to the tooltip (spec §6 proposal, not contested). |

## Group sources

| Group | Rows |
|---|---|
| Needs you | `TaskRow` running ∧ activity `NEEDS_ATTENTION` (today's `NEEDS_YOU` predicate) |
| Flagged | `TaskRow` with `session.flagged()`, not already in Needs you |
| Running | other running `TaskRow`s, any window |
| Blocked | `DelayedTaskRow` presentation `BLOCKED` |
| In review | `DelayedTaskRow` presentation `REVIEW` |
| Ready to start | `DelayedTaskRow` presentation `READY` and `INTERRUPTED` |
| Idle | stopped `TaskRow`s (today's `INACTIVE` predicate) |
| Done today | `DelayedTaskRow` `TERMINAL`/done with `done_at` today |

A `STARTED` task is represented by its session's row (it is nested under it
today); its title goes to that row's tooltip. Worktrees without a session
(idle/stale) move to the Projects view only.

## Steps

Each step: `./gradlew test` (and `buildPlugin`) in `intellij-plugin/`, then a
`runIde` launch for the user's visual check (I cannot see the IDE window).

1. **`done_at`** — `TaskDocument` field, reader tolerant of absence, written on
   the Drydock-performed transition to `done`; core tests.
2. **`InboxModel`** — pure projection from the built rows + task layer: groups,
   truncation (4 / never for Needs you), text + `project:` + `is:` filters,
   quiet-repo count, per-project active counts; unit tests.
3. **Inbox list** — `JBList` with header/row/“+N more” items and a custom
   renderer (status icon, title, `#PR`, project tag; tinted Needs-you rows,
   `JBColor` light/dark). Keyboard ↑/↓/Enter, speed search, double-click
   default action, and the existing context menu: the list publishes the same
   `SELECTED_NODE`/`SELECTED_NODES`/`SELECTED_PROJECT_ROOT` data so
   `TaskMenu`/`TaskActions`/`DelayedTaskActions` work unchanged. Age, agent and
   location move to the tooltip.
4. **Search + project chip** — `project:` suggestion popup (↑/↓/Enter/Esc),
   removable chip, Backspace-on-empty clears it, placeholder switch, empty
   state with Clear filters. "Focus on Project" becomes "apply `project:`".
5. **Projects view** — card switch from the footer ("Browse projects",
   "N quiet repos hidden"); per-repo rows with meta and "K in inbox" pill;
   expanding reuses the existing lazy Issues / Pull Requests / Worktrees
   subtree. The per-project Sessions section and the Tasks section are
   removed from this view (the inbox has them); tree tests that checked
   them move to the inbox.
6. **Add open project** — `InlineBanner` under the header (info → success
   after add), header icon button, dashed row atop Projects view; all routed
   through `sharedProjects().register(…)`; newly added repo sorted first and
   expanded.
7. **Persistence** — project-level `PersistentStateComponent`: collapsed
   groups, “more” state, active project filter, banner dismissal.
8. **Cleanup** — remove `ViewFilterRibbon` + `ViewFilter`, `ProjectFocusBar`,
   the `/<glob>/` search scope, per-project folders in the main view, inline
   `here · <agent>` / age text, and the test-only
   `app.drydock.tasks.ViewFilterRibbon`; update affected tests.

9. **PR authorship marks** (deferred from step 5, 2026-11-13) — Projects view
   PR rows marked `mine` / `review`: needs the GitHub login, a new cached
   `gh api user` lookup through `ProcessRunner` in the `github` package
   (propose the helper before adding it).
10. **Issue list truncation** (deferred from step 5) — Projects view shows the
    first few issues plus "Open all in browser"; today the expanded GitHub
    node lists every open issue.

11. **Projects-tree search on unloaded projects** (found in step 8) — the
    search narrows loaded issue/PR/worktree rows, but a project whose
    listings are still "Loading…"/"Not loaded" stays listed, because those
    placeholders are kept whatever the text (`TaskTreeModel.children`).
    Decide whether a placeholder-only project hides under a search.
12. **Dead `DelayedTaskTreeModel`** (found in step 8) — no production caller
    since before this branch (only the deleted test-only ribbon and
    `DelayedTaskRowsTest` used it); delete it with its tests.

## Not covered / risks

- Visual verification in the IDE is the user's; I can only build, test and
  launch `runIde`.
- `done_at` is only written when Drydock performs the transition; an agent or
  human editing `status: done` by hand produces no date.
