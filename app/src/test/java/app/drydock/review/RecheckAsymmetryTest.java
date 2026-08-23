package app.drydock.review;

import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The asymmetry (spec §9.7). "Affected" applies, because it can only ever
 * ADD reading and because it closes the blind spot the file-level relevance
 * filter admits to. "Unaffected" is advice, because an agent wrong THAT way
 * would cost an approval on code nobody re-read -- which is the outcome the
 * whole reviewed-state model refuses.
 *
 * <p>This is the STORE's half. The translation from the positional {@code
 * hunkId} an agent actually sends to the content digest a verdict is keyed
 * by lives in {@code McpToolRouterRecheckTest}: every test here hands the
 * store a digest directly and so exercises none of it.</p>
 */
class RecheckAsymmetryTest {

    private static AnnotationStore store() throws IOException {
        return new AnnotationStore(Files.createTempDirectory("drydock-recheck")
                .resolve("annotations.json"));
    }

    private static ReviewVerdict approved(String base) {
        return new ReviewVerdict("scope-1", "digest-1", ReviewVerdict.Decision.APPROVED,
                Optional.empty(), Instant.EPOCH, base, "head-1");
    }

    @Test
    void anAffectedAssessmentMarksAVerdictTheFilterWouldHaveMissed() throws IOException {
        AnnotationStore store = store();
        store.putVerdict(approved("base-1"));
        store.putAssessment(new RecheckAssessment("scope-1", "digest-1", "base-1", "base-2",
                true, "resolve() now returns nullptr on failure", Instant.EPOCH));

        assertTrue(store.assessedAffected("scope-1", "digest-1", "base-1", "base-2"));
    }

    @Test
    void anUnaffectedAssessmentDoesNotClearTheVerdictsStaleness() throws IOException {
        AnnotationStore store = store();
        store.putVerdict(approved("base-1"));
        store.putAssessment(new RecheckAssessment("scope-1", "digest-1", "base-1", "base-2",
                false, "the base change is in an unrelated subsystem", Instant.EPOCH));

        assertFalse(store.assessedAffected("scope-1", "digest-1", "base-1", "base-2"));
        assertTrue(store.verdict("scope-1", "digest-1").orElseThrow().staleAgainst("base-2"),
                "an agent must not clear a human's approval");
    }

    /**
     * The same pair, both directions, on ONE store -- so an implementation
     * that let the later "unaffected" un-record the earlier "affected" (or
     * vice versa) is caught. A test that only ever writes one assessment per
     * key cannot tell an overwrite that is right from one that is wrong.
     */
    @Test
    void aLaterUnaffectedReplacesAnEarlierAffectedButStillClearsNothing() throws IOException {
        AnnotationStore store = store();
        store.putVerdict(approved("base-1"));
        store.putAssessment(new RecheckAssessment("scope-1", "digest-1", "base-1", "base-2",
                true, "resolve() now returns nullptr", Instant.EPOCH));
        store.putAssessment(new RecheckAssessment("scope-1", "digest-1", "base-1", "base-2",
                false, "second look: unrelated", Instant.EPOCH.plusSeconds(60)));

        // The agent is allowed to withdraw its OWN mark -- that only removes
        // something the agent itself added.
        assertFalse(store.assessedAffected("scope-1", "digest-1", "base-1", "base-2"));
        // What it never touches is the human's verdict, which is still stale
        // against the new base on the filter's own terms.
        assertTrue(store.verdict("scope-1", "digest-1").orElseThrow().staleAgainst("base-2"));
        assertEquals(ReviewVerdict.Decision.APPROVED,
                store.verdict("scope-1", "digest-1").orElseThrow().decision());
    }

