package app.drydock.review;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Carrying approvals across the change of intent grouping.
 *
 * <p>Verdicts are persisted by intent id. The fallback grouping used to emit
 * one intent per file, keyed {@code file:<path>}; it now clusters files by
 * directory and kind, keyed {@code auto:<kind>:<dir>}. Without a migration
 * every approval recorded before that change would read as unsettled, and a
 * finished review would ask to be done again.</p>
 *
 * <p>The merge rule is deliberately asymmetric, because the two directions
 * are not equally safe. Requesting changes on part of a group is true of the
 * group. Approving a group is a claim that the human read all of it -- so a
 * partially-approved group carries nothing forward and is re-settled by
 * hand. Silently approving code nobody looked at is the one outcome a
 * migration must never produce.</p>
 */
class LegacyVerdictMigrationTest {

    private Path file;
    private AnnotationStore store;

    @BeforeEach
    void setUp() throws IOException {
        file = Files.createTempDirectory("drydock-verdict-migration").resolve("annotations.json");
        store = new AnnotationStore(file);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void aFullyApprovedGroupCarriesItsApprovalOver() {
        putLegacy("file:src/A.java", ReviewVerdict.Decision.APPROVED);
        putLegacy("file:src/B.java", ReviewVerdict.Decision.APPROVED);

        int migrated = store.migrateLegacyVerdicts("scope-1", List.of(intent("auto:change:src",
                "src/A.java", "src/B.java")));

        assertEquals(1, migrated);
        assertEquals(ReviewVerdict.Decision.APPROVED,
                store.verdict("scope-1", "auto:change:src").orElseThrow().decision());
    }

    @Test
    void theLegacyKeysAreGoneOnceMigrated() {
        putLegacy("file:src/A.java", ReviewVerdict.Decision.APPROVED);

        store.migrateLegacyVerdicts("scope-1", List.of(intent("auto:change:src", "src/A.java")));

        assertTrue(store.verdict("scope-1", "file:src/A.java").isEmpty(),
                "a migrated verdict must not also stay under its old key");
    }

    /** Changes requested on any file is true of the group that contains it. */
    @Test
    void changesRequestedOnOneFileCarriesToTheWholeGroup() {
        putLegacy("file:src/A.java", ReviewVerdict.Decision.APPROVED);
        putLegacy("file:src/B.java", ReviewVerdict.Decision.CHANGES);

        store.migrateLegacyVerdicts("scope-1", List.of(intent("auto:change:src",
                "src/A.java", "src/B.java")));

        assertEquals(ReviewVerdict.Decision.CHANGES,
                store.verdict("scope-1", "auto:change:src").orElseThrow().decision());
    }

    /** The one thing this must never do: approve code the human never settled. */
    @Test
    void aPartiallyApprovedGroupCarriesNothing() {
        putLegacy("file:src/A.java", ReviewVerdict.Decision.APPROVED);
        // src/B.java was never settled.

        int migrated = store.migrateLegacyVerdicts("scope-1", List.of(intent("auto:change:src",
                "src/A.java", "src/B.java")));

        assertEquals(0, migrated);
        assertTrue(store.verdict("scope-1", "auto:change:src").isEmpty(),
                "approving a group on the strength of one of its files is a lie about what was read");
    }

    /** A partial group's legacy verdicts are kept, not silently dropped. */
    @Test
    void aPartiallyApprovedGroupKeepsItsLegacyVerdicts() {
        putLegacy("file:src/A.java", ReviewVerdict.Decision.APPROVED);

        store.migrateLegacyVerdicts("scope-1", List.of(intent("auto:change:src",
                "src/A.java", "src/B.java")));

        assertTrue(store.verdict("scope-1", "file:src/A.java").isPresent(),
                "discarding the record would lose what the human actually did decide");
    }

    @Test
    void aHumanApprovalOutranksAnAgentsAutoApproval() {
        putLegacy("file:src/A.java", ReviewVerdict.Decision.AUTO_APPROVED);
        putLegacy("file:src/B.java", ReviewVerdict.Decision.APPROVED);

        store.migrateLegacyVerdicts("scope-1", List.of(intent("auto:change:src",
                "src/A.java", "src/B.java")));

        assertEquals(ReviewVerdict.Decision.APPROVED,
                store.verdict("scope-1", "auto:change:src").orElseThrow().decision());
    }

    @Test
    void anAllAutoApprovedGroupStaysAutoApproved() {
        putLegacy("file:src/A.java", ReviewVerdict.Decision.AUTO_APPROVED);
        putLegacy("file:src/B.java", ReviewVerdict.Decision.AUTO_APPROVED);

        store.migrateLegacyVerdicts("scope-1", List.of(intent("auto:change:src",
                "src/A.java", "src/B.java")));

        assertEquals(ReviewVerdict.Decision.AUTO_APPROVED,
                store.verdict("scope-1", "auto:change:src").orElseThrow().decision());
    }

    /** A migrated verdict says so, so its provenance is not misrepresented. */
    @Test
    void aMigratedVerdictIsMarkedAsMigrated() {
        putLegacy("file:src/A.java", ReviewVerdict.Decision.APPROVED);

        store.migrateLegacyVerdicts("scope-1", List.of(intent("auto:change:src", "src/A.java")));

        assertTrue(store.verdict("scope-1", "auto:change:src").orElseThrow()
                        .note().orElse("").toLowerCase(java.util.Locale.ROOT).contains("regroup"),
                "the note must record that this verdict was carried over, not freshly given");
    }

    /** An existing decision on the new id is the newer one and must win. */
    @Test
    void aVerdictAlreadyRecordedOnTheNewIdIsNotOverwritten() {
        putLegacy("file:src/A.java", ReviewVerdict.Decision.APPROVED);
        putLegacy("auto:change:src", ReviewVerdict.Decision.CHANGES);

        store.migrateLegacyVerdicts("scope-1", List.of(intent("auto:change:src", "src/A.java")));

        assertEquals(ReviewVerdict.Decision.CHANGES,
                store.verdict("scope-1", "auto:change:src").orElseThrow().decision(),
                "a decision made under the new grouping is newer than one made under the old");
    }

    @Test
    void runningTwiceChangesNothingTheSecondTime() {
        putLegacy("file:src/A.java", ReviewVerdict.Decision.APPROVED);
        List<ReviewIntent> intents = List.of(intent("auto:change:src", "src/A.java"));

        assertEquals(1, store.migrateLegacyVerdicts("scope-1", intents));
        assertEquals(0, store.migrateLegacyVerdicts("scope-1", intents),
                "the migration must be idempotent -- it runs on every diff that lands");
    }

    /** Another scope's verdicts are not this scope's to migrate. */
    @Test
    void onlyTheNamedScopeIsTouched() {
        store.putVerdict(new ReviewVerdict("scope-2", "file:src/A.java",
                ReviewVerdict.Decision.APPROVED, Optional.empty(), Instant.EPOCH));

        store.migrateLegacyVerdicts("scope-1", List.of(intent("auto:change:src", "src/A.java")));

        assertTrue(store.verdict("scope-2", "file:src/A.java").isPresent(),
                "scope-2's verdict belongs to scope-2");
        assertTrue(store.verdict("scope-1", "auto:change:src").isEmpty());
    }

    /** A legacy verdict on a file no longer in the diff has no group to join. */
    @Test
    void aLegacyVerdictForAFileNoLongerInTheDiffIsLeftAlone() {
        putLegacy("file:src/Deleted.java", ReviewVerdict.Decision.APPROVED);

        int migrated = store.migrateLegacyVerdicts("scope-1",
                List.of(intent("auto:change:src", "src/A.java")));

        assertEquals(0, migrated);
        assertTrue(store.verdict("scope-1", "file:src/Deleted.java").isPresent());
    }

    @Test
    void noIntentsMeansNoMigration() {
        putLegacy("file:src/A.java", ReviewVerdict.Decision.APPROVED);

        assertEquals(0, store.migrateLegacyVerdicts("scope-1", List.of()),
                "a scope whose diff has not loaded must not have its verdicts rewritten");
        assertTrue(store.verdict("scope-1", "file:src/A.java").isPresent());
    }

    @Test
    void theMigrationSurvivesAReload() throws Exception {
        putLegacy("file:src/A.java", ReviewVerdict.Decision.APPROVED);
        store.migrateLegacyVerdicts("scope-1", List.of(intent("auto:change:src", "src/A.java")));
        store.close();

        // Handed to the field so tearDown closes this one and not the store
        // that is already shut down -- closing twice submits to a dead
        // executor and fails the test for a reason that is not the point.
        store = new AnnotationStore(file);

        assertEquals(ReviewVerdict.Decision.APPROVED,
                store.verdict("scope-1", "auto:change:src").orElseThrow().decision(),
                "the migration must be written to disk, or it runs again forever");
        assertFalse(store.verdict("scope-1", "file:src/A.java").isPresent());
    }

    // ---- helpers --------------------------------------------------------

    private void putLegacy(String intentId, ReviewVerdict.Decision decision) {
        store.putVerdict(new ReviewVerdict("scope-1", intentId, decision,
                Optional.empty(), Instant.EPOCH));
    }

    private static ReviewIntent intent(String id, String... files) {
        List<String> hunkIds = new java.util.ArrayList<>();
        for (String file : files) {
            hunkIds.add(ReviewIntent.hunkId(file, 0));
        }
        return new ReviewIntent(id, 1, "an intent", ReviewIntent.Kind.CHANGE,
                ReviewIntent.Risk.LOW, "", hunkIds, Optional.empty(), false);
    }
}
