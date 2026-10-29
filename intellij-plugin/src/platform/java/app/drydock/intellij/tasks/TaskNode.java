package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.SessionActivity;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.GhCliService.OpenPullRequest;
import app.drydock.git.JiraIssue;
import app.drydock.intellij.agent.SessionCommands;
import app.drydock.projects.SharedProject;

import java.nio.file.Path;
import java.time.Instant;
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
        ISSUES("Issues"),
        PULL_REQUESTS("Pull Requests");

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
                      List<IdleWorktree> inactiveWorktrees, int running) implements TaskNode {
        public ProjectRow {
            Objects.requireNonNull(project, "project");
            children = List.copyOf(children);
            inactiveWorktrees = List.copyOf(inactiveWorktrees);
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

    /** A lazy container's single child until opening it starts the background read. */
    record Deferred(String message) implements TaskNode {
        public Deferred {
            Objects.requireNonNull(message, "message");
        }
    }

    /** A muted non-actionable line, e.g. a group whose query returned nothing. */
    record Placeholder(String text) implements TaskNode {
        public Placeholder {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * One user-defined Jira group: the named subnode of a project's Issues
     * section whose JQL scopes its rows. Jira rows appear nowhere else — a
     * project whose {@link JiraGroups} is empty shows no Jira content. The
     * children are Jira rows, or a single loading, problem or empty-state
     * child; a group whose search filter matches nothing inside is omitted.
     */
    record JiraGroupRow(Path mainRoot, JiraGroup group, List<TaskNode> children) implements TaskNode {
        public JiraGroupRow {
            Objects.requireNonNull(mainRoot, "mainRoot");
            Objects.requireNonNull(group, "group");
            children = List.copyOf(children);
            if (children.isEmpty()) {
                throw new IllegalArgumentException("a Jira group must not be empty");
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

    /** Inactive worktrees whose last known content activity is older than the stale threshold. */
    record StaleWorktrees(List<IdleWorktree> worktrees) implements TaskNode {
        public StaleWorktrees {
            worktrees = List.copyOf(worktrees);
            if (worktrees.isEmpty()) {
                throw new IllegalArgumentException("a stale group must not be empty");
            }
        }
    }

    /**
     * One task: an agent session, and where its agent is shown.
     * {@code folderMissing}: it runs, and its worktree folder is gone.
     * {@code resumable}: it is stopped and has a conversation to resume into
     * (the model's answer, warmed by {@link ResumabilityCache}); always
     * {@code false} while it runs.
     */
    record TaskRow(ManagedAgentSession session, Optional<SessionActivity> activity, boolean running,
                   Optional<String> branch, TaskPlacement placement, boolean readOnly, boolean folderMissing,
                   boolean resumable)
            implements TaskNode {
        public TaskRow {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(activity, "activity");
            Objects.requireNonNull(branch, "branch");
            Objects.requireNonNull(placement, "placement");
        }

        /**
         * The shape before the model carried the warmed answer: resumable is
         * the cold fallback ({@link SessionCommands#resumable} with no known
         * answer), which keeps Resume for a session that has an agent session
         * id the provider was never asked about.
         */
        public TaskRow(ManagedAgentSession session, Optional<SessionActivity> activity, boolean running,
                       Optional<String> branch, TaskPlacement placement, boolean readOnly, boolean folderMissing) {
            this(session, activity, running, branch, placement, readOnly, folderMissing,
                    SessionCommands.resumable(Optional.empty(), session.agentSessionId().isPresent()));
        }

        /** Whether the task works in this window's own worktree, running here or idle. */
        public boolean isThisWindow() {
            return placement instanceof TaskPlacement.ThisWindow
                    || placement instanceof TaskPlacement.Idle idle && idle.thisWindow();
        }
    }

    /** A worktree found on disk with no task: offers {@code Start ▸}. */
    record IdleWorktree(Path worktree, String branch, Optional<Instant> lastActivity, boolean locked, boolean open)
            implements TaskNode {
        public IdleWorktree {
            Objects.requireNonNull(worktree, "worktree");
            Objects.requireNonNull(branch, "branch");
            Objects.requireNonNull(lastActivity, "lastActivity");
        }
    }

    /** One open GitHub issue belonging to the containing registered project. */
    record IssueRow(Path mainRoot, OpenIssue issue) implements TaskNode {
        public IssueRow {
            Objects.requireNonNull(mainRoot, "mainRoot");
            Objects.requireNonNull(issue, "issue");
        }
    }

    /** One open Jira issue belonging to the containing registered project, in the same Issues section as GitHub rows. */
    record JiraIssueRow(Path mainRoot, JiraIssue issue) implements TaskNode {
        public JiraIssueRow {
            Objects.requireNonNull(mainRoot, "mainRoot");
            Objects.requireNonNull(issue, "issue");
        }
    }

    /**
     * One open pull request belonging to the containing registered project:
     * not a draft, and not already checked out in one of the repository's
     * worktrees (a checked-out PR is its worktree's row, the same rule the
     * standalone app's sidebar follows).
     */
    record PullRequestRow(Path mainRoot, OpenPullRequest pullRequest) implements TaskNode {
        public PullRequestRow {
            Objects.requireNonNull(mainRoot, "mainRoot");
            Objects.requireNonNull(pullRequest, "pullRequest");
        }
    }
}
