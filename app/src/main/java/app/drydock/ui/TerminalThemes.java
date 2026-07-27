package app.drydock.ui;

import app.drydock.domain.UiTheme;
import javafx.application.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

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

    private static final Logger LOG = System.getLogger(TerminalThemes.class.getName());

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
     *
     * <p>Touches the filesystem on a cache miss. Safe to call on the FX
     * thread only when the caller is <b>warmed-by-construction</b>: {@code
     * MainWorkspace.createOpenSessionTab} calls this synchronously for the
     * current (theme, terminal font size) pair, which {@code
     * DrydockApplication} warms once at startup and {@code MainWorkspace}
     * re-warms (through {@link #configFileForAsync}) on every theme toggle
     * and terminal-size change, so a session-open call is a cache hit in
     * the common case. That is not a guarantee, only the common case: a
     * session opened fast enough to outrun a just-issued warm still falls
     * back to doing the extraction inline, on the FX thread. A caller that
     * cannot make the warmed-by-construction argument (a live slider drag,
     * in particular) must go through {@link #configFileForAsync}
     * instead.</p>
     */
    static synchronized Path configFileFor(UiTheme theme, double fontSize) {
        int rounded = roundedFontSize(fontSize);
        return EXTRACTED.computeIfAbsent(new Key(theme, rounded), TerminalThemes::extract);
    }

    /** Already-extracted config file for {@code (theme, fontSize)}, with no filesystem access at all. */
    private static synchronized Optional<Path> cachedConfigFileFor(UiTheme theme, double fontSize) {
        return Optional.ofNullable(EXTRACTED.get(new Key(theme, roundedFontSize(fontSize))));
    }

    private static int roundedFontSize(double fontSize) {
        return (int) Math.round(Math.clamp(fontSize, MIN_FONT_SIZE, MAX_FONT_SIZE));
    }

    /**
     * As {@link #configFileFor}, but safe to call from the FX thread
     * unconditionally: a cache hit resolves {@code onReady} synchronously
     * with no I/O, and a cache miss extracts on a virtual thread and hands
     * the result back via {@link Platform#runLater}. A failure is logged and
     * {@code onReady} is simply never called, leaving whatever config is
     * currently applied in place.
     */
    static void configFileForAsync(UiTheme theme, double fontSize, Consumer<Path> onReady) {
        Optional<Path> cached = cachedConfigFileFor(theme, fontSize);
        if (cached.isPresent()) {
            onReady.accept(cached.get());
            return;
        }
        Thread.ofVirtual().start(() -> {
            Path file;
            try {
                file = configFileFor(theme, fontSize);
            } catch (UncheckedIOException e) {
                LOG.log(Level.WARNING, "Could not extract the terminal theme config for " + theme
                        + " at size " + fontSize + "; keeping the current config", e);
                return;
            }
            Platform.runLater(() -> onReady.accept(file));
        });
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
