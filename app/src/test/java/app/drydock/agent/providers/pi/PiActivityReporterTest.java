package app.drydock.agent.providers.pi;

import app.drydock.agent.providers.pi.internal.PiActivityExtensionSource;
import app.drydock.agent.providers.pi.internal.PiExtensionInstaller;
import app.drydock.agent.providers.pi.internal.PiPrScanExtensionSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiActivityReporterTest {

    @Test
    void installWritesTheExtensionsAndPurgesStaleState(@TempDir Path state) throws Exception {
        Path activity = Files.createDirectories(state.resolve("activity"));
        // A leftover from a force-killed pi of a previous app run: no process
        // survives the host JVM, so every state file at startup is stale.
        Path stale = Files.writeString(activity.resolve("019f-stale-session"), "attention");
        Path prLinks = Files.createDirectories(state.resolve("pr-links"));
        Path staleRef = Files.writeString(prLinks.resolve("019f-stale-session"), "DataDog/java-profiler#1034");
        PiActivityReporter reporter = new PiActivityReporter(
                PiExtensionInstaller.activity(state), PiExtensionInstaller.prScan(state),
                activity, prLinks);

        reporter.install();

        Path extension = state.resolve("pi").resolve("drydock-activity.ts");
        assertEquals(PiActivityExtensionSource.SOURCE, Files.readString(extension));
        Path prScan = state.resolve("pi").resolve("drydock-pr-scan.ts");
        assertEquals(PiPrScanExtensionSource.SOURCE, Files.readString(prScan));
        assertTrue(Files.notExists(stale), "stale state must not badge a dead session");
        assertTrue(Files.notExists(staleRef), "stale PR references must not associate a dead session");
    }

    /** Reporting needs no launch-time settings flag; the launch command adds the -e flags itself. */
    @Test
    void noSettingsFile(@TempDir Path state) {
        PiActivityReporter reporter = new PiActivityReporter(
                PiExtensionInstaller.activity(state), PiExtensionInstaller.prScan(state),
                state.resolve("activity"), state.resolve("pr-links"));

        assertTrue(reporter.settingsFile().isEmpty());
    }
}
