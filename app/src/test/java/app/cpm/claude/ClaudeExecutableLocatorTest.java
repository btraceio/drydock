package app.cpm.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeExecutableLocatorTest {

    @Test
    void explicitPathIsUsedWhenValid(@TempDir Path tempDir) throws IOException {
        Path fakeClaude = tempDir.resolve("claude");
        Files.writeString(fakeClaude, "#!/bin/sh\nexit 0\n");
        fakeClaude.toFile().setExecutable(true);

        ClaudeExecutableLocator locator = new ClaudeExecutableLocator(fakeClaude);
        assertEquals(fakeClaude, locator.locate().orElseThrow());
    }

    @Test
    void explicitInvalidPathIsNotFoundAndDoesNotFallBack() {
        ClaudeExecutableLocator locator = new ClaudeExecutableLocator(Path.of("/nonexistent/does-not-exist/claude"));
        assertTrue(locator.locate().isEmpty());
        assertTrue(locator.describeSearched().contains("nonexistent"));
    }

    @Test
    void resultIsCached() {
        ClaudeExecutableLocator locator = new ClaudeExecutableLocator(Path.of("/nonexistent/does-not-exist/claude"));
        assertEquals(locator.locate(), locator.locate());
    }
}
