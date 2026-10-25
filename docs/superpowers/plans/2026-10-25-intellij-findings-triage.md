# IntelliJ findings triage (P3) implementation plan

Decision mode: **settled**. The operator approved the design and said to
proceed. Tasks resolve reversible implementation details from the committed
spec and current code; they do not reopen design or wait for approval.

Design: `docs/superpowers/specs/2026-10-24-intellij-findings-triage-design.md`

## Approach

Keep all persisted review state in the existing `AnnotationStore` and all
blocking work on `DrydockPluginService.executor()`. Move the Review tree from a
Changes subtab into a dedicated tool window and open one project-owned reusable
`DiffVirtualFile` in the editor area. The file's `DiffRequestProcessor` is the
only route to its viewers: after each applied request, decorate the current
`EditorDiffViewer` after rediff. A separate project-startup controller loads the
scope, diff and findings without waiting for tool-window content, then maintains
source-editor gutter marks. Triage and prompt delivery sit behind testable
controllers; Swing cards only render state and invoke them.

Rejected routes remain rejected: no `DiffExtension`, request-document matching,
Swing component walk, global diff-editor listener, per-file tabs, in-panel diff,
or preview-tab behavior dependent on IDE settings. Source-editor marks never
guess positions for old-side-only or drifted anchors.

## Assumptions

- A new byte-identical `FindingsPrompt` class is the shared prompt seam; the
  already-divergent `ReviewInstructions` copies remain unchanged.
- Confirm mirrors the app's first-transition `postToPr=true` promotion.
- Gutter data loads during project warm-up, before Review is opened.
- Deleted-line findings render on LEFT in side-by-side and at the transferred
  old-side line in unified mode.
- Only `PromptOutcome.DELIVERED` marks a plugin-sent finding SENT; REFUSED,
  FAILED and a missing bound session remain distinct visible outcomes.
- CONFIRMED and DISMISSED unresolved findings retain their inlay card so Undo
  remains reachable.

## Tasks

### T1 Add the byte-identical shared findings-prompt builder

- files:
  - `app/src/main/java/app/drydock/review/FindingsPrompt.java` (new)
  - `intellij-plugin/src/main/java/app/drydock/review/FindingsPrompt.java` (new)
  - `app/src/test/java/app/drydock/review/FindingsPromptTest.java` (new)
  - `intellij-plugin/src/test/java/app/drydock/review/FindingsPromptTest.java` (new)
- depends: none
- change:
  - Add `forFix(String subject, List<ReviewAnnotation>)` for the exact existing
    fix heading and `render(String heading, List<ReviewAnnotation>)` for the
    numbered, whitespace-collapsed finding list.
  - Preserve empty-list behavior explicitly; do not invent delivery semantics
    in this pure builder.
  - Keep both source files and both tests byte-identical.
- tests:
  - exact prompt text, numbering, whitespace collapse, order and empty list;
  - byte comparison of the app/plugin copies in the full gate.
- done when: AC4's prompt text has one core implementation shared by both trees.
- memories: `intellij-findings-triage/2026-10-24-triage-ask-comment-contracts`

### T2 Switch the standalone app to the shared prompt builder

- files:
  - `app/src/main/java/app/drydock/ui/MainWorkspace.java`
  - `app/src/test/java/app/drydock/ui/MainWorkspaceFindingsPromptTest.java` (new)
- depends: T1
- change:
  - Replace the private string-building loop in
    `SessionReviewHost.handFindingsToSession` with `FindingsPrompt`.
  - Route both `askAgentToFix` and `sendFindingsToAuthor` through the builder,
    preserving their distinct headings and existing handoff rule.
  - Preserve current app behavior: any non-`REFUSED` app handoff marks SENT;
    P3's stricter `PromptOutcome.DELIVERED` rule applies only to the plugin.
- tests: pin both headings, exact rendered payload, empty findings and SENT
  mutation timing without constructing a JavaFX workspace.
- done when: the app emits the same prompt as before and no prompt loop remains
  in `MainWorkspace`.
