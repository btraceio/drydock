# UI view-model layer: sessions & worktree/git status

## Problem

The sidebar and the session tabs render from point-to-point coupling instead
of a shared model:

- `RepositorySidebar` takes the whole `MainWorkspace` in its constructor and
  calls eight of its methods directly (`resumeSession`, `closeSession`,
  `noteSessionDeleted`, `openNewSession`, `promptStartWorktreeSession`,
  `showUnopenedWorktree`, `promptRenameSession`, `activeSessionId`). The
  sidebar therefore cannot be built, reasoned about, or tested without a live
  `MainWorkspace` (which needs a `Stage`, native ghostty bindings, …), and
  the dependency direction is circular in spirit: the workspace also calls
  back into the sidebar via the `onSessionsChanged` Runnable.
- Every session-affecting event in `MainWorkspace` funnels into one
  coarse-grained `onSessionsChanged.run()`, which `DrydockApplication` wires to
  `RepositorySidebar.refreshSessions()` — an unconditional re-fetch of every
  repo and worktree git status plus a full `rebuildTree()`. In particular,
  merely SELECTING a tab (MainWorkspace's `selectedItemProperty` listener,
  ~lines 203–212) triggers the full re-fetch + rebuild-the-world path, even
  though only the sidebar's "active row" styling can have changed.
- Session/status data lives scattered in `RepositorySidebar`'s private maps
  (`statuses`, `statusFailures`, `worktreeStatuses`, `worktreeLists`), and
  tab headers get their name/status/PR-chip updates through separate direct
  calls (`open.setDisplayName`, `open.setStatus`, `tab.updatePrChip`) — two
  render paths for the same facts.
- Per-row context menus and tooltips are rebuilt from scratch on every cell
  update of every rebuild.

## Chosen design

Two cooperating pieces, both in small, verifiable steps:

### 1. `WorkspaceNavigator` (the SessionActions bridge)

A top-level interface in `app.drydock.ui`, modeled on the existing
`ReviewView.ExplorerBridge` pattern (consumer-shaped interface, implemented
by the workspace, injected at construction):

```java
public interface WorkspaceNavigator {
    void resumeSession(ManagedClaudeSession session);
    CompletableFuture<Void> closeSession(ManagedSessionId sessionId);
    void noteSessionDeleted(ManagedSessionId sessionId);
    void openNewSession(Repository repository);
    void promptStartWorktreeSession(Repository repository, WorktreeService.Worktree worktree);
    void showUnopenedWorktree(Repository repository, WorktreeService.Worktree worktree);
    void promptRenameSession(ManagedClaudeSession session);
    Optional<ManagedSessionId> activeSessionId();
}
```

`MainWorkspace implements WorkspaceNavigator` (all eight methods already
exist with these signatures). `RepositorySidebar` takes a
`WorkspaceNavigator` instead of a `MainWorkspace`.

### 2. `WorkspaceViewModel` (the observable session/status store)

A plain-Java, listener-based store in a new `app.drydock.ui.model` package. It
owns the data both surfaces render from:

- `List<ManagedClaudeSession> sessions` (snapshot of `SessionManager.sessions()`)
- `Map<RepositoryId, GitStatus>` repo statuses + `Map<RepositoryId, Throwable>` failures
- `Map<Path, GitStatus>` worktree statuses
- `Map<RepositoryId, List<WorktreeService.Worktree>>` worktree discovery results
- `Optional<ManagedSessionId> activeSession`

Listener interface (all default-no-op methods, so subscribers override only
what they need):

```java
public interface Listener {
    default void structureChanged() { }
    default void sessionRowChanged(ManagedSessionId sessionId) { }
    default void repoChanged(RepositoryId repositoryId) { }
    default void worktreeRowChanged(Path worktreeRoot) { }
    default void activeSessionChanged(Optional<ManagedSessionId> previous,
                                      Optional<ManagedSessionId> current) { }
}
```

Mutators and the events they emit:

- `setSessions(List<ManagedClaudeSession>)` — diffs against the current
  snapshot. Same session ids in the same order → one `sessionRowChanged`
  per changed session (plus `repoChanged` for that session's repository,
  because the repo header renders session count / any-running dot / meta
  line). Ids added, removed, or reordered → a single `structureChanged`.
  Identical content → no event.
