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
 * a (possibly font-scaled, see {@link UiFontScale}) {@code app.css}
 * (structure, no colors) plus exactly one of {@code theme-dark.css} /
 * {@code theme-light.css} (color tokens only). Toggling swaps the token
 * sheet in place; persistence of the choice is delegated to the {@code
 * onThemeChanged} callback so this class stays free of any state
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
    private double uiFontSize;
    private double pendingUiFontSize;
    private String appliedStylesheetUrl;

    public ThemeManager(Scene scene, UiTheme initialTheme, double initialUiFontSize,
                        Consumer<UiTheme> onThemeChanged) {
        this.scene = scene;
        this.onThemeChanged = onThemeChanged;
        this.theme = initialTheme;
        this.uiFontSize = Math.clamp(initialUiFontSize, UiFontScale.MIN_FONT_SIZE, UiFontScale.MAX_FONT_SIZE);
        this.pendingUiFontSize = this.uiFontSize;
        loadBundledFonts();
        apply();
    }

    public UiTheme theme() {
        return theme;
    }

    public double uiFontSize() {
        return uiFontSize;
    }

    public void toggle() {
        setTheme(theme.other());
    }

    /** Sets the theme absolutely (the settings modal's radio); {@link #toggle} delegates here. */
    public void setTheme(UiTheme newTheme) {
        if (newTheme == theme) {
            return;
        }
        theme = newTheme;
        apply();
        onThemeChanged.accept(theme);
    }

    /**
     * Applies an interface font size by swapping in a stylesheet whose
     * font sizes are scaled (see {@link UiFontScale}); clamped here because
     * this is the point of application. Persisting the choice is the
     * caller's job, exactly as with the theme.
     *
     * <p>Called on every tick of the settings modal's slider while the user
     * drags, so it must never block the FX thread: the lookup goes through
     * {@link UiFontScale#stylesheetForAsync}, which resolves synchronously
     * on a cache hit and off-thread on a miss. {@code pendingUiFontSize}
     * records the latest request and is re-checked when the async result
     * lands, so a stale callback from a superseded drag position can never
     * clobber a newer one -- the same pattern {@code MainWorkspace}'s
     * {@code applyTerminalConfig} uses for the terminal config.</p>
     *
     * <p>{@link #uiFontSize} therefore lags a request by one FX event on a
     * cache miss, and callers must not read it back to learn what the user
     * asked for -- persistence works from the value the user chose (see
     * {@link SizeSetting}). What it does guarantee is that every size it
     * holds has already been resolved to a stylesheet, hence cached, which
     * is what {@link #apply} relies on.</p>
     */
    public void setUiFontSize(double newUiFontSize) {
        double clamped = Math.clamp(newUiFontSize, UiFontScale.MIN_FONT_SIZE, UiFontScale.MAX_FONT_SIZE);
        if (clamped == pendingUiFontSize) {
            return;
        }
        pendingUiFontSize = clamped;
        UiFontScale.stylesheetForAsync(clamped, url -> {
            if (clamped != pendingUiFontSize) {
                return;
            }
            uiFontSize = clamped;
            if (!url.equals(appliedStylesheetUrl)) {
                applyStylesheets(url);
            }
        });
    }

    /**
     * Synchronous re-application, used by the constructor (before the stage
     * is shown, where blocking is required, not merely tolerated: the scaled
     * sheet has to be in place already, or the first layout would happen at
     * the wrong size and visibly re-flow) and by {@link #setTheme} (a cache
     * hit guaranteed: {@link #uiFontSize} only ever holds a size whose
     * stylesheet has already been resolved -- see {@link #setUiFontSize} --
     * so this never touches disk).
     */
    private void apply() {
        applyStylesheets(UiFontScale.stylesheetFor(uiFontSize));
    }

    /**
     * Swaps in {@code fontSheetUrl} plus the current theme's token sheet,
     * unconditionally -- {@link #setTheme} relies on that to always pick up
     * the new theme resource, even when the font stylesheet URL is
     * unchanged. {@link #setUiFontSize} carries its own "unchanged since
     * last application" check before calling this (see its callback), since
     * a slider drag re-running a full-scene CSS reapply on essentially
     * every tick -- even though most ticks resolve to the same
     * 0.5px-quantised stylesheet, see {@link UiFontScale#stylesheetFor} --
     * would otherwise be wasted work.
     */
    private void applyStylesheets(String fontSheetUrl) {
        appliedStylesheetUrl = fontSheetUrl;
        scene.getStylesheets().setAll(fontSheetUrl, resource(theme.stylesheet()));
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
