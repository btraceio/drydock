package app.drydock.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitExecutableLocatorTest {

    @Test
    void discoversGitViaPathOrFallback() {
        // Environment fact (see task context): git is installed on this
        // machine, so PATH/fallback discovery must find it without any
        // explicit configuration.
        GitExecutableLocator locator = new GitExecutableLocator();
        assertTrue(locator.locate().isPresent(), "expected git to be discoverable on this machine");
    }

    @Test
    void explicitPathIsUsedWhenValid(@TempDir Path tempDir) throws IOException {
        Path fakeGit = tempDir.resolve("git");
        Files.writeString(fakeGit, "#!/bin/sh\nexit 0\n");
        fakeGit.toFile().setExecutable(true);

        GitExecutableLocator locator = new GitExecutableLocator(fakeGit);
        assertEquals(fakeGit, locator.locate().orElseThrow());
    }

    @Test
    void explicitInvalidPathIsNotFoundAndDoesNotFallBack() {
        GitExecutableLocator locator = new GitExecutableLocator(Path.of("/nonexistent/does-not-exist/git"));
        assertTrue(locator.locate().isEmpty());
        assertTrue(locator.describeSearched().contains("nonexistent"));
    }

    @Test
    void resultIsCached() {
        GitExecutableLocator locator = new GitExecutableLocator();
        assertEquals(locator.locate(), locator.locate());
    }
}
