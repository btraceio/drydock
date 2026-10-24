package app.drydock.agent.providers.pi;

import app.drydock.agent.api.ActivityReporter;
import app.drydock.agent.providers.pi.internal.PiExtensionInstaller;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Installs pi's activity extension ({@code drydock-activity.ts}) so managed
 * pi sessions report {@code busy}/{@code idle}/{@code attention} state words
 * into the shared activity directory -- the same contract the Claude hook
 * script serves, which is what makes the "needs you" balloon work for pi.
 *
 * <p>{@link #settingsFile()} is always empty by contract: pi needs no
 * launch-time settings flag; enabling reporting is the {@code -e} flag the
 * provider's launch command adds for the installed extension itself.</p>
 */
final class PiActivityReporter implements ActivityReporter {

    private static final Logger LOG = System.getLogger(PiActivityReporter.class.getName());

    private final PiExtensionInstaller installer;
    private final Path activityDirectory;

    PiActivityReporter(PiExtensionInstaller installer, Path activityDirectory) {
        this.installer = installer;
        this.activityDirectory = activityDirectory;
    }

    @Override
    public void install() throws IOException {
        installer.install();
        purgeStaleActivity();
    }

    @Override
    public Optional<Path> settingsFile() {
        return Optional.empty();
    }

    /** The directory the extension writes state words into; also where the launch env points. */
    Path activityDirectory() {
        return activityDirectory;
    }

    /**
     * Drops activity left behind by a previous app run.
     *
     * <p>No agent process survives the host JVM, so every state file present
     * at startup is by definition stale -- the same rule
     * {@code ClaudeHookInstaller.purgeStaleActivity} applies, and for the same
     * reason: a force-killed pi never runs {@code session_shutdown}, so its
     * file would badge a dead session on every subsequent launch. A failure
     * here is cosmetic and must not prevent installation.</p>
     */
    private void purgeStaleActivity() {
        if (!Files.isDirectory(activityDirectory)) {
            return;
        }
        try (Stream<Path> stale = Files.list(activityDirectory)) {
            for (Path file : stale.toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not purge stale session activity: " + e.getMessage());
        }
    }
}
