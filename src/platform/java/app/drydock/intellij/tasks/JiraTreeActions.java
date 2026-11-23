package app.drydock.intellij.tasks;

import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.tasks.TaskNode.JiraGroupRow;
import app.drydock.intellij.tasks.TaskNode.JiraIssueRow;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.SectionKind;
import app.drydock.projects.SharedProject;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The Jira rows' tree actions (spec AC11, AC22). {@code TaskTreePanel} is
 * already the panel's own home, so these live beside it and reuse its data
 * keys and presentation seams.
 */
final class JiraTreeActions {

    private JiraTreeActions() {
    }

    /**
     * Whether the selected project's Jira connection is enabled — the gate
     * for offering {@code Add Jira Group…}. Reads the shared-projects list
     * and the in-memory properties, no password-safe touch; the list lookup
     * canonicalizes {@code root} (filesystem IO), so call it off the EDT.
     */
    static boolean jiraEnabled(Path root) {
        return DrydockPluginService.getInstance().sharedProjects().byRoot(root)
                .map(registered -> JiraProjectSettings.load(registered.id()).enabled())
                .orElse(false);
    }

    /** {@code Open in Jira}: the selected row's Jira page in the browser — a Jira issue row's own, or a
     *  session row's every mirrored Jira ticket (spec AC22). */
    static AnAction openInJira() {
        return new DumbAwareAction("Open in Jira", "Open this Jira issue in the browser",
                TaskTreeRenderer.JIRA_ICON) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskNode selected = e.getData(TaskTreePanel.SELECTED_NODE);
                TaskActions.present(e, selected instanceof JiraIssueRow
                        || !TaskMenu.jiraUrlsOf(selected).isEmpty());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                TaskNode selected = e.getData(TaskTreePanel.SELECTED_NODE);
                if (selected instanceof JiraIssueRow jira) {
                    BrowserUtil.browse(jira.issue().url());
                } else {
                    TaskMenu.jiraUrlsOf(selected).forEach(BrowserUtil::browse);
                }
            }
        };
    }

    /**
     * Whether {@code Add Jira Group…} applies to the selected row: a
     * registered project, its Issues section, or its Jira node — the group
     * lands there. Pure.
     */
    static boolean groupTargetNode(TaskNode node) {
        return node instanceof ProjectRow
                || node instanceof Section section && section.kind() == SectionKind.ISSUES
                || node instanceof TaskNode.JiraNode;
    }

    /**
     * {@code Add Jira Group…}: shows {@link JiraGroupDialog}, appends the
     * group to the project's {@link JiraGroups}, and rebuilds. Group
     * storage is {@code PropertiesComponent} only — EDT-safe, no password
     * safe, no modal. Only offered when the project's Jira connection is
     * enabled: a group without a connection could never fetch.
     */
    static AnAction addGroup(Project project, Consumer<Path> afterChange) {
        return new DumbAwareAction("Add Jira Group…",
                "Add a Jira subnode that lists issues matching its own JQL", AllIcons.General.Add) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                Path root = e.getData(TaskTreePanel.SELECTED_PROJECT_ROOT);
                TaskActions.present(e, groupTargetNode(e.getData(TaskTreePanel.SELECTED_NODE))
                        && root != null && jiraEnabled(root));
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                Path root = e.getData(TaskTreePanel.SELECTED_PROJECT_ROOT);
                if (root == null) {
                    return;
                }
                DrydockPluginService.getInstance().sharedProjects().byRoot(root)
                        .ifPresent(registered -> {
                            JiraGroupDialog dialog =
                                    new JiraGroupDialog(project, registered.displayName(), null);
                            if (!dialog.showAndGet()) {
                                return;
                            }
                            JiraGroups.load(registered.id())
                                    .withAdded(dialog.name(), dialog.jql())
                                    .save(registered.id());
                            afterChange.accept(root);
                        });
            }
        };
    }

    /**
     * {@code Edit Jira Group…} on a group node: the same dialog as Add,
     * prefilled; the saved group keeps its id and its position, so only
     * the query (or the name) changes underneath the tree. A group deleted
     * meanwhile (before or during the dialog) is not recreated; the tree is
     * rebuilt without it.
     */
    static AnAction editGroup(Project project, Consumer<Path> afterChange) {
        return new DumbAwareAction("Edit Jira Group…",
                "Rename this Jira group or change its JQL", AllIcons.Actions.Edit) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, e.getData(TaskTreePanel.SELECTED_NODE) instanceof JiraGroupRow);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (!(e.getData(TaskTreePanel.SELECTED_NODE) instanceof JiraGroupRow row)) {
                    return;
                }
                DrydockPluginService.getInstance().sharedProjects().byRoot(row.mainRoot())
                        .ifPresent(registered -> {
                            Optional<JiraGroup> stored =
                                    JiraGroups.load(registered.id()).byId(row.group().id());
                            if (stored.isEmpty()) {
                                // Deleted since the tree was drawn: never reopen or
                                // resurrect the stale row; a rebuild drops it.
                                afterChange.accept(row.mainRoot());
                                return;
                            }
                            JiraGroup current = stored.get();
                            JiraGroupDialog dialog =
                                    new JiraGroupDialog(project, registered.displayName(), current);
                            if (!dialog.showAndGet()) {
                                return;
                            }
                            // Re-read after the modal dialog: a delete or another edit
                            // may have landed meanwhile. Save over the fresh list,
                            // never over the snapshot.
                            JiraGroups.load(registered.id())
                                    .withEdited(new JiraGroup(current.id(), dialog.name(), dialog.jql()))
                                    .ifPresent(edited -> edited.save(registered.id()));
                            afterChange.accept(row.mainRoot());
                        });
            }
        };
    }

    /**
     * {@code Delete Jira Group…} on a group node, after a confirmation
     * naming the group. The issues themselves stay in Jira; only the
     * subnode and its cached listing go.
     */
    static AnAction deleteGroup(Project project, Consumer<Path> afterChange) {
        return new DumbAwareAction("Delete Jira Group…",
                "Remove this Jira subnode; the issues stay in Jira", AllIcons.General.Remove) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, e.getData(TaskTreePanel.SELECTED_NODE) instanceof JiraGroupRow);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (!(e.getData(TaskTreePanel.SELECTED_NODE) instanceof JiraGroupRow row)) {
                    return;
                }
                int answer = Messages.showYesNoDialog(project,
                        "Delete the Jira group \"" + row.group().name()
                                + "\"? Its issues stay in Jira.",
                        "Delete Jira Group", Messages.getQuestionIcon());
                if (answer != Messages.YES) {
                    return;
                }
                DrydockPluginService.getInstance().sharedProjects().byRoot(row.mainRoot())
                        .ifPresent(registered -> {
                            JiraGroups.load(registered.id())
                                    .without(row.group().id())
                                    .save(registered.id());
                            afterChange.accept(row.mainRoot());
                        });
            }
        };
    }

}
