package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.git.GhCliService.IssueListing;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.tasks.TaskNode.AddCurrentProject;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.IssueRow;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.SectionKind;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.tasks.TaskNode.WorktreeGroup;
import app.drydock.projects.SharedProject;

import java.nio.file.Path;
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

    /** Mutually exclusive quick views; text search is applied within the selected view. */
    public enum ViewFilter {
        ALL("All"),
        ACTIVE("Active"),
        INACTIVE("Inactive"),
        ISSUES("Issues");

        private final String label;

        ViewFilter(String label) {
            this.label = label;
        }

        public String label() {
            return label;
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
    public record Inputs(List<SharedProject> projects, List<ManagedAgentSession> sessions,
                         Map<Path, List<Worktree>> worktreesByMainRoot, Map<Path, Path> mainRootOf,
                         Optional<Path> currentMainRoot, Optional<Path> currentWorkingRoot,
                         Map<ManagedSessionId, Optional<SessionActivity>> activity, Set<ManagedSessionId> running,
                         Set<Path> missingFolders, Map<RepositoryId, Path> recordedRoots,
                         Map<Path, IssueListing> issuesByMainRoot, Optional<String> listProblem, String filter,
                         ViewFilter viewFilter) {
        public Inputs {
            projects = List.copyOf(projects);
            sessions = List.copyOf(sessions);
            worktreesByMainRoot = Map.copyOf(worktreesByMainRoot);
            mainRootOf = Map.copyOf(mainRootOf);
            Objects.requireNonNull(currentMainRoot, "currentMainRoot");
            Objects.requireNonNull(currentWorkingRoot, "currentWorkingRoot");
            activity = Map.copyOf(activity);
            running = Set.copyOf(running);
            missingFolders = Set.copyOf(missingFolders);
            recordedRoots = Map.copyOf(recordedRoots);
            issuesByMainRoot = Map.copyOf(issuesByMainRoot);
            Objects.requireNonNull(listProblem, "listProblem");
            filter = filter == null ? "" : filter.strip();
            Objects.requireNonNull(viewFilter, "viewFilter");
        }

        /** Compatibility constructor for callers that want the unfiltered full tree. */
        public Inputs(List<SharedProject> projects, List<ManagedAgentSession> sessions,
                      Map<Path, List<Worktree>> worktreesByMainRoot, Map<Path, Path> mainRootOf,
                      Optional<Path> currentMainRoot, Optional<Path> currentWorkingRoot,
                      Map<ManagedSessionId, Optional<SessionActivity>> activity, Set<ManagedSessionId> running,
                      Set<Path> missingFolders, Map<RepositoryId, Path> recordedRoots,
                      Map<Path, IssueListing> issuesByMainRoot, Optional<String> listProblem, String filter) {
            this(projects, sessions, worktreesByMainRoot, mainRootOf, currentMainRoot, currentWorkingRoot,
                    activity, running, missingFolders, recordedRoots, issuesByMainRoot, listProblem, filter,
                    ViewFilter.ALL);
        }

        /** Compatibility constructor for model callers with no GitHub issue input. */
        public Inputs(List<SharedProject> projects, List<ManagedAgentSession> sessions,
                      Map<Path, List<Worktree>> worktreesByMainRoot, Map<Path, Path> mainRootOf,
                      Optional<Path> currentMainRoot, Optional<Path> currentWorkingRoot,
                      Map<ManagedSessionId, Optional<SessionActivity>> activity, Set<ManagedSessionId> running,
                      Set<Path> missingFolders, Map<RepositoryId, Path> recordedRoots,
                      Optional<String> listProblem, String filter) {
            this(projects, sessions, worktreesByMainRoot, mainRootOf, currentMainRoot, currentWorkingRoot,
                    activity, running, missingFolders, recordedRoots, Map.of(), listProblem, filter, ViewFilter.ALL);
        }
    }

    public static List<TaskNode> build(Inputs in) {
        List<SharedProject> local = in.projects().stream().filter(project -> !project.isRemote()).toList();
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
            List<TaskRow> visible = sorted(tasksByMain.getOrDefault(root, List.of())).stream()
                    .filter(task -> visibleIn(in.viewFilter(), task))
                    .filter(task -> matches(in.filter(), task))
                    .toList();
            if (in.viewFilter() == ViewFilter.ALL || !visible.isEmpty()) {
                nodes.add(new AddCurrentProject(root, String.valueOf(root.getFileName())));
                nodes.addAll(visible);
            }
        }

        List<SharedProject> ordered = new ArrayList<>(local);
        ordered.sort(Comparator.comparing((SharedProject project) -> !project.root().equals(current.orElse(null)))
                .thenComparing(project -> project.displayName().toLowerCase(Locale.ROOT)));
        for (SharedProject project : ordered) {
            boolean thisWindow = project.root().equals(current.orElse(null));
            List<TaskNode> children = children(in, project.root(), tasksByMain.getOrDefault(project.root(), List.of()));
            if (children.isEmpty() && (in.viewFilter() != ViewFilter.ALL
                    || !in.filter().isEmpty() && !thisWindow)) {
                continue;
            }
            int running = (int) tasksByMain.getOrDefault(project.root(), List.of()).stream()
                    .filter(TaskRow::running).count();
            nodes.add(new ProjectRow(project, in.missingFolders().contains(project.root()), thisWindow, children,
                    running));
        }
        return nodes;
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
        boolean thisWindow = in.currentWorkingRoot().map(directory::equals).orElse(false);
        return new TaskRow(session, in.activity().getOrDefault(session.id(), Optional.empty()),
                in.running().contains(session.id()), branch, thisWindow, session.readOnly());
    }

    private static List<TaskNode> children(Inputs in, Path main, List<TaskRow> tasks) {
        List<TaskNode> children = new ArrayList<>();
        List<TaskRow> visibleTasks = sorted(tasks).stream()
                .filter(task -> visibleIn(in.viewFilter(), task))
                .filter(task -> matches(in.filter(), task))
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

        if (in.viewFilter() == ViewFilter.ALL) {
            List<TaskNode> sessionlessWorktrees = new ArrayList<>();
            for (Worktree worktree : in.worktreesByMainRoot().getOrDefault(main, List.of())) {
                if (worktree.mainCheckout() || worktree.prunable()
                        || tasks.stream().anyMatch(task -> task.session().workingDirectory().equals(worktree.path()))) {
                    continue;
                }
                String branch = worktree.branch().orElse(String.valueOf(worktree.path().getFileName()));
                if (in.filter().isEmpty() || contains(branch, in.filter())) {
                    sessionlessWorktrees.add(new IdleWorktree(worktree.path(), branch));
                }
            }
            if (!sessionlessWorktrees.isEmpty()) {
                children.add(new Section(SectionKind.WORKTREES, sessionlessWorktrees));
            }
        }
        if (in.viewFilter() == ViewFilter.ALL || in.viewFilter() == ViewFilter.ISSUES) {
            issueChildren(in, main).ifPresent(issueChildren ->
                    children.add(new Section(SectionKind.ISSUES, issueChildren)));
        }
        return children;
    }

    private static boolean visibleIn(ViewFilter filter, TaskRow task) {
        return switch (filter) {
            case ALL -> true;
            case ACTIVE -> task.running();
            case INACTIVE -> !task.running();
            case ISSUES -> false;
        };
    }

    private static Optional<List<TaskNode>> issueChildren(Inputs in, Path main) {
        return switch (in.issuesByMainRoot().get(main)) {
            case IssueListing.Listed listed -> {
                List<TaskNode> rows = listed.issues().stream()
                        .filter(issue -> matches(in.filter(), issue))
                        .sorted(Comparator.comparing((OpenIssue issue) -> issue.updatedAt().orElse(Instant.EPOCH))
                                .reversed())
                        .map(issue -> (TaskNode) new IssueRow(main, issue))
                        .toList();
                yield rows.isEmpty() ? Optional.empty() : Optional.of(rows);
            }
            case IssueListing.Failed failed -> Optional.of(List.of(
                    new TaskNode.ListProblem("GitHub issues: " + failed.message())));
            case IssueListing.Unsupported ignored -> Optional.empty();
            case null -> Optional.empty();
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

    private static boolean contains(String text, String filter) {
        return text.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }
}
