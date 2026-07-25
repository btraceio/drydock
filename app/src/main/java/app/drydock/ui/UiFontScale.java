package app.drydock.ui;

import app.drydock.domain.WorkspaceUiState;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces a copy of {@code app.css} with every {@code -fx-font-size} scaled
 * by the user's interface size, materialised as a temp file whose URL the
 * scene uses in place of the bundled sheet (the same extract-once-per-process
 * pattern {@link TerminalThemes} uses for ghostty configs).
 *
 * <p>Two tempting alternatives are wrong. Rewriting the declarations as
 * {@code em} compounds, because JavaFX resolves font-relative sizes against
 * the font inherited from the nearest styleable ancestor, not against {@code
 * .root}: a 12px rule containing an 11px rule would multiply both factors.
 * An inline {@code -fx-font-size} on the scene root does override the {@code
 * .root} rule, but never reaches combo popups, context menus, or tooltips --
 * they are separate scene graphs, which is exactly why app.css styles them
 * with their own top-level selectors. A stylesheet reaches them; an inline
 * style does not.</p>
 */
final class UiFontScale {

    private static final Logger LOG = System.getLogger(UiFontScale.class.getName());

    /** Supported interface font sizes; the settings slider exposes the same band. */
    static final double MIN_FONT_SIZE = 11.0;
    static final double MAX_FONT_SIZE = 16.0;

    private static final String BASE_RESOURCE = "/app/drydock/ui/app.css";

    /**
     * Matches a {@code -fx-font-size} declaration's px literal (first
     * alternative) or a standalone CSS comment, passed through untouched so
     * text that merely looks like a declaration is never rewritten (second
     * alternative). The declaration alternative is tried first and allows
     * whitespace and comments between the colon and the value -- {@code
     * -fx-font-size: /* TODO bump *}{@code / 12px;} must still be recognised
     * as one declaration, or the comment would otherwise be consumed by the
     * standalone-comment alternative first, leaving the px literal beyond it
     * silently unscaled. Group 1 (property, separator, and any intervening
     * comments, preserved verbatim) and group 2 (the number) are set when
     * the declaration alternative matches; both are {@code null} when the
     * standalone-comment alternative matched instead.
     */
    private static final Pattern FONT_SIZE = Pattern.compile(
            "(-fx-font-size\\s*:\\s*(?:/\\*.*?\\*/\\s*)*)(\\d+(?:\\.\\d+)?)px|/\\*.*?\\*/",
            Pattern.DOTALL);

    private static final Map<Integer, String> GENERATED = new HashMap<>();

    private UiFontScale() {
    }

    /** The bundled, unscaled sheet's URL. */
    static String baseStylesheetUrl() {
        return UiFontScale.class.getResource(BASE_RESOURCE).toExternalForm();
    }

    /**
     * The stylesheet URL for {@code fontSize}, clamped to {@link
     * #MIN_FONT_SIZE}..{@link #MAX_FONT_SIZE} -- this is the point of
     * application and therefore owns the range. The default size returns the
     * bundled resource untouched; other sizes are generated once and cached.
     *
     * <p>Touches the filesystem on a cache miss, so this must only be called
     * from the FX thread when the caller already knows the size is cached or
     * default (e.g. {@code ThemeManager}'s constructor, which needs the sheet
     * in place synchronously before the stage is shown). Any FX-thread caller
     * that cannot make that guarantee -- a live slider drag, in particular --
     * must go through {@link #stylesheetForAsync} instead.</p>
     */
    static synchronized String stylesheetFor(double fontSize) {
        double clamped = Math.clamp(fontSize, MIN_FONT_SIZE, MAX_FONT_SIZE);
        int key = keyFor(clamped);
        if (key == keyFor(WorkspaceUiState.DEFAULT_UI_FONT_SIZE)) {
            return baseStylesheetUrl();
        }
        return GENERATED.computeIfAbsent(key, k -> generate(k / 2.0));
    }

    /** Already-cached or default stylesheet for {@code fontSize}, with no filesystem access at all. */
    private static synchronized Optional<String> cachedStylesheetFor(double fontSize) {
        double clamped = Math.clamp(fontSize, MIN_FONT_SIZE, MAX_FONT_SIZE);
        int key = keyFor(clamped);
        if (key == keyFor(WorkspaceUiState.DEFAULT_UI_FONT_SIZE)) {
            return Optional.of(baseStylesheetUrl());
        }
        return Optional.ofNullable(GENERATED.get(key));
    }

    private static int keyFor(double fontSize) {
        return (int) Math.round(fontSize * 2);   // 0.5px resolution, matching the slider
    }

    /**
     * As {@link #stylesheetFor}, but safe to call from the FX thread
     * unconditionally: a cache hit (or the default size) resolves {@code
     * onReady} synchronously with no I/O, and a cache miss generates on a
     * virtual thread and hands the result back via {@link Platform#runLater}.
     * A generation failure is logged and {@code onReady} is simply never
     * called, leaving whatever stylesheet is currently applied in place --
     * a slider drag that fails to produce a new size should not pop an
     * error dialog over what is otherwise a purely cosmetic change.
     */
    static void stylesheetForAsync(double fontSize, Consumer<String> onReady) {
        Optional<String> cached = cachedStylesheetFor(fontSize);
        if (cached.isPresent()) {
            onReady.accept(cached.get());
            return;
        }
        Thread.ofVirtual().start(() -> {
            String url;
            try {
                url = stylesheetFor(fontSize);
            } catch (UncheckedIOException e) {
                LOG.log(Level.WARNING, "Could not generate the scaled stylesheet for size "
                        + fontSize + "; keeping the current stylesheet", e);
                return;
            }
            Platform.runLater(() -> onReady.accept(url));
        });
    }

    private static String generate(double fontSize) {
        try (InputStream stream = UiFontScale.class.getResourceAsStream(BASE_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled stylesheet: " + BASE_RESOURCE);
            }
            String css = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            String scaled = scaleCss(css, fontSize / WorkspaceUiState.DEFAULT_UI_FONT_SIZE);
            Path dir = Files.createTempDirectory("drydock-ui-scale");
            dir.toFile().deleteOnExit();
            Path file = dir.resolve("app.css");
            Files.writeString(file, scaled, StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
            return file.toUri().toURL().toExternalForm();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not generate the scaled stylesheet", e);
        }
    }

    /**
     * Multiplies every {@code -fx-font-size} px literal by {@code factor},
     * rounded to two decimals, leaving the rest of the text byte-identical.
     */
    static String scaleCss(String css, double factor) {
        Matcher matcher = FONT_SIZE.matcher(css);
        StringBuilder out = new StringBuilder(css.length());
        while (matcher.find()) {
            if (matcher.group(1) == null) {
                // A comment matched; pass it through untouched.
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            double scaled = Double.parseDouble(matcher.group(2)) * factor;
            double rounded = Math.round(scaled * 100.0) / 100.0;
            matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) + rounded + "px"));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
