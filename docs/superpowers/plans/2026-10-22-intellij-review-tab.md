# IntelliJ plugin P2 — Review tab and diff: Implementation Plan

**Spec:** `docs/superpowers/specs/2026-10-22-intellij-review-tab-design.md` (P2 only).
**Decided before this plan:** Q1 — retire the Drydock Changes tool window
(`TaskChanges*`) in P2, including its tests; the Changes link moves to the
Review tab. Q2–Q15: accepted defaults, encoded inline below.

Paths are repository-relative. `core` = `intellij-plugin/src/main/java`
(no platform classpath), `glue` = `intellij-plugin/src/platform/java`,
`ptest` = `intellij-plugin/src/test/java`. Every Gradle command runs with
`JAVA_HOME=~/.sdkman/candidates/java/25.0.2-tem` (2026.3 class files are
version 69; `kb/find-p1-impl-gotchas`).

**Spike-defect claims re-verified against the code before encoding** (branch
`review-tab-spike`, `git show d83382af:…`):

- *fragmentsFor line-vs-offsets* — CONFIRMED. `SpikeReviewSupport.flushRun`
  constructs `new LineFragmentImpl(start1, end1, start2, end2, start1, end1,
  start2, end2, List.of())` where `start1..end2` are 0-based **line numbers**;
  arguments 5–8 must be **character offsets** into the passed texts (spec,
  "263 bytecode": the constructor is `(startLine1, endLine1, startLine2,
  endLine2, startOffset1, endOffset1, startOffset2, endOffset2, inner)`).
- *baseContent reads main's tip* — CONFIRMED. `baseContent` runs
  `git show --end-of-options main:<path>` (`BASE_BRANCH = "main"`), while the
  hunks of `BRANCH_WORKING_TREE` are measured from the **merge base**
  (`DiffService` range switch: `case BRANCH_WORKING_TREE -> mergeBase(...)`).
- Also confirmed from the spike: deleted files are skipped (no worktree text),
  git runs inside the tree-model builder, and every `.idea/`/`*.iml` path is
  filtered regardless of tracked state. All three are defects to fix (T7, T12,
  T3).

**Platform facts verified for this plan beyond the spec:**
`ChangesViewContentEP` (263 bytecode) has `predicateClassName`, and
`newPredicateInstance(project)` instantiates that class through the same
Project-constructor-injection path as the provider and requires it to
implement `java.util.function.Predicate<Project>`. The visibility predicate
(T13) is written against exactly that.

## Approach

