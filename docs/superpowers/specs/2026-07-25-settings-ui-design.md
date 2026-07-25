# Settings UI

A small preferences panel, reachable from the title bar, exposing the four
settings that have (or can cheaply gain) real consumers: theme, interface
font size, terminal font size, and the worktrees directory.

## Motivation

Drydock has no settings surface. Theme is toggleable only from the title-bar
glyph, and `worktreesDirectory` — the one genuinely user-editable setting —
can only be changed by hand-editing `~/.drydock/config.json`, which is
undiscoverable. Font sizes are not configurable at all.

## Entry point

The app runs an undecorated stage with a custom `TitleBar`; there is no menu
bar and none is introduced. A `⚙` icon button is added to the title bar's
right-hand group, between the `?` (shortcuts) and theme-toggle buttons:

```
[● ● ●] [◧]            Drydock            [ ? ] [ ⚙ ] [ ☾ ]
```

- Tooltip: `Settings (⌘,)`.
- `AppShell` gains `showSettings()`, mirroring the existing
  `showShortcutsOverlay()`.
- `DrydockApplication`'s scene-level `KEY_PRESSED` filter gains a
  `cmd + COMMA` branch alongside the existing `⌘⇧L` / `⌘0` / `⌘F` branches.
- `ShortcutsOverlay.SHORTCUTS` gains a `{"Settings", "⌘,"}` row.

## Settings and where they persist

Two persistence stores already exist and the settings split cleanly across
them:

| Setting | Home | Default |
| --- | --- | --- |
| Theme | `WorkspaceUiState.theme` (existing) | `DARK` |
| Interface font size | `WorkspaceUiState.uiFontSize` (new) | `13.0` |
| Terminal font size | `WorkspaceUiState.terminalFontSize` (new) | `13.0` |
| Worktrees directory | `UserConfig.worktreesDirectory` (existing) | `<home>/dev/wt` |

Cosmetic, app-owned UI state lives in `WorkspaceUiState` alongside `theme`
and `sidebarWidth`. The human-editable `~/.drydock/config.json` keeps the
worktrees directory, which already has a consumer (`NewWorktreeModal` /
`WorktreeNaming`).

No single user action writes both stores, so there is no cross-store
consistency concern.

### `WorkspaceUiState` fields

`uiFontSize` and `terminalFontSize` are added as `double` components with
`with…` copy methods, following the record's existing shape.

`ApplicationStateCodec` encodes them as JSON numbers and decodes them
**leniently**, consistent with how `sidebarWidth` / `theme` already decode:

- member absent → default,
- member present but not a number → default.

A malformed value is never a reason to fail the whole decode. The change is
purely additive to the JSON schema; documents written before this change
decode to the defaults.

The codec deliberately does **not** clamp, matching `sidebarWidth`
(`ApplicationStateCodec.java:304-306` accepts any number; the SplitPane
clamps at use). Range ownership sits in exactly one place — the point of
application — so a hand-edited out-of-range value is honoured as far as it
can be rather than silently rewritten:

- `uiFontSize`: clamped to 11.0 – 16.0 by `ThemeManager.setUiFontSize`,
- `terminalFontSize`: clamped to 10.0 – 18.0 by `TerminalThemes`.

The sliders expose the same ranges, so in normal use the clamp never fires.

### Who writes it

`RepositoryManager` is the only holder of the `ApplicationStateStore` and
already owns the equivalent writes (`updateSidebarWidth` /`updateTheme`,
`RepositoryManager.java:181,190`). It gains `updateUiFontSize(double)` and
`updateTerminalFontSize(double)` in the same shape: a state-transform
submitted to the single writer, never a load-then-save. `SettingsModal`
receives these as callbacks from the `DrydockApplication` wiring, exactly as
the existing modals do; it holds no reference to the store.

Because every ui write is a transform against the single writer, a
shutdown-time sidebar-width write cannot clobber a font size the modal wrote
(verified: `RepositoryManager.java:181,190`).

### `UserConfig` write support

`UserConfig` is read-only today. It gains:

- `save(UserConfig, Path configFile)` — creates the parent directory when
  absent, writes to a sibling temp file, then atomically moves it into
  place, so a crash mid-write cannot leave a truncated config.
- `saveAsync(UserConfig)` — as `save`, on a virtual thread, returning a
  `CompletableFuture<Void>`, because the caller is on the FX thread.

- `flushPendingSaves()` — awaits any in-flight `saveAsync`, mirroring
  `AnnotationStore.flushPendingSaves`, per AGENTS.md's rule that a service
  writing files from a background thread must expose a flush so tests and
  shutdown do not race pending writes. Called from `DrydockApplication.stop()`
  alongside the existing flushes.

Failure is surfaced to the caller (the future completes exceptionally); it
is not swallowed. This is deliberately asymmetric with `load()`, which
tolerates a missing or malformed file and falls back to `empty()` — a failed
*save* is a user-visible action that silently did nothing, so it must be
reported.