- memories: `intellij-findings-triage/2026-10-24-triage-ask-comment-contracts`

### T3 Make finding placement and diff metadata explicit

- files:
  - `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewFindingPlacement.java` (new)
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewFindingPlacementTest.java` (new)
  - `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewDiffRequests.java`
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewDiffRequestsTest.java`
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewDiffRequestsPlatformTest.java`
- depends: none
- change:
  - Centralize `n<N>` → RIGHT/N-1 and `o<N>` → LEFT/N-1 parsing.
  - Given current row keys and the file fallback target, return exact-side
    placement or the first-hunk fallback carrying `ReviewTabModel.DRIFT_HINT`.
  - Expose a source-editor position only for a valid, present `n<N>` key.
  - Widen request creation with a file-path title and immutable request context
    needed by the reusable editor; remove stale DiffExtension claims from the
    request-key documentation.
  - Preserve custom fragments, force-read-only and scroll behavior.
- tests: valid new/old keys, malformed keys, deleted-file fallback, drift
  fallback, no source position for old/drifted keys, title and user data.
- done when: AC1/AC5/AC6 placement is pure and independently testable.
- memories: `intellij-findings-triage/2026-10-25-editor-area-diff-decoration-seam`

### T4 Implement triage, comments and plugin prompt delivery

- files:
  - `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewTriageActions.java` (new)
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewTriageActionsTest.java` (new)
- depends: T1
- change:
  - Provide async Confirm, Dismiss, Undo, comment and ask-to-fix operations over
    injected `AnnotationStore`, executor and prompt submitter.
  - Confirm mirrors `MainWorkspace.confirmedForPosting`: only the transition
    into CONFIRMED promotes `postToPr=true`; reconfirm does not undo a later
    explicit opt-out. Dismiss/Undo preserve all unrelated fields.
  - Comments append `Message("You", now, text)` and reject blank input.
  - Ask-to-fix requires the scope's bound session, uses
    `FindingsPrompt.forFix`, and marks SENT only for
    `PromptOutcome.DELIVERED`. Distinguish no-session, REFUSED and FAILED.
  - All store mutation and prompt submission runs on the injected executor.
- tests: every transition, post-to-PR promotion/opt-out, persisted comment,
  exact prompt, all delivery outcomes, no mutation on refusal/failure, and
  recorded worker thread.
- done when: AC2–AC4 and AC8's write-side behavior pass without Swing.
- memories: `intellij-findings-triage/2026-10-24-triage-ask-comment-contracts`

### T5 Build the Swing finding card

