package app.drydock.agent.providers.pi;

import app.drydock.agent.providers.pi.internal.PiActivityExtensionSource;
import app.drydock.agent.providers.pi.internal.PiExtensionInstaller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiActivityReporterTest {

    @Test
    void installWritesTheExtensionAndPurgesStaleState(@TempDir Path state) throws Exception {
        Path activity = Files.createDirectories(state.resolve("activity"));
        // A leftover from a force-killed pi of a previous app run: no process
        // survives the host JVM, so every state file at startup is stale.
        Path stale = Files.writeString(activity.resolve("019f-stale-session"), "attention");
        PiActivityReporter reporter = new PiActivityReporter(
                PiExtensionInstaller.activity(state), activity);

        reporter.install();

        Path extension = state.resolve("pi").resolve("drydock-activity.ts");
        assertEquals(PiActivityExtensionSource.SOURCE, Files.readString(extension));
        assertTrue(Files.notExists(stale), "stale state must not badge a dead session");
    }

    /** Reporting needs no launch-time settings flag; the launch command adds the -e flag itself. */
    @Test
    void noSettingsFile(@TempDir Path state) {
        PiActivityReporter reporter = new PiActivityReporter(
                PiExtensionInstaller.activity(state), state.resolve("activity"));

        assertTrue(reporter.settingsFile().isEmpty());
    }
}
