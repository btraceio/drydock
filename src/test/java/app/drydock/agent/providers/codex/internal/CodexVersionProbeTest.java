package app.drydock.agent.providers.codex.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodexVersionProbeTest {

    @Test
    void parsesCodexCliPrefixedVersion() {
        assertEquals("0.144.5", CodexVersionProbe.parseVersion("codex-cli 0.144.5"));
    }

    @Test
    void fallsBackToRawLineWhenPrefixAbsent() {
        assertEquals("some-other-format", CodexVersionProbe.parseVersion("some-other-format"));
    }

    @Test
    void blankLineFallsBackToUnknown() {
        assertEquals("unknown", CodexVersionProbe.parseVersion(""));
    }

    @Test
    void nullLineFallsBackToUnknown() {
        assertEquals("unknown", CodexVersionProbe.parseVersion(null));
    }

    @Test
    void nullExecutableFallsBackToUnknown() {
        assertEquals("unknown", CodexVersionProbe.probe(null));
    }

    @Test
    void nonexistentExecutableFallsBackToUnknown() {
        assertEquals("unknown", CodexVersionProbe.probe(Path.of("/nonexistent/codex")));
    }
}
