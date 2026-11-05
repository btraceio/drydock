package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.tasks.TaskNode.AddCurrentProject;
import app.drydock.intellij.tasks.TaskNode.ListProblem;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.SectionKind;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.projects.SharedProject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectFocusTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final Path DRYDOCK = Path.of("/work/drydock");
    private static final Path BTRACE = Path.of("/work/btrace");
    private static final Path LOOSE = Path.of("/work/unregistered");

    private static TaskRow sessionRow(String name, Path directory) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), name,
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(directory), NOW)
                .build();
        return new TaskRow(session, Optional.empty(), false, Optional.empty(),
                new TaskPlacement.Idle(false), false, false);
    }

    private static ProjectRow projectRow(Path root, TaskRow... sessions) {
        return new ProjectRow(SharedProject.local(RepositoryId.newId(), root, root.getFileName().toString(), NOW),
                false, false, sessions.length == 0 ? List.of()
                        : List.of(new Section(SectionKind.SESSIONS, List.of(sessions))), List.of(), 0);
    }

    @Test
    void aFocusKeepsOnlyItsProjectAndTheListProblem() {
        ListProblem problem = new ListProblem("Could not read the shared projects");
        ProjectRow drydock = projectRow(DRYDOCK);
        List<TaskNode> nodes = List.of(problem, new AddCurrentProject(LOOSE, "unregistered"),
                sessionRow("Loose task", LOOSE), drydock, projectRow(BTRACE));

        assertEquals(List.of(problem, drydock), ProjectFocus.focused(nodes, DRYDOCK));
        assertEquals(List.of(problem), ProjectFocus.focused(nodes, LOOSE),
                "an unregistered project has no row to keep");
    }

    @Test
    void aSessionIsPlacedInTheProjectWhoseSubtreeHoldsIt() {
        TaskRow mine = sessionRow("Mine", DRYDOCK);
        TaskRow theirs = sessionRow("Theirs", BTRACE);
        List<TaskNode> nodes = List.of(projectRow(DRYDOCK, mine), projectRow(BTRACE, theirs));

        assertEquals(Optional.of(BTRACE), ProjectFocus.projectOf(nodes, theirs.session().id())
                .map(row -> row.project().root()));
        assertEquals(Optional.of(DRYDOCK), ProjectFocus.projectOf(nodes, mine.session().id())
                .map(row -> row.project().root()));
        assertEquals(Optional.empty(), ProjectFocus.projectOf(nodes, ManagedSessionId.newId()));
    }

    @Test
    void aRepositoryIsInFocusByItsRootOrItsWorktreesMainCheckout() {
        RepositoryId main = RepositoryId.newId();
        RepositoryId worktree = RepositoryId.newId();
        RepositoryId other = RepositoryId.newId();
        Path linked = Path.of("/work/drydock-feature");
        Map<RepositoryId, Path> roots = Map.of(main, DRYDOCK, worktree, linked, other, BTRACE);
        Map<Path, Path> mainRootOf = Map.of(linked, DRYDOCK, BTRACE, BTRACE);

        assertTrue(ProjectFocus.inFocus(main, DRYDOCK, roots, mainRootOf));
        assertTrue(ProjectFocus.inFocus(worktree, DRYDOCK, roots, mainRootOf));
        assertFalse(ProjectFocus.inFocus(other, DRYDOCK, roots, mainRootOf));
        assertFalse(ProjectFocus.inFocus(RepositoryId.newId(), DRYDOCK, roots, mainRootOf),
                "an unknown repository is not in focus");
    }

    @Test
    void theBarNamesTheFocusAndAHiddenSelectionsProject() {
        assertEquals("Showing drydock only.",
                ProjectFocusBar.message(new ProjectFocusBar.State("drydock", Optional.empty())));
        assertEquals("“Fix sampling” is in btrace, outside the focus on drydock.",
                ProjectFocusBar.message(new ProjectFocusBar.State("drydock",
                        Optional.of(new ProjectFocusBar.Outside("Fix sampling", "btrace")))));
    }
}
