package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.GhCliService.OpenPullRequest;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.IssueRow;
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

    private static final TaskMenu.MenuFacts TRANSFER = new TaskMenu.MenuFacts(true);
    private static final TaskMenu.MenuFacts NO_TRANSFER = new TaskMenu.MenuFacts(false);

    private static TaskRow task(boolean running, TaskPlacement placement) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), "Fix allocation profile",
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(WORKTREE), NOW)
                .build();
        return new TaskRow(session, Optional.empty(), running, Optional.of("fix-allocation-profile"),
                placement, false, false, false);
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

    private static PullRequestRow pullRequest(Optional<String> url) {
        return new PullRequestRow(ROOT, new OpenPullRequest(40, "Speed up the flaky suite",
                "pr-40", "main", false, Optional.of("reviewer"), url));
    }

    private static List<List<TaskMenu.Item>> sectionsOf(TaskNode lead) {
        return TaskMenu.sections(lead, List.of(), NO_TRANSFER);
    }

    @Test
    void runningTaskShownElsewhereGetsEveryConditionalItem() {
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK, TaskMenu.Item.SHOW_HERE),
                List.of(TaskMenu.Item.DEACTIVATE, TaskMenu.Item.REVIEW_CHANGES, TaskMenu.Item.TRANSFER_TO),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.DEACTIVATE_AND_CLOSE, TaskMenu.Item.DONE_WITH_TASK)),
                TaskMenu.sections(task(true, new TaskPlacement.OwnWindow()), List.of(), TRANSFER));
    }

    @Test
    void runningTaskInAnotherProjectWindowGetsShowHereButNotItsOwnClose() {
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK, TaskMenu.Item.SHOW_HERE),
                List.of(TaskMenu.Item.DEACTIVATE, TaskMenu.Item.REVIEW_CHANGES),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.NEW_TASK),
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
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.DONE_WITH_TASK)),
                sectionsOf(task(true, new TaskPlacement.Here())));
    }

    @Test
    void runningTaskInThisWindowsWorktreeOffersItsOwnClose() {
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK),
                List.of(TaskMenu.Item.DEACTIVATE, TaskMenu.Item.REVIEW_CHANGES),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.DEACTIVATE_AND_CLOSE, TaskMenu.Item.DONE_WITH_TASK)),
                sectionsOf(task(true, new TaskPlacement.ThisWindow())));
    }

    @Test
    void runningTaskWithNoWindowGetsNeitherShowHereNorItsOwnClose() {
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK),
                List.of(TaskMenu.Item.DEACTIVATE, TaskMenu.Item.REVIEW_CHANGES),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.NEW_TASK),
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
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.DONE_WITH_TASK)),
                sectionsOf(task(false, new TaskPlacement.Idle(false))));
    }

    @Test
    void idleTaskOfThisWindowsWorktreeHasNoOwnWindowVariant() {
        // Its own window would be this one; plain Resume already goes there.
        assertEquals(List.of(
                List.of(TaskMenu.Item.SWITCH_TO_TASK),
                List.of(TaskMenu.Item.RESUME),
                List.of(TaskMenu.Item.RENAME, TaskMenu.Item.NEW_TASK),
                List.of(TaskMenu.Item.DONE_WITH_TASK)),
                sectionsOf(task(false, new TaskPlacement.Idle(true))));
    }

    @Test
    void pullRequestRowOffersQuickReviewThenModelReviewThenGitHub() {
        assertEquals(List.of(
                List.of(TaskMenu.Item.QUICK_REVIEW, TaskMenu.Item.REVIEW),
                List.of(TaskMenu.Item.OPEN_IN_GITHUB)),
                sectionsOf(pullRequest(Optional.of("https://github.com/DataDog/profiling-backend/pull/40"))));
        // No URL, no second section: never an empty one.
        assertEquals(List.of(List.of(TaskMenu.Item.QUICK_REVIEW, TaskMenu.Item.REVIEW)),
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
    void idleWorktreeOffersRemoveOnlyWhenItIsNeitherLockedNorOpen() {
        assertEquals(List.of(
                List.of(TaskMenu.Item.NEW_TASK, TaskMenu.Item.QUICK_REVIEW, TaskMenu.Item.REVIEW),
                List.of(TaskMenu.Item.REMOVE_WORKTREE)),
                sectionsOf(worktree(false, false)));
        assertEquals(List.of(List.of(TaskMenu.Item.NEW_TASK, TaskMenu.Item.QUICK_REVIEW, TaskMenu.Item.REVIEW)),
                sectionsOf(worktree(true, false)));
        assertEquals(List.of(List.of(TaskMenu.Item.NEW_TASK, TaskMenu.Item.QUICK_REVIEW, TaskMenu.Item.REVIEW)),
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
        assertEquals(List.of(List.of(TaskMenu.Item.REFRESH, TaskMenu.Item.GITHUB_SETTINGS)),
                sectionsOf(new Section(SectionKind.ISSUES, List.of(issue(Optional.empty())))));
        assertEquals(List.of(List.of(TaskMenu.Item.REFRESH, TaskMenu.Item.GITHUB_SETTINGS)),
                sectionsOf(new Section(SectionKind.PULL_REQUESTS, List.of(pullRequest(Optional.empty())))));
        assertEquals(List.of(), sectionsOf(new Section(SectionKind.SESSIONS, List.of(
                task(true, new TaskPlacement.ThisWindow())))));
        assertEquals(List.of(), sectionsOf(new Section(SectionKind.WORKTREES, List.of(worktree(false, false)))));
    }

    @Test
    void projectRowOffersInactiveRemovalOnlyWhenOneApplies() {
        assertEquals(List.of(
                List.of(TaskMenu.Item.NEW_TASK, TaskMenu.Item.NEW_TASK_IN_MAIN),
                List.of(TaskMenu.Item.REFRESH, TaskMenu.Item.GITHUB_SETTINGS),
                List.of(TaskMenu.Item.REMOVE_INACTIVE_WORKTREES, TaskMenu.Item.REMOVE_FROM_DRYDOCK)),
                sectionsOf(project(worktree(false, false))));
        assertEquals(List.of(
                List.of(TaskMenu.Item.NEW_TASK, TaskMenu.Item.NEW_TASK_IN_MAIN),
                List.of(TaskMenu.Item.REFRESH, TaskMenu.Item.GITHUB_SETTINGS),
                List.of(TaskMenu.Item.REMOVE_FROM_DRYDOCK)),
                sectionsOf(project(worktree(true, false))));
        assertEquals(List.of(
                List.of(TaskMenu.Item.NEW_TASK, TaskMenu.Item.NEW_TASK_IN_MAIN),
                List.of(TaskMenu.Item.REFRESH, TaskMenu.Item.GITHUB_SETTINGS),
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
                TaskMenu.Item.DONE_WITH_TASK, TaskMenu.Item.REVIEW, TaskMenu.Item.GITHUB_SETTINGS,
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
}
