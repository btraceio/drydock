package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.git.GhCliService.IssueListing;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.GhCliService.OpenPullRequest;
import app.drydock.git.GhCliService.PullRequestListing;
import app.drydock.git.JiraIssue;
import app.drydock.git.JiraIssueListing;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.agent.SessionCommands;
import app.drydock.intellij.tasks.TaskNode.AddCurrentProject;
import app.drydock.intellij.tasks.TaskNode.Ghost;
import app.drydock.intellij.tasks.TaskNode.GhostSource;
import app.drydock.intellij.tasks.TaskNode.GitHubNode;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.IssueRow;
import app.drydock.intellij.tasks.TaskNode.JiraGroupRow;
import app.drydock.intellij.tasks.TaskNode.JiraIssueRow;
import app.drydock.intellij.tasks.TaskNode.JiraNode;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.review.RepositoryPullRequests;
import app.drydock.intellij.tasks.TaskNode.PullRequestRow;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.SectionKind;
import app.drydock.intellij.tasks.TaskNode.StaleWorktrees;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.tasks.TaskNode.WorktreeGroup;
import app.drydock.projects.SharedProject;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The shape of the Drydock tool window's tree, decided in one pure place:
 * registered projects, and under each separate Sessions and sessionless
 * Worktrees sections. Sessions split into Active and Inactive when both are
 * present, and remain grouped by worktree within their activity band.
 *
 * <p>A task (an agent session) is placed by where it works, not by the
 * repository id it was recorded under: its working directory resolves to a
 * main checkout ({@code mainRootOf}, from {@code git worktree list}), and
 * that main checkout's registered project is its parent. When git can no
 * longer resolve it — a worktree removed outside Drydock — the longest
 * registered root containing it takes it, else the main checkout the task
 * recorded when it was started, so a task never just vanishes.
 * Sessions are placed this way because the plugin's catalog records each
 * worktree as its own repository, and because a project removed and added
 * again must get its old tasks back.</p>
 *
 * <p>Every path in {@link Inputs} is expected to be canonical already;
 * this class does no I/O.</p>
 */
public final class TaskTreeModel {

    static final Duration STALE_AFTER = Duration.ofDays(7);

    /** The muted child of a configured listing node the user has not opened yet. */
    static final String NOT_LOADED = "Not loaded";

    /** The one child of a listing that answered with nothing: the renderer's "answered" discriminator. */
    static final String NO_ISSUES = "No issues";

    /**
     * The search box's optional project scope: {@code /<glob>/<items>} limits
     * the search to projects whose display name matches the glob, with
     * {@code <items>} filtering the entries inside them exactly as plain
     * search does. An unclosed {@code /<glob>} (search still being typed)
     * already scopes, with no item filter. The glob matches case-insensitively
     * anywhere in the name; {@code *} and {@code ?} are wildcards. An empty
     * glob ({@code //} or a lone {@code /}) matches every project.
     *
     * <p>Plain (unscoped) search now also matches project names: a project
     * whose name contains the filter is shown with all of its entries, not
     * only the entries that themselves match.
     */
    record ProjectScope(String projectGlob, String itemFilter) {
        static final ProjectScope NONE = new ProjectScope(null, "");

        boolean scoped() {
            return projectGlob != null;
        }

        boolean matchesProject(String displayName) {
            return globMatches(projectGlob, displayName);
        }
    }

    /** Splits the raw search text into its optional project scope and the item filter. */
    static ProjectScope parseFilter(String filter) {
        if (filter == null || !filter.startsWith("/")) {
            return ProjectScope.NONE;
        }
        int end = filter.indexOf('/', 1);
        if (end < 0) {
            return new ProjectScope(filter.substring(1), "");
        }
        return new ProjectScope(filter.substring(1, end), filter.substring(end + 1));
    }

