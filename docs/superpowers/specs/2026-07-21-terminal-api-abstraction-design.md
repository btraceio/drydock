# Phase A — `terminal-api` abstraction + Claude/Terminal split

Status: approved design (2026-07-21)

This is **Phase A** of following through on `docs/implementation-plan.md`
section 5 (the multi-module split). Phase A introduces a technology-neutral
terminal abstraction *in place* (single Gradle module, no build restructuring).
**Phase B** — physically splitting the build into `terminal-api`,
`terminal-ghostty`, `native-host`, and `packaging` Gradle subprojects — is a
separate later cycle and is out of scope here.

## 1. Objective

1. Introduce `app.drydock.terminal.api`: a technology-neutral set of interfaces
   and value types describing "a terminal running a command, embedded in a
   window." Ghostty becomes the implementation behind it. All `ui`/`app`
   consumers depend only on `terminal.api.*`, never on `terminal.ghostty.*` or
   `terminal.host.*`.
2. As the validating second consumer of that abstraction, split the session
   tab's single `TERMINAL` sub-tab into **Claude** (runs the `claude` CLI —
   unchanged behavior) and **Terminal** (a plain login shell).

A single implementation is easy to abstract badly (the interface ends up being
"Ghostty with the names filed off"). The plain-shell terminal is a genuinely
different second use of the same machinery, so it forces the api to be about
*a terminal running a command* rather than *a Claude session*.

## 2. Current state (as found)

- No terminal abstraction exists. `ui` and `app` import the concrete classes
  directly: `GhosttyApp`, `GhosttySurface`, `GhosttyKeyTranslator` (+ nested
  types), `DrydockTerminalHost`.
- `terminal/` (including `ghostty/` and `host/`) imports **nothing** from other
  `app.drydock` packages — it is a self-contained leaf. Consumers are
  `app.SessionManager`, `app.SessionOpenResult`, `ui.MainWorkspace`,
  `ui.OpenSessionTab`, `ui.TerminalBridge`.
- `TerminalBridge` is already the de-facto seam: it owns the
  `GhosttyApp` + `DrydockTerminalHost` + `GhosttySurface` trio for one session
  tab and everything that talks to them (input forwarding, geometry sync,
  focus, visibility, theming, disposal). `OpenSessionTab` keeps the JavaFX
  chrome and delegates to it.
- The run command is a single POSIX shell **string**; libghostty always runs it
  via `/bin/sh -c "<command>"`. `SessionManager.buildCreateCommand` /
  `buildResumeCommand` build the `claude …` string. The surface is created with
  `GhosttySurface.create(app, host, scaleFactor, command, workingDirectory)`.
- Session tab sub-tabs today: `OpenSessionTab.SubTab { TERMINAL, EXPLORER,
  REVIEW }`, a `ToggleButton` bar bound to ⌘1/⌘2/⌘3. Explorer and Review
  replace the terminal region; only the `TERMINAL` sub-tab shows a surface.
- No JPMS `module-info.java` anywhere — isolation is classpath/package-only.

## 3. The abstraction

New package `app.drydock.terminal.api`. The line drawn: *terminal behavior* is
neutral; *how a native surface is hosted in a window* is macOS-specific but is
still expressed as a neutral interface with a single AppKit implementation.

| api type (new) | replaces / derives from | responsibility |
|---|---|---|
| `TerminalRuntime` | `GhosttyApp` | per-view runtime lifecycle: `tick`, `setFocus`, `updateConfig`, `close`. Created via the factory. |
| `TerminalSurface` | `GhosttySurface` | one running command: input (`sendKey`/`sendCharKey`/`sendTypedText`/`sendText`/mouse/scroll), output (`readScreenText`), lifecycle (`draw`/`refresh`/`setSize`/`processExited`/`close`/`closeGracefully`/`setFocus`). |
| `TerminalHostView` (+ listener interfaces) | `DrydockTerminalHost` | the embedded native view contract: `setFrame`/`setVisible`/`setFocused`/key+scroll+mouse listeners/`close`. Only implementation is AppKit. |
| `TerminalSpec` (record) | (new) | *what to run*: `command` (shell string), `workingDirectory`, theme/config. The seam that makes "Claude vs shell" just different specs. |
| `Shortcut` (enum) | `GhosttyKeyTranslator.Shortcut` | neutral app-shortcut identity the UI dispatches on (new sub-tab, cycle, toggle sidebar, …). |
| `TerminalFactory` | (new) | the single entry point that hides Ghostty: `createRuntime(...)` and `createHostForCurrentWindow()`. Surfaces are then opened on the runtime: `runtime.openSurface(host, scale, spec)`. |

