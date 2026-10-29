# Spec: jira-group-expansion-state

run_id: jira-group-fix-20261029T1045-zQ7wKx
Design: `.sphinx/address/design.md` (design-adversary round 1: no must-fix issues).

## Problem
The uncommitted change that keeps user-opened Jira groups open across tree
rebuilds has two gaps.

1. **F-ccf55e37bbe4**: `ListingExpansionTracker.jiraGroups`
   (`intellij-plugin/src/platform/java/app/drydock/intellij/tasks/ListingExpansionTracker.java:49`)
   has an add path (`treeWillExpand`, `:94`) and a read path
   (`requestedJiraGroups()`, `:73`), but nothing removes from it. Users can delete
   groups, disable a project's Jira connection, or unregister a project. Each of
   those leaves its keys behind for the rest of the panel's life.
2. **F-4e8cb7f9f5aa**: the restoration decision for a `JiraGroupRow`
   (`TaskTreePanel.java:1185-1187`) sits inside the private instance walk
   `expandEagerAndRequested`, which needs the panel's live `tree` and `expansions`
   fields. No test reaches its `root != null` guard or its
   `GroupKey(root, group.id())` lookup. Removing the guard would not cause an NPE:
   a null-root key is simply absent from the set. The guard only changes behaviour
   in the `ISSUES` view, where it stops a group with no project ancestor from
   expanding.

## Correct behaviour
Ownership
- R1. `ListingExpansionTracker` owns all four sets (`worktrees`, `issues`,
  `pullRequests`, `jiraGroups`). The panel reads them, adds section requests via
  `RefreshTarget.requestListing`, and asks the tracker to prune Jira groups.
  `requested(kind)` and `requestedJiraGroups()` still return the live sets.
- R2. A completed gather's `TaskTreeModel.Inputs.jiraGroupsByMainRoot()` decides
  which Jira groups exist. That map holds every defined group of every present,
  Jira-enabled, registered local project, whether or not its listing was fetched.
  The displayed tree does not decide this.

Transitions of a `GroupKey(root, groupId)`
- T1. absent → recorded: the user expands a `JiraGroupRow` outside
  `runProgrammatic` and `projectRootOf(path)` is present. This is unchanged.
- T2. An expansion during restoration (`programmatic`) changes nothing. This is
  unchanged.
- T3. recorded → evicted: a completed gather's definitions lack the key. Causes:
  the group was deleted, its project was unregistered or its folder is missing, or
  the project's Jira connection is disabled.
- T4. recorded → kept: the key is among the definitions. This holds whatever the
  view filter, search filter or section requested-ness, and whether or not the
  listing was fetched (`Listing.listing()` may be empty or failed).
- T5. A failed pass (gather throws, taking the `exceptionally` path) evicts nothing.
- T6. Editing a group keeps its id, so the group stays recorded. The key depends
  only on `(root, id)`, never on name or JQL.

Restoration of a `JiraGroupRow` node
- E1. The key root is the main root of the nearest `ProjectRow` ancestor,
  propagated down the walk, exactly as today. It is not `JiraGroupRow.mainRoot()`.
  This keeps it equal to what `treeWillExpand` records via `projectRootOf`. A
  `JiraGroupRow` with no `ProjectRow` ancestor never expands, in any view,
  including `ISSUES`.
- E2. With a root, a group expands iff `view == ISSUES` or `GroupKey(root, id)` is
  recorded. A key recorded under another root does not expand it.
- E3. All other rules stay exactly as they are:
  - `ProjectRow`, `WorktreeGroup`, `StaleWorktrees` and the
    `SESSIONS`/`ACTIVE`/`INACTIVE` sections are eager.
  - `WORKTREES`, `ISSUES` and `PULL_REQUESTS` follow `expandsListing` with the
    root-presence guard.
  - A node is expanded before its children are visited, and children are walked
    in index order.

