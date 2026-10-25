# IntelliJ plugin: findings triage (P3)

Status: design, 2026-10-24. Phase P3 of
`docs/superpowers/specs/2026-10-22-intellij-integrated-review-design.md`
(the "parent spec"), row "P3 — Findings in diff and editor". P1 (PR #41)
delivered the review backbone; P2 (PR #46) delivered the read-only Review
tab. P2 defect check 2026-10-24: a sandbox task whose agent never called
`review_*` shows no findings because none exist — with this phase, that
state becomes visible instead of silent.

Source research: cairn investigation `intellij-findings-triage` (wrap-up
`notes/intellij-findings-triage-wrapup.md`; source nodes
`intellij-findings-triage/2026-10-25-editor-area-diff-decoration-seam`,
`intellij-findings-triage/2026-10-24-platform-inlay-gutter-api-263`, and
`intellij-findings-triage/2026-10-24-triage-ask-comment-contracts`). Platform
API claims marked "263 bytecode" were checked with `javap` against
`idea-263.7031.23-aarch64`
(the build the plugin compiles against, `intellij-plugin/build.gradle.kts:134`)
on 2026-10-24. The display and decoration seams were verified in a live
IU-2026.3 sandbox on 2026-10-24 and 2026-10-25.

Scope confirmed with the operator: findings appear in the Review diff
**and** as editor gutter marks; human comments are in; the actions are
Confirm, Dismiss and ask-the-agent-to-fix; triage controls sit on the
inlay itself. The Review tree lives in its own tool window next to Changes.
One reusable diff tab in the editor area swaps its request when the user
selects another file. Clicking an editor gutter mark opens the Review tool
window on that file and finding.

Paths are relative to `intellij-plugin/src/` unless they start with `app/`
or `docs/`. `core` = `main/java` (zero platform classpath). `glue` =
`platform/java`.

## Goal

An agent's finding is visible where the reviewer reads the code, and the
reviewer can answer it without leaving the IDE:

- In the Review tab's diff, an inlay block opens under the finding's line:
  severity icon, title, author and age, the finding's thread (agent
  messages and human comments), a comment box, and three actions —
  Confirm, Dismiss, Ask the agent to fix.
- In the editor, files of the scope with open findings carry gutter icons
  at the finding's line.
- Confirm and Dismiss write the same `Triage` values the standalone app
  writes. Ask-to-fix hands the finding to the bound session through the
  same one-line prompt the app sends, and marks it `SENT` on success.

## Non-goals

- Tour (P4). Finish ▾ / PR submit (P5). PSI and inspections (P6).
- Explain and Deep review asks. The parent spec's P3 row names "Confirm /
  Dismiss… / ask agent" only; verdict-bar parity is a later round if asked.
- Per-hunk verdicts, "changes requested", base-move rechecks (P2 scope).
- Editing or deleting an agent's finding text; only triage, thread and
  SENT status are writable from the IDE.
- Sharing review state with the standalone app (parent-spec non-goal).
- Live anchor migration: findings anchor by exact line key
  (`integrated-review/find-finding-anchor-drift-gap`); a drifted finding
  shows with the P2 drift hint, not at a wrong line.

## Verified platform surface (263 bytecode)

| Claim | Evidence |
|---|---|
| `EditorComponentInlaysManager` is Deprecated in favour of `EditorComponentInlaysUtilKt.insertComponentAfter` | `javap -v` on `intellij.platform.collaborationTools.jar`, `kotlin.Deprecated(message=...)` |
| `com.intellij.collaboration.ui.codereview.editor.EditorComponentInlaysUtilKt` is in `intellij.platform.collaborationTools.shared.jar`, `@ApiStatus.Experimental` | `javap` |
| `insertComponentAfter(EditorEx, int line, JComponent, int flags, Function1 rendererFactory)` is static and callable from Java without coroutines. On 263 the renderer factory is non-null at runtime; Java must supply it rather than call the Kotlin default helper with `null`. | `javap` signatures; live probe first failed with the Kotlin non-null check, then rendered after supplying the factory |
| The bulk APIs (`controlInlaysIn`, `renderInlays`) require `kotlinx.coroutines` Flow / suspend; P3 does not need them because it rebuilds static components from store snapshots. | `javap` signatures; live Java-only probe |
| `DiffRequestProcessor.addListener` reports an applied viewer; `getActiveViewer()` yields the current `EditorDiffViewer`; `getHighlightEditors()` covers the unified editor or both side-by-side editors. `DiffViewerListener.onAfterRediff` fires after diff text is ready. | `javap -c -l` on `intellij.platform.diff.impl.jar`; live unified-viewer probe |
| `DiffExtension.onViewerCreated` did not fire for the plugin's panel-hosted viewers in two live runs. P3 must not use that EP. | live probe: six rendered requests, no extension load/callback or errors |
| The unified viewer creates its own document. Request-document matching cannot identify its editor, and `EditorImpl` is not an AWT `Component`, so a Swing component walk cannot find editors. | `javap -c -l`; failed probes confirmed both paths |
| Editor gutter marks need no collaboration API: `RangeHighlighter.setGutterIconRenderer`, `GutterIconRenderer`, `Inlay` are in `intellij.platform.editor.ui.jar`. A live probe rendered the gutter mark. | `unzip -l`, `javap`, live probe |
| Stored tool-window layout overrides the descriptor's default anchor after first registration. `ToolWindowFactory.init` runs during registration; content creation runs only when shown. | `javap -c -l` on `ToolWindowManagerImpl`; sandbox workspace layout |

Live verification covered a unified diff: the inlay rendered under its target
line and the gutter mark rendered. The processor listener fired again after a
request swap. Side-by-side placement follows the same
`getHighlightEditors()` list but still needs an implementation-phase live
check.

## System design

### Data flow (unchanged stores, one new prompt builder)

The tab already reads `store.forScope(scope.id())` per reload
(`ReviewTabProvider.wire`). Findings, triage, threads and SENT status all
live in `AnnotationStore` (`annotations.json`, its single writer); the IDE
writes through the same `mutate` paths the app uses. No new file writers,
no new persistence locations.

- **Confirm / Dismiss** → `store.mutate(key, with triage)`. The change
  listener the tab already registers (`marksChanged`) recomputes the tree
  marks; the same listener drives inlay rebuilds.
- **Comment** → append a `ReviewAnnotation.Message(author="You", at, text)`
  to the finding's `thread`. The agent reads it back through `review_answer`
  / `review_comments` — the tools the plugin already serves.
- **Ask the agent to fix** → one-line prompt built by a new core builder
  shared with the app (see "Core seam"), delivered through the P1
  `AgentExecutionRegistry.submitPrompt`; on hand-off success each handed
  finding is mutated to `AnnotationStatus.SENT`, exactly as the app does
  (`MainWorkspace.handFindingsToSession`).

### Core seam: no forked prompt text

The app's fix prompt is built in app-private
`MainWorkspace.handFindingsToSession` (`app/.../MainWorkspace.java:2465`):
heading `Address these review findings on "<subject>", then summarize what
you changed: ` plus `[n] <file> <startKey>: <title>. ` per finding, one
line. The plugin must not copy that string. New core code (shape:
`ReviewInstructions.forFix(subject, findings)`) holds the heading and the
per-finding rendering; the app's `handFindingsToSession` switches to it and
the plugin calls the same builder. Per the parent-spec rule, the change
lands identically in both trees (`app/src/main/java` and the plugin's core
copy) in its own commit, before the plugin's first use.

### Review surface and diff inlays

- `Drydock Review` is a dedicated project tool window, not a
  `changesViewContent` subtab. Its factory applies the existing
  `ReviewTabVisibility` condition and aligns it with the first available
  Changes host from `ToolWindowFactory.init`. A project message-bus listener
  realigns it when that host moves. The descriptor's anchor is only the
  initial-layout default; runtime alignment also updates the stored layout.
- The tool window contains the Review header, file tree and status. It does
  not contain a split diff. Selecting a file opens one project-owned reusable
  `DiffVirtualFile` in the editor area. Later selections update that file's
  processor request in place, so the Review flow never creates one editor tab
  per file. If the user closed the reusable tab, the next selection reopens
  it. The newly-created processor is seeded with the file's current request;
  otherwise the first `selectNotify -> updateRequest` has nothing to apply.
- The reusable `DiffRequestProcessor` owns a
  `DiffRequestProcessorListener`. After each viewer change, the implementation
  gets the active `EditorDiffViewer` and attaches a viewer-owned
  `DiffViewerListener`. Decoration runs after `onAfterRediff`, or immediately
  when no rediff is pending. This is the only diff-editor discovery seam;
  P3 does not register `DiffExtension`, match request documents, install a
  global `EditorFactoryListener`, or walk Swing components.
- For each open finding on the right (new) side: one inlay block inserted
  after the finding's line in the applicable highlight editor, content built
  once per rebuild: severity icon + title + author/age, thread messages
  (read-only), a comment box (commit on Enter), and Confirm / Dismiss / Fix
  buttons. PROPOSED findings show the three actions; CONFIRMED/DISMISSED show
  the current state and an Undo back to PROPOSED (the store keeps the value —
  `Triage` is a write, not a delete).
- Rebuild on the store change listener and on tab reload, coalesced to one
  rebuild per burst. Before replacement, dispose each previous `Inlay` and
  remove each previous `RangeHighlighter`; `RangeHighlighter` is not
  `Disposable` on 263. Viewer listeners are parented to their processor or
  viewer so a closed tab retains nothing.
- Findings whose `startKey` is not in the current diff's row keys render at
  their file's first hunk with the P2 drift hint, never at a guessed line.

### Editor gutter marks

- A project service starts during Drydock's existing post-startup warm-up,
  independently of the Review tool window. It resolves the project's scope,
  diff and findings off the EDT, then keeps the set of open source editors
  whose file belongs to that scope's diff (paths from `UnifiedDiff.FileDiff`).
  Marks therefore appear after project warm-up even if Review has never been
  opened; lazy tool-window content is not their data source.
- Open findings with a valid new-side line get a `RangeHighlighter` at that
  line with a `GutterIconRenderer` (severity icon, tooltip = title, count
  badge when a line holds several). Old-side-only or drifted anchors do not
  guess a source-editor line; they remain visible in the Review diff.
- Clicking a gutter icon activates the dedicated Review tool window through
  `ReviewTabOpener`, selects the finding's file and finding, and opens the
  reusable diff tab. The operator selected this behavior over a source-editor
  popup on 2026-10-25: there is one triage UI, in the diff, not two.

### Threading

| Work | Thread |
|---|---|
| Store reads/writes (`mutate`, triage, comments) | plugin executor, off the EDT |
| Inlay/gutter rebuild scheduling | store listener → EDT, coalesced |
| Inlay component construction and disposal | EDT (Swing/editor components) |
| `submitPrompt` | plugin executor (P1 contract) |
| Processor/viewer listener callbacks | platform callback thread; UI work hops through `onEdt` unless already there |

### Components

| Component | Where | Responsibility |
|---|---|---|
| `ReviewInstructions.forFix` (new) | core (+ identical app change) | the one-line fix-prompt text |
| `FindingInlayCard` | glue | the Swing content of one finding's inlay: thread, comment box, actions |
| `ReviewDiffEditor` | glue | project-owned reusable `DiffVirtualFile` and processor; opens/reopens the single diff tab and swaps requests |
| `ReviewDiffInlays` | glue | processor/viewer listeners; decorates `EditorDiffViewer.getHighlightEditors()` after rediff and owns per-editor inlay lifecycles |
| `ReviewEditorMarkModel` | glue | pure scope-diff/findings → source-file/line mark model; excludes old-side-only and drifted anchors |
| `ReviewEditorMarks` | glue | project-startup scope/diff loader plus gutter lifecycle on open source editors; click → activate Review and select the finding |
| `ReviewTriageActions` | glue | the store writes behind Confirm/Dismiss/Undo; refuses a disposed store loudly |
| Ask-to-fix delivery | glue | resolve the scope's bound session, `submitPrompt`, mark SENT; REFUSED and no-session are distinct surfaced outcomes (the app's never-pretend rule) |

