# IntelliJ plugin: Review tab and diff (P2)

Status: design, 2026-10-22. Phase P2 of
`docs/superpowers/specs/2026-10-22-intellij-integrated-review-design.md`
(the "parent spec"), row "P2 — Review tab and diff". P1 (PR #41) delivered
the review backbone: `ReviewBackend`, the router tool subset, per-launch
MCP access, `SessionReviewScopeResolver`, `submitPrompt` and the "Review
Changes" task action.

Source research: cairn investigation `integrated-review`. The live spike
results are in `integrated-review/find-p2-spikes-live-results` (IU-263.7031.23,
`runIde`, 2026-10-22). The spike code is on branch `review-tab-spike`, package
`app.drydock.intellij.review.spike` (`integrated-review/find-review-tab-diff-spike-code`).
That code is throwaway. Its findings are authoritative. Its code is not (see
"Spike defects" below).

Platform API claims marked "263 bytecode" were checked with `javap` against
`idea-263.7031.23-aarch64` (the build the plugin compiles against,
`intellij-plugin/build.gradle.kts:134`) while this spec was written. Claims
marked "live" come from the spike run. Everything else is NOT VERIFIED.

Paths are relative to `intellij-plugin/src/` unless they start with `app/`
or `docs/`. `core` = `main/java` (no platform classpath). `glue` =
`platform/java`.

## Goal

A Drydock task's window shows a **Drydock Review** tab. The tab lists every
file of the task's review scope, one row per file, with a mark on each row:
reviewed, open findings, or unreviewed. Open findings are child rows of
their file. Selecting a row shows that file's diff next to the tree. The
diff is drawn from exactly the git hunks the agent saw through
`review_scope`. The user can mark a file reviewed. That writes the same
per-hunk verdicts, keyed by hunk digest, that the standalone app writes. The
strip's Changes link opens this tab.

## Non-goals

- Findings in the diff or the editor: gutter icons, block inlays, Confirm /
  Dismiss / ask the agent, human comments. These are P3. P2 lists findings
  as tree rows and scrolls to their line, and nothing more.
- Tour (P4). Finish ▾, commit message panel, PR submit (P5). PSI and
  inspections (P6).
- **PR scope.** The P1 plan deferred PR scope resolution "to P2". This round
  defers it again, at the supervisor's direction. The tab shows the
  checkout's local scope only (`SessionReviewScopes.Scopes::local`, as
  `SessionReviewScopeResolver.java:76` does). A `pr-<n>` branch still
  carries its PR ref through `forCheckout` (`SessionReviewScopes.java:205-215`).
  No scope switcher.
- Per-hunk verdicts in the UI, "changes requested", "confirm still good",
  base-move rechecks (`SectionStates.requestRechecks` in the app). P2 has
  one file-level action: mark reviewed and unmark.
- Live anchor migration. Findings stay anchored by exact line key
  (`integrated-review/find-finding-anchor-drift-gap`).
- Inner-line (word) highlights in the diff (open question Q10).
- The `●` uncommitted marker and the `<c> commits + <u> uncommitted` hint
  from the handoff (`docs/intellij-mvp-handoff/README.md`, surface 7). See
  Q11.
- Sharing review state with the standalone app
  (`integrated-review/find-review-state-not-shared`).
- Split mode and Remote Development (`integrated-review/q-split-mode-remote-dev`).
- Using the stock Local Changes view for branch scope
  (`integrated-review/dead-stock-changes-view-branch-scope`). Grouping
  through the global `changesGroupingPolicy` EP
  (`integrated-review/dead-grouping-policy-ep-for-sections`).

## What exists today (verified in the code)

- **The Changes link is not a stub.** The parent spec says it is
  (`AgentConsoles.java:460-468`, `AgentConsoleEditor.java:535-549`). That
  is stale. PR #39 (`f1b98b8a`) wired it to a working **Drydock Changes**
  tool window: `AgentConsoleEditor.java:540-546` calls `TaskChanges.show`,
  and the hosted jump calls it at `AgentConsoles.java:328-333`. That window
  is registered at `platform/resources/META-INF/plugin.xml:97-102`. It
  lists files on the left, and opens IntelliJ's own comparison through
  `ShowDiffAction` (`TaskChangesPanel.java:184-194`), with
  `GitChangeUtils` changes (`TaskChanges.java:150-158`). It has two scopes:
  "Current vs base" and "Uncommitted vs HEAD". Its hunks are the IDE's,
  not git's, so it breaks the line-key contract below. P2 must decide what
  happens to it (Q1).
- **No IDE-noise filter exists.** Nothing in `SessionReviewScopeResolver`,
  `WorkspaceMcpSessionContext` or `DiffService` drops `.idea/` or `*.iml`.
  The untracked pass is `git ls-files --others --exclude-standard`
  (`DiffService.java:235-249`), so these files show up only when the
  repository does not ignore them. Then the agent's `review_scope` sees them
  as well.
- **`ReviewBackend` exposes no store to glue.** `annotationStore()` and
  `tourStore()` are package-private test accessors
  (`ReviewBackend.java:220-228`). The backend owns the `DiffService`,
  `GitStatusService` and `WorktreeService` instances
  (`ReviewBackend.java:70-75`).
