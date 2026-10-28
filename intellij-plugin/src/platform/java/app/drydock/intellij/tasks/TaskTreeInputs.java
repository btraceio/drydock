package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.git.GhCliService;
import app.drydock.git.GhCliService.IssueListing;
import app.drydock.git.GhCliService.PullRequestListing;
import app.drydock.git.WorktreeService;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.agent.HostedLifecycle;
import app.drydock.intellij.project.ProjectIdentity;
import app.drydock.projects.SharedProject;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
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
    private final Map<Path, Instant> activityCache = new ConcurrentHashMap<>();
    private final Map<IssueKey, CachedIssues> issueCache = new ConcurrentHashMap<>();
    private final Map<PullRequestKey, CachedPullRequests> pullRequestCache = new ConcurrentHashMap<>();
    private final ResumabilityCache resumability = new ResumabilityCache();

    private record IssueKey(Path root, Optional<String> repository) { }

    private record PullRequestKey(Path root, Optional<String> repository) { }

    private record CachedIssues(long expiresAt, CompletableFuture<IssueListing> listing) { }

    private record CachedPullRequests(long expiresAt, CompletableFuture<PullRequestListing> listing) { }

    void invalidateWorktrees() {
        worktreeCache.clear();
        activityCache.clear();
        resumability.clear();
    }

    void invalidateGitHub() {
        issueCache.clear();
        pullRequestCache.clear();
    }

    /** Forgets {@code root}'s cached issue listing, so the next gather re-queries it. */
    void invalidateIssues(Path root) {
        issueCache.keySet().removeIf(key -> key.root().equals(root));
    }

    /** Forgets {@code root}'s cached pull-request listing, so the next gather re-queries it. */
    void invalidatePullRequests(Path root) {
        pullRequestCache.keySet().removeIf(key -> key.root().equals(root));
    }

    /**
     * Whether {@code root}'s listing is fetched on the next gather. The
     * view that is the listing ({@code ISSUES} for issues, {@code
     * PULL_REQUESTS} for pull requests) wants every project's listing, so
     * those views load without an expansion click; every other view wants
     * only the roots the user opened here. Pure.
     */
    static boolean listingWanted(TaskTreeModel.ViewFilter view, TaskTreeModel.ViewFilter listingView,
                                 Set<Path> requested, Path root) {
        return view == listingView || requested.contains(root);
    }

    /**
     * Whether {@code root}'s GitHub listing is requested by the next gather: the
     * view/section rules of {@link #listingWanted}, with the search box's explicit
     * {@code /<glob>/} project scope applied first. A scoped-out project's row
     * is not shown at all, so fetching its listing — or the worktree list the
     * pull-request dedup needs — would be pure waste. Ordinary unscoped text is
     * not a scope: a plain search still spans projects. The one predicate gates
     * the listing request and the dedup worktree read alike. Pure.
     */
    static boolean githubListingWanted(TaskTreeModel.ViewFilter view, TaskTreeModel.ViewFilter listingView,
                                       Set<Path> requested, Path root, TaskTreeModel.ProjectScope scope,
                                       String displayName) {
        if (scope.scoped() && !scope.matchesProject(displayName)) {
            return false;
        }
        return listingWanted(view, listingView, requested, root);
    }

    TaskTreeModel.Inputs gather(Project here, String filter, TaskTreeModel.ViewFilter viewFilter,
                                Set<Path> requestedWorktrees, Set<Path> requestedIssues,
                                Set<Path> requestedPullRequests) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        List<SharedProject> projects = new ArrayList<>();
        Map<Path, List<Worktree>> byMain = new HashMap<>();
        Map<Path, Path> mainRootOf = new HashMap<>();
        Map<Path, CompletableFuture<IssueListing>> issueRequests = new LinkedHashMap<>();
        Map<Path, CompletableFuture<PullRequestListing>> pullRequestRequests = new LinkedHashMap<>();
        Set<Path> worktreeContainers = new HashSet<>();
        Set<Path> issueContainers = new HashSet<>();
        Set<Path> pullRequestContainers = new HashSet<>();
        Map<Path, List<Worktree>> pullRequestDedupWorktrees = new HashMap<>();
        Set<Path> missing = new HashSet<>();
        TaskTreeModel.ProjectScope scope = TaskTreeModel.parseFilter(filter);
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
            mainRootOf.put(root, root);
            worktreeContainers.add(root);
            GitHubProjectSettings githubSettings = GitHubProjectSettings.load(project.id());
            if (githubSettings.issuesEnabled()) {
                issueContainers.add(root);
                if (githubListingWanted(viewFilter, TaskTreeModel.ViewFilter.ISSUES, requestedIssues, root, scope,
                        project.displayName())) {
                    issueRequests.put(root, issuesOf(root, githubSettings.repository()));
                }
            }
            if (githubSettings.pullRequestsEnabled()) {
                pullRequestContainers.add(root);
                if (githubListingWanted(viewFilter, TaskTreeModel.ViewFilter.PULL_REQUESTS, requestedPullRequests,
                        root, scope, project.displayName())) {
                    pullRequestRequests.put(root, pullRequestsOf(root, githubSettings.repository()));
                    // Dedup needs the worktree list even when the user has never
                    // opened the Worktrees section (byMain is filled lazily), but
                    // only once pull requests themselves have been requested.
                    if (!byMain.containsKey(root)) {
                        pullRequestDedupWorktrees.put(root, worktreesOf(root));
                    }
                }
            }
            if (requestedWorktrees.contains(root)) {
                List<Worktree> list = worktreesOf(root);
                byMain.put(root, list);
                list.forEach(worktree -> mainRootOf.put(worktree.path(), root));
            }
        }

        Optional<Path> currentWorking = Optional.ofNullable(here.getBasePath())
                .map(base -> RepositoryCatalog.canonicalize(Path.of(base)));

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
        Set<Path> openFolders = new HashSet<>();
        for (Project open : ProjectManager.getInstance().getOpenProjects()) {
            if (!open.isDisposed() && open.getBasePath() != null) {
                openFolders.add(RepositoryCatalog.canonicalize(Path.of(open.getBasePath())));
            }
        }
        // A running agent whose worktree folder is gone is marked, never
        // stopped unasked.
        for (ManagedAgentSession session : sessions) {
            if (running.contains(session.id())
                    && HostedLifecycle.folderGone(true, Files.isDirectory(session.workingDirectory()))) {
                missing.add(session.workingDirectory());
            }
        }
        for (ManagedSessionId id : running) {
            executions.host(id).filter(window -> !window.isDisposed() && window.getBasePath() != null)
                    .ifPresent(window -> hosts.put(id, new TaskTreeModel.Host(
                            RepositoryCatalog.canonicalize(Path.of(window.getBasePath())),
                            ProjectIdentity.of(window).map(ProjectIdentity::repository).orElseGet(window::getName))));
        }
        // Which stopped sessions still have a conversation to resume into —
        // a blocking provider probe, cached (and pruned to the stopped
        // catalog) by ResumabilityCache so a gather does not re-ask for it.
        Map<ManagedSessionId, Boolean> resumable =
                resumability.refresh(sessions, running, AgentConsoles::hasConversation);
        Map<RepositoryId, Path> recordedRoots = new HashMap<>();
        for (Repository repository : plugin.stateStore().state().repositories()) {
            if (!repository.isRemote()) {
                recordedRoots.put(repository.id(), RepositoryCatalog.canonicalize(repository.root()));
            }
        }
        Optional<Path> currentMain = currentWorking.flatMap(working -> {
            Path known = mainRootOf.get(working);
            if (known != null) {
                return Optional.of(known);
            }
            return sessions.stream()
                    .filter(session -> session.workingDirectory().equals(working))
                    .map(session -> recordedRoots.get(session.repositoryId()))
                    .filter(Objects::nonNull)
                    .findFirst()
                    // An unregistered main checkout is its own project. This
                    // keeps the Add row without enumerating any worktrees.
                    .or(() -> Optional.of(working));
        });
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
        Map<Path, PullRequestListing> pullRequests = new HashMap<>();
        for (Map.Entry<Path, CompletableFuture<PullRequestListing>> entry : pullRequestRequests.entrySet()) {
            try {
                pullRequests.put(entry.getKey(), entry.getValue().get(GITHUB_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                LOG.log(Level.DEBUG, () -> "No GitHub pull requests for " + entry.getKey() + ": " + e);
                pullRequests.put(entry.getKey(),
                        new PullRequestListing.Failed("GitHub pull-request lookup did not complete"));
            }
        }
        return new TaskTreeModel.Inputs(projects, sessions, byMain, mainRootOf, currentMain, currentWorking,
                activity, running, hosts, missing, recordedRoots, worktreeContainers, issueContainers,
                pullRequestContainers, openFolders, Map.copyOf(activityCache), Instant.now(),
                issues, pullRequests, pullRequestDedupWorktrees, plugin.sharedProjects().problem(), filter, viewFilter,
                resumable);
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

    private CompletableFuture<PullRequestListing> pullRequestsOf(Path root, Optional<String> repository) {
        PullRequestKey key = new PullRequestKey(root, repository);
        long now = System.nanoTime();
        CachedPullRequests cached = pullRequestCache.get(key);
        if (cached != null && cached.expiresAt() > now && !failed(cached.listing())) {
            return cached.listing();
        }
        CompletableFuture<PullRequestListing> listing = github.openPullRequests(root, repository);
        pullRequestCache.put(key, new CachedPullRequests(now + ISSUE_CACHE_NANOS, listing));
        return listing;
    }

    /**
     * A failed listing is never served from the cache: the tree rebuilds on
     * every session or activity event, so a transient {@code gh} failure is
     * retried on the next pass instead of pinning the error for a minute and
     * leaving the section a dead "GitHub pull requests: …" row.
     */
    private static boolean failed(CompletableFuture<PullRequestListing> listing) {
        // openPullRequestsBlocking never completes exceptionally (every
        // failure becomes a Failed listing), so join on a done future is safe.
        return listing.isDone() && listing.join() instanceof PullRequestListing.Failed;
    }

    /** {@code root}'s worktrees, canonical paths; empty when git cannot list them. */
    private List<Worktree> worktreesOf(Path root) {
        return worktreeCache.computeIfAbsent(root, key -> {
            try {
                List<Worktree> listed = worktrees.list(key).get(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).stream()
                        .map(worktree -> new Worktree(RepositoryCatalog.canonicalize(worktree.path()),
                                worktree.branch(), worktree.mainCheckout(), worktree.detached(), worktree.prunable(),
                                worktree.locked(), worktree.lockReason(), worktree.merged()))
                        .toList();
                for (Worktree worktree : listed) {
                    if (worktree.mainCheckout() || worktree.prunable()) {
                        continue;
                    }
                    try {
                        activityCache.put(worktree.path(), worktrees.lastActivity(worktree.path())
                                .get(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (ExecutionException | TimeoutException | RuntimeException e) {
                        LOG.log(Level.DEBUG, () -> "No activity time for " + worktree.path() + ": " + e);
                    }
                }
                return listed;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                LOG.log(Level.DEBUG, () -> "No worktrees for " + key + ": " + e);
                return List.of();
            }
        });
    }

    @Override
    public void close() {
        worktrees.close();
        github.close();
    }
}
