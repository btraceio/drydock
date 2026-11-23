package app.drydock.intellij.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TaskTreeRendererHoverTest {
    @Test
    void truncationDoesNotSplitASupplementaryCharacterAtTheLimit() {
        int limit = TaskTreeRenderer.DESCRIPTION_HOVER_LIMIT;
        String body = "a".repeat(limit - 1) + "\uD83D\uDE00" + "tail";

        String hover = TaskTreeRenderer.descriptionHover(body);
        String visibleCut = hover.substring(2, hover.indexOf("\n…"));

        assertEquals(limit - 1, visibleCut.length());
        assertFalse(Character.isHighSurrogate(visibleCut.charAt(visibleCut.length() - 1)));
        assertTrue(hover.endsWith("\n… (Edit opens the task's file with the rest)"));
    }
}
