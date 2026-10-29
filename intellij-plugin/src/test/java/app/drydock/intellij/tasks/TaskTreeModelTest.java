package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.SshRemote;
import app.drydock.git.GhCliService.IssueListing;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.GhCliService.OpenPullRequest;
import app.drydock.git.GhCliService.PullRequestListing;
import app.drydock.git.JiraIssue;
import app.drydock.git.JiraIssueListing;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.tasks.TaskNode.AddCurrentProject;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.IssueRow;
import app.drydock.intellij.tasks.TaskNode.JiraGroupRow;
import app.drydock.intellij.tasks.TaskNode.JiraIssueRow;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.PullRequestRow;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.SectionKind;
import app.drydock.intellij.tasks.TaskNode.StaleWorktrees;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.tasks.TaskNode.WorktreeGroup;
import app.drydock.intellij.tasks.TaskTreeModel.ViewFilter;
import app.drydock.projects.SharedProject;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskTreeModelTest {

    private static final Instant T = Instant.parse("2026-10-19T10:00:00Z");

    private static final Path DRYDOCK = Path.of("/w/drydock");
    private static final Path BTRACE = Path.of("/w/btrace");
    private static final Path ALPHA = Path.of("/w/alpha");

    /** Fluent inputs: every path given here is already canonical, as gather() makes them. */
    private static final class In {
        final List<SharedProject> projects = new ArrayList<>();
        final List<ManagedAgentSession> sessions = new ArrayList<>();
        final Map<Path, List<Worktree>> worktrees = new HashMap<>();
        final Map<Path, Path> mainRootOf = new HashMap<>();
        Optional<Path> currentMain = Optional.empty();
        Optional<Path> currentWorking = Optional.empty();
        final Map<ManagedSessionId, Optional<SessionActivity>> activity = new HashMap<>();
        final Set<ManagedSessionId> running = new HashSet<>();
        final Map<ManagedSessionId, TaskTreeModel.Host> hosts = new HashMap<>();
        final Set<Path> missing = new HashSet<>();
        final Map<RepositoryId, Path> recordedRoots = new HashMap<>();
        final Set<Path> worktreeContainers = new HashSet<>();
        final Set<Path> issueContainers = new HashSet<>();
        final Set<Path> pullRequestContainers = new HashSet<>();
        final Set<Path> openFolders = new HashSet<>();
        final Map<Path, Instant> lastActivity = new HashMap<>();
        final Map<Path, IssueListing> issues = new HashMap<>();
        final Map<Path, List<JiraGroups.Listing>> jiraGroups = new HashMap<>();
        final Map<Path, PullRequestListing> pullRequests = new HashMap<>();
        final Map<Path, List<Worktree>> pullRequestDedupWorktrees = new HashMap<>();
        Map<ManagedSessionId, Boolean> resumable = Map.of();
        Optional<String> problem = Optional.empty();
        String filter = "";
        ViewFilter viewFilter = ViewFilter.ALL;

        In project(Path root) {
            projects.add(SharedProject.local(RepositoryId.newId(), root, root.getFileName().toString(), T));
            worktrees.computeIfAbsent(root, r -> new ArrayList<>()).add(main(root, "main"));
            worktreeContainers.add(root);
            mainRootOf.put(root, root);
            return this;
        }

        In worktree(Path main, Path dir, String branch) {
            worktrees.computeIfAbsent(main, r -> new ArrayList<>()).add(linked(dir, branch));
            mainRootOf.put(dir, main);
            return this;
        }

        In current(Path main, Path working) {
            currentMain = Optional.of(main);
            currentWorking = Optional.of(working);
            return this;
        }

        TaskTreeModel.Inputs build() {
            return new TaskTreeModel.Inputs(projects, sessions, worktrees, mainRootOf, currentMain, currentWorking,
                    activity, running, hosts, missing, recordedRoots, worktreeContainers, issueContainers,
                    pullRequestContainers, openFolders, lastActivity, T,
                    issues, jiraGroups, pullRequests, pullRequestDedupWorktrees, problem, filter, viewFilter,
                    resumable);
        }
    }

    private static Worktree main(Path path, String branch) {
        return new Worktree(path, Optional.of(branch), true, false, false, false, Optional.empty(), false);
    }

    private static Worktree linked(Path path, String branch) {
        return new Worktree(path, Optional.of(branch), false, false, false, false, Optional.empty(), false);
    }

    private static ManagedAgentSession session(String name, Path workingDirectory, AgentKind kind, Instant opened) {
        return new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(), name,
                AgentBinding.unlaunched(kind, SessionMode.WRITES), SessionWorkspace.inRepository(workingDirectory), T)
                .status(SessionStatus.INACTIVE)
                .lastOpenedAt(opened)
                .build();
    }

    private static ManagedAgentSession session(String name, Path workingDirectory) {
        return session(name, workingDirectory, AgentKind.CLAUDE, T);
    }

    private static ProjectRow projectRow(List<TaskNode> nodes, Path root) {
        return nodes.stream().filter(ProjectRow.class::isInstance).map(ProjectRow.class::cast)
                .filter(row -> row.project().root().equals(root)).findFirst().orElseThrow();
    }

    private static List<Path> projectRoots(List<TaskNode> nodes) {
        return nodes.stream().filter(ProjectRow.class::isInstance).map(node -> ((ProjectRow) node).project().root())
                .toList();
    }

    private static Optional<Section> section(ProjectRow row, SectionKind kind) {
        return row.children().stream().filter(Section.class::isInstance).map(Section.class::cast)
                .filter(section -> section.kind() == kind).findFirst();
    }

    /** Session entities, flattening the optional Active/Inactive presentation folders. */
    private static List<TaskNode> sessionNodes(ProjectRow row) {
        List<TaskNode> children = section(row, SectionKind.SESSIONS).orElseThrow().children();
        if (children.stream().allMatch(Section.class::isInstance)) {
            return children.stream().map(Section.class::cast).flatMap(group -> group.children().stream()).toList();
        }
        return children;
    }

    private static List<TaskNode> worktreeNodes(ProjectRow row) {
        return section(row, SectionKind.WORKTREES).orElseThrow().children();
    }

    private static List<TaskNode> issueNodes(ProjectRow row) {
        return section(row, SectionKind.ISSUES).orElseThrow().children();
    }

    private static List<TaskNode> pullRequestNodes(ProjectRow row) {
        return section(row, SectionKind.PULL_REQUESTS).orElseThrow().children();
    }

    private static OpenPullRequest pullRequest(int number, String title, String headBranch, boolean draft) {
        return new OpenPullRequest(number, title, headBranch, "main", draft, Optional.empty(), Optional.empty());
    }

    private static JiraIssue jiraIssue(String key, String summary, List<String> labels, Optional<Instant> updatedAt) {
        return new JiraIssue(key, summary, "https://acme.atlassian.net/browse/" + key, labels, updatedAt);
    }

    /** One fetched group: rows in Jira's own returned order, the id derived from the name. */
    private static JiraGroups.Listing listedGroup(String name, List<JiraIssue> issues) {
        return new JiraGroups.Listing(new JiraGroup("g-" + name.toLowerCase(Locale.ROOT), name,
                "project = DRY ORDER BY updated DESC"), Optional.of(new JiraIssueListing.Listed(issues)));
    }

    /** "#9" for a GitHub row, the key for a Jira row: one section's rows as comparable ids. */
    private static String issueRowId(TaskNode node) {
        if (node instanceof IssueRow issue) {
            return "#" + issue.issue().number();
        }
        if (node instanceof JiraIssueRow jira) {
            return jira.issue().key();
        }
        throw new AssertionError("unexpected Issues child: " + node);
    }

    /** A section child's row ids, descending into Jira group nodes. */
    private static Stream<String> flattenedIssueIds(TaskNode node) {
        if (node instanceof JiraGroupRow group) {
            return group.children().stream().map(TaskTreeModelTest::issueRowId);
        }
        return Stream.of(issueRowId(node));
    }

    @Test
    void aListProblemIsTheFirstRow() {
        In in = new In().project(ALPHA);
        in.problem = Optional.of("projects.json is malformed; fix it to change projects.");

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        assertEquals(new TaskNode.ListProblem("projects.json is malformed; fix it to change projects."), nodes.get(0));
        assertEquals(List.of(ALPHA), projectRoots(nodes));
    }

    @Test
    void currentProjectFirstThenAlphabetical() {
        In in = new In().project(DRYDOCK).project(BTRACE).project(ALPHA).current(DRYDOCK, DRYDOCK);

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        assertEquals(List.of(DRYDOCK, ALPHA, BTRACE), projectRoots(nodes));
        assertTrue(projectRow(nodes, DRYDOCK).isThisWindow());
    }

    @Test
    void unregisteredCurrentProjectGetsAddRowWithItsRunningTasks() {
        In in = new In().project(ALPHA).current(BTRACE, BTRACE);
        in.mainRootOf.put(BTRACE, BTRACE);
        ManagedAgentSession here = session("Fix probe leak", BTRACE);
        in.sessions.add(here);
        in.running.add(here.id());

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        assertEquals(new AddCurrentProject(BTRACE, "btrace"), nodes.get(0));
        TaskRow row = assertInstanceOf(TaskRow.class, nodes.get(1));
        assertEquals(here.id(), row.session().id());
        assertEquals(List.of(ALPHA), projectRoots(nodes));
    }

    @Test
    void remoteProjectsAreHidden() {
        In in = new In().project(ALPHA);
        SshRemote remote = new SshRemote("box", "/srv/r");
        in.projects.add(new SharedProject(RepositoryId.newId(), remote.placeholderRoot(), "r", T, Optional.of(remote)));

        assertEquals(List.of(ALPHA), projectRoots(TaskTreeModel.build(in.build())));
    }

    @Test
    void singleTaskWorktreeIsFlatRowWithBranch() {
        Path wt = Path.of("/wt/drydock-task-centric");
        In in = new In().project(DRYDOCK).worktree(DRYDOCK, wt, "task-centric");
        in.sessions.add(session("Task-centric tool window", wt));

        ProjectRow row = projectRow(TaskTreeModel.build(in.build()), DRYDOCK);

        TaskRow task = assertInstanceOf(TaskRow.class, sessionNodes(row).get(0));
        assertEquals(Optional.of("task-centric"), task.branch());
        assertEquals(1, sessionNodes(row).size());
        assertTrue(section(row, SectionKind.WORKTREES).isEmpty(),
                "a worktree with a session must not also appear under Worktrees");
    }

    @Test
    void twoTasksInOneWorktreeBecomeGroup() {
        Path wt = Path.of("/wt/btrace-jfr");
        In in = new In().project(BTRACE).worktree(BTRACE, wt, "add-jfr-events");
        in.sessions.add(session("Add JFR events", wt));
        in.sessions.add(session("Review the JFR events", wt));

        ProjectRow row = projectRow(TaskTreeModel.build(in.build()), BTRACE);

        WorktreeGroup group = assertInstanceOf(WorktreeGroup.class, sessionNodes(row).get(0));
        assertEquals("add-jfr-events", group.branch());
        assertEquals(2, group.tasks().size());
    }

    @Test
    void worktreeWithoutTaskIsIdleWorktree() {
        Path idle = Path.of("/wt/btrace-old");
        Path pruned = Path.of("/wt/btrace-gone");
        In in = new In().project(BTRACE).worktree(BTRACE, idle, "old-experiment");
        in.worktrees.get(BTRACE).add(new Worktree(pruned, Optional.of("gone"), false, false, true, false,
                Optional.empty(), false));

        ProjectRow row = projectRow(TaskTreeModel.build(in.build()), BTRACE);

        assertEquals(List.of(new IdleWorktree(idle, "old-experiment", Optional.empty(), false, false)),
                worktreeNodes(row));
        assertTrue(section(row, SectionKind.SESSIONS).isEmpty());
    }

    @Test
    void unloadedWorktreesAndIssuesStayDeferredWhileSessionsArePresent() {
        In in = new In().project(BTRACE);
        in.sessions.add(session("Eager session", BTRACE));
        in.worktrees.remove(BTRACE);
        in.issueContainers.add(BTRACE);

        ProjectRow row = projectRow(TaskTreeModel.build(in.build()), BTRACE);

        assertEquals("Eager session", ((TaskRow) sessionNodes(row).getFirst()).session().displayName());
        assertInstanceOf(TaskNode.Deferred.class,
                section(row, SectionKind.WORKTREES).orElseThrow().children().getFirst());
        assertInstanceOf(TaskNode.Deferred.class,
                section(row, SectionKind.ISSUES).orElseThrow().children().getFirst());
    }

    @Test
    void worktreesSectionExcludesWorktreesThatHaveSessions() {
        Path occupied = Path.of("/wt/btrace-active");
        Path sessionless = Path.of("/wt/btrace-idle");
        In in = new In().project(BTRACE)
                .worktree(BTRACE, occupied, "active-work")
                .worktree(BTRACE, sessionless, "idle-work");
        in.sessions.add(session("Finished task", occupied));

        ProjectRow row = projectRow(TaskTreeModel.build(in.build()), BTRACE);

        assertEquals(List.of(new IdleWorktree(sessionless, "idle-work", Optional.empty(), false, false)),
                worktreeNodes(row));
        assertEquals(Optional.of("active-work"), ((TaskRow) sessionNodes(row).get(0)).branch());
    }

    @Test
    void confirmedRemovalDisappearsFromTheBuiltModelBeforeARescan() {
        Path staleOne = Path.of("/wt/btrace-old-one");
        Path staleTwo = Path.of("/wt/btrace-old-two");
        Path recent = Path.of("/wt/btrace-recent");
        In in = new In().project(BTRACE)
                .worktree(BTRACE, staleOne, "old-one")
                .worktree(BTRACE, staleTwo, "old-two")
                .worktree(BTRACE, recent, "recent");
        in.lastActivity.put(staleOne, T.minus(TaskTreeModel.STALE_AFTER).minusSeconds(1));
        in.lastActivity.put(staleTwo, T.minus(TaskTreeModel.STALE_AFTER).minusSeconds(1));
        in.lastActivity.put(recent, T);

        List<TaskNode> visible = TaskTreeModel.withoutWorktrees(TaskTreeModel.build(in.build()),
                Set.of(staleOne, recent));

        ProjectRow row = projectRow(visible, BTRACE);
        StaleWorktrees stale = assertInstanceOf(StaleWorktrees.class, worktreeNodes(row).get(0));
        assertEquals(List.of(staleTwo), stale.worktrees().stream().map(IdleWorktree::worktree).toList());
        assertEquals(List.of(staleTwo), row.inactiveWorktrees().stream().map(IdleWorktree::worktree).toList(),
                "action inputs must lose the removed rows along with the presentation");
    }

    @Test
    void inactiveWorktreesOlderThanSevenDaysAreGroupedAsStale() {
        Path recent = Path.of("/wt/btrace-recent");
        Path old = Path.of("/wt/btrace-old");
        Path locked = Path.of("/wt/btrace-locked");
        Path open = Path.of("/wt/btrace-open");
        In in = new In().project(BTRACE)
                .worktree(BTRACE, recent, "recent")
                .worktree(BTRACE, old, "old")
                .worktree(BTRACE, open, "open");
        in.worktrees.get(BTRACE).add(new Worktree(locked, Optional.of("held"), false, false, false, true,
                Optional.of("keep"), false));
        in.mainRootOf.put(locked, BTRACE);
        in.lastActivity.put(recent, T.minus(TaskTreeModel.STALE_AFTER));
        in.lastActivity.put(old, T.minus(TaskTreeModel.STALE_AFTER).minusSeconds(1));
        in.lastActivity.put(locked, T.minus(TaskTreeModel.STALE_AFTER).minusSeconds(1));
        in.lastActivity.put(open, T.minus(TaskTreeModel.STALE_AFTER).minusSeconds(1));
        in.openFolders.add(open);

        ProjectRow row = projectRow(TaskTreeModel.build(in.build()), BTRACE);
        List<TaskNode> worktrees = worktreeNodes(row);

        assertEquals(new IdleWorktree(recent, "recent", Optional.of(T.minus(TaskTreeModel.STALE_AFTER)), false,
                        false),
                worktrees.get(0), "exactly seven days is not older than seven days");
        assertEquals(new IdleWorktree(open, "open",
                Optional.of(T.minus(TaskTreeModel.STALE_AFTER).minusSeconds(1)), false, true), worktrees.get(1),
                "an open IDE project must never be swept into bulk stale deletion");
        assertEquals(new IdleWorktree(locked, "held",
                Optional.of(T.minus(TaskTreeModel.STALE_AFTER).minusSeconds(1)), true, false), worktrees.get(2),
                "a deliberate git lock must never be swept into bulk stale deletion");
        StaleWorktrees stale = assertInstanceOf(StaleWorktrees.class, worktrees.get(3));
        assertEquals(List.of(old), stale.worktrees().stream().map(IdleWorktree::worktree).toList());
        assertEquals(List.of(recent, old, open, locked), row.inactiveWorktrees().stream()
                .map(IdleWorktree::worktree).toList(), "the project-level action must ignore the search/grouping");
    }

    @Test
    void issuesIncludeUnassignedItemsAndSortByMostRecentlyUpdated() {
        In in = new In().project(BTRACE);
        OpenIssue olderAssigned = new OpenIssue(4, "Assigned issue", Optional.of("https://example/4"),
                List.of("bug"), List.of("somebody"), Optional.of(T));
        OpenIssue newerUnassigned = new OpenIssue(9, "Unassigned issue", Optional.of("https://example/9"),
                List.of("profiling"), List.of(), Optional.of(T.plusSeconds(60)));
        in.issues.put(BTRACE, new IssueListing.Listed(List.of(olderAssigned, newerUnassigned)));

        ProjectRow row = projectRow(TaskTreeModel.build(in.build()), BTRACE);

        assertEquals(List.of(9, 4), issueNodes(row).stream()
                .map(IssueRow.class::cast).map(issue -> issue.issue().number()).toList());
    }

    @Test
    void issueSearchMatchesNumberTitleAndLabel() {
        In in = new In().project(BTRACE);
        in.issues.put(BTRACE, new IssueListing.Listed(List.of(
                new OpenIssue(9, "Allocation regression", Optional.empty(), List.of("profiling"), List.of(),
                        Optional.of(T)),
                new OpenIssue(10, "Documentation", Optional.empty(), List.of("docs"), List.of(), Optional.of(T)))));
        in.filter = "#9";

        ProjectRow row = projectRow(TaskTreeModel.build(in.build()), BTRACE);

        IssueRow issue = assertInstanceOf(IssueRow.class, issueNodes(row).getFirst());
        assertEquals(9, issue.issue().number());
    }

    @Test
    void githubRowsStayFlatAndJiraRowsLiveOnlyInsideTheirGroups() {
        In in = new In().project(BTRACE);
        in.issueContainers.add(BTRACE);
        in.issues.put(BTRACE, new IssueListing.Listed(List.of(
                new OpenIssue(4, "GitHub middle", Optional.empty(), List.of(), List.of(), Optional.of(T)),
                new OpenIssue(9, "GitHub newest", Optional.empty(), List.of(), List.of(), Optional.of(T.plusSeconds(60))))));
        in.jiraGroups.put(BTRACE, List.of(
                listedGroup("Sprint", List.of(
                        jiraIssue("PROJ-1", "Jira first", List.of(), Optional.of(T)),
                        jiraIssue("PROJ-2", "Jira second", List.of(), Optional.of(T.plusSeconds(30)))))));

        ProjectRow row = projectRow(TaskTreeModel.build(in.build()), BTRACE);
        List<TaskNode> children = issueNodes(row);

        // GitHub's flat rows first, newest first; then the group node, whose
        // rows keep the order the group's own JQL returned — the model does
        // not re-sort a user query's deliberate ORDER BY.
        assertEquals(List.of("#9", "#4"), children.stream().limit(2).map(TaskTreeModelTest::issueRowId).toList());
        JiraGroupRow group = assertInstanceOf(JiraGroupRow.class, children.get(2));
        assertEquals("Sprint", group.group().name());
        assertEquals(List.of("PROJ-1", "PROJ-2"), group.children().stream()
                .map(TaskTreeModelTest::issueRowId).toList());
    }

    @Test
    void groupsAppearInTheOrderTheyWereDefined() {
        In in = new In().project(BTRACE);
        in.issueContainers.add(BTRACE);
        in.jiraGroups.put(BTRACE, List.of(
                listedGroup("Sprint", List.of(jiraIssue("PROJ-1", "One", List.of(), Optional.of(T)))),
                listedGroup("Bugs", List.of(jiraIssue("OPS-7", "Two", List.of(), Optional.of(T))))));

        List<TaskNode> children = issueNodes(projectRow(TaskTreeModel.build(in.build()), BTRACE));

        assertEquals(List.of("Sprint", "Bugs"), children.stream().map(JiraGroupRow.class::cast)
                .map(group -> group.group().name()).toList());
    }

    @Test
    void aDeferredGroupShowsItsLoadingChildUntilTheListingArrives() {
        In in = new In().project(BTRACE);
        in.issueContainers.add(BTRACE);
        JiraGroup group = new JiraGroup("g-sprint", "Sprint", "project = DRY");
        in.jiraGroups.put(BTRACE, List.of(new JiraGroups.Listing(group, Optional.empty())));

        JiraGroupRow row = assertInstanceOf(JiraGroupRow.class,
                issueNodes(projectRow(TaskTreeModel.build(in.build()), BTRACE)).getFirst());
        assertEquals(List.of(new TaskNode.Deferred("Loading…")), row.children());

        in.jiraGroups.put(BTRACE, List.of(listedGroup("Sprint", List.of(
                jiraIssue("PROJ-123", "Fix allocation accounting", List.of("profiling"), Optional.of(T))))));
        row = assertInstanceOf(JiraGroupRow.class,
                issueNodes(projectRow(TaskTreeModel.build(in.build()), BTRACE)).getFirst());
        assertEquals(List.of("PROJ-123"), row.children().stream().map(TaskTreeModelTest::issueRowId).toList());
    }

    @Test
    void aContainerWithNoListingsAndNoGroupsStaysDeferred() {
        In in = new In().project(BTRACE);
        in.issueContainers.add(BTRACE);

        assertEquals(List.of(new TaskNode.Deferred("Loading issues…")),
                section(projectRow(TaskTreeModel.build(in.build()), BTRACE), SectionKind.ISSUES)
                        .orElseThrow().children());
    }

    @Test
    void neitherSourceGivesNoIssuesSection() {
        In in = new In().project(BTRACE);

        assertTrue(section(projectRow(TaskTreeModel.build(in.build()), BTRACE), SectionKind.ISSUES).isEmpty());
    }

    @Test
    void aFailedGroupSaysSoInsideTheGroupAndKeepsTheGitHubRows() {
        In in = new In().project(BTRACE);
        in.issueContainers.add(BTRACE);
        in.issues.put(BTRACE, new IssueListing.Listed(List.of(
                new OpenIssue(9, "Allocation regression", Optional.empty(), List.of("profiling"), List.of(),
                        Optional.of(T)))));
        JiraGroup group = new JiraGroup("g-sprint", "Sprint", "project = DRY");
        in.jiraGroups.put(BTRACE, List.of(
                new JiraGroups.Listing(group, Optional.of(new JiraIssueListing.Failed("boom")))));

        List<TaskNode> children = issueNodes(projectRow(TaskTreeModel.build(in.build()), BTRACE));

        assertEquals("#9", issueRowId(children.get(0)));
        JiraGroupRow row = assertInstanceOf(JiraGroupRow.class, children.get(1));
        assertEquals(List.of(new TaskNode.ListProblem("Jira group \"Sprint\": boom")), row.children());
    }

    @Test
    void aFailedGitHubListingAddsAProblemRowAndTheGroupsStay() {
        In in = new In().project(BTRACE);
        in.issueContainers.add(BTRACE);
        in.issues.put(BTRACE, new IssueListing.Failed("gh exited 1"));
        in.jiraGroups.put(BTRACE, List.of(listedGroup("Sprint", List.of(
                jiraIssue("PROJ-123", "Fix allocation accounting", List.of("profiling"), Optional.of(T))))));

        List<TaskNode> children = issueNodes(projectRow(TaskTreeModel.build(in.build()), BTRACE));

        assertEquals(new TaskNode.ListProblem("GitHub issues: gh exited 1"), children.get(0));
        JiraGroupRow row = assertInstanceOf(JiraGroupRow.class, children.get(1));
        assertEquals(List.of("PROJ-123"), row.children().stream().map(TaskTreeModelTest::issueRowId).toList());
    }

    @Test
    void anEmptyGroupKeepsShowingWithAPlaceholder() {
        In in = new In().project(BTRACE);
        in.issueContainers.add(BTRACE);
        in.jiraGroups.put(BTRACE, List.of(listedGroup("Sprint", List.of())));

        JiraGroupRow row = assertInstanceOf(JiraGroupRow.class,
                issueNodes(projectRow(TaskTreeModel.build(in.build()), BTRACE)).getFirst());
        assertEquals(List.of(new TaskNode.Placeholder("No issues")), row.children());
    }

    @Test
    void searchFiltersInsideGroupsAndDropsGroupsThatMatchNothing() {
        In in = new In().project(BTRACE);
        in.issueContainers.add(BTRACE);
        in.jiraGroups.put(BTRACE, List.of(listedGroup("Sprint", List.of(
                jiraIssue("PROJ-123", "Fix allocation accounting", List.of("profiling"), Optional.of(T)),
                jiraIssue("OPS-7", "Inspect the agent loader", List.of("triage"), Optional.of(T))))));
        ProjectRow projectOf = projectRow(TaskTreeModel.build(in.build()), BTRACE);
        JiraGroupRow group = assertInstanceOf(JiraGroupRow.class, issueNodes(projectOf).getFirst());
        assertEquals(2, group.children().size());

        in.filter = "ops-7";
        group = assertInstanceOf(JiraGroupRow.class,
                issueNodes(projectRow(TaskTreeModel.build(in.build()), BTRACE)).getFirst());
        assertEquals(List.of("OPS-7"), group.children().stream().map(TaskTreeModelTest::issueRowId).toList());

        // A filter nothing inside matches drops the whole group — and with
        // it the section and the project, so the tree never shows a husk.
        in.filter = "nothing-matches-this";
        assertTrue(projectRoots(TaskTreeModel.build(in.build())).isEmpty());
    }

    @Test
    void aGroupNameMatchingTheSearchShowsTheWholeGroup() {
        In in = new In().project(BTRACE);
        in.issueContainers.add(BTRACE);
        in.jiraGroups.put(BTRACE, List.of(listedGroup("Sprint", List.of(
                jiraIssue("PROJ-123", "Fix allocation accounting", List.of("profiling"), Optional.of(T))))));
        in.filter = "sprin";

        JiraGroupRow group = assertInstanceOf(JiraGroupRow.class,
                issueNodes(projectRow(TaskTreeModel.build(in.build()), BTRACE)).getFirst());
        assertEquals(List.of("PROJ-123"), group.children().stream().map(TaskTreeModelTest::issueRowId).toList());
    }

    @Test
    void searchMatchesJiraKeysSummariesLabelsAndGitHubNumbers() {
        List<OpenIssue> github = List.of(
                new OpenIssue(9, "Allocation regression", Optional.empty(), List.of("bug"), List.of(),
                        Optional.of(T)));
        List<JiraIssue> jira = List.of(
                jiraIssue("PROJ-123", "Fix allocation accounting", List.of("profiling"), Optional.of(T)),
                jiraIssue("OPS-7", "Inspect the agent loader", List.of("triage"), Optional.of(T)));

        assertEquals(List.of("PROJ-123"), issueIdsMatching("proj-12", github, jira));
        assertEquals(List.of("OPS-7"), issueIdsMatching("inspect", github, jira));
        assertEquals(List.of("OPS-7"), issueIdsMatching("TRIAGE", github, jira));
        assertEquals(List.of("#9"), issueIdsMatching("#9", github, jira));
    }

    private static List<String> issueIdsMatching(String filter, List<OpenIssue> github, List<JiraIssue> jira) {
        In in = new In().project(BTRACE);
        in.issueContainers.add(BTRACE);
        in.issues.put(BTRACE, new IssueListing.Listed(github));
        in.jiraGroups.put(BTRACE, List.of(listedGroup("All", jira)));
        in.filter = filter;

        return issueNodes(projectRow(TaskTreeModel.build(in.build()), BTRACE)).stream()
                .flatMap(TaskTreeModelTest::flattenedIssueIds).toList();
    }

    @Test
    void issuesViewKeepsGitHubRowsFlatAndGroupsBeneathThem() {
        In in = new In().project(BTRACE);
        in.issueContainers.add(BTRACE);
        in.sessions.add(session("Allocation session", BTRACE));
        in.issues.put(BTRACE, new IssueListing.Listed(List.of(
                new OpenIssue(9, "Allocation regression", Optional.empty(), List.of("profiling"), List.of(),
                        Optional.of(T)))));
        in.jiraGroups.put(BTRACE, List.of(listedGroup("Sprint", List.of(
                jiraIssue("PROJ-123", "Fix allocation accounting", List.of("profiling"), Optional.of(T))))));
        in.viewFilter = ViewFilter.ISSUES;

        ProjectRow row = projectRow(TaskTreeModel.build(in.build()), BTRACE);

        assertTrue(section(row, SectionKind.SESSIONS).isEmpty());
        List<TaskNode> children = issueNodes(row);
        assertEquals("#9", issueRowId(children.get(0)));
        JiraGroupRow group = assertInstanceOf(JiraGroupRow.class, children.get(1));
        assertEquals(List.of("PROJ-123"), group.children().stream().map(TaskTreeModelTest::issueRowId).toList());
    }


    @Test
    void activeViewContainsOnlyRunningSessions() {
        Path idle = Path.of("/wt/btrace-idle");
        In in = new In().project(BTRACE).worktree(BTRACE, idle, "unused-worktree");
        ManagedAgentSession active = session("Running task", BTRACE);
        in.sessions.addAll(List.of(active, session("Stopped task", BTRACE.resolve("stopped"))));
        in.running.add(active.id());
        in.issues.put(BTRACE, new IssueListing.Listed(List.of(
                new OpenIssue(9, "Allocation regression", Optional.empty(), List.of(), List.of(), Optional.of(T)))));
        in.viewFilter = ViewFilter.ACTIVE;

        ProjectRow row = projectRow(TaskTreeModel.build(in.build()), BTRACE);

        assertEquals(List.of("Running task"), sessionNodes(row).stream()
                .map(TaskRow.class::cast).map(task -> task.session().displayName()).toList());
        assertTrue(section(row, SectionKind.WORKTREES).isEmpty());
        assertTrue(section(row, SectionKind.ISSUES).isEmpty());
    }

    @Test
    void inactiveViewContainsOnlyStoppedSessionsAndHidesEmptyProjects() {
        In in = new In().project(BTRACE).project(ALPHA).current(BTRACE, BTRACE);
        ManagedAgentSession active = session("Running task", BTRACE);
        in.sessions.addAll(List.of(active, session("Stopped task", ALPHA)));
        in.running.add(active.id());
        in.viewFilter = ViewFilter.INACTIVE;

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        assertEquals(List.of(ALPHA), projectRoots(nodes));
        assertEquals(List.of("Stopped task"), sessionNodes(projectRow(nodes, ALPHA)).stream()
                .map(TaskRow.class::cast).map(task -> task.session().displayName()).toList());
    }

    @Test
    void issuesViewContainsOnlyIssuesAndComposesWithSearch() {
        In in = new In().project(BTRACE).project(ALPHA);
        in.sessions.add(session("Allocation session", BTRACE));
        in.issues.put(BTRACE, new IssueListing.Listed(List.of(
                new OpenIssue(9, "Allocation regression", Optional.empty(), List.of("profiling"), List.of(),
                        Optional.of(T)),
                new OpenIssue(10, "Documentation", Optional.empty(), List.of("docs"), List.of(), Optional.of(T)))));
        in.viewFilter = ViewFilter.ISSUES;
        in.filter = "profiling";

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        assertEquals(List.of(BTRACE), projectRoots(nodes));
        ProjectRow row = projectRow(nodes, BTRACE);
        assertTrue(section(row, SectionKind.SESSIONS).isEmpty());
        assertEquals(List.of(9), issueNodes(row).stream().map(IssueRow.class::cast)
                .map(issue -> issue.issue().number()).toList());
    }

    @Test
    void viewFilterOrderMatchesTheRibbon() {
        assertEquals(List.of("All", "Active", "Inactive", "Needs you", "PRs", "Issues"),
                Arrays.stream(ViewFilter.values()).map(ViewFilter::label).toList());
    }

    @Test
    void needsYouViewContainsOnlyRunningSessionsNeedingAttention() {
        In in = new In().project(ALPHA).project(BTRACE);
        ManagedAgentSession waiting = session("Waiting for you", ALPHA.resolve("a"));
        ManagedAgentSession idle = session("Acknowledged idle", ALPHA.resolve("b"));
        ManagedAgentSession busy = session("Busy", ALPHA.resolve("c"));
        ManagedAgentSession unknown = session("Unknown", ALPHA.resolve("d"));
        ManagedAgentSession stopped = session("Stopped, attention left over", ALPHA.resolve("e"));
        in.sessions.addAll(List.of(waiting, idle, busy, unknown, stopped));
        in.running.addAll(Set.of(waiting.id(), idle.id(), busy.id(), unknown.id()));
        in.activity.put(waiting.id(), Optional.of(SessionActivity.NEEDS_ATTENTION));
        in.activity.put(idle.id(), Optional.of(SessionActivity.IDLE));
        in.activity.put(busy.id(), Optional.of(SessionActivity.BUSY));
        in.activity.put(unknown.id(), Optional.of(SessionActivity.UNKNOWN));
        in.activity.put(stopped.id(), Optional.of(SessionActivity.NEEDS_ATTENTION));
        in.viewFilter = ViewFilter.NEEDS_YOU;

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        // BTRACE has no needs-you session, so it is hidden entirely.
        assertEquals(List.of(ALPHA), projectRoots(nodes));
        assertEquals(List.of("Waiting for you"), sessionNames(nodes, ALPHA));
        assertTrue(section(projectRow(nodes, ALPHA), SectionKind.WORKTREES).isEmpty());
    }

    @Test
    void searchNarrowsInsideTheNeedsYouView() {
        In in = new In().project(ALPHA);
        ManagedAgentSession allocation = session("Allocation waiting", ALPHA.resolve("a"));
        ManagedAgentSession sampling = session("Sampling waiting", ALPHA.resolve("b"));
        in.sessions.addAll(List.of(allocation, sampling));
        in.running.addAll(Set.of(allocation.id(), sampling.id()));
        in.activity.put(allocation.id(), Optional.of(SessionActivity.NEEDS_ATTENTION));
        in.activity.put(sampling.id(), Optional.of(SessionActivity.NEEDS_ATTENTION));
        in.viewFilter = ViewFilter.NEEDS_YOU;
        in.filter = "alloc";

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        assertEquals(List.of("Allocation waiting"), sessionNames(nodes, ALPHA));
    }

    @Test
    void projectScopeAppliesInsideTheNeedsYouView() {
        In in = new In().project(BTRACE).project(ALPHA);
        ManagedAgentSession btraceWaiting = session("Waiting here", BTRACE);
        ManagedAgentSession alphaWaiting = session("Waiting there", ALPHA);
        in.sessions.addAll(List.of(btraceWaiting, alphaWaiting));
        in.running.addAll(Set.of(btraceWaiting.id(), alphaWaiting.id()));
        in.activity.put(btraceWaiting.id(), Optional.of(SessionActivity.NEEDS_ATTENTION));
        in.activity.put(alphaWaiting.id(), Optional.of(SessionActivity.NEEDS_ATTENTION));
        in.viewFilter = ViewFilter.NEEDS_YOU;
        in.filter = "/btr/";

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        assertEquals(List.of(BTRACE), projectRoots(nodes));
        assertEquals(List.of("Waiting here"), sessionNames(nodes, BTRACE));
    }

    @Test
    void prsViewShowsOnlySelectablePullRequestsAndComposesWithSearch() {
        Path taken = Path.of("/wt/drydock-feature");
        In in = new In().project(DRYDOCK).project(ALPHA).worktree(DRYDOCK, taken, "feature/taken");
        in.pullRequestContainers.addAll(Set.of(DRYDOCK, ALPHA));
        in.pullRequests.put(DRYDOCK, new PullRequestListing.Listed(List.of(
                pullRequest(12, "Allocation regression", "feature/alloc", false),
                pullRequest(40, "New one", "feature/new", false),
                pullRequest(41, "A draft", "feature/draft", true),
                pullRequest(42, "Already local", "feature/taken", false))));
        in.pullRequestDedupWorktrees.put(DRYDOCK, List.of(linked(taken, "feature/taken")));
        in.pullRequests.put(ALPHA, new PullRequestListing.Listed(List.of(
                pullRequest(7, "Unrelated", "feature/x", false))));
        in.sessions.add(session("A task", DRYDOCK));
        in.issues.put(DRYDOCK, new IssueListing.Listed(List.of(
                new OpenIssue(9, "Allocation regression", Optional.empty(), List.of(), List.of(),
                        Optional.of(T)))));
        in.viewFilter = ViewFilter.PULL_REQUESTS;
        in.filter = "alloc";

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        // Alpha's single PR does not match the search, so Alpha is hidden.
        assertEquals(List.of(DRYDOCK), projectRoots(nodes));
        ProjectRow row = projectRow(nodes, DRYDOCK);
        assertTrue(section(row, SectionKind.SESSIONS).isEmpty(), "no session rows in the PRs view");
        assertTrue(section(row, SectionKind.WORKTREES).isEmpty(), "worktrees render in All only");
        assertTrue(section(row, SectionKind.ISSUES).isEmpty(), "issues render in All or Issues only");
        assertEquals(List.of(12), pullRequestNodes(row).stream().map(PullRequestRow.class::cast)
                .map(pullRequestRow -> pullRequestRow.pullRequest().number()).toList());
    }

    @Test
    void prsViewShowsTheDeferredRowWhileTheListingHasNotArrived() {
        In in = new In().project(DRYDOCK);
        in.pullRequestContainers.add(DRYDOCK);
        in.viewFilter = ViewFilter.PULL_REQUESTS;

        List<TaskNode> children = section(projectRow(TaskTreeModel.build(in.build()), DRYDOCK),
                SectionKind.PULL_REQUESTS).orElseThrow().children();

        assertEquals(List.of(new TaskNode.Deferred("Loading pull requests…")), children);
    }

    @Test
    void prsViewShowsTheFailedRow() {
        In in = new In().project(DRYDOCK);
        in.pullRequestContainers.add(DRYDOCK);
        in.pullRequests.put(DRYDOCK, new PullRequestListing.Failed("gh pr list exited 1"));
        in.viewFilter = ViewFilter.PULL_REQUESTS;

        List<TaskNode> children = pullRequestNodes(projectRow(TaskTreeModel.build(in.build()), DRYDOCK));

        assertEquals(List.of(new TaskNode.ListProblem("GitHub pull requests: gh pr list exited 1")), children);
    }

    @Test
    void aRunningTaskFromAnotherWorktreeShownInThisWindowIsHere() {
        Path feature = Path.of("/wt/btrace-feature");
        In in = new In().project(BTRACE).worktree(BTRACE, feature, "feature").current(BTRACE, BTRACE);
        ManagedAgentSession task = session("Hosted task", feature);
        in.sessions.add(task);
        in.running.add(task.id());
        in.hosts.put(task.id(), new TaskTreeModel.Host(BTRACE, "btrace"));

        TaskRow row = (TaskRow) sessionNodes(projectRow(TaskTreeModel.build(in.build()), BTRACE)).get(0);

        assertEquals(new TaskPlacement.Here(), row.placement());
    }

    @Test
    void mainCheckoutTaskHasDefaultBranchAndThisWindow() {
        In in = new In().project(BTRACE).current(BTRACE, BTRACE);
        in.sessions.add(session("Explain the agent loader", BTRACE));

        TaskRow row = (TaskRow) sessionNodes(projectRow(TaskTreeModel.build(in.build()), BTRACE)).get(0);

        assertEquals(Optional.of("main"), row.branch());
        assertTrue(row.isThisWindow());
    }

    @Test
    void filterMatchesTaskNameOrBranchCaseInsensitive() {
        Path wt = Path.of("/wt/btrace-jfr");
        In in = new In().project(BTRACE).project(ALPHA).project(DRYDOCK).current(DRYDOCK, DRYDOCK)
                .worktree(BTRACE, wt, "add-jfr-events");
        in.sessions.add(session("Sampling events", wt));
        in.sessions.add(session("Unrelated", ALPHA));
        in.filter = "JFR";

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        assertEquals(List.of(DRYDOCK, BTRACE), projectRoots(nodes));
        assertEquals(1, projectRow(nodes, BTRACE).children().size());
    }

    @Test
    void scopedSearchShowsOnlyGlobMatchingProjectsWhole() {
        In in = new In().project(BTRACE).project(ALPHA);
        in.sessions.add(session("Sampling events", BTRACE));
        in.sessions.add(session("Sampling in alpha", ALPHA));
        in.filter = "/btr/";

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        assertEquals(List.of(BTRACE), projectRoots(nodes));
        // The item filter is empty inside the matched project: everything shows.
        assertEquals(List.of("Sampling events"), sessionNames(nodes, BTRACE));
    }

    @Test
    void scopedSearchWithItemFilterFiltersInsideTheMatchingProjectsOnly() {
        In in = new In().project(BTRACE).project(ALPHA);
        in.sessions.add(session("Sampling events", BTRACE));
        in.sessions.add(session("Sampling in alpha", ALPHA));
        in.filter = "/btr/sampling";

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        // Alpha's task matches the item text but its project is out of scope.
        assertEquals(List.of(BTRACE), projectRoots(nodes));
        assertEquals(List.of("Sampling events"), sessionNames(nodes, BTRACE));
    }

    @Test
    void unclosedScopeFiltersProjectsOnly() {
        In in = new In().project(BTRACE).project(ALPHA);
        in.sessions.add(session("Sampling events", BTRACE));
        in.filter = "/btr";

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        assertEquals(List.of(BTRACE), projectRoots(nodes));
        assertEquals(List.of("Sampling events"), sessionNames(nodes, BTRACE));
    }

    @Test
    void globWildcardsMatchProjectNames() {
        In in = new In().project(BTRACE).project(ALPHA);
        in.filter = "/b*ce/";

        assertEquals(List.of(BTRACE), projectRoots(TaskTreeModel.build(in.build())));
    }

    @Test
    void questionMarkGlobMatchesExactlyOneCharacter() {
        In in = new In().project(BTRACE).project(ALPHA);
        in.filter = "/b?race/";

        assertEquals(List.of(BTRACE), projectRoots(TaskTreeModel.build(in.build())));
    }

    @Test
    void aLoneSlashIsAnEmptyGlobMatchingEveryProject() {
        In in = new In().project(BTRACE).project(ALPHA);
        in.sessions.add(session("Sampling events", BTRACE));
        in.sessions.add(session("Sampling in alpha", ALPHA));
        in.filter = "/";

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        assertEquals(Set.of(BTRACE, ALPHA), Set.copyOf(projectRoots(nodes)));
        assertEquals(List.of("Sampling events"), sessionNames(nodes, BTRACE));
        assertEquals(List.of("Sampling in alpha"), sessionNames(nodes, ALPHA));
    }

    @Test
    void anEmptyGlobWithAnItemFilterFiltersItemsInEveryProject() {
        In in = new In().project(BTRACE).project(ALPHA);
        in.sessions.add(session("Allocation events", BTRACE));
        in.sessions.add(session("Sampling events", BTRACE));
        in.sessions.add(session("Allocation in alpha", ALPHA));
        in.sessions.add(session("Sampling in alpha", ALPHA));
        in.filter = "//alloc";

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        assertEquals(Set.of(BTRACE, ALPHA), Set.copyOf(projectRoots(nodes)));
        assertEquals(List.of("Allocation events"), sessionNames(nodes, BTRACE));
        assertEquals(List.of("Allocation in alpha"), sessionNames(nodes, ALPHA));
    }

    @Test
    void scopedSearchComposesWithTheActiveView() {
        In in = new In().project(BTRACE).project(ALPHA);
        ManagedAgentSession runningBtrace = session("Running btrace", BTRACE);
        ManagedAgentSession runningAlpha = session("Running alpha", ALPHA);
        in.sessions.addAll(List.of(runningBtrace, session("Stopped btrace", BTRACE.resolve("stopped")),
                runningAlpha));
        in.running.addAll(List.of(runningBtrace.id(), runningAlpha.id()));
        in.viewFilter = ViewFilter.ACTIVE;
        in.filter = "/btr/";

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        // Alpha has a running session but is out of scope; btrace's stopped one is out of view.
        assertEquals(List.of(BTRACE), projectRoots(nodes));
        assertEquals(List.of("Running btrace"), sessionNames(nodes, BTRACE));
    }

    @Test
    void plainSearchMatchingAProjectNameShowsTheWholeProject() {
        Path other = Path.of("/wt/btrace-other");
        In in = new In().project(BTRACE).project(ALPHA).worktree(BTRACE, other, "other");
        in.sessions.add(session("Sampling events", BTRACE));
        in.sessions.add(session("Unrelated", other));
        in.filter = "btrace";

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        // Alpha matches neither by name nor by items, so it stays hidden.
        assertEquals(List.of(BTRACE), projectRoots(nodes));
        // Both sessions show: the project name matched, so the whole project is the answer.
        assertEquals(List.of("Sampling events", "Unrelated"), sessionNames(nodes, BTRACE));
    }

    @Test
    void scopeMatchingIsCaseInsensitive() {
        In in = new In().project(BTRACE);
        in.filter = "/BTR/";

        assertEquals(List.of(BTRACE), projectRoots(TaskTreeModel.build(in.build())));
    }

    private static List<String> sessionNames(List<TaskNode> nodes, Path root) {
        return sessionNodes(projectRow(nodes, root)).stream()
                .map(TaskRow.class::cast).map(task -> task.session().displayName()).toList();
    }

    @Test
    void aProjectCountsItsRunningTasksEvenWhenTheSearchHidesThem() {
        In in = new In().project(BTRACE).current(BTRACE, BTRACE);
        ManagedAgentSession hidden = session("Unrelated work", BTRACE.resolve("a"));
        in.sessions.add(hidden);
        in.running.add(hidden.id());
        in.filter = "nothing matches this";

        ProjectRow row = projectRow(TaskTreeModel.build(in.build()), BTRACE);

        assertEquals(List.of(), row.children());
        assertEquals(1, row.running(), "Remove's warning must not depend on the search box");
    }

    @Test
    void missingProjectFolderIsShownNotDropped() {
        In in = new In().project(ALPHA).project(BTRACE);
        in.missing.add(BTRACE);

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        assertTrue(projectRow(nodes, BTRACE).folderMissing());
        assertEquals(List.of(ALPHA, BTRACE), projectRoots(nodes));
    }

    @Test
    void sessionWithUnresolvableWorkingDirectoryFallsBackToPathPrefix() {
        In in = new In().project(BTRACE);
        Path removedElsewhere = BTRACE.resolve("nested/dir");
        in.sessions.add(session("Orphaned by a manual worktree remove", removedElsewhere));

        ProjectRow row = projectRow(TaskTreeModel.build(in.build()), BTRACE);

        assertEquals(1, sessionNodes(row).size());
        assertEquals(Optional.empty(), ((TaskRow) sessionNodes(row).get(0)).branch());
    }

    @Test
    void sessionWhoseWorktreeVanishedStaysUnderItsRecordedProject() {
        // The default layout: <base>/<repo>-<slug>, never under the main
        // checkout, so no path prefix can place it once git forgets it.
        In in = new In().project(DRYDOCK);
        ManagedAgentSession orphan = session("Removed outside Drydock", Path.of("/wt/drydock-gone"));
        in.sessions.add(orphan);
        in.recordedRoots.put(orphan.repositoryId(), DRYDOCK);

        ProjectRow row = projectRow(TaskTreeModel.build(in.build()), DRYDOCK);

        assertEquals(1, sessionNodes(row).size());
        assertEquals(orphan.id(), ((TaskRow) sessionNodes(row).get(0)).session().id());
    }

    @Test
    void sessionsOfUnregisteredOtherProjectsAreHidden() {
        In in = new In().project(ALPHA).current(ALPHA, ALPHA);
        in.mainRootOf.put(BTRACE, BTRACE);
        in.sessions.add(session("Somewhere else", BTRACE));

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        assertEquals(1, nodes.size());
        assertEquals(List.of(), projectRow(nodes, ALPHA).children());
    }

    @Test
    void terminalAgentSessionsAreHidden() {
        In in = new In().project(ALPHA);
        in.sessions.add(session("A shell", ALPHA, AgentKind.TERMINAL, T));

        assertEquals(List.of(), projectRow(TaskTreeModel.build(in.build()), ALPHA).children());
    }

    @Test
    void taskRowsCarryTheModelSResumabilityAnswer() {
        In in = new In().project(ALPHA);
        ManagedAgentSession gone = session("Conversation gone", ALPHA.resolve("gone"))
                .withAgentSessionId(Optional.of("cli-gone"));
        ManagedAgentSession cold = session("Cold answer", ALPHA.resolve("cold"))
                .withAgentSessionId(Optional.of("cli-cold"));
        ManagedAgentSession fresh = session("Never started", ALPHA.resolve("fresh"));
        ManagedAgentSession live = session("Live session", ALPHA.resolve("live"))
                .withAgentSessionId(Optional.of("cli-live"));
        in.sessions.addAll(List.of(gone, cold, fresh, live));
        in.running.add(live.id());
        in.resumable = Map.of(gone.id(), false, live.id(), true);

        List<TaskNode> rows = sessionNodes(projectRow(TaskTreeModel.build(in.build()), ALPHA));

        // A warmed answer wins; cold, a stopped session with an agent id keeps
        // Resume; no agent id, nothing to resume; running is never resumable.
        Map<String, Boolean> byName = new HashMap<>();
        rows.forEach(node -> {
            TaskRow task = (TaskRow) node;
            byName.put(task.session().displayName(), task.resumable());
        });
        assertFalse(byName.get("Conversation gone"), "the warmed cache answer wins");
        assertTrue(byName.get("Cold answer"), "cold, a stopped session with an agent id keeps Resume");
        assertFalse(byName.get("Never started"), "no agent session id means nothing to resume into");
        assertFalse(byName.get("Live session"), "a running session is never resumable");
    }

    @Test
    void needsYouFirstThenRunningThenMostRecent() {
        In in = new In().project(ALPHA);
        ManagedAgentSession old = session("old", ALPHA, AgentKind.CLAUDE, T);
        ManagedAgentSession recent = session("recent", ALPHA.resolve("a"), AgentKind.CLAUDE, T.plusSeconds(60));
        ManagedAgentSession running = session("running", ALPHA.resolve("b"), AgentKind.CLAUDE, T);
        ManagedAgentSession waiting = session("waiting", ALPHA.resolve("c"), AgentKind.CLAUDE, T);
        in.sessions.addAll(List.of(old, recent, running, waiting));
        in.running.addAll(List.of(running.id(), waiting.id()));
        in.activity.put(waiting.id(), Optional.of(SessionActivity.NEEDS_ATTENTION));

        ProjectRow project = projectRow(TaskTreeModel.build(in.build()), ALPHA);
        Section sessions = section(project, SectionKind.SESSIONS).orElseThrow();
        assertEquals(List.of(SectionKind.ACTIVE, SectionKind.INACTIVE), sessions.children().stream()
                .map(Section.class::cast).map(Section::kind).toList());
        List<String> order = sessionNodes(project).stream()
                .map(node -> ((TaskRow) node).session().displayName()).toList();

        assertEquals(List.of("waiting", "running", "recent", "old"), order);
    }

    @Test
    void pullRequestsExcludeDraftsAndCheckedOutOnesNewestFirst() {
        In in = new In().project(DRYDOCK).worktree(DRYDOCK, Path.of("/wt/drydock-feature"), "feature/taken");
        in.pullRequestContainers.add(DRYDOCK);
        in.pullRequests.put(DRYDOCK, new PullRequestListing.Listed(List.of(
                pullRequest(12, "Old one", "feature/old", false),
                pullRequest(40, "New one", "feature/new", false),
                pullRequest(41, "A draft", "feature/draft", true),
                pullRequest(42, "Already local", "feature/taken", false))));
        in.pullRequestDedupWorktrees.put(DRYDOCK, List.of(linked(Path.of("/wt/drydock-feature"), "feature/taken")));

        List<TaskNode> rows = pullRequestNodes(projectRow(TaskTreeModel.build(in.build()), DRYDOCK));

        assertEquals(List.of(40, 12), rows.stream().map(PullRequestRow.class::cast)
                .map(row -> row.pullRequest().number()).toList());
    }

    @Test
    void aCheckedOutPrBranchIsRecognisedAsItsPullRequest() {
        In in = new In().project(DRYDOCK);
        in.pullRequestContainers.add(DRYDOCK);
        in.pullRequests.put(DRYDOCK, new PullRequestListing.Listed(List.of(
                pullRequest(7, "Checked out under pr-7", "feature/x", false))));
        // The worktree holds the pr-<n> branch a drydock checkout creates,
        // not the PR's own head branch.
        in.pullRequestDedupWorktrees.put(DRYDOCK, List.of(linked(Path.of("/wt/drydock-pr-7"), "pr-7")));

        ProjectRow row = projectRow(TaskTreeModel.build(in.build()), DRYDOCK);

        assertTrue(section(row, SectionKind.PULL_REQUESTS).isEmpty());
    }

    @Test
    void pullRequestSearchMatchesNumberTitleAndBranch() {
        List<OpenPullRequest> pullRequests = List.of(
                pullRequest(9, "Number-only row", "feature/number-only", false),
                pullRequest(10, "Branch-only row", "feature/branch-probe", false),
                pullRequest(11, "Title probe row", "feature/title-only", false),
                pullRequest(12, "Unrelated", "feature/unrelated", false));

        assertEquals(List.of(9), pullRequestNumbersMatching("#9", pullRequests));
        assertEquals(List.of(10), pullRequestNumbersMatching("branch-probe", pullRequests));
        assertEquals(List.of(11), pullRequestNumbersMatching("Title probe", pullRequests));
        // A filter that matches nothing hides the project row entirely
        // (the same rule tasks follow), so no project row is the answer.
        In none = new In().project(DRYDOCK);
        none.pullRequestContainers.add(DRYDOCK);
        none.pullRequests.put(DRYDOCK, new PullRequestListing.Listed(pullRequests));
        none.filter = "missing";
        assertEquals(List.of(), projectRoots(TaskTreeModel.build(none.build())));
    }

    private static List<Integer> pullRequestNumbersMatching(String filter, List<OpenPullRequest> pullRequests) {
        In in = new In().project(DRYDOCK);
        in.pullRequestContainers.add(DRYDOCK);
        in.pullRequests.put(DRYDOCK, new PullRequestListing.Listed(pullRequests));
        in.filter = filter;

        return pullRequestNodes(projectRow(TaskTreeModel.build(in.build()), DRYDOCK)).stream()
                .map(PullRequestRow.class::cast)
                .map(row -> row.pullRequest().number())
                .toList();
    }

    @Test
    void unloadedPullRequestsStayDeferredWhileSessionsArePresent() {
        In in = new In().project(DRYDOCK);
        in.pullRequestContainers.add(DRYDOCK);
        in.sessions.add(session("A task", DRYDOCK));

        List<TaskNode> children = section(projectRow(TaskTreeModel.build(in.build()), DRYDOCK),
                SectionKind.PULL_REQUESTS).orElseThrow().children();

        assertEquals(List.of(new TaskNode.Deferred("Loading pull requests…")), children);
    }

    @Test
    void failedPullRequestListingIsAProblemRowInsideItsSection() {
        In in = new In().project(DRYDOCK);
        in.pullRequestContainers.add(DRYDOCK);
        in.pullRequests.put(DRYDOCK, new PullRequestListing.Failed("gh pr list exited 1"));

        List<TaskNode> children = pullRequestNodes(projectRow(TaskTreeModel.build(in.build()), DRYDOCK));

        assertEquals(List.of(new TaskNode.ListProblem("GitHub pull requests: gh pr list exited 1")), children);
    }

    @Test
    void unsupportedPullRequestListingGetsNoSection() {
        In in = new In().project(DRYDOCK);
        in.pullRequestContainers.add(DRYDOCK);
        in.pullRequests.put(DRYDOCK, new PullRequestListing.Unsupported());

        assertTrue(section(projectRow(TaskTreeModel.build(in.build()), DRYDOCK),
                SectionKind.PULL_REQUESTS).isEmpty());
    }
}