    /** Case-insensitive glob ({@code *}, {@code ?}) matching anywhere in {@code text}; an empty pattern matches all. */
    private static boolean globMatches(String glob, String text) {
        String pattern = glob == null ? "" : glob.strip();
        if (pattern.isEmpty()) {
            return true;
        }
        StringBuilder regex = new StringBuilder(".*");
        String lower = pattern.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return text.toLowerCase(Locale.ROOT)
                .matches(regex + ".*");
    }

    /** Mutually exclusive quick views; text search is applied within the selected view. */
    public enum ViewFilter {
        ALL("All", "Show sessions, worktrees, and issues",
                "No projects yet. Start a task with ＋ to add this one."),
        ACTIVE("Active", "Show only sessions whose agent is running", "No active sessions."),
        INACTIVE("Inactive", "Show only sessions whose agent is not running",
                "No inactive sessions."),
        NEEDS_YOU("Needs you", "Show only running sessions waiting for your answer",
                "No sessions need you."),
        PULL_REQUESTS("PRs", "Show only open GitHub pull requests", "No open pull requests."),
        ISSUES("Issues", "Show only open issues (GitHub and Jira)", "No open issues.");

        private final String label;
        private final String tooltip;
        private final String emptyText;

        ViewFilter(String label, String tooltip, String emptyText) {
            this.label = label;
            this.tooltip = tooltip;
            this.emptyText = emptyText;
        }

        public String label() {
            return label;
        }

        /** The view chip's hover text: what the view shows. */
        public String tooltip() {
            return tooltip;
        }

        /** What the tree says when this view is selected and nothing is in it. */
        public String emptyText() {
            return emptyText;
        }
    }

    private TaskTreeModel() {
    }

    /**
     * Everything the tree is built from.
     *
     * @param mainRootOf      canonical working directory or worktree → its canonical main checkout
     * @param currentMainRoot this window's project's main checkout, if it has one
     * @param currentWorkingRoot this window's project directory (a worktree or the main checkout)
     * @param missingFolders  registered roots whose folder does not exist
     * @param recordedRoots   the canonical root of the repository each session was recorded under (the
     *                        plugin's own catalog): a task started from New task records its main checkout
     */
    /** The window showing a running agent: its canonical project directory, and its repo's name. */
    public record Host(Path base, String repo) {
        public Host {
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(repo, "repo");
        }
    }

    public record Inputs(List<SharedProject> projects, List<ManagedAgentSession> sessions,
                         Map<Path, List<Worktree>> worktreesByMainRoot, Map<Path, Path> mainRootOf,
                         Optional<Path> currentMainRoot, Optional<Path> currentWorkingRoot,
                         Map<ManagedSessionId, Optional<SessionActivity>> activity, Set<ManagedSessionId> running,
                         Map<ManagedSessionId, Host> hosts, Set<Path> missingFolders,
                         Map<RepositoryId, Path> recordedRoots,
                         Set<Path> worktreeContainers, Set<Path> pullRequestContainers,
                         Set<Path> githubIssueRoots, Set<Path> githubGhostRoots,
                         Set<Path> jiraRoots, Set<Path> jiraGhostRoots,
                         Set<Path> openFolders, Map<Path, Instant> lastActivity, Instant now,
                         Map<Path, IssueListing> issuesByMainRoot,
                         Map<Path, List<JiraGroups.Listing>> jiraGroupsByMainRoot,
                         Map<Path, PullRequestListing> pullRequestsByMainRoot,
                         Map<Path, List<Worktree>> pullRequestDedupWorktrees,
                         Optional<String> listProblem, String filter,
                         ViewFilter viewFilter, Map<ManagedSessionId, Boolean> resumable) {
        public Inputs {
            projects = List.copyOf(projects);
            sessions = List.copyOf(sessions);
            worktreesByMainRoot = Map.copyOf(worktreesByMainRoot);
            mainRootOf = Map.copyOf(mainRootOf);
            hosts = Map.copyOf(hosts);
            Objects.requireNonNull(currentMainRoot, "currentMainRoot");
            Objects.requireNonNull(currentWorkingRoot, "currentWorkingRoot");
            activity = Map.copyOf(activity);
            running = Set.copyOf(running);
            missingFolders = Set.copyOf(missingFolders);
            recordedRoots = Map.copyOf(recordedRoots);
            worktreeContainers = Set.copyOf(worktreeContainers);
            pullRequestContainers = Set.copyOf(pullRequestContainers);
            githubIssueRoots = Set.copyOf(githubIssueRoots);
            githubGhostRoots = Set.copyOf(githubGhostRoots);
            jiraRoots = Set.copyOf(jiraRoots);
            jiraGhostRoots = Set.copyOf(jiraGhostRoots);
            openFolders = Set.copyOf(openFolders);
            lastActivity = Map.copyOf(lastActivity);
            Objects.requireNonNull(now, "now");
            issuesByMainRoot = Map.copyOf(issuesByMainRoot);
            jiraGroupsByMainRoot = Map.copyOf(jiraGroupsByMainRoot);
            jiraGroupsByMainRoot.values().forEach(children -> children.forEach(Objects::requireNonNull));
            pullRequestsByMainRoot = Map.copyOf(pullRequestsByMainRoot);
            pullRequestDedupWorktrees = Map.copyOf(pullRequestDedupWorktrees);
            Objects.requireNonNull(listProblem, "listProblem");
            filter = filter == null ? "" : filter.strip();
            Objects.requireNonNull(viewFilter, "viewFilter");
            resumable = Map.copyOf(resumable);
        }
    }