### Boundary rules (approved)

- **All keycode translation stays impl-internal.** `GhosttyKeyTranslator`'s
  AppKit-keycode → ghostty-keycode mapping and the numeric key/mod constants
  remain in `terminal.ghostty`. The UI never sees a keycode; it receives raw
  native key events at the host boundary (consumed by the impl) and neutral
  `Shortcut` values for intercepted app shortcuts. The `KeyAction` sealed
  hierarchy (`AppShortcut`/`ForwardKey`/`TypeCharacters`/`Ignore`) is an impl
  detail and does not appear in `terminal.api`.
- **JavaFX↔NSView positioning stays in the UI.** The `TerminalBridge` logic
  that reads a JavaFX anchor's scene bounds and the window output scale to
  drive `TerminalHostView.setFrame` is genuinely not neutral and remains in
  `ui`. The api exposes only `setFrame(x, y, w, h)` in device-independent
  window coordinates.
- `terminal.ghostty` and `terminal.host` become the implementation of the api
  types. Their classes may implement the api interfaces directly.

## 4. Claude/Terminal sub-tab feature

- `OpenSessionTab.SubTab` → `{ CLAUDE, TERMINAL, EXPLORER, REVIEW }`. Toggle bar
  buttons and accelerators become ⌘1 Claude / ⌘2 Terminal / ⌘3 Explorer /
  ⌘4 Review. Any existing ⌘-number wiring for Explorer/Review shifts by one.
- **Claude** sub-tab: exactly today's behavior — the surface whose command
  comes from `SessionManager.buildCreateCommand` / `buildResumeCommand`, with
  activity-hook settings, env cleanup, resume fallback, etc. all unchanged.
- **Terminal** sub-tab: a second surface built from a
  `TerminalSpec` with `command = "exec ${SHELL:-/bin/zsh} -l"` and
  `workingDirectory` = the session's worktree root. No env cleanup, no activity
  hooks, no persistence.
- **Ephemeral** (approved): the shell terminal is never persisted or resumed. It
  is created fresh on each session open and discarded when the session tab
  closes. Only the Claude session persists, exactly as today.
- **Lazy** (approved): the shell surface is created on first switch to the
  Terminal sub-tab, not at session open. `OpenSessionTab` therefore holds up to
  two `TerminalBridge` instances (Claude eager as today; shell created on
  demand). Disposal closes whichever bridges exist.
- Only one native surface is visible at a time. Switching Claude↔Terminal hides
  one host view and shows the other; switching to Explorer/Review hides both.

## 5. Migration approach (compile-green at every step)

1. Add `terminal.api` interfaces/records; make the existing `GhosttyApp`,
   `GhosttySurface`, `DrydockTerminalHost` implement the corresponding
   interfaces (additive — no consumer change yet).
2. Add `TerminalFactory`; route the construction sites
   (`MainWorkspace` runtime creation, `SessionManager` surface creation,
   host creation) through it.
3. Flip `ui`/`app` field/parameter/import types from the concrete classes to
   the api types. After this step no code outside `terminal.ghostty` /
   `terminal.host` imports a concrete terminal class.
4. Add the second sub-tab: extend `SubTab`, add the shell `TerminalBridge`
   (lazy), wire the ⌘-accelerators, and the shell `TerminalSpec`.

## 6. Testing

- `GhosttyKeyTranslator`'s existing unit tests stay as-is (translation remains
  in the impl).
- The Claude path has no behavior change, so existing terminal tests and the
  Gate 0C/0D/0E spikes remain valid acceptance checks.
- Add a focused unit test for shell-`TerminalSpec` construction (correct
  `command` string and working directory) — pure logic, no native surface.
- Manual check: open a session, confirm ⌘1 Claude behaves as before, ⌘2 opens a
  working login shell in the worktree dir, Explorer/Review (⌘3/⌘4) still work,
  and closing the tab disposes both surfaces cleanly.

## 7. Out of scope (Phase B and beyond)

- Any Gradle multi-project restructuring (`settings.gradle.kts` stays
  `include(":app")`).
- Introducing JPMS `module-info.java`.
- Relocating the buildSrc packaging tasks or `native-host` C build.
- Persisting/resuming the shell terminal.
- Renaming the on-disk state directory (still literally `ClaudeProjectManager`).