`build.gradle.kts` gains the
`intellij.platform.collaborationTools.shared` module dependency. P3 stays
Java-only; it does not add a Kotlin source set or use the flow-based renderer.

## Spike verdict (completed before implementation)

Live in `runIde`, IU-2026.3, on 2026-10-24 and 2026-10-25:

1. The operator accepted a dedicated Review tool window plus one reusable
   editor-area diff tab. The in-panel splitter and per-file tabs were rejected.
2. `DiffExtension` did not load for the panel-hosted viewers. The working seam
   is the reusable processor's viewer-change callback followed by
   `DiffViewerListener.onAfterRediff` and
   `EditorDiffViewer.getHighlightEditors()`.
3. A Java call to `insertComponentAfter` rendered the component inlay after a
   non-null renderer factory was supplied. No Kotlin adapter is needed.
4. The gutter mark rendered. Clicking it will open Review on the selected
   finding; the operator chose this over a popup.
5. The test viewer was unified, so implementation must repeat the live check in
   side-by-side mode. The API returns all highlight editors in either mode.

The spike package is throwaway and is removed before this design lands; only
these verified constraints carry into implementation.

## Acceptance criteria

Gate: `cd intellij-plugin && ./gradlew test buildPlugin` passes; the app
tree still compiles (`compileCoreJava`) with the shared builder change.

