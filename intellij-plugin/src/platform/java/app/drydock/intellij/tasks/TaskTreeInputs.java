package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.git.GhCliService;
import app.drydock.git.GhCliService.IssueListing;
import app.drydock.git.WorktreeService;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.project.ProjectIdentity;
import app.drydock.projects.SharedProject;

import com.intellij.openapi.project.Project;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
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
    private static final long GITHUB_TIMEOUT_SECONDS = 20;
    private static final long ISSUE_CACHE_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final WorktreeService worktrees = new WorktreeService();
    private final GhCliService github = new GhCliService();
    private final Map<Path, List<Worktree>> worktreeCache = new ConcurrentHashMap<>();
    private final Map<IssueKey, CachedIssues> issueCache = new ConcurrentHashMap<>();

    private record IssueKey(Path root, Optional<String> repository) { }

    private record CachedIssues(long expiresAt, CompletableFuture<IssueListing> listing) { }

    void invalidateWorktrees() {
        worktreeCache.clear();
    }

    void invalidateGitHub() {
        issueCache.clear();
    }

    TaskTreeModel.Inputs gather(Project here, String filter, TaskTreeModel.ViewFilter viewFilter) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        List<SharedProject> projects = new ArrayList<>();
        Map<Path, List<Worktree>> byMain = new HashMap<>();
        Map<Path, Path> mainRootOf = new HashMap<>();
        Map<Path, CompletableFuture<IssueListing>> issueRequests = new LinkedHashMap<>();
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
            GitHubProjectSettings githubSettings = GitHubProjectSettings.load(project.id());
            if (githubSettings.issuesEnabled()) {
                issueRequests.put(root, issuesOf(root, githubSettings.repository()));
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
        // Every live agent, shown in a window or not, and the window that shows it.
        AgentExecutionRegistry executions = AgentExecutionRegistry.getInstance();
        Set<ManagedSessionId> running = new HashSet<>(executions.runningSessions());
        Map<ManagedSessionId, TaskTreeModel.Host> hosts = new HashMap<>();
        for (ManagedSessionId id : running) {
            executions.host(id).filter(window -> !window.isDisposed() && window.getBasePath() != null)
                    .ifPresent(window -> hosts.put(id, new TaskTreeModel.Host(
                            RepositoryCatalog.canonicalize(Path.of(window.getBasePath())),
                            ProjectIdentity.of(window).map(ProjectIdentity::repository).orElseGet(window::getName))));
        }
        Map<RepositoryId, Path> recordedRoots = new HashMap<>();
        for (Repository repository : plugin.stateStore().state().repositories()) {
            if (!repository.isRemote()) {
                recordedRoots.put(repository.id(), RepositoryCatalog.canonicalize(repository.root()));
            }
        }
        Map<Path, IssueListing> issues = new HashMap<>();
        for (Map.Entry<Path, CompletableFuture<IssueListing>> entry : issueRequests.entrySet()) {
            try {
                issues.put(entry.getKey(), entry.getValue().get(GITHUB_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                LOG.log(Level.DEBUG, () -> "No GitHub issues for " + entry.getKey() + ": " + e);
                issues.put(entry.getKey(), new IssueListing.Failed("GitHub issue lookup did not complete"));
            }
        }
        return new TaskTreeModel.Inputs(projects, sessions, byMain, mainRootOf, currentMain, currentWorking,
                activity, running, hosts, missing, recordedRoots,
                issues, plugin.sharedProjects().problem(), filter, viewFilter);
    }

    private CompletableFuture<IssueListing> issuesOf(Path root, Optional<String> repository) {
        IssueKey key = new IssueKey(root, repository);
        long now = System.nanoTime();
        CachedIssues cached = issueCache.get(key);
        if (cached != null && cached.expiresAt() > now) {
            return cached.listing();
        }
        CompletableFuture<IssueListing> listing = github.openIssues(root, repository);
        issueCache.put(key, new CachedIssues(now + ISSUE_CACHE_NANOS, listing));
        return listing;
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
        github.close();
    }
}
