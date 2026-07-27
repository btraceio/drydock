package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotationStoreTest {

    private static final Instant AT = Instant.parse("2026-07-19T12:00:00Z");

    private static ReviewAnnotation sample(ManagedSessionId sessionId) {
        return ReviewAnnotation.create(sessionId, DiffScope.BASE, "src/SessionStore.java",
                "n14", "n16", new ReviewAnnotation.Message("You", AT, "The UncheckedIOException escapes here."));
    }

    @Test
    void annotationsRoundTripThroughJson(@TempDir Path dir) {
        ManagedSessionId sessionId = ManagedSessionId.newId();
        ReviewAnnotation annotation = sample(sessionId)
                .withReply(new ReviewAnnotation.Message("Claude", AT.plusSeconds(60), "Wrapped it in a catch."))
                .withStatus(AnnotationStatus.FIXED);

        List<ReviewAnnotation> decoded = AnnotationStore.fromJson(AnnotationStore.toJson(List.of(annotation)));

        assertEquals(List.of(annotation), decoded);
    }

    @Test
    void storePersistsAcrossReload(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("annotations.json");
        ManagedSessionId sessionId = ManagedSessionId.newId();

        AnnotationStore store = new AnnotationStore(file);
        ReviewAnnotation annotation = sample(sessionId);
        store.add(annotation);
        store.flushPendingSaves();
        waitForFile(file);

        AnnotationStore reloaded = new AnnotationStore(file);
        assertEquals(List.of(annotation), reloaded.forSession(sessionId));
    }

    @Test
    void mutateReplacesById(@TempDir Path dir) {
        AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"));
        ManagedSessionId sessionId = ManagedSessionId.newId();
        ReviewAnnotation annotation = sample(sessionId);
        store.add(annotation);

        store.mutate(annotation.id(), current -> current.withStatus(AnnotationStatus.RESOLVED));

        assertEquals(AnnotationStatus.RESOLVED, store.forSession(sessionId).get(0).status());
        store.flushPendingSaves();
    }

    @Test
    void forScopeFiltersBySessionAndScope(@TempDir Path dir) {
        AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"));
        ManagedSessionId sessionA = ManagedSessionId.newId();
        ManagedSessionId sessionB = ManagedSessionId.newId();
        store.add(sample(sessionA));
        store.add(ReviewAnnotation.create(sessionA, DiffScope.WORKING_TREE, "x", "n1", "n1",
                new ReviewAnnotation.Message("You", AT, "wt note")));
        store.add(sample(sessionB));

        assertEquals(1, store.forScope(sessionA, DiffScope.BASE).size());
        assertEquals(1, store.forScope(sessionA, DiffScope.WORKING_TREE).size());
        assertEquals(2, store.forSession(sessionA).size());
        store.flushPendingSaves();
    }

    @Test
    void removeSessionDropsAllItsAnnotations(@TempDir Path dir) {
        AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"));
        ManagedSessionId sessionId = ManagedSessionId.newId();
        store.add(sample(sessionId));
        store.add(sample(sessionId));

        store.removeSession(sessionId);

        assertTrue(store.forSession(sessionId).isEmpty());
        store.flushPendingSaves();
    }

    @Test
    void sentStatusRoundTripsThroughJson() {
        ReviewAnnotation sent = sample(ManagedSessionId.newId()).withStatus(AnnotationStatus.SENT);

        List<ReviewAnnotation> decoded = AnnotationStore.fromJson(AnnotationStore.toJson(List.of(sent)));

        assertEquals(List.of(sent), decoded);
        assertEquals(AnnotationStatus.SENT, decoded.get(0).status());
    }

    @Test
    void statusDecodeIsLenient() {
        assertEquals(AnnotationStatus.SENT, AnnotationStatus.fromPersisted(" sent "));
        assertEquals(AnnotationStatus.FIXED, AnnotationStatus.fromPersisted("FIXED"));
        assertEquals(AnnotationStatus.OPEN, AnnotationStatus.fromPersisted("no-such-status"));
    }

    @Test
    void malformedFileYieldsEmptyStore(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("annotations.json");
        Files.writeString(file, "{not json at all");

        AnnotationStore store = new AnnotationStore(file);

        assertTrue(store.forSession(ManagedSessionId.newId()).isEmpty());
    }

    /**
     * Documents the store-level contract {@code ReviewView.sendToClaude()}
     * relies on for its fix: a caller that lists annotations, then hands off
     * to something that can take a while (there: a synchronous post into the
     * live terminal), must re-read by id via {@link AnnotationStore#byId}
     * before writing a status change -- not compute it from the list entry
     * it captured before the hand-off. This proves the store returns the
     * up-to-date value (with the concurrent reply) when read that way, so a
     * write built from it does not clobber the other writer's change.
     */
    @Test
    void byIdReflectsAConcurrentUpdateMadeAfterAnEarlierListRead(@TempDir Path dir) {
        AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"));
        ReviewAnnotation annotation = sample(ManagedSessionId.newId());
        store.add(annotation);

        // Simulate the caller's initial list read (e.g. sendToClaude's
        // `forScope` snapshot) capturing the pre-reply value...
        ReviewAnnotation captured = store.forSession(annotation.sessionId()).get(0);

        // ...while another writer appends a reply during the hand-off window.
        store.mutate(annotation.id(), current -> current.withReply(
                new ReviewAnnotation.Message("Claude", AT.plusSeconds(5), "already on it")));

        // A write computed from the freshly re-read value keeps the reply;
        // one computed from `captured` would silently drop it.
        ReviewAnnotation fresh = store.byId(annotation.id()).orElseThrow();
        assertEquals(2, fresh.thread().size());
        ReviewAnnotation correctlyComputed = fresh.withStatus(AnnotationStatus.SENT);
        assertEquals(2, correctlyComputed.thread().size(), "re-reading before writing preserves the concurrent reply");

        ReviewAnnotation staleComputed = captured.withStatus(AnnotationStatus.SENT);
        assertEquals(1, staleComputed.thread().size(),
                "computing from the captured value would have discarded the concurrent reply");
        store.flushPendingSaves();
    }

    @Test
    void changeListenerFiresOnAddMutateAndRemove(@TempDir Path dir) throws Exception {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            List<String> events = new ArrayList<>();
            store.addChangeListener(events::add);

            ReviewAnnotation annotation = ReviewAnnotation.create(ManagedSessionId.newId(), DiffScope.BASE,
                    "src/Main.java", "n1", "n1",
                    new ReviewAnnotation.Message("You", Instant.EPOCH, "look here"));

            store.add(annotation);
            store.mutate(annotation.id(), current -> current.withStatus(AnnotationStatus.SENT));
            store.remove(annotation.id());

            assertEquals(List.of(annotation.id(), annotation.id(), annotation.id()), events);
        }
    }

    @Test
    void removingASessionFiresOneBulkChange(@TempDir Path dir) throws Exception {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            ManagedSessionId session = ManagedSessionId.newId();
            store.add(ReviewAnnotation.create(session, DiffScope.BASE, "a.java", "n1", "n1",
                    new ReviewAnnotation.Message("You", Instant.EPOCH, "one")));
            store.add(ReviewAnnotation.create(session, DiffScope.BASE, "b.java", "n2", "n2",
                    new ReviewAnnotation.Message("You", Instant.EPOCH, "two")));

            List<String> events = new ArrayList<>();
            store.addChangeListener(events::add);
            store.removeSession(session);

            assertEquals(1, events.size());
            assertNull(events.get(0), "a bulk change reports a null id");
        }
    }

    @Test
    void unsubscribingStopsDelivery(@TempDir Path dir) throws Exception {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            List<String> events = new ArrayList<>();
            Runnable unsubscribe = store.addChangeListener(events::add);
            unsubscribe.run();

            store.add(ReviewAnnotation.create(ManagedSessionId.newId(), DiffScope.BASE, "a.java", "n1", "n1",
                    new ReviewAnnotation.Message("You", Instant.EPOCH, "one")));

            assertTrue(events.isEmpty());
        }
    }

    @Test
    void aThrowingListenerDoesNotBreakTheWrite(@TempDir Path dir) throws Exception {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            store.addChangeListener(id -> {
                throw new IllegalStateException("listener blew up");
            });

            ReviewAnnotation annotation = ReviewAnnotation.create(ManagedSessionId.newId(), DiffScope.BASE,
                    "a.java", "n1", "n1", new ReviewAnnotation.Message("You", Instant.EPOCH, "one"));
            store.add(annotation);

            assertTrue(store.byId(annotation.id()).isPresent(), "the write must survive a bad listener");
        }
    }

    // ---- mutate: the atomic read-modify-write --------------------------------

    @Test
    void mutateAppliesTheTransformToTheStoredValue(@TempDir Path dir) throws Exception {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            ReviewAnnotation annotation = sample(ManagedSessionId.newId());
            store.add(annotation);

            Optional<ReviewAnnotation> updated =
                    store.mutate(annotation.id(), current -> current.withStatus(AnnotationStatus.RESOLVED));

            assertEquals(AnnotationStatus.RESOLVED, updated.orElseThrow().status());
            assertEquals(AnnotationStatus.RESOLVED, store.byId(annotation.id()).orElseThrow().status());
        }
    }

    @Test
    void mutateIsEmptyAndCallsNothingForAnUnknownId(@TempDir Path dir) throws Exception {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            List<String> applied = new ArrayList<>();

            Optional<ReviewAnnotation> updated = store.mutate("no-such-id", current -> {
                applied.add(current.id());
                return current;
            });

            assertTrue(updated.isEmpty());
            assertTrue(applied.isEmpty(), "the transform must not run for an id that is not stored");
        }
    }

    @Test
    void mutateNotifiesListeners(@TempDir Path dir) throws Exception {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            ReviewAnnotation annotation = sample(ManagedSessionId.newId());
            store.add(annotation);
            List<String> events = new ArrayList<>();
            store.addChangeListener(events::add);

            store.mutate(annotation.id(), current -> current.withStatus(AnnotationStatus.SENT));

            assertEquals(List.of(annotation.id()), events);
        }
    }

    /**
     * A transform that refuses by throwing leaves the store untouched and fires
     * nothing: that is how {@code McpToolRouter.review_reply} declines a thread
     * the human has already resolved, deciding against the stored value while
     * holding the lock that makes the decision meaningful.
     */
    @Test
    void aThrowingTransformWritesNothingAndNotifiesNobody(@TempDir Path dir) throws Exception {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            ReviewAnnotation annotation = sample(ManagedSessionId.newId());
            store.add(annotation);
            List<String> events = new ArrayList<>();
            store.addChangeListener(events::add);

            assertThrows(IllegalStateException.class, () -> store.mutate(annotation.id(), current -> {
                throw new IllegalStateException("refused");
            }));

            assertEquals(annotation, store.byId(annotation.id()).orElseThrow());
            assertTrue(events.isEmpty());
        }
    }

    /**
     * The two-thread case the whole method exists for: while one thread is
     * inside its transform, a second thread mutates the same annotation. Both
     * replies must survive.
     *
     * <p>A read-then-write pair loses one of them -- the slow thread
     * writes a value derived from what it read before the other thread's write
     * landed. This is the FX-tab-versus-MCP-router race, with the latch
     * standing in for the arbitrary scheduling that makes it rare in the
     * field and impossible to reproduce by hand.</p>
     */
    @Test
    void aConcurrentMutationIsNotLostWhileATransformIsRunning(@TempDir Path dir) throws Exception {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            ReviewAnnotation annotation = sample(ManagedSessionId.newId());
            store.add(annotation);
            CountDownLatch inTransform = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            Thread slow = new Thread(() -> store.mutate(annotation.id(), current -> {
                inTransform.countDown();
                awaitOrFail(release);
                return current.withReply(new ReviewAnnotation.Message("You", AT, "slow"));
            }));
            slow.start();
            assertTrue(inTransform.await(5, TimeUnit.SECONDS), "the first transform never started");

            Thread other = new Thread(() -> store.mutate(annotation.id(),
                    current -> current.withReply(new ReviewAnnotation.Message("Claude", AT, "fast"))));
            other.start();
            // Long enough that a store applying transforms outside its lock
            // would have let this write land and then be overwritten.
            Thread.sleep(200);
            release.countDown();
            slow.join(5_000);
            other.join(5_000);

            List<String> texts = store.byId(annotation.id()).orElseThrow().thread().stream()
                    .map(ReviewAnnotation.Message::text)
                    .toList();
            assertTrue(texts.contains("slow") && texts.contains("fast"),
                    "neither concurrent reply may be lost: " + texts);
        }
    }

    /**
     * Listeners fire outside the monitor, so a listener that blocks -- an FX
     * tab rebuilding its cards, say -- must not stop another thread from
     * mutating, and must not cost that mutation.
     */
    @Test
    void aBlockingListenerNeitherDeadlocksNorLosesTheNextMutation(@TempDir Path dir) throws Exception {
        try (AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"))) {
            ReviewAnnotation annotation = sample(ManagedSessionId.newId());
            store.add(annotation);
            CountDownLatch inListener = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            store.addChangeListener(id -> {
                if (inListener.getCount() > 0) {
                    inListener.countDown();
                    awaitOrFail(release);
                }
            });

            Thread first = new Thread(() -> store.mutate(annotation.id(),
                    current -> current.withReply(new ReviewAnnotation.Message("You", AT, "first"))));
            first.start();
            assertTrue(inListener.await(5, TimeUnit.SECONDS), "the listener never ran");

            Thread second = new Thread(() -> store.mutate(annotation.id(),
                    current -> current.withReply(new ReviewAnnotation.Message("Claude", AT, "second"))));
            second.start();
            second.join(5_000);
            assertFalse(second.isAlive(), "a blocked listener must not hold the store's monitor");

            release.countDown();
            first.join(5_000);

            List<String> texts = store.byId(annotation.id()).orElseThrow().thread().stream()
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
