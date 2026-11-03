# Review workbench — spikes S1–S4 (IU-263.7031.23)

Spec: `docs/superpowers/specs/2026-11-03-intellij-review-workbench-design.md` §9.
Plan: `docs/superpowers/plans/2026-11-03-intellij-review-workbench.md` §0.
Branch: `spike/review-workbench`. Date: 2026-11-03.

## How the spikes were run

The plan asks for actions run by hand in `runIde`. These spikes ran as
headless `BasePlatformTestCase` harnesses instead, in
`intellij-plugin/src/test/java/app/drydock/intellij/review/spike/`
(`SpikeS1NavigationTest`, `SpikeS2CardsTest`, `SpikeS3SearchScopeTest`,
`SpikeS4HostingTest`). Each harness writes its observations to
`intellij-plugin/build/spike-reports/<spike>.txt`; the values below are
copied from those files. S2(a) is a bytecode check of the IDE jars.

```
cd intellij-plugin && ./gradlew test --tests 'app.drydock.intellij.review.spike.*'
```

Result: 4 tests, 0 failures, 0 errors. This includes the test framework's
own tearDown checks for unreleased editors.

Spike-only setup on the branch:

- `build.gradle.kts` adds `bundledPlugin("com.intellij.java")`, so the
  harnesses get Java PSI. Main does not need it.
- Each harness calls `HeadlessDataManager.fallbackToProductionDataManager`.
  The headless test `DataManager` does not walk the Swing hierarchy, so
  without this no diff data key reaches any component.

The headless runs could not cover real keystrokes, focus, screenshots,
scroll cost, the real `FileEditorManagerImpl` tab, a restart, or a
project on disk. Those ran afterwards in a sandbox IDE driven by the
Robot server plugin; see "Live runIde checks (robot-driven)" below.

## S1 — navigation from a file-backed diff

API: `DiffContentFactory.create(project, virtualFile)` (right),
`create(project, baseText, VcsUtil.getFilePath(file))` (left),
`SimpleDiffRequest` + `FORCE_READ_ONLY` + `CUSTOM_DIFF_COMPUTER` +
`SCROLL_TO_LINE`, `DiffManager.createRequestPanel`. Fixture: `User.java`
(changed, calls `new Foo().bar()` and has a `Foo field;`), `Foo.java`.

**Side-by-side, right editor**

- `editor.isViewer()` = true. The document is the project file's
  document (`==` `FileDocumentManager.getDocument(file)`), PSI is
  `PsiJavaFileImpl` on the real file.
- Caret on `bar()`: `TargetElementUtil` resolves to `PsiMethod:bar in Foo.java`.
- Data context: `PSI_FILE`, `PSI_ELEMENT` (= `bar`), `USAGE_TARGETS`,
  `DIFF_VIEWER`, `DIFF_REQUEST`, `CURRENT_EDITOR`, `DiffDataKeys.NAVIGATABLE`
  are all present.
- `GotoDeclaration`, `FindUsages`, `QuickJavaDoc`, `CallHierarchy`,
  `EditSource`: all enabled. Performing `GotoDeclaration` selects `Foo.java`.
  The Java documentation provider returns the `bar` javadoc.
- F4 (`DiffDataKeys.NAVIGATABLE.navigate`) opens `User.java` at the caret's line (4).
- `FORCE_READ_ONLY` keeps all of the above.

**Side-by-side, left editor (typed content)**

- `editor.isViewer()` = true, the document is not writable, and its PSI is
  a `PsiJavaFileImpl` on a `DiffContentFactory LightVirtualFile:
  /src/p/User.java`.
- **References on the left resolve too:** caret on `Foo field` →
  `PsiClass:Foo in Foo.java`. All five actions are enabled, and Ctrl+B
  selects `Foo.java`.
- Inference, not tested: the light file resolves in the project's
  *current* context. A base-side reference to a symbol that the change
  removed or renamed therefore resolves to nothing, or to the current
  symbol, not to the base revision.
- F4 from the left editor opened `User.java`, but at line 4, not at the
  caret's line 2. Headless, this cannot be separated from caret state
  restored from the earlier navigation. The live run settled it: F4 from
  the left maps through the diff correctly (see the live section).

**Unified viewer**

- `FORCE_DIFF_TOOL` is read from the `DiffContext` when
  `DiffRequestProcessor` is constructed (bytecode). As request user data it
  has no effect. `putContextHints` after `createRequestPanel` is too late,
  because the processor already exists.
