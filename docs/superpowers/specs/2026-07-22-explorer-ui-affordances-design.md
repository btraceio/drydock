# Explorer UI affordances: caret, search-row path overlay, closable file tabs

Date: 2026-07-22

Three independent defects in the session Explorer, all in the "the affordance
exists but is invisible" family. Each is fixed in isolation; none share code.
One of them (§3) also unmasks a pre-existing silent failure, fixed here
because the fix is what makes it reachable.

## 1. The editor has no visible caret

**Cause.** `app.css:1136-1138` hides it unconditionally:

```css
.code-area .caret { -fx-stroke: transparent; }
```

RichTextFX's caret node is a `javafx.scene.shape.Path` carrying the style
class `caret`, so `-fx-stroke` is the property that paints it — the rule is
the whole cause. The library's `CaretNode` binds its visibility to
`focused AND editable AND !disabled` (`GenericStyledArea`'s
`autoCaretBlinksSteam`, with the default `CaretVisibility.AUTO` that this app
never overrides), and it drives its own blink.

**Fix.** One line, no Java change:

```css
.code-area .caret { -fx-stroke: -drydock-code-text; }
```

A read-only viewer stays caret-free by construction, because
`FileViewer.java:781` leaves the area non-editable until
`attachEditing` (`FileViewer.java:966`) flips it — the library, not CSS, is
what suppresses the caret there. An earlier draft of this spec added an
`editable-code` style class to gate the rule; that gate is redundant and is
not implemented.

Two consequences to record rather than fix:

- A `disarmed` or `CONFLICT` session keeps `setEditable(true)`
  (`FileEditSession.java:538,805`), so the caret blinks in a buffer whose
  writes are refused. That is correct — the text really is editable, only the
  save is blocked, and the edit banner already says so.
- `reload` restores the caret by clamped paragraph/column
  (`FileViewer.java:515,534-536`). An external edit that shortens the file
  will now visibly jump the caret. Acceptable, but it becomes observable for
  the first time, so it is on the verification list.

`FileViewer.java:779` is the only `new CodeArea` in the app (`DiffOverlay` is
a state holder with no node), so this rule has exactly one consumer today.
`FileViewer.java:780` re-adds the `code-area` class that `CodeArea`'s own
constructor already adds; the duplicate is harmless and is left alone.

No new `Animation.INDEFINITE` is introduced — the blink already runs — so
`stopTransitions()` (`FileViewer.java:340`) needs no new entry.

## 2. Search results show no full path

**Cause.** Result rows show a filetype badge and the bare file name. Files
mode appends a relative-path label (`SearchRail.java:336-338`); Text mode
shows a match-count pill instead and no path at all. A row for
`FileViewer.java` is therefore ambiguous whenever several modules define the
same name — always in Text mode, and in Files mode whenever the label
ellipsizes, which the 324px fixed rail (`SessionExplorerView.java:28`,
`fitToWidth` with `hbarPolicy NEVER` at `SearchRail.java:194-195`) makes
common.

**Fix.** `Tooltip.install(row, tip)` — the rows are `HBox`es, not `Control`s,
so `setTooltip` is unavailable — carrying the session-relative path, with the
show delay shortened from JavaFX's 1000ms default to 400ms.

Scope and lifecycle:

- File rows only. The tooltip goes on the `HBox row` built at
  `SearchRail.java:322`, **not** the enclosing `VBox group` that
  `buildFileRow` returns (`SearchRail.java:342,369`) — installing on the group
  would cover the nested match lines too.
- Match lines get no tooltip. They sit directly under their file row, which
  already answers "which file", and a text query yields one row per match —
  hundreds of popup registrations for a path shown two pixels above.
- No tooltip cache. Rows are rebuilt per render, but `Tooltip.install` stores
  the tooltip in the *node's* property map with no back-reference, so a
  discarded row takes its tooltip with it. Caching (as `RepositorySidebar`
  does at `:1220-1228`, with `retainAll` pruning at `:516`) would buy one
  object allocation per row against a full node-tree rebuild, in a path
  already debounced 150ms (`SearchRail.java:58`) — and it would add a
  pruning-lifecycle bug surface for nothing.
