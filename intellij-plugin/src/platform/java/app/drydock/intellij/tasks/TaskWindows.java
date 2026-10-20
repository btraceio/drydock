package app.drydock.intellij.tasks;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.project.Project;

import java.nio.file.Path;

/** Opening a worktree as its own IDE window. */
public final class TaskWindows {

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
        return ProjectUtil.openOrImport(worktreeRoot,
                OpenProjectTask.build().withForceOpenInNewFrame(true));
    }
}