- What does switch the viewer is the place's tool order:
  `DiffSettingsHolder.DiffSettings.getSettings().setDiffToolsOrder(...)`.
  The viewer's own Side-by-side/Unified switch writes that same setting.
- In `UnifiedDiffViewer`, the editor's document is the viewer's own
  combined document, not the project file's document, and
  `PsiDocumentManager.getPsiFile` returns null.
- No target resolves. `PSI_FILE`, `PSI_ELEMENT` and `USAGE_TARGETS` are
  absent, and all five actions are **disabled**.
- `DiffDataKeys.NAVIGATABLE` is present. `navigate` opens `User.java` at
  the matching line (4).

**`CUSTOM_DIFF_COMPUTER` with a file-backed side**

- Still applies. A fake fragment `[0,1)->[0,1)` over the identical
  `package p;` line is what the viewer reports as its only change.
- The computer is called once per rediff. The git fragments for this
  fixture are `[4,4)->[4,5)`.

**Live edit (new finding, not in the plan's questions)**

- The right side is the live project document, so an edit to the file
  while the diff is open makes the viewer rediff: computer calls went 1 → 2.
- The computer then returns the fragments built for the reviewed text.
  After two lines were inserted at the top, the viewer still marked
  `[4,5)` on the right, two lines above the real change.
- Nothing threw in this case. Fragments past the end of a shortened
  document were not tried.

**Verdict: PASS for side-by-side.** References resolve on both sides.
Unified offers diff-F4 only.

## S2 — section cards

**(a) `CombinedDiff*` — FAIL (not public)**

- All of `com.intellij.diff.tools.combined.*` ships in
  `intellij.platform.diff.impl.jar`, which is an internal-visibility
  product module on 263 (`kb/find-263-diff-api-internal`).
- Class-level annotations (bytecode, `RuntimeInvisibleAnnotations`):
  `CombinedDiffManager`, `CombinedDiffComponentProcessor`,
  `CombinedDiffVirtualFile`, `CombinedBlockProducer`, `CombinedPathBlockId`,
  `CombinedDiffBlockFactory` and `CombinedDiffRegistry` are
  `@ApiStatus.Experimental`. `CombinedDiffModel` and `CombinedDiffViewer`
  are `@ApiStatus.Internal`.
- `CombinedDiffViewer` also exposes a Kotlin `CoroutineScope`.

**(b) 15 stacked `DiffRequestPanel`s — PASS with caveats**

- Setup: 15 Java files of 402 lines, one inserted line after line 200.
  Each card is `createRequestPanel` + `putContextHints(PLACE,
  "DrydockReviewCard")`. `TextDiffSettings.getSettings(place)` has
  `expandByDefault=false` and `contextRange=4`.
- Collapse unchanged works with file-backed content: 6 collapsed fold
  regions, and 11 of 402 lines stay visible.
- Scroll to the hunk: the right editor's caret is on line 200 (the
  `SCROLL_TO_LINE` value).
- Cost, after a warm-up card:
  - Building the 15 panels on the EDT took 392–417 ms; all 15 were
    rediffed after 450–475 ms.
  - The 15 cards created 30 editors.
  - Heap: +15.0 MB for 15 cards with soft references cleared
    (`GCUtil.tryGcSoftlyReachableObjects`), about 1 MB per card. A plain
    `System.gc()` measured +21 MB, because the extra memory is
    softly-cached PSI.
- Lazy dispose: 11 off-screen cards were removed and their parent
  `Disposable` disposed.
  - The editors went 30 → 8 (4 kept cards × 2).
  - The heap went 15.0 → 10.6 MB, about 6.5 MB more than 4 cards
    explain. The class-histogram delta is mostly primitive arrays (`[I`
    4.1 MB, `[B` 2.5 MB), which the spike cannot attribute.
  - This is not an editor leak: the editors are gone and the framework's
    leak checks pass. The live run (below) shows that disposed cards are
    released and the heap growth is cache, not retention.
- Paint and scroll cost: not measured. Painting offscreen throws
  `IllegalComponentStateException` (the editor needs a component that is
  showing on screen). Measured live instead (below).

**Verdict:** (a) not usable under the "public API only" rule, so use (b).
Create cards lazily and dispose them when they leave a viewport margin.
Do not build all cards up front, because that costs about 25–30 ms of EDT
time per card.

## S3 — search scope

API: `SearchScopeProvider` (EP `com.intellij.searchScopesProvider`, public,
`intellij.platform.analysis.impl`) returning
`GlobalSearchScope.filesScope(project, files, "Drydock: files in this review")`.

