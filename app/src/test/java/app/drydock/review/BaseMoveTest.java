package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which base moves are worth telling the reviewer about (spec §9.2).
 * Marking every verdict stale on any base move treats "main advanced in an
 * unrelated subsystem" the same as "main rewrote a function this hunk
 * calls", and on an active repository the first is nearly all of them --
 * which is how a confirm button becomes reflex.
 *
 * <p>{@code between} spawns git and is covered by the running-app pass;
 * what is unit-tested here is the decision the spawn feeds.</p>
 */
class BaseMoveTest {

    private static BaseMove.Delta delta(String... files) {
        return new BaseMove.Delta(false, new TreeSet<>(List.of(files)));
    }

    @Test
    void aBaseMoveTouchingOnlyUnrelatedFilesCannotMatter() {
        assertFalse(BaseMove.couldMatter(delta("docs/README.md", "web/app.ts"),
                List.of("src/guards.cpp", "src/guards.h")));
    }

    @Test
    void aBaseMoveTouchingAFileThisScopeChangesMatters() {
        assertTrue(BaseMove.couldMatter(delta("docs/README.md", "src/guards.h"),
                List.of("src/guards.cpp", "src/guards.h")));
    }

    /**
     * Failing safe is the only defensible default for a signal about what was
     * read: if the old base cannot be resolved -- a force-push, a collected
     * commit -- everything is a candidate.
     */
    @Test
    void anUnresolvableOldBaseMattersRegardlessOfFiles() {
        assertTrue(BaseMove.couldMatter(new BaseMove.Delta(true, new TreeSet<>()),
                List.of("src/guards.cpp")));
    }

    @Test
    void anEmptyDeltaCannotMatter() {
        assertFalse(BaseMove.couldMatter(delta(), List.of("src/guards.cpp")));
    }

    /** A scope with no files is not a reason to mark anything. */
    @Test
    void aScopeWithNoFilesCannotBeAffected() {
        assertFalse(BaseMove.couldMatter(delta("src/guards.h"), List.of()));
    }
}