- files:
  - `intellij-plugin/src/platform/java/app/drydock/intellij/review/FindingInlayCard.java` (new)
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/FindingInlayCardTest.java` (new)
- depends: none
- change:
  - Render severity, title, author/relative age, drift hint, complete thread,
    comment input, and state-specific actions.
  - PROPOSED shows Confirm/Dismiss/Fix; CONFIRMED and DISMISSED retain the card
    and expose Undo. SENT remains visible as state, without pretending a reply.
  - Buttons and Enter submission invoke an injected callback only. Disable the
    active controls and show progress immediately; every success, refusal,
    failure and disposal path restores or removes progress.
  - Card construction and mutation assert the EDT. No store/process/file work.
- tests: component/state matrix, thread order, deterministic age via injected
  clock, Enter behavior, progress clearing and disposed-completion behavior.
- done when: AC1's full card and AC8's UI-side progress contract are covered.
- memories: none

### T6 Own one reusable editor-area diff and decorate its active viewer

- files:
  - `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewDiffEditor.java` (new)
  - `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewDiffInlays.java` (new)
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewDiffEditorTest.java` (new)
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewDiffInlaysTest.java` (new)
  - `intellij-plugin/build.gradle.kts`
- depends: T3, T4, T5
- change:
  - Add `intellij.platform.collaborationTools.shared` as a compile dependency;
    do not declare a diff product module in plugin.xml and do not add Kotlin.
  - Keep one `ReviewDiffEditor` instance per project. Its `DiffVirtualFile`
    seeds each newly-created processor with the current request, ignores a
    disposed processor, reopens after close, and swaps later requests in place.
  - Register a processor-owned viewer-change listener. `ReviewDiffInlays`
    attaches a viewer-owned after-rediff listener and decorates only the active
    `EditorDiffViewer.getHighlightEditors()`.
  - For side-by-side viewers, select LEFT for `o<N>` and RIGHT for `n<N>`.
    For `UnifiedDiffViewer`, convert the side line with
    `transferLineToOneside`; never use request-document identity.
  - Insert Java-only component inlays with a non-null renderer factory. Before
    rebuild, dispose prior inlays and explicitly remove prior
    `RangeHighlighter`s. Coalesce store-driven rebuild bursts.
  - Build cards for every unresolved finding, including CONFIRMED/DISMISSED, so
    Undo remains reachable. Apply the drift fallback and hint from T3.
- tests: first request seeding, swap/reopen, stale request suppression, unified
  and two-side editor choice/mapping through fakes, after-rediff timing,
  coalescing, idempotent rebuild and disposal ownership.
- done when: AC1, AC6 and AC7 are implemented without DiffExtension, global
  editor listeners, timers or component walks.
- memories: `intellij-findings-triage/2026-10-25-editor-area-diff-decoration-seam`,
  `intellij-findings-triage/2026-10-24-platform-inlay-gutter-api-263`

### T7 Register and anchor the dedicated Review tool window

- files:
  - `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewToolWindowFactory.java` (new)
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewToolWindowFactoryTest.java` (new)
  - `intellij-plugin/src/platform/resources/META-INF/plugin.xml`
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewTabPluginXmlTest.java`
- depends: none
- change:
  - Replace the `changesViewContent` registration with exactly one
    `Drydock Review` tool window; leave the existing `Drydock` task window
    unchanged and keep diff modules out of the descriptor.
  - In `ToolWindowFactory.init`, align Review with the first available Changes
    host (`Commit`, then `Version Control`) through `invokeLater` and listen for
    host registration, availability and anchor/layout changes. Ignore the
    Review window's own event to prevent a loop.
  - Use LEFT only as the first-layout descriptor default; runtime alignment
    corrects persisted layouts. Update availability when session coverage
    changes without blocking or touching disk.
  - Create one provider content and bind its disposal to the tool-window
    content.
- tests: descriptor uniqueness/no old tab/no diff module; target-anchor and
  self-event decisions; availability changes; provider disposed once.
- done when: the dedicated window is discoverable beside Changes and survives
  restart/layout changes without moving the separate task window.
- memories: `intellij-findings-triage/2026-10-25-editor-area-diff-decoration-seam`

### T8 Add finding-target selection to browser navigation

- files:
  - `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewTabOpener.java`
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewTabOpenerTest.java`
  - `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewTabBrowser.java`
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewTabBrowserTest.java`
- depends: T7
- change:
  - Resolve the host to the dedicated Review id; activation is sufficient for
    its single content and must not call `ChangesViewContentManager`.
  - Add `openFinding(project, file, findingId)` that records a pending target
    before activation. Keep the existing Changes-link scope mint, save and
    visible progress ordering.
  - Add browser selection by stable file path/finding id. If the snapshot is
    not loaded yet, retain the target and consume it after the next snapshot;
    malformed/stale targets leave selection unchanged.
- tests: existing open ordering, dedicated id, pending target before content,
  exact finding selection after rebuild, stale target, and ordinary open with
  no target.
- done when: AC5's gutter click can reliably focus the finding even when Review
  content has never been created.
- memories: none

### T9 Integrate the tool-window tree with the reusable diff and cards

- files:
  - `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewTabProvider.java`
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewTabProviderTest.java`
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewTabEndToEndTest.java`
  - `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewDiffPreview.java` (delete)
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewDiffPreviewTest.java` (delete)
- depends: T4, T6, T7, T8
- change:
  - Remove the in-panel splitter and obsolete request-panel preview. The tool
    window contains header, tree and status only.
  - Own one project `ReviewDiffEditor`; file/finding selection still builds git
    texts and fragments off the EDT, then shows only the latest selection's
    request on the EDT.
  - Supply the current scope, file row and all unresolved file findings to the
    inlay layer; store changes rebuild cards without losing CONFIRMED/DISMISSED
    Undo state.
  - Wire card actions to `ReviewTriageActions`; surface no-session, REFUSED and
    FAILED distinctly. Clear progress on every completion and disposal path.
  - Consume pending gutter-navigation targets after snapshots and select/open
    the requested finding.
  - Preserve mark/unmark, refresh, changed-since protection, and listener/service
    disposal.