Bound / overflow policy
- B1. After every successful gather, `|jiraGroups|` is at most the number of groups
  defined across present, registered, Jira-enabled projects. There is no cap and
  nothing is silently dropped. The accepted cost: a group whose project is briefly
  missing or disabled comes back collapsed.

Locking / threading
- L1. Adds run on the EDT. Pruning runs on the background rebuild thread right
  after `gather` returns. The set stays `ConcurrentHashMap.newKeySet()`, so
  element-wise `retainAll`/`removeIf` needs no extra lock.
- L2. Rebuild passes are serialized by `TaskTreeRefreshRequests.beginPass`. A group
  becomes visible only after a pass whose gather loaded its definition, so a live
  group never loses its recorded intent.

## Constraints
- Nothing becomes public; new members are package-private in
  `app.drydock.intellij.tasks`. The only new seams are package-private static ones
  like the existing `expandsGroup`, `expandsListing`, `treeNode` and
  `projectRootOf`.
- Pruning is bookkeeping only. It does not trigger a rebuild and does not touch
  the EDT.
- Restoration still runs inside `expansions.runProgrammatic(...)` in `apply()`.
- `ListingExpansionTracker` stays a drop-in `TreeWillExpandListener`, and
  `runProgrammatic` stays exception-safe.
- Style: Java 21 pattern `switch`, records, Javadoc on package-private seams that
  says why they are package-private. Tests use JUnit 5 and plain Swing (`JTree`,
  `DefaultMutableTreeNode`) with no IntelliJ `Project`.

## Scope

### Primary fixes
- `ListingExpansionTracker.java:49`, unbounded recorded state (missing eviction).
  Add a pure operation that keeps only the keys defined by a gather's
  `jiraGroupsByMainRoot()`. Call it in `TaskTreePanel.scheduleRebuild` right after
  a successful `gather`.
- `TaskTreePanel.java:1167-1197` (`:1185`), decision fused with panel side effects
  (untestable). Extract the restoration walk into a package-private static seam
  that takes the tracker, the view and a `Consumer<TreePath>` expander. `apply()`
  delegates to it with `tree::expandPath`, and behaviour stays identical.

### Auto-expanded sibling fixes
None identified. Candidates considered and rejected:
- Per-root section sets `worktrees`/`issues`/`pullRequests` in the same tracker.
  - FLOW: user opens a section, `treeWillExpand` → `record`; later the project is
    unregistered.
  - PRECONDITION: many projects registered and unregistered in one panel session.
  - REACHABLE: yes, but growth is bounded by projects ever registered in the
    session. Keeping the root also preserves intent when the project is
    re-registered or its missing folder returns. Gather emits no section state
    for missing-folder projects (`TaskTreeInputs.java:174-177`), so retaining
    against it would re-collapse them.
  - CONCLUSION: not the same defect (bounded, intentional). Out of scope, recorded
    as an assumption.
- `JiraIssueSource.cache` and the `TaskTreeInputs` caches
  (`issueCache`/`pullRequestCache`/`worktreeCache`/`activityCache`). These are
  pre-existing, TTL- or per-root-invalidated caches, not user-intent sets, and are
  out of scope.
- `WorktreeReview.inFlight` / `PullRequestReview.inFlight`. These are removed on
  completion (`WorktreeReview.java:140`, `PullRequestReview.java:195`), so they
  are not unbounded.

## Assumptions
- A1 (≥90%): `TaskTreeInputs.gather` puts every defined group of each present,
  Jira-enabled, registered local project in `jiraGroupsByMainRoot`, fetched or not
  (`TaskTreeInputs.java:190-215, 310-313`). That makes it a safe eviction
  authority.
- A2 (≥90%): it is acceptable to lose a group's expansion when its project is
  disabled, missing or unregistered. The group is not shown in those states.
- A3 (≥90%): remote projects never yield `JiraGroupRow`s.
- A4: the restoration-guard test targets the observable effect ("no expansion
  without a project ancestor in the ISSUES view"), not an NPE.
- A5: the reviewed change is uncommitted in the worktree. The fixes go on top of it.