Build the tab from small, independently testable pieces: a pure snapshot
model (rows, marks, finding children, counts) over the one git diff the agent
sees; a pure fragment builder that maps hunk rows to `LineFragmentImpl`s with
real character offsets (the spike's defect fixed); a pure request plan plus a
thin platform assembly; a background contents loader that reads the left side
at the merge base and the pre-image path of renames; a scope source that
never rebinds an existing scope; and verdict commands that write through the
backend's own `AnnotationStore`. The tree is an `AsyncChangesBrowserBase`
subclass on the `changesViewContent` EP; the preview follows the platform's
own placement rule (`shouldHaveSplitterDiffPreview`) with the
`DiffManager.createRequestPanel` fallback behind one interface. The old
Drydock Changes window is deleted, not migrated. Rejected alternatives:
- Always embed a splitter — rejected; it is the odd one out in the vertical
  Commit window (spec, "Diff preview position", evidence 2–3).
- `DiffManager.showDiff` chains — rejected live (`kb/find-p2-spikes-live-results`:
  one file at a time, "more files" only as prev/next arrows).
- Extending the spike code — rejected: it is throwaway; its fragmentsFor and
  baseContent are wrong (verified above) and it runs git inside the tree
  builder.
- Showing branch scope in the stock Local Changes view or via the
  `changesGroupingPolicy` EP — refuted dead ends
  (`kb/dead-stock-changes-view-branch-scope`,
  `kb/dead-grouping-policy-ep-for-sections`).
- A second `AnnotationStore` in the tab — rejected; the tab writes only
  through the store `ReviewBackend` owns (one writer per file).

## Tasks

### T1 Core seam: pre-image path on `FileDiff` (Q6)
- files:
  - `app/src/main/java/app/drydock/git/UnifiedDiff.java`
  - `app/src/main/java/app/drydock/git/DiffService.java`
  - `intellij-plugin/src/main/java/app/drydock/git/UnifiedDiff.java`
  - `intellij-plugin/src/main/java/app/drydock/git/DiffService.java`
- depends: none
- change: make the edit once in the app tree, then copy both files
  byte-identically into the plugin tree.
  - `UnifiedDiff.FileDiff` gains an eighth, last record component
    `Optional<String> oldPath` — the pre-image path of a rename, empty
    otherwise. Add a secondary 7-argument constructor delegating with
    `Optional.empty()` so all existing callers (2 production sites in
    `DiffService`, ~66 test-fixture sites) keep compiling unchanged. One
    javadoc sentence on what `oldPath` carries.
  - `DiffService`'s parser: capture the path from the `rename from <path>`
    line (unquote exactly as `parseDiffGitPath` does) into a local reset at
    each `diff --git` header, and pass it through the canonical 8-argument
    constructor at both `FileDiff` construction sites. Kind stays `"R"`;
    the post-image path remains authoritative (`+++ b/` overwrite unchanged).
    No other behaviour change: rows, line keys, hunk digests unchanged
    (`kb/find-line-key-contract`).
- tests: none in this task (T2).
- done when: `diff` of each app/plugin copy pair is empty;
  `./gradlew :app:compileJava compileCoreJava` (root) and
  `cd intellij-plugin && JAVA_HOME=… ./gradlew compileCoreJava` pass; the
  existing `DiffService*Test` classes pass unchanged. This is the Q6 seam of
  AC14/AC23 (spec: byte-identical, own change, before first use).
- memories: kb/find-p1-impl-gotchas (nothing syncs the core copies — explicit
  `diff` gate; JAVA_HOME), kb/find-line-key-contract.

### T2 Rename-parsing tests for the seam
- files:
  - `app/src/test/java/app/drydock/git/DiffServiceTest.java`
  - `intellij-plugin/src/test/java/app/drydock/git/DiffServiceTest.java`
- depends: T1
- change: edit once, copy byte-identically. New fixture case: a repo where a
  file is `git mv`-renamed and edited in the same commit, plus one ordinary
  modification. Assert for `BASE` and `BRANCH_WORKING_TREE`: the renamed
  file's `FileDiff.path()` is the post-image path, `kind()` is `"R"`,
  `oldPath()` is the pre-image path; the ordinary file has `oldPath()` empty.
  All existing assertions stay.
- tests: as above (the task is the test).
  Narrow: root `JAVA_HOME=… ./gradlew :app:test --tests 'app.drydock.git.DiffServiceTest'`
  and `cd intellij-plugin && JAVA_HOME=… ./gradlew test --tests 'app.drydock.git.DiffServiceTest'`.
- done when: both narrow commands pass; the two test copies are
  byte-identical. Core half of AC14; AC23's `diff` gate.
- memories: kb/find-p1-impl-gotchas.

### T3 `ReviewTabModel` — the pure snapshot
- files:
  - `glue/app/drydock/intellij/review/ReviewTabModel.java` (new)
  - `ptest/app/drydock/intellij/review/ReviewTabModelTest.java` (new)
- depends: none
- change: a pure class, **no platform imports** (JUnit-testable headless).
  Input record `Inputs(UnifiedDiff diff, List<ReviewAnnotation> findings,
  List<ReviewVerdict> verdicts, Optional<String> currentBaseCommit)`; output
  `Snapshot` used by the tree (T12) and header (T13):
  - Displayed file rows in path order. Display-only filter (Q3/AC5): hide
    rows where `FileDiff.untracked()` is true and the path starts with
    `.idea/` or ends with `.iml`. Tracked `.idea/` files are shown.
  - One mark per row, precedence (Q3): (1) `◆ n` with n = open findings of
    this file — `f.file()` equals the row path, `!f.resolved()`, `f.triage()
    != DISMISSED` (the per-file form of `AnnotationStore.openCount`'s rule);
    (2) `✓` reviewed — `VerdictMerge.derive` over the file's distinct hunk
    digests (`HunkDigest.of(path, hunk)`, one `Optional<ReviewVerdict>` per
    digest via the store's verdicts) is `APPROVED`/`AUTO_APPROVED` **and** no
    counted verdict is `staleAgainst(currentBaseCommit)`; an empty
    `currentBaseCommit` counts as stale (Q4, conservative);
    (3) `○` otherwise, with a distinct tooltip for a `CHANGES` decision and a
    "reviewed against an older base" hint for the stale case (Q4).
  - A file with no hunks (binary/mode-only): the "no text changes to review"
    mark, excluded from the counts.
  - Finding children per file: severity, title, `:<line>` parsed from
    `startKey` (`n<N>`/`o<N>` → N); a child whose start key is not any row's
    key of the current diff carries the "line not in the current diff" drift
    hint (`kb/find-finding-anchor-drift-gap` — exact-match only, no
    migration). Resolved and dismissed findings are hidden; the footer text
    `n resolved findings hidden`.
  - Header counts: `<k> / <n> reviewed`, where n counts displayed files with
    at least one hunk (AC8).
  - Also expose per-file hunk ids via `HunkIds.hunkId(path, index)` and
    digests, for AC4's comparison.
- tests: JUnit table test — every AC6 case (open findings with
  resolved/dismissed not counted; all approved + fresh → `✓`; unsettled →
  `○`; `CHANGES` → `○` + tooltip; stale → not `✓`; no hunks → the no-text
  mark); AC7 (children, hidden+footer, drift hint); AC8; AC5 (untracked
  `.idea/workspace.xml` and `x.iml` hidden, tracked modified
  `.idea/codeStyles/Project.xml` shown).
  Narrow: `cd intellij-plugin && JAVA_HOME=… ./gradlew test --tests 'app.drydock.intellij.review.ReviewTabModelTest'`.
- done when: AC5, AC6, AC7, AC8 pass.
- memories: kb/find-line-key-contract, kb/find-finding-anchor-drift-gap.

### T4 Snapshot equals `review_scope` (AC4, end-to-end)
- files:
  - `ptest/app/drydock/intellij/review/ReviewTabEndToEndTest.java` (new)
- depends: T3
- change: test only. Fixture as `ReviewBackendEndToEndTest` builds it (git
  repo + linked worktree, a real `ReviewBackend` with `start()`), scope via
  `backend.scopeResolver().resolve(session)`. Compute the diff through
  `backend`'s `DiffService` with `scope.diffRoot()/diffScope()/base()/
  DiffService.REVIEW_CONTEXT_LINES` — the exact call `review_scope` makes —
  assemble `ReviewTabModel`, and fetch `review_scope` over HTTP with the
  minted token (request shape from `McpServerTest`/P1 AC9). Assert: the
  snapshot's file list equals the tool's file list minus the untracked
  `.idea/`/`*.iml` rows; per-file hunk ids (`h_<file>_<index>`) and
  `HunkDigest` values are equal.
- tests: the file above. Narrow: `cd intellij-plugin && JAVA_HOME=… ./gradlew test --tests 'app.drydock.intellij.review.ReviewTabEndToEndTest'`.
- done when: AC4 passes headless.
- memories: kb/find-p1-impl-gotchas (flakes: rerun a failing git-fixture class
  in isolation, then against a baseline, before calling it a regression),
  kb/hyp-git-diff-stays-authoritative.

### T5 `ReviewFragments` — hunk rows to `LineFragmentImpl` (AC13)
- files:
  - `glue/app/drydock/intellij/review/ReviewFragments.java` (new)
  - `ptest/app/drydock/intellij/review/ReviewFragmentsTest.java` (new)
- depends: none
- change: `static List<LineFragment> fragmentsFor(UnifiedDiff.FileDiff file,
  CharSequence leftText, CharSequence rightText)`.
  - Walk the file's hunk rows as the spike did: each contiguous run of
    ADD/DEL rows (context resets, so one git hunk can contribute several
    runs, and a run never crosses context) becomes one fragment.
  - Fragment line fields (args 1–4): the run's 0-based `[startLine, endLine)`
    per side, a deletion consuming old-side lines only, an addition new-side
    only, a replace both (the other side collapsed to the insertion point) —
    the spike's structure, kept.
  - Fragment offset fields (args 5–8): **character offsets into the passed
    texts**, computed by scanning line-start offsets (honour `\n` and
    `\r\n`): `startOffset` = offset of the run's first line, `endOffset` =
    end of the run's last line, clamped to the text length when the file has
    no trailing newline. This is spike defect 1, fixed. Inner fragments:
    empty list (Q10: none in P2).
  - Pure: no git, no I/O, no EDT.
- tests: AC13's cases — add only, delete only, replace, two runs in one hunk
  split by context, several hunks, added file (left text empty), deleted file
  (right text empty), no trailing newline at EOF, CRLF text. Assert both line
  ranges and offsets.
  Narrow: `cd intellij-plugin && JAVA_HOME=… ./gradlew test --tests 'app.drydock.intellij.review.ReviewFragmentsTest'`.
- done when: AC13 passes.
- memories: kb/find-263-diff-api-internal (`LineFragmentImpl` argument
  order), kb/find-p2-spikes-live-results (spike defect 1),
  kb/hyp-git-diff-stays-authoritative, kb/find-line-key-contract.

### T6 `ReviewDiffRequests` — the request plan (AC16)
- files:
  - `glue/app/drydock/intellij/review/ReviewDiffRequests.java` (new)
  - `ptest/app/drydock/intellij/review/ReviewDiffRequestsTest.java` (new)
- depends: none
- change:
  - Pure record `Plan(CharSequence leftText, CharSequence rightText,
    List<LineFragment> fragments, Side scrollToSide, int scrollToLine)` plus a
    `static final String REQUEST_KEY` (the Drydock request key, kept for P3's
    `DiffExtension` gate; no `DiffExtension` is registered in P2).
  - Pure builder `plan(FileDiff file, List<LineFragment> fragments)`:
    `SCROLL_TO_LINE` is `(Side.RIGHT, firstAddRow.newLine()-1)` for a file
    row; for a finding row the caller maps the line key — `n<N>` →
    `(RIGHT, N-1)`, `o<N>` → `(LEFT, N-1)` (key→position only; the
    position→key direction has no P2 caller and is not written).
  - Thin platform method `SimpleDiffRequest toRequest(Project, Disposable,
    Plan)` building the two `DocumentContent`s and putting
    `FORCE_READ_ONLY=true`, `CUSTOM_DIFF_COMPUTER` (a lambda returning the
    precomputed fragments — pure, per the Threading table), `SCROLL_TO_LINE`
    and the Drydock request key. Kept thin so the tests stay headless.
- tests: pure tests on `Plan`/builder for every AC16 case (file row,
  `n<N>`, `o<N>`); assert the key set/flags of the produced user-data on the
  pure half. Narrow: `cd intellij-plugin && JAVA_HOME=… ./gradlew test --tests 'app.drydock.intellij.review.ReviewDiffRequestsTest'`.
- done when: AC16 passes.
- memories: kb/hyp-git-diff-stays-authoritative, kb/find-line-key-contract,
  kb/find-p2-spikes-live-results (FORCE_READ_ONLY/SCROLL_TO_LINE live-verified
  on `SimpleDiffRequest`).

### T7 `ReviewContents` — left/right texts and commit resolution (AC14, AC15 detection)
- files:
  - `glue/app/drydock/intellij/review/ReviewContents.java` (new)
  - `ptest/app/drydock/intellij/review/ReviewContentsTest.java` (new)
- depends: T1
- change: constructed with a `GitExecutableLocator`-equivalent through
  `ProcessRunner` usage identical to `GitStatusService`'s (locator + timeout +
  `--end-of-options`); all methods are blocking background calls, documented
  "never on the EDT, never in the tree builder".
  - `String leftText(ReviewScope scope, UnifiedDiff.FileDiff file)`: the left
    revision is the merge base of `scope.base()` and HEAD for
    `BRANCH_WORKING_TREE` and `BASE` (`git merge-base --end-of-options <base>
    HEAD` — the commit `DiffService` measures from), and `HEAD` for
    `WORKING_TREE`. Read with `git show --end-of-options <rev>:<path>`. This
    is spike defect 2, fixed. A file with `oldPath()` present (T1 seam) is
    read at the pre-image path (Q6). An added file (kind `A`) has no left
    side → empty, no git call.
  - `String rightText(ReviewScope scope, UnifiedDiff.FileDiff file)`: the
    worktree file (`WORKING_TREE`, `BRANCH_WORKING_TREE`) or
    `git show HEAD:<path>` (`BASE`); empty for a deleted file (kind `D`).
    Deleted files are review material with an empty right side — spike
    defect 3, fixed.
  - `Optional<String> baseCommit(ReviewScope scope)` and
    `Optional<String> headCommit(ReviewScope scope)`: `GitStatusService
    .commitForRefBlocking(diffRoot, …)` for `scope.base()` and
    `SessionReviewScopes.headRevision(scope.head())` — resolved commit ids
    only, never ref names.
  - Pure `static boolean matchesRows(String text, UnifiedDiff.FileDiff file,
    boolean rightSide)`: the hunk rows' highest old/new line number must not
    exceed the text's line count (AC15's detection half).
  - Failures log WARNING with a stderr excerpt and return empty — never a
    fabricated success.
- tests: git fixtures — `main` advanced past the fork changing the same file:
  left text equals `git show <merge-base>:<path>` (AC14); rename read at the
  pre-image path (AC14); WORKING_TREE/BASE sides; added/deleted; `matchesRows`
  true and false; commit resolution gives 40-hex or empty.
  Narrow: `cd intellij-plugin && JAVA_HOME=… ./gradlew test --tests 'app.drydock.intellij.review.ReviewContentsTest'`.
- done when: AC14 passes; AC15's detection half passes.
- memories: kb/find-p2-spikes-live-results (spike defects 2–3, added/deleted
  gotchas), kb/find-p1-impl-gotchas (ProcessRunner, `--end-of-options`,
  real paths), kb/hyp-git-diff-stays-authoritative, kb/find-line-key-contract.

### T8 `ReviewTabScopes` — the tab's scope source (AC19)
- files:
  - `glue/app/drydock/intellij/review/ReviewTabScopes.java` (new)
  - `glue/app/drydock/intellij/review/ReviewBackend.java` (add two
    package-private accessors: `ReviewScopeRegistry scopeRegistry()`,
    `DiffService diffService()` — same package, test-only today, wired by T13)
  - `ptest/app/drydock/intellij/review/ReviewTabScopesTest.java` (new)
- depends: none
- change: constructor `(WorktreeService worktrees, GitStatusService git,
  ReviewScopeRegistry registry, Supplier<List<ManagedAgentSession>> sessions)`.
  `CompletableFuture<ReviewScope> resolve(Path checkout,
  Optional<ManagedAgentSession> linkSession)`:
  1. `worktrees.list(checkout)`; `main` = first `mainCheckout` entry, `own`
     found by **real-path** compare with prunable entries skipped (port
     `SessionReviewScopeResolver.ownEntry`; do NOT call `resolve` — it
     rebinds). Real paths compared (macOS `/var` vs `/private/var`),
     `kb/find-p1-impl-gotchas`.
  2. Kind `WORKING_TREE` when `own` is the main checkout, else `WORKTREE`;
     PR number via `PrCheckoutService.pullRequestNumberOf(own.branch())`.
  3. Scan `registry.scopes()` for the same **identity** (kind, normalized
     `repoRoot` = main's path, normalized `worktree` = own's path, PR number
     — the fields of `ReviewScopeRegistry.Identity`). Found → return it
     **unchanged**: never call `mint`, because `mint` updates an existing
     handle in place, session binding and base/head included, and would take
     the scope away from a running agent (`isAddressableBy` would then refuse
     its `review_*` calls).
  4. Absent → `new SessionReviewScopes(git, registry).forCheckout(main.path(),
     own.path(), own.branch(), linkSession, Optional.empty())` then
     `Scopes::local`. `linkSession` is the session whose Changes link opened
     the tab; empty when opened directly (Q7). Never touch an existing
     binding. Javadoc states the accepted race: between 3 and 4 a concurrent
     mint could rebind; the Changes-link path is P2's only minter.
- tests: a scope bound to session A stays bound to A after `resolve` from a
  window whose link named session B (`isAddressableBy(A)` true, `(B)` false);
  an unbound scope + link session B becomes bound to B only; unbound + no
  link stays unbound; an existing scope is returned unchanged (same id,
  same binding); real-path matching; not-git completes exceptionally.
  Narrow: `cd intellij-plugin && JAVA_HOME=… ./gradlew test --tests 'app.drydock.intellij.review.ReviewTabScopesTest'`.
- done when: AC19 passes.
- memories: kb/find-p1-impl-gotchas (real paths, prunable entries),
  kb/find-review-tab-host-varies (why the tab owns its scope surface).

### T9 `ReviewVerdictCommands` — mark reviewed / unmark (AC9–AC12)
- files:
  - `glue/app/drydock/intellij/review/ReviewVerdictCommands.java` (new)
  - `ptest/app/drydock/intellij/review/ReviewVerdictCommandsTest.java` (new)
  - `ptest/app/drydock/intellij/review/ReviewBackendEndToEndTest.java` (add
    the AC10 case)
- depends: none
- change: constructor `(DiffService diffService, GitStatusService git,
  AnnotationStore store)` — all instances owned by `ReviewBackend`, injected
  by T13; the store is the backend's own (one writer; never constructed
  here). All work on a background executor (the caller's), never the EDT.
  - `CompletableFuture<Result> markReviewed(ReviewScope scope,
    UnifiedDiff.FileDiff file)` where `Result` is `ok` / `refused(String)`:
    1. Re-run the diff through `diffService.diff(scope.diffRoot(),
       scope.diffScope(), scope.base(), DiffService.REVIEW_CONTEXT_LINES)`
       and take the file's hunks from **that** diff — the command recomputes
       digests from a fresh diff before writing.
    2. `base = commitForRefBlocking(diffRoot, scope.base())`, `head =
       commitForRefBlocking(diffRoot, SessionReviewScopes.headRevision(scope.head()))`.
       Either empty → refused with a reason naming which commit could not be
       resolved (Q5; never stamp an unresolved sentinel — it is not a
       revision `review_recheck` can use).
    3. If any open finding of the scope on this file `blocksApproval()`
       (`ReviewAnnotation.blocksApproval`), refuse with its own reason.
    4. A file with no hunks is refused ("no text changes to review").
    5. Otherwise write one `new ReviewVerdict(scopeId, HunkDigest.of(path,
       hunk), APPROVED, Optional.empty(), Instant.now(), base, head)` per
       **distinct** digest via `store.putVerdict`.
  - `CompletableFuture<Result> unmark(ReviewScope scope, FileDiff file)`:
    for the file's current distinct digests, `clearVerdict(scopeId, digest)`
    where the stored verdict's decision is `APPROVED` (Q14: only human
    approvals; `AUTO_APPROVED` and `CHANGES` stay; other files untouched).
- tests: AC9 (one verdict per distinct digest; base/head are 40-hex and equal
  `git rev-parse` of the scope's base and head; after
  `flushPendingSaves()` the verdicts are in `annotations.json`); AC11 (both
  refusal cases, each with its own reason, nothing written); AC12. AC10 in
  `ReviewBackendEndToEndTest`: after marking through the commands, an agent's
  `review_recheck` on the file's hunk id finds the verdict, where before it
  refused with "no verdict".
  Narrow: `cd intellij-plugin && JAVA_HOME=… ./gradlew test --tests 'app.drydock.intellij.review.ReviewVerdictCommandsTest' --tests 'app.drydock.intellij.review.ReviewBackendEndToEndTest'`.
- done when: AC9, AC10, AC11, AC12 pass.
- memories: kb/find-line-key-contract (digests; verdicts dated by commits),
  kb/find-p1-impl-gotchas (JAVA_HOME, E2E request shape, flake rule).

### T10 `ReviewDiffPreview` — placement and hosting (AC17)
- files:
  - `glue/app/drydock/intellij/review/ReviewDiffPreview.java` (new)
  - `ptest/app/drydock/intellij/review/ReviewDiffPreviewTest.java` (new)
- depends: T6
- change:
  - Pure `static boolean useSplitter(boolean contentVertical, boolean
    commitToolWindowEnabled)` mirroring the platform rule — splitter when the
    content is horizontal or the Commit tool window is not enabled. The
    production call goes through
    `ChangesViewContentManager.shouldHaveSplitterDiffPreview(project,
    isContentVertical)` (public static, 263 bytecode).
  - `interface Host { void show(SimpleDiffRequest request); void dispose(); }`
    with two implementations behind one factory: the splitter branch embeds
    the preview in the tab (`PreviewDiffSplitterComponent` pattern); the
    editor-tab branch keeps **one reused** preview that re-targets on every
    selection (`TreeHandlerEditorDiffPreview` pattern). Both are fed T6's
    requests, which carry `CUSTOM_DIFF_COMPUTER`, `FORCE_READ_ONLY`,
    `SCROLL_TO_LINE` and the Drydock request key.
  - Encoded fallback (spec "What is not verified"): if `verifyPlugin` flags
    internal-API usage on the processor stack
    (`TreeHandlerDiffRequestProcessor` ← `ChangeViewDiffRequestProcessor`), or
    AC24 later shows it drops the request keys, replace both hosts with
    `DiffManager.createRequestPanel(project, disposable, window)` panels —
    splitter case in the tab, editor-tab case in one reused editor. The
    placement rule and this interface stay; only the host changes. Record the
    outcome in the investigation.
  - `show` must be called on the EDT (the caller hops); no
    `DiffManager.showDiff` anywhere in the tab (AC17's grep gate).
- tests: the placement decision matrix; a fake `Host` records `show()` order
  as selections change. Narrow: `cd intellij-plugin && JAVA_HOME=… ./gradlew test --tests 'app.drydock.intellij.review.ReviewDiffPreviewTest'`.
- done when: AC17's unit half passes (grep gate in Verification; live half is
  AC24).
- memories: kb/find-263-diff-api-internal (compile-only diff modules; the
  internal-visibility risk this task's fallback exists for),
  kb/find-p2-spikes-live-results (showDiff UX finding; keys verified on
  `SimpleDiffRequest`).

### T11 `ReviewTabRefresher` + `ReviewTabStatus` (AC21, AC15 scheduling, AC22)
- files:
  - `glue/app/drydock/intellij/review/ReviewTabRefresher.java` (new)
  - `glue/app/drydock/intellij/review/ReviewTabStatus.java` (new)
  - `ptest/app/drydock/intellij/review/ReviewTabRefresherTest.java` (new)
  - `ptest/app/drydock/intellij/review/ReviewTabStatusTest.java` (new)
- depends: T3
- change:
  - `ReviewTabRefresher`: constructor takes an `Executor background` (the
    plugin executor in production), a `Scheduler` seam (`Cancelable
    schedule(Runnable, long delayMs)`; fake in tests), a diff supplier
    `Function<ReviewScope, CompletableFuture<UnifiedDiff>>` (the backend's
    `DiffService`), and the assembler `ReviewTabModel::snapshot`.
    - `marksChanged()`: the store/registry listener path — coalesces to one
      recompute from the **cached** diff; spawns no git. (`putVerdict` fires
      with a `null` key, so the listener cannot filter by key and must
      coalesce — `AnnotationStore.putVerdict`.)
    - `reload(ReviewScope)`: full diff re-run — debounced (~300 ms), at most
      one diff in flight per scope; a request during a run schedules exactly
      one follow-up (AC15's scheduling half, AC21).
    - Each assembled `Snapshot` goes to a `Consumer<Snapshot>`; the provider
      (T13) hops to the EDT. The first user-triggered load shows the loading
      state via a callback before any background work starts.
    - `dispose()`: unregisters listeners (the provider registers
      `AnnotationStore.addChangeListener` / `ReviewScopeRegistry
      .addChangeListener` and hands the returned runnables here) and cancels
      scheduled work; later events do nothing.
  - `ReviewTabStatus`: pure enum + message text for AC22 — LOADING,
    BACKEND_NOT_READY, NO_SESSION, NOT_GIT, DIFF_FAILED (message from the
    cause; the stderr excerpt reaches the log at WARNING in T7),
    EMPTY_DIFF, READY. LOADING is the placeholder every terminal state
    replaces (nothing stays stranded).
- tests: 50 store events → exactly one mark recompute and zero diff calls
  (counting seams); a file-change burst during a run → at most one diff in
  flight and exactly one follow-up; `dispose()` → no further work; status
  mapping for every AC22 state.
  Narrow: `cd intellij-plugin && JAVA_HOME=… ./gradlew test --tests 'app.drydock.intellij.review.ReviewTabRefresherTest' --tests 'app.drydock.intellij.review.ReviewTabStatusTest'`.
- done when: AC21 passes; AC15's scheduling half and AC22's model half pass.
- memories: kb/find-p1-impl-gotchas (flake rule for scheduler tests), plus
  the repository rules on coalescing and progress clearing.

### T12 `ReviewTabBrowser` — the tree (AC7 rendering data)
- files:
  - `glue/app/drydock/intellij/review/ReviewTabBrowser.java` (new; node
    classes nested)
  - `ptest/app/drydock/intellij/review/ReviewTabBrowserTest.java` (new)
- depends: T3
- change: `AsyncChangesBrowserBase(project, showCheckboxes=false,
  highlightProblems=false)` subclass.
  - The constructor calls `init()` immediately after `super` (the base
    constructor does not call it) — spike gotcha.
  - `getChangesTreeModel()` returns
    `SimpleAsyncChangesTreeModel.create(grouping -> buildTree(grouping))`
    where `buildTree` reads **only the latest `Snapshot`** handed by
    `accept(Snapshot)` — no git, no I/O, no store lock held. After swapping
    the model it calls `getViewer().rebuildTree()` itself; without that call
    the tab stays empty with no error anywhere — spike gotcha
    (`kb/find-p2-spikes-live-results`).
  - File rows: a `ChangesBrowserNode<T>` subclass rendering path + mark
    (`✓` / `◆ n` / `○` / "no text changes to review") with the tooltips from
    the model (CHANGES, stale, drift); finding child rows with severity,
    title, `:<line>` and the drift hint. Own grouping; never a
    `changesGroupingPolicy` contribution (`kb/dead-grouping-policy-ep-for-sections`).
  - Selection listener notifies a `Consumer<Object>` (file row or finding
    row) that T13 wires to the preview; a package-private pure helper
    (`nodeRows(Snapshot)`) exposes the display data for tests without an EDT.
- tests: model→node assembly for marks, children, hidden findings/footer,
  drift hint; `accept` then rebuild ordering.
  Narrow: `cd intellij-plugin && JAVA_HOME=… ./gradlew test --tests 'app.drydock.intellij.review.ReviewTabBrowserTest'`.
- done when: AC7's rendering-data half passes (the model half is T3).
- memories: kb/find-p2-spikes-live-results (`rebuildTree` + `init()`
  gotchas), kb/find-263-diff-api-internal (module visibility of the
  Changes-browser family), kb/dead-grouping-policy-ep-for-sections,
  kb/find-review-tab-public-api.

### T13 `ReviewTabProvider` + visibility predicate + registration (AC1, AC2 static, AC22 wiring)
- files:
  - `glue/app/drydock/intellij/review/ReviewTabProvider.java` (new)
  - `glue/app/drydock/intellij/review/ReviewTabVisibility.java` (new)
  - `glue/platform/resources/META-INF/plugin.xml` (add the tab)
  - `ptest/app/drydock/intellij/review/ReviewTabPluginXmlTest.java` (new)
- depends: T7, T8, T9, T10, T11, T12
- change:
  - plugin.xml: inside `<extensions>`,
    `<changesViewContent tabName="Drydock Review"
    className="app.drydock.intellij.review.ReviewTabProvider"
    predicateClassName="app.drydock.intellij.review.ReviewTabVisibility"
    isInCommitToolWindow="true"/>`. Declare **no** `<module>` for
    `intellij.platform.diff` / `intellij.platform.diff.impl` (AC2; they stay
    `bundledModule(...)` compile-only, resolved at runtime through
    `intellij.platform.vcs.impl`).
  - `ReviewTabProvider implements ChangesViewContentProvider`, constructor
    `(Project)` (the platform injects it — live-verified in the spike).
    `initContent` assembles: the header label (`<k> / <n> reviewed`), the
    browser (T12), the preview host (T10, placed per
    `shouldHaveSplitterDiffPreview`), and the status placeholder (T11). It
    wires: scope from `ReviewTabScopes` (T8) with `linkSession` empty for a
    direct open; selection → T7 contents on background → T6 request →
    `preview.show` on the EDT; toolbar, row context menu and the preview's
    `DiffUserDataKeysEx.CONTEXT_ACTIONS` all run T9's commands (Q13: one
    command, three entry points), refusal reasons shown via the plugin's
    shared error util (`UiErrors`), never a per-view copy. `disposeContent`
    disposes the preview and the refresher (unregistering both listeners).
    Every load/state path clears the placeholder (AC22).
  - `ReviewTabVisibility implements java.util.function.Predicate<Project>`
    (verified: `ChangesViewContentEP.newPredicateInstance` constructs it with
    the Project and requires `Predicate<Project>`). Returns true when the
    plugin service is initialized and any in-memory session's working
    directory equals the project base path (real-path compare). In-memory
    only — no file reads on the call path (Q8; if AC24 shows the predicate is
    not re-evaluated, drop it and rely on the empty state — record that in
    the investigation).
- tests: `ReviewTabPluginXmlTest` reads the plugin descriptor resource from
  the test classpath and asserts: exactly one `changesViewContent` tab named
  "Drydock Review" with `isInCommitToolWindow="true"`, and no `<module>`
  element naming either diff module (AC1's registration half; AC2's static
  half; the spike-absence half is T16).
  Narrow: `cd intellij-plugin && JAVA_HOME=… ./gradlew test --tests 'app.drydock.intellij.review.ReviewTabPluginXmlTest'`.
- done when: AC1's registration half and AC2's static half pass; AC22's
  wiring is in place.
- memories: kb/find-review-tab-host-varies (host varies; resolve, never
  hard-code), kb/find-263-diff-api-internal (no module declarations),
  kb/find-p2-spikes-live-results, kb/q-split-mode-remote-dev (out of scope —
  do not special-case it).

### T14 The Changes link opens the Review tab (AC3, AC18)
- files:
  - `glue/app/drydock/intellij/review/ReviewTabOpener.java` (new)
  - `ptest/app/drydock/intellij/review/ReviewTabOpenerTest.java` (new)
  - `glue/app/drydock/intellij/agent/AgentConsoles.java` (call site
    `AgentConsoles.java:328-333`: `TaskChanges.show(target, session)` →
    `ReviewTabOpener.open(target, Optional.of(session))`)
  - `glue/app/drydock/intellij/agent/AgentConsoleEditor.java` (call site
    `AgentConsoleEditor.java:540-546`, the `SHOW_CHANGES` non-hosted branch:
    same replacement)
- depends: T13
- change: `ReviewTabOpener.open(Project project, Optional<ManagedAgentSession>
  linkSession)`: trigger the loading placeholder first, then resolve the host
  with `ChangesViewContentManager.getToolWindowIdFor(project, TAB_NAME)`
  ("Drydock Review" — Q15), activate that tool window and
  `selectContent(TAB_NAME)`. Never name `ToolWindowId.COMMIT` (AC3;
  `kb/find-review-tab-host-varies`). Test seam: a package-private `Host`
  interface (`toolWindowIdFor`, `activate`, `selectContent`) with the static
  production impl; the scope it mints is bound to `linkSession` (T8, Q7).
  Both strip paths — in-window and the hosted jump — open the tab in the
  session's own window (AC18).
- tests: with fakes, the opener resolves via the seam, activates, selects,
  and the placeholder hook runs before the first platform call; a test
  asserts the open paths carry the link session through.
  Narrow: `cd intellij-plugin && JAVA_HOME=… ./gradlew test --tests 'app.drydock.intellij.review.ReviewTabOpenerTest'`.
- done when: AC3 and AC18 pass (the "Neither path calls TaskChanges.show"
  half is compile-enforced by T15; a grep gate checks it at T14 already).
- memories: kb/find-review-tab-host-varies.

### T15 Retire the Drydock Changes tool window (Q1 — DECIDED)
- files:
  - `glue/platform/resources/META-INF/plugin.xml` (remove the `Drydock
    Changes` toolWindow registration and its comment, plugin.xml:96-102)
  - DELETE `glue/app/drydock/intellij/agent/TaskChanges.java`
  - DELETE `glue/app/drydock/intellij/agent/TaskChangesPanel.java`
  - DELETE `glue/app/drydock/intellij/agent/TaskChangesToolWindowFactory.java`
  - DELETE `ptest/app/drydock/intellij/agent/TaskChangesTest.java`
  - DELETE `ptest/app/drydock/intellij/agent/TaskChangesToolWindowFactoryTest.java`
- depends: T14
- change: deletion and the plugin.xml entry removal, nothing else. `TaskChanges`
  lost its only callers in T14, so the deletion compiles clean. The
  "Uncommitted vs HEAD" view is dropped by decision (Q1). *Bound note:* six
  owned paths, five of them pure deletions with zero new lines — the
  task-instruction-mandated composition (deletion + test cleanup +
  plugin.xml removal in one task); the ≤5-file bound is met in spirit since
  no behaviour is written.
- done when: the full gate passes; `grep -rn "TaskChanges"
  intellij-plugin/src` prints nothing; AC1's TaskChanges half holds.
- memories: kb/dead-stock-changes-view-branch-scope (the stock view was
  already refuted; this window's IDE-comparison hunks violate the line-key
  contract — deleted, not migrated).

### T16 Delete the spike (AC1's spike half; last task)
- files:
  - `glue/platform/resources/META-INF/plugin.xml` (remove the three spike
    entries and their comments: the `changesViewContent` "Drydock Review
    (Spike)", the `diff.DiffExtension`, the `Drydock.SpikeReviewDiff` action —
    plugin.xml:112-121 and 165-172)
  - DELETE `glue/app/drydock/intellij/review/spike/SpikeReviewTabProvider.java`
  - DELETE `glue/app/drydock/intellij/review/spike/SpikeReviewSupport.java`
  - DELETE `glue/app/drydock/intellij/review/spike/SpikeReviewDiffAction.java`
  - DELETE `glue/app/drydock/intellij/review/spike/SpikeReviewDiffExtension.java`
- depends: T15
- change: delete the package and its plugin.xml entries; reverts nothing
  else — the real tab (T13) stays. P2 adds no class without a production
  caller: no `DiffExtension`, no position-to-key mapper.
- done when: `grep -rni "spike" intellij-plugin/src` prints nothing; the
  AC1 plugin-xml test (T13) still passes and now sees a clean descriptor;
  the full gate passes.
- memories: kb/find-p2-spikes-live-results, kb/find-review-tab-diff-spike-code
  (findings recorded in the investigation; code throwaway).

## Waves

- W1: T1, T3, T5, T6, T8 — disjoint files, no deps
- W2: T2 (T1), T4 (T3), T7 (T1), T9, T11 (T3)
- W3: T10 (T6), T12 (T3)
- W4: T13 (T7, T8, T9, T10, T11, T12)
- W5: T14 (T13)
- W6: T15 (T14)
- W7: T16 (T15)

plugin.xml is owned by T13, then T15, then T16 — strictly sequential waves,
never shared. **Coders in a parallel wave must not run Gradle**: the root
build and the `intellij-plugin` build share no build directory, but each
tree's daemon and build directory cannot take two concurrent runs. After each
wave the coordinator runs that wave's narrow test commands one after another,
exactly as listed per task; W1–W2 include the root `./gradlew :app:test`
runs for T1/T2.

## Verification

The coordinator runs these after W7 (all Gradle commands with
`JAVA_HOME=~/.sdkman/candidates/java/25.0.2-tem`):

1. **Full gate:** `cd intellij-plugin && JAVA_HOME=… ./gradlew test buildPlugin verifyPlugin`.
   `verifyPlugin` reports Compatible on IU-263 with no internal API usage
   (AC2's dynamic half; `kb/find-p1-impl-gotchas`).
2. **Core byte-identity** (AC23; T1/T2 touched the shared core). All three
   must print nothing:
   `diff app/src/main/java/app/drydock/git/UnifiedDiff.java intellij-plugin/src/main/java/app/drydock/git/UnifiedDiff.java`
   `diff app/src/main/java/app/drydock/git/DiffService.java intellij-plugin/src/main/java/app/drydock/git/DiffService.java`
   `diff app/src/test/java/app/drydock/git/DiffServiceTest.java intellij-plugin/src/test/java/app/drydock/git/DiffServiceTest.java`
3. **Core compiles without the platform classpath** (AC23):
   `cd intellij-plugin && JAVA_HOME=… ./gradlew compileCoreJava`.
4. **App side of the core seam** (T1/T2 changed app code):
   `JAVA_HOME=… ./gradlew :app:test --tests 'app.drydock.git.*'` from the
   repository root. If `processResources`/`buildHighlighterBundle` or the
   uninitialized `third_party/ghostty` submodule blocks it, report it as
   environmental; do not skip silently.
5. **Grep gates** (run on the committed diff, or `git diff` for the working
   tree):
   - AC3: `grep -rn "ToolWindowId.COMMIT" intellij-plugin/src/platform` prints
     nothing.
   - AC17: `grep -rn "DiffManager.showDiff\|showDiffForChange"
     intellij-plugin/src/platform/java/app/drydock/intellij/review` prints
     nothing.
   - AC1: `grep -rni "spike\|TaskChanges" intellij-plugin/src` prints nothing.
   - Repository rules: `git diff -- intellij-plugin/src/platform | grep -n
     ProcessBuilder` prints nothing (git goes through `ProcessRunner` or the
     existing git services); the P1 FQN check
     (`git diff -U0 -- intellij-plugin/src/platform intellij-plugin/src/test |
     grep -E '^\+' | grep -vE '^\+\s*(import|package) ' | grep -E
     '\b(java|javax|com|org|app)\.[a-z]+\.[a-zA-Z.]*[A-Z][A-Za-z]+'`)
     prints nothing.
6. **AC24 — manual sandbox checklist** (`runIde`, recorded in the
   investigation; not automated):
   (a) tab in the Commit window; with it disabled, in the Version Control
   window; the Changes link reaches it in both; (b) vertical Commit window →
   editor-tab preview, horizontal → splitter; (c) the preview shows exactly
   the git hunks in two-side and unified viewers, read-only (this also
   settles whether the processor keeps `CUSTOM_DIFF_COMPUTER`/
   `FORCE_READ_ONLY` — the T10 fallback triggers if not); (d) a finding row
   scrolls to its line; (e) mark reviewed survives an IDE restart (same
   scope id); (f) an agent's `review_finding` makes its `◆` appear without a
   manual refresh (Q8's predicate re-evaluation is observed here too).
7. **Flake rule** (`kb/find-p1-impl-gotchas`): a failing test class reruns in
   isolation, then against a clean baseline worktree, before anyone calls it
   a regression. Known pre-existing flakes: `AgentExecutionRegistryPlatformTest
   .testAReleasedAgentKeepsRunningAndIsNotShown` (1/8 baseline), app
   `WorkspaceMcpSessionContextTest.adoptingARemoteOnlyBranch…`.

NOT VERIFIED by this plan (needs AC24's live run): that the preview processor
stack keeps the request user data, that the editor-tab preview opens in the
default layout, that the `changesViewContent` visibility predicate is
re-evaluated (Q8), and whether empty-vs-null inner fragments renders better
(Q10).
