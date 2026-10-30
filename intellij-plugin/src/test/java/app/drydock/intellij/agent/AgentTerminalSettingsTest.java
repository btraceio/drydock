package app.drydock.intellij.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentTerminalSettingsTest {

    @Test
    void theScrollbackIsTheLowerOfTheIdeSettingAndTheCap() {
        assertEquals(1_000, AgentTerminalSettings.capped(1_000), "a lower IDE setting is kept");
        assertEquals(AgentTerminalSettings.MAX_HISTORY_LINES, AgentTerminalSettings.capped(1_000_000),
                "a raised IDE setting is capped");
    }

    @Test
    void aNonPositiveIdeSettingMeansTheCap() {
        assertEquals(AgentTerminalSettings.MAX_HISTORY_LINES, AgentTerminalSettings.capped(0));
        assertEquals(AgentTerminalSettings.MAX_HISTORY_LINES, AgentTerminalSettings.capped(-1));
    }
}
