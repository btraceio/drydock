package app.drydock.sdlc;

import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancel flag for one workflow invocation; {@link #cancel()} is idempotent and thread-safe. */
public final class Cancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    public static Cancellation none() {
        return new Cancellation();
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
