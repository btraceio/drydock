# Spec: githubremotedetectable-caches-a-transient

Address run `2026-10-29T00:00:00Z-srs_VsFfyenIcpRp5aexMWvHqmPa`. Design: `.sphinx/address/design.md`, converged in design-adversary round 1 with no must-fix items.

All paths are relative to `intellij-plugin/src/platform/java/app/drydock/intellij/tasks/` unless stated otherwise.

## Problem

There are six review findings, in four clusters, plus one sibling regression.

- **A. Origin-remote detection (F-7177002fbec9, F-7bdeac04e2c3).** `TaskTreeInputs.githubRemoteDetectable` runs `git remote get-url origin` inside `remoteCache.computeIfAbsent`. Every failure stores `false`: git timeout, IOException, interrupt, and a missing git executable. So a transient failure pins the "GitHub not configured · Set up" ghost until `invalidateGitHub()` runs. Also, `gitLocator.locate()` runs inside the per-root mapping lambda, once per project instead of once per pass.
- **B. Scope gate for lazy listings (F-b36199a5527a, plus a sibling).** The new `githubIssuesWanted(requested, root)` gate dropped the explicit `/<glob>/` project-scope check that the old `githubListingWanted` applied. A project hidden by an explicit scope still fetches its GitHub issue listing. The PR gate kept this check. The same regression hit Jira group listings: the base revision gated `jira.issuesOf` with the same view/scope rule, and the current gather fetches every opened group regardless of scope.
- **C1. Groupless Jira husk (F-237033e408e8).** `TaskTreeModel.jiraNodeChildren` omits an empty Jira node only when `!nodeMatches && !groups.isEmpty()`. So a project with no groups shows a "No Jira groups" husk under a search that does not name Jira. That contradicts the method javadoc.
- **C2. Untyped model/renderer string protocol (F-70d91b9546c9).** The model emits the literal `"No issues"` at two sites. `TaskTreeRenderer` keeps a private duplicate `NO_ISSUES` and uses it to decide whether a listing is answered. Rewording either copy silently breaks the count badges.
- **D. Over-broad refresh API (F-ccbe76478263).** `ListingExpansionTracker.requested(kind)` returns an immutable `Set.of()` for every kind other than WORKTREES and PULL_REQUESTS. `RefreshTarget.requestListing(SectionKind, Path)` still accepts any kind, and its implementation calls `.add` on that set. Passing such a kind throws `UnsupportedOperationException` at runtime instead of failing at compile time.

## Correct behaviour

### A — origin-remote detection
- **R-A1 (definitive-only caching).** `remoteCache` holds an entry for a root only after git ran to completion and answered.
  - It stores TRUE when git exits 0 with non-blank stdout.
  - It stores FALSE on any non-zero exit (for example 2 for "No such remote", 128 for "not a repository" or "dubious ownership") or on exit 0 with blank stdout.
  - A timeout (`ProcessTimeoutException`), `IOException`, `InterruptedException`, or an absent git executable never creates an entry.
- **R-A2 (per-pass fallback).** An inconclusive outcome means "not detectable" for the current gather only. The root goes into `githubGhostRoots` for that pass, and the next gather asks git again. An interrupt restores the thread's interrupt flag.
- **R-A3 (locator once per pass).** `gitLocator.locate()` is called exactly once per `gather` call, before the project loop. It is never called per root or inside the cache-mapping lambda.
- **R-A4 (explicit setting short-circuit).** A project whose GitHub settings name a repository never spawns git.
- **R-A5.** `invalidateGitHub()` still clears `remoteCache` wholesale. A project with GitHub issues enabled lands in `githubIssueRoots` (detectable or explicit repository) or in `githubGhostRoots` (otherwise), never both.

