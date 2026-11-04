# IntelliJ review workbench — design

Date: 2026-11-03. Status: proposed. Implements the approved "B · Findings inbox"
canvas (Agentic Code Review, design canvas) in `intellij-plugin/`.

Parent specs: `2026-10-22-intellij-integrated-review-design.md` (P1–P6),
`2026-10-22-intellij-review-tab-design.md` (P2), `2026-10-24-intellij-findings-triage-design.md` (P3),
`2026-10-03-guided-review-tour-design.md`, `2026-08-22-review-navigation-design.md`.

## 1. Goal

One review surface, tied to an agent session, for either a PR or local changes:

- left: **Sections / Files** navigator (agent sections in dependency order, file tree);
- centre: the diff, as **real IDE editors** — Go to Declaration, Find Usages, Quick
  Documentation, Call Hierarchy, Find in Files all work from the code under review;
- right: **findings inbox** (To triage / Outbox / Done; Dismiss, Challenge, Request fix, Post to PR);
- bottom: **outbox** (PR pending review lane, session lane with one "Fixes land on" choice);
- per-hunk **agent take** (what it does, alignment with the stated intent, red flags linked to findings);
- resizable splitters between every pane.

Non-goals: sharing review state with the JavaFX app; the IDE GitHub plugin's submit
(keep `SubmitPlan` + `gh`); replacing tree-sitter sections with PSI (P6 stays separate).

## 2. Hard requirement: IDE navigation in the diff

The reviewer must be able to confirm or refute a reading with the IDE's own tools.
Today this is impossible: `ReviewDiffRequests` (l.313–314) builds both sides with
`EditorFactory.createDocument(text)` — plain documents with no file type, no PSI,
no project file behind them.

Rules for the new diff contents (`ReviewDiffContents`, glue):

1. **Right side = the real project file** whenever it is the reviewed content:
   `DiffContentFactory.getInstance().create(project, virtualFile)` on the worktree file.
   Task worktrees open as their own project (`TaskWindows.openOrImport`), so the file is
   inside the project's content roots and PSI resolution is the project's own.
   Kept read-only with `DiffUserDataKeys.FORCE_READ_ONLY`; navigation is unaffected.
