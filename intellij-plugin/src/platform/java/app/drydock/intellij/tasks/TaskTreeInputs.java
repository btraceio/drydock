package app.drydock.intellij.tasks;

import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.delayedtasks.TaskStoreWatcher;
import app.drydock.delayedtasks.TaskStoreWatcher.WatchedSnapshot;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.git.GhCliService;
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.GhCliService.IssueListing;
import app.drydock.git.GhCliService.PullRequestListing;
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.JiraIssueListing;
import app.drydock.git.JiraService;
import app.drydock.git.PrCheckoutService;
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

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.messages.MessageBusConnection;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Collects what {@link TaskTreeModel} needs: the shared projects, the
 * plugin's sessions, each project's worktrees, activity and liveness, with
 * every path made canonical. Blocking (git, the disk): call on the
 * plugin's executor, never the EDT.
 *
 * <p>Worktree lists are cached per main checkout for one minute and cleared
 * by {@link #invalidateWorktrees()}: activity changes arrive every second or
 * so and must not each cost one {@code git worktree list} per project, while
 * a branch switch in a session worktree must surface on the badge within a
 * bounded time. A listing git cannot produce is never cached: the pass
 * answers empty and the next gather re-lists. The Worktrees-section listing
 * ({@code byMain} / {@code requestedWorktrees}) is served from the same
 * cache, so an opened section may show up to one minute of staleness — the
 * bound the TTL accepts.</p>
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
    // Deliberately mirrors but does NOT share ISSUE_CACHE_NANOS: the two
    // caches must stay free to diverge.
    private static final long WORKTREE_CACHE_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final WorktreeService worktrees = new WorktreeService();
    /** Runs the session→PR badge join off the gather's thread; owned and shut down by close(). */
    private final ExecutorService badgeJoinExecutor =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("drydock-badge-join-", 0).factory());
    /** One badge join at a time; a request arriving mid-join is dropped (the join's completion rebuilds). */
    private final AtomicBoolean badgeJoinInFlight = new AtomicBoolean();
    /** The last completed badge join's answer, served by gathers while the next join runs. */
    private volatile Map<ManagedSessionId, BranchPullRequest> publishedSessionPullRequests = Map.of();
    private final GhCliService github = new GhCliService();
    private final JiraIssueSource jira = new JiraIssueSource();
    private final GitExecutableLocator gitLocator = new GitExecutableLocator();
    private final Map<Path, CachedWorktrees> worktreeCache = new ConcurrentHashMap<>();
    /** Last-activity stamps, computed only for the Worktrees section, with the listing's TTL cadence. */
    private final Map<Path, CachedActivity> activityCache = new ConcurrentHashMap<>();
    private final Map<Path, Boolean> remoteCache = new ConcurrentHashMap<>();
    private final Map<IssueKey, CachedIssues> issueCache = new ConcurrentHashMap<>();
    private final Map<PullRequestKey, CachedPullRequests> pullRequestCache = new ConcurrentHashMap<>();
    private final Map<PullRequestKey, CachedBranchPullRequests> branchPullRequestCache = new ConcurrentHashMap<>();
    /** Runs when a badge lookup this gather did not wait for completes; the panel rebuilds. */
    private volatile Runnable lateData = () -> { };
    private final ResumabilityCache resumability = new ResumabilityCache();
    /** Task-change notifications (T17a); production observes the VFS, fakes inject. */
    private final TaskChanges taskChanges;
    /** The task snapshot the last gather read (T17a); empty before any publication. */
    private volatile Optional<WatchedSnapshot> watchedTasks = Optional.empty();
    /** The task-change unsubscribe (T17a); subscribe and close both run on the EDT. */
    private volatile Runnable unsubscribeTasks = () -> { };
    private volatile boolean closedInputs;

    /**
     * Task-change notifications (T17a): production observes the platform's
     * VFS events under the task store root (P5's "VFS listener on the
     * store") -- the same events the T13a watcher debounces into a fresh
     * scan and a snapshot publication, so file changes (IDE saves and,
     * after any refresh, external edits) rebuild the tree; fakes deliver
     * publications directly, which is the seam the artifact smoke drives.
     */
    @FunctionalInterface
    interface TaskChanges {

        /** Subscribes to task changes; answers the unsubscribe, which never throws. */
        Runnable subscribe(Runnable onChange);
    }

    /** The production inputs: the store's own VFS change stream. */
    TaskTreeInputs() {
        this(TaskTreeInputs::subscribeStoreChanges);
    }

    /** Inputs whose task changes come from {@code taskChanges} (tests and sandboxes). */
    TaskTreeInputs(TaskChanges taskChanges) {
        this.taskChanges = Objects.requireNonNull(taskChanges, "taskChanges");
    }


    /** One Jira group listing request: a group id is unique per project, and the map spans projects. */
    private record JiraRequestKey(Path root, String groupId) { }

    private record IssueKey(Path root, Optional<String> repository) { }

    private record PullRequestKey(Path root, Optional<String> repository) { }

    private record CachedIssues(long expiresAt, CompletableFuture<IssueListing> listing) { }

    private record CachedPullRequests(long expiresAt, CompletableFuture<PullRequestListing> listing) { }

    /**
     * One project's head-branch PR map: the current lookup and the last
     * answer, which is served while a refresh after the TTL is in flight so
     * the badges do not blink off once a minute.
     */
    private record CachedBranchPullRequests(long expiresAt, CompletableFuture<Map<String, BranchPullRequest>> lookup,
                                            Map<String, BranchPullRequest> previous) { }

    /**
     * One root's worktree listing with its expiry stamp: re-listed after the
     * TTL so a branch switch surfaces without {@link #invalidateWorktrees()}. 
     * Seam surface (package-private): the static cache seam's parameter and
     * the same-package tests construct it.
     */
    record CachedWorktrees(long expiresAt, List<Worktree> listed) { }

    /** One worktree's last-activity stamp with its expiry: recomputed after the TTL, never eagerly. */
    record CachedActivity(long expiresAt, Instant activity) { }

    /** One {@code git remote get-url origin} run in {@code root}; throws when git gave no answer. */
    @FunctionalInterface
    interface OriginProbe {
        ProcessResult getUrl(Path git, Path root) throws IOException, InterruptedException;
    }

    /**
     * Lists {@code root}'s worktrees, canonical paths; throws when git
     * cannot answer (never returns a placeholder). The throws list below
     * IS the contract the static's catch clauses mirror one-for-one:
     * adding a throws-clause member without a matching catch in the
     * compute lambda fails to compile and fails review.
     */
    @FunctionalInterface
    interface WorktreeLister {
        List<Worktree> list(Path root) throws ExecutionException,
                TimeoutException, InterruptedException;
    }

    void invalidateWorktrees() {
        worktreeCache.clear();
        activityCache.clear();
        resumability.clear();
        publishedSessionPullRequests = Map.of();
    }

    void invalidateGitHub() {
        issueCache.clear();
        pullRequestCache.clear();
        branchPullRequestCache.clear();
        remoteCache.clear();
    }

    /**
     * Sets what runs when a session's pull-request lookup, which a gather
     * never waits for, completes later: the panel's rebuild request.
     */
    void onLateData(Runnable callback) {
        lateData = Objects.requireNonNull(callback, "callback");
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
        branchPullRequestCache.keySet().removeIf(key -> key.root().equals(root));
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

    /** Forgets {@code root}'s cached Jira listing, so the next gather re-queries it. */
    void invalidateJiraIssues(Path root) {
        jira.invalidate(root);
    }

    TaskTreeModel.Inputs gather(Project here, String filter,
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
        Map<Path, BadgeSource> badgeSources = new HashMap<>();
        Set<Path> missing = new HashSet<>();
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
                // ghost, not a broken listing.
                if (githubSettings.repository().isPresent() || githubRemoteDetectable(root, git)) {
                    githubIssueRoots.add(root);
                    if (githubIssuesWanted(requestedGitHubIssues, root)) {
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
                // any fetch.
                for (JiraGroup group : jiraGroups) {
                    if (requestedJiraGroups.contains(new ListingExpansionTracker.GroupKey(root, group.id()))) {
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
                // Keyed by the main-checkout path, not the project id: a
                // session's repositoryId is a state-store id that shares no
                // UUID space with the shared-projects file's ids, so an id
                // join matches nothing (the badge-everywhere-missing bug).
                badgeSources.put(root, new BadgeSource(root, githubSettings.repository()));
                // Only once the user opened the project's pull requests here.
                if (requestedPullRequests.contains(root)) {
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
                fillActivity(list);
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
        Map<ManagedSessionId, BranchPullRequest> sessionPullRequests = publishedSessionPullRequests;
        // The badge join itself runs off this pass: per-session worktree
        // listings here made the initial gather cost one "git worktree
        // list" plus an activity probe per listed worktree, per session
        // (measured: ~69 s / ~1000 git spawns for 32 sessions). The join's
        // completion publishes its map and requests one more rebuild
        // through onLateData, exactly like the PR badge lookups it joins
        // with.
        scheduleSessionPullRequestJoin(sessions, badgeSources);
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
        // T17a: the cached task snapshot, read here in this same background
        // pass (a volatile read of the service's last publication, never a
        // fresh scan and never the render thread); a missing one degrades
        // the tree to session-only input.
        watchedTasks = cachedTasks(plugin.taskService());
        return new TaskTreeModel.Inputs(projects, sessions, byMain, mainRootOf, currentMain, currentWorking,
                activity, running, hosts, missing, recordedRoots, worktreeContainers,
                pullRequestContainers, githubIssueRoots, githubGhostRoots, jiraRoots, jiraGhostRoots,
                openFolders, freshActivity(), Instant.now(),
                issues, jiraGroupListings, pullRequests, pullRequestDedupWorktrees, plugin.sharedProjects().problem(),
                filter, resumable, sessionPullRequests);
    }

    /** Where a project's pull-request badges come from: its main checkout and its configured repository. */
    record BadgeSource(Path root, Optional<String> repository) { }

    /**
     * Schedules the session-to-badge join off the gather's thread.
     *
     * <p>Each session's pull request is matched by its current branch against
     * the head branches of its project's PRs. A session joins its project
     * through paths, never through ids: the live worktree list of its
     * working directory names the main checkout, whose canonical path is
     * the badge source's key (a session's {@code repositoryId} is a
     * state-store id that shares no UUID space with the shared-projects
     * file's ids, so an id join matches nothing). Only projects that list
     * pull requests take part, and only sessions whose folder still exists.
     * Never waits on {@code gh}: a lookup still running yields the last
     * answer (none on the very first pass), and its completion requests
     * one more rebuild through {@link #onLateData}.</p>
     *
     * <p>The join never runs on the gather's thread: it costs one
     * {@code git worktree list} per session, which on the initial pass made
     * the first tree block for minutes. The gather serves the last published
     * map instead; this pass's answer arrives through the rebuild the
     * completion requests, one gather later — the same late-arrival shape
     * the PR badge lookups already have.</p>
     *
     * <p>The completion publishes and requests a rebuild only when the
     * answer changed: an unchanged map (the steady state — the same empty
     * map most passes) must not schedule the next join, or every join
     * completion would loop gathers forever.</p>
     */
    private void scheduleSessionPullRequestJoin(List<ManagedAgentSession> sessions,
                                                Map<Path, BadgeSource> sources) {
        if (sources.isEmpty() || sessions.isEmpty()) {
            return;
        }
        if (!badgeJoinInFlight.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture
                .supplyAsync(() -> sessionPullRequests(sessions, sources, this::worktreesOf,
                                source -> branchPullRequestsNow(source.root(), source.repository())),
                        badgeJoinExecutor)
                .thenAccept(matched -> {
                    if (!publishedSessionPullRequests.equals(matched)) {
                        publishedSessionPullRequests = Map.copyOf(matched);
                        lateData.run();
                    }
                })
                .whenComplete((ignored, failure) -> {
                    badgeJoinInFlight.set(false);
                    if (failure != null) {
                        // The published map stands; the next gather's schedule call retries the join.
                        LOG.log(Level.WARNING, "The session pull-request badge join failed", failure);
                    }
                });
    }

    /**
     * The session-to-badge join as a pure seam: the same join the instance
     * method runs, with the two blocking edges injected — the per-session
     * live worktree listing ({@code listingsOf}) and the project's head-branch
     * PR map ({@code prsOf}). Pure apart from the directory-existence check
     * (a disk read, kept here so the gate stays tested with the join). The
     * source is found by the listing's main-checkout path, never by
     * {@code session.repositoryId()}: the badge-source map is keyed by paths
     * only, so an id-join revert is a compile error, and the tests below pin
     * the behavioral regression. The branch comes from the session's own
     * deepest worktree entry, so the main checkout's branch cannot shadow it.
     */
    static Map<ManagedSessionId, BranchPullRequest> sessionPullRequests(
            List<ManagedAgentSession> sessions, Map<Path, BadgeSource> sources,
            Function<Path, List<Worktree>> listingsOf,
            Function<BadgeSource, Map<String, BranchPullRequest>> prsOf) {
        if (sources.isEmpty()) {
            return Map.of();
        }
        Map<Path, Map<String, BranchPullRequest>> byRoot = new HashMap<>();
        Map<ManagedSessionId, BranchPullRequest> matched = new HashMap<>();
        for (ManagedAgentSession session : sessions) {
            if (!Files.isDirectory(session.workingDirectory())) {
                continue;
            }
            List<Worktree> listed = listingsOf.apply(session.workingDirectory());
            Optional<BadgeSource> source = listed.stream()
                    .filter(Worktree::mainCheckout)
                    .map(Worktree::path)
                    .findFirst()
                    .map(sources::get);
            if (source.isEmpty()) {
                continue;
            }
            Optional<String> branch = branchOf(listed, session.workingDirectory());
            if (branch.isEmpty()) {
                continue;
            }
            Map<String, BranchPullRequest> pullRequests = byRoot.computeIfAbsent(source.get().root(),
                    ignored -> prsOf.apply(source.get()));
            sessionPullRequest(pullRequests, branch.get(), session.pr().number())
                    .ifPresent(pullRequest -> matched.put(session.id(), pullRequest));
        }
        return matched;
    }

    /**
     * Pure. A session's pull request among its project's: the one whose head
     * is the session's branch, else the one with the session's own PR number
     * -- the number its stored PR link carries, or the one a {@code pr-<n>}
     * PR checkout's branch encodes, since a checkout's local branch is not
     * the PR's head branch.
     */
    static Optional<BranchPullRequest> sessionPullRequest(Map<String, BranchPullRequest> byHeadBranch,
                                                          String branch, Optional<Integer> linkedNumber) {
        BranchPullRequest byBranch = byHeadBranch.get(branch);
        if (byBranch != null) {
            return Optional.of(byBranch);
        }
        Optional<Integer> number = linkedNumber.or(() -> PrCheckoutService.pullRequestNumberOf(branch));
        return number.flatMap(wanted -> byHeadBranch.values().stream()
                .filter(pullRequest -> pullRequest.number() == wanted)
                .findFirst());
    }

    /**
     * The branch of the deepest listed worktree whose root contains
     * {@code workingDirectory} — the same listing that resolved the main
     * checkout, so no extra git run. Component-wise {@link Path#startsWith},
     * so an equal path still matches and a session started in a subdirectory
     * of a worktree resolves to that worktree's branch (the deepest entry
     * wins, so a linked worktree's own branch is not shadowed by the main
     * checkout's). A detached or prunable entry has no branch, and a
     * directory git does not list at all reads as none.
     */
    static Optional<String> branchOf(List<Worktree> listed, Path workingDirectory) {
        return listed.stream()
                .filter(worktree -> workingDirectory.startsWith(worktree.path()))
                .max(Comparator.comparingInt(worktree -> worktree.path().getNameCount()))
                .flatMap(Worktree::branch);
    }

    /**
     * The head-branch PR map for {@code root} as far as it is known now.
     * A missing or expired entry starts a lookup whose completion calls
     * {@link #lateData}; until it completes the previous answer is served.
     */
    private Map<String, BranchPullRequest> branchPullRequestsNow(Path root, Optional<String> repository) {
        PullRequestKey key = new PullRequestKey(root, repository);
        long now = System.nanoTime();
        CachedBranchPullRequests cached = branchPullRequestCache.get(key);
        if (cached == null || cached.expiresAt() <= now) {
            Map<String, BranchPullRequest> previous = cached == null ? Map.of() : current(cached);
            CompletableFuture<Map<String, BranchPullRequest>> lookup = github.branchPullRequests(root, repository);
            cached = new CachedBranchPullRequests(now + ISSUE_CACHE_NANOS, lookup, previous);
            branchPullRequestCache.put(key, cached);
            if (!lookup.isDone()) {
                lookup.whenComplete((answer, failure) -> lateData.run());
            }
        }
        return current(cached);
    }

    /** The finished lookup's answer, or the previous one while it runs (or if it failed). */
    private static Map<String, BranchPullRequest> current(CachedBranchPullRequests cached) {
        CompletableFuture<Map<String, BranchPullRequest>> lookup = cached.lookup();
        if (lookup.isDone() && !lookup.isCompletedExceptionally()) {
            return lookup.join();
        }
        return cached.previous();
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

    /**
     * {@code root}'s worktrees, canonical paths; empty when git cannot list
     * them (for this pass only — a failed listing is never cached). Never
     * computes activity stamps: those belong to the Worktrees section's
     * display pass alone (see {@link #fillActivity}), never to a listing a
     * badge join or a PR dedup happens to trigger.
     */
    private List<Worktree> worktreesOf(Path root) {
        return worktreesOf(worktreeCache, root, key ->
                worktrees.list(key).get(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).stream()
                        .map(worktree -> new Worktree(RepositoryCatalog.canonicalize(worktree.path()),
                                worktree.branch(), worktree.mainCheckout(), worktree.detached(), worktree.prunable(),
                                worktree.locked(), worktree.lockReason(), worktree.merged()))
                        .toList());
    }

    /**
     * Stamps the listed worktrees' last activity into {@code activityCache}
     * — Worktrees-section display data, recomputed with the listing's TTL
     * cadence and never by a badge join or a PR-dedup listing. One blocking
     * probe per worktree that has no fresh stamp; sequential, as the
     * section listing itself is.
     */
    private void fillActivity(List<Worktree> listed) {
        long now = System.nanoTime();
        for (Worktree worktree : listed) {
            if (worktree.mainCheckout() || worktree.prunable()) {
                continue;
            }
            CachedActivity cached = activityCache.get(worktree.path());
            if (cached != null && cached.expiresAt() > now) {
                continue;
            }
            try {
                activityCache.put(worktree.path(),
                        new CachedActivity(now + WORKTREE_CACHE_NANOS,
                                worktrees.lastActivity(worktree.path())
                                        .get(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                LOG.log(Level.DEBUG, () -> "No activity time for " + worktree.path() + ": " + e);
            }
        }
    }

    /** The fresh activity stamps, for the model's staleness display. */
    private Map<Path, Instant> freshActivity() {
        long now = System.nanoTime();
        Map<Path, Instant> fresh = new HashMap<>();
        activityCache.forEach((path, cached) -> {
            if (cached.expiresAt() > now) {
                fresh.put(path, cached.activity());
            }
        });
        return Map.copyOf(fresh);
    }

    /**
     * {@code root}'s worktrees through {@code cache}, one listing per root
     * for one minute, cleared wholesale by {@link #invalidateWorktrees()}: a
     * branch switch in a session worktree must surface on the badge within
     * a bounded time, not wait for an explicit invalidation. A listing git
     * cannot produce is never cached — the pass answers empty and the next
     * gather re-lists, so a transient git failure is retried instead of
     * pinning an empty worktree list; a legitimately empty listing IS cached
     * under the TTL (the distinction is made only inside the compute
     * lambda). Pure apart from {@code cache} and {@code lister}.
     */
    static List<Worktree> worktreesOf(Map<Path, CachedWorktrees> cache, Path root, WorktreeLister lister) {
        CachedWorktrees cached = cache.get(root);
        if (cached != null && cached.expiresAt() > System.nanoTime()) {
            return cached.listed();
        }
        CachedWorktrees fresh = cache.compute(root, (key, stale) -> {
            if (stale != null && stale.expiresAt() > System.nanoTime()) {
                // A racing thread refreshed while this one waited for the compute lock.
                return stale;
            }
            try {
                List<Worktree> listed = lister.list(key);
                return new CachedWorktrees(System.nanoTime() + WORKTREE_CACHE_NANOS, listed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.log(Level.DEBUG, () -> "No worktrees for " + key + ": interrupted; not cached");
                return null;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                LOG.log(Level.DEBUG, () -> "No worktrees for " + key + ": " + e);
                return null;
            }
        });
        // null: git gave no answer — inconclusive, no mapping recorded; empty for this pass only.
        return fresh == null ? List.of() : fresh.listed();
    }

    /**
     * The task snapshot of one pass (T17a): the service's last published
     * composition, or empty before its first publication (the
     * session-only input). Pure volatile read -- the pass consumes what
     * the watcher already published, never scans itself.
     */
    static Optional<WatchedSnapshot> cachedTasks(DelayedTaskService service) {
        return Optional.ofNullable(service.watchedSnapshot());
    }

    /** The task snapshot the last gather read (T17a); empty when none was published yet. */
    Optional<WatchedSnapshot> watchedTasks() {
        return watchedTasks;
    }

    /**
     * Subscribes {@code onChange} to task changes (T17a): the panel's single
     * subscription, whose notifications ride the existing coalesced
     * rebuild scheduler -- a burst of store events is one rebuild request
     * stream, never one rebuild per event. {@link #close()} unsubscribes;
     * a subscription made after close undoes itself, so no listener
     * survives the panel. Subscribe and close both run on the EDT (the
     * panel's constructor and its dispose).
     */
    void subscribeToTasks(Runnable onChange) {
        Runnable unsubscribe = taskChanges.subscribe(Objects.requireNonNull(onChange, "onChange"));
        if (closedInputs) {
            unsubscribe.run();
            return;
        }
        unsubscribeTasks = unsubscribe;
    }

    /**
     * Production task changes (T17a, P5): the platform's VFS events under
     * the plugin's task store root -- the input side of the service's
     * snapshot publication. One notification per event batch that touches
     * the store, delivered on the EDT; the unsubscribe disconnects the
     * bus connection. Without a running IDE application there is nothing
     * to observe, and the no-op unsubscribe keeps a headless
     * construction session-only.
     */
    static Runnable subscribeStoreChanges(Runnable onChange) {
        Application application;
        try {
            application = ApplicationManager.getApplication();
        } catch (RuntimeException | LinkageError noIde) {
            application = null;
        }
        if (application == null) {
            return () -> { };
        }
        Path root;
        try {
            root = DrydockPluginService.getInstance().taskService().root();
        } catch (RuntimeException noService) {
            return () -> { };
        }
        MessageBusConnection connection = application.getMessageBus().connect();
        connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent event : events) {
                    if (underRoot(event, root)) {
                        onChange.run();
                        return;
                    }
                }
            }
        });
        return connection::disconnect;
    }

    /** Whether one VFS event names a file under the store root; no disk access. */
    private static boolean underRoot(VFileEvent event, Path root) {
        try {
            VirtualFile file = event.getFile();
            Path path = file != null ? file.toNioPath()
                    : Path.of(VfsUtilCore.urlToPath(event.getPath()));
            return path.toAbsolutePath().normalize().startsWith(root);
        } catch (RuntimeException notLocalOrUnparseable) {
            return false;
        }
    }

    @Override
    public void close() {
        closedInputs = true;
        // T17a: the task subscription goes before the services below, so a
        // publication racing the close cannot rebuild a disposed panel.
        Runnable unsubscribe = unsubscribeTasks;
        unsubscribeTasks = () -> { };
        unsubscribe.run();
        badgeJoinExecutor.shutdown();
        worktrees.close();
        github.close();
        jira.close();
    }
}
