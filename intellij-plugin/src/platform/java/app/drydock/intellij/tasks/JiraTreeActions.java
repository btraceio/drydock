package app.drydock.intellij.tasks;

import app.drydock.intellij.DrydockPluginService;
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