### B — hidden row ⇒ no fetch
- **R-B1.** If an explicit `/<glob>/` scope does not match the project's display name, it suppresses that project's GitHub issue fetch and its Jira group fetches, even when the node or group was opened. This is the same rule `pullRequestsWanted` applies.
- **R-B2.** A scope never adds a fetch. A root or group the user never opened is never fetched.
- **R-B3.** The scope gates fetching only:
  - Membership in `githubIssueRoots` and `githubGhostRoots` is still decided by settings plus remote detection.
  - `jiraRoots` membership and the Jira group definitions (`jiraGroupRequests`) still travel for a scoped-out project.
- **R-B4.** Ordinary unscoped text (`ProjectScope.NONE`) and the empty scope `//` never suppress a fetch.

### C — placeholders and filtering
- **R-C1 (no husk under search).** Under a non-empty item filter that does not contain "Jira", the model never emits a Jira node whose only child is a placeholder. This holds whether every group was filtered out or none are defined.
- **R-C2 (empty item filter keeps the node).** When the item filter is empty, a groupless Jira project still shows its Jira node with the "No Jira groups" placeholder. The item filter is empty for a filterless view, for an unscoped search that name-matches the project, and for an explicit scope with no item part (`/glob/`). The same holds when the filter contains "jira".
  - An explicit scope that has an item part (`/glob/foo`) keeps `foo` as the item filter even when the project matched. Under such a scope a groupless Jira node is omitted.
- **R-C3 (single source for answered-empty text).** Every "listing answered with nothing" placeholder the model emits uses `TaskTreeModel.NO_ISSUES`. That covers the GitHub node and the Jira group. `TaskTreeRenderer` recognizes exactly that constant: `answeredListing`, and through it `issueCountBadge`, `githubIssueCountBadge`, and `sectionCountBadge`. An answered empty listing badges "0 issues".

### D — refresh-listing API
- **R-D1 (compile-time narrowing).** `TaskTreePanel.RefreshTarget` exposes no kind-generic listing request. `requestPullRequests(Path root)` is the only by-kind listing request. The PULL_REQUESTS refresh dispatch records the same intent as before.
- **R-D2 (loud failure).** `ListingExpansionTracker.requested(kind)` returns the live, mutable set for WORKTREES and PULL_REQUESTS. For any other kind it throws `IllegalArgumentException`, never a silent empty set.
- **R-D3 (EDT guard preserved).** Expanding a SESSIONS, ACTIVE, INACTIVE, or ISSUES section through the tracker's `treeWillExpand` never throws. Only WORKTREES and PULL_REQUESTS sections reach `requested()`.

## Constraints
- Package-private visibility conventions:
  - `NOT_LOADED` is package-private, so `NO_ISSUES` is too.
  - The pure gates in `TaskTreeInputs` are `static` and package-private, so `TaskTreeInputsTest` can test them without a plugin service.
- No async remote detection and no TTL on `remoteCache`. The reviewer instruction on F-7bdeac04e2c3 asks only for no-caching of inconclusive outcomes plus hoisting the locator.
- Keep the existing 2-arg `githubIssuesWanted` and both `pullRequestsWanted` overloads; existing tests call them.
- The JUnit 5 test suite under `intellij-plugin/src/test/java` (Gradle `:intellij-plugin:test`). Javadoc-heavy style: every new method or constant gets a short javadoc in the file's voice.
- `TaskTreeModel` stays I/O-free.

## Scope