- **The plugin writes no verdicts today.** Only the app writes them
  (`MainWorkspace.java:2297-2320`). The plugin's router reads them for
  `review_recheck` (`McpToolRouter.java:726-731`,
  `ReviewToolCodec.java:304-322`). P2 is the plugin's first verdict writer.

## Constraints

### Platform API (from the spikes)

- **Diff modules are compile-only.** `intellij.platform.diff` and
  `intellij.platform.diff.impl` are internal-visibility product modules in
  2026.3. Declaring either as a `<module>` in `plugin.xml` stops the plugin
  from loading. They stay `bundledModule(...)` in `build.gradle.kts`
  (`build.gradle.kts:150-157`). At runtime the classes resolve through the
  declared public `intellij.platform.vcs.impl`. Verified live, and the
  verifier reports Compatible (`integrated-review/find-263-diff-api-internal`,
  `integrated-review/find-p2-spikes-live-results`). Every new diff type P2
  uses (the preview processor and `DiffRequestPanel`-like classes) falls
  under this rule.
- **Tab host.** A `changesViewContent` tab with `isInCommitToolWindow="true"`
  is hosted in the Commit tool window when that window is enabled (live:
  `getToolWindowIdFor` returned "Commit"). With the modal commit plugin it
  goes to the Version Control window. Code resolves the host with
  `ChangesViewContentManager.getToolWindowIdFor(project, tabName)` and
  selects the tab with `selectContent(tabName)`. Code never hard-codes
  `ToolWindowId.COMMIT` (`integrated-review/find-review-tab-host-varies`).
  The P1-era "Changes ↗" note in that finding about
  `AgentConsoles.java:338-342` no longer matches the code. The code now
  calls `TaskChanges.show` there.
- **Changes browser.** The tree is an `AsyncChangesBrowserBase(project,
  showCheckboxes=false, highlightProblems=false)` subclass. It must call
  `init()` after the super constructor, and it must call
  `getViewer().rebuildTree()` explicitly whenever its data changes. Without
  that call the tab stays empty, and nothing reports an error (live,
  `integrated-review/find-p2-spikes-live-results`). The model comes from
  `SimpleAsyncChangesTreeModel.create(Function<ChangesGroupingPolicyFactory,
  DefaultTreeModel>)`. Rows are `ChangesBrowserNode<T>` subclasses
  (`integrated-review/find-263-diff-api-internal`,
  `integrated-review/find-review-tab-public-api`). The project reaches a
  project-area provider through constructor injection (live).
- **Diff rendering.** `DiffUserDataKeysEx.CUSTOM_DIFF_COMPUTER` renders
  exactly the fragments Drydock returns. `DiffUserDataKeys.FORCE_READ_ONLY`
  and `SCROLL_TO_LINE` are honored. A `DiffExtension` gated on a Drydock
  request key decorates only Drydock diffs. Line transfer works in both
  viewer modes (live, `integrated-review/find-p2-spikes-live-results`,
  `integrated-review/find-ij-diff-viewer-extensible`,
  `integrated-review/find-unified-viewer-line-transfer`). P2 does not
  register a `DiffExtension`, because it has no decoration to draw (P3
  does). See "Dead code" below.
- **`LineFragmentImpl` arguments.** The constructor is `(startLine1,
  endLine1, startLine2, endLine2, startOffset1, endOffset1, startOffset2,
  endOffset2, innerFragments)`. Arguments 5-8 are **character offsets** into
  the texts that `DiffComputer.compute` receives: `getStartOffset1()` and the
  other offset getters exist on the type (263 bytecode).
- **The tool-window diff preview.** The platform decides between a splitter
  and an editor tab with
  `ChangesViewContentManager.shouldHaveSplitterDiffPreview(Project, boolean
  isContentVertical)`. It is public static. Its body is `!isContentVertical
  || !CommitModeManager.isCommitToolWindowEnabled(project)` (263 bytecode).
  The building blocks exist and carry no class-level `ApiStatus` (263
  bytecode, class-tail scan):
  `PreviewDiffSplitterComponent` (`intellij.platform.vcs.impl.shared`, a
  public module), `DiffPreviewUpdateProcessor`,
  `TreeHandlerDiffRequestProcessor` and `ChangesTreeDiffPreviewHandler`
  (`intellij.platform.vcs.impl`), `TreeHandlerEditorDiffPreview`,
  `ChangesBrowserBase.setShowDiffActionPreview(DiffPreview)`, and
  `DiffManager.createRequestPanel(Project, Disposable, Window)` →
  `DiffRequestPanel.setRequest(DiffRequest)`. The superclass
  `ChangeViewDiffRequestProcessor` references `@ApiStatus.Internal` 7 times,
  and `DiffRequestProcessor` 6 times. Only `verifyPlugin` can tell whether
  the members P2 calls are among them. The same classes exist in 2025.3
  (`/tmp/ij-api-probe/index.tsv`).

### The line-key and hunk contract (must survive)

