package app.cpm.review;

import app.cpm.domain.ManagedSessionId;
import app.cpm.git.DiffScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void updateReplacesById(@TempDir Path dir) {
        AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"));
        ManagedSessionId sessionId = ManagedSessionId.newId();
        ReviewAnnotation annotation = sample(sessionId);
        store.add(annotation);

        store.update(annotation.withStatus(AnnotationStatus.RESOLVED));

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

    private static void waitForFile(Path file) throws InterruptedException {
        for (int i = 0; i < 100 && !Files.exists(file); i++) {
            Thread.sleep(20);
        }
        assertTrue(Files.exists(file), "annotations file was never written");
    }
}
