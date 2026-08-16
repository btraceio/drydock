package app.drydock.agent.providers.pi.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiCapabilitiesTest {

    @Test
    void atAndAboveTheFloorSupportsTheBridge() {
        assertTrue(PiCapabilities.of("0.80.3").supportsBridge());
        assertTrue(PiCapabilities.of("0.84.1").supportsBridge());
        assertTrue(PiCapabilities.of("0.80.10").supportsBridge());   // numeric, not lexical
        assertTrue(PiCapabilities.of("1.0.0").supportsBridge());
    }

    @Test
    void belowTheFloorDoesNot() {
        assertFalse(PiCapabilities.of("0.80.2").supportsBridge());
        assertFalse(PiCapabilities.of("0.79.10").supportsBridge());  // verified working, deliberately unsupported
        assertFalse(PiCapabilities.of("0.55.4").supportsBridge());
        assertFalse(PiCapabilities.of("0.9.0").supportsBridge());
    }

    @Test
    void unknownAndJunkFailConservatively() {
        assertFalse(PiCapabilities.of("unknown").supportsBridge());
        assertFalse(PiCapabilities.of("").supportsBridge());
        assertFalse(PiCapabilities.of(null).supportsBridge());
        assertFalse(PiCapabilities.of("not.a.version").supportsBridge());
        assertFalse(PiCapabilities.of("0.80").supportsBridge());     // too few components to judge
    }

    @Test
    void prereleaseSuffixIsIgnoredForComparison() {
        assertTrue(PiCapabilities.of("0.84.1-beta.2").supportsBridge());
        assertFalse(PiCapabilities.of("0.80.2-rc1").supportsBridge());
    }

    @Test
    void versionIsCarriedThroughVerbatim() {
        assertEquals("0.84.1", PiCapabilities.of("0.84.1").version());
        assertEquals("unknown", PiCapabilities.of(null).version());
    }
}
