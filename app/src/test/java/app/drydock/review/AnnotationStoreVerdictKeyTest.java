package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verdicts are stored under a hunk's content, not under a grouping
 * (spec §9.1). The round trip is what makes an approval outlive the process
 * that recorded it, and the base/head it was given against has to survive
 * with it or staleness cannot be derived on the next launch.
 */
class AnnotationStoreVerdictKeyTest {

    private static ReviewVerdict approved(String digest, String base) {
        return new ReviewVerdict("scope-1", digest, ReviewVerdict.Decision.APPROVED,
                Optional.of("looks right"), Instant.parse("2026-08-22T00:00:00Z"), base, "head-1");
    }

    @Test
    void aVerdictRoundTripsThroughDiskWithItsBaseAndHead() throws IOException {
        Path file = Files.createTempDirectory("drydock-verdicts").resolve("annotations.json");
        AnnotationStore store = new AnnotationStore(file);
        store.putVerdict(approved("digest-a", "base-1"));
        store.flushPendingSaves();

        AnnotationStore reloaded = new AnnotationStore(file);
        Optional<ReviewVerdict> read = reloaded.verdict("scope-1", "digest-a");

        assertTrue(read.isPresent());
        assertEquals("base-1", read.get().baseCommit());
        assertEquals("head-1", read.get().headCommit());
        assertEquals(Optional.of("looks right"), read.get().note());
        assertEquals(ReviewVerdict.Decision.APPROVED, read.get().decision());
    }

    /**
     * The property that makes overlapping sections possible (spec §5.6): one
     * hunk shown in three sections is one digest, so it is one flag.
     */
    @Test
    void oneDigestIsOneFlagHoweverManySectionsShowIt() throws IOException {
        Path file = Files.createTempDirectory("drydock-verdicts").resolve("annotations.json");
        AnnotationStore store = new AnnotationStore(file);

        store.putVerdict(approved("shared-digest", "base-1"));

        assertEquals(1, store.verdictsFor("scope-1").size());
        assertTrue(store.verdict("scope-1", "shared-digest").isPresent());
    }

    @Test
    void clearingRemovesTheVerdictForThatDigestOnly() throws IOException {
        Path file = Files.createTempDirectory("drydock-verdicts").resolve("annotations.json");
        AnnotationStore store = new AnnotationStore(file);
        store.putVerdict(approved("digest-a", "base-1"));
        store.putVerdict(approved("digest-b", "base-1"));

        store.clearVerdict("scope-1", "digest-a");

        assertEquals(List.of("digest-b"),
                store.verdictsFor("scope-1").stream().map(ReviewVerdict::hunkDigest).toList());
    }

    /**
     * A v3 entry names an intentId and no digest. There are none in the wild
     * (which is why no migration is written), but a file carrying one must
     * be skipped rather than crash the load -- lenient decoding is the
     * store's existing contract.
     */
    @Test
    void aPreDigestVerdictEntryIsSkippedNotFatal() throws IOException {
        Path file = Files.createTempDirectory("drydock-verdicts").resolve("annotations.json");
        Files.writeString(file, """
                {"schemaVersion":3,"annotations":[],"submitted":[],
                 "verdicts":[{"scopeId":"scope-1","intentId":"auto:change:src",
                              "verdict":"approved","at":"2026-08-01T00:00:00Z"}]}
                """);

        AnnotationStore store = new AnnotationStore(file);

        assertEquals(List.of(), store.verdictsFor("scope-1"));
    }
}
