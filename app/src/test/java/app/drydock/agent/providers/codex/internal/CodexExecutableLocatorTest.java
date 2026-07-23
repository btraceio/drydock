package app.drydock.agent.providers.codex.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexExecutableLocatorTest {

    @Test
    void explicitNonexistentPathResolvesToNotFound() {
        CodexExecutableLocator locator = new CodexExecutableLocator(Path.of("/nonexistent/codex"));
        assertTrue(locator.locate().isEmpty());
        assertTrue(locator.describeSearched().contains("/nonexistent/codex"));
    }

    @Test
    void describeSearchedListsPathThenFallbacks() {
        CodexExecutableLocator locator = new CodexExecutableLocator(Path.of("/nonexistent/codex"));
        locator.locate();
        assertEquals("configured path /nonexistent/codex", locator.describeSearched());
    }
}