## Interface font size

All 142 `-fx-font-size` declarations live in a single file, `app.css`; there
are none in Java, none in the theme sheets, and no `-fx-font` shorthand or
`Font.font(…)`/`setFont(…)` call anywhere in `app/src`.

**`app.css` is not rewritten.** Instead, `UiFontScale` (new, package-private
in `app.drydock.ui`) generates a scaled copy of the sheet at runtime: it
reads `app.css` from the classpath, multiplies every `-fx-font-size: <n>px`
literal by `size / 13.0`, and writes the result to a temp file whose URL is
used in place of `app.css` in `scene.getStylesheets()`. Results are cached
per size, and the scale-1.0 case returns the original resource URL
untouched. This mirrors the extract-to-temp-file pattern `TerminalThemes`
already uses for ghostty configs.

Two approaches were rejected, both of which look correct and are not:

- **Converting the declarations to `em`.** JavaFX resolves font-relative
  sizes against the font inherited from the nearest styleable ancestor, not
  against `.root`, so nested rules would compound: `.code-area` (12px,
  `app.css:1195`) containing `.lineno` (11px, `app.css:1206`) would yield
  13 × 0.923 × 0.846 = 10.15px instead of 11px. Every converted line looks
  individually correct, so the error would not survive review — it would
  survive *past* it.
- **An inline `-fx-font-size` on the scene root.** It does override the
  `.root` rule, but combo popups, context menus and tooltips are separate
  scene graphs that an inline style on this scene's root never reaches —
  a fact `app.css:1304-1306` already documents. They would stay at 13px
  while the rest of the UI scaled. A *stylesheet* does reach them, which is
  precisely how app.css styles `.combo-box-popup .list-cell` and
  `.menu-item .label` today.

`ThemeManager` owns the swap, since it already owns
`scene.getStylesheets().setAll(app.css, theme.css)`:

- `setUiFontSize(double)` — clamps to 11–16, generates/looks up the scaled
  sheet, and re-applies both stylesheets.
- `setTheme(UiTheme)` — an absolute setter; only `toggle()` exists today
  (`ThemeManager.java:52-56`) and the modal's radio needs to set a specific
  value. `toggle()` is reimplemented in terms of it, and both keep firing
  `onThemeChanged` so the title-bar glyph and persistence stay in sync.
- The constructor takes the persisted `uiFontSize` alongside `initialTheme`,
  so the scaled sheet is in place before first layout rather than being
  applied as a visible re-layout after startup.

Generating the sheet touches the filesystem, so it runs off the FX thread on
first use for a given size and is applied via `Platform.runLater`; the
startup case is generated before the scene is shown.

Scaling covers font sizes only. Stock JavaFX controls (modena) express
padding in `em`, so their padding scales with the root font size for free;
`app.css`'s own fixed `-fx-min-height`/`-fx-max-height` rules (24 of each,
e.g. `.filter-field` 32px, `.icon-button` 30px, `.title-bar` 44px) do not.
That mismatch, not the text itself, is what bounds the range: 11–16 is the
band to be confirmed by the manual check below, and the slider is capped
there.

## Terminal font size

The size goes into the generated ghostty config file, not the surface
struct. `TerminalThemes.configFileFor(UiTheme)` (`TerminalThemes.java:28-46`)
already extracts a per-theme `.conf` to a temp file; it gains the font size
as a second parameter, keys its cache on `(theme, size)`, and appends a
`font-size = <n>` line to the extracted config.

Applying a change then reuses the path a theme toggle already takes:
`MainWorkspace.applyTerminalTheme` (`MainWorkspace.java:435-441`) loops the
open tabs, and `TerminalBridge` (`TerminalBridge.java:345-357`) calls
`ghostty_app_update_config` followed by `ghostty_surface_update_config` on
each surface (`GhosttySurface.java:276-285`). So the terminal size applies
**live to already-running sessions**, like the theme does. The `Applies to
new sessions` caption in the mockup is therefore dropped.

The rejected alternative was the per-surface route: `ghostty_surface_config_s`
does declare an unset `font_size` float at offset 32
(`GhosttyAppBinding.SURFACE_CONFIG_LAYOUT`), and writing it at
`ghostty_surface_new` looks like the natural seam. It is a trap — the very
next theme toggle calls `ghostty_surface_update_config`, which re-derives the
surface's config from the app config and drops the per-surface override, so
the user's terminal size would silently reset mid-session. It would also
have rippled a new component through `TerminalSpec`, `TerminalRuntime`,
`GhosttyApp`, `GhosttySurface.create`, `OpenSessionTab`, `SessionManager`,
three spike source sets, and `TerminalSpecTest`. The config-file route
changes one class.

