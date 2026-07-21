package app.drydock.ui;

import app.drydock.domain.UiTheme;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;

/**
 * Provides the on-disk ghostty config file matching each {@link UiTheme}.
 * The configs live as classpath resources ({@code terminal-dark.conf} /
 * {@code terminal-light.conf}, kept next to the theme CSS whose tokens
 * they mirror) but {@code ghostty_config_load_file} needs a real path, so
 * they are extracted once per process into a private temp directory.
 */
final class TerminalThemes {

    private static final Map<UiTheme, Path> EXTRACTED = new EnumMap<>(UiTheme.class);

    private TerminalThemes() {
    }

    static synchronized Path configFileFor(UiTheme theme) {
        return EXTRACTED.computeIfAbsent(theme, TerminalThemes::extract);
    }

    private static Path extract(UiTheme theme) {
        String resource = theme == UiTheme.LIGHT ? "terminal-light.conf" : "terminal-dark.conf";
        try (InputStream stream = TerminalThemes.class.getResourceAsStream("/app/drydock/ui/" + resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled terminal theme resource: " + resource);
            }
            Path dir = Files.createTempDirectory("drydock-terminal-theme");
            dir.toFile().deleteOnExit();
            Path file = dir.resolve(resource);
            Files.copy(stream, file, StandardCopyOption.REPLACE_EXISTING);
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not extract terminal theme config " + resource, e);
        }
    }
}
