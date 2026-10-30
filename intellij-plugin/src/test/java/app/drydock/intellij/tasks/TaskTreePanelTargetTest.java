package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.JiraIssue;
import app.drydock.git.JiraIssueListing;
import app.drydock.intellij.tasks.TaskNode.Deferred;
import app.drydock.intellij.tasks.TaskNode.Ghost;
import app.drydock.intellij.tasks.TaskNode.GhostSource;
import app.drydock.intellij.tasks.TaskNode.GitHubNode;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.IssueRow;
import app.drydock.intellij.tasks.TaskNode.JiraGroupRow;
import app.drydock.intellij.tasks.TaskNode.JiraIssueRow;
import app.drydock.intellij.tasks.TaskNode.JiraNode;
import app.drydock.intellij.tasks.TaskNode.Placeholder;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.SectionKind;
import app.drydock.intellij.tasks.TaskNode.StaleWorktrees;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.tasks.TaskNode.WorktreeGroup;
import app.drydock.intellij.tasks.TaskTreeModel.ViewFilter;
import app.drydock.projects.SharedProject;
import org.junit.jupiter.api.Test;

import javax.swing.JTree;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskTreePanelTargetTest {

    private static final Path REGISTERED_ROOT = Path.of("/work/profiling-backend");
    private static final Path LINKED_WORKTREE = Path.of("/work/profiling-backend-fix");
    private static final Instant NOW = Instant.parse("2026-10-20T17:00:00Z");

    @Test
    void projectRowTargetsTheRegisteredProjectRatherThanTheWindowProject() {
        SharedProject registered = SharedProject.local(
                RepositoryId.newId(), REGISTERED_ROOT, "profiling-backend", NOW);
        ProjectRow row = new ProjectRow(registered, false, false, List.of(), List.of(), 0);

        assertEquals(Optional.of(new TaskTreePanel.NewTaskTarget(REGISTERED_ROOT, Optional.empty())),
                TaskTreePanel.newTaskTarget(row, REGISTERED_ROOT));
    }

    @Test
    void idleWorktreeTargetsThatExactWorktree() {
        IdleWorktree row = new IdleWorktree(LINKED_WORKTREE, "fix-allocation-profile",
                Optional.empty(), false, false);
        NewTaskPlan.FixedWorktree fixed = new NewTaskPlan.FixedWorktree(
                LINKED_WORKTREE, "fix-allocation-profile");

        assertEquals(Optional.of(new TaskTreePanel.NewTaskTarget(REGISTERED_ROOT, Optional.of(fixed))),
                TaskTreePanel.newTaskTarget(row, REGISTERED_ROOT));
    }

    @Test
    void taskRowInALinkedWorktreeTargetsThatWorktreeToo() {
        ManagedAgentSession session = new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), "Fix allocation profile",
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(LINKED_WORKTREE), NOW)
                .build();
        TaskRow row = new TaskRow(session, Optional.empty(), false,
                Optional.of("fix-allocation-profile"), new TaskPlacement.Idle(false), false, false);
        NewTaskPlan.FixedWorktree fixed = new NewTaskPlan.FixedWorktree(
                LINKED_WORKTREE, "fix-allocation-profile");

        assertEquals(Optional.of(new TaskTreePanel.NewTaskTarget(REGISTERED_ROOT, Optional.of(fixed))),
                TaskTreePanel.newTaskTarget(row, REGISTERED_ROOT));
    }

    @Test
    void taskInTheMainCheckoutKeepsTheNormalMainProjectChoices() {
        ManagedAgentSession session = new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), "Main checkout task",
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(REGISTERED_ROOT), NOW)
                .build();
        TaskRow row = new TaskRow(session, Optional.empty(), false,
                Optional.of("main"), new TaskPlacement.Idle(false), false, false);

        assertEquals(Optional.of(new TaskTreePanel.NewTaskTarget(REGISTERED_ROOT, Optional.empty())),
                TaskTreePanel.newTaskTarget(row, REGISTERED_ROOT));
    }

    @Test
    void issueRowDoesNotOfferTheGenericNewTaskAction() {
        IssueRow row = new IssueRow(REGISTERED_ROOT, new OpenIssue(42, "Fix allocation profile",
                Optional.of("https://github.com/DataDog/profiling-backend/issues/42"), List.of(), List.of(),
                Optional.of(NOW)));

        assertEquals(Optional.empty(), TaskTreePanel.newTaskTarget(row, REGISTERED_ROOT));
    }

    @Test
    void anIssuesContainerNodeFollowsTheRequestAloneInEveryView() {
        for (ViewFilter view : ViewFilter.values()) {
            assertFalse(TaskTreePanel.expandsIssuesNode(false),
                    view + " must not open a GitHub node, a Jira node or a Jira group on its own");
            assertTrue(TaskTreePanel.expandsIssuesNode(true),
                    view + " keeps the user's expansion");
        }
    }

    /**
     * The reported defect, at its seam: a group whose model children never
     * reach the Swing node is a leaf — rendered with its badge, but with no
     * handle and no way to open it.
     */
    @Test
    void aJiraGroupNodeCarriesItsChildrenSoTheTreeCanExpandIt() {
        JiraGroupRow group = new JiraGroupRow(REGISTERED_ROOT, new JiraGroup("g-sprint", "Sprint", "project = DRY"),
                List.of(new JiraIssueRow(REGISTERED_ROOT, jiraIssue("DRY-1", "Fix the profiler")),
                        new JiraIssueRow(REGISTERED_ROOT, jiraIssue("DRY-2", "Ship the fix"))));

        DefaultMutableTreeNode node = TaskTreePanel.treeNode(group, Optional.empty(),
                new DefaultMutableTreeNode[1]);

        assertFalse(node.isLeaf(), "a group with issues must not be a Swing leaf");
        assertEquals(2, node.getChildCount());
        assertEquals(List.of("DRY-1", "DRY-2"), childrenKeys(node));
    }

    private static JiraIssue jiraIssue(String key, String summary) {
        return new JiraIssue(key, summary, "https://acme.atlassian.net/browse/" + key, List.of(),
                Optional.of(NOW));
    }

    private static List<String> childrenKeys(DefaultMutableTreeNode node) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            keys.add(((JiraIssueRow) child.getUserObject()).issue().key());
        }
        return keys;
    }

    private static final Path OTHER_ROOT = Path.of("/work/drydock");

    /** Sections of both projects, keyed "<file name>:<SectionKind>", with the real paths of a live tree. */
    private static JTree listingTree(ListingExpansionTracker tracker, Map<String, TreePath> sections) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        for (Path projectRoot : List.of(REGISTERED_ROOT, OTHER_ROOT)) {
            DefaultMutableTreeNode project = new DefaultMutableTreeNode(new ProjectRow(
                    SharedProject.local(RepositoryId.newId(), projectRoot,
                            projectRoot.getFileName().toString(), NOW),
                    false, false, List.of(), List.of(), 0));
            for (SectionKind kind : List.of(SectionKind.ISSUES, SectionKind.PULL_REQUESTS)) {
                // The lazy container's own row until opening it starts the background
                // read — a real child node, so the section is expandable, not a leaf.
                DefaultMutableTreeNode section = new DefaultMutableTreeNode(new Section(kind,
                        List.of(new Deferred("Reading…"))));
                section.add(new DefaultMutableTreeNode(new Deferred("Reading…")));
                project.add(section);
            }
            root.add(project);
        }
        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.setRootVisible(true);
        tree.addTreeWillExpandListener(tracker);
        // Full paths, now that every node has its parent: without them the root
        // component is missing and no path matches the model's nodes.
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode project = (DefaultMutableTreeNode) root.getChildAt(i);
            for (int j = 0; j < project.getChildCount(); j++) {
                DefaultMutableTreeNode section = (DefaultMutableTreeNode) project.getChildAt(j);
                sections.put(((ProjectRow) project.getUserObject()).project().displayName() + ":"
                        + ((Section) section.getUserObject()).kind(), new TreePath(section.getPath()));
            }
            // As the panel does: project rows expand before their sections, so a
            // section's expandPath is "viewable". Project rows are not listing
            // sections, so these expansions record nothing.
            tree.expandPath(new TreePath(project.getPath()));
        }
        return tree;
    }

    @Test
    void programmaticRestorationOfAListingViewRecordsNoIntent() {
        Map<String, TreePath> sections = new HashMap<>();
        List<Boolean> rebuilds = new ArrayList<>();
        ListingExpansionTracker tracker = new ListingExpansionTracker(
                changed -> rebuilds.add(changed), TaskTreePanel::projectRootOf);
        JTree tree = listingTree(tracker, sections);

        // The Issues-view pass: the panel itself expands both projects' sections.
        tracker.runProgrammatic(() -> {
            tree.expandPath(sections.get("profiling-backend:ISSUES"));
            tree.expandPath(sections.get("drydock:ISSUES"));
        });
        // The real expansion events fired — but no intent was recorded and no rebuild asked for.
        assertTrue(tree.isExpanded(sections.get("profiling-backend:ISSUES")));
        assertTrue(tree.isExpanded(sections.get("drydock:ISSUES")));
        assertTrue(tracker.requestedIssueSections().isEmpty());
        assertTrue(tracker.requested(SectionKind.PULL_REQUESTS).isEmpty());
        assertTrue(rebuilds.isEmpty());
    }

    @Test
    void manualIssuesSectionExpansionRecordsStateButFetchesNothing() {
        Map<String, TreePath> sections = new HashMap<>();
        List<Boolean> rebuilds = new ArrayList<>();
        ListingExpansionTracker tracker = new ListingExpansionTracker(
                changed -> rebuilds.add(changed), TaskTreePanel::projectRootOf);
        JTree tree = listingTree(tracker, sections);

        tree.expandPath(sections.get("profiling-backend:ISSUES"));

        // The section's children travel without a fetch — opening it is pure
        // UI state, and no rebuild is asked for.
        assertEquals(Set.of(REGISTERED_ROOT), tracker.requestedIssueSections());
        assertTrue(rebuilds.isEmpty());
        assertTrue(tracker.requested(SectionKind.PULL_REQUESTS).isEmpty());
        // Re-opening a section the user already opened records nothing new.
        tree.collapsePath(sections.get("profiling-backend:ISSUES"));
        tree.expandPath(sections.get("profiling-backend:ISSUES"));
        assertEquals(Set.of(REGISTERED_ROOT), tracker.requestedIssueSections());
    }

    @Test
    void requestedRejectsSectionKindsItDoesNotTrack() {
        ListingExpansionTracker tracker = new ListingExpansionTracker(changed -> { },
                TaskTreePanel::projectRootOf);
        for (SectionKind kind : SectionKind.values()) {
            if (kind == SectionKind.WORKTREES || kind == SectionKind.PULL_REQUESTS) {
                continue;
            }
            assertThrows(IllegalArgumentException.class, () -> tracker.requested(kind), kind.toString());
        }
        // The tracked kinds stay live, mutable sets.
        tracker.requested(SectionKind.PULL_REQUESTS).add(REGISTERED_ROOT);
        assertEquals(Set.of(REGISTERED_ROOT), tracker.requested(SectionKind.PULL_REQUESTS));
        tracker.requested(SectionKind.WORKTREES).add(REGISTERED_ROOT);
        assertEquals(Set.of(REGISTERED_ROOT), tracker.requested(SectionKind.WORKTREES));
    }

    @Test
    void expandingNonListingSectionsNeverReachesRequested() {
        ListingExpansionTracker tracker = new ListingExpansionTracker(changed -> { },
                TaskTreePanel::projectRootOf);
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        DefaultMutableTreeNode project = new DefaultMutableTreeNode(new ProjectRow(
                SharedProject.local(RepositoryId.newId(), REGISTERED_ROOT, "profiling-backend", NOW),
                false, false, List.of(), List.of(), 0));
        List<SectionKind> kinds = List.of(SectionKind.SESSIONS, SectionKind.ACTIVE,
                SectionKind.INACTIVE, SectionKind.ISSUES);
        for (SectionKind kind : kinds) {
            DefaultMutableTreeNode section = new DefaultMutableTreeNode(new Section(kind,
                    List.of(new Deferred("Reading…"))));
            section.add(new DefaultMutableTreeNode(new Deferred("Reading…")));
            project.add(section);
        }
        root.add(project);
        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.setRootVisible(true);
        tree.addTreeWillExpandListener(tracker);
        tree.expandPath(new TreePath(project.getPath()));

        for (int i = 0; i < project.getChildCount(); i++) {
            TreePath path = new TreePath(((DefaultMutableTreeNode) project.getChildAt(i)).getPath());
            assertDoesNotThrow(() -> tree.expandPath(path), kinds.get(i).toString());
            assertTrue(tree.isExpanded(path), kinds.get(i).toString());
        }
        assertEquals(Set.of(REGISTERED_ROOT), tracker.requestedIssueSections());
    }

    /** A tree whose Issues section holds one Jira group with a loading child. */
    private static JTree groupTree(ListingExpansionTracker tracker, TreePath[] groupPath) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        DefaultMutableTreeNode project = new DefaultMutableTreeNode(new ProjectRow(
                SharedProject.local(RepositoryId.newId(), REGISTERED_ROOT, "profiling-backend", NOW),
                false, false, List.of(), List.of(), 0));
        JiraGroupRow group = new JiraGroupRow(REGISTERED_ROOT,
                new JiraGroup("g-sprint", "Sprint", "project = DRY"),
                List.of(new Deferred("Loading…")));
        DefaultMutableTreeNode section = new DefaultMutableTreeNode(new Section(SectionKind.ISSUES, List.of(group)));
        DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode(group);
        groupNode.add(new DefaultMutableTreeNode(new Deferred("Loading…")));
        section.add(groupNode);
        project.add(section);
        root.add(project);
        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.setRootVisible(true);
        tree.addTreeWillExpandListener(tracker);
        // The panel's own restoration opens the way to the group: neither the
        // project row nor a guarded section expansion is user intent.
        tracker.runProgrammatic(() -> {
            tree.expandPath(new TreePath(root.getPath()));
            tree.expandPath(new TreePath(project.getPath()));
            tree.expandPath(new TreePath(section.getPath()));
        });
        groupPath[0] = new TreePath(groupNode.getPath());
        return tree;
    }

    @Test
    void manualGroupExpansionRecordsTheGroupWithoutARebuild() {
        List<Boolean> rebuilds = new ArrayList<>();
        ListingExpansionTracker tracker = new ListingExpansionTracker(
                changed -> rebuilds.add(changed), TaskTreePanel::projectRootOf);
        TreePath[] groupPath = new TreePath[1];
        JTree tree = groupTree(tracker, groupPath);

        tree.expandPath(groupPath[0]);

        assertTrue(tree.isExpanded(groupPath[0]));
        assertEquals(Set.of(new ListingExpansionTracker.GroupKey(REGISTERED_ROOT, "g-sprint")),
                tracker.requestedJiraGroups());
        // The group's listing is its own fetch now: opening it asks for one
        // rebuild so the next gather queries it, and the record survives it.
        assertEquals(List.of(Boolean.FALSE), rebuilds);
    }

    @Test
    void programmaticGroupExpansionRecordsNoGroup() {
        ListingExpansionTracker tracker = new ListingExpansionTracker(changed -> { },
                TaskTreePanel::projectRootOf);
        TreePath[] groupPath = new TreePath[1];
        JTree tree = groupTree(tracker, groupPath);

        // A pass through the Issues view: the panel itself opens every group.
        tracker.runProgrammatic(() -> tree.expandPath(groupPath[0]));

        assertTrue(tree.isExpanded(groupPath[0]));
        assertTrue(tracker.requestedJiraGroups().isEmpty());
    }

    @Test
    void aFailingRestorationStillClearsTheGuard() {
        Map<String, TreePath> sections = new HashMap<>();
        ListingExpansionTracker tracker = new ListingExpansionTracker(changed -> { },
                TaskTreePanel::projectRootOf);
        JTree tree = listingTree(tracker, sections);

        assertThrows(RuntimeException.class, () -> tracker.runProgrammatic(() -> {
            tree.expandPath(sections.get("drydock:ISSUES"));
            throw new IllegalStateException("restoration failed");
        }));
        // The finally cleared the guard: the next real user expansion records.
        tree.expandPath(sections.get("profiling-backend:ISSUES"));
        assertEquals(Set.of(REGISTERED_ROOT), tracker.requestedIssueSections());
    }

    @Test
    void returningToActiveOrAllKeepsOnlyTheSectionsTheUserOpened() {
        Map<String, TreePath> sections = new HashMap<>();
        ListingExpansionTracker tracker = new ListingExpansionTracker(changed -> { },
                TaskTreePanel::projectRootOf);
        JTree tree = listingTree(tracker, sections);

        // A pass through the PRs view: the panel restores every project's section itself.
        tracker.runProgrammatic(() -> {
            tree.expandPath(sections.get("profiling-backend:PULL_REQUESTS"));
            tree.expandPath(sections.get("drydock:PULL_REQUESTS"));
        });
        // Back in All, a later pass restores again — one section collapsed since.
        tree.collapsePath(sections.get("drydock:PULL_REQUESTS"));
        tracker.runProgrammatic(() -> tree.expandPath(sections.get("drydock:PULL_REQUESTS")));
        assertTrue(tracker.requested(SectionKind.PULL_REQUESTS).isEmpty());

        // The user then opens exactly one project's section by hand.
        tree.collapsePath(sections.get("drydock:PULL_REQUESTS"));
        tree.expandPath(sections.get("drydock:PULL_REQUESTS"));
        assertEquals(Set.of(OTHER_ROOT), tracker.requested(SectionKind.PULL_REQUESTS));

        // A further programmatic pass adds nothing to the request.
        tracker.runProgrammatic(() -> {
            tree.collapsePath(sections.get("profiling-backend:PULL_REQUESTS"));
            tree.expandPath(sections.get("profiling-backend:PULL_REQUESTS"));
        });
        assertEquals(Set.of(OTHER_ROOT), tracker.requested(SectionKind.PULL_REQUESTS));
    }

    @Test
    void aSectionWithNoProjectAboveItRecordsNothing() {
        List<Boolean> rebuilds = new ArrayList<>();
        ListingExpansionTracker tracker = new ListingExpansionTracker(
                changed -> rebuilds.add(changed), TaskTreePanel::projectRootOf);
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        DefaultMutableTreeNode section = new DefaultMutableTreeNode(new Section(SectionKind.ISSUES,
                List.of(new Deferred("Reading…"))));
        section.add(new DefaultMutableTreeNode(new Deferred("Reading…")));
        root.add(section);
        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.setRootVisible(true);
        tree.addTreeWillExpandListener(tracker);

        tree.expandPath(new TreePath(section.getPath()));

        assertTrue(tracker.requestedIssueSections().isEmpty());
        assertTrue(tracker.requested(SectionKind.PULL_REQUESTS).isEmpty());
        assertTrue(rebuilds.isEmpty());
    }

    // --- F8: the shared refresh gesture's scope ------------------------------------

    /** Records what one refresh gesture dispatched, in order (F8). */
    private static final class RecordingRefresh implements TaskTreePanel.RefreshTarget {

        final List<String> calls = new ArrayList<>();

        @Override
        public void invalidateAll() {
            calls.add("all");
        }

        @Override
        public void invalidateIssues(Path root) {
            calls.add("issues:" + root);
        }

        @Override
        public void invalidatePullRequests(Path root) {
            calls.add("pullRequests:" + root);
        }

        @Override
        public void invalidateJiraIssues(Path root) {
            calls.add("jiraIssues:" + root);
        }

        @Override
        public void requestPullRequests(Path root) {
            calls.add("listing:PULL_REQUESTS:" + root);
        }

        @Override
        public void requestGitHubIssues(Path root) {
            calls.add("githubIssues:" + root);
        }

        @Override
        public void requestJiraGroup(Path root, String groupId) {
            calls.add("jiraGroup:" + root + ":" + groupId);
        }

        @Override
        public void requestRebuild(boolean changedWorktrees) {
            calls.add("rebuild:" + changedWorktrees);
        }
    }

    /** A listing section with its deferred child, as the tree shows one. */
    private static Section section(SectionKind kind) {
        return new Section(kind, List.of(new Deferred("Reading…")));
    }

    @Test
    void aToolbarRefreshWithASectionSelectedStaysGlobal() {
        RecordingRefresh target = new RecordingRefresh();

        TaskTreePanel.refresh(target, false, section(SectionKind.ISSUES), REGISTERED_ROOT);

        // Whatever the tree selection, the toolbar's gesture drops every
        // project's listings and re-lists worktrees.
        assertEquals(List.of("all", "rebuild:true"), target.calls);
    }

    @Test
    void aProjectRowContextMenuRefreshStaysGlobal() {
        RecordingRefresh target = new RecordingRefresh();
        ProjectRow row = new ProjectRow(SharedProject.local(
                RepositoryId.newId(), REGISTERED_ROOT, "profiling-backend", NOW),
                false, false, List.of(), List.of(), 0);

        TaskTreePanel.refresh(target, true, row, REGISTERED_ROOT);

        // The project row's menu entry names a whole project, so it keeps
        // the toolbar's scope.
        assertEquals(List.of("all", "rebuild:true"), target.calls);
    }

    @Test
    void anIssuesSectionContextMenuRefreshFetchesItsGitHubNodeAndGroups() {
        RecordingRefresh target = new RecordingRefresh();

        TaskTreePanel.refresh(target, true,
                new Section(SectionKind.ISSUES, List.of(
                        new GitHubNode(REGISTERED_ROOT, List.of(new Placeholder(TaskTreeModel.NOT_LOADED))),
                        new JiraNode(REGISTERED_ROOT, List.of(new JiraGroupRow(REGISTERED_ROOT,
                                new JiraGroup("g-sprint", "Sprint", "project = DRY"),
                                List.of(new Placeholder(TaskTreeModel.NOT_LOADED))))))),
                REGISTERED_ROOT);

        assertEquals(List.of("issues:" + REGISTERED_ROOT,
                "jiraIssues:" + REGISTERED_ROOT,
                "githubIssues:" + REGISTERED_ROOT,
                "jiraGroup:" + REGISTERED_ROOT + ":g-sprint", "rebuild:false"), target.calls);
    }

    @Test
    void aPullRequestSectionContextMenuRefreshTouchesOnlyThatProject() {
        RecordingRefresh target = new RecordingRefresh();

        TaskTreePanel.refresh(target, true, section(SectionKind.PULL_REQUESTS), REGISTERED_ROOT);

        assertEquals(List.of("pullRequests:" + REGISTERED_ROOT,
                "listing:PULL_REQUESTS:" + REGISTERED_ROOT, "rebuild:false"), target.calls);
    }

    @Test
    void aContextSectionRefreshWithoutARootFetchesNothingRatherThanEverything() {
        RecordingRefresh target = new RecordingRefresh();

        TaskTreePanel.refresh(target, true, section(SectionKind.ISSUES), null);

        // An unscoped section must not silently broaden to a global fetch.
        assertTrue(target.calls.isEmpty());
    }

    @Test
    void aSectionWithNoListingOfItsOwnNeverBroadensToAGlobalFetch() {
        for (SectionKind kind : SectionKind.values()) {
            if (kind == SectionKind.ISSUES || kind == SectionKind.PULL_REQUESTS) {
                continue;
            }
            RecordingRefresh target = new RecordingRefresh();

            TaskTreePanel.refresh(target, true, section(kind), REGISTERED_ROOT);

            assertTrue(target.calls.isEmpty(), kind + " must not dispatch a fetch");
        }
    }

    @Test
    void jiraIssueRowDoesNotOfferTheGenericNewTaskAction() {
        JiraIssueRow row = new JiraIssueRow(REGISTERED_ROOT, new JiraIssue("PROJ-123",
                "Fix allocation accounting", "https://acme.atlassian.net/browse/PROJ-123",
                List.of(), Optional.of(NOW)));

        assertEquals(Optional.empty(), TaskTreePanel.newTaskTarget(row, REGISTERED_ROOT));
    }

    @Test
    void jiraSettingsAreReachableFromProjectIssuesSectionAndJiraRows() {
        SharedProject registered = SharedProject.local(
                RepositoryId.newId(), REGISTERED_ROOT, "profiling-backend", NOW);
        ProjectRow project = new ProjectRow(registered, false, false, List.of(), List.of(), 0);
        Section issues = new Section(SectionKind.ISSUES, List.of(new Deferred("Loading issues…")));
        JiraIssueRow jira = new JiraIssueRow(REGISTERED_ROOT, new JiraIssue("PROJ-123",
                "Fix allocation accounting", "https://acme.atlassian.net/browse/PROJ-123",
                List.of(), Optional.of(NOW)));

        assertTrue(JiraTreeActions.settingsNode(project));
        assertTrue(JiraTreeActions.settingsNode(issues));
        assertTrue(JiraTreeActions.settingsNode(jira));
    }

    @Test
    void jiraSettingsAreNotOfferedOnIssueRowsOrPullRequestSections() {
        IssueRow issue = new IssueRow(REGISTERED_ROOT, new OpenIssue(42, "Fix allocation profile",
                Optional.of("https://github.com/DataDog/profiling-backend/issues/42"), List.of(), List.of(),
                Optional.of(NOW)));
        Section pullRequests = new Section(SectionKind.PULL_REQUESTS,
                List.of(new Deferred("Loading pull requests…")));

        assertFalse(JiraTreeActions.settingsNode(issue));
        assertFalse(JiraTreeActions.settingsNode(pullRequests));
    }

    // --- Jira group expansion state: restoration walk and eviction ------------------

    private static ListingExpansionTracker tracker() {
        return new ListingExpansionTracker(changed -> { }, TaskTreePanel::projectRootOf);
    }

    private static void recordGroup(ListingExpansionTracker tracker, Path root, String groupId) {
        tracker.requestedJiraGroups().add(new ListingExpansionTracker.GroupKey(root, groupId));
    }

    private static JiraGroups.Listing listing(String groupId) {
        return new JiraGroups.Listing(new JiraGroup(groupId, "Name " + groupId, "project = DRY"), Optional.empty());
    }

    private static ProjectRow projectRow(Path root, List<TaskNode> children) {
        return new ProjectRow(SharedProject.local(RepositoryId.newId(), root, root.getFileName().toString(), NOW),
                false, false, children, List.of(), 0);
    }

    private static JiraGroupRow groupRow(Path mainRoot, String groupId) {
        return new JiraGroupRow(mainRoot, new JiraGroup(groupId, "Name " + groupId, "project = DRY"),
                List.of(new Deferred("Loading…")));
    }

    /** {@code ProjectRow(projectRoot) > Section(ISSUES) > JiraGroupRow(groupMainRoot, groupId)}. */
    private static ProjectRow issuesProject(Path projectRoot, Path groupMainRoot, String... groupIds) {
        List<TaskNode> groups = new ArrayList<>();
        for (String id : groupIds) {
            groups.add(groupRow(groupMainRoot, id));
        }
        return projectRow(projectRoot, List.of(new Section(SectionKind.ISSUES, groups)));
    }

    /** The invisible root the panel builds, holding the model rows mapped exactly as the panel maps them. */
    private static DefaultMutableTreeNode modelTree(TaskNode... nodes) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        for (TaskNode node : nodes) {
            root.add(TaskTreePanel.treeNode(node, Optional.empty(), new DefaultMutableTreeNode[1]));
        }
        return root;
    }

    /** The path of the node reached from {@code root} by these child indices. */
    private static TreePath pathAt(DefaultMutableTreeNode root, int... indices) {
        DefaultMutableTreeNode node = root;
        for (int index : indices) {
            node = (DefaultMutableTreeNode) node.getChildAt(index);
        }
        return new TreePath(node.getPath());
    }

    /** Runs the panel's restoration walk from the invisible root, collecting what it expands, in order. */
    private static List<TreePath> walk(DefaultMutableTreeNode root, ListingExpansionTracker tracker) {
        List<TreePath> collected = new ArrayList<>();
        TaskTreePanel.restoreExpansions(root, new TreePath(root.getPath()), null, tracker, collected::add);
        return collected;
    }

    @Test
    void aRecordedJiraGroupIsReExpandedByTheRestorationWalk() {
        DefaultMutableTreeNode root = modelTree(issuesProject(REGISTERED_ROOT, REGISTERED_ROOT, "g-sprint"));
        ListingExpansionTracker tracker = tracker();
        recordGroup(tracker, REGISTERED_ROOT, "g-sprint");

        List<TreePath> expanded = walk(root, tracker);

        assertTrue(expanded.contains(pathAt(root, 0, 0, 0)), "the recorded group is restored");
        assertTrue(expanded.contains(pathAt(root, 0)), "project rows stay eager");
        // A group request is not a section request: the unrequested Issues
        // section stays collapsed outside the Issues view.
        assertFalse(expanded.contains(pathAt(root, 0, 0)));
    }

    @Test
    void aGroupRecordedUnderAnotherProjectStaysCollapsed() {
        DefaultMutableTreeNode root = modelTree(issuesProject(REGISTERED_ROOT, REGISTERED_ROOT, "g-sprint"));
        ListingExpansionTracker tracker = tracker();
        recordGroup(tracker, OTHER_ROOT, "g-sprint");

        assertFalse(walk(root, tracker).contains(pathAt(root, 0, 0, 0)));

        // Two projects owning a group with the same id: the root is the
        // nearest project row's, carried down the walk.
        DefaultMutableTreeNode both = modelTree(issuesProject(REGISTERED_ROOT, REGISTERED_ROOT, "g-sprint"),
                issuesProject(OTHER_ROOT, OTHER_ROOT, "g-sprint"));
        List<TreePath> expanded = walk(both, tracker);
        assertFalse(expanded.contains(pathAt(both, 0, 0, 0)));
        assertTrue(expanded.contains(pathAt(both, 1, 0, 0)));
    }

    @Test
    void theGroupKeyRootIsTheProjectRowsNotTheGroupRowsOwn() {
        // A group whose own main root differs from its project row's: the key
        // is what treeWillExpand records, the nearest project row's root.
        DefaultMutableTreeNode root = modelTree(issuesProject(REGISTERED_ROOT, OTHER_ROOT, "g-sprint"));

        ListingExpansionTracker underProject = tracker();
        recordGroup(underProject, REGISTERED_ROOT, "g-sprint");
        assertTrue(walk(root, underProject).contains(pathAt(root, 0, 0, 0)));

        ListingExpansionTracker underGroupRow = tracker();
        recordGroup(underGroupRow, OTHER_ROOT, "g-sprint");
        assertFalse(walk(root, underGroupRow).contains(pathAt(root, 0, 0, 0)));
    }

    @Test
    void aJiraGroupWithNoProjectAboveItNeverExpands() {
        DefaultMutableTreeNode root = modelTree(groupRow(REGISTERED_ROOT, "g-sprint"));
        ListingExpansionTracker tracker = tracker();
        // Keys that would match if the guard were dropped (null root) or if
        // the key used the row's own main root.
        recordGroup(tracker, null, "g-sprint");
        recordGroup(tracker, REGISTERED_ROOT, "g-sprint");

        List<TreePath> expanded = assertDoesNotThrow(() -> walk(root, tracker));
        assertFalse(expanded.contains(pathAt(root, 0)), "must not expand a group with no project");

        // The section guard is unchanged: no project, no expansion.
        DefaultMutableTreeNode bareSection = modelTree(new Section(SectionKind.ISSUES,
                List.of(new Deferred("Reading…"))));
        assertFalse(walk(bareSection, tracker).contains(pathAt(bareSection, 0)));
    }

    // --- Collapse: a closed lazy node stays closed across rebuilds -------------------

    /** {@code ProjectRow > Section(ISSUES) > GitHubNode, JiraNode > JiraGroupRow(g-sprint)}. */
    private static ProjectRow issuesSourcesProject(Path projectRoot) {
        return projectRow(projectRoot, List.of(new Section(SectionKind.ISSUES, List.of(
                new GitHubNode(projectRoot, List.of(new Placeholder(TaskTreeModel.NOT_LOADED))),
                new JiraNode(projectRoot, List.of(groupRow(projectRoot, "g-sprint")))))));
    }

    /** Simulates the user's collapsing gesture at {@code path}. */
    private static void collapse(ListingExpansionTracker tracker, TreePath path) throws ExpandVetoException {
        tracker.treeWillCollapse(new TreeExpansionEvent(new JTree(), path));
    }

    /** {@link #collapse} for a lambda: the tracker never vetoes, so the checked exception is a failure. */
    private static void collapseQuietly(ListingExpansionTracker tracker, TreePath path) {
        assertDoesNotThrow(() -> collapse(tracker, path));
    }

    /** Simulates an expansion event at {@code path} (a user's gesture or the panel's restoration). */
    private static void expand(ListingExpansionTracker tracker, TreePath path) {
        assertDoesNotThrow(() -> tracker.treeWillExpand(new TreeExpansionEvent(new JTree(), path)));
    }

    /** Copies of all six record sets, in a fixed order, so before/after can be compared. */
    private static List<Set<?>> records(ListingExpansionTracker tracker) {
        return List.of(
                Set.copyOf(tracker.requested(SectionKind.WORKTREES)),
                Set.copyOf(tracker.requested(SectionKind.PULL_REQUESTS)),
                Set.copyOf(tracker.requestedIssueSections()),
                Set.copyOf(tracker.requestedGitHubIssues()),
                Set.copyOf(tracker.requestedJiraNodes()),
                Set.copyOf(tracker.requestedJiraGroups()));
    }

    /** Records {@code root} in every set, and its "g-sprint" group. */
    private static void recordEverything(ListingExpansionTracker tracker, Path root) {
        tracker.requested(SectionKind.WORKTREES).add(root);
        tracker.requested(SectionKind.PULL_REQUESTS).add(root);
        tracker.requestedIssueSections().add(root);
        tracker.requestedGitHubIssues().add(root);
        tracker.requestedJiraNodes().add(root);
        recordGroup(tracker, root, "g-sprint");
    }

    /**
     * {@code ProjectRow > [Section(ISSUES) > [GitHubNode, JiraNode > JiraGroupRow(g-sprint)],
     * Section(WORKTREES), Section(PULL_REQUESTS), Section(SESSIONS)]}.
     * Paths: project 0; ISSUES 0,0; GitHub 0,0,0; Jira 0,0,1; group 0,0,1,0;
     * WORKTREES 0,1; PULL_REQUESTS 0,2; SESSIONS 0,3.
     */
    private static ProjectRow everyCollapsibleKind(Path root) {
        return projectRow(root, List.of(
                new Section(SectionKind.ISSUES, List.of(
                        new GitHubNode(root, List.of(new Placeholder(TaskTreeModel.NOT_LOADED))),
                        new JiraNode(root, List.of(groupRow(root, "g-sprint"))))),
                new Section(SectionKind.WORKTREES, List.of(new Deferred("Reading…"))),
                new Section(SectionKind.PULL_REQUESTS, List.of(new Deferred("Reading…"))),
                new Section(SectionKind.SESSIONS, List.of(new Deferred("Reading…")))));
    }

    @Test
    void aClosedLazyNodeStaysClosedAcrossRebuilds() throws ExpandVetoException {
        DefaultMutableTreeNode root = modelTree(issuesSourcesProject(REGISTERED_ROOT));
        ListingExpansionTracker tracker = tracker();
        tracker.requestedGitHubIssues().add(REGISTERED_ROOT);
        assertTrue(walk(root, tracker).contains(pathAt(root, 0, 0, 0)));

        collapse(tracker, pathAt(root, 0, 0, 0));

        assertTrue(tracker.requestedGitHubIssues().isEmpty(),
                "the closed GitHub node's fetch trigger is forgotten, so the next rebuild keeps it closed");
        assertFalse(walk(root, tracker).contains(pathAt(root, 0, 0, 0)));
    }

    @Test
    void closingTheJiraNodeForgetsItsGroups() throws ExpandVetoException {
        DefaultMutableTreeNode root = modelTree(issuesSourcesProject(REGISTERED_ROOT));
        ListingExpansionTracker tracker = tracker();
        tracker.requestedJiraNodes().add(REGISTERED_ROOT);
        recordGroup(tracker, REGISTERED_ROOT, "g-sprint");

        collapse(tracker, pathAt(root, 0, 0, 1));

        assertTrue(tracker.requestedJiraNodes().isEmpty());
        assertTrue(tracker.requestedJiraGroups().isEmpty(),
                "a recorded group would re-open the closed Jira node through the restoration's ancestor expansion");
        List<TreePath> expanded = walk(root, tracker);
        assertFalse(expanded.contains(pathAt(root, 0, 0, 1)));
        assertFalse(expanded.contains(pathAt(root, 0, 0, 1, 0)));
    }

    @Test
    void closingTheIssuesSectionForgetsEveryLazyDescendant() throws ExpandVetoException {
        DefaultMutableTreeNode root = modelTree(issuesSourcesProject(REGISTERED_ROOT));
        ListingExpansionTracker tracker = tracker();
        tracker.requestedIssueSections().add(REGISTERED_ROOT);
        tracker.requestedGitHubIssues().add(REGISTERED_ROOT);
        tracker.requestedJiraNodes().add(REGISTERED_ROOT);
        recordGroup(tracker, REGISTERED_ROOT, "g-sprint");

        collapse(tracker, pathAt(root, 0, 0));

        assertTrue(tracker.requestedIssueSections().isEmpty());
        assertTrue(tracker.requestedGitHubIssues().isEmpty());
        assertTrue(tracker.requestedJiraNodes().isEmpty());
        assertTrue(tracker.requestedJiraGroups().isEmpty());
        assertTrue(walk(root, tracker).contains(pathAt(root, 0)),
                "the eager project row is unaffected");
        assertEquals(List.of(pathAt(root, 0)), walk(root, tracker));
    }

    @Test
    void closingOneProjectsNodeLeavesAnotherProjectsRecord() throws ExpandVetoException {
        DefaultMutableTreeNode root = modelTree(
                issuesSourcesProject(REGISTERED_ROOT), issuesSourcesProject(OTHER_ROOT));
        ListingExpansionTracker tracker = tracker();
        tracker.requestedGitHubIssues().add(REGISTERED_ROOT);
        tracker.requestedGitHubIssues().add(OTHER_ROOT);

        collapse(tracker, pathAt(root, 0, 0, 0));

        List<TreePath> expanded = walk(root, tracker);
        assertFalse(expanded.contains(pathAt(root, 0, 0, 0)));
        assertTrue(expanded.contains(pathAt(root, 1, 0, 0)));
    }

    @Test
    void closingTheWorktreesSectionForgetsItsRootOnly() throws ExpandVetoException {
        DefaultMutableTreeNode root = modelTree(projectRow(REGISTERED_ROOT, List.of(
                new Section(SectionKind.WORKTREES, List.of(new Deferred("Reading…"))),
                new Section(SectionKind.PULL_REQUESTS, List.of(new Deferred("Reading…"))))));
        ListingExpansionTracker tracker = tracker();
        tracker.requested(SectionKind.WORKTREES).add(REGISTERED_ROOT);
        tracker.requested(SectionKind.PULL_REQUESTS).add(REGISTERED_ROOT);

        collapse(tracker, pathAt(root, 0, 0));

        assertTrue(tracker.requested(SectionKind.WORKTREES).isEmpty());
        assertTrue(tracker.requested(SectionKind.PULL_REQUESTS).contains(REGISTERED_ROOT),
                "collapsing one section never touches a sibling's record");
        assertFalse(walk(root, tracker).contains(pathAt(root, 0, 0)));
        assertTrue(walk(root, tracker).contains(pathAt(root, 0, 1)));
    }

    @Test
    void aProgrammaticCollapseForgetsNothing() throws ExpandVetoException {
        DefaultMutableTreeNode root = modelTree(everyCollapsibleKind(REGISTERED_ROOT));
        ListingExpansionTracker tracker = tracker();
        recordEverything(tracker, REGISTERED_ROOT);
        List<Set<?>> before = records(tracker);

        // The panel's own restoration collapses every kind of lazy node: leaves
        // first, then the containers that would clear several sets at once.
        List<TreePath> paths = List.of(pathAt(root, 0, 0, 1, 0), pathAt(root, 0, 0, 0), pathAt(root, 0, 0, 1),
                pathAt(root, 0, 1), pathAt(root, 0, 2), pathAt(root, 0, 0));
        tracker.runProgrammatic(() -> paths.forEach(path -> collapseQuietly(tracker, path)));

        assertEquals(before, records(tracker), "a programmatic collapse is not intent: every record survives");

        // Control: the guard is cleared afterwards, so the same collapse by the user forgets.
        collapse(tracker, pathAt(root, 0, 0, 0));
        assertEquals(Set.of(), tracker.requestedGitHubIssues());
        collapse(tracker, pathAt(root, 0, 2));
        assertEquals(Set.of(), tracker.requested(SectionKind.PULL_REQUESTS));
    }

    @Test
    void aCollapseWithNoProjectAboveItForgetsNothing() {
        List<TaskNode> bare = List.of(
                groupRow(REGISTERED_ROOT, "g-sprint"),
                new GitHubNode(REGISTERED_ROOT, List.of(new Placeholder(TaskTreeModel.NOT_LOADED))),
                new JiraNode(REGISTERED_ROOT, List.of(groupRow(REGISTERED_ROOT, "g-sprint"))),
                new Section(SectionKind.ISSUES, List.of(new Deferred("Reading…"))),
                new Section(SectionKind.WORKTREES, List.of(new Deferred("Reading…"))),
                new Section(SectionKind.PULL_REQUESTS, List.of(new Deferred("Reading…"))));
        for (TaskNode node : bare) {
            String kind = node instanceof Section section ? section.kind().name() : node.getClass().getSimpleName();
            DefaultMutableTreeNode root = modelTree(node);
            ListingExpansionTracker tracker = tracker();
            recordEverything(tracker, REGISTERED_ROOT);
            // The key a dropped guard would build for a group: GroupKey(null, id).
            recordGroup(tracker, null, "g-sprint");
            List<Set<?>> before = records(tracker);

            assertDoesNotThrow(() -> collapse(tracker, pathAt(root, 0)), kind);

            assertEquals(before, records(tracker), kind + " with no project above it must forget nothing");
        }
    }

    @Test
    void closingAJiraGroupForgetsOnlyThatGroup() throws ExpandVetoException {
        // The group rows' own main root (OTHER_ROOT) deliberately differs from
        // their project row's (REGISTERED_ROOT): the key is the project row's.
        DefaultMutableTreeNode root = modelTree(
                projectRow(REGISTERED_ROOT, List.of(new Section(SectionKind.ISSUES, List.of(
                        new JiraNode(REGISTERED_ROOT, List.of(
                                groupRow(OTHER_ROOT, "g-sprint"), groupRow(OTHER_ROOT, "g-other"))))))),
                issuesSourcesProject(OTHER_ROOT));
        ListingExpansionTracker tracker = tracker();
        tracker.requestedIssueSections().add(REGISTERED_ROOT);
        tracker.requestedGitHubIssues().add(REGISTERED_ROOT);
        tracker.requestedJiraNodes().add(REGISTERED_ROOT);
        tracker.requestedJiraNodes().add(OTHER_ROOT);
        recordGroup(tracker, REGISTERED_ROOT, "g-sprint");
        recordGroup(tracker, REGISTERED_ROOT, "g-other");
        recordGroup(tracker, OTHER_ROOT, "g-sprint");

        collapse(tracker, pathAt(root, 0, 0, 0, 0));

        assertEquals(Set.of(new ListingExpansionTracker.GroupKey(REGISTERED_ROOT, "g-other"),
                        new ListingExpansionTracker.GroupKey(OTHER_ROOT, "g-sprint")),
                tracker.requestedJiraGroups(),
                "only the closed group's record, under its project row's root, is forgotten");
        assertEquals(Set.of(REGISTERED_ROOT, OTHER_ROOT), tracker.requestedJiraNodes(),
                "closing a group is not closing its Jira node");
        assertEquals(Set.of(REGISTERED_ROOT), tracker.requestedIssueSections());
        assertEquals(Set.of(REGISTERED_ROOT), tracker.requestedGitHubIssues());

        List<TreePath> expanded = walk(root, tracker);
        assertFalse(expanded.contains(pathAt(root, 0, 0, 0, 0)), "the closed group stays closed");
        assertTrue(expanded.contains(pathAt(root, 0, 0, 0, 1)), "its sibling group is still restored");
    }

    @Test
    void closingThePullRequestsSectionForgetsItsRootOnly() throws ExpandVetoException {
        DefaultMutableTreeNode root = modelTree(projectRow(REGISTERED_ROOT, List.of(
                new Section(SectionKind.WORKTREES, List.of(new Deferred("Reading…"))),
                new Section(SectionKind.PULL_REQUESTS, List.of(new Deferred("Reading…"))))));
        ListingExpansionTracker tracker = tracker();
        tracker.requested(SectionKind.WORKTREES).add(REGISTERED_ROOT);
        tracker.requested(SectionKind.PULL_REQUESTS).add(REGISTERED_ROOT);
        tracker.requested(SectionKind.PULL_REQUESTS).add(OTHER_ROOT);

        collapse(tracker, pathAt(root, 0, 1));

        assertEquals(Set.of(OTHER_ROOT), tracker.requested(SectionKind.PULL_REQUESTS));
        assertEquals(Set.of(REGISTERED_ROOT), tracker.requested(SectionKind.WORKTREES),
                "collapsing one section never touches a sibling's record");
    }

    @Test
    void collapsingAProjectRowOrAnUnlistedSectionForgetsNothing() throws ExpandVetoException {
        DefaultMutableTreeNode root = modelTree(everyCollapsibleKind(REGISTERED_ROOT));
        ListingExpansionTracker tracker = tracker();
        recordEverything(tracker, REGISTERED_ROOT);
        List<Set<?>> before = records(tracker);

        collapse(tracker, pathAt(root, 0));     // the ProjectRow: treeWillCollapse's default
        collapse(tracker, pathAt(root, 0, 3));  // Section(SESSIONS): closeSection's default

        assertEquals(before, records(tracker));
    }

    @Test
    void programmaticExpansionOfTheIssueSourcesRecordsNothing() {
        DefaultMutableTreeNode root = modelTree(issuesSourcesProject(REGISTERED_ROOT));
        List<Boolean> rebuilds = new ArrayList<>();
        ListingExpansionTracker tracker = new ListingExpansionTracker(
                changed -> rebuilds.add(changed), TaskTreePanel::projectRootOf);

        tracker.runProgrammatic(() -> {
            expand(tracker, pathAt(root, 0, 0, 0));   // GitHubNode
            expand(tracker, pathAt(root, 0, 0, 1));   // JiraNode
        });
        assertEquals(Set.of(), tracker.requestedGitHubIssues());
        assertEquals(Set.of(), tracker.requestedJiraNodes());
        assertTrue(rebuilds.isEmpty());

        // The user's own opening records both; only the GitHub node is a fetch trigger.
        expand(tracker, pathAt(root, 0, 0, 0));
        expand(tracker, pathAt(root, 0, 0, 1));
        assertEquals(Set.of(REGISTERED_ROOT), tracker.requestedGitHubIssues());
        assertEquals(Set.of(REGISTERED_ROOT), tracker.requestedJiraNodes());
        assertEquals(List.of(Boolean.FALSE), rebuilds);
    }

    /**
     * The {@code r != null} guard on the Worktrees / Pull requests expansion branch,
     * which {@link #aSectionWithNoProjectAboveItRecordsNothing} (an Issues section) cannot reach.
     */
    @Test
    void aListingSectionWithNoProjectAboveItRecordsNothing() {
        for (SectionKind kind : List.of(SectionKind.WORKTREES, SectionKind.PULL_REQUESTS)) {
            DefaultMutableTreeNode root = modelTree(new Section(kind, List.of(new Deferred("Reading…"))));
            List<Boolean> rebuilds = new ArrayList<>();
            ListingExpansionTracker tracker = new ListingExpansionTracker(
                    changed -> rebuilds.add(changed), TaskTreePanel::projectRootOf);

            expand(tracker, pathAt(root, 0));

            assertTrue(tracker.requested(SectionKind.WORKTREES).isEmpty(), kind.name());
            assertTrue(tracker.requested(SectionKind.PULL_REQUESTS).isEmpty(), kind.name());
            assertTrue(rebuilds.isEmpty(), kind.name());
        }
    }

    @Test
    void restorationKeepsEagerAndListingRulesInParentFirstOrder() {
        DefaultMutableTreeNode root = modelTree(projectRow(REGISTERED_ROOT, List.of(
                new Section(SectionKind.SESSIONS, List.of(new Deferred("Reading…"))),
                new Section(SectionKind.WORKTREES, List.of(new Deferred("Reading…"))),
                new Section(SectionKind.ISSUES, List.of(new Deferred("Reading…"))),
                new Section(SectionKind.PULL_REQUESTS, List.of(new Deferred("Reading…"))),
                new WorktreeGroup(LINKED_WORKTREE, "fix", List.of()),
                new StaleWorktrees(List.of(new IdleWorktree(LINKED_WORKTREE, "fix", Optional.empty(),
                        false, false))))));
        ListingExpansionTracker tracker = tracker();
        tracker.requested(SectionKind.WORKTREES).add(REGISTERED_ROOT);

        assertEquals(List.of(pathAt(root, 0), pathAt(root, 0, 0), pathAt(root, 0, 1),
                        pathAt(root, 0, 4), pathAt(root, 0, 5)),
                walk(root, tracker));
    }

    @Test
    void theDefinedGroupsAreEveryRootTimesEveryDefinedId() {
        assertEquals(Set.of(new ListingExpansionTracker.GroupKey(REGISTERED_ROOT, "g1"),
                        new ListingExpansionTracker.GroupKey(REGISTERED_ROOT, "g2"),
                        new ListingExpansionTracker.GroupKey(OTHER_ROOT, "g3")),
                ListingExpansionTracker.definedJiraGroups(Map.of(
                        REGISTERED_ROOT, List.of(listing("g1"), listing("g2")),
                        OTHER_ROOT, List.of(listing("g3")))));
        assertTrue(ListingExpansionTracker.definedJiraGroups(Map.of()).isEmpty());
    }

    @Test
    void aCompletedGatherForgetsGroupsItNoLongerDefines() {
        ListingExpansionTracker tracker = tracker();
        recordGroup(tracker, REGISTERED_ROOT, "g1");
        recordGroup(tracker, REGISTERED_ROOT, "g2");
        recordGroup(tracker, OTHER_ROOT, "g3");
        Set<ListingExpansionTracker.GroupKey> before = tracker.requestedJiraGroups();

        // g2 was deleted; OTHER_ROOT was unregistered or its Jira disabled.
        Map<Path, List<JiraGroups.Listing>> defined = Map.of(REGISTERED_ROOT, List.of(listing("g1")));
        tracker.retainDefinedJiraGroups(defined);

        assertEquals(Set.of(new ListingExpansionTracker.GroupKey(REGISTERED_ROOT, "g1")),
                tracker.requestedJiraGroups());
        assertTrue(tracker.requestedJiraGroups().size() <= 1, "bounded by the defined groups");
        assertSame(before, tracker.requestedJiraGroups(), "the live set is pruned, not replaced");
    }

    @Test
    void aGatherWithNoDefinedGroupsForgetsEveryGroup() {
        ListingExpansionTracker tracker = tracker();
        recordGroup(tracker, REGISTERED_ROOT, "g1");
        recordGroup(tracker, OTHER_ROOT, "g2");

        tracker.retainDefinedJiraGroups(Map.of());

        assertTrue(tracker.requestedJiraGroups().isEmpty());
    }

    @Test
    void aStillDefinedGroupStaysRecordedWhateverItsListing() {
        ListingExpansionTracker tracker = tracker();
        recordGroup(tracker, REGISTERED_ROOT, "g1");
        ListingExpansionTracker.GroupKey key = new ListingExpansionTracker.GroupKey(REGISTERED_ROOT, "g1");

        // Edited in place (same id, new name and JQL), its query not run yet.
        tracker.retainDefinedJiraGroups(Map.of(REGISTERED_ROOT, List.of(new JiraGroups.Listing(
                new JiraGroup("g1", "Renamed", "project = OTHER"), Optional.empty()))));
        assertTrue(tracker.requestedJiraGroups().contains(key));

        // Its query ran and failed.
        tracker.retainDefinedJiraGroups(Map.of(REGISTERED_ROOT, List.of(new JiraGroups.Listing(
                new JiraGroup("g1", "Renamed", "project = OTHER"),
                Optional.of(new JiraIssueListing.Failed("Jira refused the credentials (HTTP 401)"))))));
        assertTrue(tracker.requestedJiraGroups().contains(key));
    }

    @Test
    void anEvictedGroupIsNotReExpandedButAKeptOneIs() {
        DefaultMutableTreeNode root = modelTree(issuesProject(REGISTERED_ROOT, REGISTERED_ROOT, "g1", "g2"));
        ListingExpansionTracker tracker = tracker();
        recordGroup(tracker, REGISTERED_ROOT, "g1");
        recordGroup(tracker, REGISTERED_ROOT, "g2");

        tracker.retainDefinedJiraGroups(Map.of(REGISTERED_ROOT, List.of(listing("g1"))));
        List<TreePath> expanded = walk(root, tracker);

        assertTrue(expanded.contains(pathAt(root, 0, 0, 0)));
        assertFalse(expanded.contains(pathAt(root, 0, 0, 1)));
    }

    /**
     * Best-effort smoke check, not deterministic: pruning on the rebuild
     * thread while the EDT records opens must neither throw nor lose a
     * defined group. A plain {@code HashSet} would likely fail it; the
     * concurrent field type is what actually guarantees this.
     */
    @Test
    void pruningRacesUserOpensWithoutLosingADefinedGroup() throws InterruptedException {
        ListingExpansionTracker tracker = tracker();
        List<JiraGroups.Listing> listings = new ArrayList<>();
        Set<ListingExpansionTracker.GroupKey> definedKeys = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            listings.add(listing("d" + i));
            definedKeys.add(new ListingExpansionTracker.GroupKey(REGISTERED_ROOT, "d" + i));
        }
        Map<Path, List<JiraGroups.Listing>> defined = Map.of(REGISTERED_ROOT, listings);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread opens = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 200; i++) {
                    recordGroup(tracker, REGISTERED_ROOT, "d" + i);
                    recordGroup(tracker, OTHER_ROOT, "u" + i);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        Thread prunes = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 1_000; i++) {
                    tracker.retainDefinedJiraGroups(defined);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        opens.start();
        prunes.start();
        start.countDown();
        opens.join(TimeUnit.SECONDS.toMillis(30));
        prunes.join(TimeUnit.SECONDS.toMillis(30));

        assertFalse(opens.isAlive(), "the opening thread finished");
        assertFalse(prunes.isAlive(), "the pruning thread finished");
        if (failure.get() != null) {
            throw new AssertionError("a worker thread failed", failure.get());
        }
        tracker.retainDefinedJiraGroups(defined);
        assertEquals(definedKeys, tracker.requestedJiraGroups());
    }
}
