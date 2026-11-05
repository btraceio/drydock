package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.intellij.tasks.TaskNode.ListProblem;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskTreePanel.TaskLayer;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.TaskNode.TasksSection;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The tree's single-project focus, as pure functions over built model rows:
 * which top-level rows a focus keeps, and which project row holds a session
 * that the focus hides.
 */
final class ProjectFocus {

    private ProjectFocus() {
    }

    /**
     * The top-level rows a focus on {@code root} keeps: the list problem, if
     * any, and the registered project row whose main root is {@code root}.
     * Every other project row goes, and so do the current window's
     * unregistered-project rows (its "add" row and the loose task rows under
     * it): a focus is always on a registered project.
     */
    static List<TaskNode> focused(List<TaskNode> nodes, Path root) {
        return nodes.stream()
                .filter(node -> node instanceof ListProblem
                        || node instanceof ProjectRow row && row.project().root().equals(root))
                .toList();
    }

    /** The project row whose subtree holds a session row of {@code sessionId}, if any. */
    static Optional<ProjectRow> projectOf(List<TaskNode> nodes, ManagedSessionId sessionId) {
        String key = sessionId.toString();
        return nodes.stream()
                .filter(ProjectRow.class::isInstance)
                .map(ProjectRow.class::cast)
                .filter(row -> TaskTreePanel.sessionRowsOf(List.of(row)).containsKey(key))
                .findFirst();
    }

    /**
     * Whether a delayed task of {@code repository} belongs to the focused
     * project: its recorded root is the focused main root, or a worktree
     * whose main checkout is. {@code rootsByRepository} maps each repository
     * to its canonical root, {@code mainRootOf} each canonical worktree to its
     * main checkout.
     */
    static boolean inFocus(RepositoryId repository, Path root, Map<RepositoryId, Path> rootsByRepository,
                           Map<Path, Path> mainRootOf) {
        Path recorded = rootsByRepository.get(repository);
        return recorded != null && (recorded.equals(root) || root.equals(mainRootOf.get(recorded)));
    }

    /**
     * The delayed-task layer under a focus: the Tasks section keeps only the
     * tasks of repositories {@code inFocus} accepts, and goes when none is
     * left. Tasks attached to session rows pass through unchanged; only the
     * session rows the focus keeps are drawn.
     */
    static TaskLayer focused(TaskLayer layer, Predicate<RepositoryId> inFocus) {
        List<TasksSection> sections = layer.sections().stream()
                .map(section -> new TasksSection(section.label(), section.children().stream()
                        .filter(child -> !(child instanceof DelayedTaskRow row) || inFocus.test(row.repository()))
                        .toList()))
                .filter(section -> !section.children().isEmpty())
                .toList();
        return new TaskLayer(sections, layer.attachedBySession());
    }
}
