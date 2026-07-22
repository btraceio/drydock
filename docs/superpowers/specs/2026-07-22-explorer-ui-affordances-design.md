# Explorer UI affordances: caret, search-row path overlay, closable file tabs

Date: 2026-07-22

Three independent defects in the session Explorer, all in the "the affordance
exists but is invisible" family. Each is fixed in isolation; none share code.

## 1. The editor has no visible caret

**Cause.** `app.css:1136` hides it unconditionally:

```css
.code-area .caret { -fx-stroke: transparent; }
```

RichTextFX's `CodeArea` already maintains a blinking caret with `AUTO`
visibility (painted only while the area has focus). Nothing else is missing.

**Fix.** Keep the transparent default — read-only viewers must stay
caret-free, so the editor does not invite typing where typing is refused —
and paint the caret only for editable files:

```css
.code-area.editable-code .caret { -fx-stroke: -drydock-code-text; }
```

`FileViewer.attachEditing` (`FileViewer.java:933`) is the single place a tab's
area flips to editable, so it adds the `editable-code` style class alongside
`area.setEditable(true)`. Areas that never reach `attachEditing` keep the
default and stay caret-free.

Blinking is the library's own, driven by focus; no `Animation.INDEFINITE` is
introduced, so `stopTransitions()` needs no new entry.

## 2. Search results show no full path

**Cause.** Result rows show a filetype badge and the bare file name; in Files
mode a relative path label is appended, in Text mode it is not. A row for
`FileViewer.java` is therefore ambiguous when several modules define the same
name.

**Fix.** Install a JavaFX `Tooltip` carrying the session-relative path on both
row kinds — `SearchRail.buildFileRow` (`SearchRail.java:292`) and
`SearchRail.buildMatchLine` (`SearchRail.java:372`). Match rows already render
their line number, so the tooltip is the path alone.

Rows are rebuilt on every keystroke, so tooltips are cached by relative path
in a `Map<Path, Tooltip>` field and reused across rebuilds — the pattern
`RepositorySidebar.java:1220-1228` already uses for its rebuilt session rows.
The cache is cleared wherever the rail drops its result state.

## 3. Explorer file tabs cannot be closed

**Cause.** They already are closable: `FileViewer.java:175` sets
`TabClosingPolicy.ALL_TABS`, and the close path is complete (conflict veto at
`FileViewer.java:872`, flush and deregistration in `closeTab`). The close
button is merely invisible, through a style-class collision:
`OpenSessionTab.java:546` gives its custom close `Button` the style class
`tab-close-button`, which is also the built-in class JavaFX puts on the close
`StackPane` inside every `Tab`. For that StackPane the cross glyph *is* its
`-fx-background-color`, and `app.css:369` sets it to `transparent`.

**Fix.** Two parts:

- Rename the custom button's class to `session-tab-close`
  (`OpenSessionTab.java:546` and the two rules at `app.css:369-382`), so the
  app's own styling no longer reaches into any `TabPane`'s internals. This
  also protects every future `TabPane`.
- Style the built-in button explicitly for the Explorer strip:
  `.viewer-tabs .tab-close-button` draws the cross in `-drydock-text-faint`,
  brightening to `-drydock-text` on hover, so it matches the theme rather than
  inheriting Modena's default mark colour.

No Java behaviour changes; the existing close, veto and flush paths are what
the newly visible button triggers.

## Verification

Unit tests cover none of this (it is rendering). Verification is by launching
the app and confirming on screen: a blinking caret in an editable file and
none in a read-only one, a path tooltip on hover over a search result in both
Files and Text mode, and a visible, working close button on Explorer file tabs
including the unsaved-conflict veto still firing.
