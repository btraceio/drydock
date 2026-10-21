package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.SessionActivity;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.projects.SharedProject;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One row of the Drydock tool window's tree, as {@link TaskTreeModel} decides it. */
public sealed interface TaskNode {

    /** Semantic folders under a registered project, and the optional active/inactive split under Sessions. */
    enum SectionKind {
        SESSIONS("Sessions"),
        ACTIVE("Active"),
        INACTIVE("Inactive"),
        WORKTREES("Worktrees"),
        ISSUES("Issues");

        private final String label;

        SectionKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** The shared projects list cannot be changed; says why (malformed, unreadable entries, newer schema). */
    record ListProblem(String message) implements TaskNode {
        public ListProblem {
            Objects.requireNonNull(message, "message");
        }
    }

    /** {@code <repo> isn't in Drydock · Add}: this window's project is not registered. */
    record AddCurrentProject(Path mainRoot, String repoName) implements TaskNode {
        public AddCurrentProject {
            Objects.requireNonNull(mainRoot, "mainRoot");
            Objects.requireNonNull(repoName, "repoName");
        }
    }

    /**
     * A registered project and what is under it. {@code running} counts its
     * running tasks before any search filter, for questions that must not
     * depend on what the search box shows.
     */
    record ProjectRow(SharedProject project, boolean folderMissing, boolean isThisWindow, List<TaskNode> children,
                      int running) implements TaskNode {
        public ProjectRow {
            Objects.requireNonNull(project, "project");
            children = List.copyOf(children);
        }
    }

    /** A semantic, non-actionable folder. Empty sections are never emitted by the model. */
    record Section(SectionKind kind, List<TaskNode> children) implements TaskNode {
        public Section {
            Objects.requireNonNull(kind, "kind");
            children = List.copyOf(children);
            if (children.isEmpty()) {
                throw new IllegalArgumentException("section must not be empty");
            }
        }
    }

    /** A worktree with more than one task: {@code ⑂ <branch>} with its tasks beneath. */
    record WorktreeGroup(Path worktree, String branch, List<TaskRow> tasks) implements TaskNode {
        public WorktreeGroup {
            Objects.requireNonNull(worktree, "worktree");
            Objects.requireNonNull(branch, "branch");
            tasks = List.copyOf(tasks);
        }
    }

    /**
     * One task: an agent session, and where its agent is shown.
     * {@code folderMissing}: it runs, and its worktree folder is gone.
     */
    record TaskRow(ManagedAgentSession session, Optional<SessionActivity> activity, boolean running,
                   Optional<String> branch, TaskPlacement placement, boolean readOnly, boolean folderMissing)
            implements TaskNode {
        public TaskRow {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(activity, "activity");
            Objects.requireNonNull(branch, "branch");
            Objects.requireNonNull(placement, "placement");
        }

        /** Whether the task works in this window's own worktree, running here or idle. */
        public boolean isThisWindow() {
            return placement instanceof TaskPlacement.ThisWindow
                    || placement instanceof TaskPlacement.Idle idle && idle.thisWindow();
        }
    }

    /** A worktree found on disk with no task: offers {@code Start ▸}. */
    record IdleWorktree(Path worktree, String branch) implements TaskNode {
        public IdleWorktree {
            Objects.requireNonNull(worktree, "worktree");
            Objects.requireNonNull(branch, "branch");
        }
    }

    /** One open GitHub issue belonging to the containing registered project. */
    record IssueRow(Path mainRoot, OpenIssue issue) implements TaskNode {
        public IssueRow {
            Objects.requireNonNull(mainRoot, "mainRoot");
            Objects.requireNonNull(issue, "issue");
        }
    }
}
