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
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The Jira rows' tree actions (spec AC11, AC15, AC22). {@code TaskTreePanel}
 * is already the panel's own home, so these live beside it and reuse its data
 * keys and presentation seams, exactly like the GitHub settings action.
 *
 * <p>The settings action never touches the password safe on the EDT: the
 * token is read before the dialog and written after it, each inside a
 * {@link Task.Modal} so the password safe's own non-EDT assertion holds and
 * the user sees progress. The tree reacts only in {@code onFinished}, after
 * the write completed.</p>
 */
final class JiraTreeActions {

    private JiraTreeActions() {
    }

    /**
     * Whether the Jira settings action applies to the selected row: a
     * registered project, its Issues section, or one of its Jira rows (spec
     * AC11). Pure.
     */
    static boolean settingsNode(TaskNode node) {
        return node instanceof ProjectRow
                || node instanceof Section section && section.kind() == SectionKind.ISSUES
                || node instanceof JiraIssueRow;
    }

    /**
     * Whether the selected project's Jira connection is enabled — the gate
     * for offering {@code Add Jira Group…}. Reads the shared-projects list
     * and the in-memory properties: EDT-safe, no password-safe touch.
     */
    static boolean jiraEnabled(Path root) {
        return DrydockPluginService.getInstance().sharedProjects().byRoot(root)
                .map(registered -> JiraProjectSettings.load(registered.id()).enabled())
                .orElse(false);
    }

    /** {@code Open in Jira}: the selected Jira row's browse URL in the browser (spec AC22). */
    static AnAction openInJira() {
        return new DumbAwareAction("Open in Jira", "Open this Jira issue in the browser",
                TaskTreeRenderer.JIRA_ICON) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, e.getData(TaskTreePanel.SELECTED_NODE) instanceof JiraIssueRow);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (e.getData(TaskTreePanel.SELECTED_NODE) instanceof JiraIssueRow jira) {
                    BrowserUtil.browse(jira.issue().url());
                }
            }
        };
    }

    /**
     * {@code Jira Settings…} for the selected project: reads the stored token
     * off the EDT, shows {@link JiraProjectSettingsDialog}, saves the
     * non-secret settings, writes the token off the EDT, and only then runs
     * {@code afterSave} with the project's root (spec AC14, AC15).
     */
    static AnAction settings(Project project, Consumer<Path> afterSave) {
        return new DumbAwareAction("Jira Settings…",
                "Configure Jira issues for this Drydock project", AllIcons.General.Settings) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, settingsNode(e.getData(TaskTreePanel.SELECTED_NODE)));
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                Path root = e.getData(TaskTreePanel.SELECTED_PROJECT_ROOT);
                if (root == null) {
                    return;
                }
                DrydockPluginService.getInstance().sharedProjects().byRoot(root)
                        .ifPresent(registered -> editSettings(project, root, registered, afterSave));
            }
        };
    }

    /**
     * Whether {@code Add Jira Group…} applies to the selected row: a
     * registered project or its Issues section — the group lands there.
     * Pure.
     */
    static boolean groupTargetNode(TaskNode node) {
        return node instanceof ProjectRow
                || node instanceof Section section && section.kind() == SectionKind.ISSUES;
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
                return ActionUpdateThread.EDT;
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
                return ActionUpdateThread.EDT;
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
                return ActionUpdateThread.EDT;
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

    /** Reads the stored token under a modal progress, then shows the dialog. */
    private static void editSettings(Project project, Path root, SharedProject registered,
                                     Consumer<Path> afterSave) {
        JiraProjectSettings current = JiraProjectSettings.load(registered.id());
        ProgressManager.getInstance().run(new Task.Modal(project, "Reading Jira credentials…", true) {
            private String stored = "";

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                // Reading a stored token with nothing configured yet would
                // only mint a password-safe key for a base URL the user has
                // not chosen; skip it.
                stored = current.baseUrl().isEmpty() ? "" : JiraCredentials.read(current).orElse("");
            }

            @Override
            public void onSuccess() {
                showSettings(project, root, registered, current, stored, afterSave);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                // The token could not be read, but the rest is still editable.
                showSettings(project, root, registered, current, "", afterSave);
            }
        });
    }

    /** Shows the dialog on the EDT, then saves settings and writes the token off it. */
    private static void showSettings(Project project, Path root, SharedProject registered,
                                     JiraProjectSettings current, String currentSecret,
                                     Consumer<Path> afterSave) {
        JiraProjectSettingsDialog dialog =
                new JiraProjectSettingsDialog(project, registered, current, currentSecret);
        if (!dialog.showAndGet()) {
            return;
        }
        JiraProjectSettings settings = dialog.settings();
        settings.save(registered.id());
        ProgressManager.getInstance().run(new Task.Modal(project, "Saving Jira credentials…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                JiraCredentials.write(settings, dialog.secret());
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                Messages.showErrorDialog(project,
                        "Could not store the Jira token (" + error.getClass().getSimpleName() + ")",
                        "Jira Settings");
            }

            @Override
            public void onFinished() {
                // Only after the write: the rebuild must see the new token.
                afterSave.accept(root);
            }
        });
    }
}
