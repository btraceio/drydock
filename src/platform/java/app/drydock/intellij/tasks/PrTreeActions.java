package app.drydock.intellij.tasks;

import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.tasks.TaskNode.PrGroupRow;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.SectionKind;

import com.intellij.icons.AllIcons;
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
 * The pull-request subgroup rows' tree actions, mirroring {@link JiraTreeActions}.
 */
final class PrTreeActions {

    private PrTreeActions() {
    }

    /**
     * Whether the selected project's GitHub pull-request listing is enabled —
     * the gate for offering {@code Add PR Group…}. Reads the shared-projects
     * list and the in-memory properties; the list lookup canonicalizes
     * {@code root} (filesystem IO), so call it off the EDT.
     */
    static boolean pullRequestsEnabled(Path root) {
        return DrydockPluginService.getInstance().sharedProjects().byRoot(root)
                .map(registered -> GitHubProjectSettings.load(registered.id()).pullRequestsEnabled())
                .orElse(false);
    }

    /**
     * Whether {@code Add PR Group…} applies to the selected row: a registered
     * project or its Pull Requests section. Pure.
     */
    static boolean groupTargetNode(TaskNode node) {
        return node instanceof ProjectRow
                || node instanceof Section section && section.kind() == SectionKind.PULL_REQUESTS;
    }

    /**
     * {@code Add PR Group…}: shows {@link PrGroupDialog}, appends the group
     * to the project's {@link PrGroups}, and rebuilds. Only offered when the
     * project's pull-request listing is enabled.
     */
    static AnAction addGroup(Project project, Consumer<Path> afterChange) {
        return new DumbAwareAction("Add PR Group…",
                "Add a Pull Requests subgroup filtered by a label expression", AllIcons.General.Add) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                Path root = e.getData(TaskTreePanel.SELECTED_PROJECT_ROOT);
                TaskActions.present(e, groupTargetNode(e.getData(TaskTreePanel.SELECTED_NODE))
                        && root != null && pullRequestsEnabled(root));
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
                            PrGroupDialog dialog =
                                    new PrGroupDialog(project, registered.displayName(), null);
                            if (!dialog.showAndGet()) {
                                return;
                            }
                            PrGroups.load(registered.id())
                                    .withAdded(dialog.name(), dialog.filter())
                                    .save(registered.id());
                            afterChange.accept(root);
                        });
            }
        };
    }

    /**
     * {@code Edit PR Group…} on a PR subgroup node: the same dialog as Add,
     * prefilled; the saved group keeps its id and position. A group deleted
     * meanwhile (before or during the dialog) is not resurrected.
     */
    static AnAction editGroup(Project project, Consumer<Path> afterChange) {
        return new DumbAwareAction("Edit PR Group…",
                "Rename this PR group or change its label filter", AllIcons.Actions.Edit) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, e.getData(TaskTreePanel.SELECTED_NODE) instanceof PrGroupRow);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (!(e.getData(TaskTreePanel.SELECTED_NODE) instanceof PrGroupRow row)) {
                    return;
                }
                DrydockPluginService.getInstance().sharedProjects().byRoot(row.mainRoot())
                        .ifPresent(registered -> {
                            Optional<PrGroup> stored =
                                    PrGroups.load(registered.id()).byId(row.group().id());
                            if (stored.isEmpty()) {
                                afterChange.accept(row.mainRoot());
                                return;
                            }
                            PrGroup current = stored.get();
                            PrGroupDialog dialog =
                                    new PrGroupDialog(project, registered.displayName(), current);
                            if (!dialog.showAndGet()) {
                                return;
                            }
                            PrGroups.load(registered.id())
                                    .withEdited(new PrGroup(current.id(), dialog.name(), dialog.filter()))
                                    .ifPresent(edited -> edited.save(registered.id()));
                            afterChange.accept(row.mainRoot());
                        });
            }
        };
    }

    /**
     * {@code Delete PR Group…} on a PR subgroup node, after a confirmation
     * naming the group. The pull requests themselves stay on GitHub.
     */
    static AnAction deleteGroup(Project project, Consumer<Path> afterChange) {
        return new DumbAwareAction("Delete PR Group…",
                "Remove this PR subgroup; the pull requests stay on GitHub", AllIcons.General.Remove) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, e.getData(TaskTreePanel.SELECTED_NODE) instanceof PrGroupRow);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (!(e.getData(TaskTreePanel.SELECTED_NODE) instanceof PrGroupRow row)) {
                    return;
                }
                int answer = Messages.showYesNoDialog(project,
                        "Delete the PR group \"" + row.group().name()
                                + "\"? Its pull requests stay on GitHub.",
                        "Delete PR Group", Messages.getQuestionIcon());
                if (answer != Messages.YES) {
                    return;
                }
                DrydockPluginService.getInstance().sharedProjects().byRoot(row.mainRoot())
                        .ifPresent(registered -> {
                            PrGroups.load(registered.id())
                                    .without(row.group().id())
                                    .save(registered.id());
                            afterChange.accept(row.mainRoot());
                        });
            }
        };
    }
}