| # | Criterion | Test |
|---|---|---|
| AC1 | A PROPOSED finding renders one inlay under its line in the reusable editor-area diff, with severity, title, author/age, thread and the three actions. Unified mode renders in its one highlight editor; side-by-side mode renders on the applicable right side. | live IDE in both modes + unit test on card construction and placement from a fixture finding |
| AC2 | Confirm sets triage CONFIRMED, Dismiss sets DISMISSED, both persist to `annotations.json` after flush and re-render the mark. Undo returns to PROPOSED. | JUnit over `ReviewTriageActions` + store |
| AC3 | A comment entered on the card appends `Message(author="You")` to the thread, persists, and is returned by `review_answer` for that finding. | JUnit through the store + router path (P1 style) |
| AC4 | Ask-to-fix sends the same one-line prompt text the app sends (shared builder), through `submitPrompt`; on success the findings become SENT. A REFUSED hand-off and a missing bound session are distinct, surfaced outcomes that mark nothing. | JUnit on the builder (both trees byte-identical) + the delivery path with fakes |
| AC5 | After project warm-up, the source editor shows gutter icons for open new-side findings on scope files even if Review was never opened. Clicking activates the dedicated Review tool window, selects that file and finding, and opens or updates the reusable diff tab. Non-scope files, old-side-only anchors and drifted anchors get no guessed source line. | live IDE before opening Review + unit test on the mark model, editor filter and opener call |
| AC6 | A finding whose line key drifted renders at its file's first hunk with the drift hint, never at a wrong line. | JUnit on placement logic |
| AC7 | Inlays and gutter marks are disposed with the viewer/editor/tab; processor and viewer listeners have matching parent disposables; no global editor listener, listener leak, or inlay survives disposal. | JUnit dispose test + live check for no disposer warning |
| AC8 | All store writes run off the EDT; every UI rebuild is coalesced. | review + existing executor conventions |

## Open question

Does the inlay comment box need `@mention`-style author names beyond "You"
in P3? Assumed: no — use the app's existing author convention without adding
mention completion.
