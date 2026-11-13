package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.GhCliService;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.JiraIssue;
import app.drydock.git.GhCliService.OpenPullRequest;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.IssueRow;
import app.drydock.intellij.tasks.TaskNode.JiraGroupRow;
import app.drydock.intellij.tasks.TaskNode.JiraIssueRow;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.PullRequestRow;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.SectionKind;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.tasks.TaskNode.WorktreeGroup;
import app.drydock.projects.SharedProject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure layout of the tree's context menu: {@link TaskMenu#sections} with
 * injected {@link TaskMenu.MenuFacts} — order per node kind, no empty
 * section, destructive items last, and no entry that would only go to the
 * destination the user is already in. Never touches the plugin service or
 * the registry; {@code factsFor} is not called here.
 */
class TaskMenuTest {

    private static final Path ROOT = Path.of("/work/profiling-backend");
    private static final Path WORKTREE = Path.of("/work/profiling-backend-fix");
    private static final Instant NOW = Instant.parse("2026-10-20T17:00:00Z");

    private static final TaskMenu.MenuFacts TRANSFER = new TaskMenu.MenuFacts(true, false);
    private static final TaskMenu.MenuFacts NO_TRANSFER = new TaskMenu.MenuFacts(false, false);
    private static final TaskMenu.MenuFacts JIRA = new TaskMenu.MenuFacts(false, true);
    private static final TaskMenu.MenuFacts WORKSPACE_BOX = new TaskMenu.MenuFacts(false, false, false, true);
    /** The facts of an ALIVE-detached workspace-box row: the remote Stop. */
    private static final TaskMenu.MenuFacts REMOTE_STOP =
            new TaskMenu.MenuFacts(false, false, false, false, true, false, false, null, null, null);

