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
- member present but not a number → default,
- number outside the allowed range → clamped to the range.

A malformed value is never a reason to fail the whole decode. The change is
purely additive to the JSON schema; documents written before this change
decode to the defaults.

Ranges (also enforced by the sliders):

- `uiFontSize`: 11.0 – 16.0
- `terminalFontSize`: 10.0 – 18.0

Writes go through the existing single-writer `ApplicationStateStore`; the
modal never does its own load-then-save.

### `UserConfig` write support

`UserConfig` is read-only today. It gains:

- `save(UserConfig, Path configFile)` — creates the parent directory when
  absent, writes to a sibling temp file, then atomically moves it into
  place, so a crash mid-write cannot leave a truncated config.
- `saveAsync(UserConfig)` — as `save`, on a virtual thread, returning a
  `CompletableFuture<Void>`, because the caller is on the FX thread.

Failure is surfaced to the caller (the future completes exceptionally); it
is not swallowed. This is deliberately asymmetric with `load()`, which
tolerates a missing or malformed file and falls back to `empty()` — a failed
*save* is a user-visible action that silently did nothing, so it must be
reported.

## Interface font size

All 142 `-fx-font-size` declarations live in a single file, `app.css`; there
are none in Java and none in the theme sheets. They are converted to `em`
units relative to the `.root` base (`N px` → `N/13 em`), leaving `.root` at
`-fx-font-size: 13px`.

`ThemeManager` gains:

- `setUiFontSize(double)` — stamps `-fx-font-size: <n>px` as an inline style
  on the scene root, which overrides the `.root` rule and cascades to every
  `em`-relative declaration.
- `setTheme(UiTheme)` — an absolute setter; only `toggle()` exists today,
  and the modal's radio needs to set a specific value. `toggle()` is
  reimplemented in terms of it, and both keep firing `onThemeChanged` so the
  title-bar glyph and persistence stay in sync.

Padding, spacing, and fixed heights remain in px and therefore do not scale.
The 11–16px range is chosen so layouts stay intact across it; a wider range
would clip.

## Terminal font size

`ghostty_surface_config_s` already declares a `font_size` float at offset 32
(`GhosttyAppBinding.SURFACE_CONFIG_LAYOUT`) that nothing currently sets, so
libghostty applies its own default.

- `TerminalSpec` gains a `float fontSize` component; `0` means "use
  libghostty's default".
- `GhosttySurface.create` writes the field when the value is `> 0`, using an
  offset derived from `SURFACE_CONFIG_LAYOUT` via
  `PathElement.groupElement("font_size")` rather than a hard-coded literal,
  per the repository's native-interop rule on struct writes.
- `TerminalSpec.loginShell` and every other construction site passes the
  persisted setting.

The field is per-surface and read at `ghostty_surface_new` time, so a change
takes effect for **newly opened sessions only**; already-running surfaces
keep their size. The modal states this explicitly under the slider rather
than pretending otherwise.

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
│                    Applies to new sessions    │
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
- **Terminal size** slider → persisted only; read at the next surface
  creation.
- **Worktrees directory** → `UserConfig.loadAsync` when the modal opens, with
  the field disabled and showing a "Loading…" prompt until it resolves;
  `DirectoryChooser` behind `Browse…`; `saveAsync` on commit with the field
  and Browse button disabled until it completes. Every completion path —
  success, failure, and early return — re-enables the controls. A save
  failure is reported through `UiErrors`.

The modal holds no blocking I/O on the FX thread, per the repository's async
rules.

## Testing

Following the repository's existing pure-logic test style
(`NewWorktreeStateTest`, `ApplicationStateCodecTest`, `UserConfigTest`):

- `ApplicationStateCodecTest`: round-trip of `uiFontSize` /
  `terminalFontSize`; absent members decode to defaults; non-numeric members
  decode to defaults; out-of-range numbers clamp; none of these fail the
  overall decode.
- `UserConfigTest`: `save` → `load` round-trip; save over an existing
  malformed file; save when `~/.drydock` does not yet exist; the temp file
  is not left behind.
- A small pure helper for slider clamping / display formatting, tested
  directly, keeping `SettingsModal` itself thin enough to need no UI test.
- `docs/manual-terminal-checklist.md` gains a terminal-font-size step: open a
  session, change the setting, open a second session, confirm the new
  surface uses the new size and the first is unchanged.

## Deliberately out of scope

- **Default base branch.** `NewWorktreeModal` already defaults the fork-from
  field to the repository's checked-out branch, which is a better default
  than any global value; a global override would fight it.
- **Skip delete confirmations.** Cheap to wire, but it removes the only
  safety net on an irreversible, destructive action.
- **Live terminal resize.** Would need a libghostty API call against running
  surfaces; the new-sessions-only behaviour is stated in the UI instead.
- **Per-repository settings.** `RepositorySettings` stays an empty record;
  nothing in this round is repository-scoped.
