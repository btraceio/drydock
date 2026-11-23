package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.GhCliService;
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.GhCliService.CiFailure;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.GhCliService.PrInfo.PrLifecycle;
import app.drydock.git.JiraIssue;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskStatus;
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

import com.intellij.icons.AllIcons;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.icons.RowIcon;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.awt.GraphicsEnvironment;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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

    private static GhCliService.OpenPullRequest openPr(int number, String title, String head) {
        return new GhCliService.OpenPullRequest(number, title, head, "main", false,
                Optional.of("someone"), Optional.of("https://github.com/o/r/pull/" + number));
    }

    private static TaskDocument document(TaskId id) {
        return new TaskDocument(id, RepositoryId.newId(), "Fix allocation profile", TaskStatus.STARTED, null,
                Optional.of(Instant.EPOCH), List.of(), List.of(), "", "");
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
    void anIdleRowElsewhereKeepsItsMvpMeta() {
        assertEquals("⑂ fix/chunk_memory · Pi · 5m",
                TaskTreeRenderer.secondary(row(false, new TaskPlacement.Idle(false)), "Pi", Duration.ofMinutes(5)));
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

    @Test
    void jiraPriorityNamesMapToBadgeKindsAndCustomOnesStayNeutral() {
        assertEquals(Optional.of(TaskTreeRenderer.PriorityKind.HIGH), TaskTreeRenderer.priorityKind("Highest"));
        assertEquals(Optional.of(TaskTreeRenderer.PriorityKind.HIGH), TaskTreeRenderer.priorityKind("high"));
        assertEquals(Optional.of(TaskTreeRenderer.PriorityKind.MEDIUM), TaskTreeRenderer.priorityKind("Medium"));
        assertEquals(Optional.of(TaskTreeRenderer.PriorityKind.LOW), TaskTreeRenderer.priorityKind("Lowest"));
        assertEquals(Optional.of(TaskTreeRenderer.PriorityKind.NEUTRAL), TaskTreeRenderer.priorityKind("Critical"),
                "a site's custom priority wears the neutral mark, labelled as reported");
        assertEquals(Optional.empty(), TaskTreeRenderer.priorityKind("   "));
    }

    @Test
    void aJiraRowBadgesItsTicketMarkWithItsPriorityAndAnUnprioritizedOneKeepsItAlone() {
        Icon badged = TaskTreeRenderer.jiraRowIcon(new JiraIssue("PROJ-1", "Fix allocation accounting",
                "https://acme.atlassian.net/browse/PROJ-1", List.of(), Optional.empty(), Optional.of("High")));
        Icon plain = TaskTreeRenderer.jiraRowIcon(jiraProj123());

        assertSame(TaskTreeRenderer.JIRA_ICON, plain,
                "an issue without a priority keeps exactly the ticket mark");
        assertNotEquals(TaskTreeRenderer.JIRA_ICON, badged);
    }

    @Test
    void aMirroredTaskWearsThePriorityItsJiraListingAnswered() {
        Map<String, Optional<String>> priorities = Map.of(
                "PROJ-7", Optional.of("Highest"),
                "OPS_2-41", Optional.empty());

        assertEquals(Optional.of(TaskTreeRenderer.PriorityKind.HIGH), TaskTreeRenderer.jiraMirrorPriority(
                List.of("https://acme.atlassian.net/browse/PROJ-7"), priorities));
        assertEquals(Optional.empty(), TaskTreeRenderer.jiraMirrorPriority(
                List.of("https://github.com/o/r/issues/12"), priorities),
                "a GitHub mirror names no Jira ticket");
        assertEquals(Optional.empty(), TaskTreeRenderer.jiraMirrorPriority(
                List.of("https://jira.example.com/browse/OPS_2-41"), priorities),
                "a listed ticket without a priority badges nothing");
        assertEquals(Optional.empty(), TaskTreeRenderer.jiraMirrorPriority(
                List.of("https://acme.atlassian.net/browse/MISS-1"), priorities),
                "a ticket no shown listing answered badges nothing");
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

    private static TaskRow prRow(PrLink pr, String branch) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(),
                "Review #930", AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(DIR), Instant.EPOCH).pr(pr).build();
        return new TaskRow(session, Optional.empty(), false, Optional.of(branch), new TaskPlacement.Idle(false), false,
                false, false);
    }

    @Test
    void aSessionRowNamesItsPullRequestFromItsLinkElseItsPrBranch() {
        assertEquals(Optional.of(930), TaskTreeRenderer.pullRequestOf(
                prRow(PrLink.of(PrState.OPEN, Optional.of(930)), "feature/x")), "the session's own PR link");
        assertEquals(Optional.of(77), TaskTreeRenderer.pullRequestOf(prRow(PrLink.none(), "pr-77")),
                "else the pr-<n> branch of a PR checkout");
        assertEquals(Optional.empty(), TaskTreeRenderer.pullRequestOf(prRow(PrLink.none(), "fix/chunk_memory")));
    }

    @Test
    void aPullRequestWithABlankTitleTooltipNamesOnlyNumberAndBranch() {
        assertEquals("Pull request #41 from pr-41",
                TaskTreeRenderer.pullRequestLinkTooltip(openPr(41, "  ", "pr-41")));
    }

    @Test
    void aPullRequestRowsLinkedTaskTooltipNamesTheTaskWorkingItsBranch() {
        assertEquals("Task 'Fix allocation profile' (fix-alloc-k3f9) works this branch",
                TaskTreeRenderer.linkedTaskTooltip(document(TaskId.of("fix-alloc-k3f9"))));
    }

    private static BranchPullRequest branchPr(PrLifecycle state, boolean draft) {
        return new BranchPullRequest(12, state, draft, "feature/x", "Fix it", Optional.empty());
    }

    private static BranchPullRequest branchPr(PrLifecycle state, boolean draft, Optional<String> url) {
        return new BranchPullRequest(12, state, draft, "feature/x", "Fix it", url);
    }

    private static TaskRow row(BranchPullRequest pullRequest) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(),
                "Fix chunk memory growth", AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(DIR), Instant.EPOCH).status(SessionStatus.INACTIVE).build();
        return new TaskRow(session, Optional.empty(), false, Optional.of("fix/chunk_memory"),
                new TaskPlacement.Idle(false), false, false, false, Optional.of(pullRequest));
    }

    @Test
    void aSessionRowOffersItsPullRequestsPageWhenGhReportedOne() {
        assertEquals(Optional.of("https://github.com/o/r/pull/12"), TaskTreeRenderer.pullRequestUrl(
                row(branchPr(PrLifecycle.OPEN, false, Optional.of("https://github.com/o/r/pull/12")))),
                "gh's url is the link, whatever the state");
        assertEquals(Optional.of("https://github.com/o/r/pull/12"), TaskTreeRenderer.pullRequestUrl(
                row(branchPr(PrLifecycle.MERGED, false, Optional.of("https://github.com/o/r/pull/12")))),
                "a merged PR's page stays useful");
        assertEquals(Optional.empty(), TaskTreeRenderer.pullRequestUrl(row(branchPr(PrLifecycle.OPEN, false))),
                "no url from gh: no link to offer");
        assertEquals(Optional.empty(), TaskTreeRenderer.pullRequestUrl(row(false, new TaskPlacement.Idle(false))),
                "no pull request known: no link to offer");
    }


    @Test
    void theSecondaryTextNamesTheBranchWhereItRunsAndTheAgent() {
        assertEquals("fix/chunk_memory · this window · Pi",
                TaskTreeRenderer.secondary(row(false, new TaskPlacement.Idle(true)), "Pi", Duration.ZERO));
        assertEquals("fix/chunk_memory · not shown · Pi",
                TaskTreeRenderer.secondary(row(true, new TaskPlacement.NotShown()), "Pi", Duration.ZERO));
    }

    @Test
    void aDelayedTasksPullRequestTooltipNamesNumberBranchAndTitle() {
        assertEquals("Pull request #40 from pr-40: Speed up the flaky suite",
                TaskTreeRenderer.pullRequestLinkTooltip(openPr(40, "Speed up the flaky suite", "pr-40")));
    }

    @Test
    void aTaskRowsDescriptionHoverCarriesTheBodyAfterABlankLine() {
        assertEquals("", TaskTreeRenderer.descriptionHover(""), "no body, no hover block");
        assertEquals("", TaskTreeRenderer.descriptionHover("   \n "), "a blank body shows nothing");
        assertEquals("\n\nFix the parser.", TaskTreeRenderer.descriptionHover("\n Fix the parser. \n"),
                "the body rides trimmed, after the one-line summary");
    }

    @Test
    void aLongDescriptionHoverIsCutOnALineBoundaryAndSaysWhereTheRestLives() {
        String cut = "x".repeat(TaskTreeRenderer.DESCRIPTION_HOVER_LIMIT - 1);
        String rest = "y".repeat(2_000);
        assertEquals("\n\n" + cut + "\n… (Edit opens the task's file with the rest)",
                TaskTreeRenderer.descriptionHover(cut + "\n" + rest),
                "the cut stops before the line that crosses the cap");
        String lineless = "a".repeat(3_000);
        assertEquals("\n\n" + lineless.substring(0, TaskTreeRenderer.DESCRIPTION_HOVER_LIMIT)
                        + "\n… (Edit opens the task's file with the rest)",
                TaskTreeRenderer.descriptionHover(lineless),
                "no line boundary to cut at: the cap itself ends the hover");
    }

    private static GhCliService.OpenPullRequest healthPr(boolean conflicting, Set<CiFailure> ci) {
        return new GhCliService.OpenPullRequest(7, "Fix it", "feature/x", "main", false,
                Optional.of("someone"), Optional.empty(), conflicting, ci);
    }

    private static TaskTreeRenderer painted(TaskTreeRenderer renderer, GhCliService.OpenPullRequest pr,
                                            Optional<TaskDocument> linked) {
        renderer.clear();
        renderer.paintPullRequest(pr, linked);
        return renderer;
    }

    private static List<Object> tags(TaskTreeRenderer renderer) {
        List<Object> tags = new ArrayList<>();
        SimpleColoredComponent.ColoredIterator it = renderer.iterator();
        while (it.hasNext()) {
            it.next();
            tags.add(it.getTag());
        }
        return tags;
    }

    @Test
    void aPullRequestRowsIconComposesConflictThenCiAndOnlyForPositiveSignals() {
        Icon github = AllIcons.Vcs.Vendors.Github;
        assertSame(github, TaskTreeRenderer.pullRequestRowIcon(false, Set.of()), "no status: the bare mark");
        Icon conflict = TaskTreeRenderer.pullRequestRowIcon(true, Set.of());
        assertEquals(List.of(github, TaskTreeRenderer.PR_CONFLICT_ICON), ((RowIcon) conflict).getAllIcons());
        Icon ci = TaskTreeRenderer.pullRequestRowIcon(false, Set.of(CiFailure.FAILURE));
        assertEquals(List.of(github, TaskTreeRenderer.PR_CI_FAILURE_ICON), ((RowIcon) ci).getAllIcons());
        Icon both = TaskTreeRenderer.pullRequestRowIcon(true, Set.of(CiFailure.ERROR));
        assertEquals(List.of(conflict, TaskTreeRenderer.PR_CI_FAILURE_ICON), ((RowIcon) both).getAllIcons(),
                "the conflict composite, then the CI glyph: both visible at once");
        assertNotSame(conflict, both);
        assertNotSame(ci, both);
    }

    @Test
    void theLegacyIconMethodKeepsItsMeaningAndRepaintsReuseTheCachedComposites() {
        assertSame(AllIcons.Vcs.Vendors.Github, TaskTreeRenderer.pullRequestRowIcon(false));
        assertSame(TaskTreeRenderer.pullRequestRowIcon(true, Set.of()), TaskTreeRenderer.pullRequestRowIcon(true));
        assertSame(TaskTreeRenderer.pullRequestRowIcon(true, Set.of(CiFailure.FAILURE)),
                TaskTreeRenderer.pullRequestRowIcon(true, Set.of(CiFailure.ERROR, CiFailure.FAILURE)));
        Icon base = TaskTreeRenderer.JIRA_ICON;
        assertSame(TaskTreeRenderer.healthIcon(base, true, true), TaskTreeRenderer.healthIcon(base, true, true),
                "a shared base gets the same composite on every paint");
        assertSame(base, TaskTreeRenderer.healthIcon(base, false, false));
    }

    @Test
    void theHealthTooltipNamesOnlyPositiveSignals() {
        assertEquals(Optional.empty(), TaskTreeRenderer.healthTooltip(7, Optional.of("main"), false, Set.of()));
        assertEquals(Optional.of("#7 cannot merge into main: it has conflicts"),
                TaskTreeRenderer.healthTooltip(7, Optional.of("main"), true, Set.of()));
        assertEquals(Optional.of("#7 has failing CI checks"),
                TaskTreeRenderer.healthTooltip(7, Optional.of("main"), false, Set.of(CiFailure.FAILURE)));
        assertEquals(Optional.of("#7 has merge conflicts · #7 has failing CI checks (errored)"),
                TaskTreeRenderer.healthTooltip(7, Optional.empty(), true, Set.of(CiFailure.ERROR)));
        assertEquals(Optional.of("#12 has merge conflicts"), TaskTreeRenderer.healthTooltip(new BranchPullRequest(
                12, PrLifecycle.OPEN, false, "feature/x", "Fix it", Optional.empty(), true, Set.of())));
        assertEquals(Optional.empty(), TaskTreeRenderer.healthTooltip(branchPr(PrLifecycle.OPEN, false)));
    }

    @Test
    void resolveConflictsIsOfferedExactlyWhenThePullRequestConflicts() {
        TaskTreeRenderer renderer = new TaskTreeRenderer();
        assertTrue(tags(painted(renderer, healthPr(true, Set.of()), Optional.empty()))
                .contains(TaskTreeRenderer.RESOLVE_CONFLICTS_LINK));
        assertTrue(tags(painted(renderer, healthPr(true, Set.of(CiFailure.FAILURE)), Optional.empty()))
                .contains(TaskTreeRenderer.RESOLVE_CONFLICTS_LINK));
        assertFalse(tags(painted(renderer, healthPr(false, Set.of(CiFailure.FAILURE)), Optional.empty()))
                .contains(TaskTreeRenderer.RESOLVE_CONFLICTS_LINK), "failing CI is not a conflict");
        assertFalse(tags(painted(renderer, healthPr(false, Set.of()), Optional.empty()))
                .contains(TaskTreeRenderer.RESOLVE_CONFLICTS_LINK));
        assertTrue(tags(renderer).contains(TaskTreeRenderer.LINK), "Review stays offered");
    }

    @Test
    void aPullRequestRowsTooltipComposesHealthWithTheLinkedTask() {
        TaskTreeRenderer renderer = new TaskTreeRenderer();
        TaskDocument task = document(TaskId.of("fix-alloc-k3f9"));
        String linked = TaskTreeRenderer.linkedTaskTooltip(task);
        assertEquals("#7 cannot merge into main: it has conflicts · #7 has failing CI checks · " + linked,
                painted(renderer, healthPr(true, Set.of(CiFailure.FAILURE)), Optional.of(task)).getToolTipText());
        assertEquals(linked, painted(renderer, healthPr(false, Set.of()), Optional.of(task)).getToolTipText());
        assertTrue(renderer.getCharSequence(false).toString().contains(TaskTreeRenderer.mirroredTaskBadge(task.title())));
    }

    @Test
    void aReusedRendererKeepsNoHealthOfThePreviousPullRequest() {
        TaskTreeRenderer renderer = new TaskTreeRenderer();
        painted(renderer, healthPr(true, Set.of(CiFailure.FAILURE)), Optional.empty());
        assertSame(TaskTreeRenderer.pullRequestRowIcon(true, Set.of(CiFailure.FAILURE)), renderer.getIcon());

        painted(renderer, healthPr(false, Set.of()), Optional.empty());
        assertSame(AllIcons.Vcs.Vendors.Github, renderer.getIcon(), "a healthy PR gets the bare mark");
        assertNull(renderer.getToolTipText(), "no conflict or CI text leaks onto a healthy PR");

        painted(renderer, healthPr(false, Set.of(CiFailure.ERROR)), Optional.empty());
        renderer.customizeCellRenderer(new JTree(), new DefaultMutableTreeNode(
                githubNode(new Placeholder(TaskTreeModel.NOT_LOADED))), false, false, false, 0, false);
        assertSame(AllIcons.Vcs.Vendors.Github, renderer.getIcon());
        assertNull(renderer.getToolTipText(), "an unrelated row inherits no status");
    }

    @Test
    void theCiFailureGlyphShipsLightAndDarkSixteenPixelVariants() throws IOException {
        String light = resource("/icons/prCiFailure.svg");
        String dark = resource("/icons/prCiFailure_dark.svg");
        for (String svg : List.of(light, dark)) {
            assertTrue(svg.contains("width=\"16\"") && svg.contains("height=\"16\""), svg);
        }
        assertNotEquals(light, dark, "the dark theme gets its own colour");
        assertNotEquals(resource("/icons/prConflict.svg").replaceAll("#\\w+", ""), light.replaceAll("#\\w+", ""),
                "conflict and CI glyphs differ in shape, not just colour");
        // IconLoader's SVG rasterization has no headless path — it hands back
        // its 1x1 placeholder — so the rendered-size assertions hold only headful.
        if (!GraphicsEnvironment.isHeadless()) {
            assertEquals(16, TaskTreeRenderer.PR_CI_FAILURE_ICON.getIconWidth());
            assertEquals(16, TaskTreeRenderer.PR_CI_FAILURE_ICON.getIconHeight());
        }
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = TaskTreeRenderer.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
