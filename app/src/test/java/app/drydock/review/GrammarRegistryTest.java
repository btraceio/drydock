package app.drydock.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A grammar that is not on the classpath is the lexical path, not an error
 * (spec §10.2). That single rule is what keeps the shipped language set a
 * packaging decision rather than an architectural one -- the .app and the
 * jbang jar may ship different sets, and an unsupported language produces a
 * coarser surface rather than a broken one.
 */
class GrammarRegistryTest {

    @Test
    void aShippedLanguageResolvesToAGrammar() {
        assertTrue(GrammarRegistry.forPath("src/Main.java").isPresent());
    }

    @Test
    void anUnshippedLanguageResolvesToNothingWithoutThrowing() {
        assertTrue(GrammarRegistry.forPath("build/config.zig").isEmpty());
    }

    @Test
    void aFileWithNoExtensionResolvesToNothing() {
        assertTrue(GrammarRegistry.forPath("Makefile").isEmpty());
    }

    /** Case is not a language: .JAVA is Java. */
    @Test
    void extensionMatchingIsCaseInsensitive() {
        assertTrue(GrammarRegistry.forPath("src/Main.JAVA").isPresent());
    }

    @Test
    void aDirectoryEndingInAKnownExtensionIsNotAFile() {
        assertFalse(GrammarRegistry.forPath("vendor/foo.java/").isPresent());
    }

    /**
     * A name-match check against the artifact catalog is not enough -- a
     * class can exist and still fail reflectively (no no-arg constructor, a
     * visibility change). Exercise every shipped extension end-to-end so a
     * broken entry fails here, loudly and locally, rather than silently at
     * the next dependency bump.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "java", "kt", "kts", "py", "js", "mjs", "ts", "tsx",
            "go", "rs", "c", "h", "cc", "cpp", "hpp"
    })
    void everyShippedExtensionResolvesToAGrammar(String extension) {
        assertTrue(GrammarRegistry.forPath("Example." + extension).isPresent());
    }

    /**
     * A per-class reflective-shape failure must not touch the global
     * native-availability latch. Loading all nine grammar classes above and
     * still finding the native library available is what proves a single
     * broken class cannot take every language down with it.
     */
    @Test
    void nativeStaysAvailableAfterLoadingEveryGrammar() {
        for (String extension : new String[] {
                "java", "kt", "kts", "py", "js", "mjs", "ts", "tsx",
                "go", "rs", "c", "h", "cc", "cpp", "hpp"
        }) {
            GrammarRegistry.forPath("Example." + extension);
        }
        assertTrue(GrammarRegistry.nativeAvailable());
    }
}