    /** An assessment is about one base pair; a later move is a new question. */
    @Test
    void anAssessmentDoesNotCarryToADifferentBasePair() throws IOException {
        AnnotationStore store = store();
        store.putAssessment(new RecheckAssessment("scope-1", "digest-1", "base-1", "base-2",
                true, "why", Instant.EPOCH));

        assertFalse(store.assessedAffected("scope-1", "digest-1", "base-2", "base-3"));
        // Nor to a different hunk, nor to a different scope: every term of
        // the key is load-bearing, and a fixture varying only one of them
        // cannot say so.
        assertFalse(store.assessedAffected("scope-1", "digest-2", "base-1", "base-2"));
        assertFalse(store.assessedAffected("scope-2", "digest-1", "base-1", "base-2"));
        assertTrue(store.assessedAffected("scope-1", "digest-1", "base-1", "base-2"));
    }

    @Test
    void assessmentsRoundTripThroughDisk() throws IOException {
        Path file = Files.createTempDirectory("drydock-recheck").resolve("annotations.json");
        AnnotationStore store = new AnnotationStore(file);
        store.putAssessment(new RecheckAssessment("scope-1", "digest-1", "base-1", "base-2",
                true, "why", Instant.EPOCH));
        store.flushPendingSaves();

        assertTrue(new AnnotationStore(file)
                .assessedAffected("scope-1", "digest-1", "base-1", "base-2"));
    }

    /**
     * An "unaffected" survives a restart as an unaffected, rather than being
     * dropped and re-read as "never asked". The two are the same to every
     * caller, so a round trip that lost it would go unnoticed by
     * {@link #assessmentsRoundTripThroughDisk} -- this reads the record
     * itself.
     */
    @Test
    void anUnaffectedAssessmentIsPersistedRatherThanDroppedAsInert() throws IOException {
        Path file = Files.createTempDirectory("drydock-recheck").resolve("annotations.json");
        AnnotationStore store = new AnnotationStore(file);
        store.putAssessment(new RecheckAssessment("scope-1", "digest-1", "base-1", "base-2",
                false, "unrelated subsystem", Instant.EPOCH));
        store.flushPendingSaves();

        List<RecheckAssessment> reloaded = new AnnotationStore(file).assessmentsFor("scope-1");
        assertEquals(1, reloaded.size());
        assertFalse(reloaded.get(0).affected());
        assertEquals("unrelated subsystem", reloaded.get(0).why());
    }