- `setRepoStatus(RepositoryId, GitStatus)` / `setRepoStatusFailure(RepositoryId,
  Throwable)` / `clearRepoStatus(RepositoryId)` → `repoChanged` (no-op when
  the value is unchanged).
- `setWorktreeStatus(Path, GitStatus)` / `removeWorktreeStatus(Path)` →
  `worktreeRowChanged` (no-op when unchanged).
- `setWorktrees(RepositoryId, List<Worktree>)` / `removeRepository(RepositoryId)`
  → `structureChanged` when the list actually differs (rows appear/disappear).
- `setActiveSession(Optional<ManagedSessionId>)` → `activeSessionChanged`
  only on an actual change.

Threading: the model performs no thread hops itself (no `Platform.*`), so it
is fully headless-testable. In the app every mutator is called on the FX
Application Thread (async completions already hop via `Platform.runLater`
before touching UI state today; they keep doing that and then write to the
model), and listeners are invoked synchronously on the mutating thread.

### Rewiring

- `DrydockApplication` creates the model, seeds it with
  `sessionManager.sessions()`, and passes it to both `MainWorkspace` and
  `RepositorySidebar`. `setOnSessionsChanged` is deleted.
- `MainWorkspace` replaces every `onSessionsChanged.run()` after a session
  mutation with `model.setSessions(sessionManager.sessions())`. The
  tab-selection listener instead calls only
  `model.setActiveSession(activeSessionId())` — the cheap path. Tab headers
  render from the model too: `MainWorkspace` registers a listener whose
  `sessionRowChanged` updates the matching open tab's display name, status
  dot, and PR chip, replacing the scattered direct calls.
- `RepositorySidebar` drops its four private data maps in favor of model
  reads; its async git-status/worktree fetches keep their existing debounce/
  coalescing shape but write results into the model. Subscription:
  - `structureChanged` → coalesced `requestRebuild()` (existing
    `rebuildPending` AtomicBoolean) plus the existing unconditional status
    re-fetch (`refreshAllStatuses()`), preserving today's freshness behavior
    for real session-list changes.
  - `sessionRowChanged(id)` → update just that row in place, plus
    `refreshAllStatuses()` (today every session change re-fetched statuses;
    a status flip keeps doing so).
  - `repoChanged(id)` → update the repo header row and any main-checkout
    session rows under it (their branch tag reads the repo status).
  - `worktreeRowChanged(path)` → update the one matching session/unopened
    row.
  - `activeSessionChanged` → restyle the previously/newly active rows and
    run the existing `syncActiveSelection()` — no rebuild, no re-fetch.

  In-place row update mechanism: `TreeItem.setValue(freshNodeRecord)` — the
  `TreeCell` listens to its item's `valueProperty` and re-renders exactly
  that cell; sibling rows, expansion state, scroll position, and the
  filter are untouched. The footer counts are recomputed on any event
  (extracted `updateFooter()`).
- Context menus and tooltips are cached per row key (repository id /
  session id) in the sidebar and reused across cell updates; menu actions
  resolve the CURRENT session via `model.sessionById(id)` so a cached menu
  never operates on a stale snapshot. Caches are pruned on
  `structureChanged` for keys that vanished.

## Alternatives considered

- **JavaFX properties per row field, with cell-graphic bindings** (e.g.
  `StringProperty branchText` bound into labels): finer-grained, but cell
  reuse makes binding lifecycles leak-prone (every rebuilt graphic stays
  registered on the property until the row model dies), and rows are cheap
  to rebuild wholesale. Rejected in favor of coarse per-row invalidation
  via `TreeItem.setValue`.
- **Caching fully-built row NODES per row**: defeated by cell-scoped
  wiring — the repo row's expand handler uses `getTreeItem()` and the
  hover-revealed action buttons bind to the CELL's `hoverProperty()`, so a
  node cached across cells would target the wrong cell. Only the
  cell-independent pieces (context menus, tooltips) are cached.
- **`ObservableList<ManagedClaudeSession>` + `ListChangeListener`**:
  JavaFX's list-change machinery reports permutations/updates poorly for
  wholesale snapshot replacement (`setAll` reports remove-all + add-all,
  losing the "only this row changed" information we specifically need), and
  it doesn't cover the status maps at all. A purpose-built diff in
  `setSessions` is simpler and testable.
