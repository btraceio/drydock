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
import app.drydock.git.GhCliService.OpenPullRequest;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.IssueRow;
import app.drydock.intellij.tasks.TaskNode.JiraGroupRow;
import app.drydock.intellij.tasks.TaskNode.JiraIssueRow;
import app.drydock.intellij.tasks.TaskNode.ListProblem;
import app.drydock.intellij.tasks.TaskNode.Placeholder;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.PullRequestRow;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.SectionKind;
import app.drydock.intellij.tasks.TaskNode.StaleWorktrees;
import app.drydock.intellij.tasks.TaskNode.WorktreeGroup;
import app.drydock.projects.SharedProject;

import com.intellij.openapi.actionSystem.AnAction;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskActionsTest {

    @Test
    void removeQuestionNamesTheAgentsThatKeepRunning() {
        assertEquals("Remove drydock from Drydock? 2 agents keep running.", TaskActions.removeQuestion("drydock", 2));
        assertEquals("Remove drydock from Drydock? 1 agent keeps running.", TaskActions.removeQuestion("drydock", 1));
    }

    @Test
    void toolbarButtonsStayPutAndGreyOutContextMenusHide() {
        assertEquals(new TaskActions.Shown(true, false), TaskActions.shown(false, false), "toolbar, not applicable");
        assertEquals(new TaskActions.Shown(true, true), TaskActions.shown(false, true), "toolbar, applicable");
        assertEquals(new TaskActions.Shown(false, false), TaskActions.shown(true, false), "menu, not applicable");
        assertEquals(new TaskActions.Shown(true, true), TaskActions.shown(true, true), "menu, applicable");
    }

    @Test
    void aSeparateEnabledAnswerGreysWithoutEverHiding() {
        // Busy greys the action everywhere; it never removes it.
        assertEquals(new TaskActions.Shown(true, false), TaskActions.shown(false, false, false),
                "toolbar, not applicable, busy");
        assertEquals(new TaskActions.Shown(true, false), TaskActions.shown(false, true, false),
                "toolbar, applicable, busy");
        assertEquals(new TaskActions.Shown(false, false), TaskActions.shown(true, false, false),
                "menu, not applicable, busy");
        assertEquals(new TaskActions.Shown(true, false), TaskActions.shown(true, true, false),
                "menu, applicable, busy");
        assertEquals(new TaskActions.Shown(true, true), TaskActions.shown(true, true, true),
                "menu, applicable, idle");
        assertEquals(new TaskActions.Shown(true, true), TaskActions.shown(false, true, true),
                "toolbar, applicable, idle");
    }

    @Test
    void theTwoArgFormsDelegateWithEnabledTrue() {
        assertEquals(TaskActions.shown(false, false), TaskActions.shown(false, false, true));
        assertEquals(TaskActions.shown(false, true), TaskActions.shown(false, true, true));
        assertEquals(TaskActions.shown(true, false), TaskActions.shown(true, false, true));
        assertEquals(TaskActions.shown(true, true), TaskActions.shown(true, true, true));
    }

    @Test
    void lifecycleTextSaysStartOnlyWhenThereIsNothingToResume() {
        assertEquals("Start", TaskActions.lifecycleText(false, false));
        assertEquals("Resume", TaskActions.lifecycleText(true, false));
        assertEquals("Start in Its Own Window", TaskActions.lifecycleText(false, true));
        assertEquals("Resume in Its Own Window", TaskActions.lifecycleText(true, true));
    }

    @Test
    void stopAndCloseOnlyClosesTheAgentsOwnWindow() {
        assertTrue(TaskActions.closesOwnWindow(row(true, new TaskPlacement.ThisWindow())));
        assertTrue(TaskActions.closesOwnWindow(row(true, new TaskPlacement.OwnWindow())));
        // Hosted somewhere else, or nowhere: that window is not the agent's.
        assertFalse(TaskActions.closesOwnWindow(row(true, new TaskPlacement.Here())));
        assertFalse(TaskActions.closesOwnWindow(row(true, new TaskPlacement.OtherWindow("repo"))));
        assertFalse(TaskActions.closesOwnWindow(row(true, new TaskPlacement.NotShown())));
        assertFalse(TaskActions.closesOwnWindow(row(false, new TaskPlacement.Idle(true))));
    }

    @Test
    void reviewChangesIsOfferedOnlyForARunningTask() {
        assertTrue(TaskActions.reviewsChanges(row(true, new TaskPlacement.ThisWindow())));
        assertFalse(TaskActions.reviewsChanges(row(false, new TaskPlacement.Idle(true))));
    }

    @Test
    void resumingInItsOwnWindowIsOfferedOnlyForAnIdleTaskFromAnotherWorktree() {
        // Resume itself resumes in this window, like New task; its own
        // window is the explicit alternative, and only where it differs.
        assertTrue(TaskActions.resumesElsewhere(row(false, new TaskPlacement.Idle(false))));
        assertFalse(TaskActions.resumesElsewhere(row(false, new TaskPlacement.Idle(true))));
        assertFalse(TaskActions.resumesElsewhere(row(true, new TaskPlacement.NotShown())));
    }

    @Test
    void defaultActivationResumesIdleTasksHereAndOnlyNavigatesToRunningTasks() {
        assertEquals(TaskActions.DefaultActivation.RESUME_HERE,
                TaskActions.defaultActivation(row(false, new TaskPlacement.Idle(false))));
        assertEquals(TaskActions.DefaultActivation.RESUME_HERE,
                TaskActions.defaultActivation(row(false, new TaskPlacement.Idle(true))));
        assertEquals(TaskActions.DefaultActivation.SWITCH_TO_RUNNING,
                TaskActions.defaultActivation(row(true, new TaskPlacement.OwnWindow())));
        assertEquals(TaskActions.DefaultActivation.SWITCH_TO_RUNNING,
                TaskActions.defaultActivation(row(true, new TaskPlacement.Here())));
    }

    @Test
    void destructiveRemovalQuestionSaysWhatIsLostAndWhatIsKept() {
        assertEquals("Remove 3 stale worktrees? This permanently deletes their folders and discards any "
                        + "uncommitted and untracked changes. Branches are kept.",
                TaskActions.removalQuestion(3, true));
        assertEquals("Remove 1 inactive worktree? This permanently deletes its folder and discards any "
                        + "uncommitted and untracked changes. Branches are kept.",
                TaskActions.removalQuestion(1, false));
        assertEquals("Remove", TaskActions.removalButton(1));
        assertEquals("Remove 3", TaskActions.removalButton(3));
    }

    @Test
    void lockedAndOpenWorktreesAreNeverRemovalTargets() {
        IdleWorktree removable = new IdleWorktree(Path.of("/wt/old"), "old", Optional.empty(), false, false);
        IdleWorktree locked = new IdleWorktree(Path.of("/wt/held"), "held", Optional.empty(), true, false);
        IdleWorktree open = new IdleWorktree(Path.of("/wt/open"), "open", Optional.empty(), false, true);

        assertEquals(List.of(removable), TaskActions.removable(removable));
        assertEquals(List.of(), TaskActions.removable(locked));
        assertEquals(List.of(), TaskActions.removable(open));
    }

    @Test
    void multiSelectionRemovesEverySelectedWorktreeOnce() {
        IdleWorktree first = new IdleWorktree(Path.of("/wt/first"), "first", Optional.empty(), false, false);
        IdleWorktree second = new IdleWorktree(Path.of("/wt/second"), "second", Optional.empty(), false, false);

        assertEquals(List.of(first, second), TaskActions.removable(List.of(first, second)));
        assertEquals("Remove 2 inactive worktrees? This permanently deletes their folders and discards any "
                        + "uncommitted and untracked changes. Branches are kept.",
                TaskActions.removalQuestion(TaskActions.removable(List.of(first, second)).size(), false));
        assertEquals(List.of(first, second), TaskActions.removable(
                List.of(new StaleWorktrees(List.of(first, second)), first)),
                "selecting a group and one of its children must not remove the child twice");
    }

    @Test
    void mixedSelectionDoesNotSilentlyRemoveOnlyItsWorktreeRows() {
        IdleWorktree worktree = new IdleWorktree(Path.of("/wt/first"), "first", Optional.empty(), false, false);

        assertEquals(List.of(), TaskActions.removable(List.of(worktree, new ListProblem("broken"))));
    }

    @Test
    void theReviewChangesActionNamesTheAskExplicitly() {
        // "Review Changes" read as viewing them; the action sends the agent
        // a review prompt, so the label says what it does (A10).
        assertEquals("Ask Agent to Review Changes",
                TaskActions.taskActions().get(TaskMenu.Item.REVIEW_CHANGES).getTemplatePresentation().getText());
    }

    @Test
    void theActionsMapCoversEveryItemTaskMenuUses() {
        Map<TaskMenu.Item, AnAction> actions = TaskActions.taskActions();
        Set<TaskMenu.Item> covered = new HashSet<>(actions.keySet());
        covered.addAll(TaskTreePanel.panelMenuItems());

        assertTrue(Collections.disjoint(actions.keySet(), TaskTreePanel.panelMenuItems()),
                "an item cannot be owned by both TaskActions and the panel");
        assertEquals(usedByTaskMenu(), covered, "every menu item needs an action behind it");
    }

    @Test
    void theToolbarShowsEachActionOnceInEnumOrder() {
        Map<TaskMenu.Item, AnAction> actions = TaskActions.taskActions();
        List<AnAction> toolbar = TaskActions.toolbarActions(actions);

        // The removal action serves four menu entries but must be one button.
        assertEquals(1, Collections.frequency(toolbar, actions.get(TaskMenu.Item.REMOVE_INACTIVE_WORKTREES)),
                "the shared removal action must be one toolbar button");
        assertEquals(Set.copyOf(actions.values()), Set.copyOf(toolbar), "every action, and no other");
        assertEquals(actions.size() - 3, toolbar.size(), "four removal keys share one instance");
        // values() is the toolbar's order: the enum's declaration order.
        assertTrue(toolbar.indexOf(actions.get(TaskMenu.Item.SHOW_HERE))
                < toolbar.indexOf(actions.get(TaskMenu.Item.DEACTIVATE)));
        assertTrue(toolbar.indexOf(actions.get(TaskMenu.Item.DONE_WITH_TASK))
                < toolbar.indexOf(actions.get(TaskMenu.Item.RESUME)));
        assertTrue(toolbar.indexOf(actions.get(TaskMenu.Item.RESUME))
                < toolbar.indexOf(actions.get(TaskMenu.Item.RESUME_IN_OWN_WINDOW)));
        assertTrue(toolbar.indexOf(actions.get(TaskMenu.Item.REMOVE_INACTIVE_WORKTREES))
                < toolbar.indexOf(actions.get(TaskMenu.Item.REMOVE_FROM_DRYDOCK)));
    }

    /** Every {@link TaskMenu.Item} a menu can offer, over one row of each kind. */
    private static Set<TaskMenu.Item> usedByTaskMenu() {
        // Jira enabled, so the group items a menu can offer are all reachable.
        TaskMenu.MenuFacts transfer = new TaskMenu.MenuFacts(true, true);
        IdleWorktree worktree = new IdleWorktree(Path.of("/wt/fix"), "fix", Optional.empty(), false, false);
        List<TaskNode> shapes = List.of(
                row(true, new TaskPlacement.OwnWindow()),
                row(false, new TaskPlacement.Idle(false)),
                pullRequest(Optional.of("https://github.com/DataDog/repo/pull/7")),
                issue(Optional.of("https://github.com/DataDog/repo/issues/9")),
                new JiraIssueRow(Path.of("/repo"), new JiraIssue("PROJ-9", "Fix the thing",
                        "https://acme.atlassian.net/browse/PROJ-9", List.of(), Optional.of(Instant.EPOCH))),
                new JiraGroupRow(Path.of("/repo"), new JiraGroup("g-sprint", "Sprint", "project = DRY"),
                        List.of(new Placeholder("No issues"))),
                worktree,
                new StaleWorktrees(List.of(worktree)),
                new WorktreeGroup(Path.of("/wt/fix"), "fix", List.of()),
                new Section(SectionKind.ISSUES, List.of(issue(Optional.empty()))),
                project(worktree));
        Set<TaskMenu.Item> used = new HashSet<>();
        for (TaskNode shape : shapes) {
            TaskMenu.sections(shape, List.of(), transfer).forEach(used::addAll);
        }
        // The bulk-removal entry only a multi-selection offers.
        TaskMenu.sections(worktree, List.of(worktree,
                new IdleWorktree(Path.of("/wt/other"), "other", Optional.empty(), false, false)), transfer)
                .forEach(used::addAll);
        return used;
    }

    private static IssueRow issue(Optional<String> url) {
        return new IssueRow(Path.of("/repo"), new OpenIssue(9, "Fix the thing", url, List.of(), List.of(),
                Optional.of(Instant.EPOCH)));
    }

    private static PullRequestRow pullRequest(Optional<String> url) {
        return new PullRequestRow(Path.of("/repo"), new OpenPullRequest(7, "Speed the suite", "pr-7", "main",
                false, Optional.empty(), url));
    }

    private static ProjectRow project(IdleWorktree... inactive) {
        return new ProjectRow(SharedProject.local(RepositoryId.newId(), Path.of("/repo"), "repo", Instant.EPOCH),
                false, false, List.of(), List.of(inactive), 0);
    }

    private static TaskNode.TaskRow row(boolean running, TaskPlacement placement) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(),
                "Task", AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/wt/task")), Instant.EPOCH).status(SessionStatus.INACTIVE).build();
        return new TaskNode.TaskRow(session, Optional.empty(), running, Optional.empty(), placement, false, false);
    }
}
