package app.drydock.agent.providers.pi.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiExecutableLocatorTest {

    @Test
    void explicitNonexistentPathResolvesToNotFound() {
        PiExecutableLocator locator = new PiExecutableLocator(Path.of("/nonexistent/pi"));
        assertTrue(locator.locate().isEmpty());
        assertTrue(locator.describeSearched().contains("/nonexistent/pi"));
    }

    @Test
    void describeSearchedListsPathThenFallbacks() {
        PiExecutableLocator locator = new PiExecutableLocator(Path.of("/nonexistent/pi"));
        locator.locate();
        assertEquals("configured path /nonexistent/pi", locator.describeSearched());
    }
}
