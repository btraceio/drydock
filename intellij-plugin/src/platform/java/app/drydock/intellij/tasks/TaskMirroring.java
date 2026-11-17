package app.drydock.intellij.tasks;

import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.delayedtasks.SharedProjectResolver;
import app.drydock.delayedtasks.TaskStoreWatcher;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.git.JiraConnection;
import app.drydock.intellij.delayedtasks.DelayedTaskActions;
import app.drydock.git.JiraIssueWriter;
import app.drydock.github.GitHubIssueMirrorService;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.worktree.WorktreeErrors;
import app.drydock.mcp.TaskToolContext;
import app.drydock.projects.SharedProject;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskMirror;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.TaskSnapshot;

import com.intellij.ide.BrowserUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The tree's mirror flows: a task copied into a GitHub issue or a Jira
 * ticket, the address recorded back on the task, and the way from an
 * issue row back to its task. Each write asks first on the EDT, shows a
 * background progress task right away, runs the network write and the
 * task-store write off the EDT, and ends on every path with either a
 * notification or a visible refusal. The local task is never removed.
 *
 * <p>The order is remote first, then local: the address only exists once
 * the issue does. When the issue is created and recording it fails, the
 * refusal names the issue's address, so the user can add it by hand
 * instead of creating a duplicate.</p>
 */
public final class TaskMirroring {

    private static final Logger LOG = System.getLogger(TaskMirroring.class.getName());

    /** Matches the plugin.xml notificationGroup id. */
    private static final String GROUP = "Drydock tasks";

    /** One snapshot's mirror index, rebuilt only when the watched snapshot changes. */
    private record Index(TaskSnapshot snapshot, Map<String, TaskDocument> byMirror) { }

    private static volatile Index index;

    private TaskMirroring() {
    }

    /** Pure: Mirror to GitHub Issue is offered until the task has a GitHub mirror. */
    public static boolean offersGitHub(DelayedTaskRow row) {
        return !TaskMirror.hasMirror(row.mirrors(), TaskMirror.Kind.GITHUB);
    }

    /** Pure: Mirror to Jira is offered until the task has a Jira mirror. */
    public static boolean offersJira(DelayedTaskRow row) {
        return !TaskMirror.hasMirror(row.mirrors(), TaskMirror.Kind.JIRA);
    }

    /** Opens every recorded mirror of the task in the browser. */
    public static void openMirrors(DelayedTaskRow row) {
        row.mirrors().forEach(BrowserUtil::browse);
    }

