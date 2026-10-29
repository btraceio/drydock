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
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.JiraIssueListing;
import app.drydock.git.JiraService;
import app.drydock.git.WorktreeService;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.agent.HostedLifecycle;
import app.drydock.intellij.project.ProjectIdentity;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import app.drydock.projects.SharedProject;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import java.util.function.Function;

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
    // The tree waits longer than the client's own bound (connect + request
    // plus slack): the client timeout turns a slow site into a Failed
    // listing. This outer bound is ONE deadline shared by every group of a
    // gather, so a hung future costs one budget, never one per group.
    private static final long JIRA_TIMEOUT_SECONDS =
            JiraService.CONNECT_TIMEOUT.plus(JiraService.REQUEST_TIMEOUT).plusSeconds(5).toSeconds();
    private static final long ISSUE_CACHE_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final WorktreeService worktrees = new WorktreeService();
    private final GhCliService github = new GhCliService();
    private final JiraIssueSource jira = new JiraIssueSource();
    private final GitExecutableLocator gitLocator = new GitExecutableLocator();
    private final Map<Path, List<Worktree>> worktreeCache = new ConcurrentHashMap<>();
    private final Map<Path, Instant> activityCache = new ConcurrentHashMap<>();
    private final Map<Path, Boolean> remoteCache = new ConcurrentHashMap<>();
    private final Map<IssueKey, CachedIssues> issueCache = new ConcurrentHashMap<>();
    private final Map<PullRequestKey, CachedPullRequests> pullRequestCache = new ConcurrentHashMap<>();
    private final ResumabilityCache resumability = new ResumabilityCache();

    /** One Jira group listing request: a group id is unique per project, and the map spans projects. */
    private record JiraRequestKey(Path root, String groupId) { }

    private record IssueKey(Path root, Optional<String> repository) { }

    private record PullRequestKey(Path root, Optional<String> repository) { }

    private record CachedIssues(long expiresAt, CompletableFuture<IssueListing> listing) { }

    private record CachedPullRequests(long expiresAt, CompletableFuture<PullRequestListing> listing) { }

    /** One {@code git remote get-url origin} run in {@code root}; throws when git gave no answer. */
    @FunctionalInterface
    interface OriginProbe {
        ProcessResult getUrl(Path git, Path root) throws IOException, InterruptedException;
    }

    void invalidateWorktrees() {
        worktreeCache.clear();
        activityCache.clear();
        resumability.clear();
    }

    void invalidateGitHub() {
        issueCache.clear();
        pullRequestCache.clear();
        remoteCache.clear();
    }

    /** Forgets every cached Jira listing, so the next gather re-queries each project. */
    void invalidateJira() {
        jira.invalidate();
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
     * Whether {@code root}'s GitHub listing is fetched on the next gather:
     * only when the user opened the project's GitHub node here — the view
     * never asks for issues on its own (issues are strictly lazy, unlike
     * pull requests). Pure.
     */
    static boolean githubIssuesWanted(Set<Path> requested, Path root) {
        return requested.contains(root);
    }

    /**
     * Whether {@code root}'s GitHub issue listing is requested by the next gather:
     * the opened-node rule of {@link #githubIssuesWanted(Set, Path)}, with the search
     * box's explicit {@code /<glob>/} project scope applied first — the same rule as
     * {@link #pullRequestsWanted(TaskTreeModel.ViewFilter, Set, Path, TaskTreeModel.ProjectScope, String)}.
     * A scoped-out project's row is not shown at all, so fetching its listing would
     * be pure waste. Ordinary unscoped text is not a scope. Pure.
     */
    static boolean githubIssuesWanted(Set<Path> requested, Path root,
                                      TaskTreeModel.ProjectScope scope, String displayName) {
        if (scope.scoped() && !scope.matchesProject(displayName)) {
            return false;
        }
        return githubIssuesWanted(requested, root);
    }

    /**
     * Whether one Jira group's listing is fetched on the next gather: only
     * when the user opened that group here, and — as for GitHub issues and
     * pull requests — never for a project the search box's explicit
     * {@code /<glob>/} scope hides. Ordinary unscoped text is not a scope.
     * The group definitions travel regardless; this gates the fetch only. Pure.
     */
    static boolean jiraGroupWanted(Set<ListingExpansionTracker.GroupKey> requested,
                                   ListingExpansionTracker.GroupKey group,
                                   TaskTreeModel.ProjectScope scope, String displayName) {
        if (scope.scoped() && !scope.matchesProject(displayName)) {
            return false;
        }
        return requested.contains(group);
    }

    /**
     * Whether {@code root}'s pull-request listing is fetched on the next
     * gather: the PRs view wants every project's listing, so that view
     * loads without an expansion click; every other view wants only the
     * roots the user opened here. Pure.
     */
    static boolean pullRequestsWanted(TaskTreeModel.ViewFilter view, Set<Path> requested, Path root) {
        return view == TaskTreeModel.ViewFilter.PULL_REQUESTS || requested.contains(root);
    }

    /**
     * Whether {@code root}'s pull-request listing is requested by the next gather:
     * the view/section rule of {@link #pullRequestsWanted}, with the search box's
     * explicit {@code /<glob>/} project scope applied first. A scoped-out project's
     * row is not shown at all, so fetching its listing — or the worktree list the
     * pull-request dedup needs — would be pure waste. Ordinary unscoped text is
     * not a scope: a plain search still spans projects. The one predicate gates
     * the listing request and the dedup worktree read alike. Pure.
     */
    static boolean pullRequestsWanted(TaskTreeModel.ViewFilter view, Set<Path> requested, Path root,
                                      TaskTreeModel.ProjectScope scope, String displayName) {
        if (scope.scoped() && !scope.matchesProject(displayName)) {
            return false;
        }
        return pullRequestsWanted(view, requested, root);
    }

    /** Forgets {@code root}'s cached Jira listing, so the next gather re-queries it. */
    void invalidateJiraIssues(Path root) {
        jira.invalidate(root);
    }

    TaskTreeModel.Inputs gather(Project here, String filter, TaskTreeModel.ViewFilter viewFilter,
                                Set<Path> requestedWorktrees, Set<Path> requestedGitHubIssues,
                                Set<ListingExpansionTracker.GroupKey> requestedJiraGroups,
                                Set<Path> requestedPullRequests) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        List<SharedProject> projects = new ArrayList<>();
        Map<Path, List<Worktree>> byMain = new HashMap<>();
        Map<Path, Path> mainRootOf = new HashMap<>();
        Map<Path, List<JiraGroups.Listing>> jiraGroupListings = new HashMap<>();
        Map<Path, CompletableFuture<IssueListing>> issueRequests = new LinkedHashMap<>();
        Map<JiraRequestKey, CompletableFuture<JiraIssueListing>> jiraRequests = new LinkedHashMap<>();
        Map<Path, List<JiraGroup>> jiraGroupRequests = new LinkedHashMap<>();
        Map<Path, CompletableFuture<PullRequestListing>> pullRequestRequests = new LinkedHashMap<>();
        Set<Path> worktreeContainers = new HashSet<>();
        Set<Path> pullRequestContainers = new HashSet<>();
        Set<Path> githubIssueRoots = new HashSet<>();
        Set<Path> githubGhostRoots = new HashSet<>();
        Set<Path> jiraRoots = new HashSet<>();
        Set<Path> jiraGhostRoots = new HashSet<>();
        Map<Path, List<Worktree>> pullRequestDedupWorktrees = new HashMap<>();
        Set<Path> missing = new HashSet<>();
        TaskTreeModel.ProjectScope scope = TaskTreeModel.parseFilter(filter);
        // Resolved once per pass, not once per project: remote detection reuses it.
        Optional<Path> git = gitLocator.locate();
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
            JiraProjectSettings jiraSettings = JiraProjectSettings.load(project.id());
            // Groups exist only under an enabled connection.
            List<JiraGroup> jiraGroups = jiraSettings.enabled()
                    ? JiraGroups.load(project.id()).groups() : List.of();
            if (githubSettings.issuesEnabled()) {
                // The node's presence is decided from settings alone; only
                // the user's opening the node decides the fetch. An empty
                // repository setting is not unconfigured — gh auto-detects
                // from origin — but no origin to detect from is: that is the
                // ghost, not a broken listing. An explicit /glob/ scope that
                // hides the project skips the fetch, as for pull requests.
                if (githubSettings.repository().isPresent() || githubRemoteDetectable(root, git)) {
                    githubIssueRoots.add(root);
                    if (githubIssuesWanted(requestedGitHubIssues, root, scope, project.displayName())) {
                        issueRequests.put(root, issuesOf(root, githubSettings.repository()));
                    }
                } else {
                    githubGhostRoots.add(root);
                }
            }
            if (jiraSettings.enabled()) {
                jiraRoots.add(root);
                // Each group's listing is its own fetch trigger: only the
                // groups the user opened here are queried, the definitions
                // always travel so the tree can offer edit and delete before
                // any fetch — and, like the other listings, none of a project
                // an explicit /glob/ scope hides.
                for (JiraGroup group : jiraGroups) {
                    if (jiraGroupWanted(requestedJiraGroups,
                            new ListingExpansionTracker.GroupKey(root, group.id()), scope, project.displayName())) {
                        jiraRequests.put(new JiraRequestKey(root, group.id()),
                                jira.issuesOf(root, jiraSettings, group));
                    }
                }
                if (!jiraGroups.isEmpty()) {
                    jiraGroupRequests.put(root, jiraGroups);
                }
            } else {
                // One flag means both "never configured" and "switched off":
                // either way the tree says so and offers the settings dialog.
                jiraGhostRoots.add(root);
            }
            if (githubSettings.pullRequestsEnabled()) {
                pullRequestContainers.add(root);
                // A scoped-out project's row is not shown at all, so fetching
                // its listing — or the worktree list the dedup needs — would
                // be pure waste. Ordinary unscoped text is not a scope: a
                // plain search still spans projects.
                if (pullRequestsWanted(viewFilter, requestedPullRequests, root, scope, project.displayName())) {
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
        // One deadline for every group of every project, computed once here,
        // right before the first Jira wait, never per project or per group.
        long jiraDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(JIRA_TIMEOUT_SECONDS);
        for (Map.Entry<Path, List<JiraGroup>> entry : jiraGroupRequests.entrySet()) {
            Path root = entry.getKey();
            jiraGroupListings.put(root, jiraListings(root, entry.getValue(),
                    group -> jiraRequests.get(new JiraRequestKey(root, group.id())), jiraDeadline));
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
                activity, running, hosts, missing, recordedRoots, worktreeContainers,
                pullRequestContainers, githubIssueRoots, githubGhostRoots, jiraRoots, jiraGhostRoots,
                openFolders, Map.copyOf(activityCache), Instant.now(),
                issues, jiraGroupListings, pullRequests, pullRequestDedupWorktrees, plugin.sharedProjects().problem(),
                filter, viewFilter, resumable);
    }

    /**
     * One group's future against the gather's shared deadline (an absolute
     * {@link System#nanoTime()} value): it waits only for the time left, so N
     * groups together never wait longer than one budget. A future already done
     * answers even past the deadline; one still running becomes a
     * {@code Failed} listing and is left running (never cancelled — the source
     * caches it).
     */
    static JiraIssueListing resolveJira(Path root, CompletableFuture<JiraIssueListing> future,
                                        long deadlineNanos) {
        try {
            return future.get(Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new JiraIssueListing.Failed("The Jira lookup was interrupted");
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.log(Level.DEBUG, () -> "No Jira issues for " + root + ": " + e.getClass().getSimpleName());
            return new JiraIssueListing.Failed("The Jira lookup did not complete");
        }
    }

    /**
     * One project's group listings, in group order: every group yields exactly
     * one listing, so no group is ever dropped or left looking "not requested".
     * A group the user has not opened has no future — its listing stays empty
     * (the tree's "Not loaded" state); one the user opened is waited for
     * against the shared deadline and answers even when it misses it, as a
     * {@code Failed} listing.
     */
    static List<JiraGroups.Listing> jiraListings(Path root, List<JiraGroup> groups,
                                                 Function<JiraGroup, CompletableFuture<JiraIssueListing>> futureOf,
                                                 long deadlineNanos) {
        List<JiraGroups.Listing> listings = new ArrayList<>();
        for (JiraGroup group : groups) {
            CompletableFuture<JiraIssueListing> future = futureOf.apply(group);
            listings.add(new JiraGroups.Listing(group, future == null ? Optional.empty()
                    : Optional.of(resolveJira(root, future, deadlineNanos))));
        }
        return listings;
    }

    /**
     * Whether {@code root} has an {@code origin} remote for {@code gh} to
     * auto-detect the repository from. Presence decides the GitHub node vs
     * its ghost: with any origin, {@code gh} can at least try (and a
     * remote it cannot map fails its fetch into an error row that names
     * the problem); with none, it can never succeed, so the tree shows the
     * ghost instead of a node that could only ever fail. Only git's
     * definitive answer is cached per root (see {@link #remoteDetectable});
     * a timeout, an I/O failure, an interrupt or a missing git executable
     * is inconclusive: it shows the ghost for that pass only and is asked
     * again on the next gather, never pinned for the session. The cache is
     * dropped together with the GitHub caches, so fixing the checkout or
     * the settings is seen on the next pass. {@code git} is resolved once
     * per gather by the caller.
     */
    private boolean githubRemoteDetectable(Path root, Optional<Path> git) {
        return remoteDetectable(remoteCache, root, git, (executable, dir) -> ProcessRunner.run(
                List.of(executable.toString(), "remote", "get-url", "origin"), dir,
                Duration.ofSeconds(GIT_TIMEOUT_SECONDS)));
    }

    /**
     * Whether {@code root} has an {@code origin} remote, caching only git's
     * definitive answer in {@code cache}: exit 0 with a non-blank URL is
     * {@code true}; a non-zero exit ("No such remote", not a repository) or
     * blank output is {@code false}. A timeout, an I/O failure, an interrupt
     * (flag restored) or no git executable answers {@code false} for this
     * pass only and stores nothing, so the next call asks git again. Pure
     * apart from {@code cache} and {@code probe}.
     */
    static boolean remoteDetectable(Map<Path, Boolean> cache, Path root, Optional<Path> git,
                                    OriginProbe probe) {
        if (git.isEmpty()) {
            // Inconclusive (no git to ask), never cached: a git installed later is seen next pass.
            return false;
        }
        Boolean detectable = cache.computeIfAbsent(root, key -> {
            try {
                ProcessResult result = probe.getUrl(git.get(), key);
                return result.exitCode() == 0 && !result.stdout().isBlank();
            } catch (ProcessTimeoutException e) {
                LOG.log(Level.DEBUG, () -> "Origin remote of " + key + " undetermined: timed out; not cached");
                return null;
            } catch (IOException e) {
                LOG.log(Level.DEBUG, () -> "Origin remote of " + key + " undetermined: " + e + "; not cached");
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });
        // null: inconclusive, no mapping recorded — not detectable for this pass only.
        return Boolean.TRUE.equals(detectable);
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
        jira.close();
    }
}
