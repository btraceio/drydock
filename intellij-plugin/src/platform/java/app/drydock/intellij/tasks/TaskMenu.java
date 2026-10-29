package app.drydock.intellij.tasks;

import app.drydock.intellij.agent.SessionTransfer;
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

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Drydock tree's context menu, as pure layout: which actions apply to
 * the selected rows, in which sections, and in which order — navigation
 * first, work on the task next, its management after, destructive cleanup
 * last, and nothing that would only go to the destination the user is
 * already in (A10).
 *
 * <p>Only applicability is decided here, never presentation: each
 * {@link Item} is one action instance the panel owns, whose own
 * {@code update} keeps its label honest (Resume vs Start, the quick
 * review's remembered model). The one non-pure answer a menu needs —
 * transfer eligibility — is computed once per menu build by
 * {@link #factsFor} and injected, so {@link #sections} stays pure and
 * testable without the registry.</p>
 */
final class TaskMenu {

    /** One menu entry; the action behind it lives in the panel or {@link TaskActions}. */
    enum Item {
        /** Raise the task's window and select its agent console; "Switch" when the task is idle. */
        SWITCH_TO_TASK,
        /** Move an agent another window shows into this one; absent when it is shown here already. */
        SHOW_HERE,
        DEACTIVATE,
        /** Ask the task's agent to review its changes, not the user viewing them. */
        REVIEW_CHANGES,
        TRANSFER_TO,
        RENAME,
        NEW_TASK,
        DEACTIVATE_AND_CLOSE,
        DONE_WITH_TASK,
        /** Resume, or Start when there is no conversation to resume into. */
        RESUME,
        RESUME_IN_OWN_WINDOW,
        /** Review with the repository's remembered starting model. */
        QUICK_REVIEW,
        REVIEW,
        /** Start a review session whose first prompt is the harness's sphinx command. */
        REVIEW_IN_SPHINX,
        OPEN_IN_GITHUB,
        /** Open the Jira issue's page; only a Jira row offers it. */
        OPEN_IN_JIRA,
        IMPLEMENT,
        TRIAGE,
        GITHUB_SETTINGS,
        /** Per-project Jira configuration: site, credentials. */
        JIRA_SETTINGS,
        /** Add a user-defined Jira group (name + JQL) to the Issues section. */
        ADD_JIRA_GROUP,
        /** Rename a Jira group or change its JQL; only a group node offers it. */
        EDIT_JIRA_GROUP,
        /** Delete a Jira group, after confirmation; only a group node offers it. */
        DELETE_JIRA_GROUP,
        REFRESH,
        NEW_TASK_IN_MAIN,
        REMOVE_WORKTREE,
        REMOVE_STALE_WORKTREES,
        REMOVE_INACTIVE_WORKTREES,
        REMOVE_FROM_DRYDOCK,
        REMOVE_WORKTREES
    }

    /** The non-pure answers of one menu build, computed once by {@link #factsFor}. */
    record MenuFacts(boolean transferAvailable, boolean jiraEnabled) {
    }

    private TaskMenu() {
    }

    /**
     * Pure: the applicable menu sections for a selection, never an empty
     * section; {@code List.of()} when nothing applies, so no empty group or
     * separator can be shown. A multi-selection acts on the whole selection,
     * never on just the lead row — and a mixed selection offers nothing
     * rather than acting on its removable subset.
     */
    static List<List<Item>> sections(TaskNode lead, List<TaskNode> selection, MenuFacts facts) {
        List<TaskNode> selected = selection == null ? List.of() : selection;
        if (selected.size() > 1) {
            return TaskActions.removable(selected).isEmpty()
                    ? List.of()
                    : List.of(List.of(Item.REMOVE_WORKTREES));
        }
        TaskNode node = selected.isEmpty() ? lead : selected.get(0);
        return switch (node) {
            case null -> List.of();
            case TaskRow row -> row.running() ? running(row, facts) : idle(row);
            case PullRequestRow ignored -> nonEmpty(List.of(
                    List.of(Item.QUICK_REVIEW, Item.REVIEW, Item.REVIEW_IN_SPHINX),
                    section(urlOf(node).isPresent() ? Item.OPEN_IN_GITHUB : null)));
            case IssueRow ignored -> nonEmpty(List.of(
                    List.of(Item.IMPLEMENT, Item.TRIAGE),
                    section(urlOf(node).isPresent() ? Item.OPEN_IN_GITHUB : null, Item.GITHUB_SETTINGS)));
            case JiraIssueRow ignored -> nonEmpty(List.of(
                    List.of(Item.IMPLEMENT, Item.TRIAGE),
                    List.of(Item.OPEN_IN_JIRA, Item.JIRA_SETTINGS)));
            case JiraGroupRow ignored -> nonEmpty(List.of(
                    List.of(Item.EDIT_JIRA_GROUP, Item.DELETE_JIRA_GROUP),
                    List.of(Item.REFRESH)));
            case IdleWorktree idleWorktree -> nonEmpty(List.of(
                    List.of(Item.NEW_TASK, Item.QUICK_REVIEW, Item.REVIEW, Item.REVIEW_IN_SPHINX),
                    // Locked or open worktrees cannot be removed from here.
                    section(TaskActions.removable(idleWorktree).isEmpty() ? null : Item.REMOVE_WORKTREE)));
            case StaleWorktrees ignored -> List.of(List.of(Item.REMOVE_STALE_WORKTREES));
            case WorktreeGroup ignored -> List.of(List.of(Item.NEW_TASK));
            case Section section when section.kind() == SectionKind.ISSUES
                    || section.kind() == SectionKind.PULL_REQUESTS ->
                    section.kind() == SectionKind.ISSUES
                            ? nonEmpty(List.of(section(Item.REFRESH,
                                    facts.jiraEnabled() ? Item.ADD_JIRA_GROUP : null,
                                    Item.GITHUB_SETTINGS, Item.JIRA_SETTINGS)))
                            : List.of(List.of(Item.REFRESH, Item.GITHUB_SETTINGS));
            case ProjectRow project -> project(project, facts);
            default -> List.of();
        };
    }

    /** A running task: switch to it or pull it here, work on it, manage it, clean up last. */
    private static List<List<Item>> running(TaskRow row, MenuFacts facts) {
        return nonEmpty(List.of(
                section(Item.SWITCH_TO_TASK, shownElsewhere(row) ? Item.SHOW_HERE : null),
                section(Item.DEACTIVATE,
                        TaskActions.reviewsChanges(row) ? Item.REVIEW_CHANGES : null,
                        facts.transferAvailable() ? Item.TRANSFER_TO : null),
                List.of(Item.RENAME, Item.NEW_TASK),
                section(TaskActions.closesOwnWindow(row) ? Item.DEACTIVATE_AND_CLOSE : null,
                        Item.DONE_WITH_TASK)));
    }

    /** An idle task: the honest lifecycle variants, then management, cleanup last. */
    private static List<List<Item>> idle(TaskRow row) {
        return nonEmpty(List.of(
                List.of(Item.SWITCH_TO_TASK),
                section(Item.RESUME, TaskActions.resumesElsewhere(row) ? Item.RESUME_IN_OWN_WINDOW : null),
                List.of(Item.RENAME, Item.NEW_TASK),
                List.of(Item.DONE_WITH_TASK)));
    }

    /** A project: new tasks, the listings' refresh and settings, then cleanup. */
    private static List<List<Item>> project(ProjectRow row, MenuFacts facts) {
        return nonEmpty(List.of(
                List.of(Item.NEW_TASK, Item.NEW_TASK_IN_MAIN),
                section(Item.REFRESH, Item.GITHUB_SETTINGS, Item.JIRA_SETTINGS,
                        facts.jiraEnabled() ? Item.ADD_JIRA_GROUP : null),
                section(TaskActions.removable(row).isEmpty() ? null : Item.REMOVE_INACTIVE_WORKTREES,
                        Item.REMOVE_FROM_DRYDOCK)));
    }

    /** The sections that have items; an inapplicable conditional never leaves a hole. */
    private static List<List<Item>> nonEmpty(List<List<Item>> sections) {
        return sections.stream().filter(section -> !section.isEmpty()).toList();
    }

    /** One section's present items, in order; an inapplicable conditional passes {@code null}. */
    private static List<Item> section(Item... items) {
        List<Item> present = new ArrayList<>();
        for (Item item : items) {
            if (item != null) {
                present.add(item);
            }
        }
        return List.copyOf(present);
    }

    /**
     * Pure: whether another window shows the agent — its own worktree's, or
     * a hosting project's — so Show Here moves it here instead of re-showing
     * the console this window already has. The same rule as the toolbar's
     * Show Here.
     */
    private static boolean shownElsewhere(TaskRow row) {
        return row.placement() instanceof TaskPlacement.OwnWindow
                || row.placement() instanceof TaskPlacement.OtherWindow;
    }

    /** The GitHub URL of an issue or pull request row, when it has one. Pure. */
    static Optional<String> urlOf(TaskNode node) {
        return switch (node) {
            case IssueRow issue -> issue.issue().url();
            // A Jira row's URL is Jira's, opened by its own action.
            case JiraIssueRow ignored -> Optional.empty();
            case PullRequestRow pullRequest -> pullRequest.pullRequest().url();
            case null, default -> Optional.empty();
        };
    }

    /**
     * EDT: the non-pure answers a menu build needs, computed once here
     * instead of per action. Transfer eligibility reads the registry's O(1)
     * probes, the transfer table, and the provider registry — memory-only,
     * but not pure, so {@link #sections} takes the answer as
     * {@link MenuFacts} instead. {@code jiraEnabled} is the selected
     * project's connection flag as the caller read it; whether to offer
     * adding a group hinges on it (the Jira settings entry is always offered).
     */
    static MenuFacts factsFor(TaskNode lead, boolean jiraEnabled) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        return new MenuFacts(lead instanceof TaskRow row
                && SessionTransfer.available(row.session(), row.activity()), jiraEnabled);
    }

    /**
     * EDT: the popup for a selection — {@link #sections} with a separator
     * only between the sections that have actions, so a conditional absence
     * never leaves a leading, trailing, or doubled separator. The map holds
     * the panel's action instances; an {@link Item} without one is skipped
     * rather than shown broken.
     */
    static ActionGroup group(TaskNode lead, List<TaskNode> selection, Map<Item, AnAction> actions,
                             boolean jiraEnabled) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        DefaultActionGroup menu = new DefaultActionGroup();
        boolean separated = false;
        for (List<Item> section : sections(lead, selection, factsFor(lead, jiraEnabled))) {
            List<AnAction> present = new ArrayList<>();
            for (Item item : section) {
                AnAction action = actions.get(item);
                if (action != null) {
                    present.add(action);
                }
            }
            if (present.isEmpty()) {
                continue;
            }
            if (separated) {
                menu.addSeparator();
            }
            menu.addAll(present);
            separated = true;
        }
        return menu;
    }
}
