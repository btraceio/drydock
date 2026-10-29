# Spec: jira-group-tree-followups

<!-- run_id: srs-H1k5T16767OOW7RNf4oKLI1-1761657600 -->

## Problem

Four LOW follow-ups on the Jira-group task tree (`intellij-plugin`, package
`app.drydock.intellij.tasks`), plus one sibling found by the planner:

1. **F-850800607dc6**: `JiraTreeActions.editGroup` falls back to the tree row's
   stale copy of the group (`byId(...).orElse(row.group())`). `JiraGroups.withReplaced`
   appends ids it does not know, so editing a group that was deleted after the
   tree was drawn brings it back on save. The `JiraGroups` list is also loaded
   *before* the modal dialog, so a delete that lands while the dialog is open is
   undone by the save as well.
2. **F-029f35bc02fb**: `TaskTreeInputs.gather()` waits on each Jira group future
   one after another, each with a fresh full `JIRA_TIMEOUT_SECONDS`. If the
   executor hangs, the worst case is N × budget (N = number of groups across
   projects).
3. **F-136f41310af3**: the `JiraGroupRow` render branch hides the issue-count
   badge only for the loading shape. A Failed listing (a single `ListProblem`
   child) shows "0 issues".
4. **F-8dbbd50036c6**: the `TaskMenu.factsFor` javadoc says `jiraEnabled` also
   decides whether to keep the settings entry. In fact `JIRA_SETTINGS` is
   always offered.
5. **Sibling S1** (same defect class as 3): the `Section` badge
   (`TaskTreeRenderer.itemCount`) counts each `JiraGroupRow` as 1 item whatever
   its state (loading, failed, or listed with N issues). It also counts a
   `ListProblem` as 1 item. Two examples: "Issues  2" while both groups are
   still loading, and "Pull Requests  1" next to a failed-lookup row.

## Correct behaviour

### A. Jira group edits (JiraTreeActions / JiraGroups)
- `JiraGroups.load` / `.save` is the only source of truth for which groups
  exist. Tree rows are render-time snapshots and are never authoritative.
- **A1 (no resurrection):** an edit never writes an id that is missing from
  storage at save time. Only `addGroup` creates ids (via `withAdded`).
- **A2 (missing before the dialog):** if storage does not hold the row's id,
  do not open the dialog, do not save, and do not pre-fill from the stale row.
  Call `afterChange.accept(row.mainRoot())` so a rebuild drops the stale row.
  No message is shown.
- **A3 (missing after the dialog):** after `showAndGet()` returns true, reload
  `JiraGroups` and decide through `withEdited`:
  - if the id is gone, do not save;
  - otherwise replace the group in place in the *freshly loaded* list, so
    concurrent changes to other groups are kept. Last write wins for the
    edited group only.
  In both cases, call `afterChange.accept(row.mainRoot())`.
- **A4 (cancel):** a cancelled dialog does not save and does not rebuild.
- New pure method `Optional<JiraGroups> JiraGroups.withEdited(JiraGroup updated)`:
  - returns `Optional.empty()` when `byId(updated.id())` is empty;
  - otherwise returns `Optional.of(withReplaced(updated))`.
  `withReplaced` keeps its append-unknown contract.
- Threading: every load and save stays on the EDT.

### B. Jira gather timing (TaskTreeInputs)
- **B1:** the total Jira wait in one gather is at most ONE `JIRA_TIMEOUT_SECONDS`,
  whatever the number of groups or projects.
- **B2:** compute one absolute deadline (`System.nanoTime() + SECONDS.toNanos(JIRA_TIMEOUT_SECONDS)`)
  immediately before the Jira wait loop. Each group waits only for the time
  remaining, `Math.max(0, deadline - System.nanoTime())` nanoseconds. Compute
  the deadline once per gather, never once per project.
- **B3 (overflow):** a group whose future is not done by the deadline yields
  `JiraIssueListing.Failed("The Jira lookup did not complete")`, logged at
  DEBUG. Every requested group still yields exactly one
  `JiraGroups.Listing(group, Optional.of(...))`, in group order. No group is
  ever dropped and none ever gets `Optional.empty()`.
- **B4:** a future that has already completed returns its real value, even
  after the deadline.
- **B5:** a timed-out future is never cancelled. It is cached by `JiraIssueSource`.
- **B6:** an interrupt yields `Failed("The Jira lookup was interrupted")` and
  sets the interrupt flag again.
- GitHub wait loops are unchanged.

### C. Jira group badge (TaskTreeRenderer)
| Children | Badge |
|---|---|
| `[Deferred]` (loading) | none |
| `[ListProblem]` (Failed) | none |
| all `JiraIssueRow` | `1 issue` / `N issues` |
| `[Placeholder]` (empty Listed) | `0 issues` |
| any other shape (e.g. `JiraIssueRow` + `ListProblem`) | none |
- **C1:** show a badge only when every child is a `JiraIssueRow` or a
  `Placeholder`.
- **C2:** the rendered text is byte-identical to today's in the Listed states:
  two spaces + `"N issue"`/`"N issues"`, in `GRAYED_SMALL_ATTRIBUTES`. The icon,
  the bold name and the tooltip are unchanged.

