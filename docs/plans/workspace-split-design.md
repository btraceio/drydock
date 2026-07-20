# Workspace split — design

Decomposes the two biggest UI classes along their natural seams, with no
behavior change.

## Seam 1: `GhosttyKeyTranslator` (new, `app.cpm.terminal.ghostty`)

`OpenSessionTab` currently owns the macOS-keycode/ghostty-mods vocabulary
and the whole key-classification policy inline in `onKeyEvent`:

- the NS_* modifier masks and GHOSTTY_MODS_* masks plus `translateModifiers`;
- `SPECIAL_KEYS` (raw macOS virtual keycodes forwarded as `sendKey`);
- the app-shortcut interception (⌘1/⌘2/⌘3, ⌘⇧[/⌘⇧], ⌘0) that must run
  BEFORE forwarding, because the native NSEvent monitor sees keys before
  the JavaFX scene filter;
- the shortcut/special-key vs typed-characters split, including the
  unshifted-codepoint rule (`0` on key-up or when unshifted chars are
  empty);
- the raw `36` Return keycode literal in `sendPrompt`.

`GhosttySurface.closeGracefully` separately re-declares `KVK_ANSI_D` and
`GHOSTTY_MODS_CTRL` for its Ctrl+D exit request — the same vocabulary,
duplicated.

**What moves.** A new pure, static, dependency-free
`GhosttyKeyTranslator` owns:

- public keycode constants (`KEY_RETURN` = 36, `KEY_D` = kVK_ANSI_D = 2)
  and the `MODS_*` ghostty bitmask constants;
