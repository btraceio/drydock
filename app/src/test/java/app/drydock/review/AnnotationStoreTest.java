package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffScope;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review store: {@code (scopeId, id)} keying, verdicts, persistence and
 * the concurrency contract between its two writers (the FX Review
 * destination and the MCP tool router).
 */
class AnnotationStoreTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    private static ReviewAnnotation finding(String scopeId, String id) {
        return finding(scopeId, id, Severity.QUESTION);
    }

    private static ReviewAnnotation finding(String scopeId, String id, Severity severity) {
        return new ReviewAnnotation(scopeId, id, Optional.of("i1"), "src/Main.java", "n42", "n42",
                severity, Confidence.HIGH, Optional.of("Title"), "Claude", AT,
                List.of(), Optional.empty(), Optional.empty(), List.of(),
                List.of(new ReviewAnnotation.Message("Claude", AT, "body")),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false);
    }

    @Test
    void persistsTheSecretUsedForRestartStableScopeIds(@TempDir Path dir) {
        Path file = dir.resolve("annotations.json");
        byte[] original;
        try (AnnotationStore store = new AnnotationStore(file)) {
            original = store.scopeIdSecret();
            store.upsert(finding("rs_scope", "f1"));
        }

        try (AnnotationStore reopened = new AnnotationStore(file)) {
            assertArrayEquals(original, reopened.scopeIdSecret());
        }
    }

    // ---- the keying bug -----------------------------------------------------

    /**
     * The regression test for the bug the scoped key exists to prevent:
     * finding ids are agent-chosen and stable across re-runs, so the SAME id
     * genuinely appears in two worktrees at once. Resolving one must not
     * resolve the other.
     */
    @Test
    void resolvingAFindingInOneScopeLeavesTheSameIdInAnotherAlone(@TempDir Path dir) {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            store.upsert(finding("rs_left", "f_leak_1"));
            store.upsert(finding("rs_right", "f_leak_1"));

            store.mutate(new ReviewAnnotation.Key("rs_left", "f_leak_1"),
                    current -> current.withStatus(AnnotationStatus.RESOLVED));

            assertTrue(store.byId("rs_left", "f_leak_1").orElseThrow().resolved());
            assertFalse(store.byId("rs_right", "f_leak_1").orElseThrow().resolved(),
                    "resolving a finding in one worktree must not resolve the same id in another");
        }
    }

    @Test
    void theSameIdInTwoScopesIsTwoFindings(@TempDir Path dir) {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            store.upsert(finding("rs_left", "f1"));
            store.upsert(finding("rs_right", "f1"));

            assertEquals(1, store.forScope("rs_left").size());
            assertEquals(1, store.forScope("rs_right").size());
        }
    }

    @Test
    void removingAScopeLeavesTheOtherScopesIntact(@TempDir Path dir) {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            store.upsert(finding("rs_left", "f1"));
            store.upsert(finding("rs_right", "f1"));
            store.putVerdict(new ReviewVerdict("rs_left", "i1", ReviewVerdict.Decision.APPROVED,
                    Optional.empty(), AT));

            store.removeScope("rs_left");

            assertTrue(store.forScope("rs_left").isEmpty());
            assertTrue(store.verdict("rs_left", "i1").isEmpty());
            assertEquals(1, store.forScope("rs_right").size());
        }
    }

    @Test
    void aTransformMayNotMoveAFindingToAnotherScope(@TempDir Path dir) {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            store.upsert(finding("rs_a", "f1"));

            assertThrows(IllegalArgumentException.class, () ->
                    store.mutate(new ReviewAnnotation.Key("rs_a", "f1"),
                            current -> current.withScopeId("rs_b")));
        }
    }

    // ---- upsert / queries ---------------------------------------------------

    /** A reviewer re-run upserts on the same id, so the thread survives. */
    @Test
    void upsertReplacesTheFindingUnderTheSameKey(@TempDir Path dir) {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            store.upsert(finding("rs_a", "f1", Severity.NIT));
            store.upsert(finding("rs_a", "f1", Severity.BLOCKING));

            assertEquals(1, store.forScope("rs_a").size());
            assertEquals(Severity.BLOCKING, store.byId("rs_a", "f1").orElseThrow().severity());
        }
    }

    @Test
    void openCountIgnoresResolvedFindings(@TempDir Path dir) {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            store.upsert(finding("rs_a", "f1"));
            store.upsert(finding("rs_a", "f2"));
            store.mutate(new ReviewAnnotation.Key("rs_a", "f2"),
                    current -> current.withStatus(AnnotationStatus.RESOLVED));

            assertEquals(1, store.openCount("rs_a"));
        }
    }

    @Test
    void aResolvedBlockingFindingNoLongerBlocks(@TempDir Path dir) {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            store.upsert(finding("rs_a", "f1", Severity.BLOCKING));
            assertTrue(store.hasOpenBlockingFinding("rs_a", "i1"));

            store.mutate(new ReviewAnnotation.Key("rs_a", "f1"),
                    current -> current.withStatus(AnnotationStatus.RESOLVED));

            assertFalse(store.hasOpenBlockingFinding("rs_a", "i1"));
        }
    }

    /** A human downgrade must actually unblock approval; the agent's original opinion is kept. */
    @Test
    void aSeverityOverrideDecidesWhetherAFindingBlocks(@TempDir Path dir) {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            store.upsert(finding("rs_a", "f1", Severity.BLOCKING));

            store.mutate(new ReviewAnnotation.Key("rs_a", "f1"),
                    current -> current.withSeverityOverride(Severity.QUESTION));

            assertFalse(store.hasOpenBlockingFinding("rs_a", "i1"));
            ReviewAnnotation stored = store.byId("rs_a", "f1").orElseThrow();
            assertEquals(Severity.BLOCKING, stored.severity(), "the reviewer's opinion is kept");
            assertEquals(Severity.QUESTION, stored.effectiveSeverity());
        }
    }

    @Test
    void forIntentFiltersWithinOneScope(@TempDir Path dir) {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            store.upsert(finding("rs_a", "f1"));
            ReviewAnnotation other = new ReviewAnnotation("rs_a", "f2", Optional.of("i2"),
                    "src/Other.java", "n1", "n1", Severity.NIT, Confidence.HIGH, Optional.empty(),
                    "Claude", AT, List.of(), Optional.empty(), Optional.empty(), List.of(),
                    List.of(), Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false);
            store.upsert(other);

            assertEquals(List.of("f1"), store.forIntent("rs_a", "i1").stream()
                    .map(ReviewAnnotation::id).toList());
        }
    }

    // ---- verdicts -----------------------------------------------------------

    @Test
    void verdictsAreKeyedByScopeAndIntent(@TempDir Path dir) {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            store.putVerdict(new ReviewVerdict("rs_a", "i1", ReviewVerdict.Decision.APPROVED,
                    Optional.empty(), AT));
            store.putVerdict(new ReviewVerdict("rs_b", "i1", ReviewVerdict.Decision.CHANGES,
                    Optional.of("needs a test"), AT));

            assertEquals(ReviewVerdict.Decision.APPROVED,
                    store.verdict("rs_a", "i1").orElseThrow().decision());
            assertEquals(ReviewVerdict.Decision.CHANGES,
                    store.verdict("rs_b", "i1").orElseThrow().decision());
        }
    }

    @Test
    void clearingAVerdictOnlyClearsThatOne(@TempDir Path dir) {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            store.putVerdict(new ReviewVerdict("rs_a", "i1", ReviewVerdict.Decision.APPROVED,
                    Optional.empty(), AT));
            store.putVerdict(new ReviewVerdict("rs_a", "i2", ReviewVerdict.Decision.APPROVED,
                    Optional.empty(), AT));

            store.clearVerdict("rs_a", "i1");

            assertTrue(store.verdict("rs_a", "i1").isEmpty());
            assertTrue(store.verdict("rs_a", "i2").isPresent());
        }
    }

    @Test
    void submissionIsRecordedPerScope(@TempDir Path dir) {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            store.markSubmitted("rs_a");

            assertTrue(store.isSubmitted("rs_a"));
            assertFalse(store.isSubmitted("rs_b"));
        }
    }

    // ---- persistence --------------------------------------------------------

    @Test
    void findingsRoundTripThroughJson() {
        ReviewAnnotation rich = new ReviewAnnotation("rs_a", "f1", Optional.of("i1"),
                "src/Main.java", "n42", "n45", Severity.BLOCKING, Confidence.UNSURE,
                Optional.of("Event filter never detached"), "Claude", AT,
                List.of(new ReviewAnnotation.Evidence("leak", "addEventFilter(...)", "java")),
                Optional.of(new ReviewAnnotation.Patch("--- a\n+++ b\n", "one line in onRelease")),
                Optional.of(new ReviewAnnotation.DeviatesFrom("min width 240", Optional.of(9))),
                List.of(new ReviewAnnotation.Ask("Why is it a leak?", "Explain the leak.")),
                List.of(new ReviewAnnotation.Message("Claude", AT, "body",
                        Optional.of(new ReviewAnnotation.Evidence("here", "code", "java")))),
                Optional.of(Severity.QUESTION), AnnotationStatus.ADDRESSED,
                Optional.of(new ReviewAnnotation.GitHubComment(42L, "https://github.com/o/r/pull/1#discussion_r42",
                        true)),
                true);

        List<ReviewAnnotation> decoded = AnnotationStore.fromJson(JsonParser.parse(
                JsonWriter.write(AnnotationStore.toJson(List.of(rich), List.of(), List.of()))));

        assertEquals(List.of(rich), decoded);
    }

    @Test
    void githubStateSurvivesARoundTrip() {
        ReviewAnnotation finding = ReviewAnnotation.human("scope-1", "Sidebar.java", "o55", "n58",
                        new ReviewAnnotation.Message("You", Instant.parse("2026-08-05T10:00:00Z"), "why?"))
                .withGithub(new ReviewAnnotation.GitHubComment(9001L,
                        "https://github.com/o/r/pull/7#discussion_r9001", false));

        List<ReviewAnnotation> decoded = AnnotationStore.fromJson(
                AnnotationStore.toJson(List.of(finding), List.of(), List.of()));

        assertEquals(1, decoded.size());
        assertEquals(9001L, decoded.get(0).github().orElseThrow().id());
        assertEquals("https://github.com/o/r/pull/7#discussion_r9001",
                decoded.get(0).github().orElseThrow().url());
        assertTrue(decoded.get(0).postToPr(), "a comment you authored posts by default");
    }

    @Test
    void anEntryWrittenBeforeGithubStateExistedStillDecodes() {
        // Lenient decode: an entry written before github/postToPr existed has
        // neither key, and must load as a local-only, not-posting annotation
        // rather than being skipped.
        JsonObject entry = JsonObject.empty();
        entry.put("scopeId", new JsonString("scope-1"));
        entry.put("id", new JsonString("f_1"));
        entry.put("file", new JsonString("Sidebar.java"));
        entry.put("startKey", new JsonString("n40"));
        entry.put("endKey", new JsonString("n40"));
        entry.put("author", new JsonString("reviewer"));
        entry.put("at", new JsonString("2026-08-05T10:00:00Z"));
        entry.put("status", new JsonString("OPEN"));
        entry.put("thread", new JsonArray(List.of()));
        JsonObject root = JsonObject.empty();
        root.put("annotations", new JsonArray(List.of(entry)));

        List<ReviewAnnotation> decoded = AnnotationStore.fromJson(root);

        assertEquals(1, decoded.size());
        assertTrue(decoded.get(0).github().isEmpty());
        assertFalse(decoded.get(0).postToPr(), "an agent's finding posts only once promoted");
    }

    @Test
    void verdictsAndSubmissionsRoundTripThroughJson() {
        ReviewVerdict verdict = new ReviewVerdict("rs_a", "i1", ReviewVerdict.Decision.CHANGES,
                Optional.of("please add a test"), AT);

        String json = JsonWriter.write(AnnotationStore.toJson(List.of(), List.of(verdict), List.of("rs_a")));

        assertEquals(List.of(verdict), AnnotationStore.verdictsFromJson(JsonParser.parse(json)));
        assertEquals(List.of("rs_a"), AnnotationStore.submittedFromJson(JsonParser.parse(json)));
    }

    @Test
    void everythingPersistsAcrossAReload(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("annotations.json");
        try (AnnotationStore store = new AnnotationStore(file)) {
            store.upsert(finding("rs_a", "f1"));
            store.putVerdict(new ReviewVerdict("rs_a", "i1", ReviewVerdict.Decision.APPROVED,
                    Optional.empty(), AT));
            store.markSubmitted("rs_a");
            store.flushPendingSaves();
        }
        waitForFile(file);

        try (AnnotationStore reloaded = new AnnotationStore(file)) {
            assertEquals(1, reloaded.forScope("rs_a").size());
            assertTrue(reloaded.verdict("rs_a", "i1").isPresent());
            assertTrue(reloaded.isSubmitted("rs_a"));
        }
    }

    /**
     * A pre-scope-handle file must not lose the user's comments on upgrade.
     * Its entries are parked under a deterministic placeholder and adopted by
     * the matching scope the first time one is minted.
     */
    @Test
    void aSchemaVersionOneFileIsMigratedRatherThanDropped(@TempDir Path dir) throws Exception {
        ManagedSessionId session = ManagedSessionId.newId();
        Path file = dir.resolve("annotations.json");
        Files.writeString(file, """
                {"schemaVersion":1,"annotations":[{
                  "id":"a1","sessionId":"%s","scope":"BASE","file":"src/Main.java",
                  "startKey":"n42","endKey":"n42","status":"OPEN",
                  "thread":[{"author":"You","at":"2026-01-01T00:00:00Z","text":"look at this"}]
                }]}
                """.formatted(session), StandardCharsets.UTF_8);

        try (AnnotationStore store = new AnnotationStore(file)) {
            String legacy = AnnotationStore.legacyScopeId(session, DiffScope.BASE);
            assertEquals(1, store.forScope(legacy).size(), "a v1 entry must survive the upgrade");
            assertEquals("You", store.forScope(legacy).get(0).author());

            int adopted = store.adoptLegacy(session, DiffScope.BASE, "rs_new");

            assertEquals(1, adopted);
            assertTrue(store.forScope(legacy).isEmpty());
            assertEquals("look at this",
                    store.byId("rs_new", "a1").orElseThrow().thread().get(0).text());
        }
    }

    /** The live review is the truth: an adopted legacy copy must never overwrite it. */
    @Test
    void adoptionNeverOverwritesAFindingTheLiveScopeAlreadyHas(@TempDir Path dir) throws Exception {
        ManagedSessionId session = ManagedSessionId.newId();
        Path file = dir.resolve("annotations.json");
        Files.writeString(file, """
                {"schemaVersion":1,"annotations":[{
                  "id":"a1","sessionId":"%s","scope":"BASE","file":"old.java",
                  "startKey":"n1","endKey":"n1","status":"OPEN","thread":[]
                }]}
                """.formatted(session), StandardCharsets.UTF_8);

        try (AnnotationStore store = new AnnotationStore(file)) {
            store.upsert(finding("rs_new", "a1"));

            store.adoptLegacy(session, DiffScope.BASE, "rs_new");

            assertEquals("src/Main.java", store.byId("rs_new", "a1").orElseThrow().file());
        }
    }

    @Test
    void aMalformedFileYieldsAnEmptyStore(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("annotations.json");
        Files.writeString(file, "{ not json", StandardCharsets.UTF_8);

        try (AnnotationStore store = new AnnotationStore(file)) {
            assertTrue(store.forScope("rs_a").isEmpty());
        }
    }

    @Test
    void oneMalformedEntryNeverDiscardsTheRest(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("annotations.json");
        Files.writeString(file, """
                {"schemaVersion":2,"annotations":[
                  {"scopeId":"rs_a","id":"broken"},
                  {"scopeId":"rs_a","id":"good","file":"a.java","startKey":"n1","endKey":"n1",
                   "status":"OPEN","author":"You","at":"2026-01-01T00:00:00Z","thread":[]}
                ]}
                """, StandardCharsets.UTF_8);

        try (AnnotationStore store = new AnnotationStore(file)) {
            assertEquals(List.of("good"),
                    store.forScope("rs_a").stream().map(ReviewAnnotation::id).toList());
        }
    }

    // ---- change notification ------------------------------------------------

    @Test
    void changeListenersSeeTheAffectedKey(@TempDir Path dir) {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            List<ReviewAnnotation.Key> seen = new ArrayList<>();
            store.addChangeListener(seen::add);

            store.upsert(finding("rs_a", "f1"));
            store.mutate(new ReviewAnnotation.Key("rs_a", "f1"),
                    current -> current.withStatus(AnnotationStatus.RESOLVED));
            store.remove(new ReviewAnnotation.Key("rs_a", "f1"));

            ReviewAnnotation.Key key = new ReviewAnnotation.Key("rs_a", "f1");
            assertEquals(List.of(key, key, key), seen);
        }
    }

    @Test
    void removingAScopeFiresOneBulkChange(@TempDir Path dir) {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            store.upsert(finding("rs_a", "f1"));
            store.upsert(finding("rs_a", "f2"));
            List<ReviewAnnotation.Key> seen = new ArrayList<>();
            store.addChangeListener(seen::add);

            store.removeScope("rs_a");

            assertEquals(1, seen.size());
            assertEquals(java.util.Collections.singletonList(null), seen);
        }
    }

    @Test
    void aThrowingListenerDoesNotBreakTheWrite(@TempDir Path dir) {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            store.addChangeListener(key -> {
                throw new IllegalStateException("boom");
            });
            List<ReviewAnnotation.Key> seen = new ArrayList<>();
            store.addChangeListener(seen::add);

            store.upsert(finding("rs_a", "f1"));

            assertTrue(store.byId("rs_a", "f1").isPresent());
            assertEquals(1, seen.size());
        }
    }

    @Test
    void unsubscribingStopsDelivery(@TempDir Path dir) {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            List<ReviewAnnotation.Key> seen = new ArrayList<>();
            Runnable unsubscribe = store.addChangeListener(seen::add);
            unsubscribe.run();

            store.upsert(finding("rs_a", "f1"));

            assertTrue(seen.isEmpty());
        }
    }

    @Test
    void aThrowingTransformWritesNothingAndNotifiesNobody(@TempDir Path dir) {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            store.upsert(finding("rs_a", "f1"));
            List<ReviewAnnotation.Key> seen = new ArrayList<>();
            store.addChangeListener(seen::add);

            assertThrows(IllegalStateException.class, () ->
                    store.mutate(new ReviewAnnotation.Key("rs_a", "f1"), current -> {
                        throw new IllegalStateException("refused");
                    }));

            assertEquals(AnnotationStatus.OPEN, store.byId("rs_a", "f1").orElseThrow().status());
            assertTrue(seen.isEmpty());
        }
    }

    @Test
    void mutateIsEmptyForAnUnknownKey(@TempDir Path dir) {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            assertTrue(store.mutate(new ReviewAnnotation.Key("rs_a", "nope"),
                    current -> current).isEmpty());
        }
    }

    // ---- concurrency --------------------------------------------------------

    /**
     * Two writers race on one finding: while a slow transform is parked
     * inside its transform, a second thread mutates the same finding. Both
     * replies must survive.
     *
     * <p>A read-then-write pair loses one of them -- the slow thread writes a
     * value derived from what it read before the other thread's write landed.
     * This is the FX-destination-versus-MCP-router race, with the latch
     * standing in for the arbitrary scheduling that makes it rare in the
     * field and impossible to reproduce by hand.</p>
     */
    @Test
    void aConcurrentMutationIsNotLostWhileATransformIsRunning(@TempDir Path dir) throws Exception {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            ReviewAnnotation.Key key = new ReviewAnnotation.Key("rs_a", "f1");
            store.upsert(finding("rs_a", "f1"));
            CountDownLatch inTransform = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            Thread slow = new Thread(() -> store.mutate(key, current -> {
                inTransform.countDown();
                awaitOrFail(release);
                return current.withReply(new ReviewAnnotation.Message("You", AT, "slow"));
            }));
            slow.start();
            assertTrue(inTransform.await(5, TimeUnit.SECONDS), "the first transform never started");

            Thread other = new Thread(() -> store.mutate(key,
                    current -> current.withReply(new ReviewAnnotation.Message("Claude", AT, "fast"))));
            other.start();
            // Long enough that a store applying transforms outside its lock
            // would have let this write land and then be overwritten.
            Thread.sleep(200);
            release.countDown();
            slow.join(5_000);
            other.join(5_000);

            List<String> texts = store.byKey(key).orElseThrow().thread().stream()
                    .map(ReviewAnnotation.Message::text)
                    .toList();
            assertTrue(texts.contains("slow") && texts.contains("fast"),
                    "neither concurrent reply may be lost: " + texts);
        }
    }

    /**
     * Listeners fire outside the monitor, so a listener that blocks -- an FX
     * view rebuilding its cards, say -- must not stop another thread from
     * mutating, and must not cost that mutation.
     */
    @Test
    void aBlockingListenerNeitherDeadlocksNorLosesTheNextMutation(@TempDir Path dir) throws Exception {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            ReviewAnnotation.Key key = new ReviewAnnotation.Key("rs_a", "f1");
            store.upsert(finding("rs_a", "f1"));
            CountDownLatch inListener = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            store.addChangeListener(changed -> {
                if (inListener.getCount() > 0) {
                    inListener.countDown();
                    awaitOrFail(release);
                }
            });

            Thread first = new Thread(() -> store.mutate(key,
                    current -> current.withReply(new ReviewAnnotation.Message("You", AT, "first"))));
            first.start();
            assertTrue(inListener.await(5, TimeUnit.SECONDS), "the listener never ran");

            Thread second = new Thread(() -> store.mutate(key,
                    current -> current.withReply(new ReviewAnnotation.Message("Claude", AT, "second"))));
            second.start();
            second.join(5_000);
            assertFalse(second.isAlive(), "a blocked listener must not hold the store's monitor");

            release.countDown();
            first.join(5_000);

            List<String> texts = store.byKey(key).orElseThrow().thread().stream()
                    .map(ReviewAnnotation.Message::text)
                    .toList();
            assertTrue(texts.contains("first") && texts.contains("second"), String.valueOf(texts));
        }
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "latch never released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void waitForFile(Path file) throws InterruptedException {
        for (int i = 0; i < 100 && !Files.exists(file); i++) {
            Thread.sleep(20);
        }
        assertTrue(Files.exists(file), "annotations file was never written");
    }
}
