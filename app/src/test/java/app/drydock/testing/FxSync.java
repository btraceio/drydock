package app.drydock.testing;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The FX test harness plumbing TestFX's {@code FxRobot} ran on, minus what
 * made it slow.
 *
 * <p>Three things are done differently from TestFX's {@code
 * ApplicationTest} lifecycle:</p>
 *
 * <ul>
 *   <li>The toolkit is started exactly once per JVM with {@link
 *       Platform#startup} and never torn down. {@code ApplicationTest}
 *       re-ran {@code FxToolkit.setupApplication} before every test, which
 *       re-created the application and re-set-up the stage behind a
 *       {@code waitForFxEvents} polling loop.</li>
 *   <li>{@link #waitForFxEvents()} is a single {@code runLater} barrier: one
 *       hop through the FX queue instead of TestFX's blind poll of up to
 *       25 x 100 ms sleeps (308 call sites in this suite). Everything queued
 *       on the FX thread before the barrier has run when it returns, which
 *       is the only guarantee the tests relied on.</li>
 *   <li>{@link #interact(Runnable)} joins the FX thread directly with a
 *       future instead of polling, and simply runs inline (no pulse settle --
 *       waiting for one from the FX thread would deadlock) when already on
 *       the FX thread.</li>
 * </ul>
 *
 * <p>Robot interactions (clicks, drags, keyboard) stay on TestFX's real
 * robot: the production code depends on the real Glass gesture pipeline
 * ({@code DRAG_DETECTED}, full press-drag-release with pick switching -- see
 * {@code ReviewDiffColumn}'s gutter selection), and synthesizing that by
 * hand would reimplement JavaFX's gesture machinery. Only the lifecycle and
 * the waiting were the measured cost.</p>
 */
public final class FxSync {

    /** Longest an {@code interact}/{@code waitForFxEvents} may block the caller. */
    private static final long BARRIER_TIMEOUT_SECONDS = 10;

    private static final AtomicBoolean PLATFORM_STARTED = new AtomicBoolean();

    private FxSync() {
    }

    /**
     * Starts the FX toolkit once per JVM. Later calls (including from other
     * test classes) are no-ops; a second {@code Platform.startup} throws
     * {@code IllegalStateException}, which is exactly the "already running"
     * case ignored here.
     *
     * <p>A failing first {@code Platform.startup} (a broken Monocle/Glass in
     * a damaged CI environment) releases the flag again and rethrows, so the
     * failure is attributed to the first caller -- a flag stuck on "started"
     * would turn every later {@code ensurePlatform} into a no-op and smear
     * the failure across the whole suite as missing-FX-thread errors.</p>
     */
    public static void ensurePlatform() {
        if (PLATFORM_STARTED.compareAndSet(false, true)) {
            try {
                Platform.startup(() -> {
                    // Closing the last window would otherwise shut the toolkit
                    // down (implicit exit), leaving the next test with no FX
                    // thread -- TestFX's robot calls then block forever on an
                    // unbounded semaphore. The toolkit must outlive every
                    // single test's windows.
                    Platform.setImplicitExit(false);
                });
            } catch (RuntimeException e) {
                PLATFORM_STARTED.set(false);
                throw e;
            }
        }
    }

    /**
     * Runs {@code action} on the FX thread and returns after it completed.
     * Inline when already on the FX thread, a joined future otherwise.
     *
     * <p>On the FX thread the pulse settle is SKIPPED (waiting for a pulse
     * from the FX thread deadlocks -- the timer that would count the latch is
     * itself scheduled via {@code runLater}, behind this very call). A caller
     * on the FX thread therefore gets the action's effects but no completed
     * layout pass; geometry-sensitive assertions must go through {@link
     * #interact} from a non-FX thread.</p>
     */
    public static void interact(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        joinOnFx(new FutureTask<>(action, null));
        settleOnePulse();
    }

    /**
     * {@link #interact(Runnable)} with a value. On the FX thread the pulse
     * settle is skipped for the same deadlock reason -- see the Runnable
     * overload.
     */
    public static <T> T interact(Callable<T> action) {
        if (Platform.isFxApplicationThread()) {
            try {
                return action.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        T value = joinOnFx(new FutureTask<>(action));
        settleOnePulse();
        return value;
    }

    /**
     * One runLater hop through the FX queue, then one full pulse. Returns
     * when every runnable queued before the barrier has executed AND a
     * subsequent scene pulse (layout pass included) has completed -- which
     * is what a geometry assertion after {@code setWidth} etc. needs. The
     * pulse wait comes from an {@link AnimationTimer} firing on the FX
     * thread (two handles bracket one completed pulse) while the caller
     * waits on a latch; nothing ever blocks the FX thread itself.
     */
    public static void waitForFxEvents() {
        if (Platform.isFxApplicationThread()) {
            // Called from the FX thread (e.g. from start()): the queue up to
            // here has already run, and waiting from here would deadlock --
            // the barrier runLater sits behind this very runnable. TestFX's
            // blockFxThreadWithSemaphore behaved the same way via its
            // runOnFxThread short-circuit. NOTE: no pulse settle happens on
            // this path, so the "completed pulse" contract below does NOT
            // hold for an FX-thread caller; geometry assertions need a
            // non-FX-thread interact().
            return;
        }
        CountDownLatch queued = new CountDownLatch(1);
        Platform.runLater(queued::countDown);
        await(queued, "FX event queue did not drain");
        settleOnePulse();
    }

    /**
     * Waits for one completed pulse (two AnimationTimer handles bracket it:
     * the second fires only after the first pulse's layout finished). This
     * is what TestFX's polling {@code interact} provided implicitly: its
     * {@code asyncFx + waitFor} left the pulse timer enough air to apply
     * CSS and run layout between the action and the caller's next read, so
     * tests could {@code applyCss()}-and-read straight after an interact.
     *
     * <p>If the renderer stops producing frames entirely (all windows closed,
     * throttled headless), the timer never fires; after half the barrier
     * timeout the wait falls back to a single runLater hop -- weaker than a
     * bracketed pulse (layout may not have run), but progress instead of
     * every caller burning the full timeout. The timeout error names which
     * stage stalled.</p>
     */
    private static void settleOnePulse() {
        CountDownLatch pulses = new CountDownLatch(2);
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                pulses.countDown();
                if (pulses.getCount() == 0) {
                    stop();
                }
            }
        };
        try {
            Platform.runLater(timer::start);
            if (pulses.await(BARRIER_TIMEOUT_SECONDS / 2, TimeUnit.SECONDS)) {
                return;
            }
            // Renderer stalled: fall back to one runLater hop so the failure
            // mode is slow-and-weak rather than a cascade of 10s timeouts.
            CountDownLatch queued = new CountDownLatch(1);
            Platform.runLater(queued::countDown);
            if (!queued.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("no FX pulse within " + BARRIER_TIMEOUT_SECONDS + "s"
                        + " and the FX queue did not drain (renderer stalled and queue blocked)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for an FX pulse", e);
        } finally {
            // The latch await can time out or be interrupted; either way the
            // per-frame timer must not keep running for the rest of the JVM.
            timer.stop();
        }
    }

    private static void await(CountDownLatch latch, String what) {
        try {
            if (!latch.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError(what + " within " + BARRIER_TIMEOUT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while " + what, e);
        }
    }

    /**
     * Polls {@code condition} on the calling thread until true or timeout.
     * The checked {@link TimeoutException} matches the TestFX call it
     * replaces, so existing try/catch call sites keep compiling unchanged.
     */
    public static boolean waitFor(long timeout, TimeUnit unit, Callable<Boolean> condition)
            throws TimeoutException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        try {
            while (true) {
                if (condition.call()) {
                    return true;
                }
                if (System.nanoTime() >= deadline) {
                    throw new TimeoutException("condition not met within " + timeout + " " + unit);
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            TimeoutException timedOut = new TimeoutException("interrupted while waiting for condition");
            timedOut.initCause(e);
            throw timedOut;
        } catch (Exception e) {
            if (e instanceof TimeoutException te) {
                throw te;
            }
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e);
        }
    }

    /** Runs {@code action} on the FX thread as soon as it is free. */
    public static Future<Void> asyncFx(Runnable action) {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        return task;
    }

    /** {@link #asyncFx(Runnable)} with a value. */
    public static <T> Future<T> asyncFx(Callable<T> action) {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task;
    }

    /**
     * Runs {@code action} on the FX thread and returns its value, giving up
     * after {@code timeoutMillis} (the TestFX signature this replaces).
     */
    public static <T> T waitForAsyncFx(long timeoutMillis, Callable<T> action) {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        try {
            return task.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("FX action did not complete within " + timeoutMillis + "ms", e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Closes every open window; the startup'ed toolkit stays alive. */
    static void closeAllStageWindows() {
        interact(() -> {
            for (javafx.stage.Window window : java.util.List.copyOf(javafx.stage.Window.getWindows())) {
                if (window instanceof javafx.stage.Stage stageWindow) {
                    stageWindow.close();
                }
            }
        });
    }

    private static <T> T joinOnFx(FutureTask<T> task) {
        Platform.runLater(task);
        try {
            return task.get(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError(
                    "FX thread did not execute the action within " + BARRIER_TIMEOUT_SECONDS + "s", e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
