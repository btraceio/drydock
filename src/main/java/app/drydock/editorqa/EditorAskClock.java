package app.drydock.editorqa;

import java.time.Instant;

/**
 * The time source of the editor ask turn lane. Every deadline in
 * {@link EditorAskLane} is measured against this seam, so tests drive time
 * explicitly and never sleep.
 */
@FunctionalInterface
public interface EditorAskClock {

    /** The real wall clock. */
    EditorAskClock SYSTEM = Instant::now;

    Instant now();
}