- No new CSS: `.tooltip` is already themed at `app.css:795-803`.

Out of scope: result rows are mouse-only today — every control in them is
`setFocusTraversable(false)` (`SearchRail.java:295,308`) — so the tooltip is
mouse-only too. Making the rail keyboard-navigable is a separate change.

## 3. Explorer file tabs cannot be closed

**Cause.** They already are closable: `FileViewer.java:175` sets
`TabClosingPolicy.ALL_TABS`, and the close path is complete (conflict veto at
`FileViewer.java:872`, flush and deregistration in `closeTab`). The close
button is all but invisible, through a style-class collision.
`OpenSessionTab.java:546` gives its custom close `Button` the style class
`tab-close-button` — also the class JavaFX's `TabPaneSkin` puts (via
`setAll`) on the close `StackPane` inside every `Tab`. For that StackPane the
cross glyph *is* its `-fx-background-color` over a `-fx-shape`
(modena.css:1696-1704), and `app.css:369` sets it to `transparent`. Author
stylesheets beat the user-agent sheet regardless of modena's higher
specificity, so the glyph disappears. Modena's white dropshadow survives, so
what is actually on screen is a faint ghost on hover — not literally nothing.

**Fix.**

- Rename the custom button's class to `session-tab-close`
  (`OpenSessionTab.java:546` plus the two rules at `app.css:369-382`), so app
  styling no longer reaches into any `TabPane`'s internals. Safe because
  those three sites are the only users of the name, and because
  `MainWorkspace.java:229` sets the session strip to
  `TabClosingPolicy.UNAVAILABLE` — its skin never creates a close button, so
  the rename cannot make a second ✕ appear there.
- Add `.viewer-tabs .tab-close-button`, restoring what the renamed rule used
  to supply (17px box, zero padding, hand cursor) and painting the glyph
  `-drydock-text-faint`, `-drydock-text` on hover. Set `-fx-effect: null` to
  drop modena's white dropshadow, which reads as muddy against the light
  theme. No hover plate here, unlike `.session-tab-close`: modena shapes this
  StackPane, and JavaFX fills every background layer through that shape while
  ignoring `-fx-background-radius`, so the ✕ can only change colour. (Picking
  follows the shape too, so a plate would light up only while the cursor sat
  on the glyph's own strokes.)
- Cap `.viewer-tab-label` at 160px (`app.css:1064`), matching the session tab
  labels (`OpenSessionTab.java:1220-1223`). The graphic is badge + name +
  dirty dot and the ✕ now sits beside it; without a cap a long filename
  crowds the strip.
- Style `.viewer-tabs > .tab-header-area > .control-buttons-tab`, which
  `app.css:342` currently provides only for `.session-tabs`. Wider tabs reach
  JavaFX's overflow menu sooner, and its default chrome is modena blue-grey
  inside a `-drydock-tabbar` strip.

**Unmasked defect: a save that fails during close is swallowed.** `closeTab`
flushes asynchronously (`FileViewer.java:1060`). If that write fails,
`onSaveFailed` calls `showSaveErrorBanner`, which returns immediately because
`ownsBannerRow(tab)` is false once the tab has left the pane
(`FileViewer.java:714-719`). Its comment says the selection listener
re-derives the error from the session's `ERROR` state — but the tab is gone
and `sessions.remove` has dropped the session, so nothing ever does. The user
loses the edit with no indication. Today this needs the programmatic close
path to hit it; after this change it is one click on a dirty tab.

Fix: in `closeTab`'s `pending.whenComplete` (`FileViewer.java:1069`), if the
session ended in `State.ERROR`, log at WARNING with the path and
`session.lastError()` before dropping it. A background write that fails must
not be indistinguishable from one that succeeded. Surfacing it in the UI
(re-raising a banner on whatever tab remains) is deliberately not done here —
there is no owning tab to attach it to, and inventing a viewer-level
notification is a larger change than this fix warrants.

Also unstated but true: nothing persists open Explorer tabs — no `explorer`
key exists under `app/src/main/java/app/drydock/state`. Closing is
irreversible across restarts.

⌘W is out of scope. `DrydockApplication.java:216` routes it to window close,
`FileViewer.java:977-982` intercepts only ⌘S, and JavaFX `TabPane` has no
middle-click close. Adding a tab-close binding would also require a
`ShortcutsOverlay` row for advertised↔bound parity (AGENTS.md:86-88). File
tabs stay mouse-closable, deliberately.

## Verification

There is no TestFX dependency (`app/build.gradle.kts:77-78` lists JUnit only)
and the Explorer's tests are pure logic (`FileContentTest`,
`FileEditSessionTest`, `SyntaxHighlighterTest`). Adding a UI test harness is
out of scope; this is verified by running the app.

