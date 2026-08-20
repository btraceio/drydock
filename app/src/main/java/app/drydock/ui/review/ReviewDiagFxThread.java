package app.drydock.ui.review;

import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One home for "run this {@code diag*} accessor on the FX thread, whichever
 * thread called it".
 *
 * <p>The {@code diag*} methods on {@link ReviewDiffColumn} and {@link
 * SessionReviewView} exist for two callers that are never the FX thread
 * themselves: a JUnit/TestFX test thread polling for an async diff to land,
 * and the diagnostic script driver in {@code DrydockApplication}. Both read
 * or mutate plain fields the FX Application Thread owns -- {@code
 * ObservableList}s, selection indices, scene-graph nodes. A method that
 * reaches into that state directly from an arbitrary thread races the FX
 * thread's own mutations of it: {@code List.copyOf} over a list mid-{@code
 * setAll} throws {@code ConcurrentModificationException}, and a mutator like
 * {@code diagSelectRange} touching pseudo-classes or scrolling a {@code
 * ListView} off-thread is undefined behavior even when it happens not to
 * throw.</p>
 *
 * <p>{@link #call} closes that window: it always runs {@code work} ON the FX
 * thread -- directly if already there, otherwise by posting it via {@link
 * Platform#runLater} and blocking the caller on a {@link FutureTask} with a
 * bounded timeout. Do NOT "simplify" a {@code diag*} accessor back to
 * touching FX state directly from its own thread -- that is exactly the bug
 * this class exists to close, and it only fails intermittently, under the
 * timing window a full-suite run hits and an isolated run does not.</p>
 */
final class ReviewDiagFxThread {

    private static final long TIMEOUT_SECONDS = 10;

    private ReviewDiagFxThread() {
    }

    /**
     * Runs {@code work} on the FX Application Thread and returns its result,
     * regardless of which thread calls this method.
     *
     * @throws IllegalStateException if the FX thread does not complete
     *         {@code work} within {@value #TIMEOUT_SECONDS} seconds -- a
     *         wedged FX thread must fail loudly here rather than hang the
     *         caller (a test, or the diag driver) forever
     */
    static <T> T call(Callable<T> work) {
        if (Platform.isFxApplicationThread()) {
            try {
                return work.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("diag FX accessor failed", e);
            }
        }

        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        try {
            return task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the FX thread", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("diag FX accessor failed", cause);
        } catch (TimeoutException e) {
            throw new IllegalStateException(
                    "FX thread did not run the diag accessor within " + TIMEOUT_SECONDS
                            + "s -- it may be wedged", e);
        }
    }
}
