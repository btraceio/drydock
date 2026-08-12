package app.drydock.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scaling multiplies every {@code -fx-font-size} px literal by the factor
 * and touches nothing else.
 *
 * <p>The nested-rule test is the important one. The rejected alternative --
 * rewriting the declarations as {@code em} -- looks correct line by line but
 * compounds, because JavaFX resolves font-relative sizes against the
 * inherited font rather than against {@code .root}: {@code .code-area}
 * (12px) containing {@code .lineno} (11px) would land at 10.15px instead of
 * 13.54px at factor 16/13. Absolute scaling keeps the ratio exact.</p>
 */
class UiFontScaleTest {

    @Test
    void scalesEveryFontSizeDeclaration() {
        String scaled = UiFontScale.scaleCss(".root { -fx-font-size: 13px; }", 16.0 / 13.0);

        assertTrue(scaled.contains("-fx-font-size: 16.0px"), scaled);
    }

    @Test
    void nestedRulesKeepTheirRatioInsteadOfCompounding() {
        String css = """
                .code-area { -fx-font-size: 12px; }
                .code-area .lineno { -fx-font-size: 11px; }
                """;

        String scaled = UiFontScale.scaleCss(css, 16.0 / 13.0);

        // 12 * 16/13 = 14.769..., 11 * 16/13 = 13.538...
        assertTrue(scaled.contains("-fx-font-size: 14.77px"), scaled);
        assertTrue(scaled.contains("-fx-font-size: 13.54px"), scaled);
    }

    @Test
    void preservesFractionalSourceSizes() {
        String scaled = UiFontScale.scaleCss(".pill { -fx-font-size: 12.5px; }", 2.0);

        assertTrue(scaled.contains("-fx-font-size: 25.0px"), scaled);
    }

    @Test
    void leavesEverythingThatIsNotAFontSizeAlone() {
        String css = """
                /* -fx-font-size: 99px in a comment must not move */
                .filter-field {
                    -fx-min-height: 32px;
                    -fx-max-height: 32px;
                    -fx-background-color: #161514;
                    -fx-font-family: "System";
                    -fx-padding: 0.5em;
                }
                """;

        String scaled = UiFontScale.scaleCss(css, 2.0);

        assertTrue(scaled.contains("-fx-min-height: 32px"), scaled);
        assertTrue(scaled.contains("-fx-max-height: 32px"), scaled);
        assertTrue(scaled.contains("-fx-background-color: #161514"), scaled);
        assertTrue(scaled.contains("-fx-padding: 0.5em"), scaled);
        assertTrue(scaled.contains("99px in a comment"), scaled);
    }

    @Test
    void scalesADeclarationWithACommentBetweenTheColonAndTheValue() {
        // A comment sitting between the colon and the value must not split
        // the declaration into an unrecognised property and a bare number:
        // the whole thing is still one declaration and the px literal must
        // still be scaled, with the comment text preserved verbatim.
        String scaled = UiFontScale.scaleCss(
                ".pill { -fx-font-size: /* TODO bump */ 12px; }", 2.0);

        assertTrue(scaled.contains("-fx-font-size: /* TODO bump */ 24.0px"), scaled);
    }

    @Test
    void toleratesSpacingVariantsInTheSourceDeclaration() {
        String scaled = UiFontScale.scaleCss(".a{-fx-font-size:10px}", 2.0);

        assertTrue(scaled.contains("20.0px"), scaled);
        assertFalse(scaled.contains("10px"), scaled);
    }

    @Test
    void identityFactorReturnsTheOriginalResourceUrl() {
        // The unscaled case must not generate anything at all: it is both the
        // default and the hot path at startup.
        assertEquals(UiFontScale.baseStylesheetUrl(), UiFontScale.stylesheetFor(13.0));
    }

    /**
     * The sheet is carried IN the URL, not written beside it. A temp file was
     * the first implementation and it failed silently on a machine whose
     * temp directory it could not write: generation threw, the unscaled sheet
     * was cached in its place, and the interface size then did nothing at all
     * with nothing on screen to say why. Asserting the scheme is asserting
     * that no environment sits between choosing a size and applying it.
     */
    @Test
    void scaledSizeProducesAUsableStylesheetUrl() {
        String url = UiFontScale.stylesheetFor(16.0);

        assertTrue(url.startsWith("data:text/css;base64,"), url);
        assertFalse(url.equals(UiFontScale.baseStylesheetUrl()), url);
    }

    /** And what the URL carries is the scaled sheet, not merely a well-formed one. */
    @Test
    void theUrlCarriesTheScaledStylesheet() {
        String url = UiFontScale.stylesheetFor(16.0);

        String css = new String(Base64.getDecoder().decode(
                url.substring("data:text/css;base64,".length())), StandardCharsets.UTF_8);

        // .root is 13px unscaled; 16/13 of it, to two decimals.
        assertTrue(css.contains("-fx-font-size: 16.0px;"), "the .root size must be the chosen one");
        assertFalse(css.contains("-fx-font-size: 13px;"), "no declaration may be left unscaled");
    }

    @Test
    void asyncResolvesTheDefaultSizeSynchronouslyOnTheCallingThread() {
        // The whole point of the async entry point is that an FX-thread
        // caller can call it unconditionally; the default-size fast path
        // must never hop to another thread to prove that.
        Thread callingThread = Thread.currentThread();
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Thread> calledFrom = new AtomicReference<>();

        UiFontScale.stylesheetForAsync(13.0, url -> {
            result.set(url);
            calledFrom.set(Thread.currentThread());
        });

        assertEquals(UiFontScale.baseStylesheetUrl(), result.get());
        assertEquals(callingThread, calledFrom.get());
    }

    @Test
    void asyncResolvesAnAlreadyCachedSizeSynchronouslyOnTheCallingThread() {
        // Warm the cache via the synchronous path first (as ThemeManager's
        // constructor does at startup), then confirm the async entry point
        // recognises the cache hit and never leaves the calling thread.
        String expected = UiFontScale.stylesheetFor(14.5);
        Thread callingThread = Thread.currentThread();
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Thread> calledFrom = new AtomicReference<>();

        UiFontScale.stylesheetForAsync(14.5, url -> {
            result.set(url);
            calledFrom.set(Thread.currentThread());
        });

        assertEquals(expected, result.get());
        assertEquals(callingThread, calledFrom.get());
    }
}
