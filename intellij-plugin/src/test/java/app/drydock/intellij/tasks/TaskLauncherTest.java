package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.intellij.tasks.NewTaskPlan.Choice;
import app.drydock.intellij.tasks.NewTaskPlan.OpenIn;
import app.drydock.intellij.tasks.NewTaskPlan.WorkIn;
import app.drydock.intellij.tasks.TaskLauncher.Steps;
import app.drydock.projects.SharedProject;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskLauncherTest {

    private static final Path MAIN = Path.of("/w/drydock");
    private static final Path FOLDER = Path.of("/wt/drydock-task");
    private static final Optional<SharedProject> REGISTERED =
            Optional.of(SharedProject.local(RepositoryId.newId(), MAIN, "drydock", Instant.EPOCH));

    private static Choice choice(SessionMode mode, WorkIn workIn, OpenIn openIn) {
        return new Choice("Task", Optional.empty(), AgentKind.CLAUDE, mode, workIn,
                workIn == WorkIn.MAIN_CHECKOUT ? "" : "task", "origin/main",
                workIn == WorkIn.MAIN_CHECKOUT ? MAIN : FOLDER, openIn);
    }

    @Test
    void unregisteredProjectIsRegisteredFirst() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.WRITES, WorkIn.NEW_WORKTREE, OpenIn.NEW_WINDOW),
                Optional.empty(), MAIN, false);

        assertTrue(steps.registerFirst());
    }

    @Test
    void newWorktreeCreatesThenOpensNewWindow() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.WRITES, WorkIn.NEW_WORKTREE, OpenIn.NEW_WINDOW),
                REGISTERED, MAIN, false);

        assertEquals(new Steps(false, true, FOLDER, OpenIn.NEW_WINDOW, false), steps);
    }

    @Test
    void thisWindowReplacesCurrent() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.WRITES, WorkIn.NEW_WORKTREE, OpenIn.THIS_WINDOW),
                REGISTERED, MAIN, false);

        assertEquals(OpenIn.THIS_WINDOW, steps.openIn());
        assertFalse(steps.reuseOpenWindow());
    }

    @Test
    void mainCheckoutReusesOpenWindow() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.WRITES, WorkIn.MAIN_CHECKOUT, OpenIn.NEW_WINDOW),
                REGISTERED, MAIN, true);

        assertEquals(new Steps(false, false, MAIN, OpenIn.NEW_WINDOW, true), steps);
    }

    @Test
    void existingWorktreeNeverCreates() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.WRITES, WorkIn.EXISTING_WORKTREE, OpenIn.NEW_WINDOW),
                REGISTERED, MAIN, false);

        assertFalse(steps.createWorktree());
        assertEquals(FOLDER, steps.workingDirectory());
    }

    @Test
    void readOnlyNeverCreates() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.READ_ONLY, WorkIn.MAIN_CHECKOUT, OpenIn.NEW_WINDOW),
                REGISTERED, MAIN, false);

        assertFalse(steps.createWorktree());
        assertEquals(MAIN, steps.workingDirectory());
    }

    @Test
    void defaultStartPrefersTheRemoteDefaultBranch() {
        assertEquals("origin/main", TaskLauncher.defaultStart("main", List.of("main", "origin/main"),
                List.of("origin")));
        assertEquals("main", TaskLauncher.defaultStart("main", List.of("main"), List.of()));
    }

    @Test
    void aWindowThatDidNotOpenIsReportedWithItsFolder() {
        assertEquals("The worktree /wt/drydock-x is ready, but its window did not open. "
                        + "Open it with File › Open, or double-click the task in the Drydock tool window.",
                TaskLauncher.windowDidNotOpen(Path.of("/wt/drydock-x")));
    }
}
