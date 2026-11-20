package app.drydock.intellij.delayedtasks;

import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.RepositoryId;
import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.delayedtasks.NewDelayedTaskDialog;
import app.drydock.delayedtasks.NewDelayedTaskDialog.Prefill;
import app.drydock.delayedtasks.NewDelayedTaskDialog.ProjectChoice;
import app.drydock.delayedtasks.NewDelayedTaskDialog.Result;
import app.drydock.delayedtasks.TaskPrerequisitePicker;
import app.drydock.delayedtasks.TaskStoreWatcher;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.tasks.TaskLauncher;
import app.drydock.intellij.worktree.WorktreeErrors;
import app.drydock.intellij.tasks.TaskTreePanel;
import app.drydock.mcp.TaskToolContext;
import app.drydock.projects.SharedProject;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskSnapshot;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The Tasks node's two dialog flows over one dialog class (P5): creating a
 * task and editing one. Both run the same shape -- a background index build
 * over the CACHED watched snapshot (the watcher's publication, else the
 * service's own async scan; never a render-thread read), then the
 * {@link NewDelayedTaskDialog} on the EDT, then the write through the
 * production seam with progress. A canceled dialog writes nothing (P5's
 * zero-write); failures surface in a dialog and the background task ends on
 * every path, so no progress is stranded.
 *
 * <p>The attachment list the creation form offers is the dual registry the
 * task layer already names projects by
 * ({@link SharedProjectResolver#displayNames}): the shared list first, then
 * the install-catalog entries it does not know by root. An IDE project the
 * shared list does not carry yet rides along as an unregistered default
 * ({@code ProjectChoice} without an id, sorted in by name); its save
 * registers it, exactly as the create flow always has. The edit form offers
 * registered projects only: an edit moves a task that already HAS an id, so
 * every choice carries one.</p>
 */
public final class TaskDialogFlows {

    private static final Logger LOG = Logger.getInstance(TaskDialogFlows.class);

    private TaskDialogFlows() {
    }

    /** The Tasks node's New Task…: the creation form, the attachment defaulting to the
     * project the dialog was opened from. */
    public static void newTask(Project from) {
        open(from, null);
    }

    /** A task node's Edit…: the same form prefilled from the task's stored document. */
    public static void editTask(Project from, TaskId task) {
        open(from, Objects.requireNonNull(task, "task"));
    }

    /** The shared flow shape: one background index build, one dialog, one write. */
    private static void open(Project from, TaskId editing) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        ProgressManager.getInstance().run(new Task.Backgroundable(from,
                editing == null ? "Reading tasks…" : "Opening task…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    DelayedTaskService service = plugin.taskService();
                    TaskStoreWatcher.WatchedSnapshot watched = service.watchedSnapshot();
                    TaskSnapshot snapshot = watched != null ? watched.snapshot()
                            : service.snapshot().get(60, TimeUnit.SECONDS);
                    indicator.setText("Indexing tasks");
                    TaskDocument document = editing == null ? null
                            : snapshot.byId().get(editing);
                    TaskPrerequisitePicker.Index index = document != null
                            ? TaskPrerequisitePicker.Index.editing(snapshot, slugs(plugin), editing)
                            : TaskPrerequisitePicker.Index.of(snapshot, slugs(plugin));
                    Path root = Optional.ofNullable(from.getBasePath()).map(Path::of)
                            .map(RepositoryCatalog::canonicalize).orElse(null);
                    if (root == null || (editing != null && document == null)) {
                        return; // no document to edit: a vanished task opens no form
                    }
                    List<ProjectChoice> choices = document != null
                            ? projectChoices(plugin, false)
                            : projectChoices(plugin, true);
                    ProjectChoice attached = document != null
                            ? attachmentOf(document, choices, root)
                            : choices.stream().filter(choice -> choice.root().equals(root))
                                    .findFirst()
                                    .orElseGet(() -> new ProjectChoice(Optional.empty(), root,
                                            TaskTreePanel.repositoryDisplayName(plugin, root)));
                    ApplicationManager.getApplication().invokeLater(
                            () -> show(from, index, choices, attached, editing),
                            ModalityState.nonModal(), from.getDisposed());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.warn(editing == null ? "Could not open the New Task form"
                            : "Could not open the Edit Task form", e);
                    ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(from,
                            "Drydock could not open the task form: " + e.getMessage(), "Drydock"),
                            ModalityState.nonModal(), from.getDisposed());
                }
            }
        });
    }

    /** EDT: the form over one index. A confirmed OK saves through the production seam
     * -- create through the save core (no worktree, no agent, P5), an edit through the
     * human-only edit seam. A canceled dialog writes nothing. */
    private static void show(Project from, TaskPrerequisitePicker.Index index,
            List<ProjectChoice> choices, ProjectChoice attached, TaskId edited) {
        Prefill prefill = edited != null
                ? Prefill.edit(index.snapshot().byId().get(edited), attached)
                : Prefill.none(attached);
        NewDelayedTaskDialog dialog = new NewDelayedTaskDialog(index, choices, prefill);
        if (!dialog.showAndGet()) {
            return;
        }
        Result result = dialog.result();
        if (edited == null) {
            TaskTreePanel.savedTaskPlan(Optional.of(result), result.project().root(),
                    result.project().name()).ifPresent(plan -> TaskLauncher.saveTask(from, plan));
            return;
        }
        // An edit moves a task that has an id, so the edit form offers registered
        // choices only; the save refuses visibly should that invariant ever break.
        if (result.project().id().isEmpty()) {
            refuse(from, "Drydock cannot attach a task to an unregistered project; register "
                    + result.project().name() + " first.");
            return;
        }
        saveEdit(from, edited, result);
    }

    /** The edit write: immediate visible progress, the service's edit seam off the EDT,
     * a visible refusal on every refused path -- a refused edit changes nothing. */
    private static void saveEdit(Project from, TaskId edited, Result result) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        ProgressManager.getInstance().run(new Task.Backgroundable(from,
                "Saving task \"" + result.title() + "\"", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("Saving task \"" + result.title() + "\"");
                try {
                    DelayedTaskService.TaskEdit edit = new DelayedTaskService.TaskEdit(edited,
                            result.title(), result.doneBy(), result.after(), bodyLines(result),
                            result.project().id().orElseThrow());
                    TaskToolContext.Outcome outcome = plugin.taskService().edit(edit)
                            .get(60, TimeUnit.SECONDS);
                    if (outcome.refused()) {
                        refuse(from, outcome.refusal());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.warn("Could not edit the task " + edited.text(), e);
                    refuse(from, "Drydock could not save the task \"" + result.title() + "\": "
                            + WorktreeErrors.visibleMessage(e));
                }
            }
        });
    }

    /** The dialog body as note-candidate lines, the same split the create save stores. */
    private static List<String> bodyLines(Result result) {
        return List.of(result.body().split("\\R", -1));
    }

    /** One visible refusal; nothing is left running (the write already ended). */
    private static void refuse(Project from, String message) {
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(from, message, "Drydock"),
                ModalityState.nonModal(), from.getDisposed());
    }

    /**
     * The attachment choices the picker offers: the shared list's projects, then the
     * install-catalog entries the shared list does not know by root, one entry per id,
     * sorted by display name. With {@code withUnregisteredDefault} (creation) an IDE
     * project no registry knows yet rides along as an id-less choice -- the save
     * registers it. Memory-only reads over the two registries, safe off the EDT.
     */
    static List<ProjectChoice> projectChoices(DrydockPluginService plugin,
            boolean withUnregisteredDefault) {
        Map<RepositoryId, ProjectChoice> choices = new LinkedHashMap<>();
        for (SharedProject shared : plugin.sharedProjects().projects()) {
            choices.put(shared.id(), ProjectChoice.of(shared.id(), shared.root(),
                    shared.displayName()));
        }
        if (plugin.stateStore() != null) {
            for (app.drydock.domain.Repository repository
                    : plugin.stateStore().state().repositories()) {
                boolean known = plugin.sharedProjects().projects().stream()
                        .anyMatch(shared -> shared.root().equals(repository.root()));
                if (!known) {
                    choices.putIfAbsent(repository.id(), ProjectChoice.of(repository.id(),
                            repository.root(), repository.displayName()));
                }
            }
        }
        List<ProjectChoice> list = new ArrayList<>(choices.values());
        list.sort(Comparator.comparing(choice -> choice.name().toLowerCase()));
        return List.copyOf(list);
    }

    /** The choice a task is currently attached to: its registry entry, or a fallback
      * naming the raw id when the dual registry maps nothing (a legacy task) -- the
      * fallback's root is the dialog's own, which nothing reads unless the human
      * picks a different project. */
    private static ProjectChoice attachmentOf(TaskDocument document, List<ProjectChoice> choices,
            Path defaultRoot) {
        RepositoryId repository = document.repository();
        return choices.stream()
                .filter(choice -> choice.id().equals(Optional.of(repository)))
                .findFirst()
                .orElseGet(() -> new ProjectChoice(Optional.of(repository), defaultRoot,
                        TaskTreePanel.displayNamesByRepository(
                                DrydockPluginService.getInstance())
                                .getOrDefault(repository, repository.toString())));
    }

    /** The live catalog as the picker's project-slug mapping (P2), the same projection
     * the task layer applies: read once from memory, no I/O. */
    private static java.util.function.Function<RepositoryId, String> slugs(
            DrydockPluginService plugin) {
        Map<RepositoryId, String> displayNames = TaskTreePanel.displayNamesByRepository(plugin);
        return repository -> TaskTreePanel.slugOf(displayNames.get(repository));
    }
}