**What the 2026-07-22 pass could and could not reach.** Neither documented
route worked on the development machine that day:

- `screencapture` failed with "could not create image from display" — the
  agent's process does not hold macOS Screen Recording permission, whatever
  the shell that granted it once did. `docs/architecture.md` claims this
  permission is granted; that is now true only for some processes.
- The app launches and renders, but `ghostty_surface_new` returns NULL, so no
  session tab opens and the Explorer is unreachable from the running app.
  (`third_party/ghostty` was a dirty submodule at the time; not investigated,
  as it is unrelated to these changes.)

Two things came out of that. A `shot:<path>` verb was added to
`app.drydock.diag.explorerScript`: it writes a PNG of the window's scene graph
with `Scene.snapshot`, which needs no Screen Recording permission, cannot be
overlapped by another window, and does not need the app frontmost — but it
cannot see popup windows (tooltips, menus, the tab overflow list), which have
their own scenes. And the rendering itself was verified in a standalone
JavaFX harness that loads the real `app.css` + theme stylesheets and builds
the exact nodes under test (`viewer-tabs` `TabPane` with closable tabs, an
editable and a read-only `CodeArea`, a `Tooltip`), snapshotting each.

That harness confirmed, in both themes: the caret paints
(`stroke=0xc9ccd3ff` dark / `0x3b3d44ff` light — the resolved
`-drydock-code-text`, no longer transparent) and sits at the caret position;
the read-only area's caret node reports `visible=false`, so the library
suppresses it with no CSS gate, as §1 claims; the ✕ renders on every file tab;
a 63-character filename ellipsizes at the 160px cap; the tooltip renders
themed with the full relative path; and the overflow corner is a bare themed
▾ rather than modena's blue-grey pill.

It could not confirm the in-app behaviours: hover states, a real close click
and its flush, the conflict veto, and the tooltip attached to an actual search
result. Those remain unverified and need either a machine where a session
opens or a human.

The repo's diagnostic driver is the vehicle for the caret:
`app/build.gradle.kts:104-110` forwards `-Papp.drydock.diag.*`, and
`DrydockApplication.java:348-390` implements `explorerScript` with
`open`/`type`/`mark`, reaching `FileViewer.diagType`
(`FileViewer.java:1330`) which already calls `requestFocus()`. Because the
caret blinks, confirm it across several frames rather than one screenshot.

Checklist:

1. Caret blinks in an editable file with the editor focused; a read-only file
   (binary/oversized) shows none.
2. Caret survives `reload` with an external edit that shortens the file.
3. Hovering a search result in Text mode shows the session-relative path;
   Files mode shows it too, untruncated, where the inline label ellipsizes.
4. Explorer file tabs show a themed ✕ in both light and dark themes; clicking
   it closes the tab and flushes.
5. The unsaved-conflict veto still refuses the close and re-raises its banner.
6. Session tabs (top strip) are visually unchanged after the class rename.
7. Open enough Explorer file tabs that the header area overflows and the
   tabs-menu button appears: its corner is themed, not modena blue-grey, in
   both themes.

Items 3, 4 and 7 have no diag verb (no hover, no tab-close, no second-file
open) and are hand-verified.
