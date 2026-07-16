# Milestone 5 Report — Terminal Tabs UI

Plan reference: `docs/implementation-plan.md` section 13 "Main Workspace", section 12
"Repository Sidebar", section 9 "Terminal Abstraction", section 11 "Session Creation
and Resumption", section 10.3 "Workspace state". This is the fourth and final step of
Milestone 5 (after step 1's domain model/persistence/Claude discovery, commit
`4e9877d`, and step 2's `SessionManager` lifecycle orchestration, commit `80bb638`):
wires those into a real terminal-tabs UI, and is the independent final-verification
pass over all three steps together, per plan rule 27.20 ("do not claim a milestone
complete until its exit criteria have been manually or automatically verified").

## What was built (file by file)

### `app/src/main/java/app/cpm/ui/OpenSessionTab.java` (new)
One open terminal tab's native resources: its own `GhosttyApp` + `CpmTerminalHost` +
(once attached) `GhosttySurface`, per Gate 0C/0D/0E's established
one-`GhosttyApp`-per-window/view pattern (one instance per tab here, all attached to
the same single application window — see "Design notes" below for why that's valid).

- Owns a `StackPane` placeholder used purely as a JavaFX layout anchor: Ghostty does
  not render into the scene graph, so the placeholder's on-screen bounds
  (`localToScene`) are read to reposition the native `NSView` overlay (`host.setFrame`)
  whenever the placeholder's bounds or scene-transform change (splitter drag, window
  resize, tab switch).
- `attachSurface(GhosttySurface)` — called once `SessionManager` hands back a running
  surface; installs the real key-forwarding listener and removes the "Starting..."
  placeholder label.
- `onKeyEvent(...)` — a corrected reimplementation of Gate0cSpike's key-translation
  logic: special keys/Ctrl/Cmd shortcuts go through `sendKey`, but plain typed
  characters go through `GhosttySurface.sendCharKey` (the real per-character keyboard
  codepath), **not** `sendText` (paste semantics — Gate0cSpike itself still uses the
  older, since-documented-as-wrong `sendText` path for plain characters; this class
  does not copy that, using the fixed API `GhosttySurface.sendCharKey` document
  instead — see that method's Javadoc and `docs/claude-integration.md`).
- `disposeNativeResources()` — closes only `GhosttyApp`/`CpmTerminalHost`; never calls
  `GhosttySurface.close()` itself (that already happened inside
  `SessionManager.closeSession`'s `closeGracefully` call before this runs).

### `app/src/main/java/app/cpm/ui/MainWorkspace.java` (new)
The main workspace (plan section 13): a `TabPane` of terminal tabs on top of a
`Map<ManagedSessionId, OpenSessionTab>`.

- `openNewSession(Repository)` — creates a placeholder tab immediately, then calls
  `SessionManager.createSession`, attaching the real surface once it resolves
  (off the FX thread; results are re-marshaled via `Platform.runLater`).
- `resumeSession(ManagedClaudeSession)` — if already open in this app instance,
  just focuses the existing tab; otherwise calls `SessionManager.resumeSession` and
  handles all three `SessionOpenResult` variants:
  - `Opened` → attach surface, same as create.
  - `AlreadyOpen` → discard the unused placeholder's app/host (never handed a
    surface, since `SessionManager` short-circuits before creating one) and focus
    the existing tab for the active session id.
  - `MissingWorkingDirectory` → a real, specific dialog (plan section 20) naming the
    missing path, offering a `DirectoryChooser`, then calling
    `SessionManager.reassignWorkingDirectory` and retrying.
- `closeSession(ManagedSessionId)` / `closeAllSessions()` — **always** go through
  `SessionManager.closeSession` (`closeGracefully` internally); never call
  `GhosttySurface.close()` from UI code. Used by the tab's close button, the
  sidebar's "Stop process" action, and app shutdown.
- `renameSession` / `promptRenameSession` — calls `SessionManager.renameSession`;
  updates the open tab's title if present.
- Tab selection listener toggles `host.setVisible`/focus per tab so only the
  selected tab's native view is shown and receives keyboard input; a
  `stage.focusedProperty()` listener refocuses the selected tab when the window
  regains focus.

### `app/src/main/java/app/cpm/ui/RepositorySidebar.java` (modified)
- Constructor now also takes `SessionManager` and `MainWorkspace`.
- The repository context menu's "New Claude session" item is no longer a disabled
  stub — it calls `mainWorkspace.openNewSession(repository)`.
- Each repository row now renders a nested list of that repository's
  `ManagedClaudeSession`s (approximating plan section 12's two-level tree sketch
  inside a single `ListView`'s cell, rather than an actual `TreeView`): a status
  icon, double-click-to-resume, and a per-session context menu (Resume, Rename,
  Stop process, Reveal working directory). "Remove application entry" from the
  plan's session-context-action list is **not** implemented — `SessionManager` has
  no delete-session-metadata method, and adding one was out of scope for "wire up
  the existing API" (see "Deviations" below).
- `refreshSessions()` — re-renders from `SessionManager.sessions()` (no local
  session cache; `MainWorkspace` calls this after every open/resume/close/rename via
  `setOnSessionsChanged`).
- The "0 running sessions" hardcoded placeholder is gone; it now counts real
  `RUNNING`-status sessions.

### `app/src/main/java/app/cpm/CpmApplication.java` (rewritten)
- Constructs `ClaudeCapabilityService` and `SessionManager` alongside the existing
  `GitStatusService`/`RepositoryManager`, sharing one `JsonApplicationStateRepository`
  instance.
- Replaces the placeholder main-area `Label` with a real `MainWorkspace`.
- Plan section 9 "Application shutdown prompts once for all active processes":
  `primaryStage.setOnCloseRequest` checks `MainWorkspace.hasOpenSessions()`; if any
  are open, shows one confirmation `Alert`, then calls
  `MainWorkspace.closeAllSessions()` and only closes the stage once every session's
  `closeGracefully` has completed. Modeled directly on `Gate0eSpike.shutdown()`'s
  already-verified pattern (`Stage.close()` does not re-fire `setOnCloseRequest`, so
  this cannot infinite-loop).
- `stop()` additionally closes `SessionManager`/`ClaudeCapabilityService`.

### `app/src/main/java/app/cpm/app/SessionManager.java` and `.../app/RepositoryManager.java` (modified — see "Bug found while wiring up")
Both gained a `mergeXOntoLatestDiskState` helper so each class's mutators re-read the
freshest on-disk state and apply only their own owned field(s) on top of it, instead
of writing back their entire long-lived in-memory `ApplicationState` snapshot
(including the *other* class's fields) unconditionally. See below for why.

### `app/build.gradle.kts` (modified)
- `applicationDefaultJvmArgs` gained `--enable-native-access=ALL-UNNAMED` (the real
  app now loads libghostty/the native host shim via FFM, same requirement as every
  `gateNSpike` task).
- The `run` task now `dependsOn(buildGhosttyNative, buildNativeHost)` and pins the
  JDK 26 toolchain launcher + `rootProject.projectDir` as its working directory
  (needed for `CpmTerminalHostLibrary`'s `build/native` discovery), matching every
  other native-consuming task in this file.

## Bug found while wiring up (plan step's explicit "explain exactly why" clause)

**Cross-manager `ApplicationState` overwrite.** `RepositoryManager` (Milestone 4) and
`SessionManager` (Milestone 5 step 2) each independently `load()` and cache their own
in-memory `ApplicationState` snapshot from the *same* `JsonApplicationStateRepository`
file, and each mutator on both classes previously wrote its **entire** cached
snapshot back unconditionally on every save. That was harmless as long as only one of
the two classes existed/ran at a time (true through step 2), but this step is the
first time both run together in the same process against the same file. Concretely:
add a repository via the sidebar (`RepositoryManager` persists), then create a
session in it (`SessionManager` persists) — `SessionManager`'s own cached
`state.repositories()` predates the just-added repository (it was loaded at app
startup, before the add), so its save silently reverted that repository out of the
persisted file, even though the UI kept showing it (the sidebar reads its own
`RepositoryManager`'s in-memory list, not a fresh reload). A restart would then load
a session whose `repositoryId` points at a repository no longer in the persisted
list. **This is exactly the flow this milestone's own acceptance test performs**
("add repository, then create a session in it, then restart"), so it was caught,
not theoretical.

Fix (in both classes, symmetric): before every save, re-read the current on-disk
state and apply only that class's own owned field(s) on top of it —
`SessionManager.mergeSessionsOntoLatestDiskState` overlays `sessions` onto a fresh
`stateRepository.load()`; `RepositoryManager.mergeRepositoriesOntoLatestDiskState`
(and `updateSidebarWidth`) overlay `repositories`/`ui` the same way. No public API of
either class changed. This does not add real concurrency safety (no file locking),
but it eliminates the specific "long-lived stale snapshot from a different object"
staleness window that actually manifests in normal single-user desktop usage.
Verified directly (see below): adding a repository then creating a session in it,
then reloading fresh manager instances from the same file, restores **both** the
repository and the session.

## Design notes

- **Native-view overlay, not a scene-graph `Node`.** `CpmTerminalHost` attaches a
  real AppKit `NSView` as a sibling of the current window's content view, positioned
  via pixel `setFrame` calls in that window's own coordinate space — it does not
  render through JavaFX's scene graph at all. `OpenSessionTab`'s placeholder is
  therefore an empty layout anchor only; this matches how Gate 0C/0D/0E already work,
  just adapted to move/resize/show/hide per *tab* instead of filling one whole
  spike window.
- **One application window.** `CpmTerminalHost.createForCurrentWindow()`'s own
  Javadoc documents it as "most-recently-created still-open Glass window" — a
  single-window simplification. This application still has exactly one `Stage`
  (`CpmApplication`'s `primaryStage`), so every tab's host correctly resolves to
  that same window every time; this would need revisiting if the application ever
  grew a second top-level window.
- **Key forwarding uses the corrected `sendCharKey` codepath**, not the older
  `sendText` approach still present (unmodified, per instructions) in
  `Gate0cSpike.onKeyEvent`. See `GhosttySurface.sendCharKey`'s Javadoc and
  `docs/claude-integration.md` for why `sendText` corrupts input once bracketed
  paste is enabled.
- **`SessionOpenResult.AlreadyOpen` handling** discards the placeholder tab's
  freshly created (but never surfaced) `GhosttyApp`/`CpmTerminalHost` rather than
  reusing them, since `SessionManager.checkResumeBlocked` short-circuits before ever
  creating a `GhosttySurface` for that attempt. Slightly wasteful (an unused
  `ghostty_app_new`/native-view-create-and-destroy pair) but simple and correct.

## Deviations / explicitly deferred

1. **Open-tab restoration across restart is deferred.** Session *metadata*
   (display name, working directory, Claude session id/name, status) already
   persists and restores via `SessionManager`/`JsonApplicationStateRepository`
   (verified below). Restoring *which tabs were actually open* — i.e. automatically
   re-opening terminal tabs on the next launch — is **not implemented**: doing so
   correctly without violating "no unexpected `claude` process launches" would mean
   either (a) silently launching one `claude --resume` per previously-open tab on
   startup (explicitly forbidden by this step's instructions), or (b) restoring
   inert/unsurfaced placeholder tabs that still require an explicit user action to
   actually resume — a real feature with its own UX questions (stale placeholders,
   partial-restore-failure UI) that did not fit this step's scope. `WorkspaceUiState`
   was deliberately left unchanged (no new "open session ids" field) so as not to
   half-implement a schema for a feature whose restore-time behavior isn't decided.
2. **"Remove application entry"** (plan section 12's session context action) is not
   implemented: `SessionManager` has no delete-session-metadata method, and adding
   one was out of scope for a step whose brief was "consume `SessionManager`'s
   existing API, don't extend it" (beyond the one documented, explained bug fix
   above). Rename/Stop/Reveal/Resume are all wired.
3. **Double-click-to-rename** was not added in addition to the "Rename..." tab
   context-menu item; the task listed both as an "e.g." alternative, and one entry
   point (per session row and per tab) was judged sufficient for this step.
4. **Shutdown confirmation** is a single `Alert.CONFIRMATION`, not a listing of each
   individual running session — "prompts once for all active processes" is
   satisfied, but the dialog does not enumerate them by name. A fuller version could
   list session names in the dialog body; deferred as a minor polish item.

## Verification performed

All commands run from `/Users/jbachorik/src/olifer`.

1. **`./gradlew clean compileJava test`** — `BUILD SUCCESSFUL`, all existing unit
   tests (including `SessionManagerTest`, `RepositoryManagerTest`) still pass
   unmodified.
2. **`./gradlew gate0dSpike`** — Phase 0 regression guard: still **12/12 PASS**,
   confirming this step's `CpmApplication`/build-file changes did not regress the
   already-verified interactive-shell terminal behavior.
3. **Real end-to-end flow against a real `claude` CLI and a real throwaway git
   repository** (`/tmp/cpm-milestone5-test`, created and destroyed for this run only,
   never this project's own repository), using a throwaway JavaFX driver class
   (written, run, and then deleted before this commit — not part of the delivered
   source) that constructed the exact same production objects
   (`RepositoryManager`/`SessionManager`/`MainWorkspace`) `CpmApplication` does and
   called `MainWorkspace.openNewSession`/`closeSession` — the identical methods
   `RepositorySidebar`'s "New Claude session"/"Stop process" menu items call. This
   was necessary because no GUI-automation tool for a native macOS window (as
   opposed to a browser tab) was available in this environment to literally click
   the sidebar's context menu; the driver exercises the same code path a click would,
   just invoked directly, inside a real visible JavaFX window with a real
   `GhosttyApp`/`CpmTerminalHost`/`GhosttySurface` underneath (not mocked).
   - `openNewSession` → session metadata appeared with `status=RUNNING`; log
     confirmed `ClaudeExecutableLocator` resolved and launched the real
     `/Users/jbachorik/.local/bin/claude` binary in the repo's working directory.
   - `closeSession` → `closeGracefully` requested Ctrl+D, the grace period elapsed
     (this build's `claude` REPL does not treat Ctrl+D as an exit request the way a
     shell does — an existing, previously-documented behavior of `SessionManager`'s
     default grace period, not something newly introduced here), then force-closed;
     session metadata updated to `status=EXITED`; **no JVM crash**. `ps aux | grep
     claude` immediately after confirmed the spawned `claude` process was actually
     gone (not just reported gone).
   - Simulated restart (fresh `RepositoryManager`/`SessionManager` instances
     constructed against the same state file, exactly what `CpmApplication.start()`
     does on a real relaunch) confirmed **both** the repository and the session
     (with its final `EXITED` status) were restored, and confirmed no new `claude`
     process was spawned by doing so.
   - Directly inspected the persisted `state.json`: both the repository and session
     entries were present together — the cross-manager overwrite fix (above) is what
     makes this true; without it the repository entry would have been missing.
4. **`./gradlew run`**, launched for real, confirmed the actual `app.cpm.Main` /
   `CpmApplication` process starts cleanly (JavaFX window opens, no exception in
   logs) with the new `MainWorkspace`/`SessionManager`/`ClaudeCapabilityService`
   wiring, then was killed.
5. **Process cleanup**: after every step above, confirmed via
   `ps aux | grep -E 'gradlew run|CpmApplication|app.cpm' | grep -v grep` that no
   process remained running — this printed nothing on the final check.

No milestone-completion claim beyond what the above evidence actually shows.
