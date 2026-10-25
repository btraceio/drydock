# intellij-findings-triage — wrap-up

P3 of the IntelliJ integrated-review design: finding inlays in the review
diff, source-editor gutter marks, Confirm/Dismiss/ask-to-fix triage, and human
comments. Scope and live-spike decisions were settled with the operator on
2026-10-24 and 2026-10-25.

## Settled design

- The Review tree lives in a dedicated `Drydock Review` tool window aligned
  with Changes. The changes subtab was hard to discover and was rejected.
- The diff lives in the editor area, not in a splitter inside the tool window.
  One project-owned reusable `DiffVirtualFile` swaps requests in place. Per-file
  tabs, the in-panel splitter, and preview-tab behavior dependent on a user
  setting were rejected.
- Clicking a source-editor gutter mark activates Review, selects the file and
  finding, and updates or reopens the reusable diff. The operator selected this
  over a popup at the source line.
- Triage remains Confirm, Dismiss and ask-the-agent-to-fix. Human comments are
  appended as `ReviewAnnotation.Message` entries. Ask-to-fix uses one shared
  core prompt builder and marks findings SENT only after successful handoff.

## Live spike verdict (IU-2026.3)

The complete evidence is in
`nodes/2026-10-25-editor-area-diff-decoration-seam.md` and
`nodes/2026-10-24-platform-inlay-gutter-api-263.md`.

- `DiffExtension` never loaded for the plugin's panel-hosted viewers in two
  clean runs. It is not a P3 seam.
- The working seam is owned by the reusable `DiffRequestProcessor`: listen for
  viewer changes, get the active `EditorDiffViewer`, use
  `getHighlightEditors()`, and decorate after
  `DiffViewerListener.onAfterRediff`.
- The sandbox selected `UnifiedDiffViewer`, which owns a different document
  from the request contents. Request-document matching therefore cannot find
  it. `EditorImpl` implements `EditorEx` but is not an AWT `Component`, so a
  Swing component walk cannot find it either.
- A newly-created processor must be seeded with the file's current request.
  Otherwise its first `selectNotify -> updateRequest` applies nothing and the
  tab appears stalled until the second selection.
- Plain Java can call `EditorComponentInlaysUtilKt.insertComponentAfter`; no
  Kotlin/coroutine adapter is needed. On 263 its renderer factory is non-null at
  runtime. Passing null produced the observed `0/N inlay(s), gutter=true` state;
  supplying a `Function1` rendered both the component inlay and gutter icon.
  The operator confirmed the final visible result.
- A global `EditorFactoryListener`, delayed timers and component walks were
  removed. The listener also caused a disposer leak warning. Final listeners
  are parented to the processor/viewer; inlays are disposed and
  `RangeHighlighter`s are explicitly removed.
- The descriptor's tool-window anchor is only an initial-layout default;
  persisted layout wins later. Alignment belongs in `ToolWindowFactory.init`
  and must react to Changes-host layout events. Content creation is too late.

## Other confirmed contracts

- `Triage.PROPOSED/CONFIRMED/DISMISSED` wire names are stable. Agent findings
  land PROPOSED.
- The app's ask-to-fix prompt is currently app-private; P3 moves it to shared
  core rather than copying the text into the plugin.
- The plugin's pi bridge must expose session tools through direct
  `registerTool` proxies because pi-mcp-adapter strips direct tools from
  extension-registered MCP servers (`533c4416`). The bridge retries its initial
  handshake through the spawn/bind race (`e9874c7b`). Both fixes were verified
  live.

## Current state and next step

Throwaway spike code has been removed. The updated design is
`docs/superpowers/specs/2026-10-24-intellij-findings-triage-design.md`.
The only remaining design assumption is comment author display: use the app's
existing convention and do not add @mention completion in P3.

Next: review and land the design spec, then implement P3. Side-by-side mode must
be included in implementation-phase live verification; the completed spike
verified unified mode.
