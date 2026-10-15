package app.drydock.agent.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchPlanTest {

    @Test
    void ofMarksPlanSupported() {
        LaunchPlan plan = LaunchPlan.of("claude --resume 'x'", true);
        assertEquals("claude --resume 'x'", plan.command());
        assertTrue(plan.sessionIdUsed());
        assertTrue(plan.supported());
    }

    @Test
    void unsupportedPlanCarriesNoCommand() {
        LaunchPlan plan = LaunchPlan.unsupported();
        assertFalse(plan.supported());
        assertFalse(plan.sessionIdUsed());
        assertEquals("", plan.command());
    }
}
