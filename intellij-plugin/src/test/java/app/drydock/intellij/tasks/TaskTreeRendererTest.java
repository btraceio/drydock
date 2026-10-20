package app.drydock.intellij.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskTreeRendererTest {

    @Test
    void aSingleClickActsOnlyOnTheLinkText() {
        // A stray click while selecting the "isn't in Drydock · Add" row must
        // not register the project on the shared list.
        assertTrue(TaskTreeRenderer.clickActs(true, 1));
        assertFalse(TaskTreeRenderer.clickActs(false, 1));
        assertTrue(TaskTreeRenderer.clickActs(false, 2), "a double-click on the row is deliberate");
        assertFalse(TaskTreeRenderer.clickActs(true, 0), "a synthetic click with no count is not a click");
    }
}
