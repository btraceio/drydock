package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.SessionActivity;
import app.drydock.git.WorktreeService;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.AgentExecutionService;
import app.drydock.projects.SharedProject;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Collects what {@link TaskTreeModel} needs: the shared projects, the
 * plugin's sessions, each project's worktrees, activity and liveness, with
 * every path made canonical. Blocking (git, the disk): call on the
 * plugin's executor, never the EDT.
 *
 * <p>Worktree lists are cached per main checkout until {@link
 * #invalidateWorktrees()}: activity changes arrive every second or so and
 * must not each cost one {@code git worktree list} per project.</p>
 */
final class TaskTreeInputs implements AutoCloseable {

    private static final Logger LOG = System.getLogger(TaskTreeInputs.class.getName());

    private static final long GIT_TIMEOUT_SECONDS = 15;

    private final WorktreeService worktrees = new WorktreeService();
    private final Map<Path, List<Worktree>> worktreeCache = new ConcurrentHashMap<>();

    void invalidateWorktrees() {
        worktreeCache.clear();
    }

    TaskTreeModel.Inputs gather(Project here, String filter) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        List<SharedProject> projects = new ArrayList<>();
        Map<Path, List<Worktree>> byMain = new HashMap<>();
        Map<Path, Path> mainRootOf = new HashMap<>();
        Set<Path> missing = new HashSet<>();
        for (SharedProject project : plugin.sharedProjects().projects()) {
            if (project.isRemote()) {
                projects.add(project);
                continue;
            }
            Path root = RepositoryCatalog.canonicalize(project.root());
            projects.add(new SharedProject(project.id(), root, project.displayName(), project.addedAt(),
                    project.remote()));
            if (!Files.isDirectory(root)) {
                missing.add(root);
                continue;
            }
            List<Worktree> list = worktreesOf(root);
            byMain.put(root, list);
            list.forEach(worktree -> mainRootOf.put(worktree.path(), root));
            mainRootOf.put(root, root);
        }

        Optional<Path> currentWorking = Optional.ofNullable(here.getBasePath())
                .map(base -> RepositoryCatalog.canonicalize(Path.of(base)));
        Optional<Path> currentMain = currentWorking.flatMap(working -> Optional.ofNullable(mainRootOf.get(working))
                .or(() -> mainOf(working)));
        currentMain.ifPresent(main -> {
            if (!byMain.containsKey(main)) {
                List<Worktree> list = worktreesOf(main);
                byMain.put(main, list);
                list.forEach(worktree -> mainRootOf.put(worktree.path(), main));
            }
        });

        List<ManagedAgentSession> sessions = new ArrayList<>();
        Map<ManagedSessionId, Optional<SessionActivity>> activity = new HashMap<>();
        for (ManagedAgentSession session : plugin.stateStore().state().sessions()) {
            sessions.add(session.withWorkingDirectory(RepositoryCatalog.canonicalize(session.workingDirectory())));
            activity.put(session.id(), plugin.activityMonitor().current(session.id()));
        }
        Set<ManagedSessionId> running = new HashSet<>();
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (!project.isDisposed()) {
                running.addAll(AgentExecutionService.getInstance(project).runningSessions());
            }
        }
        return new TaskTreeModel.Inputs(projects, sessions, byMain, mainRootOf, currentMain, currentWorking,
                activity, running, missing, filter);
    }

    /** {@code root}'s worktrees, canonical paths; empty when git cannot list them. */
    private List<Worktree> worktreesOf(Path root) {
        return worktreeCache.computeIfAbsent(root, key -> {
            try {
                return worktrees.list(key).get(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).stream()
                        .map(worktree -> new Worktree(RepositoryCatalog.canonicalize(worktree.path()),
                                worktree.branch(), worktree.mainCheckout(), worktree.detached(), worktree.prunable(),
                                worktree.locked(), worktree.lockReason(), worktree.merged()))
                        .toList();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                LOG.log(Level.DEBUG, () -> "No worktrees for " + key + ": " + e);
                return List.of();
            }
        });
    }

    private Optional<Path> mainOf(Path directory) {
        return worktreesOf(directory).stream().filter(Worktree::mainCheckout).map(Worktree::path).findFirst();
    }

    @Override
    public void close() {
        worktrees.close();
    }
}