    public static List<TaskNode> build(Inputs in) {
        List<SharedProject> local = in.projects().stream().filter(project -> !project.isRemote()).toList();
        ProjectScope scope = parseFilter(in.filter());
        Map<Path, List<TaskRow>> tasksByMain = new LinkedHashMap<>();
        for (ManagedAgentSession session : in.sessions()) {
            if (session.agentKind() == AgentKind.TERMINAL) {
                continue;
            }
            placement(in, local, session).ifPresent(main ->
                    tasksByMain.computeIfAbsent(main, ignored -> new ArrayList<>()).add(row(in, main, session)));
        }

        List<TaskNode> nodes = new ArrayList<>();
        in.listProblem().ifPresent(message -> nodes.add(new TaskNode.ListProblem(message)));
        Optional<Path> current = in.currentMainRoot();
        if (current.isPresent() && local.stream().noneMatch(project -> project.root().equals(current.get()))) {
            Path root = current.get();
            String name = String.valueOf(root.getFileName());
            boolean nameMatch = nameMatch(in.filter(), scope, name);
            // A scope is a hard filter; unscoped, the current project keeps
            // showing (with the plain filter applied to its tasks).
            if (!scope.scoped() || nameMatch) {
                String itemFilter = itemFilter(in.filter(), scope, nameMatch);
                List<TaskRow> visible = sorted(tasksByMain.getOrDefault(root, List.of())).stream()
                        .filter(task -> visibleIn(in.viewFilter(), task))
                        .filter(task -> matches(itemFilter, task))
                        .toList();
                if (in.viewFilter() == ViewFilter.ALL || !visible.isEmpty()) {
                    nodes.add(new AddCurrentProject(root, name));
                    nodes.addAll(visible);
                }
            }
        }

        List<SharedProject> ordered = new ArrayList<>(local);
        ordered.sort(Comparator.comparing((SharedProject project) -> !project.root().equals(current.orElse(null)))
                .thenComparing(project -> project.displayName().toLowerCase(Locale.ROOT)));
        for (SharedProject project : ordered) {
            boolean thisWindow = project.root().equals(current.orElse(null));
            boolean nameMatch = nameMatch(in.filter(), scope, project.displayName());
            if (scope.scoped() && !nameMatch) {
                continue;
            }
            String itemFilter = itemFilter(in.filter(), scope, nameMatch);
            Children classified = children(in, project.root(),
                    tasksByMain.getOrDefault(project.root(), List.of()), itemFilter);
            if (classified.visible().isEmpty() && (in.viewFilter() != ViewFilter.ALL
                    || !in.filter().isEmpty() && !thisWindow && !nameMatch)) {
                continue;
            }
            int running = (int) tasksByMain.getOrDefault(project.root(), List.of()).stream()
                    .filter(TaskRow::running).count();
            nodes.add(new ProjectRow(project, in.missingFolders().contains(project.root()), thisWindow,
                    classified.visible(), classified.inactive(), running));
        }
        return nodes;
    }