    /**
     * The schema version the store WRITES is 5.
     *
     * <p>Pinned on the constant's actual effect, because the bump is the only
     * thing that tells a v4 file from a v5 one: without an assertion the
     * constant can be reverted and every other test on this branch still
     * passes, which the reviewer demonstrated by doing exactly that.</p>
     */
    @Test
    void theWrittenSchemaVersionIsFive() throws IOException {
        Path file = Files.createTempDirectory("drydock-recheck").resolve("annotations.json");
        AnnotationStore store = new AnnotationStore(file);
        store.putAssessment(new RecheckAssessment("scope-1", "digest-1", "base-1", "base-2",
                true, "why", Instant.EPOCH));
        store.flushPendingSaves();

        JsonValue root = JsonParser.parse(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(5, ((JsonValue.JsonNumber) ((JsonValue.JsonObject) root).get("schemaVersion"))
                        .asInt(),
                "persisting a new assessments array is a schema change; without the bump a v4 "
                        + "file and a v5 file are indistinguishable");
    }

    /**
     * A file written before this task (schema 4, no {@code assessments} key)
     * loads cleanly and yields no assessments. The branch has no migration
     * and needs none -- {@code loadFromDisk} reads each named array
     * independently -- but "old file loads cleanly" is pinned rather than
     * assumed.
     *
     * <p>The {@code submitted} flag is what makes this test able to FAIL. A
     * decode that threw on the missing key would be swallowed by {@code
     * loadFromDisk}'s lenient catch, and the verdict read BEFORE it would
     * survive in the map anyway -- so a fixture asserting only on assessments
     * and verdicts passes just as well when the load blew up halfway. {@code
     * submitted} is read after the assessments and is the first thing such a
     * load would lose.</p>
     */
    @Test
    void aFileWrittenBeforeAssessmentsExistedLoadsWithNoneAndKeepsEverythingElse() throws IOException {
        Path file = Files.createTempDirectory("drydock-recheck").resolve("annotations.json");
        Files.writeString(file, """
                {"schemaVersion":4,
                 "annotations":[],
                 "verdicts":[{"scopeId":"scope-1","hunkDigest":"digest-1","verdict":"approved",
                              "at":"1970-01-01T00:00:00Z","base":"base-1","head":"head-1"}],
                 "submitted":["scope-1"]}
                """, StandardCharsets.UTF_8);

        AnnotationStore store = new AnnotationStore(file);

        assertEquals(List.of(), store.assessmentsFor("scope-1"));
        assertFalse(store.assessedAffected("scope-1", "digest-1", "base-1", "base-2"));
        assertTrue(store.verdict("scope-1", "digest-1").isPresent(),
                "the v4 verdict must survive the schema bump");
        assertTrue(store.isSubmitted("scope-1"),
                "everything read after the assessments must survive too");
    }

    /**
     * Dropping a scope drops its rechecks with it. A stale assessment left
     * behind would answer for whatever scope handle the store minted next.
     */
    @Test
    void removingAScopeRemovesItsAssessments() throws IOException {
        AnnotationStore store = store();
        store.putAssessment(new RecheckAssessment("scope-1", "digest-1", "base-1", "base-2",
                true, "why", Instant.EPOCH));
        store.putAssessment(new RecheckAssessment("scope-2", "digest-1", "base-1", "base-2",
                true, "why", Instant.EPOCH));

        store.removeScope("scope-1");

        assertFalse(store.assessedAffected("scope-1", "digest-1", "base-1", "base-2"));
        assertTrue(store.assessedAffected("scope-2", "digest-1", "base-1", "base-2"),
                "removing one scope must not take another's rechecks with it");
    }

    /**
     * The branch's determinism bar: the same assessments come back in the
     * order they arrived, byte for byte, across two separate store instances
     * -- across PROCESSES, in effect, since a second instance re-decodes from
     * disk with nothing carried over in memory.
     *
     * <p>Twenty of them, with digests running OPPOSITE to insertion order, so
     * a hash-ordered map could not come out right by accident and a fixture
     * of two or three could not tell the difference.</p>
     */
    @Test
    void assessmentsKeepTheirArrivalOrderAcrossAReload() throws IOException {
        Path file = Files.createTempDirectory("drydock-recheck").resolve("annotations.json");
        AnnotationStore store = new AnnotationStore(file);
        List<String> arrival = new java.util.ArrayList<>();
        for (int n = 19; n >= 0; n--) {
            String digest = "digest-%02d".formatted(n);
            arrival.add(digest);
            store.putAssessment(new RecheckAssessment("scope-1", digest, "base-1", "base-2",
                    n % 2 == 0, "why " + n, Instant.EPOCH));
        }
        store.flushPendingSaves();
        String firstText = Files.readString(file, StandardCharsets.UTF_8);

        AnnotationStore reloaded = new AnnotationStore(file);
        assertEquals(arrival, reloaded.assessmentsFor("scope-1").stream()
                .map(RecheckAssessment::hunkDigest).toList());

        // Re-saving what was re-read reproduces the same bytes, re-stating the
        // FIRST entry included: an overwrite that moved its key to the end
        // would reorder the file, and an ordering that only survived because
        // nothing had been round-tripped yet would drift here.
        reloaded.putAssessment(new RecheckAssessment("scope-1", "digest-19", "base-1", "base-2",
                false, "why 19", Instant.EPOCH));
        reloaded.flushPendingSaves();
        assertEquals(firstText, Files.readString(file, StandardCharsets.UTF_8));
    }
}
