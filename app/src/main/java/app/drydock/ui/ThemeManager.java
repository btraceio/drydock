package app.drydock.ui;

import app.drydock.domain.UiTheme;
import javafx.scene.Scene;
import javafx.scene.text.Font;

import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.function.Consumer;

/**
 * Runtime theming (design handoff "Target stack"): the scene always carries
 * {@code app.css} (structure, no colors) plus exactly one of {@code
 * theme-dark.css} / {@code theme-light.css} (color tokens only). Toggling
 * swaps the token sheet in place; persistence of the choice is delegated to
 * the {@code onThemeChanged} callback so this class stays free of any state
 * dependency.
 *
 * <p>Also owns one-time registration of the bundled JetBrains Mono faces
 * (handoff "Assets"): {@code Font.loadFont} must run before any CSS lookup
 * of the family name resolves.</p>
 */
public final class ThemeManager {

    private static final Logger LOG = System.getLogger(ThemeManager.class.getName());

    private static final String CSS_BASE = "/app/drydock/ui/";
    private static final String[] BUNDLED_FONTS = {
            "fonts/JetBrainsMono-Regular.ttf",
            "fonts/JetBrainsMono-Medium.ttf",
            "fonts/JetBrainsMono-SemiBold.ttf",
            "fonts/JetBrainsMono-Bold.ttf",
    };

    private static boolean fontsLoaded;

    private final Scene scene;
    private final Consumer<UiTheme> onThemeChanged;
    private UiTheme theme;

    public ThemeManager(Scene scene, UiTheme initialTheme, Consumer<UiTheme> onThemeChanged) {
        this.scene = scene;
        this.onThemeChanged = onThemeChanged;
        this.theme = initialTheme;
        loadBundledFonts();
        apply();
    }

    public UiTheme theme() {
        return theme;
    }

    public void toggle() {
        theme = theme.other();
        apply();
        onThemeChanged.accept(theme);
    }

    private void apply() {
        scene.getStylesheets().setAll(
                resource("app.css"),
                resource(theme.stylesheet()));
    }

    private static String resource(String name) {
        return ThemeManager.class.getResource(CSS_BASE + name).toExternalForm();
    }

    private static synchronized void loadBundledFonts() {
        if (fontsLoaded) {
            return;
        }
        fontsLoaded = true;
        for (String font : BUNDLED_FONTS) {
            try (InputStream stream = ThemeManager.class.getResourceAsStream(CSS_BASE + font)) {
                if (stream == null || Font.loadFont(stream, 12) == null) {
                    LOG.log(Level.WARNING, "Could not load bundled font {0}", font);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Could not load bundled font " + font, e);
            }
        }
    }
}
