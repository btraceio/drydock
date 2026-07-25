package app.drydock.ui;

import app.drydock.domain.UiTheme;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated ghostty config carries the user's terminal font size, and
 * each (theme, size) pair gets its own file so switching back and forth
 * never re-extracts or cross-contaminates.
 */
class TerminalThemesTest {

    @Test
    void writesTheRequestedFontSizeIntoTheConfig() throws IOException {
        Path config = TerminalThemes.configFileFor(UiTheme.DARK, 15.0);

        String text = Files.readString(config, StandardCharsets.UTF_8);

        assertTrue(text.contains("font-size = 15"), text);
        // The bundled theme content must survive alongside it.
        assertTrue(text.contains("background = #161514"), text);
    }

    @Test
    void clampsAnOutOfRangeSizeToTheSupportedBand() throws IOException {
        Path tooLarge = TerminalThemes.configFileFor(UiTheme.DARK, 900.0);
        Path tooSmall = TerminalThemes.configFileFor(UiTheme.DARK, 0.0);

        assertTrue(Files.readString(tooLarge, StandardCharsets.UTF_8).contains("font-size = 18"));
        assertTrue(Files.readString(tooSmall, StandardCharsets.UTF_8).contains("font-size = 10"));
    }

    @Test
    void cachesPerThemeAndSize() {
        Path darkThirteen = TerminalThemes.configFileFor(UiTheme.DARK, 13.0);

        assertEquals(darkThirteen, TerminalThemes.configFileFor(UiTheme.DARK, 13.0));
        assertNotEquals(darkThirteen, TerminalThemes.configFileFor(UiTheme.DARK, 14.0));
        assertNotEquals(darkThirteen, TerminalThemes.configFileFor(UiTheme.LIGHT, 13.0));
    }

    @Test
    void asyncResolvesAnAlreadyCachedPairSynchronouslyOnTheCallingThread() {
        // Warm the cache via the synchronous path first (as a theme toggle
        // ordinarily would), then confirm the async entry point recognises
        // the cache hit and never leaves the calling thread -- this is what
        // lets MainWorkspace.applyTerminalTheme call it unconditionally from
        // the settings modal's font-size slider.
        Path expected = TerminalThemes.configFileFor(UiTheme.DARK, 12.0);
        Thread callingThread = Thread.currentThread();
        AtomicReference<Path> result = new AtomicReference<>();
        AtomicReference<Thread> calledFrom = new AtomicReference<>();

        TerminalThemes.configFileForAsync(UiTheme.DARK, 12.0, file -> {
            result.set(file);
            calledFrom.set(Thread.currentThread());
        });

        assertEquals(expected, result.get());
        assertEquals(callingThread, calledFrom.get());
    }
}
