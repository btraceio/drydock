package app.drydock.ui;

import app.drydock.domain.UiTheme;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides the on-disk ghostty config file matching each {@link UiTheme} and
 * terminal font size. The theme bodies live as classpath resources ({@code
 * terminal-dark.conf} / {@code terminal-light.conf}, kept next to the theme
 * CSS whose tokens they mirror) but {@code ghostty_config_load_file} needs a
 * real path, so each (theme, size) pair is materialised once per process
 * into a private temp directory.
 *
 * <p>The font size rides in the config rather than in {@code
 * ghostty_surface_config_s.font_size} deliberately: a theme toggle calls
 * {@code ghostty_surface_update_config}, which re-derives a running
 * surface's configuration from the app config and would drop a per-surface
 * override, silently resetting the user's terminal size mid-session. Going
 * through the config file instead means a size change applies live to
 * running surfaces over the very same path.</p>
 */
final class TerminalThemes {

    /** Supported terminal font sizes; the settings slider exposes the same band. */
    static final double MIN_FONT_SIZE = 10.0;
    static final double MAX_FONT_SIZE = 18.0;

    private static final Map<Key, Path> EXTRACTED = new HashMap<>();

    /** Cache key: one materialised config file per theme and rounded font size. */
    private record Key(UiTheme theme, int fontSize) {
    }

    private TerminalThemes() {
    }

    /**
     * The config file for {@code theme} at {@code fontSize} points, clamped
     * to {@link #MIN_FONT_SIZE}..{@link #MAX_FONT_SIZE} -- this is the point
     * of application, and therefore the owner of the range (the codec
     * deliberately stores whatever it was given).
     */
    static synchronized Path configFileFor(UiTheme theme, double fontSize) {
        int rounded = (int) Math.round(Math.clamp(fontSize, MIN_FONT_SIZE, MAX_FONT_SIZE));
        return EXTRACTED.computeIfAbsent(new Key(theme, rounded), TerminalThemes::extract);
    }

    private static Path extract(Key key) {
        String resource = key.theme() == UiTheme.LIGHT ? "terminal-light.conf" : "terminal-dark.conf";
        try (InputStream stream = TerminalThemes.class.getResourceAsStream("/app/drydock/ui/" + resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled terminal theme resource: " + resource);
            }
            String body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Path dir = Files.createTempDirectory("drydock-terminal-theme");
            dir.toFile().deleteOnExit();
            Path file = dir.resolve(resource);
            Files.writeString(file, body + System.lineSeparator()
                    + "font-size = " + key.fontSize() + System.lineSeparator(), StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not extract terminal theme config " + resource, e);
        }
    }
}
