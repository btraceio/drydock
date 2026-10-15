package app.drydock.agent.providers.pi.internal;

import app.drydock.testing.TestPaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiExecutableLocatorTest {

    @Test
    void explicitNonexistentPathResolvesToNotFound() {
        PiExecutableLocator locator = new PiExecutableLocator(TestPaths.abs("/nonexistent/pi"));
        assertTrue(locator.locate().isEmpty());
        assertTrue(locator.describeSearched().contains(TestPaths.abs("/nonexistent/pi").toString()));
    }

    @Test
    void describeSearchedListsPathThenFallbacks() {
        PiExecutableLocator locator = new PiExecutableLocator(TestPaths.abs("/nonexistent/pi"));
        locator.locate();
        assertEquals("configured path " + TestPaths.abs("/nonexistent/pi"), locator.describeSearched());
    }
}
