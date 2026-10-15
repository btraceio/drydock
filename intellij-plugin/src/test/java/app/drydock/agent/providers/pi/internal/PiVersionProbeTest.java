package app.drydock.agent.providers.pi.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PiVersionProbeTest {

    @Test
    void parsesBareVersionString() {
        assertEquals("0.71.1", PiVersionProbe.parseVersion("0.71.1"));
    }

    @Test
    void parsesVersionWithNamePrefix() {
        assertEquals("0.71.1", PiVersionProbe.parseVersion("pi 0.71.1"));
    }

    @Test
    void blankLineFallsBackToUnknown() {
        assertEquals("unknown", PiVersionProbe.parseVersion(""));
    }

    @Test
    void nullLineFallsBackToUnknown() {
        assertEquals("unknown", PiVersionProbe.parseVersion(null));
    }

    @Test
    void nullExecutableFallsBackToUnknown() {
        assertEquals("unknown", PiVersionProbe.probe(null));
    }
}
