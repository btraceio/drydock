package app.drydock.git;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GhExecutableLocatorTest {

    @Test
    void checksDogbrewShimBeforePathAndOrdinaryFallbacks() {
        List<Path> candidates = GhExecutableLocator.searchOrder(
                String.join(File.pathSeparator, "/custom/bin", "/usr/local/bin"));

        assertEquals(List.of(
                Path.of("/opt/dogbrew/shims/bin/gh"),
                Path.of("/custom/bin/gh"),
                Path.of("/usr/local/bin/gh"),
                Path.of("/opt/homebrew/bin/gh")), candidates);
    }
}