### Primary fixes
- `TaskTreeInputs.java:420` (F-7177002fbec9). Defect class: transient failure cached as a definitive negative. Fix: cache only git's definitive answers; return `false` uncached for timeout, IOException, interrupt, and missing git.
- `TaskTreeInputs.java:426` (F-7bdeac04e2c3). Defect class: repeated per-root executable lookup, plus the same caching defect. Fix: hoist `gitLocator.locate()` to once per `gather`, and pass the `Optional<Path>` in. Async detection and a TTL are out of scope.
- `TaskTreeInputs.java:133` (F-b36199a5527a). Defect class: lost scope gate, so hidden rows still fetch. Fix: add a 4-arg `githubIssuesWanted(requested, root, scope, displayName)` and call it from `gather`.
- `TaskTreeModel.java:574` (F-237033e408e8). Defect class: guard narrower than its documented rule. Fix: `if (!nodeMatches)`.
- `TaskTreeModel.java:558` (F-70d91b9546c9). Defect class: untyped string protocol between model and renderer. Fix: add package-private `TaskTreeModel.NO_ISSUES` and use it at both emission sites (L558, L620) and in the renderer. Delete the renderer's private copy.
- `ListingExpansionTracker.java:87` (F-ccbe76478263). Defect class: an interface advertises a broader contract than its implementation honors. Fix:
  - Replace `RefreshTarget.requestListing(SectionKind, Path)` with `requestPullRequests(Path)`, in the interface, the panel implementation, the caller at L797, and both test fakes.
  - Make `requested()` throw `IllegalArgumentException` for kinds it does not track.

### Auto-expanded sibling fixes
- **`TaskTreeInputs.java:230-236`.** The Jira group fetch in `gather` has the same lost scope gate as F-b36199a5527a.
  - Evidence: at base `d18ce65a`, `TaskTreeInputs.java:201-209` gated `jira.issuesOf` with `githubListingWanted(..., scope, project.displayName())`, under the comment "Each group's listing follows the same view/scope rule as the GitHub one". The current loop checks only `requestedJiraGroups.contains(new GroupKey(root, group.id()))`.
  - Reasoning:
    - FLOW: `TaskTreePanel.scheduleRebuild` → `inputs.gather(..., requestedJiraGroups, ...)` → per-project loop → `jiraSettings.enabled()` → for each group → `jira.issuesOf(root, jiraSettings, group)`.
    - PRECONDITION: the user opened a Jira group of project P in this panel, then typed an explicit `/glob/` scope that excludes P, and P's cached listing has expired or was invalidated.
    - REACHABLE: yes. `requestedJiraGroups` is never scope-pruned.
    - CONCLUSION: LOW. It costs a wasted Jira REST fetch per hidden opened group, the same regression as the GitHub issues finding.
  - Fix: add a pure `jiraGroupWanted(requested, key, scope, displayName)` gate and use it in the loop. The group definitions still travel.

## Assumptions
- At ≥90% confidence: the inconclusive branches of remote detection get a test seam. A package-private static helper takes the cache, the root, the hoisted `Optional<Path>`, and an origin-probe function that may throw. This makes R-A1 and R-A2 unit-testable without a plugin service or a real git process. Behavior is identical to the design's method body.
- At ≥90% confidence: any non-zero git exit is a definitive "no origin" and is cached (design assumption 2).
- At ≥90% confidence: R-A3 (locator once per pass) and R-A4 (explicit-setting short-circuit) are verified by code review and a grep. Testing them needs `DrydockPluginService` (`gather`), which the plain JUnit suite cannot construct.
- At ≥90% confidence: `worktreesOf` (`TaskTreeInputs.java:480-510`) also caches `List.of()` on a timeout. It is not auto-expanded, because:
  - It has a different, documented cache contract (class javadoc L59-61: cached until `invalidateWorktrees()`, which session and worktree events trigger often).
  - Not caching its timeouts would re-spawn `git worktree list` (15 s bound) on every activity-driven gather, which is exactly the cadence that cache protects.
  - A correct fix needs a retry-after design. It is recorded as a follow-up.
- At ≥90% confidence: the slug is derived from the first finding's prose after its severity badge, because the change requests carry no `original_feedback` field.
- Residual risk, accepted by the reviewer: a checkout whose git keeps timing out pays up to 15 s per gather, and the N×15 s cold-cache worst case remains. Async detection or a per-root retry-after is the follow-up.
- `GitExecutableLocator` may cache an empty Optional on its own. That is out of scope.