- It is offered by `ScopeChooserCombo.init(project, false, true, null, null)`:
  the scope is in the combo model.
- `ScopeChooserCombo` is the chooser that both Find Usages
  (`AbstractFindUsagesDialog`, `UsageOptionsDialog`) and Find in Files
  (`FindPopupScopeUIImpl`) use (bytecode references).
- It is **not** returned by `PredefinedSearchScopeProvider.getPredefinedScopes`,
  which listed only `[All Places, Project Files, Scratches and Consoles]`.
  Code must not look the scope up there.
- Filtering: `ReferencesSearch.search(bar, reviewScope)` returns
  `[Changed.java]`, and the project scope returns `[Changed.java, Untouched.java]`.
  Find in Files (`FindInProjectUtil.findUsages`, custom scope) for `bar(`
  returns `[Changed.java, Foo.java]`. `Untouched.java` is excluded.

**Verdict: PASS.** Not seen: the scope in the real dialogs' drop-down
(needs `runIde`).

## S4 — hosting in a custom `FileEditor`

API: own `LightVirtualFile` + `FileEditorProvider` (`HIDE_DEFAULT_EDITOR`,
`DumbAware`) + `FileEditor` (the `AgentConsoleEditorProvider` shape). The
component is three nested `OnePixelSplitter`s (`drydock.spike.nav`,
`.inbox`, `.outbox`) around `DiffRequestPanel.getComponent()`. The panel
is parented to the `FileEditor`.

- `TestEditorManagerImpl` (the light-test `FileEditorManager`) opens text
  editors only. The harness therefore drives the provider the way
  `FileEditorManagerImpl` does: `createEditor`, then `Disposer.dispose(editor)`.
- Disposal, with `Disposer` debug mode on: the panel is disposed with the
  editor, and the editors went 2 → 0 after each of two create/dispose
  cycles. The framework's unreleased-editor check passes.
- Focus: `getPreferredFocusedComponent()` →
  `panel.getPreferredFocusedComponent()` is the **right** editor's
  `EditorComponentImpl`, inside our root. Real focus traversal was not
  tested (headless).
- Data keys: from the editor component inside the splitters, `DIFF_VIEWER`,
  `DIFF_REQUEST`, `CURRENT_EDITOR` and `NAVIGATABLE` are present.
  `PlatformDataKeys.FILE_EDITOR` resolves to the diff viewer's text-editor
  wrapper of `User.java`, not to our `FileEditor`.
- Splitters:
  - `setProportion(0.33)` writes `PropertiesComponent["drydock.spike.nav"] = 0.33`.
    Untouched keys stay unset.
  - `JBSplitter` *loads* the value only in `addNotify()` (first show) or
    `setAndLoadSplitterProportionKey` (bytecode). A new, never-shown
    instance therefore reads its default (0.2), and after
    `setAndLoadSplitterProportionKey` it reads 0.33.
  - Persistence across a real restart is `PropertiesComponent`'s own and
    was not exercised.

**Verdict: PASS** for disposal and data keys. The live run (below) adds the
real tab, focus, and a restart.

## Live runIde checks (robot-driven)

Setup (spike branch only):

- `build.gradle.kts` registers `runIdeForUiTests` with
  `robotServerPlugin()` (0.11.23) on port 8082.
- Spike actions `Drydock.Spike.Workbench` and `Drydock.Spike.Cards`
  (`src/platform/java/app/drydock/intellij/review/spike/`) load
  `git diff HEAD` off the EDT and open a real editor tab: the S4 layout
  with a navigator list, or 15 lazily created cards.
- Fixture: `intellij-plugin/spike-robot/make-fixture.sh`, a git repo on
  disk (`/tmp/drydock-spike-repo`) with `.idea` and a `src` source root,
  base committed and one insertion per file left uncommitted.
- Scripts in `intellij-plugin/spike-robot/` post Rhino code to
  `POST /js/execute`.
- Key presses are `KeyEvent`s posted to `IdeEventQueue`, so they go
  through the IDE's keymap dispatch. They are not OS-level input:
  `java.awt.Robot` would need macOS Accessibility permission. The sandbox
  window was brought to the front with `osascript` (System Events) before
  each press, because an inactive window has no focus owner.
- Shortcuts were read from the active keymap ("Mac OS X 10.5+"):
  GotoDeclaration ⌘B, FindUsages ⌥F7, QuickJavaDoc F1, CallHierarchy ⌃⌥H,
  EditSource ⌘↓.
- Screenshots paint the frame's root pane into a PNG
  (`notes/review-workbench-spikes/*.png`).

