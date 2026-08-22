package app.drydock.review;

import org.junit.jupiter.api.Test;

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
}