2. **Guard: content must match — when the request is built and on every rediff.**
   - *At build time.* `ReviewContents.matchesRows` already checks a text against git's row
     numbers. If the worktree file differs from the reviewed right side (dirty worktree, PR
     scope whose HEAD ≠ working tree), fall back to a typed text content
     `DiffContentFactory.create(project, text, filePath)` holding the reviewed text, and
     show a one-line notice in the diff header ("worktree differs from reviewed revision —
     this side is a snapshot; references resolve against the current code; Refresh or
     Review working tree"). A typed content is not "no resolution": S1 showed it gets PSI
     in the project's context, so Ctrl+B and Find Usages work on it, but against the
     *current* project, and its lines are not the file's lines.
   - *While the diff is open.* A file-backed right side is the live project document. An
     edit to the file (by the user in another editor, or by the agent) makes the viewer
     rediff, and the viewer calls `CUSTOM_DIFF_COMPUTER` again with the new text. S1
     showed that returning the precomputed fragments then marks the wrong lines. So the
     computer compares its `text2` argument with the reviewed right text and returns the
     git fragments only when they are equal. On a mismatch it does not return them. The
     request is then marked stale, the notice above is shown, and decorations keyed by
     line keys (finding ranges, inlays, take panels) are hidden until Refresh. On a
     mismatch the computer returns the platform's own comparison of the two texts
     (`ComparisonManager.compareLines`), so the highlighting matches the text on screen.
3. **Left side = typed revision content** `create(project, baseText, filePath)` so it is
   highlighted with the right language. References on the left resolve (S1), but against
   the *current* project, not the base revision. A symbol that the change removed or
   renamed therefore resolves to nothing, or to its current version (inferred from how the
   content is created, not tested). F4 / Jump to Source from either side opens the real
   file at the corresponding line. Live check: from the left it maps through the diff, so
   a base line below an insertion lands one line further down in the file.
4. Fragments stay git-authoritative: `CUSTOM_DIFF_COMPUTER` with `ReviewFragments`, behind
   the rediff check of rule 2.

Extra navigation affordances (glue, each small):

- **Review search scope.** `searchScopesProvider` extension contributing
  "Drydock: files in this review" (a `GlobalSearchScope` over the scope's diff paths) and
  "Drydock: current section". Find Usages / Find in Files can then be narrowed to the change.
- **Ask the agent about a selection.** Diff-editor context action "Ask reviewer about
  selection…" → `ReviewInstructions.forPeekAsk` + `PendingQuestions` (exists, unused in the
  plugin) → answer lands as a thread on a new human finding at that range.
- **Round trip.** Navigating away lands in ordinary source editors, which already get finding
  gutter marks (`ReviewEditorHighlighters`); extend those marks with "changed in this review"
  ranges and a "Back to review" gutter action (`ReviewTabOpener.openFinding` pattern).

## 3. Surface and layout

A **file editor tab** ("Drydock Review — PR #930"), not the tool window: the design needs
width for three panes. Same pattern as `ReviewDiffEditor` today — one reusable virtual file
per project + a `FileEditorProvider` — but the editor hosts the workbench component.

```
ReviewWorkbenchEditor (FileEditor)
└── header: scope chip [PR #930 | Local changes] · session chip · base→head
└── OnePixelSplitter vertical  key "drydock.review.outbox"
    ├── OnePixelSplitter horizontal  key "drydock.review.nav"
    │   ├── NavigatorPanel (Sections | Files)
    │   └── OnePixelSplitter horizontal  key "drydock.review.inbox"
    │       ├── CenterPanel (diff host)
    │       └── InboxPanel
    └── OutboxPanel (PR lane | Session lane)
```

`OnePixelSplitter(proportionKey, default)` persists proportions per user — this is the
"movable dividers" requirement, with no custom code. The existing `Drydock Review` tool
window stays as the launcher and compact summary ("k/n reviewed", Open workbench); its tree
is superseded by the navigator.

Every pane is a thin Swing view over a **pure, headless-tested model** in
`app.drydock.intellij.review.workbench.model` (conventions of `ReviewTabModel`,
`ReviewFindingPlacement`, `ReviewEditorMarkModel`). One `WorkbenchState` snapshot is rebuilt
off-EDT on store events (`AnnotationStore`/`TourStore` listeners, debounced like
`ReviewTabRefresher`) and pushed to panes on the EDT.

## 4. Centre: the diff host

**File view.** One embedded `DiffRequestPanel` (`DiffManager.createRequestPanel`) per file,
with the contents from §2.
- *Changes only / Full file* maps to the platform's collapse-unchanged setting on the viewer
  (`TextDiffSettings` expand-by-default / context range) — no custom folding.
- Findings: range highlighters over `startKey..endKey` (not just the start line), with
  error-stripe marks in severity colour, so the editor's scrollbar ruler shows change and
  finding positions in full-file mode. Existing inlay cards (`ReviewDiffInlays`,
  `FindingInlayCard`) stay for the selected finding.
- The selected finding is scrolled to (`SCROLL_TO_LINE`) and outlined; others show a
  lighter range bar.

**Section view.** A vertical list of hunk cards across files. Each card has a header
(file · lines · −/+ counts · also in §N · Mark reviewed · Full file), the agent take,
and a diff of that file scrolled to the hunk with unchanged code collapsed. A hunk reviewed
in another section is a one-line "reviewed in §N · title" row with Show again.

Decision (S2): each card is its own `DiffManager.createRequestPanel` panel with the §2
contents, stacked in a `JBScrollPane`. The platform's `CombinedDiff*` viewer is not
usable: it ships in the internal-visibility `diff.impl` module and is
`@ApiStatus.Experimental` / `@ApiStatus.Internal` on 263.
- All cards share one diff settings place (`DiffUserDataKeys.PLACE` context hint, put
  before `setRequest`). Its `TextDiffSettings` have `expandByDefault = false` and a small
  context range, so unchanged code is collapsed by the viewer's own folding.
  `SCROLL_TO_LINE` puts the caret on the hunk.
- A card costs two editors and about 1 MB of heap, and building one takes about
  25–30 ms on the EDT. Cards are therefore created when they enter a viewport margin,
  and their `Disposable` is disposed when they leave it. A placeholder of the same
  height holds the scroll position. All remaining cards are disposed with the panel.
- Measured live with 15 cards: a scroll frame (scroll + lazy create/dispose + paint) takes
  a median 3.5 ms, p90 20.7 ms and max 32.6 ms, and the slow frames are card creations. At
  most 6 cards are live at once. Disposed cards are released: the count of live panels
  stays flat over 120 create/dispose cycles, and the heap growth is softly-held caches.
- Each card shows the full diff toolbar (viewer switch, settings) and no file name, so the
  card header from this section has to replace or hide that chrome.

**Selection → comment.** Native text selection in the diff editors. A gutter "+" on
hover and the context action "Comment on selection" convert the selection to line keys
(inverse of `ReviewFindingPlacement`; port the app's `DiffLineSelection` same-hunk rule into
core so PR comments stay GitHub-legal) and open the composer in the inbox.

**Unified vs side-by-side.** The canvas shows unified. Decision (S1): **side-by-side is
the default**, because it is the only mode with IDE navigation. Its right editor is the
project file's own document with PSI, so Ctrl+B, Alt+F7, Ctrl+Q, Ctrl+Alt+H and F4 all
work there under `FORCE_READ_ONLY`. Unified stays available through the viewer's own
switch, but it offers **F4 only**. `UnifiedDiffViewer` shows its own combined document,
which has no PSI, so the other four actions are disabled there. The viewer mode is the
place's `DiffSettings` tool order, which the viewer's switch also writes.
`DiffUserDataKeysEx.FORCE_DIFF_TOOL` is not used: the processor reads it from the
`DiffContext` at construction, so neither request user data nor a later context hint
reaches it.

## 5. Left: navigator

**Files.** Reuse `ReviewTabModel.Snapshot` (per-file open findings, hunk ids and digests).
Add the per-file "reviewed hunks / total" count from verdicts per digest, and the "With
findings" filter. Rendered with the existing `ReviewTabBrowser` tree machinery.

**Sections.** Source of truth:
1. the agent's **tour** (`ReviewTour.steps`) when present — title = `TourStep.title`,
   explanation = `narrative`, hunks = anchors resolved through `AnchorIndex` to digests;
2. otherwise the **computed** `Sections.of(diff, ChangeGraph.of(diff))` (titles only), marked
   "computed".

Model changes (plugin core only — see §10):
- `TourStep.dependsOn: List<String>` (step ids) — codec, `TourValidator` (ids exist, acyclic),
  `review_tour` schema, `ReviewInstructions.forScope` asks for dependency order + deps.
  Dependency order = step order; `dependsOn` drives the "builds on §2 / needed by §4" chips.
- `ReviewVerdict.settledInStep: Optional<String>` (lenient decode, absent for file-level
  marks). A hunk card collapses as "reviewed in §N" when its digest has a non-stale verdict
  settled in a different step. Per-section progress = settled digests / section digests;
  "Mark section reviewed" puts one verdict per unsettled digest.
- **Intent** shown at the top of the list. W3: the agent states it in an optional `intent`
  on `review_tour`, stored with the tour. The other two sources are not available in the
  plugin: `GhCliService.PrInfo` carries no PR body, and `ManagedAgentSession` does not keep
  the session's initial prompt. The PR description becomes a second source with the PR lane
  (W5).

## 6. Agent take per hunk

New core record, stored on the `ReviewTour` (`ReviewTour.takes`, one per digest), so it has
the tour's writer and lifecycle and `TourMigration` drops a take whose hunk left the diff:

```
HunkTake(hunkDigest, String does, Alignment alignment, String alignmentNote,
         List<RedFlag(String text, Optional<String> findingId)> redFlags, Instant at)
Alignment = ALIGNED | PARTIAL | DIVERGES | CONTEXT
```

The scope is the tour's own. No author is recorded: the router knows the calling session's
id, not an agent name.

- MCP: extend `review_tour` with an optional `takes[]` (keyed by hunk id, resolved to digest on
  write) — one call for the whole structure, as the agent already writes tours that way.
- Prompt: `ReviewInstructions.forScope` gives the agent the intent text and asks for one take
  per hunk, red flags referencing finding ids it posted with `review_finding`.
- UI: a collapsible panel above each hunk card / at each hunk start in file view; red flags
  with a finding id are buttons that select the finding. A take whose digest no longer
  exists is dropped, never re-anchored (same rule as findings).

## 7. Right: findings inbox

A pure `InboxModel.snapshot(findings, scope, filter)` → tabs and cards. Mapping onto the
existing model — no new statuses:

| Tab | Rule |
|---|---|
| To triage | `triage = PROPOSED`, status `OPEN` (badge "Challenged" when the last thread message is the human's and unanswered) |
| Outbox | `triage = CONFIRMED`, status `OPEN`, not yet posted |
| Done | `DISMISSED`, or status `SENT`/`ADDRESSED`/`RESOLVED`, or `github` present |

| Action | Implementation |
|---|---|
| Dismiss | `ReviewTriageActions.dismiss` |
| Challenge | `ReviewTriageActions.comment` + submit `ReviewInstructions.forFindingQuestion` to the session; reply arrives via `review_answer` into the thread |
| Request fix | `confirm` with `postToPr = false` |
| Post to PR (PR mode only) | `confirm` with `postToPr = true` |
| Undo | `ReviewTriageActions.undo` |

Scopes All / This section (or file) / Whole PR. Cards reuse `FindingInlayCard`'s chat-item
building (`CodeReviewChatItemUIUtil`) inside a `JBScrollPane` stack, plus the excerpt of the
anchored rows and "Show in diff".

**Whole-PR findings.** `ReviewAnnotation` requires a file today. Add an anchorless form
(`file` optional; `startKey`/`endKey` empty) — codec, `review_finding` schema (`anchor`
optional), `SubmitPlan` routes them to body notes. Auto-advance to the next untriaged card
after a decision.

## 8. Bottom: outbox

**Session lane.** Lists Outbox findings with `postToPr = false` and human instructions.
"Send to session" = `ReviewTriageActions.sendDecisions`. New global choice
**Fixes land on: Same branch | Stacked sub-PR** (PR mode only), persisted per scope, passed
to `FindingsPrompt.forDecisions(subject, toFix, dismissed, FixTarget)`. For a sub-PR the
prompt tells the agent to branch from the PR head, commit the fixes there and open a PR
whose base is the PR branch (`gh pr create --base`). The plugin creates no branches itself;
a later step can mint `ReviewScope.Kind.STACK` for the sub-PR.

**PR lane.** Lists Outbox findings with `postToPr = true` and human PR comments. "Submit
review" opens a Swing port of the app's `ReviewSubmitSheet`: `SubmitPlan.of(...)` preview
(inline comments, body notes, refusals), event (`SubmitPlan.preselect`), then
`GitHubReviewService.submit` via `gh`. Hidden in local mode.

**Mode.** The review is in PR mode when its scope carries a pull request (`ReviewScope.pr()`);
the header's chip shows which mode it is and does not switch. A switch to the separate
`Kind.PR` scope (`SessionReviewScopes.Scopes.pullRequest`) would show a scope no plugin agent
writes to: `SessionReviewScopeResolver` and `ReviewTabScopes` both keep only the local scope, and
the local scope of a `pr-<n>` checkout already carries the PR and diffs from its declared base
(W5b decision, 2026-11-04).

## 9. Spikes (before W1; live on 263, public API only)

Findings: `notes/review-workbench-spikes.md`. The spikes ran twice on branch
`spike/review-workbench`. First as headless `BasePlatformTestCase` harnesses, then live in
a sandbox IDE (`runIdeForUiTests` + Robot server plugin) over a git repository on disk,
with keymap-dispatched key events and screenshots. The "Live" column holds what only the
live run could show.

| # | Question | Pass | Verdict | Live (robot-driven `runIde`) |
|---|---|---|---|---|
| S1 | With right content = project `VirtualFile`: do Ctrl+B / Alt+F7 / Ctrl+Q work in the side-by-side right editor, and in the unified viewer? Does `FORCE_READ_ONLY` keep them? | references resolve in side-by-side; unified result recorded | **PASS.** Side-by-side right: target resolves, the five actions are enabled, Ctrl+B lands in the declaring file, F4 lands on the line; `FORCE_READ_ONLY` keeps all of it. `CUSTOM_DIFF_COMPUTER` still decides the hunks. Unified: no PSI, F4 only. Contradicts §2.3: the left typed content also resolves, against the current project. New: an edit to the live file makes the viewer rediff with stale git fragments (§2.2 must also guard inside the computer). | ⌘B, ⌥F7, F1, ⌃⌥H and ⌘↓ from the right editor all work through the real keymap: ⌘B lands on the declaration, Find shows the usages, the doc popup opens, the callers hierarchy opens, and F4 lands on the line. ⌘B also works from the left editor, and F4 from the left maps through the diff to the right line. |
| S2 | Section view cards: (a) `CombinedDiff*` multi-file viewer — public on 263? (b) stacked `DiffRequestPanel`s, one per hunk, with collapsed unchanged + scroll-to-hunk; memory and scroll cost for 15 cards | pick (a) only if public and stable, else (b) | **(a) FAIL**: `diff.impl` only; `@Experimental` entry points, `@Internal` model and viewer. **(b) chosen**: collapse and scroll-to-hunk work (11 of 402 lines visible, caret on the hunk); 15 cards = 30 editors, about 15 MB, about 0.4 s of EDT; disposing releases the editors. | 15 cards: frame median 3.5 ms, p90 20.7 ms, max 32.6 ms; at most 6 live; no card retention over 120 create/dispose cycles. Each card shows the full diff toolbar. |
| S3 | `searchScopesProvider` scope from a path set appears in Find Usages / Find in Files | scope selectable and correct | **PASS.** Listed by `ScopeChooserCombo` (used by `AbstractFindUsagesDialog` and `FindPopupScopeUIImpl`); `ReferencesSearch` and Find in Files return only files in the set. Not listed by `PredefinedSearchScopeProvider`. | Not run live. W1's exit check covers the real drop-down. |
| S4 | `DiffRequestPanel` inside a custom `FileEditor` + `OnePixelSplitter`: disposal, focus, `DiffDataKeys` for actions | no leaks under `Disposer` debug | **PASS.** The panel, parented to the `FileEditor`, is disposed with it; 0 editors leaked across two cycles. The preferred focus is the right editor. Diff data keys reach components inside the splitters. Splitter proportions are saved on change but loaded only on first show (`addNotify`). | A real tab: closing it disposes the editor and releases both diff editors. Focus starts on the right editor but does not leave it by traversal, so pane focus needs explicit actions. The proportion survives a restart (written to `other.xml`, read on first show). |

## 10. Phases

| Phase | Content | Depends on |
|---|---|---|
| W1 | Workbench editor tab, splitters, header + mode chip, Files navigator, file diff with **real-file contents and navigation**, range highlighting + stripe marks, review search scope | S1, S3, S4 |
| W2 | Inbox (model, tabs, actions incl. Challenge), selection → comment, ask-about-selection | W1 |
| W3 | Sections: tour + computed fallback, `dependsOn`, `settledInStep`, section view, reviewed-in-§N collapse, intent | W1, S2 |
| W4 | Agent takes: model, `review_tour.takes`, prompt, UI on the section view's hunk cards | W3 |
| W4b | Agent take at each hunk start in the file view (§6): a second decoration kind in `ReviewDiffInlays`, beside the finding cards | W4 |
| W5 | Outbox, session lane: the pending decisions and Send to session in the workbench's bottom pane (local mode) | W2 |
| W5b | PR mode: the mode from the scope's PR (§8), Post to PR in the inbox, the session lane leaving out `postToPr` findings, Fixes land on (`FixTarget`), the intent from the PR description | W5 |
| W5e | The PR lane and its submit sheet: `SubmitPlan` preview, event, `GitHubReviewService.submit` via `gh` | W5b |
| W5c | Whole-PR findings: an anchorless `ReviewAnnotation`, `review_finding`'s optional anchor, `SubmitPlan` body notes, the inbox's Whole PR scope | W5b |
| W5d | The PR menu opens the workbench (bug below): the PR is checked out into its worktree as today, no agent is launched, and the workbench offers the review to run: section-based (a new prompt and a tour whose steps need no checks), the full educational tour (today's `forScope`), or the harness's sphinx review when it reports the plugin; a fully marked section moves on to the next one, and a card shows only its own hunk (both below) | W5b |
| W6 | Source-editor round trip (changed-range marks, back to review); tool window becomes launcher | W1 |
| W7 | Polish and fixes deferred from W1–W4 (see below) | W4 |

**Deferred from earlier phases.** Each item was left out of the phase that found it; the
reason and the phase that takes it are recorded here, so nothing lives only in a PR.

| Item | Found in | Why it was left out | Goes to |
|---|---|---|---|
| The agent take at each hunk start in the file view (§6) | W4 | Needs a second decoration kind in `ReviewDiffInlays`; the section cards were the surface W3 built | Done in W4b |
| Section cards show no finding marks or inlay cards, and no gutter "+" for comments (§4) | W3 | The cards are separate diff panels; `ReviewDiffInlays` and `ReviewGutterComments` are wired to the file view's processor only | Done in W4b (cards are `DiffRequestProcessor`s) |
| The intent from the PR description (§5) | W3 | `GhCliService.PrInfo` has no body and the workbench has no PR mode | W5b, with PR mode |
| An end-to-end run with a real agent session posting `review_tour` with `dependsOn`, intent and takes | W3, W4 | The sandbox has no agent session; the router tests cover the tool | Done in W5: a `WorktreeReview` session's agent posted all three, and Send to Session reached it |
| The MCP review diff includes untracked IDE files (`.idea/workspace.xml`) that the workbench hides, so an agent spends steps, takes and findings on files the reviewer is not shown | W5 | Found in the end-to-end run; which side should change (the MCP diff, or the workbench's IDE-noise rule) is a product decision | W7 |
| No automated test that the provider adopts the registry's copy of its scope when a session binds it later | W5 | `ReviewTabProvider` has no test harness for registry changes; verified live only | W7 |
| A branch with an open PR under another name than `pr-<n>` can split into two reviews: the agent's resolver looks the PR up (`ReviewBackend.pullRequestCarriedBy`), the tab's `ReviewTabScopes` does not, and the PR ref is part of the scope's identity (pre-existing) | W5b | Found while reading the scope paths; W5b's PR mode relies on `pr-<n>` checkouts, where both derive the same ref from the branch name | W7 |
| A stacked sub-PR hand-off delivered to a real agent, and the agent opening the sub-PR | W5b | The live run had no bound session on the PR checkout; `FindingsPromptTest` covers the prompt's text only | W7: W5e's live run had no session bound to the PR checkout either; bind one and send with Stacked sub-PR |
| Overriding a refused finding into the review body from the submit dialog (the app's sheet allows it) | W5e | The plugin's first submit dialog previews refusals read-only; the override needs the reviewed diff's line text per refused finding | W7 |
| A real post to GitHub from the submit dialog (Posted, the "Review posted" balloon, the findings turning Done) | W5e | A post publishes a review on a real PR, which needs the user's go-ahead and a PR to post on; the post path is covered by `ReviewSubmitDialogPlatformTest` and `PrSubmissionTest` with a fake transport | W7: W5e shipped without a real post (the user's decision, 2026-11-04); post on a PR the user names |
| The app's `GitHubReviewService` runs `gh view` / `gh diff` without `pr` and with `--end-of-options`, so its fresh-head check never answers (the plugin's copy is fixed in W5e) | W5e | Outside the plugin: `app/` is not changed in this work | Not planned here (app) |
| **UI request:** a session that covers a PR's branch shows no link to the PR, so the reviewer cannot find which session belongs to which PR | Reported 2026-11-04 | The task tree's session rows (`TaskTreeRenderer.paintTask`) show name, agent and age only, and PR rows show no sessions; it is the task tree, not the workbench | W7: a grey small "PR #N" tag on the session row, as "RO" is drawn (the session's `PrLink` number when set, else its `pr-<n>` branch via `PrCheckoutService.pullRequestNumberOf`), and on the PR row a "· N sessions" link that selects them; from data the tree already holds, no extra gh call |
| A real agent posting a finding without an anchor through `review_finding` | W5c | The live run seeded the whole-change finding as the tool stores it; `McpToolRouterReviewTest` covers the tool | W7, with the real-agent runs above |
| **Bug:** a review asked for on a PR opens its session, but the workbench offers Local changes only: the Pull request chip is greyed out, so every comment goes to the session and none can be posted to the PR | Reported 2026-11-04 | The mode chip was hard-wired to Local until PR mode, and there is no PR lane yet | W5b (the mode follows the `pr-<n>` checkout's scope; Post to PR in the inbox) and W5e (submitting the PR-bound findings to GitHub) |
| **Bug:** a section card shows the whole file's changes, not the section's: unchanged code is folded, but every other hunk of the file is drawn as a change, so a file in three sections shows all its changes three times and the reviewer has to find the one the section talks about | Reported 2026-11-04 | W3 built each card as a whole-file diff scrolled to its hunk (spike S2), with collapsing that only folds unchanged code | W5d: each card reports only its own hunk's fragments to its viewer (`CUSTOM_DIFF_COMPUTER`), so every other change folds like unchanged code while the right side stays the file-backed document with navigation; check the folded regions' line pairing after an omitted hunk |
| **Feature request:** in the section-based review, when every change of the shown section is marked reviewed, move on to the next section automatically | Reported 2026-11-04 | Requested during W5b; it changes the section view's flow, which W5d reworks for the section-based review | W5d: after a mark completes and the section's progress reaches its total, select the next section in order that still has unreviewed hunks; with none left, stay and say every section is reviewed |
| **Bug:** the task tree's pull-request menu (`TaskTreePanel`: Review…, Review (default), Review in Sphinx) only starts an agent review; it should open the review workbench on the PR, where the reviewer picks a section-based review, the full educational tour or, when available, a sphinx review | Reported 2026-11-04 | Opening the workbench on a PR needs PR mode (W5b), and the section-based review needs its own prompt and a tour whose steps carry no checks (`TourValidator` requires one per step today) | W5d |
| The Files tree does not highlight the row after Full File or Show finding | W3, W4 | The state rebuild re-renders the tree and drops a tree selection; the diff is selected through the pipeline instead | W7 |
| `TourMigration` drops every step's diagram when it rebuilds a step (pre-existing) | W3 | Not part of the workbench; found while threading `dependsOn` through migration | W7 |
| Finding cards and takes in a diff are unreadable at the workbench's default proportions: each side of the side-by-side centre is about 110 px wide, and the inlays wrap a word per line | W2, W4b | The platform clamps every component inlay to the editor viewport (`CodeReviewComponentInlayRenderer` uses `ComponentInlayAlignment.FIT_VIEWPORT_WIDTH`), and the other alignments are `@ApiStatus.Internal` on 263, so a wider inlay is not reachable through public API. The fix is the centre's width: default proportions, panes that fold, or the unified viewer when the centre is narrow | W7 |
| A live check that a section card scrolled out of view releases its finding cards and gutter "+" | W4b | Covered by `ReviewWorkbenchSectionViewPlatformTest` (the decorations end with the card's lifetime); the robot run checked a card in view only | W7 |
| The Review tool window's decisions bar text overlaps its Send button | W2 | Tool-window layout; the tool window is being reduced to a launcher | W6 |

**Plugin first; the dual-tree rule is waived for this work** (operator decision,
2026-11-03: the JavaFX app is heading for obsolescence and may never get these features).
Core changes (`TourStep.dependsOn`, `ReviewVerdict.settledInStep`, `HunkTake`,
`ReviewTour.intent`, anchorless `ReviewAnnotation`, `FixTarget`, prompt text) land in
`intellij-plugin/src/main/java` only. Consequences to respect:

- The plugin core copy now **intentionally diverges** from `app/` for these files; list them in
  the PR description so nobody "re-syncs" them back from the app.
- New fields stay **lenient and optional** on decode, so state written by the plugin never
  breaks an older reader, and the app (which does not share state files) is unaffected.
- MCP schema additions are additive (`dependsOn`, `takes[]`, optional `anchor`); agents that
  ignore them keep working against both frontends.
- Porting to the app, if ever, is a separate decision and a separate change.

## 11. Testing

- Pure models (`WorkbenchState`, `InboxModel`, section/collapse derivation, selection → keys,
  take resolution): JUnit 5, headless, like `ReviewTabModel` tests.
- `BasePlatformTestCase`: `ReviewDiffContents` returns a file-backed right content for a
  clean file and a text content for a dirty one; PSI reference at a caret in the right
  editor resolves (`TargetElementUtil`); the search scope contains exactly the diff paths;
  after an edit to the file-backed document, the computer does not return the git
  fragments and the request is marked stale.
- Codec round trips and lenient decoding for every new field; `TourValidator` deps rules.

## 12. Risks

- **Dirty worktrees** turn the right side into a snapshot (by design, §2.2). It still
  resolves, but against the current code, and its lines are not the file's lines. The
  notice must say why and offer the working-tree scope.
- **Edits during review** make the file-backed side diverge from the reviewed text. The
  rediff check (§2.2) must catch this. Without it, git fragments and finding ranges land
  on the wrong lines and nothing reports an error.
- **Base side resolves against the current project.** References on the left can point to
  the current version of a symbol, or to nothing, instead of the base revision. Acceptable,
  because the right side is the reviewed code; the left side is not a source of truth for
  navigation.
- **Many embedded editors** in the section view — lazy creation and disposal per card (S2).
- **Internal diff APIs** (`diff.impl`) stay compile-only; anything needing them is a spike
  failure, not a workaround.
