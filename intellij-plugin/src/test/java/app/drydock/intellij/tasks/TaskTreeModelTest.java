package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.SshRemote;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.tasks.TaskNode.AddCurrentProject;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.tasks.TaskNode.WorktreeGroup;
import app.drydock.projects.SharedProject;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskTreeModelTest {

    private static final Instant T = Instant.parse("2026-10-19T10:00:00Z");

    private static final Path DRYDOCK = Path.of("/w/drydock");
    private static final Path BTRACE = Path.of("/w/btrace");
    private static final Path ALPHA = Path.of("/w/alpha");

    /** Fluent inputs: every path given here is already canonical, as gather() makes them. */
    private static final class In {
        final List<SharedProject> projects = new ArrayList<>();
        final List<ManagedAgentSession> sessions = new ArrayList<>();
        final Map<Path, List<Worktree>> worktrees = new HashMap<>();
        final Map<Path, Path> mainRootOf = new HashMap<>();
        Optional<Path> currentMain = Optional.empty();
        Optional<Path> currentWorking = Optional.empty();
        final Map<ManagedSessionId, Optional<SessionActivity>> activity = new HashMap<>();
        final Set<ManagedSessionId> running = new HashSet<>();
        final Set<Path> missing = new HashSet<>();
        String filter = "";

        In project(Path root) {
            projects.add(SharedProject.local(RepositoryId.newId(), root, root.getFileName().toString(), T));
            worktrees.computeIfAbsent(root, r -> new ArrayList<>()).add(main(root, "main"));
            mainRootOf.put(root, root);
            return this;
        }

        In worktree(Path main, Path dir, String branch) {
            worktrees.computeIfAbsent(main, r -> new ArrayList<>()).add(linked(dir, branch));
            mainRootOf.put(dir, main);
            return this;
        }

        In current(Path main, Path working) {
            currentMain = Optional.of(main);
            currentWorking = Optional.of(working);
            return this;
        }

        TaskTreeModel.Inputs build() {
            return new TaskTreeModel.Inputs(projects, sessions, worktrees, mainRootOf, currentMain, currentWorking,
                    activity, running, missing, filter);
        }
    }

    private static Worktree main(Path path, String branch) {
        return new Worktree(path, Optional.of(branch), true, false, false, false, Optional.empty(), false);
    }

    private static Worktree linked(Path path, String branch) {
        return new Worktree(path, Optional.of(branch), false, false, false, false, Optional.empty(), false);
    }

    private static ManagedAgentSession session(String name, Path workingDirectory, AgentKind kind, Instant opened) {
        return new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(), name,
                AgentBinding.unlaunched(kind, SessionMode.WRITES), SessionWorkspace.inRepository(workingDirectory), T)
                .status(SessionStatus.INACTIVE)
                .lastOpenedAt(opened)
                .build();
    }

    private static ManagedAgentSession session(String name, Path workingDirectory) {
        return session(name, workingDirectory, AgentKind.CLAUDE, T);
    }

    private static ProjectRow projectRow(List<TaskNode> nodes, Path root) {
        return nodes.stream().filter(ProjectRow.class::isInstance).map(ProjectRow.class::cast)
                .filter(row -> row.project().root().equals(root)).findFirst().orElseThrow();
    }

    private static List<Path> projectRoots(List<TaskNode> nodes) {
        return nodes.stream().filter(ProjectRow.class::isInstance).map(node -> ((ProjectRow) node).project().root())
                .toList();
    }

    @Test
    void currentProjectFirstThenAlphabetical() {
        In in = new In().project(DRYDOCK).project(BTRACE).project(ALPHA).current(DRYDOCK, DRYDOCK);

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        assertEquals(List.of(DRYDOCK, ALPHA, BTRACE), projectRoots(nodes));
        assertTrue(projectRow(nodes, DRYDOCK).isThisWindow());
    }

    @Test
    void unregisteredCurrentProjectGetsAddRowWithItsRunningTasks() {
        In in = new In().project(ALPHA).current(BTRACE, BTRACE);
        in.mainRootOf.put(BTRACE, BTRACE);
        ManagedAgentSession here = session("Fix probe leak", BTRACE);
        in.sessions.add(here);
        in.running.add(here.id());

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        assertEquals(new AddCurrentProject(BTRACE, "btrace"), nodes.get(0));
        TaskRow row = assertInstanceOf(TaskRow.class, nodes.get(1));
        assertEquals(here.id(), row.session().id());
        assertEquals(List.of(ALPHA), projectRoots(nodes));
    }

    @Test
    void remoteProjectsAreHidden() {
        In in = new In().project(ALPHA);
        SshRemote remote = new SshRemote("box", "/srv/r");
        in.projects.add(new SharedProject(RepositoryId.newId(), remote.placeholderRoot(), "r", T, Optional.of(remote)));

        assertEquals(List.of(ALPHA), projectRoots(TaskTreeModel.build(in.build())));
    }

    @Test
    void singleTaskWorktreeIsFlatRowWithBranch() {
        Path wt = Path.of("/wt/drydock-task-centric");
        In in = new In().project(DRYDOCK).worktree(DRYDOCK, wt, "task-centric");
        in.sessions.add(session("Task-centric tool window", wt));

        ProjectRow row = projectRow(TaskTreeModel.build(in.build()), DRYDOCK);

        TaskRow task = assertInstanceOf(TaskRow.class, row.children().get(0));
        assertEquals(Optional.of("task-centric"), task.branch());
        assertEquals(1, row.children().size());
    }

    @Test
    void twoTasksInOneWorktreeBecomeGroup() {
        Path wt = Path.of("/wt/btrace-jfr");
        In in = new In().project(BTRACE).worktree(BTRACE, wt, "add-jfr-events");
        in.sessions.add(session("Add JFR events", wt));
        in.sessions.add(session("Review the JFR events", wt));

        ProjectRow row = projectRow(TaskTreeModel.build(in.build()), BTRACE);

        WorktreeGroup group = assertInstanceOf(WorktreeGroup.class, row.children().get(0));
        assertEquals("add-jfr-events", group.branch());
        assertEquals(2, group.tasks().size());
    }

    @Test
    void worktreeWithoutTaskIsIdleWorktree() {
        Path idle = Path.of("/wt/btrace-old");
        Path pruned = Path.of("/wt/btrace-gone");
        In in = new In().project(BTRACE).worktree(BTRACE, idle, "old-experiment");
        in.worktrees.get(BTRACE).add(new Worktree(pruned, Optional.of("gone"), false, false, true, false,
                Optional.empty(), false));

        ProjectRow row = projectRow(TaskTreeModel.build(in.build()), BTRACE);

        assertEquals(List.of(new IdleWorktree(idle, "old-experiment")), row.children());
    }

    @Test
    void mainCheckoutTaskHasDefaultBranchAndThisWindow() {
        In in = new In().project(BTRACE).current(BTRACE, BTRACE);
        in.sessions.add(session("Explain the agent loader", BTRACE));

        TaskRow row = (TaskRow) projectRow(TaskTreeModel.build(in.build()), BTRACE).children().get(0);

        assertEquals(Optional.of("main"), row.branch());
        assertTrue(row.isThisWindow());
    }

    @Test
    void filterMatchesTaskNameOrBranchCaseInsensitive() {
        Path wt = Path.of("/wt/btrace-jfr");
        In in = new In().project(BTRACE).project(ALPHA).project(DRYDOCK).current(DRYDOCK, DRYDOCK)
                .worktree(BTRACE, wt, "add-jfr-events");
        in.sessions.add(session("Sampling events", wt));
        in.sessions.add(session("Unrelated", ALPHA));
        in.filter = "JFR";

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        assertEquals(List.of(DRYDOCK, BTRACE), projectRoots(nodes));
        assertEquals(1, projectRow(nodes, BTRACE).children().size());
    }

    @Test
    void missingProjectFolderIsShownNotDropped() {
        In in = new In().project(ALPHA).project(BTRACE);
        in.missing.add(BTRACE);

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        assertTrue(projectRow(nodes, BTRACE).folderMissing());
        assertEquals(List.of(ALPHA, BTRACE), projectRoots(nodes));
    }

    @Test
    void sessionWithUnresolvableWorkingDirectoryFallsBackToPathPrefix() {
        In in = new In().project(BTRACE);
        Path removedElsewhere = BTRACE.resolve("nested/dir");
        in.sessions.add(session("Orphaned by a manual worktree remove", removedElsewhere));

        ProjectRow row = projectRow(TaskTreeModel.build(in.build()), BTRACE);

        assertEquals(1, row.children().size());
        assertEquals(Optional.empty(), ((TaskRow) row.children().get(0)).branch());
    }

    @Test
    void sessionsOfUnregisteredOtherProjectsAreHidden() {
        In in = new In().project(ALPHA).current(ALPHA, ALPHA);
        in.mainRootOf.put(BTRACE, BTRACE);
        in.sessions.add(session("Somewhere else", BTRACE));

        List<TaskNode> nodes = TaskTreeModel.build(in.build());

        assertEquals(1, nodes.size());
        assertEquals(List.of(), projectRow(nodes, ALPHA).children());
    }

    @Test
    void terminalAgentSessionsAreHidden() {
        In in = new In().project(ALPHA);
        in.sessions.add(session("A shell", ALPHA, AgentKind.TERMINAL, T));

        assertEquals(List.of(), projectRow(TaskTreeModel.build(in.build()), ALPHA).children());
    }

    @Test
    void needsYouFirstThenRunningThenMostRecent() {
        In in = new In().project(ALPHA);
        ManagedAgentSession old = session("old", ALPHA, AgentKind.CLAUDE, T);
        ManagedAgentSession recent = session("recent", ALPHA.resolve("a"), AgentKind.CLAUDE, T.plusSeconds(60));
        ManagedAgentSession running = session("running", ALPHA.resolve("b"), AgentKind.CLAUDE, T);
        ManagedAgentSession waiting = session("waiting", ALPHA.resolve("c"), AgentKind.CLAUDE, T);
        in.sessions.addAll(List.of(old, recent, running, waiting));
        in.running.addAll(List.of(running.id(), waiting.id()));
        in.activity.put(waiting.id(), Optional.of(SessionActivity.NEEDS_ATTENTION));

        List<String> order = projectRow(TaskTreeModel.build(in.build()), ALPHA).children().stream()
                .map(node -> ((TaskRow) node).session().displayName()).toList();

        assertEquals(List.of("waiting", "running", "recent", "old"), order);
    }
}