    /**
     * Removes worktree rows from an already-built model. This is the fast UI
     * acknowledgement after git confirms deletion; a full rebuild still
     * follows to reconcile the model with disk.
     */
    static List<TaskNode> withoutWorktrees(List<TaskNode> nodes, Set<Path> removed) {
        return nodes.stream().map(node -> withoutWorktrees(node, removed)).flatMap(Optional::stream).toList();
    }

    private static Optional<TaskNode> withoutWorktrees(TaskNode node, Set<Path> removed) {
        return switch (node) {
            case ProjectRow row -> Optional.of(new ProjectRow(row.project(), row.folderMissing(), row.isThisWindow(),
                    withoutWorktrees(row.children(), removed), row.inactiveWorktrees().stream()
                            .filter(worktree -> !removed.contains(worktree.worktree())).toList(), row.running()));
            case Section section -> {
                List<TaskNode> children = withoutWorktrees(section.children(), removed);
                yield children.isEmpty() ? Optional.empty() : Optional.of(new Section(section.kind(), children));
            }
            case StaleWorktrees stale -> {
                List<IdleWorktree> worktrees = stale.worktrees().stream()
                        .filter(worktree -> !removed.contains(worktree.worktree())).toList();
                yield worktrees.isEmpty() ? Optional.empty() : Optional.of(new StaleWorktrees(worktrees));
            }
            case IdleWorktree idle when removed.contains(idle.worktree()) -> Optional.empty();
            default -> Optional.of(node);
        };
    }

    /** Whether the project matches by name: the scope's glob when scoped, else the plain filter. */
    private static boolean nameMatch(String rawFilter, ProjectScope scope, String displayName) {
        return scope.scoped() ? scope.matchesProject(displayName)
                : !rawFilter.isEmpty() && contains(displayName, rawFilter);
    }

    /**
     * The filter the project's entries are narrowed by: none when the project
     * itself matched by name (the whole project is the answer), otherwise the
     * scope's item filter or, unscoped, the plain filter.
     */
    private static String itemFilter(String rawFilter, ProjectScope scope, boolean nameMatch) {
        if (scope.scoped()) {
            return scope.itemFilter();
        }
        return nameMatch ? "" : rawFilter;
    }

    /** The main checkout {@code workingDirectory} belongs to, if any registered or current project takes it. */
    private static Optional<Path> placement(Inputs in, List<SharedProject> local, ManagedAgentSession session) {
        Path workingDirectory = session.workingDirectory();
        Path main = in.mainRootOf().get(workingDirectory);
        if (main == null) {
            main = local.stream()
                    .map(SharedProject::root)
                    .filter(workingDirectory::startsWith)
                    .max(Comparator.comparingInt(Path::getNameCount))
                    .orElse(null);
        }
        if (main == null) {
            // Git no longer knows the folder (a worktree removed outside
            // Drydock lives at <base>/<repo>-<slug>, under no project root):
            // the main checkout the task recorded when it was started.
            main = in.recordedRoots().get(session.repositoryId());
        }
        if (main == null && in.currentWorkingRoot().isPresent()
                && workingDirectory.startsWith(in.currentWorkingRoot().get())) {
            main = in.currentMainRoot().orElse(null);
        }
        if (main == null) {
            return Optional.empty();
        }
        Path resolved = main;
        boolean registered = local.stream().anyMatch(project -> project.root().equals(resolved));
        return registered || resolved.equals(in.currentMainRoot().orElse(null)) ? Optional.of(resolved)
                : Optional.empty();
    }

