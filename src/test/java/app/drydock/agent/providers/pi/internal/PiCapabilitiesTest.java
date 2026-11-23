package app.drydock.agent.providers.pi.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiCapabilitiesTest {
    @Test
    void oneDotZeroAndNewerAreSupported() {
        assertTrue(PiCapabilities.of("1.0.0").supported());
        assertTrue(PiCapabilities.of("1.3.0").supported());
        assertTrue(PiCapabilities.of("2.0.0").supported());
    }

    @Test
    void preOneVersionsAreUnsupported() {
        assertFalse(PiCapabilities.of("0.99.0").supported());
        assertFalse(PiCapabilities.of("0.80.3").supported());
    }

    @Test
    void unknownAndJunkFailConservatively() {
        assertFalse(PiCapabilities.of("unknown").supported());
        assertFalse(PiCapabilities.of("").supported());
        assertFalse(PiCapabilities.of(null).supported());
        assertFalse(PiCapabilities.of("not.a.version").supported());
        assertFalse(PiCapabilities.of("1.0").supported());
    }

    @Test
    void prereleaseSuffixIsIgnoredForComparison() {
        assertTrue(PiCapabilities.of("1.0.0-beta.2").supported());
        assertFalse(PiCapabilities.of("0.99.0-rc1").supported());
    }

    @Test
    void versionAndParseStateArePreserved() {
        assertEquals("1.3.0", PiCapabilities.of("1.3.0").version());
        assertEquals("unknown", PiCapabilities.of(null).version());
        assertTrue(PiCapabilities.of("0.99.0").versionParsed());
        assertFalse(PiCapabilities.of("v1.3.0").versionParsed());
    }
}
