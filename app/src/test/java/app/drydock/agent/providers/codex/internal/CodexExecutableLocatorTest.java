package app.drydock.agent.providers.codex.internal;

import app.drydock.testing.TestPaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexExecutableLocatorTest {

    @Test
    void explicitNonexistentPathResolvesToNotFound() {
        CodexExecutableLocator locator = new CodexExecutableLocator(TestPaths.abs("/nonexistent/codex"));
        assertTrue(locator.locate().isEmpty());
        assertTrue(locator.describeSearched().contains(TestPaths.abs("/nonexistent/codex").toString()));
    }

    @Test
    void describeSearchedListsPathThenFallbacks() {
        CodexExecutableLocator locator = new CodexExecutableLocator(TestPaths.abs("/nonexistent/codex"));
        locator.locate();
        assertEquals("configured path " + TestPaths.abs("/nonexistent/codex"), locator.describeSearched());
    }
}