    private static TaskRow row(Inputs in, Path main, ManagedAgentSession session) {
        Path directory = session.workingDirectory();
        Optional<String> branch = in.worktreesByMainRoot().getOrDefault(main, List.of()).stream()
                .filter(worktree -> worktree.path().equals(directory))
                .findFirst()
                .flatMap(Worktree::branch);
        boolean running = in.running().contains(session.id());
        boolean resumable = !running && SessionCommands.resumable(
                Optional.ofNullable(in.resumable().get(session.id())), session.agentSessionId().isPresent());
        Optional<Host> host = Optional.ofNullable(in.hosts().get(session.id()));
        TaskPlacement placement = TaskPlacement.of(running, host.map(Host::base), host.map(Host::repo), directory,
                in.currentWorkingRoot());
        return new TaskRow(session, in.activity().getOrDefault(session.id(), Optional.empty()),
                running, branch, placement, session.readOnly(),
                running && in.missingFolders().contains(directory), resumable);
    }

    private record Children(List<TaskNode> visible, List<IdleWorktree> inactive) {
    }

    private static Children children(Inputs in, Path main, List<TaskRow> tasks, String itemFilter) {
        List<TaskNode> children = new ArrayList<>();
        List<TaskRow> visibleTasks = sorted(tasks).stream()
                .filter(task -> visibleIn(in.viewFilter(), task))
                .filter(task -> matches(itemFilter, task))
                .toList();
        if (!visibleTasks.isEmpty()) {
            List<TaskRow> active = visibleTasks.stream().filter(TaskRow::running).toList();
            List<TaskRow> inactive = visibleTasks.stream().filter(task -> !task.running()).toList();
            List<TaskNode> sessionChildren = new ArrayList<>();
            if (!active.isEmpty() && !inactive.isEmpty()) {
                sessionChildren.add(new Section(SectionKind.ACTIVE, groupedByWorktree(active)));
                sessionChildren.add(new Section(SectionKind.INACTIVE, groupedByWorktree(inactive)));
            } else {
                sessionChildren.addAll(groupedByWorktree(visibleTasks));
            }
            children.add(new Section(SectionKind.SESSIONS, sessionChildren));
        }

        List<TaskNode> sessionlessWorktrees = new ArrayList<>();
        List<IdleWorktree> inactive = new ArrayList<>();
        List<IdleWorktree> stale = new ArrayList<>();
        for (Worktree worktree : in.worktreesByMainRoot().getOrDefault(main, List.of())) {
            if (worktree.mainCheckout() || worktree.prunable()
                    || tasks.stream().anyMatch(task -> task.session().workingDirectory().equals(worktree.path()))) {
                continue;
            }
            String branch = worktree.branch().orElse(String.valueOf(worktree.path().getFileName()));
            Optional<Instant> lastActivity = Optional.ofNullable(in.lastActivity().get(worktree.path()));
            boolean open = in.openFolders().contains(worktree.path());
            IdleWorktree idle = new IdleWorktree(worktree.path(), branch, lastActivity, worktree.locked(), open);
            inactive.add(idle);
            if (in.viewFilter() == ViewFilter.ALL && (itemFilter.isEmpty() || contains(branch, itemFilter))) {
                boolean isStale = !worktree.locked() && !open && lastActivity
                        .map(last -> last.isBefore(in.now().minus(STALE_AFTER)))
                        .orElse(false);
                if (isStale) {
                    stale.add(idle);
                } else {
                    sessionlessWorktrees.add(idle);
                }
            }
        }
        if (!stale.isEmpty()) {
            sessionlessWorktrees.add(new StaleWorktrees(stale));
        }
        if (in.viewFilter() == ViewFilter.ALL && in.worktreeContainers().contains(main)
                && !in.worktreesByMainRoot().containsKey(main)) {
            children.add(new Section(SectionKind.WORKTREES,
                    List.of(new TaskNode.Deferred("Loading worktrees…"))));
        } else if (!sessionlessWorktrees.isEmpty()) {
            children.add(new Section(SectionKind.WORKTREES, sessionlessWorktrees));
        }
        if (in.viewFilter() == ViewFilter.ALL || in.viewFilter() == ViewFilter.ISSUES) {
            issueChildren(in, main, itemFilter).ifPresent(issueChildren ->
                    children.add(new Section(SectionKind.ISSUES, issueChildren)));
        }
        if (in.viewFilter() == ViewFilter.ALL || in.viewFilter() == ViewFilter.PULL_REQUESTS) {
            if (in.pullRequestContainers().contains(main) && !in.pullRequestsByMainRoot().containsKey(main)) {
                children.add(new Section(SectionKind.PULL_REQUESTS,
                        List.of(new TaskNode.Deferred("Loading pull requests…"))));
            } else {
                pullRequestChildren(in, main, itemFilter).ifPresent(pullRequestChildren ->
                        children.add(new Section(SectionKind.PULL_REQUESTS, pullRequestChildren)));
            }
        }
        return new Children(List.copyOf(children), List.copyOf(inactive));
    }

