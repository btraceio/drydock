package app.drydock.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract {@code ThemeManager} depends on and that no compiler
 * enforces: {@code theme-dark.css} and {@code theme-light.css} define the
 * <em>identical</em> set of token names, {@code app.css} refers to no token
 * that is missing from them, and every font size in {@code app.css} is a
 * {@code px} literal so {@link UiFontScale} can scale it.
 *
 * <p>Without this, adding a token to one sheet and forgetting the other
 * shows up as an unstyled node in whichever theme the developer was not
 * using -- and a font size expressed in any other unit silently stops
 * responding to the interface-size slider.</p>
 */
class ThemeTokenContractTest {

    private static final Pattern DEFINITION = Pattern.compile("(-drydock-[a-z0-9-]+)\\s*:");
    private static final Pattern REFERENCE = Pattern.compile("(-drydock-[a-z0-9-]+)");
    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern FONT_SIZE = Pattern.compile("-fx-font-size\\s*:\\s*([^;}]+)");

    @Test
    void bothThemeSheetsDefineTheIdenticalSetOfTokens() {
        Set<String> dark = definitionsIn("theme-dark.css");
        Set<String> light = definitionsIn("theme-light.css");

        assertEquals(new TreeSet<>(dark), new TreeSet<>(light),
                "theme-dark.css and theme-light.css must define the same token names");
    }

    @Test
    void appCssOnlyReferencesTokensBothThemesDefine() {
        Set<String> defined = definitionsIn("theme-dark.css");
        Set<String> referenced = new LinkedHashSet<>(referencesIn("app.css"));

        referenced.removeAll(defined);
        assertTrue(referenced.isEmpty(), "app.css refers to undefined theme tokens: " + referenced);
    }

    @Test
    void everyAppCssFontSizeIsAPxLiteralSoTheInterfaceSizeSliderScalesIt() {
        Matcher matcher = FONT_SIZE.matcher(stripComments(read("app.css")));
        int checked = 0;
        while (matcher.find()) {
            String value = matcher.group(1).strip();
            assertTrue(value.matches("\\d+(\\.\\d+)?px"),
                    "app.css font size '" + value + "' is not a px literal, so UiFontScale cannot scale it");
            checked++;
        }
        assertTrue(checked > 50, "expected app.css to declare many font sizes; found " + checked);
    }

    private static Set<String> definitionsIn(String sheet) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = DEFINITION.matcher(stripComments(read(sheet)));
        while (matcher.find()) {
            tokens.add(matcher.group(1));
        }
        return tokens;
    }

    private static Set<String> referencesIn(String sheet) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = REFERENCE.matcher(stripComments(read(sheet)));
        while (matcher.find()) {
            tokens.add(matcher.group(1));
        }
        return tokens;
    }

    private static String stripComments(String css) {
        return COMMENT.matcher(css).replaceAll(" ");
    }

    private static String read(String sheet) {
        try (InputStream stream = ThemeTokenContractTest.class
                .getResourceAsStream("/app/drydock/ui/" + sheet)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled stylesheet: " + sheet);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
