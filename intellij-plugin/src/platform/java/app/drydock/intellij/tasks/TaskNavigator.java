package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.agent.AgentExecutionService;
import app.drydock.intellij.worktree.WorktreeDialogs;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * "Switch to this task", from the Drydock tool window: its window to the
 * front, its agent tab selected, the keyboard in its terminal. A task
 * whose window is closed gets its window opened, with the tab showing and
 * <b>no agent started</b> — starting again stays an explicit Resume.
 */
public final class TaskNavigator {

    private TaskNavigator() {
    }

    /**
     * The window a task lives in: the one whose execution service knows the
     * session (it runs, or ran, there), else the one opened on the task's
     * working directory. Pure; {@code canonicalBase} gives a window's
     * canonical project directory.
     */
    static <W> Optional<W> windowFor(List<W> windows, Predicate<W> ownsExecution,
                                     Function<W, Optional<Path>> canonicalBase, Path canonicalWorkingDirectory) {
        Optional<W> owner = windows.stream().filter(ownsExecution).findFirst();
        if (owner.isPresent()) {
            return owner;
        }
        return windows.stream()
                .filter(window -> canonicalBase.apply(window).map(canonicalWorkingDirectory::equals).orElse(false))
                .findFirst();
    }

    /**
     * What to tell the user instead of opening a window, when the task has
     * no open window and its folder is gone (a worktree removed outside
     * Drydock). Pure.
     */
    static Optional<String> missingFolderMessage(boolean windowOpen, boolean folderExists, String taskName,
                                                 Path directory) {
        if (windowOpen || folderExists) {
            return Optional.empty();
        }
        return Optional.of("The folder of \"" + taskName + "\" is gone: " + directory
                + ". Use Done with This Task… to forget it.");
    }

    /**
     * Switches to {@code session}. Resolving the window touches the disk
     * (canonical paths), so it runs on the plugin's executor; the switch
     * itself happens on the EDT.
     */
    public static void switchTo(ManagedAgentSession session) {
        switchTo(session, target -> { });
    }

    /** As {@link #switchTo(ManagedAgentSession)}, then {@code then} with the task's window, on the EDT. */
    public static void switchTo(ManagedAgentSession session, Consumer<Project> then) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        plugin.executor().execute(() -> {
            Path directory = RepositoryCatalog.canonicalize(session.workingDirectory());
            List<Project> open = Arrays.stream(ProjectManager.getInstance().getOpenProjects())
                    .filter(project -> !project.isDisposed())
                    .toList();
            Optional<Project> window = windowFor(open,
                    project -> AgentExecutionService.getInstance(project).knownSessions().contains(session.id()),
                    project -> Optional.ofNullable(project.getBasePath())
                            .map(base -> RepositoryCatalog.canonicalize(Path.of(base))),
                    directory);
            Optional<String> gone = missingFolderMessage(window.isPresent(), Files.isDirectory(directory),
                    session.displayName(), directory);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (gone.isPresent()) {
                    Messages.showInfoMessage(gone.get(), "Drydock");
                    return;
                }
                Project target = window.filter(project -> !project.isDisposed())
                        .orElseGet(() -> WorktreeDialogs.openAsProject(directory));
                if (target == null || target.isDisposed()) {
                    return;
                }
                ProjectUtil.focusProjectWindow(target, true);
                AgentConsoles.show(target, session.id());
                AgentConsoles.focusConsole(target, session.id());
                then.accept(target);
            }, ModalityState.nonModal());
        });
    }
}
