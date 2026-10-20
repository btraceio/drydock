package app.drydock.intellij.tasks;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskNavigatorTest {

    private static final Map<String, Path> BASE = Map.of(
            "main window", Path.of("/w/btrace"),
            "worktree window", Path.of("/wt/btrace-jfr"));

    @Test
    void theWindowShowingTheSessionWinsOverItsOwnWorktreeWindow() {
        Optional<String> window = TaskNavigator.windowFor(List.of("worktree window", "main window"),
                Optional.of("main window"), name -> Optional.of(BASE.get(name)), Path.of("/wt/btrace-jfr"));

        assertEquals(Optional.of("main window"), window);
    }

    @Test
    void aNotShownSessionGoesToItsWorktreeWindow() {
        Optional<String> window = TaskNavigator.windowFor(List.of("main window", "worktree window"),
                Optional.empty(), name -> Optional.of(BASE.get(name)), Path.of("/wt/btrace-jfr"));

        assertEquals(Optional.of("worktree window"), window);
    }

    @Test
    void windowForEmptyWhenNoneOpen() {
        Optional<String> window = TaskNavigator.windowFor(List.of("main window"),
                Optional.empty(), name -> Optional.of(BASE.get(name)), Path.of("/wt/elsewhere"));

        assertEquals(Optional.empty(), window);
    }

    @Test
    void aTaskWhoseFolderIsGoneSaysSoInsteadOfOpeningNothing() {
        assertEquals(Optional.of("The folder of \"Add farewell\" is gone: /wt/drydock-gone. "
                        + "Use Done with This Task… to forget it."),
                TaskNavigator.missingFolderMessage(false, false, "Add farewell", Path.of("/wt/drydock-gone")));
        assertEquals(Optional.empty(),
                TaskNavigator.missingFolderMessage(true, false, "Add farewell", Path.of("/wt/drydock-gone")));
        assertEquals(Optional.empty(),
                TaskNavigator.missingFolderMessage(false, true, "Add farewell", Path.of("/wt/drydock-x")));
    }
}
