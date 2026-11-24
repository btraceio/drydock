package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.SessionActivity;
import app.drydock.git.BranchRef;
import app.drydock.git.GhCliService.BranchPullRequest;
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
        BRANCHES("Branches"),
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
     * The project's GitHub issues container, the first child of its Issues
     * section. Its listing is fetched only when the user opens the node —
     * never with the section, never with a view. The children are GitHub
     * rows, or a single not-loaded, loading, problem or empty-state child;
     * a node whose search filter matches nothing inside is omitted.
     */
    record GitHubNode(Path mainRoot, List<TaskNode> children) implements TaskNode {
        public GitHubNode {
            Objects.requireNonNull(mainRoot, "mainRoot");
            children = List.copyOf(children);
            if (children.isEmpty()) {
                throw new IllegalArgumentException("a GitHub node must not be empty");
            }
        }
    }

    /**
     * The project's Jira container, the second child of its Issues section,
     * shown whenever the project's Jira connection is enabled. Expanding it
     * fetches nothing: its children are the user-defined groups as stored
     * (or a single empty-state child), each fetching only when the group
     * itself is opened.
     */
    record JiraNode(Path mainRoot, List<TaskNode> children) implements TaskNode {
        public JiraNode {
            Objects.requireNonNull(mainRoot, "mainRoot");
            children = List.copyOf(children);
            if (children.isEmpty()) {
                throw new IllegalArgumentException("a Jira node must not be empty");
            }
        }
    }

    /**
     * A source the project cannot list issues from yet: a muted row naming
     * the source, whose click opens that source's settings. No children, no
     * loading — a ghost never fetches.
     */
    record Ghost(Path mainRoot, GhostSource source) implements TaskNode {
        public Ghost {
            Objects.requireNonNull(mainRoot, "mainRoot");
            Objects.requireNonNull(source, "source");
        }
    }

    /** The source a {@link Ghost} points at, deciding its text and its settings dialog. */
    enum GhostSource {
        GITHUB, JIRA
    }

    /**
     * One user-defined Jira group: a subnode of the project's Jira node
     * whose JQL scopes its rows. Jira rows appear nowhere else — a project
     * whose {@link JiraGroups} is empty shows no Jira content. The children
     * are Jira rows, or a single not-loaded, problem or empty-state child;
     * a group whose search filter matches nothing inside is omitted. A
     * group's listing is fetched only when the user opens the group.
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
     * {@code false} while it runs. {@code branchPullRequest}: the GitHub
     * pull request whose head is the session's current branch, when the
     * project lists pull requests and {@code gh} has answered.
     * {@code babysit}: the session's part in a babysitting engagement
     * ({@link SessionBabysit}), joined from the same state snapshot the
     * rebuild read its sessions from. {@code babysitters}: the active
     * babysitter sessions watching this session's pull request, nested
     * beneath it by the model — never a row of their own; empty for a
     * session nothing babysits.
     */
    record TaskRow(ManagedAgentSession session, Optional<SessionActivity> activity, boolean running,
                   Optional<String> branch, TaskPlacement placement, boolean readOnly, boolean folderMissing,
                   boolean resumable, Optional<BranchPullRequest> branchPullRequest,
                   Optional<SessionBabysit> babysit, List<TaskRow> babysitters)
            implements TaskNode {
        public TaskRow {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(activity, "activity");
            Objects.requireNonNull(branch, "branch");
            Objects.requireNonNull(placement, "placement");
            Objects.requireNonNull(branchPullRequest, "branchPullRequest");
            Objects.requireNonNull(babysit, "babysit");
            Objects.requireNonNull(babysitters, "babysitters");
            babysitters = List.copyOf(babysitters);
        }

        /** The shape before the model nested babysitters: a session is never a parent. */
        public TaskRow(ManagedAgentSession session, Optional<SessionActivity> activity, boolean running,
                       Optional<String> branch, TaskPlacement placement, boolean readOnly, boolean folderMissing,
                       boolean resumable, Optional<BranchPullRequest> branchPullRequest,
                       Optional<SessionBabysit> babysit) {
            this(session, activity, running, branch, placement, readOnly, folderMissing, resumable,
                    branchPullRequest, babysit, List.of());
        }

        /** This row with {@code babysitters} nested beneath it; everything else unchanged. */
        public TaskRow withBabysitters(List<TaskRow> babysitters) {
            return babysitters.isEmpty() && this.babysitters.isEmpty() ? this
                    : new TaskRow(session, activity, running, branch, placement, readOnly, folderMissing,
                            resumable, branchPullRequest, babysit, babysitters);
        }

        /** The shape before the model carried babysitting: no engagement. */
        public TaskRow(ManagedAgentSession session, Optional<SessionActivity> activity, boolean running,
                       Optional<String> branch, TaskPlacement placement, boolean readOnly, boolean folderMissing,
                       boolean resumable, Optional<BranchPullRequest> branchPullRequest) {
            this(session, activity, running, branch, placement, readOnly, folderMissing, resumable,
                    branchPullRequest, Optional.empty());
        }

        /** The shape before the model carried a branch's pull request: none known. */
        public TaskRow(ManagedAgentSession session, Optional<SessionActivity> activity, boolean running,
                       Optional<String> branch, TaskPlacement placement, boolean readOnly, boolean folderMissing,
                       boolean resumable) {
            this(session, activity, running, branch, placement, readOnly, folderMissing, resumable,
                    Optional.empty());
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

    /**
     * One branch of the repository, local or remote-tracking, with the local
     * branch name a new worktree on it would carry: the ref itself for a
     * local branch, the remote prefix stripped for a remote one. Occupancy
     * travels on the ref ({@link BranchRef#checkedOutAt()}): a held branch
     * mirrors its holder worktree's start, a free one offers a new worktree
     * on it and a sub-branch cut from it. Git refuses a second checkout of a
     * held branch, so at most one worktree holds it.
     */
    record BranchRow(Path mainRoot, BranchRef branch, String worktreeBranch) implements TaskNode {
        public BranchRow {
            Objects.requireNonNull(mainRoot, "mainRoot");
            Objects.requireNonNull(branch, "branch");
            if (worktreeBranch == null || worktreeBranch.isBlank()) {
                throw new IllegalArgumentException("worktreeBranch must not be blank");
            }
        }

        /** Whether a worktree (or the main checkout) already holds this branch. */
        public boolean held() {
            return branch.checkedOutAt().isPresent();
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

    /** One open Jira issue belonging to the containing registered project, inside its group under the Jira node. */
    record JiraIssueRow(Path mainRoot, JiraIssue issue) implements TaskNode {
        public JiraIssueRow {
            Objects.requireNonNull(mainRoot, "mainRoot");
            Objects.requireNonNull(issue, "issue");
        }
    }

    /**
     * One open pull request belonging to the containing registered project:
     * not a draft, and not already checked out in one of the repository's
     * worktrees (a checked-out PR is its worktree's row).
     */
    record PullRequestRow(Path mainRoot, OpenPullRequest pullRequest) implements TaskNode {
        public PullRequestRow {
            Objects.requireNonNull(mainRoot, "mainRoot");
            Objects.requireNonNull(pullRequest, "pullRequest");
        }
    }
}
