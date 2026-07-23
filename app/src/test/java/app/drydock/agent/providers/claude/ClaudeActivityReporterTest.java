package app.drydock.agent.providers.claude;

import app.drydock.agent.providers.claude.internal.ClaudeHookInstaller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the invariant documented on {@link ClaudeActivityReporter}: {@code
 * settingsFile()} must report empty until {@code install()} has succeeded,
 * so a hook-install failure can never surface a {@code --settings} flag
 * pointing at a file that was never written.
 */
class ClaudeActivityReporterTest {

    @Test
    void settingsFileIsEmptyBeforeInstall(@TempDir Path baseDir) {
        ClaudeActivityReporter reporter = new ClaudeActivityReporter(new ClaudeHookInstaller(baseDir));

        assertTrue(reporter.settingsFile().isEmpty());
    }

    @Test
    void settingsFileIsPresentAfterSuccessfulInstall(@TempDir Path baseDir) throws IOException {
        ClaudeActivityReporter reporter = new ClaudeActivityReporter(new ClaudeHookInstaller(baseDir));

        reporter.install();

        assertTrue(reporter.settingsFile().isPresent());
    }

    @Test
    void settingsFileStaysEmptyAfterFailedInstall(@TempDir Path tempDir) throws IOException {
        // A regular file cannot have children, so Files.createDirectories on a
        // path beneath it deterministically throws inside install().
        Path regularFile = tempDir.resolve("not-a-directory");
        Files.createFile(regularFile);
        Path unusableBaseDir = regularFile.resolve("sub");

        ClaudeActivityReporter reporter = new ClaudeActivityReporter(new ClaudeHookInstaller(unusableBaseDir));

        assertThrows(IOException.class, reporter::install);
        assertFalse(reporter.settingsFile().isPresent());
    }
}
