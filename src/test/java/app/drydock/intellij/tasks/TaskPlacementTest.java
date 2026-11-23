package app.drydock.intellij.tasks;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where a task's agent is shown, in the row's words (cross-project tabs, frames F1 and F5). */
class TaskPlacementTest {

    private static final Path HERE = Path.of("/w/java-profiler");
    private static final Path ELSEWHERE = Path.of("/w/dd-jmc-fix");
    private static final Path TASK = Path.of("/wt/dd-trace-java-otlp");

    @Test
    void aStoppedTaskIsIdle() {
        assertEquals(new TaskPlacement.Idle(false),
                TaskPlacement.of(false, Optional.of(HERE), Optional.of("java-profiler"), TASK, Optional.of(HERE)));
        assertEquals("", new TaskPlacement.Idle(false).meta());
    }

    @Test
    void aRunningTaskNoWindowShowsIsNotShown() {
        assertEquals("not shown",
                TaskPlacement.of(true, Optional.empty(), Optional.empty(), TASK, Optional.of(HERE)).meta());
    }

    @Test
    void thisWindowsOwnWorktreeShownHereIsThisWindow() {
        assertEquals("this window",
                TaskPlacement.of(true, Optional.of(HERE), Optional.of("java-profiler"), HERE, Optional.of(HERE)).meta());
    }

    @Test
    void anotherWorktreeShownHereIsHere() {
        assertEquals("here",
                TaskPlacement.of(true, Optional.of(HERE), Optional.of("java-profiler"), TASK, Optional.of(HERE)).meta());
    }

    @Test
    void shownInItsOwnWorktreesWindowIsInItsOwnWindow() {
        assertEquals("in its own window",
                TaskPlacement.of(true, Optional.of(TASK), Optional.of("dd-trace-java"), TASK, Optional.of(HERE)).meta());
    }

    @Test
    void hostedInAThirdWindowNamesThatWindowsRepo() {
        assertEquals("in java-profiler window",
                TaskPlacement.of(true, Optional.of(ELSEWHERE), Optional.of("java-profiler"), TASK, Optional.of(HERE))
                        .meta());
    }
}
