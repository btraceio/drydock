package app.drydock.agent.providers.claude;

import app.drydock.agent.api.ActivityReporter;
import app.drydock.claude.ClaudeHookInstaller;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Installs Claude's activity hooks via {@link ClaudeHookInstaller}.
 *
 * <p>{@link #settingsFile()} reports empty until {@link #install()} has
 * succeeded at least once: a hook-install failure (permissions, a read-only
 * home directory, ...) must never surface a {@code --settings} flag pointing
 * at a file that was never actually written, because a badge/activity
 * feature failing must not break a session launch.</p>
 */
final class ClaudeActivityReporter implements ActivityReporter {

    private final ClaudeHookInstaller installer;
    private volatile boolean installed;

    ClaudeActivityReporter(ClaudeHookInstaller installer) {
        this.installer = installer;
    }

    @Override
    public void install() throws IOException {
        installer.install();
        installed = true;
    }

    @Override
    public Optional<Path> settingsFile() {
        return installed ? Optional.of(installer.settingsFile()) : Optional.empty();
    }
}