    private static boolean visibleIn(ViewFilter filter, TaskRow task) {
        return switch (filter) {
            case ALL -> true;
            case ACTIVE -> task.running();
            case INACTIVE -> !task.running();
            // Needs you is live process + unacknowledged attention: a
            // stopped session's leftover activity never counts.
            case NEEDS_YOU -> task.running()
                    && task.activity().equals(Optional.of(SessionActivity.NEEDS_ATTENTION));
            case PULL_REQUESTS, ISSUES -> false;
        };
    }

    /**
     * The project's open, non-draft pull requests that no local worktree
     * already holds -- the same rule the standalone app's sidebar applies
     * (via {@link RepositoryPullRequests#selectable}, which also excludes
     * drafts). Newest first; gh's own order is repository-dependent.
     */
    private static Optional<List<TaskNode>> pullRequestChildren(Inputs in, Path main, String itemFilter) {
        return switch (in.pullRequestsByMainRoot().get(main)) {
            case PullRequestListing.Listed listed -> {
                List<TaskNode> rows = RepositoryPullRequests.selectable(
                                listed.pullRequests(), in.pullRequestDedupWorktrees().getOrDefault(main, List.of()))
                        .stream()
                        .filter(pullRequest -> matches(itemFilter, pullRequest))
                        .sorted(Comparator.comparingInt(OpenPullRequest::number).reversed())
                        .map(pullRequest -> (TaskNode) new PullRequestRow(main, pullRequest))
                        .toList();
                yield rows.isEmpty() ? Optional.empty() : Optional.of(rows);
            }
            case PullRequestListing.Failed failed -> Optional.of(List.of(
                    new TaskNode.ListProblem("GitHub pull requests: " + failed.message())));
            case PullRequestListing.Unsupported ignored -> Optional.empty();
            case null -> Optional.empty();
        };
    }

    /**
     * The project's Issues section children: the GitHub node (or its ghost),
     * then the Jira node (or its ghost). Nothing here fetches — the nodes'
     * listings arrive only when the user opens them, and the section's own
     * expansion is pure tree state.
     *
     * <p>A GitHub issues toggle switched off shows nothing for GitHub, and
     * an absent source shows nothing at all — but an enabled-but-unusable
     * one (no detectable GitHub remote, a disabled Jira connection) shows a
     * ghost that opens that source's settings. Empty sections are never
     * emitted.</p>
     */
    private static Optional<List<TaskNode>> issueChildren(Inputs in, Path main, String itemFilter) {
        List<TaskNode> children = new ArrayList<>();
        if (in.githubGhostRoots().contains(main)) {
            children.add(new Ghost(main, GhostSource.GITHUB));
        } else if (in.githubIssueRoots().contains(main)) {
            githubNodeChildren(in, main, itemFilter)
                    .ifPresent(rows -> children.add(new GitHubNode(main, rows)));
        }
        if (in.jiraGhostRoots().contains(main)) {
            children.add(new Ghost(main, GhostSource.JIRA));
        } else if (in.jiraRoots().contains(main)) {
            jiraNodeChildren(in, main, itemFilter)
                    .ifPresent(rows -> children.add(new JiraNode(main, rows)));
        }
        if (children.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(children));
    }