One assumption to verify before building on it: that libghostty honours
`font-size` in a config file loaded via `ghostty_config_load_file`.
`third_party/ghostty` is an unpopulated submodule, so it cannot be confirmed
from this checkout. **Verification step, first task in the plan:** append
`font-size = 20` to a `terminal-*.conf`, run the app, open a session, and
confirm the text is visibly larger. If it is not honoured, terminal font
size is cut from this round rather than reinstating the per-surface route.

## The modal

`SettingsModal` — new, package-private, in `app.drydock.ui`, structured like
`StartSessionModal`: a `VBox` with the `modal` style class, hosted in the
existing `ModalLayer`. That gives Esc-to-close, backdrop-click-to-close, and
automatic hiding of the native terminal view while the modal is up
(`ModalLayer.setOnShowingChanged`) with no new machinery.

```
┌─ Settings ──────────────────────────────── × ┐
│ Appearance                                    │
│   Theme           ( ● Dark   ○ Light )        │
│   Interface size   11 ──●───── 16     13 px   │
│   Terminal size    10 ────●─── 18     13 px   │
│                                               │
│ Worktrees                                     │
│   Directory   [ ~/dev/wt          ] [Browse…] │
│               New worktrees are created here  │
│                                      [ Done ] │
└───────────────────────────────────────────────┘
```

Behaviour:

- **Apply on change**, no OK/Cancel — the macOS preferences convention.
  `Done` (and Esc, and the `×`) simply close.
- **Theme** radio → `ThemeManager.setTheme`, which already routes through the
  callback that updates the title-bar glyph and persists the choice. The
  title-bar toggle and the modal therefore cannot drift: both read the
  manager's current theme.
- **Interface size** slider → `ThemeManager.setUiFontSize` live while
  dragging, so the effect is visible immediately; the state write is
  debounced and happens on release, not per pixel.
- **Terminal size** slider → same shape: applied live to open surfaces via
  the existing theme-reapply path, persisted on release.
- **Worktrees directory** → `UserConfig.loadAsync` when the modal opens, with
  the field disabled and showing a "Loading…" prompt until it resolves;
  `DirectoryChooser` behind `Browse…`; `saveAsync` on commit with the field
  and Browse button disabled until it completes. Every completion path —
  success, failure, and early return — re-enables the controls. A save
  failure is reported through `UiErrors`.

The modal holds no blocking I/O on the FX thread, per the repository's async
rules.

### Opening it

`⌘,` is handled in `DrydockApplication`'s existing scene key filter, and is
**inert while a modal is already showing** (`modalLayer.isShowingModal()`),
so it cannot replace an open Start-session or New-worktree modal underneath
the user. The gear button is unreachable in that state anyway, since the
backdrop covers the title bar.

## Testing

Following the repository's existing pure-logic test style
(`NewWorktreeStateTest`, `ApplicationStateCodecTest`, `UserConfigTest`):

- `ApplicationStateCodecTest`: round-trip of `uiFontSize` /
  `terminalFontSize`; absent members decode to defaults; non-numeric members
  decode to defaults; an out-of-range number survives the decode unchanged
  (clamping belongs to the consumer); none of these fail the overall decode.
- `UserConfigTest`: `save` → `load` round-trip; save over an existing
  malformed file; save when `~/.drydock` does not yet exist; the temp file
  is not left behind.
- `UiFontScaleTest` — the substantive new unit test, since this is where the
  rejected `em` approach would have gone wrong silently. Against a fixture
  stylesheet: every `-fx-font-size: Npx` is scaled by the factor and nothing
  else in the text is touched (colors, `-fx-min-height`, `em` values,
  comments, `-fx-font-family`); a nested rule pair scales
  proportionally rather than compounding (12px/11px at factor 16/13 →
  14.77px/13.54px, ratio preserved); fractional sources like `12.5px`
  survive; scale 1.0 is an identity that returns the original resource.
- A small pure helper for slider clamping / display formatting, tested
  directly, keeping `SettingsModal` itself thin enough to need no UI test.
- `docs/manual-terminal-checklist.md` gains two steps: (a) with a session
  open, change the terminal size and confirm the **running** surface resizes
  and survives a subsequent theme toggle without reverting; (b) sweep the
  interface size across 11–16 and confirm no clipping in the title bar,
  filter field, icon buttons, combo popups, context menus, and tooltips —
  the fixed-height rules and separate popup scene graphs being the two known
  risks.

## Deliberately out of scope

- **Default base branch.** `NewWorktreeModal` already defaults the fork-from
  field to the repository's checked-out branch, which is a better default
  than any global value; a global override would fight it.
- **Skip delete confirmations.** Cheap to wire, but it removes the only
  safety net on an irreversible, destructive action.
- **Per-repository settings.** `RepositorySettings` stays an empty record;
  nothing in this round is repository-scoped.
- **Scaling padding, spacing, and fixed heights.** Only font sizes scale.
  Making the fixed `-fx-min-height`/`-fx-max-height` rules scale too would
  mean scaling the title bar and traffic lights, which are sized against
  macOS conventions. The capped 11–16 range is the accepted trade.