- tests: late selection suppression, no embedded preview, target consumption,
  card action/store round trips, distinct delivery errors, refresh invariants,
  and end-to-end snapshot → selection → reusable request.
- done when: AC1–AC4, AC6 and AC8 operate through the dedicated Review surface.
- memories: `intellij-findings-triage/2026-10-25-editor-area-diff-decoration-seam`

### T10 Compute source-editor marks without guessing anchors

- files:
  - `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewEditorMarkModel.java` (new)
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewEditorMarkModelTest.java` (new)
- depends: T3
- change:
  - Build an immutable map from diff path and valid current row keys to grouped
    open findings by zero-based source line.
  - Include PROPOSED and CONFIRMED unresolved findings; exclude DISMISSED,
    RESOLVED/FIXED, old-side-only keys and drifted new-side keys.
  - Select severity/count/tooltip deterministically for multiple findings on
    one line and retain all finding ids for navigation.
- tests: scope/path filtering, all triage/status states, n/o/drifted keys,
  line bounds and deterministic grouped output.
- done when: AC5's source-line policy is pure and no wrong-line fallback exists.
- memories: `intellij-findings-triage/2026-10-24-triage-ask-comment-contracts`

### T11 Maintain gutter highlighters on open source editors

- files:
  - `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewEditorHighlighters.java` (new)
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewEditorHighlightersTest.java` (new)
- depends: T8, T10
- change:
  - On the EDT, apply a `ReviewEditorMarkModel` snapshot to all current project
    editors and to editors created later. Resolve source files from editor
    documents, normalize checkout-relative paths, and ignore other projects.
  - Add grouped severity/count gutter renderers; click calls
    `ReviewTabOpener.openFinding` with a deterministic finding target.
  - Remove every old `RangeHighlighter` before replacement and on disposal.
    Register the source-editor `EditorFactoryListener` with the project-owned
    disposable; no root-disposable registration.
- tests: project/path filter, existing/new editor application, grouped click
  target, idempotent replacement, explicit highlighter removal and listener
  disposal using an injected editor host.
- done when: AC5/AC7's editor-side lifecycle is deterministic and leak-free.
- memories: `intellij-findings-triage/2026-10-25-editor-area-diff-decoration-seam`

### T12 Load gutter data at project startup, independently of Review content