    /**
     * The GitHub node's children: its rows newest {@code updatedAt} first,
     * or the one child that says why there are none — never requested
     * ({@code Not loaded}), loading, failed, or GitHub's own "issues are
     * disabled" answer. A node whose search filter matches nothing inside
     * is omitted; its name matching the filter keeps the whole node (its
     * listing is the answer, as with a name-matched project).
     */
    private static Optional<List<TaskNode>> githubNodeChildren(Inputs in, Path main, String itemFilter) {
        boolean nodeMatches = itemFilter.isEmpty() || contains("GitHub", itemFilter);
        List<TaskNode> children = new ArrayList<>();
        switch (in.issuesByMainRoot().get(main)) {
            case null -> children.add(new TaskNode.Placeholder(NOT_LOADED));
            case IssueListing.Listed listed -> listed.issues().stream()
                    .filter(issue -> nodeMatches || matches(itemFilter, issue))
                    .forEach(issue -> children.add(new IssueRow(main, issue)));
            case IssueListing.Failed failed ->
                    children.add(new TaskNode.ListProblem("GitHub issues: " + failed.message()));
            case IssueListing.Unsupported ignored ->
                    children.add(new TaskNode.Placeholder("Issues are disabled for this repository"));
        }
        if (children.isEmpty() && in.issuesByMainRoot().get(main) instanceof IssueListing.Listed) {
            if (!nodeMatches) {
                return Optional.empty();
            }
            children.add(new TaskNode.Placeholder(NO_ISSUES));
        }
        if (children.stream().allMatch(IssueRow.class::isInstance)) {
            children.sort(Comparator.comparing((TaskNode row) -> rowUpdatedAt(row).orElse(Instant.EPOCH))
                    .reversed());
        }
        return Optional.of(List.copyOf(children));
    }

    /**
     * The Jira node's children: one node per user-defined group, in the
     * order they were defined, whatever their listings have answered — a
     * group's definition travels unfetched so it can be edited or deleted.
     * A group whose search filter matches nothing inside is omitted by
     * {@link #jiraGroupChildren}; a node whose groups are all filtered out
     * (or whose project defines none, with a filter that does not name it)
     * is omitted like any other empty row — but a filterless project with
     * no groups keeps the node with an empty-state child, the place its
     * context menu offers adding one.
     */
    private static Optional<List<TaskNode>> jiraNodeChildren(Inputs in, Path main, String itemFilter) {
        boolean nodeMatches = itemFilter.isEmpty() || contains("Jira", itemFilter);
        List<JiraGroups.Listing> groups = in.jiraGroupsByMainRoot().getOrDefault(main, List.of());
        List<TaskNode> children = groups.stream()
                .map(listing -> jiraGroupChildren(in, main, listing, itemFilter))
                .flatMap(Optional::stream)
                .toList();
        if (children.isEmpty()) {
            if (!nodeMatches) {
                return Optional.empty();
            }
            return Optional.of(List.of(new TaskNode.Placeholder("No Jira groups")));
        }
        return Optional.of(children);
    }

