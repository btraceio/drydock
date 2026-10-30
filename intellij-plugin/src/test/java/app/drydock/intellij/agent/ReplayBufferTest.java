package app.drydock.intellij.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a console shown later gets of an agent's earlier output, and where that stops. */
class ReplayBufferTest {

    @Test
    void keepsEverythingUnderTheCaps() {
        ReplayBuffer buffer = new ReplayBuffer();
        buffer.append("a\nb");
        buffer.append("\n");

        assertEquals("a\nb\n", buffer.snapshot());
        assertFalse(buffer.trimmed());
    }

    @Test
    void dropsTheOldestLinesPastTheLineCap() {
        ReplayBuffer buffer = new ReplayBuffer(3, 1000);
        buffer.append("1\n2\n3\n4\n5\n");

        assertEquals(ReplayBuffer.TRIMMED + "3\n4\n5\n", buffer.snapshot());
        assertTrue(buffer.trimmed());
    }

    @Test
    void dropsTheOldestTextPastTheCharCap() {
        ReplayBuffer buffer = new ReplayBuffer(1000, 6);
        buffer.append("aaa\nbbb\nccc\n");

        assertEquals(ReplayBuffer.TRIMMED + "ccc\n", buffer.snapshot());
    }

    @Test
    void aLineLongerThanTheCharCapKeepsItsTail() {
        ReplayBuffer buffer = new ReplayBuffer(1000, 4);
        buffer.append("abcdefgh");

        assertEquals(ReplayBuffer.TRIMMED + "efgh", buffer.snapshot());
    }

    @Test
    void theDefaultsAreTheSpecs() {
        assertEquals(AgentTerminalSettings.MAX_HISTORY_LINES, ReplayBuffer.MAX_LINES);
        assertEquals(2 * 1024 * 1024, ReplayBuffer.MAX_CHARS);
    }
}
