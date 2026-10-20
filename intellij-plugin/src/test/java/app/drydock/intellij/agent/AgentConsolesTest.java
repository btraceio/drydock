package app.drydock.intellij.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentConsolesTest {

    @Test
    void aSessionRunningInAnotherWindowIsFoundThere() {
        Optional<String> elsewhere = AgentConsoles.runningElsewhere(List.of("host", "owner"), "host",
                Set.of("owner")::contains);

        assertEquals(Optional.of("owner"), elsewhere);
    }

    @Test
    void aSessionRunningOnlyHereIsNotElsewhere() {
        Optional<String> elsewhere = AgentConsoles.runningElsewhere(List.of("host", "owner"), "host",
                Set.of("host")::contains);

        assertEquals(Optional.empty(), elsewhere);
    }

    @Test
    void aSessionRunningNowhereIsNotElsewhere() {
        Optional<String> elsewhere = AgentConsoles.runningElsewhere(List.of("host", "owner"), "host",
                window -> false);

        assertEquals(Optional.empty(), elsewhere);
    }
}
