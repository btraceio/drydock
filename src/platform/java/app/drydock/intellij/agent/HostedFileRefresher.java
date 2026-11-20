package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When a hosted agent's turn ends, its files open in any window are
 * refreshed (cross-project tabs, "Other rules").
 *
 * <p>An agent whose worktree no window has as its project edits files the IDE
 * does not watch: an editor showing one of them as a non-project file stayed
 * stale for over a minute in the spike, until something else refreshed it.
 * The end of a turn is when the edits are done.</p>
 *
 * <p>Registered as an application listener on {@link SessionActivityListener};
 * it remembers each session's last activity to see the busy-to-done edge.</p>
 */
public final class HostedFileRefresher implements SessionActivityListener {

    private static final Map<ManagedSessionId, Optional<SessionActivity>> last = new ConcurrentHashMap<>();

    @Override
    public void activityChanged(ManagedSessionId sessionId, Optional<SessionActivity> activity) {
        // An ended agent reports no activity; forgetting it then keeps this
        // map to the agents that are alive.
        Optional<SessionActivity> before = Optional.ofNullable(activity.isEmpty()
                ? last.remove(sessionId) : last.put(sessionId, activity)).flatMap(value -> value);
        if (!before.equals(Optional.of(SessionActivity.BUSY)) || activity.equals(before)) {
            return;
        }
        Optional<Path> directory = AgentExecutionRegistry.getInstance().execution(sessionId)
                .map(AgentExecution::workingDirectory);
        if (directory.isEmpty()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> refresh(before, activity, directory.get()),
                ModalityState.any());
    }

    /** EDT: memory only — open windows and their open editors — then an asynchronous refresh. */
    private static void refresh(Optional<SessionActivity> before, Optional<SessionActivity> after, Path directory) {
        Path worktree = directory.toAbsolutePath().normalize();
        List<Project> open = List.of(ProjectManager.getInstance().getOpenProjects());
        // Hosted: no window has the worktree as its project, so nothing
        // watches it. Lexical comparison, since this is the EDT; a miss only
        // costs a refresh that was not needed.
        boolean hosted = open.stream().noneMatch(project -> project.getBasePath() != null
                && Path.of(project.getBasePath()).toAbsolutePath().normalize().equals(worktree));
        if (!HostedLifecycle.refreshAfter(before, after, hosted)) {
            return;
        }
        List<VirtualFile> stale = new ArrayList<>();
        for (Project project : open) {
            if (project.isDisposed()) {
                continue;
            }
            for (VirtualFile file : FileEditorManager.getInstance(project).getOpenFiles()) {
                if (file.isInLocalFileSystem() && Path.of(file.getPath()).normalize().startsWith(worktree)) {
                    stale.add(file);
                }
            }
        }
        if (!stale.isEmpty()) {
            VfsUtil.markDirtyAndRefresh(true, false, false, stale.toArray(VirtualFile[]::new));
        }
    }
}
