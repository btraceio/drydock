package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.agent.AgentExecutionRegistry;

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

/**
 * Where "Switch to Task" lands a task. The default ({@link #switchTo}) is
 * the window the ask came from: a running console moves here from whichever
 * window hosted it ({@link AgentConsoles#showHere}, so the previous host's
 * tab closes and no "Show agent" detour remains), and an idle session's tab
 * shows here with <b>no agent started</b> — starting again stays an explicit
 * Resume. The explicit own-window jump is the menu's second entry
 * ({@code AgentConsoles#jumpToOwnWindow}); {@link #openOwnWindow} serves
 * Resume in Its Own Window, which must run its resume inside the window it
 * just raised or opened.
 */
public final class TaskNavigator {

    private TaskNavigator() {
    }

    /**
     * The window a task lives in: the one showing its agent, else the one
     * opened on the task's working directory. Pure; {@code canonicalBase}
     * gives a window's canonical project directory.
     */
    static <W> Optional<W> windowFor(List<W> windows, Optional<W> showing,
                                     Function<W, Optional<Path>> canonicalBase, Path canonicalWorkingDirectory) {
        Optional<W> owner = showing.filter(windows::contains);
        if (owner.isPresent()) {
            return owner;
        }
        return RaiseOrOpenWindow.findOpenWindow(windows, canonicalBase, canonicalWorkingDirectory);
    }

    /**
     * What to tell the user instead of showing anything, when the task's
     * folder is gone (a worktree removed outside Drydock). Pure.
     */
    static Optional<String> missingFolderMessage(boolean folderExists, String taskName,
                                                 Path directory) {
        if (folderExists) {
            return Optional.empty();
        }
        return Optional.of("The folder of \"" + taskName + "\" is gone: " + directory
                + ". Use Done with This Task… to forget it.");
    }

    /**
     * EDT. Switches to the session {@code id} names, as the catalog has it
     * now — the babysitting jumps' target, which is the other side of a
     * pairing rather than the row the user is on. A session no longer in the
     * catalog says so instead. Memory-only read of the state snapshot.
     */
    public static void switchTo(Project current, ManagedSessionId id) {
        Optional<ManagedAgentSession> session = DrydockPluginService.getInstance().stateStore().state().sessions()
                .stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst();
        if (session.isEmpty()) {
            Messages.showInfoMessage(current, "That session is no longer in Drydock.", "Drydock");
            return;
        }
        switchTo(current, session.get());
    }

    /**
     * Shows {@code session} in {@code current}, the window the ask came
     * from. Resolving the folder touches the disk (canonical paths), so it
     * runs on the plugin's executor; the show happens on the EDT. A null or
     * disposed {@code current} (a balloon with no focused window) falls back
     * to {@link #openOwnWindow}.
     */
    public static void switchTo(Project current, ManagedAgentSession session) {
        if (current == null || current.isDisposed()) {
            openOwnWindow(session, target -> { });
            return;
        }
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        plugin.executor().execute(() -> {
            Path directory = RepositoryCatalog.canonicalize(session.workingDirectory());
            boolean folderExists = Files.isDirectory(directory);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (current.isDisposed()) {
                    return;
                }
                missingFolderMessage(folderExists, session.displayName(), directory)
                        .ifPresent(message -> Messages.showInfoMessage(current, message, "Drydock"));
                if (!folderExists) {
                    return;
                }
                // One agent per session across the IDE: a running console
                // moves here (the previous host's tab closes); an idle
                // session's tab shows here and nothing starts.
                if (AgentExecutionRegistry.getInstance().isRunning(session.id())) {
                    AgentConsoles.showHere(current, session.id());
                } else {
                    AgentConsoles.show(current, session.id());
                }
            }, ModalityState.nonModal());
        });
    }

    /**
     * Opens or raises the task's own window — the window showing its agent,
     * else the one on its working directory — shows the tab there with no
     * agent started, then runs {@code then} with that window, on the EDT.
     * Resume in Its Own Window's path: the resume must land in the window
     * this opened. Resolving the window touches the disk (canonical paths),
     * so it runs on the plugin's executor.
     */
    public static void openOwnWindow(ManagedAgentSession session, Consumer<Project> then) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        plugin.executor().execute(() -> {
            Path directory = RepositoryCatalog.canonicalize(session.workingDirectory());
            List<Project> open = Arrays.stream(ProjectManager.getInstance().getOpenProjects())
                    .filter(project -> !project.isDisposed())
                    .toList();
            Optional<Project> window = windowFor(open, AgentExecutionRegistry.getInstance().host(session.id()),
                    project -> Optional.ofNullable(project.getBasePath())
                            .map(base -> RepositoryCatalog.canonicalize(Path.of(base))),
                    directory);
            Optional<String> gone = missingFolderMessage(Files.isDirectory(directory),
                    session.displayName(), directory);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (gone.isPresent()) {
                    Messages.showInfoMessage(gone.get(), "Drydock");
                    return;
                }
                Project target = window.filter(project -> !project.isDisposed())
                        .orElseGet(() -> TaskWindows.openAsProject(directory));
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