This is the agent wire contract (`integrated-review/find-line-key-contract`,
parent spec "Line-key and hunk contract").

- **One diff for human and agent.** The tab reads a scope's diff only through
  `diffService.diff(scope.diffRoot(), scope.diffScope(), scope.base(),
  DiffService.REVIEW_CONTEXT_LINES)`. That is the call `review_scope` makes
  (`WorkspaceMcpSessionContext.java:331-334`). `ReviewScope.diffScope()` is
  "the ONE mapping every reader of a scope's diff goes through … so a human
  and an agent can never be shown different code under the same scope id"
  (`ReviewScope.java:88-107`). The IDE's comparison never defines a hunk
  (`integrated-review/hyp-git-diff-stays-authoritative`).
- **Line keys.** `n<newLine>` for context and added rows, `o<oldLine>` for
  deleted rows (`ReviewAnnotation.java:22-25`). P2 uses the key-to-position
  direction only: `n<N>` → `(Side.RIGHT, N-1)`, and `o<N>` → `(Side.LEFT,
  N-1)`. The position-to-key direction has no P2 caller, so it waits for P3
  (human comments).
- **Hunk ids and digests.** Hunk ids are `h_<file>_<index>` (`HunkIds.java:21`).
  The digest is `HunkDigest.of(path, hunk)`, computed over the path and the
  kind and text of every row, context included, with no line numbers
  (`HunkDigest.java:33-42`). "Reviewed" is a set of
  `ReviewVerdict(scopeId, digest, APPROVED, …)`. P2 adds no digest and no
  key format of its own.
- **Verdicts are dated by commits.** `ReviewVerdict.baseCommit` and
  `headCommit` are resolved commit ids, never ref names. A verdict stamped
  `"main"` could never go stale (`MainWorkspace.java:3036-3043`;
  `ReviewVerdict.staleAgainst`, `ReviewVerdict.java:95-97`). The router hands
  `baseCommit` on to `review_recheck` (`ReviewToolCodec.java:322`), so a
  value that is not a revision breaks the agent's recheck.
- **Approval rule.** An approval is refused while the file has an open
  blocking finding (`ReviewAnnotation.blocksApproval`,
  `ReviewAnnotation.java:195-197`; the app's rule is at
  `MainWorkspace.java:2306-2314` and `SessionReviewView.java:1367-1371`). A
  file's decision is `VerdictMerge.derive` over its hunks' verdicts: any
  `CHANGES` wins, and an approval needs every hunk settled
  (`VerdictMerge.java`).
- **Read-only diffs.** Every review diff opens with `FORCE_READ_ONLY`
  (parity with the app; `integrated-review/find-finding-anchor-drift-gap`).

### Scope identity and binding

- `ReviewScopeRegistry.mint` **updates an existing handle in place**, the
  session binding included (`ReviewScopeRegistry.java:107-129`). The scope
  identity excludes the session and the base (`ReviewScopeRegistry.java:55`,
  `:196`). If the tab minted the checkout's scope with another session, or
  with none, it would silently take the scope away from the running agent.
  Then `isAddressableBy` (`ReviewScopeRegistry.java:219-225`) would refuse
  that agent's `review_*` calls. **The tab must never change an existing
  scope's session binding.**
- `repositoryRoot` passed to `forCheckout` is the main checkout, never the
  catalog root (parent spec "Review scopes"; `SessionReviewScopeResolver`
  class javadoc). Real paths are compared (macOS `/var` vs `/private/var`),
  and prunable worktree entries are skipped
  (`integrated-review/find-p1-impl-gotchas`).
