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
                workIn == WorkIn.MAIN_CHECKOUT ? MAIN : FOLDER, openIn, Optional.empty());
    }

    @Test
    void unregisteredProjectIsRegisteredFirst() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.WRITES, WorkIn.NEW_WORKTREE, OpenIn.OWN_WINDOW),
                Optional.empty(), MAIN, false, false);

        assertTrue(steps.registerFirst());
    }

    @Test
    void newWorktreeCreatesThenOpensNewWindow() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.WRITES, WorkIn.NEW_WORKTREE, OpenIn.OWN_WINDOW),
                REGISTERED, MAIN, false, false);

        assertEquals(new Steps(false, true, FOLDER, OpenIn.OWN_WINDOW, false, false), steps);
    }

    @Test
    void thisWindowOnAnotherWorktreeHostsTheTask() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.WRITES, WorkIn.NEW_WORKTREE, OpenIn.THIS_WINDOW),
                REGISTERED, MAIN, true, false);

        assertTrue(steps.hosted());
        assertFalse(steps.reuseOpenWindow());
    }

    @Test
    void thisWindowOnItsOwnWorktreeIsAPlainTab() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.WRITES, WorkIn.MAIN_CHECKOUT, OpenIn.THIS_WINDOW),
                REGISTERED, MAIN, true, true);

        assertFalse(steps.hosted());
        assertFalse(steps.reuseOpenWindow());
    }

    @Test
    void itsOwnWindowOpensOrReusesAWindow() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.WRITES, WorkIn.EXISTING_WORKTREE, OpenIn.OWN_WINDOW),
                REGISTERED, MAIN, true, false);

        assertTrue(steps.reuseOpenWindow());
        assertFalse(steps.hosted());
    }

    @Test
    void mainCheckoutReusesOpenWindow() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.WRITES, WorkIn.MAIN_CHECKOUT, OpenIn.OWN_WINDOW),
                REGISTERED, MAIN, true, false);

        assertEquals(new Steps(false, false, MAIN, OpenIn.OWN_WINDOW, true, false), steps);
    }

    @Test
    void existingWorktreeNeverCreates() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.WRITES, WorkIn.EXISTING_WORKTREE, OpenIn.OWN_WINDOW),
                REGISTERED, MAIN, false, false);

        assertFalse(steps.createWorktree());
        assertEquals(FOLDER, steps.workingDirectory());
    }

    @Test
    void readOnlyNeverCreates() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.READ_ONLY, WorkIn.MAIN_CHECKOUT, OpenIn.OWN_WINDOW),
                REGISTERED, MAIN, false, false);

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