    /**
     * The mirror addresses of every task whose {@code sessions} list names
     * the session, in snapshot order, each address once: two tasks naming
     * the same session may record the same mirror ({@link TaskMirror#byMirror}
     * keeps the first for the same reason), and a repeat would paint a
     * duplicate link and open a duplicate tab. From the task service's cached
     * watched snapshot only: no scan, no I/O (the same paint discipline as the
     * plan annotation). Empty before the first snapshot and for a session no
     * task claims.
     */
    public static List<String> mirrorsOfSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        TaskStoreWatcher.WatchedSnapshot watched =
                DrydockPluginService.getInstance().taskService().watchedSnapshot();
        if (watched == null) {
            return List.of();
        }
        List<String> mirrors = new ArrayList<>();
        for (TaskDocument document : watched.snapshot().bySessionReference()
                .getOrDefault(sessionId, List.of())) {
            mirrors.addAll(document.mirrors());
        }
        return TaskMirror.uniqueByAddress(mirrors);
    }

    /**
     * The task an issue page is a mirror of, from the task service's cached
     * watched snapshot only: no scan, no I/O. Empty before the first
     * snapshot and for an issue no task mirrors.
     */
    static Optional<TaskDocument> mirroredTask(String url) {
        TaskStoreWatcher.WatchedSnapshot watched = DrydockPluginService.getInstance().taskService().watchedSnapshot();
        if (watched == null || url == null) {
            return Optional.empty();
        }
        Index current = index;
        if (current == null || current.snapshot() != watched.snapshot()) {
            current = new Index(watched.snapshot(), TaskMirror.byMirror(watched.snapshot()));
            index = current;
        }
        return Optional.ofNullable(current.byMirror().get(TaskMirror.normalize(url)));
    }

    /** Opens the task file an issue page mirrors, when a task mirrors it. */
    static void openMirroredTask(Project from, String url) {
        mirroredTask(url).ifPresentOrElse(
                document -> DelayedTaskActions.edit(from, document.repository(), document.id(), document.title()),
                () -> refuse(from, "No Drydock task mirrors " + url + "."));
    }

    /**
     * Mirror to GitHub Issue: confirm, then {@code gh api} creates the issue
     * in the project's repository, then the task records the issue's page.
     */
    public static void mirrorToGitHub(Project from, DelayedTaskRow row) {
        Optional<SharedProject> project = registeredProject(from, row.repository(), true);
        if (project.isEmpty()) {
            return;
        }
        SharedProject target = project.get();
        GitHubProjectSettings settings = GitHubProjectSettings.load(target.id());
        String where = settings.repository().orElse("the GitHub repository of " + target.displayName());
        if (Messages.showOkCancelDialog(from, "Create an issue in " + where + " from the task '" + row.title()
                        + "'? The task stays here and records the issue's link; the issue names the task and"
                        + " carries its metadata in a hidden comment.",
                "Mirror to GitHub Issue", "Create Issue", "Cancel", null) != Messages.YES) {
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(from,
                "Mirroring '" + row.title() + "' to GitHub…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("Creating the GitHub issue for '" + row.title() + "'");
                try (GitHubIssueMirrorService github = new GitHubIssueMirrorService()) {
                    TaskDocument document = freshDocument(row);
                    GitHubIssueMirrorService.Outcome outcome = github.create(target.root(), settings.repository(),
                            document.title(), TaskMirror.githubBody(document, target.displayName()))
                            .get(60, TimeUnit.SECONDS);
                    switch (outcome) {
                        case GitHubIssueMirrorService.Created created ->
                                record(from, row, created.url(), Optional.empty());
                        case GitHubIssueMirrorService.Rejected rejected ->
                                refuse(from, "GitHub did not create the issue: " + rejected.message());
                        case GitHubIssueMirrorService.Unavailable unavailable ->
                                refuse(from, "Mirroring to GitHub needs an authenticated gh: " + unavailable.message());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    refuse(from, interrupted(row, "GitHub issue"));
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not mirror " + row.id().text() + " to GitHub", e);
                    refuse(from, "Drydock could not mirror '" + row.title() + "' to GitHub: "
                            + WorktreeErrors.visibleMessage(e));
                }
            }
        });
    }

    /**
     * Mirror to Jira: the project's Jira connection must be enabled; the
     * dialog asks for the Jira project and issue type, then the ticket is
     * created, the metadata stored as its property, and the task records
     * the ticket's page.
     */
    public static void mirrorToJira(Project from, DelayedTaskRow row) {
        Optional<SharedProject> project = registeredProject(from, row.repository(), false);
        if (project.isEmpty()) {
            return;
        }
        SharedProject target = project.get();
        JiraProjectSettings settings = JiraProjectSettings.load(target.id());
        Optional<JiraConnection> connection = settings.connection("");
        if (connection.isEmpty()) {
            refuse(from, "Jira is not enabled for " + target.displayName() + "; turn it on in its Jira Settings"
                    + " first.");
            return;
        }
        JiraMirrorDialog dialog = new JiraMirrorDialog(from, row.title(), target.displayName(),
                JiraMirrorDialog.lastProjectKey(target.id()), JiraMirrorDialog.lastIssueType(target.id()));
        if (!dialog.showAndGet()) {
            return;
        }
        String projectKey = dialog.projectKey();
        String issueType = dialog.issueType();
        JiraMirrorDialog.remember(target.id(), projectKey, issueType);
        ProgressManager.getInstance().run(new Task.Backgroundable(from,
                "Mirroring '" + row.title() + "' to Jira…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("Creating the Jira ticket for '" + row.title() + "' in " + projectKey);
                try (JiraIssueWriter jira = new JiraIssueWriter(JiraIssueSource::ideClientBuilder)) {
                    TaskDocument document = freshDocument(row);
                    String secret = JiraCredentials.read(settings).orElse("");
                    JiraIssueWriter.Outcome outcome = jira.create(connection.get(), secret, projectKey, issueType,
                            document.title(), TaskMirror.jiraDescription(document, target.displayName()),
                            TaskMirror.JIRA_PROPERTY, TaskMirror.metadata(document, target.displayName()));
                    switch (outcome) {
                        case JiraIssueWriter.Created created ->
                                record(from, row, created.url(), created.propertyProblem());
                        case JiraIssueWriter.Failed failed ->
                                refuse(from, "Jira did not create the ticket: " + failed.message());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    refuse(from, interrupted(row, "Jira ticket"));
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not mirror " + row.id().text() + " to Jira", e);
                    refuse(from, "Drydock could not mirror '" + row.title() + "' to Jira: "
                            + WorktreeErrors.visibleMessage(e));
                }
            }
        });
    }

    /**
     * The refusal for an interrupted mirror. The remote write runs on its
     * own executor and is not cancelled by the interrupt, so whether the
     * mirror exists is unknown.
     */
    private static String interrupted(DelayedTaskRow row, String what) {
        return "Mirroring '" + row.title() + "' was interrupted; whether the " + what + " was created is unknown."
                + " Check before retrying.";
    }

    /**
     * Background: the task's document as the store holds it now, not as the
     * row was painted, so the mirror carries the latest body and metadata.
     */
    private static TaskDocument freshDocument(DelayedTaskRow row)
            throws InterruptedException, ExecutionException, TimeoutException {
        return DrydockPluginService.getInstance().taskService().snapshot().get(60, TimeUnit.SECONDS)
                .find(row.id())
                .orElseThrow(() -> new IllegalStateException("the task '" + row.title()
                        + "' is no longer in the task store"));
    }

    /**
     * Background: records {@code url} on the task through the service's
     * single writer, then tells the user. A failure here names the address
     * of the issue that does exist, so it is not created twice.
     */
    private static void record(Project from, DelayedTaskRow row, String url, Optional<String> warning) {
        DelayedTaskService service = DrydockPluginService.getInstance().taskService();
        try {
            TaskToolContext.Outcome outcome = service.recordMirror(row.id(), url).get(60, TimeUnit.SECONDS);
            if (outcome.refused()) {
                refuse(from, "Created " + url + ", but the task could not record it: " + outcome.refusal());
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            refuse(from, "Created " + url + ", but recording it on the task was interrupted. Add it to the"
                    + " task's 'mirrors' list by hand.");
            return;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not record the mirror " + url + " on " + row.id().text(), e);
            refuse(from, "Created " + url + ", but the task could not record it: "
                    + WorktreeErrors.visibleMessage(e) + ". Add it to the task's 'mirrors' list by hand.");
            return;
        }
        notify(from, "Mirrored '" + row.title() + "' to " + TaskMirror.label(url), url, warning);
    }

    /** Any thread: the success balloon with an Open action, a warning one when the metadata was not stored. */
    private static void notify(Project from, String title, String url, Optional<String> warning) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Notification balloon = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
                    .createNotification(title, warning.orElse(url),
                            warning.isPresent() ? NotificationType.WARNING : NotificationType.INFORMATION);
            balloon.addAction(NotificationAction.createSimpleExpiring("Open", () -> BrowserUtil.browse(url)));
            balloon.notify(from);
        }, ModalityState.nonModal(), from.getDisposed());
    }

    /**
     * EDT: the registered project the task belongs to, or a visible
     * refusal. A GitHub mirror runs {@code gh} in the project's checkout,
     * so it needs a local one and refuses a remote (SSH) project; a Jira
     * mirror talks to the site alone.
     */
    private static Optional<SharedProject> registeredProject(Project from, RepositoryId repository,
                                                             boolean needsCheckout) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        Optional<SharedProject> project = resolveShared(plugin.sharedProjects().projects(),
                plugin.repositories(), repository);
        if (project.isEmpty()) {
            refuse(from, "The task's project is not registered in Drydock.");
            return Optional.empty();
        }
        return checkedOut(from, project.get(), needsCheckout);
    }

    /** Any thread: the project when it fits the mirror's checkout demand, or the visible
      * refusal and empty. */
    private static Optional<SharedProject> checkedOut(Project from, SharedProject project,
                                                      boolean needsCheckout) {
        if (needsCheckout && project.isRemote()) {
            refuse(from, "Mirroring to GitHub needs a local checkout; " + project.displayName()
                    + " is a remote project.");
            return Optional.empty();
        }
        return Optional.of(project);
    }

    /**
     * Pure: the shared-list project a task's repository id names. Delegates to
     * {@link SharedProjectResolver#resolve}, the single copy of the dual-registry
     * resolution this package shares with delayedtasks, so the mirror-time resolution
     * and the create-time stamp cannot drift apart. The shared entry is what the flow
     * needs: the Jira and GitHub settings are keyed by the shared registry's id. Empty
     * when neither registry maps the id -- the caller refuses.
     */
    static Optional<SharedProject> resolveShared(List<SharedProject> shared, List<Repository> install,
                                                 RepositoryId repository) {
        return SharedProjectResolver.resolve(shared, install, repository);
    }

    /** Any thread: one visible refusal, shown on the EDT. */
    private static void refuse(Project from, String message) {
        ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(from, message, "Drydock"),
                ModalityState.nonModal(), from.getDisposed());
    }
}
