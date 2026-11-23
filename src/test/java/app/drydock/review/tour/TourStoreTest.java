package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static app.drydock.review.tour.TourFixtures.SCOPE;
import static app.drydock.review.tour.TourFixtures.coveringTour;
import static app.drydock.review.tour.TourFixtures.twoFileDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TourStoreTest {

    private final UnifiedDiff diff = twoFileDiff();

    @Test
    void aPutRecordSurvivesARestart(@TempDir Path dir) {
        Path file = dir.resolve("review-tours.json");
        TourRecord record = TourRecord.fresh(coveringTour(diff), diff).withReviewAnyway(true);
        try (TourStore store = new TourStore(file)) {
            store.put(record);
            store.flushPendingSaves();
        }
        try (TourStore reopened = new TourStore(file)) {
            assertEquals(Optional.of(record), reopened.forScope(SCOPE));
        }
    }

    @Test
    void putReplacesTheScopesPreviousTour(@TempDir Path dir) {
        try (TourStore store = new TourStore(dir.resolve("review-tours.json"))) {
            store.put(TourRecord.fresh(coveringTour(diff), diff));
            TourRecord second = TourRecord.fresh(coveringTour(diff), diff).withShelved(true);
            store.put(second);
            assertEquals(Optional.of(second), store.forScope(SCOPE));
        }
    }

    @Test
    void mutateAppliesAndNotifies(@TempDir Path dir) {
        try (TourStore store = new TourStore(dir.resolve("review-tours.json"))) {
            List<String> heard = new ArrayList<>();
            store.addChangeListener(heard::add);
            store.put(TourRecord.fresh(coveringTour(diff), diff));
            store.mutate(SCOPE, record -> record.withReviewAnyway(true));
            assertTrue(store.forScope(SCOPE).orElseThrow().reviewAnyway());
            assertEquals(List.of(SCOPE, SCOPE), heard);
            assertTrue(store.mutate("nope", record -> record).isEmpty());
        }
    }

    @Test
    void aThrowingListenerDoesNotStopTheOthers(@TempDir Path dir) {
        try (TourStore store = new TourStore(dir.resolve("review-tours.json"))) {
            List<String> heard = new ArrayList<>();
            store.addChangeListener(scope -> {
                throw new IllegalStateException("boom");
            });
            store.addChangeListener(heard::add);
            store.put(TourRecord.fresh(coveringTour(diff), diff));
            assertEquals(List.of(SCOPE), heard);
        }
    }

    @Test
    void aMalformedStepIsDroppedAndTheRestOfTheTourSurvives(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("review-tours.json");
        try (TourStore store = new TourStore(file)) {
            store.put(TourRecord.fresh(coveringTour(diff), diff));
            store.flushPendingSaves();
        }
        String text = Files.readString(file, StandardCharsets.UTF_8).replaceFirst("\"kind\"\\s*:\\s*\"predict\"", "\"kind\": 7");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        try (TourStore reopened = new TourStore(file)) {
            assertEquals(List.of("s2"), reopened.forScope(SCOPE).orElseThrow().tour().steps().stream()
                    .map(TourStep::id).toList());
        }
    }

    @Test
    void aCorruptTourFileStartsEmptyWithoutTouchingOtherFiles(@TempDir Path dir) throws Exception {
        Path annotations = dir.resolve("annotations.json");
        Files.writeString(annotations, "{\"version\":5}", StandardCharsets.UTF_8);
        Path file = dir.resolve("review-tours.json");
        Files.writeString(file, "{not json", StandardCharsets.UTF_8);
        try (TourStore store = new TourStore(file)) {
            assertTrue(store.forScope(SCOPE).isEmpty());
        }
        assertEquals("{\"version\":5}", Files.readString(annotations, StandardCharsets.UTF_8));
    }

    @Test
    void siblingOfSitsNextToTheStateFile() {
        assertEquals(Path.of("/x/review-tours.json"), TourStore.siblingOf(Path.of("/x/state.json")));
    }
}
