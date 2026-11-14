package app.drydock.intellij.tasks;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaiseOrOpenWindowTest {

    private record Window(String name, Path base) {
    }

    private static Optional<Path> baseOf(Window window) {
        return Optional.ofNullable(window.base());
    }

    @Test
    void findsTheWindowShowingTheCanonicalDirectory() {
        Window other = new Window("other", Path.of("/repos/other"));
        Window match = new Window("match", Path.of("/repos/worktree"));

        Optional<Window> found = RaiseOrOpenWindow.findOpenWindow(
                List.of(other, match), RaiseOrOpenWindowTest::baseOf, Path.of("/repos/worktree"));

        assertEquals(Optional.of(match), found);
    }

    @Test
    void findsNothingWhenNoWindowShowsTheDirectory() {
        List<Window> windows = List.of(new Window("other", Path.of("/repos/other")));

        assertTrue(RaiseOrOpenWindow.findOpenWindow(windows, RaiseOrOpenWindowTest::baseOf,
                Path.of("/repos/worktree")).isEmpty());
    }

    @Test
    void skipsWindowsWithoutAProjectDirectory() {
        List<Window> windows = List.of(new Window("no-base", null));

        assertTrue(RaiseOrOpenWindow.findOpenWindow(windows, RaiseOrOpenWindowTest::baseOf,
                Path.of("/repos/worktree")).isEmpty());
    }

    @Test
    void keepsTheFirstWindowWhenSeveralShowTheDirectory() {
        Window first = new Window("first", Path.of("/repos/worktree"));
        Window second = new Window("second", Path.of("/repos/worktree"));

        Optional<Window> found = RaiseOrOpenWindow.findOpenWindow(
                List.of(first, second), RaiseOrOpenWindowTest::baseOf, Path.of("/repos/worktree"));

        assertEquals(Optional.of(first), found);
    }
}