### S1. Section badge (TaskTreeRenderer, sibling)
- **S1a:** no badge when any direct child is `Deferred` (unchanged rule).
- **S1b:** no badge when any direct `JiraGroupRow` child is still loading,
  i.e. has a `Deferred` child. The total is not known yet.
- **S1c:** in the count, a `JiraGroupRow` contributes its number of
  `JiraIssueRow` children. `ListProblem`, `Placeholder` and `Deferred`
  contribute 0. A nested `Section`, `StaleWorktrees` and `WorktreeGroup` keep
  their current counting, and every other node still counts 1.
- **S1d:** no badge when the count is 0 and the section holds a problem: a
  direct `ListProblem`, or a `JiraGroupRow` with a `ListProblem` child. A
  failure must not read as "0".
- **S1e:** otherwise the badge is the plain number, rendered as two spaces +
  number in `GRAYED_SMALL_ATTRIBUTES`, as today.

### D. TaskMenu docs
- **D1:** the `factsFor` javadoc says only that `jiraEnabled` gates
  `ADD_JIRA_GROUP`; `JIRA_SETTINGS` is unconditional. Comment-only change.

## Constraints
- No public API changes. Only these package-private test seams may be added:
  - `TaskTreeInputs.resolveJira` becomes static and takes a `long deadlineNanos`;
  - `TaskTreeInputs.jiraListings(Path, List<JiraGroup>, Function<JiraGroup, CompletableFuture<JiraIssueListing>>, long)`;
  - `JiraGroups.withEdited(JiraGroup)`;
  - `TaskTreeRenderer.issueCountBadge(TaskNode.JiraGroupRow)`;
  - `TaskTreeRenderer.sectionCountBadge(TaskNode.Section)`.
- `TaskTreeModel`, `JiraIssueSource`, `TaskTreePanel` and the EDT/BGT split
  in `JiraTreeActions` are untouched.
- Java 25, JUnit 5. Follow the existing style: pure package-private static
  helpers tested directly, and javadoc that states only what the code does.
- Existing tests must still pass: `JiraGroupsTest`, `TaskTreeRendererTest`,
  `TaskTreeModelTest`, `TaskMenuTest`, `TaskTreeInputsTest`,
  `TaskTreePanelTargetTest`.

## Scope

### Primary fixes
- `JiraTreeActions.java:199`: stale-snapshot upsert resurrection. Look the
  group up without a fallback (A2). After the dialog, reload and save through
  `JiraGroups.withEdited` (A1/A3).
- `TaskTreeInputs.java:342`: serial per-item timeout budgets. Use one shared
  nanoTime deadline for the gather, a static `resolveJira(root, future, deadline)`
  and a static `jiraListings` helper (B1–B6).
- `TaskTreeRenderer.java:98`: count badge shown for a non-count state. Extract
  `issueCountBadge` and apply C1/C2.
- `TaskMenu.java:219`: documentation contradicts the code. Fix the javadoc (D1).

### Auto-expanded sibling fixes
- `TaskTreeRenderer.java:83-85,186-197`: count badge shown for a non-count
  state (Section level).
  - Evidence: `itemCount` ends with `default -> 1`, so a `JiraGroupRow` or a
    `ListProblem` counts as 1 item. The badge is suppressed only on a direct
    `Deferred` child.
  - FLOW: `TaskTreeModel` (~469-476) builds `Section(ISSUES, issueChildren)` with
    `JiraGroupRow` children (each may hold `[Deferred]` or `[ListProblem]`), or
    (~521-522) `Section(PULL_REQUESTS, [ListProblem])`. The renderer's
    `case Section` then calls `itemCount`.
  - PRECONDITION: a project with Jira groups defined, or a failed GitHub listing.
  - REACHABLE: yes.
  - CONCLUSION: LOW. The section badge reports a number for loading or failed
    content, the same misreport as F-136f41310af3.

### Out-of-scope observations (no change)
- `TaskTreeInputs.gather()` GitHub issue/PR wait loops (~294-305, ~314-326)
  also wait serially with a full `GITHUB_TIMEOUT_SECONDS` per project. Same
  class as F-029f35bc02fb, but the design rules them out of scope. Recorded
  for a follow-up.

## Assumptions
- On the "missing" path, edit triggers a rebuild (`afterChange`) and shows no
  message. The reviewer's "no-op (rebuild will drop the stale row)" allows it.
- The post-dialog recheck (A3) has the same root cause, so it is in scope.
- The Jira deadline covers the whole gather, not each project. This is
  stricter than the reviewer's per-project `allOf`, and each future still gets
  ≥ `JIRA_TIMEOUT_SECONDS` from its own start, because all futures start
  before any wait.
- `JiraIssueListing.Unsupported` never reaches the renderer today. C1 hides
  the badge for any unexpected shape anyway.
- S1 is in scope as a sibling. The count rules are inferred from the
  existing pattern, in which container nodes (`WorktreeGroup`,
  `StaleWorktrees`) contribute their content count.
- The `editGroup` lambda wiring, and where `gather()` computes the deadline,
  are checked by review only. They need a modal dialog,
  `PropertiesComponent`, or a full plugin service.
