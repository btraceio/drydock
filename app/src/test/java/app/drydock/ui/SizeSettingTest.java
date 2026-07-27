package app.drydock.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the apply/persist sequencing that the settings sliders drive. No
 * JavaFX toolkit is involved: {@link SizeSetting} exists precisely so this
 * logic can be driven directly (the convention in this module -- see {@code
 * RemoteRepositoryModalTest} -- is to test pure helpers only).
 */
class SizeSettingTest {

    /**
     * Stands in for {@code ThemeManager}: applying a size not yet cached
     * completes only when the queued FX callback runs, so the applied size
     * lags the request by one turn -- the situation every assertion below
     * cares about.
     */
    private static final class DeferredApply {
        private final Deque<Runnable> queued = new ArrayDeque<>();
        private double appliedSize;

        DeferredApply(double initial) {
            this.appliedSize = initial;
        }

        void request(double size) {
            queued.add(() -> appliedSize = size);
        }

        void runQueued() {
            while (!queued.isEmpty()) {
                queued.remove().run();
            }
        }
    }

    /**
     * The regression this type was extracted for: an arrow key produces a
     * single tick with no drag in progress, so the persist runs in the same
     * turn as the apply, while the applied size is still the previous one.
     * Persisting the applied size here stored one step behind, invisibly
     * until the next launch.
     */
    @Test
    void commitInTheSameTurnAsTheApplyStillPersistsTheChosenSize() {
        DeferredApply applied = new DeferredApply(14.0);
        List<Double> persisted = new ArrayList<>();
        SizeSetting setting = new SizeSetting(() -> applied.appliedSize, applied::request, persisted::add);

        setting.changed(15.0, false);

        assertEquals(List.of(15.0), persisted);
        assertEquals(14.0, applied.appliedSize, "the apply must still be pending, or the test proves nothing");
        applied.runQueued();
        assertEquals(15.0, applied.appliedSize);
    }

    /** Repeated keyboard steps each persist their own size, never the one before. */
    @Test
    void everyDiscreteStepPersistsItsOwnSize() {
        DeferredApply applied = new DeferredApply(14.0);
        List<Double> persisted = new ArrayList<>();
        SizeSetting setting = new SizeSetting(() -> applied.appliedSize, applied::request, persisted::add);

        setting.changed(14.5, false);
        setting.changed(15.0, false);
        setting.changed(15.5, false);

        assertEquals(List.of(14.5, 15.0, 15.5), persisted);
    }

    /** A drag applies per tick and persists exactly once, at the released value. */
    @Test
    void dragAppliesEveryTickAndPersistsOnlyOnRelease() {
        DeferredApply applied = new DeferredApply(14.0);
        List<Double> persisted = new ArrayList<>();
        SizeSetting setting = new SizeSetting(() -> applied.appliedSize, applied::request, persisted::add);

        setting.changed(14.5, true);
        setting.changed(15.0, true);
        setting.changed(15.5, true);
        assertTrue(persisted.isEmpty(), "no disk traffic mid-drag");
        assertEquals(3, applied.queued.size(), "every tick still applies live");

        setting.dragEnded(15.5);

        assertEquals(List.of(15.5), persisted);
    }

    /**
     * A drag whose live applies have not landed yet still persists where the
     * slider came to rest, not the size the UI happens to be showing.
     */
    @Test
    void dragEndedPersistsTheReleasedSizeNotTheAppliedOne() {
        DeferredApply applied = new DeferredApply(14.0);
        List<Double> persisted = new ArrayList<>();
        SizeSetting setting = new SizeSetting(() -> applied.appliedSize, applied::request, persisted::add);

        setting.changed(16.0, true);
        setting.dragEnded(16.0);

        assertEquals(List.of(16.0), persisted);
        assertEquals(14.0, applied.appliedSize);
    }

    /** {@code current()} is the one legitimate read of applied state: the slider's opening value. */
    @Test
    void currentReadsTheAppliedSize() {
        DeferredApply applied = new DeferredApply(12.5);
        SizeSetting setting = new SizeSetting(() -> applied.appliedSize, applied::request, size -> {
        });

        assertEquals(12.5, setting.current());
    }
}
