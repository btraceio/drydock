package app.drydock.ui;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * One home for "start async work without letting a synchronous failure
 * escape the caller".
 *
 * <p>Every service call the UI makes begins with {@code
 * CompletableFuture.supplyAsync(..., executor)} or a {@code
 * Platform.runLater}, and both throw synchronously once the app is shutting
 * down ({@code RejectedExecutionException}, {@code IllegalStateException}).
 * A throw at that point does not land in the {@code whenComplete} branch the
 * caller wrote for failures -- it escapes the click handler, which per
 * AGENTS.md is the one thing that must not happen: the busy modal or
 * placeholder is already on screen by then, and nothing is left to take it
 * down. Wrapping the call turns that into the failed future the caller
 * already knows how to report.</p>
 */
final class AsyncCalls {

    private AsyncCalls() {
    }

    /**
     * Invokes {@code call}, converting a synchronous throw -- or a {@code
     * null} future, which would otherwise NPE somewhere down the chain
     * instead of at the collaborator that produced it -- into a failed
     * future.
     */
    static <T> CompletableFuture<T> attempt(Supplier<CompletableFuture<T>> call) {
        try {
            CompletableFuture<T> future = call.get();
            return future == null
                    ? CompletableFuture.failedFuture(new NullPointerException("collaborator returned null"))
                    : future;
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }
}
