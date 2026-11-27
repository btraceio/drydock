package app.drydock.intellij.tasks;

import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.delayedtasks.TaskStoreWatcher;
import app.drydock.delayedtasks.TaskStoreWatcher.WatchedSnapshot;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.git.BranchCatalog;
import app.drydock.git.BranchListing;
import app.drydock.git.BranchRef;
import app.drydock.git.GhCliService;
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.GhCliService.IssueListing;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.GhCliService.OpenPullRequest;
import app.drydock.git.GhCliService.PrInfo.PrLifecycle;
import app.drydock.git.GhCliService.PullRequestListing;
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.GitStatusService;
import app.drydock.git.JiraIssueListing;
import app.drydock.git.PrCheckoutService;
import app.drydock.git.WorktreeService;
import app.drydock.git.WorktreeService.Checkout;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.agent.HostedLifecycle;
import app.drydock.intellij.project.ProjectIdentity;
import app.drydock.intellij.startup.DrydockStartupActivity;
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
import java.util.function.Supplier;

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
    private static final long ISSUE_CACHE_NANOS = TimeUnit.MINUTES.toNanos(1);
    /** How long a failed pull-request listing is served instead of refetched. */
    private static final long FAILED_LISTING_NANOS = TimeUnit.SECONDS.toNanos(30);
    /**
     * The badge listing's cadence. It is the one fetch that still carries
     * {@code mergeable,statusCheckRollup} -- a query whose cost grows with
     * every check of every listed PR and the consumer that exhausted the
     * 5,000 points/hour GraphQL budget -- so it refreshes on a slower
     * cadence than the light listings it badges: five minutes, not one.
     * The manual Refresh gesture drops this cache along with the listings'
     * ({@link #invalidatePullRequests}), so "show me now" is never more
     * stale than the gesture.
     */
    private static final long BADGE_CACHE_NANOS = TimeUnit.MINUTES.toNanos(5);
    // Deliberately mirrors but does NOT share ISSUE_CACHE_NANOS: the two
    // caches must stay free to diverge.
    private static final long WORKTREE_CACHE_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final WorktreeService worktrees = new WorktreeService();
    /** The branch listing behind the Branches section: one {@code for-each-ref} per project, cached like the worktrees. */
    private final GitStatusService gitStatus = new GitStatusService();
    /** Runs the session→PR badge join off the gather's thread; owned and shut down by close(). */
    private final ExecutorService badgeJoinExecutor =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("drydock-badge-join-", 0).factory());
    /** One badge join at a time; a request arriving mid-join is dropped (the join's completion rebuilds). */
    private final AtomicBoolean badgeJoinInFlight = new AtomicBoolean();
    /** The last completed badge join's answer, served by gathers while the next join runs. */
    private volatile Map<ManagedSessionId, BranchPullRequest> publishedSessionPullRequests = Map.of();
    private final GhCliService github = new GhCliService();
    private final TargetPullRequests targets = new TargetPullRequests(
            target -> github.pullRequest(Path.of(System.getProperty("user.home")),
                    Optional.of(target.repository()), target.number()),
            System::nanoTime, () -> this.lateData.run());
    private final JiraIssueSource jira = new JiraIssueSource();
    private final GitExecutableLocator gitLocator = new GitExecutableLocator();
    private final Map<Path, CachedWorktrees> worktreeCache = new ConcurrentHashMap<>();
    /** Each session directory's checkout, under the worktree lists' TTL and invalidation. */
    private final Map<Path, CachedCheckout> checkoutCache = new ConcurrentHashMap<>();
    /** Each project's merged branch catalog, under the worktree lists' TTL and invalidation. */
    private final Map<Path, CachedBranches> branchCache = new ConcurrentHashMap<>();
    /** Last-activity stamps, computed only for the Worktrees section, with the listing's TTL cadence. */
    private final Map<Path, CachedActivity> activityCache = new ConcurrentHashMap<>();
    private final Map<Path, Boolean> remoteCache = new ConcurrentHashMap<>();
    private final Map<IssueKey, CachedIssues> issueCache = new ConcurrentHashMap<>();
    private final Map<PullRequestKey, CachedPullRequests> pullRequestCache = new ConcurrentHashMap<>();
    private final Map<PrGroupKey, CachedPullRequests> prGroupCache = new ConcurrentHashMap<>();
    private final Map<PullRequestKey, CachedBranchPullRequests> branchPullRequestCache = new ConcurrentHashMap<>();
    /** The badge join's own listing: the light field set, keyed like the heavy cache it decorates from. */
    private final Map<PullRequestKey, CachedBranchPullRequests> branchPullRequestRowCache = new ConcurrentHashMap<>();
    /** Each project's latest search-scoped pull-request fetch, keyed by its main-checkout root. */
    private final Map<Path, CachedSearchPullRequests> searchPullRequestCache = new ConcurrentHashMap<>();
    /** Each project's latest search-scoped issue fetch, keyed by its main-checkout root. */
    private final Map<Path, CachedSearchIssues> searchIssueCache = new ConcurrentHashMap<>();
    /** Exact-number PR lookups keyed by (root, repository, number). */
    private final Map<ExactPullRequestKey, CachedExactPullRequest> exactPullRequestCache = new ConcurrentHashMap<>();
    /** Runs when a badge lookup or listing this gather did not wait for completes; the panel rebuilds. */
    private volatile Runnable lateData = () -> { };
    /** What each GitHub and Jira listing shows now; a gather never waits for one. */
    private final ListingAnswers listingAnswers = new ListingAnswers(() -> lateData.run());
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

    /** A Jira group's shown answer: an edited query never shows the old query's issues. */
    private record JiraAnswerKey(Path root, String groupId, String jql) { }

    private static final IssueListing ISSUES_DID_NOT_COMPLETE =
            new IssueListing.Failed("GitHub issue lookup did not complete");
    private static final PullRequestListing PULL_REQUESTS_DID_NOT_COMPLETE =
            new PullRequestListing.Failed("GitHub pull-request lookup did not complete");
    private static final JiraIssueListing JIRA_DID_NOT_COMPLETE =
            new JiraIssueListing.Failed("The Jira lookup did not complete");

    private record IssueKey(Path root, Optional<String> repository) { }

    private record PullRequestKey(Path root, Optional<String> repository) { }

    private record PrGroupKey(Path root, Optional<String> repository, String filter) { }

    /** A PR subgroup's shown answer: an edited filter never shows the old filter's pull requests. */
    private record PrGroupAnswerKey(Path root, Optional<String> repository, String groupId, String filter) { }

    private record CachedIssues(long expiresAt, CompletableFuture<IssueListing> listing) { }

    private record CachedPullRequests(long expiresAt, CompletableFuture<PullRequestListing> listing) { }

    /**
     * What the search field asks the listings to reach beyond their fetch
     * limit: the server-side queries (empty: nothing to ask) and the
     * {@code pr:} terms that name a PR by number and resolve by exact
     * lookup instead. Built by the panel from the parsed search text;
     * {@link #EMPTY} when no search is active.
     */
    record ListingSearch(String pullRequestQuery, String issueQuery, List<Integer> pullRequestNumbers) {

        static final ListingSearch EMPTY = new ListingSearch("", "", List.of());

        boolean searchesPullRequests() {
            return !pullRequestQuery.isEmpty() || !pullRequestNumbers().isEmpty();
        }

        boolean searchesIssues() {
            return !issueQuery.isEmpty();
        }
    }

    /**
     * One project's latest search-scoped listing: the query it answers, the
     * running fetch, and the last completed answer of the same query,
     * served while a refresh after the TTL is in flight so typing does not
     * make the searched rows blink off. A changed query replaces the entry:
     * only the newest query's rows may merge.
     */
    private record CachedSearchPullRequests(String query, long expiresAt,
                                            CompletableFuture<PullRequestListing> lookup,
                                            PullRequestListing.Listed previous) { }

    private record CachedSearchIssues(String query, long expiresAt,
                                      CompletableFuture<IssueListing> lookup,
                                      IssueListing.Listed previous) { }

    /** One exact-number PR lookup: (root, repository, number) and its cached answer. */
    private record ExactPullRequestKey(Path root, Optional<String> repository, int number) { }

    private record CachedExactPullRequest(long expiresAt,
                                          CompletableFuture<Optional<OpenPullRequest>> lookup,
                                          Optional<OpenPullRequest> previous) { }

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

    /**
     * One root's merged branch catalog with its expiry stamp: re-listed after
     * the worktree lists' TTL so a new or deleted branch surfaces without
     * {@link #invalidateWorktrees()}. Seam surface (package-private): the
     * same-package tests construct it.
     */
    record CachedBranches(long expiresAt, BranchCatalog listed) { }

    private record CachedCheckout(long expiresAt, Checkout checkout) { }

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
        checkoutCache.clear();
        activityCache.clear();
        branchCache.clear();
        resumability.clear();
        publishedSessionPullRequests = Map.of();
    }

    void invalidateGitHub() {
        issueCache.clear();
        pullRequestCache.clear();
        prGroupCache.clear();
        branchPullRequestCache.clear();
        branchPullRequestRowCache.clear();
        searchPullRequestCache.clear();
        searchIssueCache.clear();
        exactPullRequestCache.clear();
        targets.clear();
        remoteCache.clear();
        listingAnswers.forgetAll();
    }

    /**
     * Sets what runs when a session's pull-request lookup or a GitHub or
     * Jira listing, none of which a gather waits for, completes later: the
     * panel's rebuild request.
     */
    void onLateData(Runnable callback) {
        lateData = Objects.requireNonNull(callback, "callback");
    }

    /**
     * Every shown Jira group listing's issue key to its priority name, the
     * badge lookup for a mirrored task's row. Pure memory: the listing
     * answers are the cache the gather already fills, so the fold never
     * fetches. A ticket whose group listing has not loaded yet — its group
     * collapsed, or the ticket matches no group's JQL — is absent, and the
     * row wears no badge until the listing that names it arrives.
     */
    Map<String, Optional<String>> jiraPriorities() {
        Map<String, Optional<String>> byKey = new HashMap<>();
        for (Object answer : listingAnswers.shownListings()) {
            if (answer instanceof JiraIssueListing.Listed listed) {
                listed.issues().forEach(issue -> byKey.put(issue.key(), issue.priority()));
            }
        }
        return byKey;
    }

    /** Forgets every cached Jira listing, so the next gather re-queries each project. */
    void invalidateJira() {
        jira.invalidate();
        listingAnswers.forgetAll();
    }

    /** Forgets {@code root}'s cached issue listing, so the next gather re-queries it. */
    void invalidateIssues(Path root) {
        issueCache.keySet().removeIf(key -> key.root().equals(root));
        searchIssueCache.remove(root);
        listingAnswers.forget(root);
    }

    /** Forgets {@code root}'s cached pull-request listing, so the next gather re-queries it. */
    void invalidatePullRequests(Path root) {
        pullRequestCache.keySet().removeIf(key -> key.root().equals(root));
        prGroupCache.keySet().removeIf(key -> key.root().equals(root));
        branchPullRequestCache.keySet().removeIf(key -> key.root().equals(root));
        branchPullRequestRowCache.keySet().removeIf(key -> key.root().equals(root));
        searchPullRequestCache.remove(root);
        exactPullRequestCache.keySet().removeIf(key -> key.root().equals(root));
        listingAnswers.forget(root);
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
        listingAnswers.forget(root);
    }

    TaskTreeModel.Inputs gather(Project here, String filter,
                                Set<Path> requestedWorktrees, Set<Path> requestedGitHubIssues,
                                Set<ListingExpansionTracker.GroupKey> requestedJiraGroups,
                                Set<Path> requestedPullRequests,
                                Set<Path> requestedAllPullRequests,
                                Set<ListingExpansionTracker.GroupKey> requestedPrGroups,
                                Set<Path> requestedBranches,
                                ListingSearch search) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        List<SharedProject> projects = new ArrayList<>();
        Map<Path, List<Worktree>> byMain = new HashMap<>();
        Map<Path, Path> mainRootOf = new HashMap<>();
        Map<Path, List<JiraGroups.Listing>> jiraGroupListings = new HashMap<>();
        // A gather never waits for a listing (ListingAnswers): a running one
        // shows its last answer, or a loading row, and its completion
        // requests one more rebuild.
        Map<Path, IssueListing> issues = new HashMap<>();
        Set<Path> pendingIssues = new HashSet<>();
        Map<JiraRequestKey, CompletableFuture<JiraIssueListing>> jiraRequests = new LinkedHashMap<>();
        Map<Path, List<JiraGroup>> jiraGroupRequests = new LinkedHashMap<>();
        Set<ListingExpansionTracker.GroupKey> pendingJiraGroups = new HashSet<>();
        Map<Path, PullRequestListing> pullRequests = new HashMap<>();
        Map<Path, List<PrGroups.Listing>> prGroupListings = new HashMap<>();
        Set<Path> pendingAllPullRequests = new HashSet<>();
        Set<ListingExpansionTracker.GroupKey> pendingPrGroups = new HashSet<>();
        Set<Path> worktreeContainers = new HashSet<>();
        Set<Path> pullRequestContainers = new HashSet<>();
        Set<Path> githubIssueRoots = new HashSet<>();
        Set<Path> githubGhostRoots = new HashSet<>();
        Set<Path> jiraRoots = new HashSet<>();
        Set<Path> jiraGhostRoots = new HashSet<>();
        Map<Path, List<Worktree>> pullRequestDedupWorktrees = new HashMap<>();
        Map<Path, BranchCatalog> branches = new HashMap<>();
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
                        listingAnswers.now(root, new IssueKey(root, githubSettings.repository()),
                                        issuesOf(root, githubSettings.repository()), ISSUES_DID_NOT_COMPLETE)
                                .ifPresentOrElse(
                                        listing -> issues.put(root,
                                                searchedIssues(root, githubSettings.repository(), listing, search)),
                                        () -> pendingIssues.add(root));
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
                // Keyed by the main-checkout path, not the project id: a
                // session's repositoryId is a state-store id that shares no
                // UUID space with the shared-projects file's ids, so an id
                // join matches nothing (the badge-everywhere-missing bug).
                badgeSources.put(root, new BadgeSource(root, githubSettings.repository()));
                List<PrGroup> prGroups = PrGroups.load(project.id()).groups();
                if (prGroups.isEmpty()) {
                    pullRequestContainers.add(root);
                    // Only once the user opened the project's pull requests here.
                    if (requestedPullRequests.contains(root)) {
                        // The badge map lookup rides the same fetch the session
                        // badges use: the one heavy listing, on its slower TTL.
                        // A fetch still running serves the previous answer (none
                        // on the first pass) and its completion requests one
                        // more rebuild, when the joined signals appear.
                        Map<String, BranchPullRequest> badges =
                                branchPullRequestsNow(root, githubSettings.repository());
                        listingAnswers.now(root, new PullRequestKey(root, githubSettings.repository()),
                                        pullRequestsOf(root, githubSettings.repository()), PULL_REQUESTS_DID_NOT_COMPLETE)
                                .ifPresent(listing -> pullRequests.put(root, withBadges(
                                        searchedPullRequests(root, githubSettings.repository(), listing, search),
                                        badges)));
                        // Dedup needs the worktree list even when the user has never
                        // opened the Worktrees section (byMain is filled lazily), but
                        // only once pull requests themselves have been requested.
                        if (!byMain.containsKey(root)) {
                            pullRequestDedupWorktrees.put(root, worktreesOf(root));
                        }
                    }
                } else {
                    // Grouped mode: definitions travel without fetching. Only
                    // opened subgroups and an opened "All" folder fetch.
                    boolean allWanted = requestedAllPullRequests.contains(root);
                    boolean anyGroupWanted = prGroups.stream().anyMatch(group ->
                            requestedPrGroups.contains(new ListingExpansionTracker.GroupKey(root, group.id())));
                    Map<String, BranchPullRequest> badges = allWanted || anyGroupWanted
                            ? branchPullRequestsNow(root, githubSettings.repository()) : Map.of();
                    if ((allWanted || anyGroupWanted) && !byMain.containsKey(root)) {
                        pullRequestDedupWorktrees.put(root, worktreesOf(root));
                    }
                    if (allWanted) {
                        listingAnswers.now(root, new PullRequestKey(root, githubSettings.repository()),
                                        pullRequestsOf(root, githubSettings.repository()), PULL_REQUESTS_DID_NOT_COMPLETE)
                                .ifPresentOrElse(
                                        listing -> pullRequests.put(root, withBadges(
                                                searchedPullRequests(root, githubSettings.repository(), listing, search),
                                                badges)),
                                        () -> pendingAllPullRequests.add(root));
                    }
                    List<PrGroups.Listing> listings = new ArrayList<>(prGroups.size());
                    for (PrGroup group : prGroups) {
                        ListingExpansionTracker.GroupKey groupKey =
                                new ListingExpansionTracker.GroupKey(root, group.id());
                        Optional<PullRequestListing> answer = Optional.empty();
                        if (requestedPrGroups.contains(groupKey)) {
                            Optional<PullRequestListing> raw = listingAnswers.now(root,
                                    new PrGroupAnswerKey(root, githubSettings.repository(), group.id(), group.filter()),
                                    prGroupListingOf(root, githubSettings.repository(), group),
                                    PULL_REQUESTS_DID_NOT_COMPLETE);
                            if (raw.isPresent()) {
                                answer = Optional.of(withBadges(raw.get(), badges));
                            } else {
                                pendingPrGroups.add(groupKey);
                            }
                        }
                        listings.add(new PrGroups.Listing(group, answer));
                    }
                    prGroupListings.put(root, listings);
                }
            }
            // One listing pass serves both sections: the Worktrees trigger
            // (its own section) and the Branches section share the same
            // listing cadence, and the branch merge reuses this worktree
            // list for occupancy instead of a second `git worktree list`.
            if (requestedWorktrees.contains(root) || requestedBranches.contains(root)) {
                List<Worktree> list = worktreesOf(root);
                fillActivity(list);
                byMain.put(root, list);
                list.forEach(worktree -> mainRootOf.put(worktree.path(), root));
                branches.put(root, branchesOf(root, list));
            }
        }

        Optional<Path> currentWorking = Optional.ofNullable(here.getBasePath())
                .map(base -> RepositoryCatalog.canonicalize(Path.of(base)));

        List<ManagedAgentSession> sessions = new ArrayList<>();
        Map<ManagedSessionId, Optional<SessionActivity>> activity = new HashMap<>();
        // One snapshot for the rows and their babysitting part, so a row and
        // its pairing can never come from two different states.
        ApplicationState state = plugin.stateStore().state();
        Map<ManagedSessionId, SessionBabysit> babysit = SessionBabysit.join(state.pairings(), state.escalations());
        for (ManagedAgentSession session : state.sessions()) {
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
        Map<ManagedSessionId, BranchPullRequest> sessionPullRequests =
                targets.resolve(sessions, publishedSessionPullRequests);
        // The badge join itself runs off this pass: a per-session git probe
        // here — originally one "git worktree list" plus an activity probe
        // per listed worktree, per session — made the initial gather cost
        // ~69 s / ~1000 git spawns for 32 sessions. The join's completion
        // publishes its map and requests one more rebuild
        // through onLateData, exactly like the PR badge lookups it joins
        // with.
        scheduleSessionPullRequestJoin(sessions, badgeSources, here);
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
        for (Map.Entry<Path, List<JiraGroup>> entry : jiraGroupRequests.entrySet()) {
            Path root = entry.getKey();
            List<JiraGroups.Listing> listings = new ArrayList<>();
            for (JiraGroup group : entry.getValue()) {
                // A group the user has not opened has no future: its listing
                // stays empty, the tree's "Not loaded" state.
                CompletableFuture<JiraIssueListing> future = jiraRequests.get(new JiraRequestKey(root, group.id()));
                Optional<JiraIssueListing> answer = future == null ? Optional.empty()
                        : listingAnswers.now(root, new JiraAnswerKey(root, group.id(), group.jql()), future,
                                JIRA_DID_NOT_COMPLETE);
                if (future != null && answer.isEmpty()) {
                    pendingJiraGroups.add(new ListingExpansionTracker.GroupKey(root, group.id()));
                }
                listings.add(new JiraGroups.Listing(group, answer));
            }
            jiraGroupListings.put(root, listings);
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
                filter, List.of(), List.of(), Optional.empty(), resumable, sessionPullRequests,
                Map.of(), Map.of(), pendingIssues, pendingJiraGroups, babysit, branches,
                prGroupListings, pendingAllPullRequests, pendingPrGroups);
    }

    /** Where a project's pull-request badges come from: its main checkout and its configured repository. */
    record BadgeSource(Path root, Optional<String> repository) { }

    /**
    /**
     * Schedules the session-to-badge join off the gather's thread.
     *
     * <p>Sessions with explicit targets use {@link TargetPullRequests}, not
     * this branch join. Each remaining session's pull request is matched
     * by its current branch against the head branches of its project's PRs. A session joins its project
     * through paths, never through ids: {@code git rev-parse} in its working
     * directory names the main checkout, whose canonical path is the badge
     * source's key (a session's {@code repositoryId} is a state-store id that
     * shares no UUID space with the shared-projects file's ids, so an id join
     * matches nothing). Only projects that list pull requests take part, and
     * only sessions whose folder still exists.
     * Never waits on {@code gh}: a lookup still running yields the last
     * answer (none on the very first pass), and its completion requests
     * one more rebuild through {@link #onLateData}.</p>
     *
     * <p>The join never runs on the gather's thread: a per-session git probe
     * here — originally one {@code git worktree list} plus an activity probe
     * per listed worktree — made the initial pass block for minutes
     * (measured: ~69 s / ~1000 git spawns for 32 sessions). One bounded
     * {@code git rev-parse} per session directory, cached like the worktree
     * lists, now supplies the checkout. The gather serves the last published
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
        // The test seam drives the join without a project: the discovery is
        // derived and published, never persisted.
        scheduleSessionPullRequestJoin(sessions, sources, null);
    }

    private void scheduleSessionPullRequestJoin(List<ManagedAgentSession> sessions,
                                                Map<Path, BadgeSource> sources, Project here) {
        if (sources.isEmpty() || sessions.isEmpty()) {
            return;
        }
        if (!badgeJoinInFlight.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture
                .supplyAsync(() -> sessionPullRequestJoin(sessions, sources, this::checkoutOf,
                                this::sessionBadgeRows),
                        badgeJoinExecutor)
                .thenAccept(join -> {
                    boolean changed = !publishedSessionPullRequests.equals(join.matched());
                    if (changed) {
                        publishedSessionPullRequests = Map.copyOf(join.matched());
                    }
                    // The discovery is applied only when it differs from the last
                    // applied answer: the badge map renders from live rows either
                    // way, and an unchanged pass must not pay a state-store scan
                    // every badge cycle.
                    Map<ManagedSessionId, Integer> discovered = join.discovered();
                    Set<ManagedSessionId> stale = join.stale();
                    if (!discovered.equals(appliedDiscovered) || !stale.equals(appliedStale)) {
                        appliedDiscovered = Map.copyOf(discovered);
                        appliedStale = Set.copyOf(stale);
                        persistDiscoveredPullRequests(discovered, stale, here);
                    }
                    if (changed) {
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
     * The last discovery the session service was asked to apply: an unchanged
     * answer (the steady state once every branch-carrying session's link is
     * current) schedules no state-store pass at all.
     */
    private volatile Map<ManagedSessionId, Integer> appliedDiscovered = Map.of();
    private volatile Set<ManagedSessionId> appliedStale = Set.of();

    /**
     * Persists what the join discovered through the session service's single
     * writer: the open PR each branch-matched session's working branch now
     * carries, and the sessions whose previously discovered open PR their
     * branch no longer carries. Fire-and-forget: a failed write costs the
     * session its persisted link until the next join pass re-derives the
     * same answer, and the badge map never depended on it. A disposed window
     * persists nothing -- its tree is going away, and the next window's
     * gather re-derives and applies the same discovery.
     */
    private void persistDiscoveredPullRequests(Map<ManagedSessionId, Integer> discovered,
                                               Set<ManagedSessionId> stale, Project here) {
        if (here == null || here.isDisposed()) {
            return;
        }
        try {
            DrydockStartupActivity.sessions(here).recordDiscoveredPullRequests(discovered, stale)
                    .exceptionally(failure -> {
                        LOG.log(Level.WARNING, "Could not persist the discovered pull requests", failure);
                        return false;
                    });
        } catch (RuntimeException e) {
            // The plugin service can refuse before returning a future (a project
            // tearing down mid-call): the same next-pass recovery applies.
            LOG.log(Level.WARNING, "Could not persist the discovered pull requests", e);
        }
    }

    /**
     * The session-to-badge join as a pure seam: the same join the instance
     * method runs, with the two blocking edges injected — the session
     * directory's checkout ({@code checkoutOf}: canonical main checkout and
     * branch, empty when git gives no answer) and the project's head-branch
     * PR map ({@code prsOf}). Pure apart from the directory-existence check
     * (a disk read, kept here so the gate stays tested with the join). The
     * source is found by the checkout's main-checkout path, never by
     * {@code session.repositoryId()}: the badge-source map is keyed by paths
     * only, so an id-join revert is a compile error, and the tests below pin
     * the behavioral regression. A detached checkout has no branch, so no badge.
     */
    /**
     * The badge view of the join: the same map the tree has always rendered
     * from. The persistence-relevant parts (which matches came from the
     * branch, which sessions were evaluated at all) ride {@link
     * #sessionPullRequestJoin}.
     */
    static Map<ManagedSessionId, BranchPullRequest> sessionPullRequests(
            List<ManagedAgentSession> sessions, Map<Path, BadgeSource> sources,
            Function<Path, Optional<Checkout>> checkoutOf,
            Function<BadgeSource, Map<String, BranchPullRequest>> prsOf) {
        return sessionPullRequestJoin(sessions, sources, checkoutOf, prsOf).matched();
    }

    /**
     * One join pass's full answer: the badge map, and the raw material the
     * persisted PR link is derived from.
     *
     * <p>{@code matched} is the badge answer -- branch match first, then the
     * session's stored number or its {@code pr-<n>} alias -- unchanged from
     * when it was the whole answer. {@code branchMatched} is the subset whose
     * match came from the head branch itself: the only matches authoritative
     * enough to persist, since a number-fallback match (a {@code pr-<n>}
     * checkout, a stale stored number) says nothing about the working branch
     * carrying the PR. {@code evaluated} is every session the pass actually
     * examined with positive evidence -- directory present, checkout read,
     * branch named, and a non-empty listing for a known badge source -- the
     * set whose members may have their discovered-but-no-longer-branch-carried
     * link cleared. The exclusions cut both ways: a session whose directory is
     * gone or whose checkout is detached carries positive evidence that its
     * branch can no longer back a PR (it joins {@code evaluated} without the
     * listing), while a failed git probe, an unregistered project or an empty
     * listing is no evidence at all and must never clear a link.
     *
     * <p>{@code discovered} maps each branch-matched session to the number of
     * the OPEN PR its branch carries (rows of any other lifecycle suppress
     * clearing but persist nothing -- a merged PR's link belongs to the merge
     * flow, which records it with its own semantics). {@code stale} is the
     * complementary evaluated set: sessions whose previously persisted OPEN
     * link their branch no longer backs.
     */
    record SessionPullRequestJoin(Map<ManagedSessionId, BranchPullRequest> matched,
                                  Map<ManagedSessionId, BranchPullRequest> branchMatched,
                                  Set<ManagedSessionId> evaluated) {

        SessionPullRequestJoin {
            matched = Map.copyOf(matched);
            branchMatched = Map.copyOf(branchMatched);
            evaluated = Set.copyOf(evaluated);
        }

        /** Branch-matched sessions' open PR numbers, keyed by session. */
        Map<ManagedSessionId, Integer> discovered() {
            Map<ManagedSessionId, Integer> open = new HashMap<>();
            branchMatched.forEach((id, pullRequest) -> {
                if (pullRequest.state() == PrLifecycle.OPEN) {
                    open.put(id, pullRequest.number());
                }
            });
            return open;
        }

        /** Evaluated sessions whose working branch no longer carries their PR. */
        Set<ManagedSessionId> stale() {
            Set<ManagedSessionId> remaining = new HashSet<>(evaluated);
            remaining.removeAll(branchMatched.keySet());
            return remaining;
        }
    }

    /**
     * The session-to-badge join as a pure seam: the same join the instance
     * method runs, with the two blocking edges injected -- the session
     * directory's checkout ({@code checkoutOf}: canonical main checkout and
     * branch, empty when git gives no answer) and the project's head-branch
     * PR map ({@code prsOf}). Pure apart from the directory-existence check
     * (a disk read, kept here so the gate stays tested with the join). The
     * source is found by the checkout's main-checkout path, never by
     * {@code session.repositoryId()}: the badge-source map is keyed by paths
     * only, so an id-join revert is a compile error, and the tests below pin
     * the behavioral regression. A detached checkout has no branch, so no badge.
     */
    static SessionPullRequestJoin sessionPullRequestJoin(
            List<ManagedAgentSession> sessions, Map<Path, BadgeSource> sources,
            Function<Path, Optional<Checkout>> checkoutOf,
            Function<BadgeSource, Map<String, BranchPullRequest>> prsOf) {
        Map<ManagedSessionId, BranchPullRequest> matched = new HashMap<>();
        Map<ManagedSessionId, BranchPullRequest> branchMatched = new HashMap<>();
        Set<ManagedSessionId> evaluated = new HashSet<>();
        if (sources.isEmpty()) {
            // No project lists pull requests, so nothing can be re-observed;
            // a gone directory is still positive evidence its link is stale.
            sessions.stream()
                    .filter(session -> session.targetPullRequest().isEmpty())
                    .filter(session -> !Files.isDirectory(session.workingDirectory()))
                    .map(ManagedAgentSession::id)
                    .forEach(evaluated::add);
            return new SessionPullRequestJoin(matched, branchMatched, evaluated);
        }
        Map<Path, Map<String, BranchPullRequest>> byRoot = new HashMap<>();
        for (ManagedAgentSession session : sessions) {
            if (session.targetPullRequest().isPresent()) {
                continue;
            }
            if (!Files.isDirectory(session.workingDirectory())) {
                evaluated.add(session.id());
                continue;
            }
            Optional<Checkout> checkout = checkoutOf.apply(session.workingDirectory());
            if (checkout.isEmpty()) {
                continue;
            }
            // A detached checkout's branch is definitively gone: it joins the
            // stale set without consulting the listing, which has nothing to
            // match a branchless checkout against.
            Optional<String> branch = checkout.get().branch();
            if (branch.isEmpty()) {
                evaluated.add(session.id());
                continue;
            }
            Optional<BadgeSource> source = Optional.ofNullable(sources.get(checkout.get().mainCheckout()));
            if (source.isEmpty()) {
                continue;
            }
            Map<String, BranchPullRequest> pullRequests = byRoot.computeIfAbsent(source.get().root(),
                    ignored -> prsOf.apply(source.get()));
            if (pullRequests.isEmpty()) {
                // An empty answer is no evidence: the listing may have failed
                // or been truncated, and silence must never clear a link.
                continue;
            }
            evaluated.add(session.id());
            BranchPullRequest byBranch = pullRequests.get(branch.get());
            if (byBranch != null) {
                branchMatched.put(session.id(), byBranch);
            }
            sessionPullRequest(pullRequests, branch.get(), session.pr().number())
                    .ifPresent(pullRequest -> matched.put(session.id(), pullRequest));
        }
        return new SessionPullRequestJoin(matched, branchMatched, evaluated);
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
     * The head-branch PR map for {@code root} as far as it is known now.
     * A missing or expired entry starts a lookup whose completion calls
     * {@link #lateData}; until it completes the previous answer is served.
     * The badge listing refreshes on {@link #BADGE_CACHE_NANOS}, not the
     * listings' one-minute TTL: it is the heavy fetch every light listing's
     * conflict and CI signals join from.
     */
    private Map<String, BranchPullRequest> branchPullRequestRowsNow(Path root, Optional<String> repository) {
        PullRequestKey key = new PullRequestKey(root, repository);
        long now = System.nanoTime();
        CachedBranchPullRequests cached = branchPullRequestRowCache.get(key);
        if (cached == null || cached.expiresAt() <= now) {
            Map<String, BranchPullRequest> previous = cached == null ? Map.of() : current(cached);
            CompletableFuture<Map<String, BranchPullRequest>> lookup = github.branchPullRequestRows(root, repository);
            cached = new CachedBranchPullRequests(now + BADGE_CACHE_NANOS, lookup, previous);
            branchPullRequestRowCache.put(key, cached);
            if (!lookup.isDone()) {
                lookup.whenComplete((answer, failure) -> lateData.run());
            }
        }
        return current(cached);
    }

    /**
     * The join's per-source fetch: light rows for identity and state, with
     * the heavy listing's conflict/CI signals joined in by branch where the
     * heavy answer exists. The heavy fetch keeps running here (it is what
     * fed the join before the light split) so a session row's hover keeps
     * its health text without the PR node ever being opened; when GitHub
     * drops the heavy query the badge still stands, undecorated.
     */
    private Map<String, BranchPullRequest> sessionBadgeRows(BadgeSource source) {
        Map<String, BranchPullRequest> rows = branchPullRequestRowsNow(source.root(), source.repository());
        // The heavy answer as of now (the previous map while a refresh runs):
        // the same call the tree's pull-request section badges from.
        Map<String, BranchPullRequest> health = branchPullRequestsNow(source.root(), source.repository());
        if (rows.isEmpty() || health.isEmpty()) {
            return rows;
        }
        Map<String, BranchPullRequest> decorated = new HashMap<>(rows);
        rows.forEach((branch, row) -> {
            BranchPullRequest full = health.get(branch);
            if (full != null && (full.conflicting() || !full.ciFailures().isEmpty())) {
                decorated.put(branch, new BranchPullRequest(row.number(), row.state(), row.draft(),
                        row.headRefName(), row.title(), row.url(), full.conflicting(), full.ciFailures()));
            }
        });
        return decorated;
    }

    /**
     * A missing or expired entry starts a lookup whose completion calls
     * {@link #lateData}; until it completes the previous answer is served.
     * The badge listing refreshes on {@link #BADGE_CACHE_NANOS}, not the
     * listings' one-minute TTL: it is the heavy fetch every light listing's
     * conflict and CI signals join from.
     */
    private Map<String, BranchPullRequest> branchPullRequestsNow(Path root, Optional<String> repository) {
        PullRequestKey key = new PullRequestKey(root, repository);
        long now = System.nanoTime();
        CachedBranchPullRequests cached = branchPullRequestCache.get(key);
        if (cached == null || cached.expiresAt() <= now) {
            Map<String, BranchPullRequest> previous = cached == null ? Map.of() : current(cached);
            CompletableFuture<Map<String, BranchPullRequest>> lookup = github.branchPullRequests(root, repository);
            cached = new CachedBranchPullRequests(now + BADGE_CACHE_NANOS, lookup, previous);
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
        return cachedPullRequests(pullRequestCache, new PullRequestKey(root, repository),
                () -> github.openPullRequests(root, repository));
    }

    private CompletableFuture<PullRequestListing> prGroupListingOf(Path root, Optional<String> repository,
                                                                   PrGroup group) {
        return cachedPullRequests(prGroupCache, new PrGroupKey(root, repository, group.filter()),
                () -> github.filteredPullRequests(root, repository, group.parsedFilter()));
    }

    private <K> CompletableFuture<PullRequestListing> cachedPullRequests(
            Map<K, CachedPullRequests> cache, K key, Supplier<CompletableFuture<PullRequestListing>> fetch) {
        long now = System.nanoTime();
        CachedPullRequests cached = cache.get(key);
        if (cached != null && cached.expiresAt() > now) {
            if (!failed(cached.listing())) {
                return cached.listing();
            }
            // A failed listing is served for a bounded backoff instead of
            // refetching on every rebuild pass. GitHub's GraphQL 502/504s on
            // heavy listing queries for minutes at a stretch, and the old
            // refetch-on-every-pass rule -- with the failure's own rebuild
            // trigger -- turned one failing repository into a retry storm
            // that also burned the rate budget every other repository's
            // listing shares. During the backoff the section keeps its
            // error row; after it the retry gets one bounded run (the
            // listing itself retries once without its heaviest fields).
            cache.replace(key, cached,
                    new CachedPullRequests(Math.min(cached.expiresAt(), now + FAILED_LISTING_NANOS), cached.listing()));
            return cached.listing();
        }
        CompletableFuture<PullRequestListing> listing = fetch.get();
        cache.put(key, new CachedPullRequests(now + ISSUE_CACHE_NANOS, listing));
        return listing;
    }

    /**
     * Whether a cached pull-request listing failed. A failed listing is
     * served from the cache only for {@link #FAILED_LISTING_NANOS}: a
     * transient {@code gh} failure is retried after the backoff instead of
     * pinning the error forever, and the section's error row stays up
     * while a backoff-expired refetch is in flight (the last shown answer
     * is served until the new one completes).
     */
    private static boolean failed(CompletableFuture<PullRequestListing> listing) {
        // openPullRequestsBlocking never completes exceptionally (every
        // failure becomes a Failed listing), so join on a done future is safe.
        return listing.isDone() && listing.join() instanceof PullRequestListing.Failed;
    }

    /**
     * {@code base} widened by the rows a server-side search finds beyond
     * the fetch limit: the limit bounds the plain listing's newest-100
     * window, and the client-side filter over that window alone made older
     * PRs and issues unreachable by search. The searched rows ride
     * {@code --search} (the server applies the query before its limit) and
     * exact-number lookups; they only ever widen the pool — the model's own
     * matcher still decides what is shown, so a search hit the client would
     * reject never appears. Only a Listed base widens: a failed plain
     * listing is the section's error row, not a candidate pool. Pure apart
     * from the search fetches it starts (never waited on; their completion
     * requests one more rebuild through {@link #onLateData}).
     */
    private PullRequestListing searchedPullRequests(Path root, Optional<String> repository,
                                                    PullRequestListing base, ListingSearch search) {
        if (!(base instanceof PullRequestListing.Listed listed) || !search.searchesPullRequests()) {
            return base;
        }
        List<OpenPullRequest> extra = new ArrayList<>();
        if (!search.pullRequestQuery().isEmpty()) {
            extra.addAll(searchPullRequestRows(root, repository, search.pullRequestQuery()));
        }
        for (int number : search.pullRequestNumbers()) {
            exactPullRequest(root, repository, number).ifPresent(extra::add);
        }
        if (extra.isEmpty()) {
            return base;
        }
        return new PullRequestListing.Listed(mergePullRequests(listed.pullRequests(), extra));
    }

    /** Same rule for a GitHub issue listing. */
    private IssueListing searchedIssues(Path root, Optional<String> repository,
                                        IssueListing base, ListingSearch search) {
        if (!(base instanceof IssueListing.Listed listed) || !search.searchesIssues()) {
            return base;
        }
        List<OpenIssue> extra = searchIssueRows(root, repository, search.issueQuery());
        if (extra.isEmpty()) {
            return base;
        }
        Map<Integer, OpenIssue> merged = new LinkedHashMap<>();
        for (OpenIssue row : listed.issues()) {
            merged.put(row.number(), row);
        }
        for (OpenIssue row : extra) {
            merged.putIfAbsent(row.number(), row);
        }
        return new IssueListing.Listed(List.copyOf(merged.values()));
    }

    /** Base rows win; a searched row only fills a number the base does not carry. Pure. */
    static List<OpenPullRequest> mergePullRequests(List<OpenPullRequest> base, List<OpenPullRequest> extra) {
        Map<Integer, OpenPullRequest> merged = new LinkedHashMap<>();
        for (OpenPullRequest row : base) {
            merged.put(row.number(), row);
        }
        for (OpenPullRequest row : extra) {
            merged.putIfAbsent(row.number(), row);
        }
        return List.copyOf(merged.values());
    }

    /**
     * The listing's rows carrying the conflict and CI signals of the badge
     * listing ({@link #branchPullRequestsNow}, the one fetch that still
     * asks for the heavy fields), joined by PR number. A row the badge map
     * does not know yet -- a PR listed in the last minute that the badge
     * fetch has not seen, or a badge fetch that failed -- keeps the light
     * row's "no signal", which the renderer treats as neither red nor
     * conflicting. A failed listing passes through unchanged: the section's
     * error row is not something a badge join can repair. Pure.
     */
    static PullRequestListing withBadges(PullRequestListing listing,
                                         Map<String, BranchPullRequest> byHeadBranch) {
        if (!(listing instanceof PullRequestListing.Listed listed)) {
            return listing;
        }
        Map<Integer, BranchPullRequest> byNumber = new HashMap<>();
        for (BranchPullRequest pullRequest : byHeadBranch.values()) {
            byNumber.put(pullRequest.number(), pullRequest);
        }
        List<OpenPullRequest> rows = new ArrayList<>();
        for (OpenPullRequest row : listed.pullRequests()) {
            BranchPullRequest badge = byNumber.get(row.number());
            rows.add(badge == null ? row
                    : row.withSignals(badge.conflicting(), badge.ciFailures()));
        }
        return new PullRequestListing.Listed(rows);
    }

    /**
     * The searched rows of {@code root}'s latest query: the running fetch's
     * answer when it is done, else the previous answer of the same query
     * (a failed re-query keeps showing what the last one found), else
     * none yet. A changed query or an expired entry starts one new fetch;
     * its completion requests one more rebuild.
     */
    private List<OpenPullRequest> searchPullRequestRows(Path root, Optional<String> repository, String query) {
        long now = System.nanoTime();
        CachedSearchPullRequests cached = searchPullRequestCache.get(root);
        if (cached == null || !cached.query().equals(query) || cached.expiresAt() <= now) {
            PullRequestListing.Listed previous =
                    cached != null && cached.query().equals(query) ? cached.previous() : null;
            CompletableFuture<PullRequestListing> lookup = github.searchPullRequests(root, repository, query);
            cached = new CachedSearchPullRequests(query, now + ISSUE_CACHE_NANOS, lookup, previous);
            searchPullRequestCache.put(root, cached);
            if (!lookup.isDone()) {
                lookup.whenComplete((ignored, failure) -> lateData.run());
            }
        }
        return searchedRows(cached);
    }

    private List<OpenPullRequest> searchedRows(CachedSearchPullRequests cached) {
        PullRequestListing answer = cached.lookup().isDone()
                ? completedPullRequests(cached.lookup()) : null;
        PullRequestListing.Listed listed = answer instanceof PullRequestListing.Listed l ? l : cached.previous();
        return listed == null ? List.of() : listed.pullRequests();
    }

    private List<OpenIssue> searchIssueRows(Path root, Optional<String> repository, String query) {
        long now = System.nanoTime();
        CachedSearchIssues cached = searchIssueCache.get(root);
        if (cached == null || !cached.query().equals(query) || cached.expiresAt() <= now) {
            IssueListing.Listed previous =
                    cached != null && cached.query().equals(query) ? cached.previous() : null;
            CompletableFuture<IssueListing> lookup = github.searchIssues(root, repository, query);
            cached = new CachedSearchIssues(query, now + ISSUE_CACHE_NANOS, lookup, previous);
            searchIssueCache.put(root, cached);
            if (!lookup.isDone()) {
                lookup.whenComplete((ignored, failure) -> lateData.run());
            }
        }
        if (cached.lookup().isDone()) {
            IssueListing answer = completedIssues(cached.lookup());
            IssueListing.Listed listed = answer instanceof IssueListing.Listed l ? l : cached.previous();
            return listed == null ? List.of() : listed.issues();
        }
        return cached.previous() == null ? List.of() : cached.previous().issues();
    }

    /** The finished lookup's listing, or null while it runs (every failure is a Failed listing, never a throw). */
    private static PullRequestListing completedPullRequests(CompletableFuture<PullRequestListing> lookup) {
        return lookup.isDone() && !lookup.isCompletedExceptionally() ? lookup.join() : null;
    }

    private static IssueListing completedIssues(CompletableFuture<IssueListing> lookup) {
        return lookup.isDone() && !lookup.isCompletedExceptionally() ? lookup.join() : null;
    }

    /** One exact-number PR lookup with the same run/previous-answer cadence as the searched rows. */
    private Optional<OpenPullRequest> exactPullRequest(Path root, Optional<String> repository, int number) {
        ExactPullRequestKey key = new ExactPullRequestKey(root, repository, number);
        long now = System.nanoTime();
        CachedExactPullRequest cached = exactPullRequestCache.get(key);
        if (cached == null || cached.expiresAt() <= now) {
            Optional<OpenPullRequest> previous = Optional.empty();
            if (cached != null) {
                previous = cached.lookup().isDone()
                        ? completedExact(cached.lookup()) : cached.previous();
            }
            CompletableFuture<Optional<OpenPullRequest>> lookup =
                    github.pullRequestDetail(root, repository, number);
            cached = new CachedExactPullRequest(now + ISSUE_CACHE_NANOS, lookup, previous);
            exactPullRequestCache.put(key, cached);
            if (!lookup.isDone()) {
                lookup.whenComplete((ignored, failure) -> lateData.run());
            }
        }
        if (cached.lookup().isDone()) {
            Optional<OpenPullRequest> answer = completedExact(cached.lookup());
            return answer != null ? answer : cached.previous();
        }
        return cached.previous();
    }

    /** The finished lookup's answer, or null while it runs (a failed lookup answered empty, never a throw). */
    private static Optional<OpenPullRequest> completedExact(CompletableFuture<Optional<OpenPullRequest>> lookup) {
        return lookup.isDone() && !lookup.isCompletedExceptionally() ? lookup.join() : null;
    }

    /**
     * {@code directory}'s checkout with a canonical main checkout, cached for
     * the worktree lists' TTL; empty when git gives no answer (for this pass
     * only, never cached).
     */
    private Optional<Checkout> checkoutOf(Path directory) {
        long now = System.nanoTime();
        CachedCheckout cached = checkoutCache.get(directory);
        if (cached != null && cached.expiresAt() > now) {
            return Optional.of(cached.checkout());
        }
        try {
            Checkout found = worktrees.checkoutOf(directory).get(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Checkout canonical = new Checkout(RepositoryCatalog.canonicalize(found.mainCheckout()), found.branch());
            checkoutCache.put(directory, new CachedCheckout(now + WORKTREE_CACHE_NANOS, canonical));
            return Optional.of(canonical);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.log(Level.DEBUG, () -> "No checkout for " + directory + ": " + e);
            return Optional.empty();
        }
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

    /**
     * {@code root}'s branches merged with the worktree occupancy, canonical
     * holder paths; the {@link BranchCatalog#EMPTY} stand-in when git cannot
     * answer (for this pass only — a failed listing is never cached, the
     * same rule the worktree listing follows). The worktree list is the one
     * already gathered for the project, so the merge costs no second
     * {@code git worktree list}.
     */
    private BranchCatalog branchesOf(Path root, List<Worktree> worktreeList) {
        long now = System.nanoTime();
        CachedBranches cached = branchCache.get(root);
        if (cached != null && cached.expiresAt() > now) {
            return cached.listed();
        }
        try {
            BranchListing listing = gitStatus.listBranches(root)
                    .get(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<Worktree> canonical = worktreeList.stream()
                    .map(worktree -> new Worktree(RepositoryCatalog.canonicalize(worktree.path()),
                            worktree.branch(), worktree.mainCheckout(), worktree.detached(), worktree.prunable(),
                            worktree.locked(), worktree.lockReason(), worktree.merged()))
                    .toList();
            BranchCatalog merged = BranchCatalog.merge(listing, canonical);
            branchCache.put(root, new CachedBranches(now + WORKTREE_CACHE_NANOS, merged));
            return merged;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.DEBUG, () -> "No branches for " + root + ": interrupted; not cached");
            return BranchCatalog.EMPTY;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.log(Level.DEBUG, () -> "No branches for " + root + ": " + e);
            return BranchCatalog.EMPTY;
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
        gitStatus.close();
        github.close();
        jira.close();
    }
}
