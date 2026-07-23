package app.drydock.agent.providers.claude;

import app.drydock.agent.api.ActivityReporter;
import app.drydock.claude.ClaudeHookInstaller;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** Installs Claude's activity hooks via {@link ClaudeHookInstaller}. */
final class ClaudeActivityReporter implements ActivityReporter {

    private final ClaudeHookInstaller installer;

    ClaudeActivityReporter(ClaudeHookInstaller installer) {
        this.installer = installer;
    }

    @Override
    public void install() throws IOException {
        installer.install();
    }

    @Override
    public Optional<Path> settingsFile() {
        return Optional.of(installer.settingsFile());
    }
}