- `translateModifiers(int nsModifierFlags)`;
- `isSpecialKey(int keyCode)` (the former `SPECIAL_KEYS` set);
- `translate(keyCode, nsModifierFlags, keyDown, characters,
  unshiftedCharacters)` returning a sealed `KeyAction`:
  - `AppShortcut(shortcut, keyDown)` — consumed by the app (the caller
    performs the action only on key-down, but swallows both edges,
    exactly like today's early returns);
  - `ForwardKey(keyCode, mods, keyDown, unshiftedCodepoint)` — special
    key or Ctrl/⌘ shortcut, forwarded via `GhosttySurface.sendKey`;
  - `TypeCharacters(characters, mods)` — plain typing, forwarded
    per-codepoint via `sendCharKey`;
  - `Ignore` — nothing to do (e.g. key-up of a plain character).

The decision order and every predicate are copied verbatim from
`onKeyEvent`; the caller-side effects (which surface method to call) stay
with the caller. `GhosttySurface`'s private `KVK_ANSI_D`/
`GHOSTTY_MODS_CTRL` constants are replaced by references to the
translator's `KEY_D`/`MODS_CTRL`; `sendPrompt`'s raw `36` becomes
`KEY_RETURN`.

**Testability.** The translator is a pure function of its inputs — unit
tests cover modifier translation, every special key, the shortcut table
(including the shifted `{`/`}` forms), the unshifted-codepoint policy,
empty-characters fallbacks, and key-up swallowing. This logic was
previously untestable because it lived in a class that needs live native
resources to construct.

The `Gate0*Spike` files keep their own private copies (another pack owns
them; not touched).

## Seam 2: `TerminalBridge` (new, `app.cpm.ui`, package-private)

`OpenSessionTab` mixes JavaFX chrome (tab graphic, session header,
sub-tab bar, rename, worktree chips) with the native-terminal bridge
(surface lifecycle guards, key/mouse/scroll forwarding, geometry sync,
focus, visibility, theming, disposal). The seam is the field set
`{app, host, surface, disposed, surfaceClosing, workspaceWantsVisible}`
— chrome never touches the natives except through a handful of calls.

**What moves** into `TerminalBridge`:

- ownership of the `GhosttyApp` + `CpmTerminalHost` pair and the
  attached `GhosttySurface`, plus the `disposed`/`surfaceClosing` flags;
- `attachSurface` (host listener wiring), `onKeyEvent`, `onScrollEvent`,
  `onMousePosEvent`, `onMouseButtonEvent`, `diagPressKey`, `diagScroll`;
- `updateGeometry` (placeholder scene-bounds → `host.setFrame` +
  `surface.setSize`), driven by the same two placeholder property
  listeners;
- visibility: the AND of `workspaceWantsVisible` and
  "Terminal sub-tab active" (the tab reports the latter via
  `setTerminalSubTabActive`), with the show-before-draw ordering intact;
- `focus`, `releaseTerminalFocus`, `sendPrompt`, `tickAndDraw`,
  `isProcessExited`, `applyTerminalTheme`, `markSurfaceClosing`,
  `disposeNativeResources`.

**What stays** in `OpenSessionTab`: all chrome, sub-tab hosting, the
placeholder `StackPane` itself (it is a layout anchor the chrome owns;
the bridge only reads it), and the delegating one-liners that preserve
`OpenSessionTab`'s package-private surface for `MainWorkspace`
(`app()`, `host()`, `sendPrompt`, `focus`, `tickAndDraw`, …).

**Wiring.** The bridge takes the app/host pair, the anchor `Region`, a
scale supplier (`stage::getOutputScaleX` — no `Stage` dependency), a
session-id supplier for log messages, and a
`Consumer<GhosttyKeyTranslator.Shortcut>` the tab maps to
`showSubTab`/previous/next/toggle-sidebar. Key classification itself is
delegated to `GhosttyKeyTranslator`, so the bug-prone policy is unit
tested there; the bridge is a thin effects layer (it still requires the
real native classes to construct — `GhosttyApp`/`CpmTerminalHost` are
final native wrappers — so it is exercised via the existing runtime
paths, not new unit tests).

## Seam 3: `WorktreeLifecycleController` (new, `app.cpm.ui`, package-private)

`MainWorkspace` conflates tab/session orchestration with the whole
worktree-finish lifecycle (handoff section B). The lifecycle block —
`setupWorktreeHeader`, `refreshWorktreeChips`, `showFinishPanel` (busy
modal, inspection records, PR-state reconciliation), the three
`handoff*` prompts, the `PauseTransition` polling machinery
(`pollHandoff*`, caps), `applyPrState`, `restoreFinishLater`,
`busyModal`, `branchNameOf`, `openInBrowser` — talks to tabs only
through `OpenSessionTab`'s public-ish methods and to the workspace only
through "is this session's tab still open", "note deleted", and
"sessions changed".

**What moves.** All of the above into `WorktreeLifecycleController`,
constructed by `MainWorkspace` with injected collaborators:

- services: `SessionManager`, `GitStatusService`, `GhCliService`;
- `Function<ManagedSessionId, OpenSessionTab>` open-tab lookup (returns
  `null` once the tab closed — the exact `openTabs.get`/`containsKey`
  liveness guard every async completion uses today);
- `Function<ManagedSessionId, Optional<Repository>>` repository lookup;
- `Runnable` sessions-changed notifier and a
  `Consumer<ManagedSessionId>` deleted-session notifier (both delegate
  to the workspace so listener semantics are unchanged);
- the `ModalLayer`, forwarded by `MainWorkspace.setModalLayer` (the
  public setter stays on `MainWorkspace`).

`MainWorkspace` keeps: tab orchestration, open/resume/close paths, the
create-worktree and start-session modals (they are session-opening
paths, not finish-lifecycle), the unopened-worktree empty state, and the
exit watcher. `attachOpenedSession` calls
`worktreeLifecycle.setupWorktreeHeader(...)` instead of the private
method. `sessionById`/`repositoryFor` stay in `MainWorkspace` (still
used by resume paths); the controller resolves sessions through
`SessionManager` the same way.

**Public surface**: unchanged. `MainWorkspace`'s public methods are all
kept; `OpenSessionTab`'s package-private API is kept via delegation.

## Risks

- **Listener wiring order** (`attachSurface` registers four host
  listeners; placeholder property listeners registered in the tab
  constructor must still fire into the bridge after construction) —
  preserved by wiring the bridge before the listeners are attached and
  keeping registration order identical.
- **Teardown race guards**: the `disposed`/`surfaceClosing` checks and
  the benign `IllegalStateException` catches move verbatim; the
  `markSurfaceClosing`-before-TabPane-removal contract is unchanged
  (`MainWorkspace.removeTab` still calls the tab, which delegates).
- **FX-thread hops**: every `Platform.runLater` completion hop and
  `PauseTransition` in the moved worktree code is copied verbatim, as
  are the progress-state clears on success/error/early-return paths
  (AGENTS.md).
- **Shortcut behavior**: the translator must reproduce `onKeyEvent`'s
  exact decision order (shortcut interception before the special-key
  check, `characters`-empty fallback to `unshiftedCharacters`, both-edge
  swallowing) — covered by unit tests.
