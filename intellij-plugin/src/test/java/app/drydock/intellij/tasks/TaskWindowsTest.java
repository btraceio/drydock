package app.drydock.intellij.tasks;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskWindowsTest {

    @Test
    void openingRemembersEachTaskWindowOnceInOpeningOrder() {
        List<String> remembered = TaskWindows.withRemembered(List.of("/wt/one"), "/wt/two");

        assertEquals(List.of("/wt/one", "/wt/two"), remembered);
        assertEquals(remembered, TaskWindows.withRemembered(remembered, "/wt/one"));
    }

    @Test
    void anOrdinaryWindowCloseForgetsOnlyThatTaskWindow() {
        assertEquals(List.of("/wt/one", "/wt/three"), TaskWindows.withoutRemembered(
                List.of("/wt/one", "/wt/two", "/wt/three"), "/wt/two"));
    }
}