- **Moving the git-status/worktree FETCHING into the model**: would make
  the model the single fetch orchestrator, but drags async policy,
  `GitStatusService`, rescan-note/highlight UX, and `Platform.runLater`
  into what should stay a pure store — and doubles the migration surface.
  The sidebar keeps its fetch orchestration; the model stores results.

## Migration risks

- **Lost rebuild triggers**: today's single coarse `onSessionsChanged`
  over-rebuilds but never under-rebuilds. The diffing model must not drop a
  case (e.g. a session moving between repos → id set equal but repositoryId
  changed → this is a *row* change by id-diff, yet it moves the row across
  parents). Mitigation: `setSessions` treats a changed `repositoryId` or
  `worktreeRoot` as structural, since those decide which parent/row the
  session renders under; unit tests pin this.
- **Stale captures in cached menus**: context-menu handlers must read the
  live session from the model, not a captured record. Pinned by resolving
  via `model.sessionById` inside the handler.
- **Status freshness regressions**: tab-switch no longer re-fetches
  statuses (intended); genuine session changes still do. If some workflow
  silently relied on tab-switch-triggered refresh, branch/dirty data could
  go stale until the next real change or manual ⟳ rescan. Accepted —
  matches the stated goal.
- **Event ordering/reentrancy**: listeners fire synchronously during a
  mutator; a listener that mutates the model back could recurse. The
  in-tree subscribers only schedule UI work or async fetches (whose
  completions run later), so no reentrant mutation exists today; the model
  documents (and tests) that listeners must not mutate synchronously.
- **`noteSessionDeleted` double-fire**: both the sidebar (after
  `deleteSession`) and the workspace push `setSessions`; the model's
  no-op-on-identical-content diff makes the second push silent.

## Implementation plan

Each step compiles and passes `./gradlew compileJava test` on its own and is
committed separately.

1. **`WorkspaceNavigator` extraction.** Add the interface in `app.drydock.ui`;
   `MainWorkspace implements WorkspaceNavigator` (methods already exist);
   `RepositorySidebar` swaps its `MainWorkspace` field/constructor param for
   `WorkspaceNavigator`; `DrydockApplication` unchanged except for the parameter
   type flowing through. No behavior change.
2. **`WorkspaceViewModel` + tests.** Add `app.drydock.ui.model.WorkspaceViewModel`
   (data, listener interface, mutators with the diff semantics above,
   `sessionById`, snapshot getters) and headless unit tests covering: no-op
   sets emit nothing; field-level session change emits `sessionRowChanged` +
   `repoChanged`; add/remove/reorder and repositoryId/worktreeRoot moves emit
   `structureChanged`; status setters emit only on change; active-session
   transitions; listener add/remove. Nothing uses the class yet.
3. **Workspace writes the model.** `DrydockApplication` constructs/seeds the
   model and passes it to `MainWorkspace`; `MainWorkspace` replaces
   `onSessionsChanged.run()` with `model.setSessions(...)` (mutations) and
   `model.setActiveSession(...)` (tab-selection listener), registers its
   `sessionRowChanged` listener to update open tab headers (name, status,
   PR chip), and `setOnSessionsChanged` is deleted. `DrydockApplication` keeps
   the old sidebar wiring alive temporarily by subscribing a bridge listener
   that calls `sidebar.refreshSessions()` on structure/session/active
   events, so behavior is unchanged mid-migration.
4. **Sidebar reads the model.** `RepositorySidebar` takes the model, drops
   its four data maps (fetch completions write to the model), subscribes
   with the event mapping above (incremental row updates via
   `TreeItem.setValue`, `updateFooter()` extraction, `syncActiveSelection`
   on active change only), and the temporary bridge listener in
   `DrydockApplication` is removed.
5. **Context menu / tooltip caching.** Cache per row key, handlers resolve
   live sessions through the model, prune on `structureChanged`.
6. **Self-review + verify.** Diff the branch against its base, check against
   AGENTS.md (async rules, no inline FQNs, lifecycle hygiene, doc comments
   in `RepositorySidebar`/`MainWorkspace` that describe the old wiring), fix
   findings, then run `./gradlew compileJava compileTestJava test`.