**S1, live**

- Right editor, caret on `bar()`:
  - ⌘B → `Foo.java`, caret on the line of `bar()`'s declaration (line 3).
  - ⌥F7 → Find tool window, content "bar() in Project Files".
  - F1 → documentation popup containing "Says hi".
  - ⌃⌥H → Hierarchy tool window, "Callers of bar".
  - ⌘↓ (EditSource) → `User.java`, line 4 (the caret's line).
- Left editor, caret on `Foo field`: ⌘B → `Foo.java`.
- F4 (⌘↓) from the left maps through the diff, each tried with
  `User.java` closed first: base lines 0–3 land on the same file lines,
  and base line 4 (below the insertion) lands on file line 5. The earlier
  headless "line 4" was leftover state.
- Harness hazard: ⌘↓ pressed while the navigator list had focus moved the
  list selection to another file. Pane focus has to be explicit.

**S4, live**

- The workbench opens in `FileEditorManagerImpl` as a real tab (screenshot
  `s1-workbench.png`). Closing the tab calls `FileEditor.dispose` once and
  releases the two diff editors (`EditorFactory` count 7 → 5).
- After reopening, the preferred focus and the actual focus owner are
  both the right editor.
- `KeyboardFocusManager.focusNextComponent()` from the right editor never
  leaves it. IDE editors keep traversal keys for themselves, so the
  workbench needs its own pane-to-pane focus actions and shortcuts (W1,
  AGENTS.md keyboard-access rule).
- Splitter across a restart:
  1. `setProportion(0.33)` on `drydock.spike.nav`.
  2. `Application.exit` wrote `"drydock.spike.nav": "0.33"` into the
     sandbox `options/other.xml`.
  3. After a relaunch, the reopened workbench's splitter reads 0.33 once
     shown (screenshot `s4-after-restart.png`).

**S2, live** (15 cards, viewport 844×793, cards 300 px high, lazy margin of
one card above and below)

- Cards render collapsed around the hunk, scrolled to it (screenshot
  `s2-cards.png`).
- Each card carries the full diff toolbar (viewer switch, settings) and no
  file name. W3's card header has to replace or hide that chrome.
- Scroll pass in 60 px steps (67 frames). Each frame is
  `setViewPosition` + lazy create/dispose + `paintImmediately`, timed on
  the EDT:
  - median 3.5 ms, p90 20.7 ms, max 32.6 ms;
  - the slow frames are the ones where a card is created;
  - at most 6 cards were live at once.
- Memory: `jcmd GC.class_histogram`, which forces a full GC.
  - Read 2 s after closing the tab, all 128 cards of the session were
    still in the heap. That was deferred disposal, not a leak: later
    readings showed them released.
  - With settle time, `DiffRequestPanelImpl` stays at 6 across six passes
    (120 more create/dispose cycles), and drops to 2 when the tab closes.
  - Heap rose by about 15 MB per pass and then fell back on its own
    (1049 → 982 MB), which is softly-held caches.
  - **No card retention.** The headless 6.5 MB residue is the same
    cache effect.
- Fixture caveat: no JDK is configured, so `hashCode()` shows as an error
  in the cards. This does not affect the measurements.

**Still not covered:** the review scope in the real Find Usages / Find in
Files drop-down (S3; covered by W1's own exit check), OS-level keystrokes
(as opposed to keymap-dispatched events), and a large real repository.

## Decisions for the spec

1. **§4 Unified vs side-by-side:** side-by-side is the default and the only
   mode with IDE navigation. Unified stays available through the viewer's
   own switch, with diff-F4 only (Ctrl+B / Alt+F7 / Ctrl+Q / Ctrl+Alt+H
   disabled there). Selecting the tool means the place's
   `DiffSettings` tool order, not `FORCE_DIFF_TOOL`.
2. **§4 Section view:** stacked `DiffRequestPanel` cards (S2(b)), one
   settings place for cards (`expandByDefault=false`, small context
   range), created lazily and disposed out of view.
3. **§2 (applied in the spec):**
   - Left-side references resolve, against the current project rather
     than the base revision (§2.3, §12).
   - The fragment computer compares `text2` against the reviewed text on
     every rediff, so an edited live document does not get stale git
     fragments (§2.2, §11, §12).
4. **§3:** `OnePixelSplitter(key)` persistence holds across a restart,
   as specified. Panes built before first show must not read
   `getProportion()` and expect the stored value.
5. **§3 (new):** focus does not leave a diff editor by traversal. The
   workbench needs explicit focus actions per pane.
