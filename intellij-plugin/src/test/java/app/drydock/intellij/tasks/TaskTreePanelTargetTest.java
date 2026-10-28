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
import app.drydock.intellij.tasks.TaskNode.Deferred;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.IssueRow;
import app.drydock.intellij.tasks.TaskNode.JiraIssueRow;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.SectionKind;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.tasks.TaskTreeModel.ViewFilter;
import app.drydock.projects.SharedProject;
import org.junit.jupiter.api.Test;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void theListingViewExpandsItsOwnSectionsWithoutARequest() {
        assertTrue(TaskTreePanel.expandsListing(SectionKind.ISSUES, ViewFilter.ISSUES, false));
        assertTrue(TaskTreePanel.expandsListing(SectionKind.PULL_REQUESTS, ViewFilter.PULL_REQUESTS, false));
        assertTrue(TaskTreePanel.expandsListing(SectionKind.ISSUES, ViewFilter.ISSUES, true));
        assertTrue(TaskTreePanel.expandsListing(SectionKind.PULL_REQUESTS, ViewFilter.PULL_REQUESTS, true));
    }

    @Test
    void aListingSectionFollowsTheRequestInEveryOtherView() {
        for (ViewFilter view : ViewFilter.values()) {
            if (view == ViewFilter.ISSUES) {
                continue;
            }
            assertFalse(TaskTreePanel.expandsListing(SectionKind.ISSUES, view, false),
                    view + " should not expand Issues sections on its own");
            assertTrue(TaskTreePanel.expandsListing(SectionKind.ISSUES, view, true),
                    view + " keeps the user's expansion");
        }
        for (ViewFilter view : ViewFilter.values()) {
            if (view == ViewFilter.PULL_REQUESTS) {
                continue;
            }
            assertFalse(TaskTreePanel.expandsListing(SectionKind.PULL_REQUESTS, view, false),
                    view + " should not expand Pull Requests sections on its own");
            assertTrue(TaskTreePanel.expandsListing(SectionKind.PULL_REQUESTS, view, true),
                    view + " keeps the user's expansion");
        }
        // The listing views never open each other's sections for free.
        assertFalse(TaskTreePanel.expandsListing(SectionKind.ISSUES, ViewFilter.PULL_REQUESTS, false));
        assertFalse(TaskTreePanel.expandsListing(SectionKind.PULL_REQUESTS, ViewFilter.ISSUES, false));
    }

    @Test
    void aSectionWithNoViewOfItsOwnFollowsTheRequestAlone() {
        assertFalse(TaskTreePanel.expandsListing(SectionKind.WORKTREES, ViewFilter.ALL, false));
        assertFalse(TaskTreePanel.expandsListing(SectionKind.WORKTREES, ViewFilter.ISSUES, false));
        assertFalse(TaskTreePanel.expandsListing(SectionKind.WORKTREES, ViewFilter.PULL_REQUESTS, false));
        assertTrue(TaskTreePanel.expandsListing(SectionKind.WORKTREES, ViewFilter.ALL, true));
        assertTrue(TaskTreePanel.expandsListing(SectionKind.WORKTREES, ViewFilter.ISSUES, true));
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
        assertTrue(tracker.requested(SectionKind.ISSUES).isEmpty());
        assertTrue(tracker.requested(SectionKind.PULL_REQUESTS).isEmpty());
        assertTrue(rebuilds.isEmpty());
    }

    @Test
    void manualExpansionThroughTheRealTreeEventRecordsIntent() {
        Map<String, TreePath> sections = new HashMap<>();
        List<Boolean> rebuilds = new ArrayList<>();
        ListingExpansionTracker tracker = new ListingExpansionTracker(
                changed -> rebuilds.add(changed), TaskTreePanel::projectRootOf);
        JTree tree = listingTree(tracker, sections);

        tree.expandPath(sections.get("profiling-backend:ISSUES"));

        assertEquals(Set.of(REGISTERED_ROOT), tracker.requested(SectionKind.ISSUES));
        assertEquals(List.of(Boolean.FALSE), rebuilds);
        assertTrue(tracker.requested(SectionKind.PULL_REQUESTS).isEmpty());
        // Re-opening a section the user already opened asks for nothing new.
        tree.collapsePath(sections.get("profiling-backend:ISSUES"));
        tree.expandPath(sections.get("profiling-backend:ISSUES"));
        assertEquals(List.of(Boolean.FALSE), rebuilds);
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
        assertEquals(Set.of(REGISTERED_ROOT), tracker.requested(SectionKind.ISSUES));
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

        assertTrue(tracker.requested(SectionKind.ISSUES).isEmpty());
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
        public void requestListing(SectionKind kind, Path root) {
            calls.add("listing:" + kind + ":" + root);
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
    void anIssuesSectionContextMenuRefreshTouchesOnlyThatProject() {
        RecordingRefresh target = new RecordingRefresh();

        TaskTreePanel.refresh(target, true, section(SectionKind.ISSUES), REGISTERED_ROOT);

        assertEquals(List.of("issues:" + REGISTERED_ROOT,
                "jiraIssues:" + REGISTERED_ROOT,
                "listing:ISSUES:" + REGISTERED_ROOT, "rebuild:false"), target.calls);
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

    @Test
    void issueStringsDoNotReadAsGitHubOnly() {
        // AC21: neither the ISSUES tooltip nor its empty text keeps the
        // GitHub-only phrasing. The tooltip names both sources, so the guard
        // is the old "GitHub issues" wording; the empty text names no source.
        // The strings live on the ViewFilter enum since the ribbon refactor.
        assertEquals("Show only open issues (GitHub and Jira)",
                ViewFilter.ISSUES.tooltip());
        assertFalse(ViewFilter.ISSUES.tooltip().contains("GitHub issues"));
        assertEquals("No open issues.", TaskTreePanel.emptyText("", ViewFilter.ISSUES));
        assertFalse(TaskTreePanel.emptyText("", ViewFilter.ISSUES).contains("GitHub"));
    }
}
