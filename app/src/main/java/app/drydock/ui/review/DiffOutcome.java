package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;

import java.util.Objects;

/**
 * What a diff attempt produced for one scope.
 *
 * <p>Three states, not two. "Failed" and "still loading" look identical to
 * anything downstream that only learns about successes, and the intent rail
 * has to tell them apart -- a rail reading "Diffing…" beside a column
 * reading "Could not diff" is the kind of contradiction that taught readers
 * to distrust the rail in the first place.</p>
 */
public sealed interface DiffOutcome {

    /** A diff request is in flight. */
    record Diffing() implements DiffOutcome { }

    /** The diff resolved; {@code diff} may legitimately contain zero files. */
    record Loaded(UnifiedDiff diff) implements DiffOutcome {
        public Loaded {
            Objects.requireNonNull(diff, "diff");
        }
    }

    /** The diff could not be produced; {@code message} is what the column shows. */
    record Failed(String message) implements DiffOutcome {
        public Failed {
            Objects.requireNonNull(message, "message");
        }
    }
}