    /**
     * One group's node, or empty when the search filter leaves it nothing
     * to show: the group's name matching the filter keeps the whole group
     * (its query is the answer, as with a name-matched project), otherwise
     * the rows inside are filtered like any other row. A failed query
     * stays visible under any filter — its problem row is diagnostics,
     * and the same rule keeps GitHub's problem rows unfiltered.
     */
    private static Optional<TaskNode> jiraGroupChildren(Inputs in, Path main, JiraGroups.Listing listing,
                                                        String itemFilter) {
        boolean groupMatches = itemFilter.isEmpty() || contains(listing.group().name(), itemFilter);
        List<TaskNode> children = new ArrayList<>();
        switch (listing.listing().orElse(null)) {
            case null -> children.add(new TaskNode.Placeholder(NOT_LOADED));
            case JiraIssueListing.Failed failed ->
                    children.add(new TaskNode.ListProblem(
                            "Jira group \"" + listing.group().name() + "\": " + failed.message()));
            case JiraIssueListing.Unsupported ignored -> { }
            case JiraIssueListing.Listed listed -> listed.issues().stream()
                    .filter(issue -> groupMatches || matches(itemFilter, issue))
                    .forEach(issue -> children.add(new JiraIssueRow(main, issue)));
        }
        if (children.isEmpty() && listing.listing().orElse(null) instanceof JiraIssueListing.Listed) {
            if (!groupMatches) {
                return Optional.empty();
            }
            children.add(new TaskNode.Placeholder(NO_ISSUES));
        }
        return Optional.of(new JiraGroupRow(main, listing.group(), children));
    }

    /** The row's issue timestamp, whatever source the row came from. */
    private static Optional<Instant> rowUpdatedAt(TaskNode row) {
        return switch (row) {
            case IssueRow issue -> issue.issue().updatedAt();
            case JiraIssueRow jira -> jira.issue().updatedAt();
            default -> Optional.empty();
        };
    }

    /** Groups multiple sessions sharing a worktree; the given order decides group order and task order. */
    private static List<TaskNode> groupedByWorktree(List<TaskRow> tasks) {
        Map<Path, List<TaskRow>> byWorktree = new LinkedHashMap<>();
        for (TaskRow task : tasks) {
            byWorktree.computeIfAbsent(task.session().workingDirectory(), ignored -> new ArrayList<>()).add(task);
        }
        List<TaskNode> grouped = new ArrayList<>();
        byWorktree.forEach((worktree, rows) -> {
            if (rows.size() == 1) {
                grouped.add(rows.get(0));
            } else {
                grouped.add(new WorktreeGroup(worktree, rows.get(0).branch().orElse(String.valueOf(
                        worktree.getFileName())), rows));
            }
        });
        return grouped;
    }

    private static List<TaskRow> sorted(List<TaskRow> tasks) {
        List<TaskRow> copy = new ArrayList<>(tasks);
        copy.sort(Comparator.comparingInt(TaskTreeModel::rank)
                .thenComparing((TaskRow task) -> task.session().lastOpenedAt(), Comparator.reverseOrder()));
        return copy;
    }

    /** Needs you, then running, then the rest. */
    private static int rank(TaskRow task) {
        if (task.running() && task.activity().equals(Optional.of(SessionActivity.NEEDS_ATTENTION))) {
            return 0;
        }
        return task.running() ? 1 : 2;
    }

    private static boolean matches(String filter, TaskRow task) {
        return filter.isEmpty() || contains(task.session().displayName(), filter)
                || task.branch().map(branch -> contains(branch, filter)).orElse(false);
    }

    private static boolean matches(String filter, OpenIssue issue) {
        return filter.isEmpty() || contains(issue.title(), filter)
                || contains("#" + issue.number(), filter)
                || issue.labels().stream().anyMatch(label -> contains(label, filter));
    }

    private static boolean matches(String filter, OpenPullRequest pullRequest) {
        return filter.isEmpty() || contains(pullRequest.title(), filter)
                || contains("#" + pullRequest.number(), filter)
                || contains(pullRequest.headRefName(), filter);
    }

    /** A Jira row matches on its key, its summary or any label, case-insensitively. */
    private static boolean matches(String filter, JiraIssue issue) {
        return filter.isEmpty() || contains(issue.key(), filter)
                || contains(issue.summary(), filter)
                || issue.labels().stream().anyMatch(label -> contains(label, filter));
    }

    private static boolean contains(String text, String filter) {
        return text.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }
}
