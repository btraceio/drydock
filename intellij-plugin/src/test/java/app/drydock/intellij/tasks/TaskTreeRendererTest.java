package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.JiraIssue;
import app.drydock.intellij.tasks.TaskNode.Deferred;
import app.drydock.intellij.tasks.TaskNode.GitHubNode;
import app.drydock.intellij.tasks.TaskNode.IssueRow;
import app.drydock.intellij.tasks.TaskNode.JiraGroupRow;
import app.drydock.intellij.tasks.TaskNode.JiraIssueRow;
import app.drydock.intellij.tasks.TaskNode.JiraNode;
import app.drydock.intellij.tasks.TaskNode.ListProblem;
import app.drydock.intellij.tasks.TaskNode.Placeholder;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.SectionKind;
import app.drydock.intellij.tasks.TaskNode.TaskRow;

import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskTreeRendererTest {

    private static final Path DIR = Path.of("/wt/dd-jmc-fix");

    private static final Path ROOT = Path.of("/wt/dd-main");

    private static OpenIssue issue42() {
        return new OpenIssue(42, "Fix allocation accounting", Optional.of("https://github.com/o/r/issues/42"),
                List.of("profiling"), List.of(), Optional.empty());
    }

    private static JiraIssue jiraProj123() {
        return new JiraIssue("PROJ-123", "Fix allocation accounting",
                "https://acme.atlassian.net/browse/PROJ-123", List.of("profiling"), Optional.empty());
    }

    private static TaskRow row(boolean running, TaskPlacement placement) {
        return row(running, placement, true);
    }

    private static TaskRow row(boolean running, TaskPlacement placement, boolean resumable) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(),
                "Fix chunk memory growth", AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(DIR), Instant.EPOCH).status(SessionStatus.INACTIVE).build();
        return new TaskRow(session, Optional.empty(), running, Optional.of("fix/chunk_memory"), placement, false,
                false, resumable);
    }

    @Test
    void aSingleClickActsOnlyOnTheLinkText() {
        // A stray click while selecting the "isn't in Drydock · Add" row must
        // not register the project on the shared list.
        assertTrue(TaskTreeRenderer.clickActs(true, true, 1));
        assertFalse(TaskTreeRenderer.clickActs(true, false, 1));
        assertTrue(TaskTreeRenderer.clickActs(true, false, 2), "a double-click on the row is deliberate");
        assertFalse(TaskTreeRenderer.clickActs(true, true, 0), "a synthetic click with no count is not a click");
    }

    @Test
    void onlyTheEditorsActiveSessionRowIsHighlighted() {
        TaskRow active = row(true, new TaskPlacement.Here());
        TaskRow other = row(true, new TaskPlacement.Here());
        ManagedSessionId id = active.session().id();

        assertTrue(TaskTreeRenderer.highlightsAsActive(active, id, false));
        assertFalse(TaskTreeRenderer.highlightsAsActive(other, id, false));
        assertFalse(TaskTreeRenderer.highlightsAsActive(active, id, true), "the selection fill wins");
        assertFalse(TaskTreeRenderer.highlightsAsActive(active, null, false), "no console tab selected");
        assertFalse(TaskTreeRenderer.highlightsAsActive(new Placeholder("x"), id, false));
    }

    @Test
    void aSecondaryClickNeverActivatesTheRow() {
        assertFalse(TaskTreeRenderer.clickActs(false, true, 1),
                "right-clicking Start must open the context menu, not start a task");
        assertFalse(TaskTreeRenderer.clickActs(false, false, 2),
                "a secondary double-click must not switch tasks");
    }

    @Test
    void aRunningRowSaysWhereItIsShown() {
        assertEquals("fix/chunk_memory · here · Pi",
                TaskTreeRenderer.secondary(row(true, new TaskPlacement.Here()), "Pi", Duration.ZERO));
    }

    @Test
    void aNotShownRowOffersShowHere() {
        TaskRow row = row(true, new TaskPlacement.NotShown());

        assertEquals("fix/chunk_memory · not shown · Pi", TaskTreeRenderer.secondary(row, "Pi", Duration.ZERO));
        assertEquals(Optional.of("Show here"), TaskTreeRenderer.link(row));
    }

    @Test
    void anIdleRowFromAnotherWorktreeOffersResumeHereWhenResumable() {
        assertEquals(Optional.of("Resume here"),
                TaskTreeRenderer.link(row(false, new TaskPlacement.Idle(false), true)));
    }

    @Test
    void anIdleRowFromAnotherWorktreeOffersStartHereWhenNotResumable() {
        // "Resume" on a task with no conversation left to resume into is a
        // broken promise: the click would start a fresh one, so say that.
        assertEquals(Optional.of("Start here"),
                TaskTreeRenderer.link(row(false, new TaskPlacement.Idle(false), false)));
    }

    @Test
    void anIdleRowFromThisWorktreeKeepsItsMvpMeta() {
        TaskRow row = row(false, new TaskPlacement.Idle(true));

        assertEquals("fix/chunk_memory · this window · Pi", TaskTreeRenderer.secondary(row, "Pi", Duration.ZERO));
        assertEquals(Optional.empty(), TaskTreeRenderer.link(row));
    }

    @Test
    void anIdleRowElsewhereKeepsItsMvpMeta() {
        assertEquals("⑂ fix/chunk_memory · Pi · 5m",
                TaskTreeRenderer.secondary(row(false, new TaskPlacement.Idle(false)), "Pi", Duration.ofMinutes(5)));
    }

    @Test
    void aRowShownElsewhereOffersNoInlineLink() {
        assertEquals(Optional.empty(), TaskTreeRenderer.link(row(true, new TaskPlacement.OwnWindow())));
    }

    @Test
    void githubAndJiraIssueRowsCarryDifferentSourceIcons() {
        Optional<Icon> github = TaskTreeRenderer.sourceIcon(new IssueRow(ROOT, issue42()));
        Optional<Icon> jira = TaskTreeRenderer.sourceIcon(new JiraIssueRow(ROOT, jiraProj123()));

        assertTrue(github.isPresent(), "a GitHub issue row shows GitHub's vendor icon");
        assertTrue(jira.isPresent(), "a Jira issue row shows the bundled ticket glyph");
        assertNotEquals(github.orElseThrow(), jira.orElseThrow(),
                "the two sources must be told apart at a glance (AC20)");
        assertTrue(TaskTreeRenderer.sourceIcon(new TaskNode.Deferred("Loading issues…")).isEmpty(),
                "only issue rows carry a source icon");
    }

    @Test
    void aGithubIssueRowSaysHashNumberAndTitle() {
        assertEquals("#42 Fix allocation accounting", TaskTreeRenderer.issueText(issue42()));
    }

    @Test
    void aJiraIssueRowSaysKeyAndSummary() {
        assertEquals("PROJ-123 Fix allocation accounting", TaskTreeRenderer.issueText(jiraProj123()));
    }

    private static final JiraGroup SPRINT = new JiraGroup("g1", "Sprint", "project = DRY");

    private static JiraIssueRow jiraRow(String key) {
        return new JiraIssueRow(ROOT, new JiraIssue(key, "Fix allocation accounting",
                "https://acme.atlassian.net/browse/" + key, List.of(), Optional.empty()));
    }

    private static JiraGroupRow jiraGroup(TaskNode... children) {
        return new JiraGroupRow(ROOT, SPRINT, List.of(children));
    }

    @Test
    void aFailedJiraGroupShowsNoIssueCount() {
        assertEquals(Optional.empty(), TaskTreeRenderer.issueCountBadge(
                jiraGroup(new ListProblem("Jira group \"Sprint\": boom"))));
    }

    @Test
    void aLoadingJiraGroupShowsNoIssueCount() {
        assertEquals(Optional.empty(), TaskTreeRenderer.issueCountBadge(jiraGroup(new Deferred("Loading…"))));
        // "Not loaded" — the group the user has never opened — is not an
        // answer either: no badge beats a false "0 issues".
        assertEquals(Optional.empty(), TaskTreeRenderer.issueCountBadge(
                jiraGroup(new Placeholder(TaskTreeModel.NOT_LOADED))));
    }

    @Test
    void aListedJiraGroupCountsItsIssues() {
        assertEquals(Optional.of("1 issue"), TaskTreeRenderer.issueCountBadge(
                jiraGroup(new JiraIssueRow(ROOT, jiraProj123()))));
        assertEquals(Optional.of("2 issues"), TaskTreeRenderer.issueCountBadge(
                jiraGroup(jiraRow("PROJ-1"), jiraRow("PROJ-2"))));
    }

    @Test
    void anEmptyListedJiraGroupSaysZeroIssues() {
        assertEquals(Optional.of("0 issues"), TaskTreeRenderer.issueCountBadge(
                jiraGroup(new Placeholder("No issues"))));
    }

    @Test
    void aMixedJiraGroupShowsNoIssueCount() {
        assertEquals(Optional.empty(), TaskTreeRenderer.issueCountBadge(
                jiraGroup(jiraRow("PROJ-1"), new ListProblem("Jira group \"Sprint\": boom"))));
    }

    private static GitHubNode githubNode(TaskNode... children) {
        return new GitHubNode(ROOT, List.of(children));
    }

    private static JiraNode jiraNodeOf(TaskNode... groups) {
        return new JiraNode(ROOT, List.of(groups));
    }

    @Test
    void anUnloadedGitHubNodeSectionShowsNoCount() {
        assertEquals(Optional.empty(), TaskTreeRenderer.sectionCountBadge(
                new Section(SectionKind.ISSUES, List.of(githubNode(new Placeholder(TaskTreeModel.NOT_LOADED))))));
    }

    @Test
    void aListedGitHubNodeCountsItsIssues() {
        assertEquals(Optional.of("1"), TaskTreeRenderer.sectionCountBadge(
                new Section(SectionKind.ISSUES, List.of(githubNode(new IssueRow(ROOT, issue42()))))));
    }

    @Test
    void aSectionCountsTheIssuesInsideItsJiraNode() {
        assertEquals(Optional.of("2"), TaskTreeRenderer.sectionCountBadge(
                new Section(SectionKind.ISSUES, List.of(jiraNodeOf(jiraGroup(jiraRow("PROJ-1"), jiraRow("PROJ-2")))))));
    }

    @Test
    void aSectionWithALoadingJiraGroupShowsNoCount() {
        assertEquals(Optional.empty(), TaskTreeRenderer.sectionCountBadge(
                new Section(SectionKind.ISSUES, List.of(jiraNodeOf(jiraGroup(new Deferred("Loading…")))))));
        assertEquals(Optional.empty(), TaskTreeRenderer.sectionCountBadge(
                new Section(SectionKind.ISSUES, List.of(githubNode(new IssueRow(ROOT, issue42())),
                        jiraNodeOf(jiraGroup(new Deferred("Loading…")))))));
    }

    @Test
    void aSectionDoesNotCountProblemRowsBesideItems() {
        assertEquals(Optional.of("1"), TaskTreeRenderer.sectionCountBadge(
                new Section(SectionKind.ISSUES, List.of(githubNode(new ListProblem("GitHub issues: boom")),
                        jiraNodeOf(jiraGroup(jiraRow("PROJ-1")))))));
        assertEquals(Optional.of("1"), TaskTreeRenderer.sectionCountBadge(
                new Section(SectionKind.ISSUES, List.of(jiraNodeOf(jiraGroup(new Placeholder("No issues"))),
                        githubNode(new IssueRow(ROOT, issue42()))))));
    }

    @Test
    void aFailedOnlySectionShowsNoCount() {
        assertEquals(Optional.empty(), TaskTreeRenderer.sectionCountBadge(
                new Section(SectionKind.PULL_REQUESTS, List.of(new ListProblem("GitHub pull requests: boom")))));
    }

    @Test
    void aSectionWhoseOnlyJiraGroupFailedShowsNoCount() {
        assertEquals(Optional.empty(), TaskTreeRenderer.sectionCountBadge(
                new Section(SectionKind.ISSUES, List.of(
                        jiraGroup(new ListProblem("Jira group \"Sprint\": boom"))))));
    }

    @Test
    void anEmptyListedJiraGroupSectionSaysZero() {
        assertEquals(Optional.of("0"), TaskTreeRenderer.sectionCountBadge(
                new Section(SectionKind.ISSUES, List.of(jiraNodeOf(jiraGroup(new Placeholder("No issues")))))));
        assertEquals(Optional.of("1"), TaskTreeRenderer.sectionCountBadge(
                new Section(SectionKind.ISSUES, List.of(githubNode(new IssueRow(ROOT, issue42()))))));
    }

    @Test
    void aJiraRowTooltipDoesNotLeakOntoOtherRows() {
        // One shared renderer paints every row: the JQL tooltip set for a
        // Jira group row must not surface when hovering any other node.
        TaskTreeRenderer renderer = new TaskTreeRenderer();
        JTree tree = new JTree();
        renderer.customizeCellRenderer(tree, new DefaultMutableTreeNode(
                jiraGroup(new Placeholder(TaskTreeModel.NOT_LOADED))), false, false, false, 0, false);
        assertEquals("Jira group · " + SPRINT.jql(), renderer.getToolTipText());

        renderer.customizeCellRenderer(tree, new DefaultMutableTreeNode(
                githubNode(new Placeholder(TaskTreeModel.NOT_LOADED))), false, false, false, 0, false);
        assertNull(renderer.getToolTipText(), "the shared renderer must not leak the Jira group's JQL tooltip");
    }
}
