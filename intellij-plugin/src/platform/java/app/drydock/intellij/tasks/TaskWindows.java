package app.drydock.intellij.tasks;

import app.drydock.intellij.DrydockPluginService;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opening a worktree as its own IDE window. */
public final class TaskWindows {

    private static final String OPEN_WINDOWS_KEY = "drydock.openTaskWindows";
    private static final AtomicBoolean RESTORE_STARTED = new AtomicBoolean();

    private TaskWindows() {
    }

    /**
     * Opens {@code worktreeRoot} as a project in a new window. The sync
     * {@code ProjectUtil.openOrImport(Path, OpenProjectTask)} is the File &gt;
     * Open path and wraps itself in modal progress when called from the
     * EDT, so the EDT hop is the sanctioned path, not a re-implementation of
     * it.
     *
     * <p>Returns the opened project, or null when the open failed or was
     * cancelled: starting a task has to continue <em>into</em> that window
     * to open the console tab and start the agent, so it needs the instance
     * rather than only the side effect.</p>
     *
     * <p>EDT, from a write-safe context: callers hop here with
     * {@code ModalityState.nonModal()}, never {@code any()}.</p>
     */
    public static Project openAsProject(Path worktreeRoot) {
        // openOrImport, not ProjectManagerEx.openProject: only the former runs
        // the project-open processors File > Open runs -- a module for a plain
        // directory, a Gradle or Maven import for a build. Without them the
        // worktree window opened with a Project view holding nothing but
        // External Libraries: no sources, not even the files the agent was
        // editing.
        Project opened = ProjectUtil.openOrImport(worktreeRoot,
                OpenProjectTask.build().withForceOpenInNewFrame(true));
        if (opened != null) {
            remember(worktreeRoot);
        }
        return opened;
    }

    /** Keeps a task window across an IDE quit; an ordinary close forgets it. EDT, memory/config only. */
    public static void windowClosing(Project project, boolean quitting) {
        if (quitting || project.getBasePath() == null) {
            return;
        }
        String path = normalized(Path.of(project.getBasePath()));
        PropertiesComponent properties = PropertiesComponent.getInstance();
        List<String> current = values(properties);
        List<String> updated = withoutRemembered(current, path);
        if (!updated.equals(current)) {
            if (updated.isEmpty()) {
                properties.unsetValue(OPEN_WINDOWS_KEY);
            } else {
                properties.setValues(OPEN_WINDOWS_KEY, updated.toArray(String[]::new));
            }
        }
    }

    /** Reopens the task windows remembered at the last IDE quit, without starting their agents. */
    public static void restoreRemembered() {
        if (!RESTORE_STARTED.compareAndSet(false, true)) {
            return;
        }
        List<Path> remembered = values(PropertiesComponent.getInstance()).stream().map(Path::of).toList();
        DrydockPluginService.getInstance().executor().execute(() -> {
            List<Path> existing = remembered.stream().filter(Files::isDirectory).toList();
            ApplicationManager.getApplication().invokeLater(() -> {
                Set<String> open = new LinkedHashSet<>();
                for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                    if (!project.isDisposed() && project.getBasePath() != null) {
                        open.add(normalized(Path.of(project.getBasePath())));
                    }
                }
                for (Path path : existing) {
                    if (open.add(normalized(path))) {
                        openAsProject(path);
                    }
                }
            }, ModalityState.nonModal());
        });
    }

    private static void remember(Path path) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        List<String> current = values(properties);
        List<String> updated = withRemembered(current, normalized(path));
        if (!updated.equals(current)) {
            properties.setValues(OPEN_WINDOWS_KEY, updated.toArray(String[]::new));
        }
    }

    private static List<String> values(PropertiesComponent properties) {
        String[] values = properties.getValues(OPEN_WINDOWS_KEY);
        return values == null ? List.of() : List.copyOf(Arrays.asList(values));
    }

    static List<String> withRemembered(List<String> current, String path) {
        LinkedHashSet<String> result = new LinkedHashSet<>(current);
        result.add(path);
        return List.copyOf(result);
    }

    static List<String> withoutRemembered(List<String> current, String path) {
        List<String> result = new ArrayList<>(current);
        result.removeIf(path::equals);
        return List.copyOf(result);
    }

    private static String normalized(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