- The registry lives in memory only. Ids are derived by HMAC from the secret
  in `annotations.json`, so after an IDE restart the tab mints the same id
  and finds the stored findings and verdicts (parent spec "Persistence
  locations").

### Repository rules (AGENTS.md)

- Blocking work never runs on the EDT. Every user-triggered operation shows
  progress at once. Every path (success, error, early return) clears the
  progress.
- Every git spawn goes through `ProcessRunner`, with a timeout. Revision and
  path arguments come after `--end-of-options`. A failed command never
  silently equals an empty result.
- One writer per persistent file. The tab writes verdicts only through the
  `AnnotationStore` that `ReviewBackend` owns. It never constructs a second
  store (parent spec "Ownership and lifecycle").
- Keystroke- or event-driven rebuilds are debounced and coalesced. No
  rebuild per event.
- Anything with listeners or native resources has a dispose path that the
  content's disposal actually calls.
- Shared presentation (relative time, error unwrapping) goes through the
  plugin's existing utilities. No per-view copies.
- Imports, not fully qualified names.
- **Dead code.** The spike package and its `plugin.xml` entries (the
  changesViewContent tab "Drydock Review (Spike)", the `DiffExtension`, and
  the `Drydock.SpikeReviewDiff` Tools action, `plugin.xml:117-121,168-172`)
  are deleted. P2 adds no class without a production caller: no
  `DiffExtension`, and no position-to-key mapper.
- Core copies have no sync check. A core seam P2 adds (Q6) lands
  byte-identical in `app/` and `intellij-plugin/`, in its own commit, and a
  `diff` gate checks it (`integrated-review/find-p1-impl-gotchas`).
- Do not fork prompt text (kb/find-check-answerability-gap,
  kb/find-learning-science-authoring-rules). P2 sends no prompts.

### Spike defects the real code must not inherit

- `SpikeReviewSupport.fragmentsFor` passes **line numbers** where
  `LineFragmentImpl` expects character offsets (see above). The live render
  looked right because line-level drawing reads the line fields. Anything
  that reads the offsets (navigation, copy, inner fragments, P3 decorations)
  would be wrong.
- `SpikeReviewSupport.baseContent` reads the left side at the **tip of
  `main`** (`git show main:<path>`). The hunks of `BRANCH_WORKING_TREE` are
  measured from the **merge base** (`DiffService.java:150-155`). After `main`
  moves past the fork point, the left text and the fragments disagree.
- The spike skips deleted files (no worktree text) and runs git inside the
  tree-model builder. A deleted file is review material, with an empty
  right side. The builder must be pure (see Threading).
- The spike filters every path under `.idea/` and every `*.iml`. A tracked,
  modified `.idea/` file is real review material.

## The diff preview: embed, following the platform's placement rule

**Position.** The tab owns one diff preview that follows the tree selection.
P2 never walks files with a `DiffManager.showDiff` chain. The preview's
placement follows the rule the platform applies to Local Changes:
`shouldHaveSplitterDiffPreview(project, isContentVertical)`.

- When the rule says splitter (the host content is horizontal, or the Commit
  tool window is not enabled): the diff is embedded in the tab, in a
  splitter beside the tree (`PreviewDiffSplitterComponent` pattern).
- When the rule says no splitter (the tab sits in the vertical, left-docked
  Commit tool window, which is the default layout): the preview is the
  platform's **single reused editor-tab diff preview**. The tree stays
  visible in the tool window, and the one preview tab re-targets on every
  selection (`TreeHandlerEditorDiffPreview` pattern).

**Evidence.**

1. Live UX finding: a `showDiff` chain shows one file at a time in a floating
   window, and "there are more files" is visible only as prev/next arrows.
   The user did not find it (`integrated-review/find-p2-spikes-live-results`).
   Both placements above keep the file list on screen, and the list drives
   the diff.
2. The platform's own rule, read from 263 bytecode: a splitter only when the
   content is not vertical or there is no Commit tool window. In the default
   layout a splitter would leave a two-side diff a few hundred pixels wide.
   Local Changes does not embed there for that reason, and a Drydock tab
   that always embedded would be the odd one out inside the same tool
   window.
3. The repository already chose this layout. PR #39 placed the Drydock
   Changes list in a left tool window "so the native diff editor can use the
   full center area" (`plugin.xml:97-100`).
4. All building blocks are public classes with no class-level `ApiStatus`
   (Constraints). The preview is fed `DiffRequestProducer`s, so each file's
   request still carries `CUSTOM_DIFF_COMPUTER`, `FORCE_READ_ONLY` and
   `SCROLL_TO_LINE`.

**What is not verified.** That the tree-driven preview processor keeps the
request user data that `CUSTOM_DIFF_COMPUTER` and `FORCE_READ_ONLY` need. The
spike verified them only on `SimpleDiffRequest` under `showDiff`. This is a
live check (AC24). Fallback if the processor drops the keys: a
`DiffManager.createRequestPanel` panel. It is public and un-annotated, and
its `setRequest` takes the same `SimpleDiffRequest` the spike verified. The
splitter case uses it in the tab, and the editor-tab case uses it in one
reused editor. The placement rule stays. Only the processor changes.

`DiffManager.showDiff` stays acceptable for a single jump that has no tree
context (P3 finding jumps, per `integrated-review/find-p2-spikes-live-results`).
P2 has no such caller.

## Behaviour

### Scope shown

The tab shows the local review scope of the **project's checkout**. That is
the project base path, the directory a Drydock task window opens
(`integrated-review/find-review-lives-in-owner-window`). The kind follows
`forCheckout`: `WORKTREE` (`BRANCH_WORKING_TREE`) in a linked worktree, and
`WORKING_TREE` in the main checkout. If the registry already holds that
checkout's identity, the tab uses that scope, binding unchanged. If not, the
tab mints it through the same `SessionReviewScopes.forCheckout` path, bound
to the session the Changes link came from. When no such session exists, the
scope is minted unbound (Q7). Base and head come from the same resolution,
so the agent and the tab diff the same base.

A `pr-<n>` checkout's base privileges the PR's declared `baseRefName` when
the review prompt's resolver can name the pull request
(`SessionReviewScopeResolver` consults the `PrLookup` the backend wires,
aliased to `pr-<n>` branches so an ordinary branch spends no gh round trip);
a failed or empty lookup falls back to the fork-point heuristic, same scope
id. A PR review session opens the tab itself once its agent takes the
review request (`PullRequestReview`, through `ReviewTabOpener`) — except
under a custom review prompt, which bypasses Drydock's review tools
entirely (Q16). A second "Review…" click on a live PR joins the running
review session instead of re-checking out.

### Rows and marks

- One row per file of the scope's review diff, sorted by path. The tab hides
  **untracked** files (`FileDiff.untracked()`) whose path starts with
  `.idea/` or ends with `.iml`. Hiding is display-only. Hunk ids are
  per-file (`h_<file>_<index>`), so no other file's ids or digests change.
- One mark per row. Precedence (Q3):
  1. `◆ n`: n open findings on the file. "Open" has the meaning of
     `AnnotationStore.openCount`: not resolved, not dismissed
     (`AnnotationStore.java:178-183`), restricted to the file.
  2. `✓` reviewed: `VerdictMerge.derive` over the file's distinct hunk
     digests is `APPROVED` or `AUTO_APPROVED`, and no counted verdict is
     stale against the scope's current base commit (Q4).
  3. `○` unreviewed: everything else, including `CHANGES`. A `CHANGES`
     decision gets its own tooltip text.
- A file with no text hunks (binary or mode-only) has no digest. It shows a
  "no text changes to review" mark, and it is excluded from the counts.
- Open findings are child rows of their file: severity, title, and `:line`
  taken from the start key. The tab hides resolved and dismissed findings
  and shows a footer, `n resolved findings hidden`
  (`docs/intellij-mvp-handoff/README.md`, surface 7). A finding whose start
  key is not in the current diff (drift) is still listed, with a "line not
  in the current diff" hint.
- The header reads `<k> / <n> reviewed`, where n is the number of files with
  at least one hunk.

### Mark file reviewed

- It is available from the tab (toolbar and row context menu) and from the
  preview's toolbar (`DiffUserDataKeysEx.CONTEXT_ACTIONS`). Both run the
  same command (Q13).
- It writes one `ReviewVerdict(scopeId, digest, APPROVED, empty note, now,
  baseCommit, headCommit)` per distinct digest of the file. Base and head
  are the commits that `scope.base()` and
  `SessionReviewScopes.headRevision(scope.head())` resolve to.
- It is refused, with the reason shown, when the file has an open blocking
  finding, or when either commit cannot be resolved (Q5).
- Unmark clears the file's human `APPROVED` verdicts only (Q14).
- The tab marks only the digests of the diff currently shown. If the diff
  changed since the last load, the command recomputes the file's digests
  from a fresh diff before it writes.

### Changes link

Both strip paths open the Review tab in the session's own window instead of
`TaskChanges.show`: in-window (`AgentConsoleEditor.java:540-546`) and the
hosted jump (`AgentConsoles.java:328-333`). Opening the tab resolves the
host with `getToolWindowIdFor`, activates it, and selects the tab. A
"Loading review…" state appears at once, before the scope loads.

### Refresh

- Annotation and registry change events (`AnnotationStore.addChangeListener`,
  `ReviewScopeRegistry.addChangeListener`) recompute the **marks** from the
  cached diff. They spawn no git. `putVerdict` fires with a `null` key for
  any scope (`AnnotationStore.java:346-349`), so the listener cannot filter
  by key and must coalesce.
- File changes under the checkout, the refresh action, and the Changes link
  re-run the diff. Re-runs are debounced, and at most one diff runs per
  scope at a time. A change that arrives during a run triggers one follow-up
  run.
- A user-triggered load first saves unsaved documents (Q9). The preview
  snapshots the file texts. Fragments are fed only when the snapshot matches
  the hunk rows. Otherwise the preview shows "changed since the diff was
  computed" and schedules a re-run.

### Preview contents

- Left side: the content at the scope's left revision. That is the merge
  base for `BRANCH_WORKING_TREE` and `BASE` (the commit `DiffService`
  measures from), and `HEAD` for `WORKING_TREE`. It is empty for an added
  file. For a renamed file (kind `R`) it is read at the pre-image path (Q6).
- Right side: the working-tree file for `WORKING_TREE` and
  `BRANCH_WORKING_TREE`, and `HEAD` for `BASE`. It is empty for a deleted
  file.
- `CUSTOM_DIFF_COMPUTER` returns precomputed fragments: one per contiguous
  run of added or deleted rows, with offsets computed from the passed texts.
- Request keys: `FORCE_READ_ONLY = true`. `SCROLL_TO_LINE` is the first
  changed line for a file row, and the finding's key position for a finding
  row. The request also carries a Drydock request key, kept for P3's
  `DiffExtension` gate.

## Threading

| Work | Thread |
|---|---|
| Scope lookup and mint (`worktree list`, `defaultBranch`, `reviewBase`) | `WorktreeService` / `GitStatusService` executors owned by `ReviewBackend`, composed as futures. Never the EDT. |
| Review diff (`DiffService.diff`) | `ReviewBackend`'s `DiffService` executor |
| Left/right text snapshots (`git show` through `ProcessRunner`, file reads), and base/head commit resolution for verdicts | background (plugin executor or the git service executor). Never the EDT and never the tree builder. |
| Snapshot assembly (rows, marks, finding children, counts, fragments per file) | background, as a pure function of diff + findings + verdicts + base commit |
| `SimpleAsyncChangesTreeModel` builder | the platform's background builder. It reads only the latest assembled snapshot. No git, no I/O, no store lock held across calls. |
| `rebuildTree()`, header text, empty/error states, progress placeholders | EDT |
| Creating `Document`s, `DiffRequest`s and updating the preview | EDT |
| `DiffComputer.compute` | the platform's diff thread. It returns precomputed fragments only. Pure. |
| `putVerdict` / `clearVerdict` | background. The store persists on its own executor; `flushPendingSaves()` exists for tests. |
| Store and registry listeners | the writer's thread (MCP virtual threads for agent writes). They only schedule one coalesced recompute, hopping to the EDT for UI. |
| `FileDocumentManager.saveAllDocuments()` (Q9) | EDT, before the background load starts |
| Host resolution, `selectContent`, tool window activation | EDT |

## Acceptance criteria

Gate: `cd intellij-plugin && JAVA_HOME=~/.sdkman/candidates/java/25.0.2-tem
./gradlew test buildPlugin verifyPlugin` passes. `verifyPlugin` reports
Compatible on IU-263 with no internal API usage
(`integrated-review/find-p1-impl-gotchas`, "Gradle now needs…").

| # | Criterion | Traces to | Test |
|---|---|---|---|
| AC1 | `plugin.xml` registers one `changesViewContent` tab, "Drydock Review", with `isInCommitToolWindow="true"`. The spike package and its three `plugin.xml` entries are gone. | Constraints: tab host, dead code | Build gate; a test that reads the packaged `plugin.xml` |
| AC2 | `plugin.xml` declares no `<module>` for `intellij.platform.diff` or `intellij.platform.diff.impl`. `verifyPlugin` reports Compatible with no internal-API usage. | find-263-diff-api-internal | Gate |
| AC3 | Opening the tab resolves the host through `getToolWindowIdFor(project, "Drydock Review")` and selects the tab. No open path names `ToolWindowId.COMMIT`. | find-review-tab-host-varies | Unit test over an injectable host-resolver seam; a grep gate on the glue |
| AC4 | For a fixture worktree, the tab snapshot's file list equals the `review_scope` file list for the same scope id, minus the filtered files. Per-file hunk ids and digests are equal. | One diff for human and agent | JUnit with a git fixture and a real `ReviewBackend`; `review_scope` over HTTP, as in P1 AC9 |
| AC5 | An untracked `.idea/workspace.xml` and an untracked `x.iml` are hidden. A tracked, modified `.idea/codeStyles/Project.xml` is shown. | Rows and marks | JUnit with a git fixture |
| AC6 | Mark derivation is a pure function with these cases: open findings → `◆ n` (resolved and dismissed not counted). All hunks approved and fresh → `✓`. Some hunks unsettled → `○`. Any `CHANGES` → `○` with its tooltip. A stale verdict → not `✓`. No hunks → the "no text changes" mark. | Rows and marks; VerdictMerge | JUnit table test |
| AC7 | Open findings appear as child rows with severity, title and `:line`. Resolved and dismissed findings are hidden and counted in the footer. A finding whose key is not in the diff is listed with the drift hint. | Rows and marks | JUnit on the snapshot model |
| AC8 | The header reads `<k> / <n> reviewed`. n counts only files with at least one hunk. | Rows and marks | JUnit |
| AC9 | Mark reviewed writes exactly one `APPROVED` verdict per distinct `HunkDigest.of(path, hunk)` of the file. The scope id is the tab's scope, and the base and head are 40-hex commit ids that equal `git rev-parse` of the scope's base and head. After `flushPendingSaves()` the verdicts are in `annotations.json`. | Verdicts dated by commits | JUnit with a git fixture and a real backend |
| AC10 | After AC9, an agent's `review_recheck` on that file's hunk id finds the verdict, where before AC9 it refused with "no verdict". | Contract survives across surfaces | JUnit over HTTP |
| AC11 | Mark reviewed is refused, and writes nothing, while the file has an open blocking finding (`blocksApproval`) or while base or head cannot be resolved. Each case shows its own reason. | Approval rule; Q5 | JUnit |
| AC12 | Unmark removes the file's `APPROVED` verdicts and leaves other decisions and other files' verdicts in place. | Q14 | JUnit |
| AC13 | The fragment builder maps hunk rows to `LineFragmentImpl`s whose line ranges equal the changed-row runs and whose offsets equal the line-start offsets in the passed texts. Cases: add only, delete only, replace, two runs in one hunk split by context, several hunks, an added file, a deleted file, no trailing newline at EOF, CRLF text. | LineFragmentImpl arguments; spike defect 1 | Pure JUnit |
| AC14 | The left text is the content at the merge base, not at the base tip. In a fixture where `main` advanced after the fork and changed the same file, the left text equals `git show <merge-base>:<path>`. A renamed file's left text is read at its pre-image path. | Spike defect 2; Q6 | JUnit with a git fixture |
| AC15 | When a snapshot text disagrees with the hunk rows (the file was edited after the diff), the computer is not fed, the preview shows the "changed since the diff" state, and exactly one re-run is scheduled. | Refresh | Pure JUnit plus a fake scheduler |
| AC16 | Every review request carries `FORCE_READ_ONLY=true`, `CUSTOM_DIFF_COMPUTER`, and the Drydock request key. `SCROLL_TO_LINE` is `(RIGHT, firstAdded-1)` for a file row, `(RIGHT, N-1)` for `n<N>`, and `(LEFT, N-1)` for `o<N>`. | Line keys; read-only | Pure JUnit on the request builder |
| AC17 | Tree selection drives one preview. No code path in the tab calls `DiffManager.showDiff`. Placement follows `shouldHaveSplitterDiffPreview(project, isVertical)`. | Diff preview position | Unit test on the placement decision; grep gate; live check AC24 |
| AC18 | The in-window and the hosted Changes link both open the Review tab in the session's own window and show that session's checkout scope. The placeholder appears before any background work. Neither path calls `TaskChanges.show`. | Changes link | Unit test with fakes for the tab opener (seam in the style of P1's `ReviewRequest`) |
| AC19 | Loading the tab never changes the session binding of an existing scope. A scope bound to a running session A stays addressable by A, and only by A, after the tab loads it from a window whose Changes link named session B. An unbound scope is bound only to the Changes-link session. | Scope identity and binding | JUnit over `ReviewScopeRegistry` and the tab's scope source |
| AC20 | No git, file I/O or store write runs on the EDT. The tree builder and `DiffComputer` are pure over a snapshot. | Threading | JUnit with a thread-asserting executor seam (as `DrydockPluginServiceTest` does for init); review |
| AC21 | A burst of 50 annotation-store events gives one mark recompute and no git spawn. A burst of file-change events gives at most one diff in flight and one follow-up. Disposing the tab content unregisters both store listeners, and later events do nothing. | Refresh; lifecycle | JUnit with a fake scheduler and a counting git seam |
| AC22 | Each of these states renders its own message, and every one clears the loading placeholder: backend not ready, no Drydock session for this checkout, not a git checkout, diff failed (with an stderr excerpt in the log at WARNING), empty diff. | Repository rules: progress | JUnit on the state model |
| AC23 | `compileCoreJava` passes. A core seam (Q6), if added, is byte-identical in `app/` and `intellij-plugin/` (`diff` gate), and the app's tests pass. | Core copies | Gradle gate; `diff` |
| AC24 | Live `runIde` checklist, not automated, recorded in the investigation. (a) The tab is in the Commit window; with Commit tool window disabled it is in the Version Control window, and the Changes link reaches it in both. (b) Vertical Commit window → editor-tab preview; horizontal → splitter. (c) The preview shows exactly the git hunks in the two-side and the unified viewer, read-only. (d) A finding row scrolls to its line. (e) Mark reviewed survives an IDE restart (same scope id). (f) An agent's `review_finding` makes its `◆` appear without a manual refresh. | Diff preview "not verified" | Manual (sandbox IDE) |

NOT VERIFIED until AC24 runs: the processor keeps `CUSTOM_DIFF_COMPUTER` and
`FORCE_READ_ONLY`, the editor-tab preview opens in the default layout, and
the `changesViewContent` visibility predicate is re-evaluated (Q8).

## Risks

| Risk | Effect | Mitigation |
|---|---|---|
| The preview processor stack (`TreeHandlerDiffRequestProcessor` ← `ChangeViewDiffRequestProcessor`, 7 `@Internal` references; `EditorTabDiffPreview` in the internal-visibility `diff.impl` module) uses internal members or churns | `verifyPlugin` flags it, or a future IDE breaks it | AC2 gate. Fallback: `DiffManager.createRequestPanel` (un-annotated), same placement rule |
| The processor drops request user data | IDE comparison drawn instead of git hunks, or an editable right side | AC24 live check before the tab ships; the fallback above |
| The tab rebinds a running agent's scope (`mint` updates in place) | The agent's `review_*` calls are refused mid-review | AC19; never mint over an existing identity with a different session |
| Snapshot race: the file changes between the diff and the text read | Fragments on the wrong lines; marks on the wrong digests | Snapshot/row check (AC15); re-diff before a verdict write |
| Left side read at the base tip | Misaligned fragments once `main` moves | AC14 |
| Renames: `FileDiff` has no pre-image path (`UnifiedDiff.java:26-35`; kind `R` at `DiffService.java:426-427`), and rename detection follows the user's git config | No left text for a renamed file | Q6 core seam; AC14 |
| Spawn storms: agents write many findings in a burst | One git per event | Store events recompute marks only (AC21) |
| IDE-noise filter hides real material, or misses some | Missed review, or noise | Filter untracked only (AC5). The agent's `review_scope` still lists these files (no core filter). Recorded as a known difference. |
| The plugin becomes a verdict writer | `review_recheck` becomes reachable with plugin-written bases | Commit ids only, refusal when unresolved (AC9, AC11) |
| Finding drift: an agent fixes code and keys move | A finding row points at a wrong line or at none | Drift hint (AC7); read-only diff; migration out of scope (`integrated-review/find-finding-anchor-drift-gap`) |
| Worktree-path orphaning: the scope id is an HMAC of the worktree path and PR number, so a changed `worktreesDirectory` config, a moved home, or a renamed repository mints a new id | A PR's findings and verdicts silently orphan under the old id; the new scope reads empty | Same bug class as the base/head variant the `ReviewScopeRegistry.Identity` javadoc documents (identity now excludes base and head, but the path stays in it). Accepted known behavior; no migration in P2 |
| The visibility predicate is evaluated once | The tab is missing in a window that gains a session, or shown in a non-Drydock window | Q8 default; AC24 |
| Retiring Drydock Changes drops its "Uncommitted vs HEAD" scope | Users lose a view that shipped in #39 | Q1; main-checkout sessions still get `WORKING_TREE` |
| Large diffs (`MAX_UNTRACKED` skip, `DiffService.java:42-43,177-180`; huge files) | Slow snapshot, large documents | Background assembly; snapshot texts per file on demand at selection, not for the whole tree |
| Split mode | Swing preview or custom tab may not work | Out of scope (`integrated-review/q-split-mode-remote-dev`) |

## Open questions

- **Q1.** What happens to the Drydock Changes tool window (`TaskChanges*`,
  `plugin.xml:97-102`)? — `DECIDED by the user (2026-10-22): retire it in P2.
  The Changes link moves to the Review tab, so TaskChanges loses its only
  callers, and the dead-code rule deletes it along with its tests. Its
  IDE-comparison diff violates the hunk contract. The "Uncommitted vs HEAD"
  view is dropped.`
- **Q2.** Always embed a splitter, or follow the platform placement rule? —
  `assumed: follow shouldHaveSplitterDiffPreview (splitter when horizontal
  or no Commit window, single reused editor-tab preview in the vertical
  Commit window).`
- **Q3.** Mark precedence when a file has both findings and approvals? —
  `assumed: ◆ findings > ✓ reviewed > ○ unreviewed, one mark per row.`
- **Q4.** How does a stale verdict render (its base commit differs from the
  current base)? — `assumed: not counted as reviewed. The row shows a
  "reviewed against an older base" hint, and marking again re-stamps it. No
  BaseMove file filter and no recheck dispatch in P2.`
- **Q5.** Base or head cannot be resolved when marking? — `assumed: refuse
  with a reason. Do not stamp the app's "unresolved" sentinel: it lives in
  app UI (SessionReviewView.java:494), not in core, and it is not a revision
  review_recheck can use.`
- **Q6.** Pre-image path for renamed files? — `assumed: a small generic core
  seam that carries the pre-image path on the parsed file diff, byte-identical
  in app/ and intellij-plugin/, in its own commit before its first use.
  Existing callers are unchanged.`
- **Q7.** Which session binds a scope the tab mints for the first time? —
  `assumed: the session whose Changes link opened the tab. When the tab is
  opened directly, mint unbound. Never touch an existing binding.`
- **Q8.** Should the tab be hidden in windows with no Drydock session? —
  `assumed: yes, through predicateClassName, from in-memory state only
  (a session's checkout equals the project base path). If AC24 shows the
  predicate is not re-evaluated, register it always and show the empty
  state instead. A tab stranded in NO_SESSION by the window-before-session
  ordering now re-runs its load when the session catalog changes; the
  predicate's own re-evaluation remains the AC24 live check.`
- **Q9.** Save unsaved documents before computing the diff? — `assumed: yes
  for user-triggered loads (Changes link, refresh action), on the EDT before
  the background work. Not for event-driven refreshes.`
- **Q10.** Inner-line (word) highlights? — `assumed: none in P2. Fragments
  carry no inner fragments. Whether an empty list or null renders better is
  checked live (AC24c).`
- **Q11.** The `●` uncommitted marker and the commits/uncommitted hint line?
  — `assumed: deferred. The BRANCH_WORKING_TREE diff does not say which rows
  are uncommitted, so it needs a second diff.`
- **Q12.** PR scope? — `assumed: out of P2 (supervisor direction), contrary
  to the P1 plan's deferral. It moves to the phase that submits to PRs (P5).`
- **Q13.** Where does "Mark file reviewed" live? — `assumed: tab toolbar,
  row context menu, and the preview toolbar (CONTEXT_ACTIONS), all one
  command.`
- **Q14.** What does unmark clear? — `assumed: only the file's human APPROVED
  verdicts. AUTO_APPROVED and CHANGES stay.`
- **Q15.** Tab name? — `assumed: "Drydock Review" (it is also the key for
  getToolWindowIdFor and selectContent).`
- **Q16.** What does the tab show for a PR review running with a custom
  review prompt (which bypasses Drydock's review tools)? — `DECIDED by the
  user (2026-10-23): keep it silent. The tab resolves the checkout's scope
  normally and shows the PR diff with every row unreviewed and no
  findings; the PR review flow does not open the tab for such sessions, so
  the state is only reached by opening it by hand. An explanatory status
  or hiding the tab would need a way for the tab to know the session runs
  outside Drydock's tools.`

No question blocks the spec.