- files:
  - `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewEditorMarks.java` (new)
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewEditorMarksTest.java` (new)
- depends: T10, T11
- change:
  - Add a project-owned controller whose cheap constructor performs no I/O.
    `start()` waits for plugin initialization on the plugin executor, resolves
    the checkout scope, runs the authoritative `DiffService` request, reads the
    scope's findings, computes T10's model and applies it through T11 on the
    EDT.
  - Subscribe to store/scope changes, session-catalog changes, VFS changes and
    covering-session turn completion. Coalesce bursts, keep at most one reload
    in flight plus one follow-up, and never equate failure with an empty model.
  - Before a covering session/scope exists, retain no marks and retry on the
    catalog signal. Log a bounded diagnosis for genuine failures.
  - `close()` unregisters every listener, cancels pending work, clears marks on
    the EDT, and closes controller-owned git/worktree services independently.
- tests: initial load without opening Review, no-session→catalog retry,
  store-only rebuild, diff reload signals, coalescing/in-flight follow-up,
  failures preserve the last valid model, and all close races.
- done when: AC5's "before Review opens" requirement and AC7/AC8 lifecycle and
  threading requirements are covered.
- memories: `intellij-findings-triage/2026-10-25-editor-area-diff-decoration-seam`

### T13 Start and dispose immediate gutter marks with project warm-up

- files:
  - `intellij-plugin/src/platform/java/app/drydock/intellij/startup/DrydockStartupActivity.java`
  - `intellij-plugin/src/test/java/app/drydock/intellij/startup/DrydockStartupActivityTest.java` (new)
- depends: T12
- change:
  - Construct exactly one `ReviewEditorMarks` per project with the other
    startup-owned services.
  - Chain its initial `start()` load into the existing visible background
    warm-up so no hidden blocking work runs on the EDT and all startup progress
    paths terminate.
  - Register project disposal so marks/listeners/services close even if Review
    was never opened. Expose only the narrow accessor needed by tests.
- tests: cheap construction, off-EDT start, one instance, warm-up completion on
  success/no-session/failure, and exactly-once project disposal.
- done when: immediate marks have an explicit startup owner and shutdown path.
- memories: none

## Waves

Gradle uses shared build directories in this worktree. Tasks in a parallel wave
may edit disjoint files, but they do **not** run Gradle concurrently; the
coordinator runs the listed narrow tests after each complete wave.

- W1: T1, T3, T5, T7
- W2: T2, T4, T8, T10
- W3: T6, T11
- W4: T9, T12
- W5: T13

## Verification

### After each wave

Run from `intellij-plugin/` unless the test belongs to the app tree:

1. `./gradlew compileJava compilePlatformJava`
2. Narrow tests named by the completed tasks, in one Gradle invocation.
3. `git diff --check`
4. Confirm task ownership with `git status --short` and per-task path diffs.

For T1/T2 also run from repository root:

- `./gradlew :app:test --tests "app.drydock.review.FindingsPromptTest" --tests "app.drydock.ui.MainWorkspaceFindingsPromptTest"`
- `cmp app/src/main/java/app/drydock/review/FindingsPrompt.java intellij-plugin/src/main/java/app/drydock/review/FindingsPrompt.java`
- `cmp app/src/test/java/app/drydock/review/FindingsPromptTest.java intellij-plugin/src/test/java/app/drydock/review/FindingsPromptTest.java`

### Full automated gate

From `intellij-plugin/`:

- `./gradlew test buildPlugin`
- `./gradlew runPluginVerifier`

From repository root:

- `./gradlew :app:test :app:compileJava`

Record exact test counts and archive full Gradle logs under randomized names in
the repository's existing build-log location. Do not report success from
reasoning or partial tasks.

### Live IU-2026.3 gate

Run `./gradlew runIde` and verify all of the following:

1. Before opening Review, wait for Drydock project warm-up, open a changed source
   file with a PROPOSED finding, and see its gutter mark. Non-scope, deleted-only
   and drifted findings get no guessed source mark.
2. `Drydock Review` is on the same stripe as the available Changes host after a
   clean start and another restart; moving the host realigns it. The separate
   `Drydock` task window stays right-docked.
3. Clicking the gutter mark opens Review, selects the exact finding and opens
   the reusable editor diff immediately.
4. First and subsequent file selections reuse one diff tab. Closing it and
   selecting again reopens it. No first-load blank/stall occurs.
5. In unified mode, `n<N>` and `o<N>` findings render at transferred lines. In
   side-by-side mode, new lines render on RIGHT and deleted lines on LEFT.
   Drifted findings render at the first hunk with the drift hint.
6. Confirm, Dismiss, Undo, comment and Fix update the card without stacking
   inlays. Confirm promotes `postToPr` only on first transition. A delivered Fix
   marks SENT; no-session/refused/failed outcomes do not.
7. Close/reopen the diff and project, then inspect `idea.log`: no disposer leak,
   no surviving editor listener, no repeated inlays/highlighters, and no EDT
   freeze report.
