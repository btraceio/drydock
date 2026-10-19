package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.tasks.TaskNode.AddCurrentProject;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.tasks.TaskNode.WorktreeGroup;
import app.drydock.projects.SharedProject;

import java.nio.file.Path;
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
 * registered projects, and under each its tasks, grouped by worktree.
 *
 * <p>A task (an agent session) is placed by where it works, not by the
 * repository id it was recorded under: its working directory resolves to a
 * main checkout ({@code mainRootOf}, from {@code git worktree list}), and
 * that main checkout's registered project is its parent. When git can no
 * longer resolve it — a worktree removed outside Drydock — the longest
 * registered root containing it takes it, so a task never just vanishes.
 * Sessions are placed this way because the plugin's catalog records each
 * worktree as its own repository, and because a project removed and added
 * again must get its old tasks back.</p>
 *
 * <p>Every path in {@link Inputs} is expected to be canonical already;
 * this class does no I/O.</p>
 */
public final class TaskTreeModel {

    private TaskTreeModel() {
    }

    /**
     * Everything the tree is built from.
     *
     * @param mainRootOf      canonical working directory or worktree → its canonical main checkout
     * @param currentMainRoot this window's project's main checkout, if it has one
     * @param currentWorkingRoot this window's project directory (a worktree or the main checkout)
     * @param missingFolders  registered roots whose folder does not exist
     */
    public record Inputs(List<SharedProject> projects, List<ManagedAgentSession> sessions,
                         Map<Path, List<Worktree>> worktreesByMainRoot, Map<Path, Path> mainRootOf,
                         Optional<Path> currentMainRoot, Optional<Path> currentWorkingRoot,
                         Map<ManagedSessionId, Optional<SessionActivity>> activity, Set<ManagedSessionId> running,
                         Set<Path> missingFolders, String filter) {
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
            filter = filter == null ? "" : filter.strip();
        }
    }

    public static List<TaskNode> build(Inputs in) {
        List<SharedProject> local = in.projects().stream().filter(project -> !project.isRemote()).toList();
        Map<Path, List<TaskRow>> tasksByMain = new LinkedHashMap<>();
        for (ManagedAgentSession session : in.sessions()) {
            if (session.agentKind() == AgentKind.TERMINAL) {
                continue;
            }
            placement(in, local, session.workingDirectory()).ifPresent(main ->
                    tasksByMain.computeIfAbsent(main, ignored -> new ArrayList<>()).add(row(in, main, session)));
        }

        List<TaskNode> nodes = new ArrayList<>();
        Optional<Path> current = in.currentMainRoot();
        if (current.isPresent() && local.stream().noneMatch(project -> project.root().equals(current.get()))) {
            Path root = current.get();
            nodes.add(new AddCurrentProject(root, String.valueOf(root.getFileName())));
            sorted(tasksByMain.getOrDefault(root, List.of())).stream()
                    .filter(task -> matches(in.filter(), task))
                    .forEach(nodes::add);
        }

        List<SharedProject> ordered = new ArrayList<>(local);
        ordered.sort(Comparator.comparing((SharedProject project) -> !project.root().equals(current.orElse(null)))
                .thenComparing(project -> project.displayName().toLowerCase(Locale.ROOT)));
        for (SharedProject project : ordered) {
            boolean thisWindow = project.root().equals(current.orElse(null));
            List<TaskNode> children = children(in, project.root(), tasksByMain.getOrDefault(project.root(), List.of()));
            if (!in.filter().isEmpty() && children.isEmpty() && !thisWindow) {
                continue;
            }
            nodes.add(new ProjectRow(project, in.missingFolders().contains(project.root()), thisWindow, children));
        }
        return nodes;
    }

    /** The main checkout {@code workingDirectory} belongs to, if any registered or current project takes it. */
    private static Optional<Path> placement(Inputs in, List<SharedProject> local, Path workingDirectory) {
        Path main = in.mainRootOf().get(workingDirectory);
        if (main == null) {
            main = local.stream()
                    .map(SharedProject::root)
                    .filter(workingDirectory::startsWith)
                    .max(Comparator.comparingInt(Path::getNameCount))
                    .orElse(null);
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
        Map<Path, List<TaskRow>> byWorktree = new LinkedHashMap<>();
        for (TaskRow task : sorted(tasks)) {
            if (matches(in.filter(), task)) {
                byWorktree.computeIfAbsent(task.session().workingDirectory(), ignored -> new ArrayList<>()).add(task);
            }
        }
        List<TaskNode> children = new ArrayList<>();
        // Groups follow their best task, so the sort of the tasks above
        // already orders them.
        byWorktree.forEach((worktree, rows) -> {
            if (rows.size() == 1) {
                children.add(rows.get(0));
            } else {
                children.add(new WorktreeGroup(worktree, rows.get(0).branch().orElse(String.valueOf(
                        worktree.getFileName())), rows));
            }
        });
        for (Worktree worktree : in.worktreesByMainRoot().getOrDefault(main, List.of())) {
            if (worktree.mainCheckout() || worktree.prunable() || byWorktree.containsKey(worktree.path())
                    || tasks.stream().anyMatch(task -> task.session().workingDirectory().equals(worktree.path()))) {
                continue;
            }
            String branch = worktree.branch().orElse(String.valueOf(worktree.path().getFileName()));
            if (in.filter().isEmpty() || contains(branch, in.filter())) {
                children.add(new IdleWorktree(worktree.path(), branch));
            }
        }
        return children;
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

    private static boolean contains(String text, String filter) {
        return text.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }
}