    private static TaskRow task(boolean running, TaskPlacement placement) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), "Fix allocation profile",
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(WORKTREE), NOW)
                .build();
        return new TaskRow(session, Optional.empty(), running, Optional.of("fix-allocation-profile"),
                placement, false, false, false);
    }

    /** The same row carrying a branch pull request; {@code url} is what gh reported for its page. */
    private static TaskRow rowWithPullRequest(TaskRow row, Optional<String> url) {
        return new TaskRow(row.session(), row.activity(), row.running(), row.branch(), row.placement(),
                row.readOnly(), row.folderMissing(), row.resumable(),
                Optional.of(new GhCliService.BranchPullRequest(9,
                        GhCliService.PrInfo.PrLifecycle.OPEN, false, "fix-allocation-profile",
                        "Fix allocation profile", url)));
    }

    private static IdleWorktree worktree(boolean locked, boolean open) {
        return new IdleWorktree(WORKTREE, "fix-allocation-profile", Optional.empty(), locked, open);
    }

    private static ProjectRow project(IdleWorktree... inactive) {
        return new ProjectRow(SharedProject.local(RepositoryId.newId(), ROOT, "profiling-backend", NOW),
                false, false, List.of(), List.of(inactive), 0);
    }

    private static IssueRow issue(Optional<String> url) {
        return new IssueRow(ROOT, new OpenIssue(42, "Fix allocation profile", url, List.of(), List.of(),
                Optional.of(NOW)));
    }

    private static JiraIssueRow jiraIssue() {
        return new JiraIssueRow(ROOT, new JiraIssue("PROJ-123", "Fix allocation profile",
                "https://acme.atlassian.net/browse/PROJ-123", List.of(), Optional.of(NOW)));
    }

    private static PullRequestRow pullRequest(Optional<String> url) {
        return new PullRequestRow(ROOT, new OpenPullRequest(40, "Speed up the flaky suite",
                "pr-40", "main", false, Optional.of("reviewer"), url));
    }

    private static List<List<TaskMenu.Item>> sectionsOf(TaskNode lead) {
        return TaskMenu.sections(lead, List.of(), NO_TRANSFER);
    }

    private static final TaskMenu.MenuFacts SESSION_LINKS =
            new TaskMenu.MenuFacts(false, false, false, true, true);

    @Test
    void aTaskRowWithAttachedLinksOffersOpenInGithubAndJira() {
        // The link section sits with the row's management items, before the
        // cleanup section; each entry follows its own fact.
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK),
                List.of(TaskMenu.Item.RESUME),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.FLAG, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.OPEN_IN_GITHUB, TaskMenu.Item.OPEN_IN_JIRA),
                List.of(TaskMenu.Item.DONE_WITH_TASK)),
                TaskMenu.sections(task(false, new TaskPlacement.Idle(true)), List.of(), SESSION_LINKS));
        // Only GitHub known: no Jira entry, and never an empty section.
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK),
                List.of(TaskMenu.Item.RESUME),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.FLAG, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.OPEN_IN_GITHUB),
                List.of(TaskMenu.Item.DONE_WITH_TASK)),
                TaskMenu.sections(task(false, new TaskPlacement.Idle(true)), List.of(),
                        new TaskMenu.MenuFacts(false, false, false, true, false)));
    }

    @Test
    void aRunningTaskRowWithAttachedLinksOffersThemBeforeCleanup() {
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK),
                List.of(TaskMenu.Item.DEACTIVATE, TaskMenu.Item.REVIEW_CHANGES),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.FLAG, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.OPEN_IN_JIRA),
                List.of(TaskMenu.Item.DEACTIVATE_AND_CLOSE, TaskMenu.Item.DONE_WITH_TASK)),
                TaskMenu.sections(task(true, new TaskPlacement.ThisWindow()), List.of(),
                        new TaskMenu.MenuFacts(false, false, false, false, true)));
    }

    @Test
    void aSessionRowsGithubLinksAreItsPullRequestPlusItsMirrors() {
        // Pure over the given mirrors: the PR's page first, then the GitHub
        // mirrors in order; Jira mirrors and unknown addresses stay out.
        TaskRow row = task(false, new TaskPlacement.Idle(true));
        assertEquals(List.of("https://github.com/o/r/pull/9", "https://github.com/o/r/issues/12"),
                TaskMenu.githubUrlsOf(rowWithPullRequest(row, Optional.of("https://github.com/o/r/pull/9")),
                        List.of("https://github.com/o/r/issues/12",
                                "https://acme.atlassian.net/browse/PROJ-7")));
        assertEquals(List.of("https://acme.atlassian.net/browse/PROJ-7"),
                TaskMenu.jiraUrlsOf(rowWithPullRequest(row, Optional.empty()),
                        List.of("https://github.com/o/r/issues/12",
                                "https://acme.atlassian.net/browse/PROJ-7")));
        // No pull request, no mirrors: nothing to offer.
        assertTrue(TaskMenu.githubUrlsOf(rowWithPullRequest(row, Optional.empty()), List.of()).isEmpty());
    }

    @Test
    void issueAndPullRequestRowsKeepTheirOwnUrlAsTheGithubLink() {
        assertEquals(List.of("https://github.com/o/r/issues/42"),
                TaskMenu.githubUrlsOf(issue(Optional.of("https://github.com/o/r/issues/42")), List.of()));
        assertEquals(List.of("https://github.com/o/r/pull/40"),
                TaskMenu.githubUrlsOf(pullRequest(Optional.of("https://github.com/o/r/pull/40")), List.of()));
        assertEquals(List.of("https://acme.atlassian.net/browse/PROJ-123"),
                TaskMenu.jiraUrlsOf(jiraIssue(), List.of()));
        assertEquals(List.of(), TaskMenu.jiraUrlsOf(issue(Optional.empty()), List.of()));
    }

    @Test
    void runningTaskShownElsewhereGetsEveryConditionalItem() {
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK, TaskMenu.Item.SHOW_HERE),
                List.of(TaskMenu.Item.DEACTIVATE, TaskMenu.Item.REVIEW_CHANGES, TaskMenu.Item.TRANSFER_TO),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.FLAG, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.DEACTIVATE_AND_CLOSE, TaskMenu.Item.DONE_WITH_TASK)),
                TaskMenu.sections(task(true, new TaskPlacement.OwnWindow()), List.of(), TRANSFER));
    }

    @Test
    void runningTaskInAnotherProjectWindowGetsShowHereButNotItsOwnClose() {
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK, TaskMenu.Item.SHOW_HERE),
                List.of(TaskMenu.Item.DEACTIVATE, TaskMenu.Item.REVIEW_CHANGES),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.FLAG, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.DONE_WITH_TASK)),
                sectionsOf(task(true, new TaskPlacement.OtherWindow("profiling-rt"))));
    }

    @Test
    void runningTaskHostedHereHasNoShowHereAndNoOwnWindowClose() {
        // The console is already here, and this window is another project's —
        // closing it would close what the user is working in.
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK),
                List.of(TaskMenu.Item.DEACTIVATE, TaskMenu.Item.REVIEW_CHANGES),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.FLAG, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.DONE_WITH_TASK)),
                sectionsOf(task(true, new TaskPlacement.Here())));
    }

    @Test
    void runningTaskInThisWindowsWorktreeOffersItsOwnClose() {
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK),
                List.of(TaskMenu.Item.DEACTIVATE, TaskMenu.Item.REVIEW_CHANGES),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.FLAG, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.DEACTIVATE_AND_CLOSE, TaskMenu.Item.DONE_WITH_TASK)),
                sectionsOf(task(true, new TaskPlacement.ThisWindow())));
    }

    @Test
    void runningTaskWithNoWindowGetsNeitherShowHereNorItsOwnClose() {
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK),
                List.of(TaskMenu.Item.DEACTIVATE, TaskMenu.Item.REVIEW_CHANGES),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.FLAG, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.DONE_WITH_TASK)),
                sectionsOf(task(true, new TaskPlacement.NotShown())));
    }

    @Test
    void transferFollowsTheInjectedFactsOnly() {
        TaskRow row = task(true, new TaskPlacement.ThisWindow());
        assertTrue(TaskMenu.sections(row, List.of(), TRANSFER).get(1)
                .contains(TaskMenu.Item.TRANSFER_TO));
        assertTrue(TaskMenu.sections(row, List.of(), NO_TRANSFER).stream()
                .noneMatch(section -> section.contains(TaskMenu.Item.TRANSFER_TO)));
    }

    @Test
    void idleTaskOfAnotherWorktreeOffersItsOwnWindow() {
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK),
                List.of(TaskMenu.Item.RESUME, TaskMenu.Item.RESUME_IN_OWN_WINDOW),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.FLAG, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.DONE_WITH_TASK)),
                sectionsOf(task(false, new TaskPlacement.Idle(false))));
    }

    @Test
    void idleTaskOfThisWindowsWorktreeHasNoOwnWindowVariant() {
        // Its own window would be this one; plain Resume already goes there.
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK),
                List.of(TaskMenu.Item.RESUME),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.FLAG, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.DONE_WITH_TASK)),
                sectionsOf(task(false, new TaskPlacement.Idle(true))));
    }

    @Test
    void anIdleRemoteRowWithTheAliveFactGainsTheStopBesideItsResume() {
        // The restart scenario (review R3 defect 1): the row runs nowhere in
        // this IDE but its box-bound agent is ALIVE — the menu keeps the
        // honest Resume and adds the Stop ahead of it, routed to the same
        // remote Deactivate the strip and the tab use, so the live agent is
        // never stranded behind a Resume-only menu.
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK),
                List.of(TaskMenu.Item.DEACTIVATE, TaskMenu.Item.RESUME, TaskMenu.Item.RESUME_IN_OWN_WINDOW),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.FLAG, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.DONE_WITH_TASK)),
                TaskMenu.sections(task(false, new TaskPlacement.Idle(false)), List.of(), REMOTE_STOP));
        // This window's own worktree keeps its plain Resume line; the Stop
        // joins the same section.
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK),
                List.of(TaskMenu.Item.DEACTIVATE, TaskMenu.Item.RESUME),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.FLAG, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.DONE_WITH_TASK)),
                TaskMenu.sections(task(false, new TaskPlacement.Idle(true)), List.of(), REMOTE_STOP));
    }

    @Test
    void theRemoteStopFactChangesNoOtherRowsMenu() {
        // Only an idle task row carries the Stop; every other row — and every
        // running task, whose menu already has it — keeps its byte-identical
        // list under the same facts.
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK),
                List.of(TaskMenu.Item.DEACTIVATE, TaskMenu.Item.REVIEW_CHANGES),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.FLAG, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.DONE_WITH_TASK)),
                TaskMenu.sections(task(true, new TaskPlacement.Here()), List.of(), REMOTE_STOP));
        List<TaskNode> rows = List.of(
                issue(Optional.empty()),
                jiraIssue(),
                pullRequest(Optional.empty()),
                worktree(false, false),
                project(worktree(false, false)),
                new TaskNode.StaleWorktrees(List.of(worktree(false, false))),
                new WorktreeGroup(WORKTREE, "fix-allocation-profile",
                        List.of(task(false, new TaskPlacement.Idle(false)))),
                new Section(SectionKind.ISSUES, List.of(issue(Optional.empty()))));
        for (TaskNode row : rows) {
            assertEquals(TaskMenu.sections(row, List.of(), NO_TRANSFER),
                    TaskMenu.sections(row, List.of(), REMOTE_STOP),
                    "the Stop fact must not touch this row: " + row.getClass().getSimpleName());
        }
    }

    @Test
    void aLocalIdleRowWithoutTheFactKeepsItsExactPreRemoteMenu() {
        // Every legacy MenuFacts shape carries no remote Stop, so a local
        // idle row's menu is the byte-identical pre-remote list — the fact
        // is computed from the row's own box binding and liveness, never
        // defaulted on.
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK),
                List.of(TaskMenu.Item.RESUME, TaskMenu.Item.RESUME_IN_OWN_WINDOW),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.FLAG, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.DONE_WITH_TASK)),
                TaskMenu.sections(task(false, new TaskPlacement.Idle(false)), List.of(), WORKSPACE_BOX));
        assertFalse(TaskMenu.sections(task(false, new TaskPlacement.Idle(false)), List.of(), WORKSPACE_BOX)
                .stream().anyMatch(section -> section.contains(TaskMenu.Item.DEACTIVATE)));
    }

    @Test
    void pullRequestRowOffersTheWorkbenchNewTaskThenGitHub() {
        // The PR is reviewed from its workbench, whose Review menu asks for an agent review;
        // New Task starts a task on the PR's checked-out branch instead.
        assertEquals(List.of(
                List.of(TaskMenu.Item.OPEN_IN_WORKBENCH),
                List.of(TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.OPEN_IN_GITHUB)),
                sectionsOf(pullRequest(Optional.of("https://github.com/DataDog/profiling-backend/pull/40"))));
        // No URL, no last section: never an empty one.
        assertEquals(List.of(List.of(TaskMenu.Item.OPEN_IN_WORKBENCH), List.of(TaskMenu.Item.NEW_TASK)),
                sectionsOf(pullRequest(Optional.empty())));
    }

    @Test
    void issueRowOffersImplementTriageThenGitHubSettings() {
        assertEquals(List.of(
                List.of(TaskMenu.Item.IMPLEMENT, TaskMenu.Item.TRIAGE),
                List.of(TaskMenu.Item.OPEN_IN_GITHUB, TaskMenu.Item.GITHUB_SETTINGS)),
                sectionsOf(issue(Optional.of("https://github.com/DataDog/profiling-backend/issues/42"))));
        assertEquals(List.of(
                List.of(TaskMenu.Item.IMPLEMENT, TaskMenu.Item.TRIAGE),
                List.of(TaskMenu.Item.GITHUB_SETTINGS)),
                sectionsOf(issue(Optional.empty())));
    }

    @Test
    void jiraRowOffersImplementTriageThenOpenInJiraAndSettings() {
        assertEquals(List.of(
                List.of(TaskMenu.Item.IMPLEMENT, TaskMenu.Item.TRIAGE),
                List.of(TaskMenu.Item.OPEN_IN_JIRA, TaskMenu.Item.JIRA_SETTINGS)),
                sectionsOf(jiraIssue()));
    }

    @Test
    void jiraGroupRowOffersEditDeleteThenRefreshAndNothingElse() {
        JiraGroupRow group = new JiraGroupRow(ROOT,
                new JiraGroup("g-1", "Sprint", "project = DRY"), List.of(jiraIssue()));
        assertEquals(List.of(
                List.of(TaskMenu.Item.EDIT_JIRA_GROUP, TaskMenu.Item.DELETE_JIRA_GROUP),
                List.of(TaskMenu.Item.REFRESH)),
                sectionsOf(group));
    }

    @Test
    void addJiraGroupIsOfferedOnlyWhenTheFactsSayJiraIsEnabled() {
        TaskNode issues = new Section(SectionKind.ISSUES, List.of(issue(Optional.empty())));
        assertEquals(List.of(List.of(TaskMenu.Item.REFRESH, TaskMenu.Item.ADD_JIRA_GROUP,
                        TaskMenu.Item.GITHUB_SETTINGS, TaskMenu.Item.JIRA_SETTINGS)),
                TaskMenu.sections(issues, List.of(), JIRA));
        // Disabled Jira: the old menu, Add would only ever fetch nothing.
        assertEquals(List.of(List.of(TaskMenu.Item.REFRESH, TaskMenu.Item.GITHUB_SETTINGS,
                        TaskMenu.Item.JIRA_SETTINGS)),
                sectionsOf(issues));
        assertEquals(List.of(
                List.of(TaskMenu.Item.NEW_TASK, TaskMenu.Item.NEW_TASK_IN_MAIN),
                List.of(TaskMenu.Item.FOCUS_PROJECT),
                List.of(TaskMenu.Item.REFRESH, TaskMenu.Item.GITHUB_SETTINGS, TaskMenu.Item.JIRA_SETTINGS,
                        TaskMenu.Item.ADD_JIRA_GROUP),
                List.of(TaskMenu.Item.REMOVE_FROM_DRYDOCK)),
                TaskMenu.sections(project(), List.of(), JIRA));
    }

    @Test
    void idleWorktreeOffersRemoveOnlyWhenItIsNeitherLockedNorOpen() {
        assertEquals(List.of(
                List.of(TaskMenu.Item.NEW_TASK, TaskMenu.Item.QUICK_REVIEW, TaskMenu.Item.REVIEW, TaskMenu.Item.REVIEW_IN_SPHINX),
                List.of(TaskMenu.Item.REMOVE_WORKTREE)),
                sectionsOf(worktree(false, false)));
        assertEquals(List.of(List.of(TaskMenu.Item.NEW_TASK, TaskMenu.Item.QUICK_REVIEW, TaskMenu.Item.REVIEW, TaskMenu.Item.REVIEW_IN_SPHINX)),
                sectionsOf(worktree(true, false)));
        assertEquals(List.of(List.of(TaskMenu.Item.NEW_TASK, TaskMenu.Item.QUICK_REVIEW, TaskMenu.Item.REVIEW, TaskMenu.Item.REVIEW_IN_SPHINX)),
                sectionsOf(worktree(false, true)));
    }

    @Test
    void staleWorktreesRowOffersOnlyItsOwnRemoval() {
        assertEquals(List.of(List.of(TaskMenu.Item.REMOVE_STALE_WORKTREES)),
                sectionsOf(new TaskNode.StaleWorktrees(List.of(worktree(false, false)))));
    }

    @Test
    void worktreeGroupRowOffersOnlyANewTask() {
        assertEquals(List.of(List.of(TaskMenu.Item.NEW_TASK)),
                sectionsOf(new WorktreeGroup(WORKTREE, "fix-allocation-profile",
                        List.of(task(false, new TaskPlacement.Idle(false))))));
    }

    @Test
    void listingSectionsOfferRefreshAndSettingsOthersNothing() {
        assertEquals(List.of(List.of(TaskMenu.Item.REFRESH, TaskMenu.Item.GITHUB_SETTINGS,
                        TaskMenu.Item.JIRA_SETTINGS)),
                sectionsOf(new Section(SectionKind.ISSUES, List.of(issue(Optional.empty())))));
        assertEquals(List.of(List.of(TaskMenu.Item.REFRESH, TaskMenu.Item.GITHUB_SETTINGS)),
                sectionsOf(new Section(SectionKind.PULL_REQUESTS, List.of(pullRequest(Optional.empty())))));
        assertEquals(List.of(List.of(TaskMenu.Item.CLEANUP_DONE_SESSIONS)),
                sectionsOf(new Section(SectionKind.SESSIONS, List.of(
                        task(true, new TaskPlacement.ThisWindow())))));
        assertEquals(List.of(), sectionsOf(new Section(SectionKind.WORKTREES, List.of(worktree(false, false)))));
    }

    @Test
    void sessionsSectionOffersOnlyItsBulkCleanup() {
        assertEquals(List.of(List.of(TaskMenu.Item.CLEANUP_DONE_SESSIONS)),
                sectionsOf(new Section(SectionKind.SESSIONS, List.of(
                        task(true, new TaskPlacement.ThisWindow())))));
    }

    @Test
    void projectRowOffersInactiveRemovalOnlyWhenOneApplies() {
        assertEquals(List.of(
                List.of(TaskMenu.Item.NEW_TASK, TaskMenu.Item.NEW_TASK_IN_MAIN),
                List.of(TaskMenu.Item.FOCUS_PROJECT),
                List.of(TaskMenu.Item.REFRESH, TaskMenu.Item.GITHUB_SETTINGS, TaskMenu.Item.JIRA_SETTINGS),
                List.of(TaskMenu.Item.REMOVE_INACTIVE_WORKTREES, TaskMenu.Item.REMOVE_FROM_DRYDOCK)),
                sectionsOf(project(worktree(false, false))));
        assertEquals(List.of(
                List.of(TaskMenu.Item.NEW_TASK, TaskMenu.Item.NEW_TASK_IN_MAIN),
                List.of(TaskMenu.Item.FOCUS_PROJECT),
                List.of(TaskMenu.Item.REFRESH, TaskMenu.Item.GITHUB_SETTINGS, TaskMenu.Item.JIRA_SETTINGS),
                List.of(TaskMenu.Item.REMOVE_FROM_DRYDOCK)),
                sectionsOf(project(worktree(true, false))));
        assertEquals(List.of(
                List.of(TaskMenu.Item.NEW_TASK, TaskMenu.Item.NEW_TASK_IN_MAIN),
                List.of(TaskMenu.Item.FOCUS_PROJECT),
                List.of(TaskMenu.Item.REFRESH, TaskMenu.Item.GITHUB_SETTINGS, TaskMenu.Item.JIRA_SETTINGS),
                List.of(TaskMenu.Item.REMOVE_FROM_DRYDOCK)),
                sectionsOf(project()));
    }

    @Test
    void multiSelectionActsOnTheWholeSelectionOnly() {
        assertEquals(List.of(List.of(TaskMenu.Item.REMOVE_WORKTREES)),
                TaskMenu.sections(worktree(false, false), List.of(worktree(false, false),
                        new IdleWorktree(ROOT, "main", Optional.empty(), false, false)), NO_TRANSFER));
        // A mixed or non-removable selection offers nothing, never a subset.
        assertEquals(List.of(), TaskMenu.sections(worktree(false, false), List.of(
                task(true, new TaskPlacement.ThisWindow()), worktree(false, false)), NO_TRANSFER));
        assertEquals(List.of(), TaskMenu.sections(worktree(false, false), List.of(
                worktree(false, false), worktree(true, false)), NO_TRANSFER));
        assertEquals(List.of(), TaskMenu.sections(task(true, new TaskPlacement.ThisWindow()), List.of(
                task(true, new TaskPlacement.ThisWindow()), task(false, new TaskPlacement.Idle(false))),
                NO_TRANSFER));
    }

    @Test
    void aSingleRowSelectionMatchesItsLead() {
        TaskRow row = task(false, new TaskPlacement.Idle(false));
        assertEquals(TaskMenu.sections(row, List.of(), NO_TRANSFER),
                TaskMenu.sections(null, List.of(row), NO_TRANSFER));
        assertEquals(TaskMenu.sections(row, List.of(), NO_TRANSFER),
                TaskMenu.sections(row, null, NO_TRANSFER));
    }

    @Test
    void anythingElseGetsNothing() {
        assertEquals(List.of(), sectionsOf(null));
        assertEquals(List.of(), sectionsOf(new TaskNode.Deferred("Loading…")));
        assertEquals(List.of(), sectionsOf(new TaskNode.ListProblem("unreadable")));
        assertEquals(List.of(), sectionsOf(new TaskNode.AddCurrentProject(ROOT, "profiling-backend")));
    }

    @Test
    void noSectionIsEverEmptyAndDestructiveItemsComeLast() {
        List<List<List<TaskMenu.Item>>> menus = List.of(
                sectionsOf(task(true, new TaskPlacement.OwnWindow())),
                sectionsOf(task(true, new TaskPlacement.NotShown())),
                sectionsOf(task(false, new TaskPlacement.Idle(false))),
                sectionsOf(pullRequest(Optional.empty())),
                sectionsOf(issue(Optional.empty())),
                sectionsOf(worktree(false, false)),
                sectionsOf(new TaskNode.StaleWorktrees(List.of(worktree(false, false)))),
                sectionsOf(project(worktree(false, false))));
        List<TaskMenu.Item> last = List.of(TaskMenu.Item.DONE_WITH_TASK, TaskMenu.Item.DONE_WITH_TASK,
                TaskMenu.Item.DONE_WITH_TASK, TaskMenu.Item.NEW_TASK, TaskMenu.Item.GITHUB_SETTINGS,
                TaskMenu.Item.REMOVE_WORKTREE, TaskMenu.Item.REMOVE_STALE_WORKTREES,
                TaskMenu.Item.REMOVE_FROM_DRYDOCK);
        assertEquals(last.size(), menus.size());
        for (int i = 0; i < menus.size(); i++) {
            List<List<TaskMenu.Item>> menu = menus.get(i);
            for (List<TaskMenu.Item> section : menu) {
                assertTrue(!section.isEmpty(), "an empty section leaked through");
            }
            List<TaskMenu.Item> closing = menu.get(menu.size() - 1);
            assertEquals(last.get(i), closing.get(closing.size() - 1), "destructive item must close the menu");
        }
    }

    @Test
    void urlOfReadsIssueAndPullRequestRowsOnly() {
        assertEquals(Optional.of("https://github.com/DataDog/profiling-backend/issues/42"),
                TaskMenu.urlOf(issue(Optional.of("https://github.com/DataDog/profiling-backend/issues/42"))));
        assertEquals(Optional.empty(), TaskMenu.urlOf(issue(Optional.empty())));
        assertEquals(Optional.empty(), TaskMenu.urlOf(worktree(false, false)));
        assertEquals(Optional.empty(), TaskMenu.urlOf(task(true, new TaskPlacement.ThisWindow())));
        assertEquals(Optional.empty(), TaskMenu.urlOf(null));
    }

    @Test
    void aMirroredIssueOffersItsTaskFirstAndAPlainOneDoesNot() {
        TaskMenu.MenuFacts mirrored = new TaskMenu.MenuFacts(false, false, true);
        assertEquals(List.of(
                List.of(TaskMenu.Item.OPEN_MIRRORED_TASK),
                List.of(TaskMenu.Item.IMPLEMENT, TaskMenu.Item.TRIAGE),
                List.of(TaskMenu.Item.OPEN_IN_JIRA, TaskMenu.Item.JIRA_SETTINGS)),
                TaskMenu.sections(jiraIssue(), List.of(jiraIssue()), mirrored));
        IssueRow github = issue(Optional.of("https://github.com/DataDog/profiling-backend/issues/42"));
        assertEquals(TaskMenu.Item.OPEN_MIRRORED_TASK,
                TaskMenu.sections(github, List.of(github), mirrored).get(0).get(0));
        assertFalse(TaskMenu.sections(github, List.of(github), NO_TRANSFER).stream()
                .anyMatch(section -> section.contains(TaskMenu.Item.OPEN_MIRRORED_TASK)));
    }

    @Test
    void issueUrlOfNamesTheGitHubAndJiraPages() {
        assertEquals(Optional.of("https://acme.atlassian.net/browse/PROJ-123"), TaskMenu.issueUrlOf(jiraIssue()));
        assertEquals(Optional.of("https://github.com/DataDog/profiling-backend/issues/42"),
                TaskMenu.issueUrlOf(issue(Optional.of("https://github.com/DataDog/profiling-backend/issues/42"))));
        assertEquals(Optional.empty(), TaskMenu.issueUrlOf(pullRequest(Optional.of("https://github.com/o/r/pull/40"))));
        assertEquals(Optional.empty(), TaskMenu.issueUrlOf(null));
    }

    @Test
    void aConflictingPullRequestRowOffersResolveConflictsBesideTheWorkbench() {
        PullRequestRow conflicting = new PullRequestRow(ROOT, new OpenPullRequest(40, "Speed up the flaky suite",
                "pr-40", "main", false, Optional.of("reviewer"), Optional.empty(), true));
        assertEquals(List.of(List.of(TaskMenu.Item.OPEN_IN_WORKBENCH, TaskMenu.Item.RESOLVE_CONFLICTS),
                List.of(TaskMenu.Item.NEW_TASK)),
                sectionsOf(conflicting));
    }

    // ---- the cross-row jumps: navigation first, only when a target resolved ----

    /** Facts with every jump target resolved to non-null markers. */
    private static TaskMenu.MenuFacts jumps(Object task, Object pullRequest, Object session) {
        return new TaskMenu.MenuFacts(false, false, false, false, false,
                (app.drydock.tasks.TaskNode.DelayedTaskRow) task,
                (PullRequestRow) pullRequest, (TaskRow) session);
    }

    @Test
    void aPullRequestRowLeadsWithItsTaskAndSessionJumps() {
        TaskMenu.MenuFacts facts = jumps(newDelayedTaskRow(), pullRequest(Optional.empty()),
                task(false, new TaskPlacement.Here()));
        assertEquals(List.of(
                List.of(TaskMenu.Item.SHOW_TASK, TaskMenu.Item.SHOW_SESSION),
                List.of(TaskMenu.Item.OPEN_IN_WORKBENCH),
                List.of(TaskMenu.Item.NEW_TASK)),
                TaskMenu.sections(pullRequest(Optional.empty()), List.of(), facts));
    }

    @Test
    void aTaskRowLeadsWithItsPullRequestAndTaskJumpsWhenTheyResolved() {
        TaskMenu.MenuFacts facts = jumps(newDelayedTaskRow(), pullRequest(Optional.empty()), null);
        assertTrue(TaskMenu.sections(task(true, new TaskPlacement.Here()), List.of(), facts)
                .get(0).equals(List.of(TaskMenu.Item.SHOW_PULL_REQUEST, TaskMenu.Item.SHOW_TASK)));
        // No resolved target, no jump section: today's menu shape, unchanged.
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK),
                List.of(TaskMenu.Item.DEACTIVATE, TaskMenu.Item.REVIEW_CHANGES),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.FLAG, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.DONE_WITH_TASK)),
                TaskMenu.sections(task(true, new TaskPlacement.Here()), List.of(), NO_TRANSFER));
    }

    @Test
    void anIdleTaskRowLeadsWithItsJumpsToo() {
        TaskMenu.MenuFacts facts = jumps(null, pullRequest(Optional.empty()), null);
        assertEquals(List.of(TaskMenu.Item.SHOW_PULL_REQUEST),
                TaskMenu.sections(task(false, new TaskPlacement.Here()), List.of(), facts).get(0));
    }

    @Test
    void anIssueRowAddsTheShowTaskJumpBesideTheMirroredTaskOpen() {
        TaskMenu.MenuFacts facts = new TaskMenu.MenuFacts(false, false, true, false, false, newDelayedTaskRow(), null, null);
        IssueRow github = issue(Optional.of("https://github.com/DataDog/profiling-backend/issues/42"));
        assertEquals(List.of(TaskMenu.Item.SHOW_TASK, TaskMenu.Item.OPEN_MIRRORED_TASK),
                TaskMenu.sections(github, List.of(), facts).get(0));
    }

    /** A minimal delayed-task row for the facts' showTask target; its fields are never read here. */
    private static app.drydock.tasks.TaskNode.DelayedTaskRow newDelayedTaskRow() {
        return new app.drydock.tasks.TaskNode.DelayedTaskRow(
                new app.drydock.tasks.TaskState.TaskView(app.drydock.tasks.TaskId.of("fix-alloc-k3f9"),
                        app.drydock.tasks.TaskState.Presentation.READY, List.of(), List.of()),
                "Fix allocation profile", RepositoryId.newId(), Optional.empty(), false, List.of());
    }

    @Test
    void projectRowOffersWorkspaceBoxNextToGitHubSettingsOnlyWhileTheOptInIsOn() {
        // Flag on: the entry sits next to GitHub settings, before Jira's —
        // both settings facts on at once keeps every other entry in place.
        assertEquals(List.of(
                List.of(TaskMenu.Item.NEW_TASK, TaskMenu.Item.NEW_TASK_IN_MAIN),
                List.of(TaskMenu.Item.FOCUS_PROJECT),
                List.of(TaskMenu.Item.REFRESH, TaskMenu.Item.GITHUB_SETTINGS, TaskMenu.Item.WORKSPACE_BOX,
                        TaskMenu.Item.JIRA_SETTINGS),
                List.of(TaskMenu.Item.REMOVE_FROM_DRYDOCK)),
                TaskMenu.sections(project(), List.of(), WORKSPACE_BOX));
        assertEquals(List.of(
                List.of(TaskMenu.Item.NEW_TASK, TaskMenu.Item.NEW_TASK_IN_MAIN),
                List.of(TaskMenu.Item.FOCUS_PROJECT),
                List.of(TaskMenu.Item.REFRESH, TaskMenu.Item.GITHUB_SETTINGS, TaskMenu.Item.WORKSPACE_BOX,
                        TaskMenu.Item.JIRA_SETTINGS, TaskMenu.Item.ADD_JIRA_GROUP),
                List.of(TaskMenu.Item.REMOVE_FROM_DRYDOCK)),
                TaskMenu.sections(project(), List.of(), new TaskMenu.MenuFacts(false, true, false, true)));
        // Flag off — every delegating MenuFacts constructor keeps it off, so
        // the existing flag-off assertions already pin this: no project menu
        // contains the entry, and neither does any other row that shares
        // the settings section.
        assertEquals(List.of(
                List.of(TaskMenu.Item.NEW_TASK, TaskMenu.Item.NEW_TASK_IN_MAIN),
                List.of(TaskMenu.Item.FOCUS_PROJECT),
                List.of(TaskMenu.Item.REFRESH, TaskMenu.Item.GITHUB_SETTINGS, TaskMenu.Item.JIRA_SETTINGS),
                List.of(TaskMenu.Item.REMOVE_FROM_DRYDOCK)),
                sectionsOf(project()));
        assertFalse(TaskMenu.sections(new Section(SectionKind.ISSUES, List.of(issue(Optional.empty()))), List.of(),
                        WORKSPACE_BOX).stream()
                .anyMatch(section -> section.contains(TaskMenu.Item.WORKSPACE_BOX)));
    }

    @Test
    void noOtherRowOffersWorkspaceBoxEvenWithTheOptInOn() {
        List<TaskNode> rows = List.of(
                task(true, new TaskPlacement.OwnWindow()),
                task(false, new TaskPlacement.Idle(false)),
                issue(Optional.empty()),
                jiraIssue(),
                pullRequest(Optional.empty()),
                worktree(false, false),
                new WorktreeGroup(WORKTREE, "fix-allocation-profile", List.of(task(false, new TaskPlacement.Idle(false)))),
                new Section(SectionKind.ISSUES, List.of(issue(Optional.empty()))),
                new Section(SectionKind.PULL_REQUESTS, List.of(pullRequest(Optional.empty()))),
                new TaskNode.StaleWorktrees(List.of(worktree(false, false))),
                new TaskNode.AddCurrentProject(ROOT, "profiling-backend"));
        for (TaskNode row : rows) {
            assertTrue(TaskMenu.sections(row, List.of(), WORKSPACE_BOX).stream()
                            .noneMatch(section -> section.contains(TaskMenu.Item.WORKSPACE_BOX)),
                    "only a project row may offer the workspace-box settings: " + row.getClass().getSimpleName());
        }
    }
}
