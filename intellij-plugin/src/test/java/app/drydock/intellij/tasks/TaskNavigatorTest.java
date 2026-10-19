package app.drydock.intellij.tasks;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskNavigatorTest {

    private static final Map<String, Path> BASE = Map.of(
            "main window", Path.of("/w/btrace"),
            "worktree window", Path.of("/wt/btrace-jfr"));

    @Test
    void windowForPrefersExecutionOwner() {
        Optional<String> window = TaskNavigator.windowFor(List.of("worktree window", "main window"),
                Set.of("main window")::contains, name -> Optional.of(BASE.get(name)), Path.of("/wt/btrace-jfr"));

        assertEquals(Optional.of("main window"), window);
    }

    @Test
    void windowForFallsBackToBasePath() {
        Optional<String> window = TaskNavigator.windowFor(List.of("main window", "worktree window"),
                name -> false, name -> Optional.of(BASE.get(name)), Path.of("/wt/btrace-jfr"));

        assertEquals(Optional.of("worktree window"), window);
    }

    @Test
    void windowForEmptyWhenNoneOpen() {
        Optional<String> window = TaskNavigator.windowFor(List.of("main window"),
                name -> false, name -> Optional.of(BASE.get(name)), Path.of("/wt/elsewhere"));

        assertEquals(Optional.empty(), window);
    }
}
