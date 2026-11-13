package app.drydock.workspace;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceBoxNamesTest {

    // --- defaultName: deterministic sanitization (AC-15 determinism half) ---

    @Test
    void defaultNameMixedCaseAndDots() {
        assertEquals("drydock-profiling-rt-ai", WorkspaceBoxNames.defaultName("Profiling.RT-AI"));
        assertEquals("drydock-my-repo", WorkspaceBoxNames.defaultName("My.Repo"));
    }

    @Test
    void defaultNameSlashesCollapseToDashes() {
        assertEquals("drydock-foo-bar-baz", WorkspaceBoxNames.defaultName("foo/bar/baz"));
    }

    @Test
    void defaultNameUnicodeFallsBackToDashes() {
        assertEquals("drydock-caf", WorkspaceBoxNames.defaultName("Café"));
        assertEquals("drydock-caf", WorkspaceBoxNames.defaultName("CAFÉ"));
    }

    @Test
    void defaultNameCollapsesEachNonAlphanumericRunToOneDash() {
        assertEquals("drydock-a-b", WorkspaceBoxNames.defaultName("a--..b"));
        assertEquals("drydock-a-b", WorkspaceBoxNames.defaultName("a...b"));
        assertEquals("drydock-a-b", WorkspaceBoxNames.defaultName("a b"));
        assertEquals("drydock-a-b", WorkspaceBoxNames.defaultName("a - b"));
    }

    @Test
    void defaultNameKeepsRepositoryDashes() {
        // A dash is outside [a-z0-9], but a single-dash run replaces to itself.
        assertEquals("drydock-my-repo", WorkspaceBoxNames.defaultName("my-repo"));
    }

    @Test
    void defaultNameTrimsDashesFromTheRepositoryPart() {
        assertEquals("drydock-hidden", WorkspaceBoxNames.defaultName(".hidden"));
        assertEquals("drydock-trailing", WorkspaceBoxNames.defaultName("trailing/"));
        // A degenerate all-punctuation name trims to nothing, leaving the
        // bare prefix (a valid box name; real GitHub repos cannot reach this).
        assertEquals("drydock-", WorkspaceBoxNames.defaultName("///"));
        assertEquals("drydock-", WorkspaceBoxNames.defaultName(""));
    }

    @Test
    void defaultNameIsDeterministicAcrossCalls() {
        assertEquals(WorkspaceBoxNames.defaultName("Profiling.RT-AI"),
                WorkspaceBoxNames.defaultName("Profiling.RT-AI"));
    }

    @Test
    void defaultNameRejectsNull() {
        assertThrows(NullPointerException.class, () -> WorkspaceBoxNames.defaultName(null));
    }

    // --- nextGeneration: the first free generation against a supplied name set ---

    @Test
    void nextGenerationReturnsFirstFreeSuffix() {
        assertEquals("drydock-foo-2", WorkspaceBoxNames.nextGeneration("drydock-foo", Set.of()));
        assertEquals("drydock-foo-3",
                WorkspaceBoxNames.nextGeneration("drydock-foo", Set.of("drydock-foo-2")));
        assertEquals("drydock-foo-4",
                WorkspaceBoxNames.nextGeneration("drydock-foo", Set.of("drydock-foo-2", "drydock-foo-3")));
    }

    @Test
    void nextGenerationIgnoresOtherNamesAndTheBaseItself() {
        assertEquals("drydock-foo-2", WorkspaceBoxNames.nextGeneration("drydock-foo",
                Set.of("drydock-foo", "other-box", "drydock-bar-2")));
    }

    @Test
    void nextGenerationRejectsInvalidBase() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceBoxNames.nextGeneration("drydock_foo", Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceBoxNames.nextGeneration("Drydock-Foo", Set.of()));
    }

    @Test
    void nextGenerationRejectsNullNameSet() {
        assertThrows(NullPointerException.class,
                () -> WorkspaceBoxNames.nextGeneration("drydock-foo", null));
    }

    // --- nextGeneration: every returned candidate passes validate (R2 defect 5) ---

    @Test
    void nextGenerationReturnsOnlyCandidatesThatThemselvesValidate() {
        // A 61-character base still leaves room for one two-digit-free
        // suffix: base + "-2" is exactly the 63-character maximum.
        String longBase = "drydock-" + "x".repeat(53); // 61 characters
        assertTrue(WorkspaceBoxNames.validate(longBase), longBase);
        assertEquals(longBase + "-2", WorkspaceBoxNames.nextGeneration(longBase, Set.of()));
        // The next free generation of the same length is fine too.
        assertEquals(longBase + "-3",
                WorkspaceBoxNames.nextGeneration(longBase, Set.of(longBase + "-2")));
    }

    @Test
    void nextGenerationRefusesABaseTooLongToSuffix() {
        // A 62- or 63-character valid mapped name leaves NO valid
        // generation: every suffix pushes the candidate past the
        // 63-character limit, so the CLI adapter would reject whatever
        // was offered. The refusal happens here, before a dialog offers
        // the name — never an offered-then-rejected candidate.
        for (int length : new int[] {62, 63}) {
            String base = "drydock-" + "x".repeat(length - "drydock-".length());
            assertTrue(WorkspaceBoxNames.validate(base), base);
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> WorkspaceBoxNames.nextGeneration(base, Set.of()), base);
            assertTrue(refused.getMessage().contains(base), refused.getMessage());
        }
    }

    @Test
    void nextGenerationRefusesWhenEveryValidGenerationIsTaken() {
        // 61-character base: "-2" through "-9" are the only suffixes that
        // fit the 63-character limit; once they are all excluded, the
        // first "-10" candidate is 64 characters and invalid.
        String base = "drydock-" + "x".repeat(53);
        Set<String> taken = new java.util.HashSet<>();
        for (int generation = 2; generation <= 9; generation++) {
            taken.add(base + "-" + generation);
        }
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceBoxNames.nextGeneration(base, taken));
    }

    // --- validate: the accept/reject table (ASM-11) ---

    @Test
    void validateAcceptsLowerCaseAlphanumericWithDashes() {
        assertTrue(WorkspaceBoxNames.validate("drydock-foo"));
        assertTrue(WorkspaceBoxNames.validate("a"));
        assertTrue(WorkspaceBoxNames.validate("a-b-c"));
        assertTrue(WorkspaceBoxNames.validate("0-9"));
        assertTrue(WorkspaceBoxNames.validate("x".repeat(63)), "63 characters is the maximum");
        assertTrue(WorkspaceBoxNames.validate("drydock-foo-2"));
    }

    @Test
    void validateRejectsEverythingElse() {
        assertFalse(WorkspaceBoxNames.validate(null));
        assertFalse(WorkspaceBoxNames.validate(""));
        assertFalse(WorkspaceBoxNames.validate("-foo"), "must start with a lower-case alphanumeric");
        assertFalse(WorkspaceBoxNames.validate("Foo"), "upper case is rejected");
        assertFalse(WorkspaceBoxNames.validate("foo_bar"), "underscore is rejected");
        assertFalse(WorkspaceBoxNames.validate("foo.bar"), "dot is rejected");
        assertFalse(WorkspaceBoxNames.validate("foo bar"), "space is rejected");
        assertFalse(WorkspaceBoxNames.validate("x".repeat(64)), "64 characters is one too many");
    }

    @Test
    void defaultNameResultIsValidForOrdinaryRepositories() {
        String name = WorkspaceBoxNames.defaultName("Profiling.RT-AI/Plugins");
        assertTrue(WorkspaceBoxNames.validate(name), name);
        assertTrue(WorkspaceBoxNames.validate(WorkspaceBoxNames.defaultName("my-repo")));
    }
}
