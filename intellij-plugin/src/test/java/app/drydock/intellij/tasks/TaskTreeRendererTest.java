package app.drydock.intellij.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskTreeRendererTest {

    @Test
    void aSingleClickActsOnlyOnTheLinkText() {
        // A stray click while selecting the "isn't in Drydock · Add" row must
        // not register the project on the shared list.
        assertTrue(TaskTreeRenderer.clickActs(true, true, 1));
        assertFalse(TaskTreeRenderer.clickActs(true, false, 1));
        assertTrue(TaskTreeRenderer.clickActs(true, false, 2), "a double-click on the row is deliberate");
        assertFalse(TaskTreeRenderer.clickActs(true, true, 0), "a synthetic click with no count is not a click");
    }

    @Test
    void aSecondaryClickNeverActivatesTheRow() {
        assertFalse(TaskTreeRenderer.clickActs(false, true, 1),
                "right-clicking Start must open the context menu, not start a task");
        assertFalse(TaskTreeRenderer.clickActs(false, false, 2),
                "a secondary double-click must not switch tasks");
    }
}
