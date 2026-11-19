package app.drydock.intellij.tasks;

import app.drydock.domain.SessionMode;
import app.drydock.intellij.tasks.WorkflowInvocationState.Accepted;
import app.drydock.intellij.tasks.WorkflowInvocationState.Input;
import app.drydock.sdlc.Cancellation;
import app.drydock.sdlc.MaterializedWorkflow;
import app.drydock.sdlc.SelectedWorkflow;
import app.drydock.sdlc.SessionWorkflowResourceOwner;
import app.drydock.sdlc.SessionWorkflowResourceOwner.Outcome;
import app.drydock.sdlc.WorkflowMaterializationRequest;
import app.drydock.sdlc.WorkflowMaterializationRequest.Destination;
import app.drydock.sdlc.WorkflowMaterializationRequest.ReviewContext;
import app.drydock.sdlc.WorkflowMaterializer;
import app.drydock.sdlc.WorkflowResolution;
import app.drydock.sdlc.WorkflowResolution.WorkflowProblem;
import app.drydock.sdlc.WorkflowResourceLease;
import app.drydock.sdlc.WorkflowTarget;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Drives one invocation's preparation off the UI thread: resolve, then materialize. Progress is shown before any
 * background work and cleared on every completion (success, failure, exception, timeout, cancel, dispose). An
 * invalid selected source never falls back silently: the UI gets exactly two choices, {@link #chooseDefaultOnce}
 * or {@link #cancel}. Every completion is fenced by the {@link WorkflowInvocationState} generation; a late result
 * only releases the lease it carries. The pending lease passes to the session owner on {@link #commitLaunch}.
 * UI callbacks are delivered through the UI executor and re-checked there for currency.
 */
public final class WorkflowInvocationController implements Disposable {

    /** Resolves the (already project-bound) selection; runs on the background executor. */
    public interface Resolver {
        WorkflowResolution resolve(WorkflowTarget target, Cancellation cancellation);
    }

    /** What to prepare: the action target and where its references will be consumed. */
    public record Request(WorkflowTarget target, Destination destination, SessionMode mode,
                          Optional<ReviewContext> review) {
    }

    /** Outcome of a preparation; {@code workflow} is empty when the default applies. */
    public record Prepared(Optional<MaterializedWorkflow> workflow, long bytes, Destination destination) {
        static final Prepared DEFAULT = new Prepared(Optional.empty(), 0, Destination.LOCAL);
    }

    /** Presentation callbacks; always invoked on the UI executor, never for a superseded generation. */
    public interface Ui {
        /**
         * The modality state completions must be delivered on. A dialog-hosted Ui returns its own
         * dialog's modality: a {@code ModalityState.nonModal()} runnable never executes while any
         * modal dialog is showing, so a modal New Task dialog would starve its own workflow
         * completions (Start stuck grey behind an eternal "Preparing workflow…"). Non-dialog
         * hosts keep the platform default.
         */
        default ModalityState modality() {
            return ModalityState.nonModal();
        }

        void progress(boolean shown);

        void ready(String seedText, boolean edited);

        void invalidSource(WorkflowProblem problem);

        void failed(String message);
    }

    private final Executor background;
    private final Executor uiExecutor;
    private final Resolver resolver;
    private final WorkflowMaterializer materializer;
    private final SessionWorkflowResourceOwner owner;
    private final Ui ui;
    private final long timeoutMillis;
    private final String defaultSeed;
    private final WorkflowInvocationState<Prepared> state;

    private Cancellation cancellation = Cancellation.none();
    private WorkflowResourceLease pending;
    private Prepared pendingPrepared;
    private boolean progressShown;
    private boolean disposed;
    private long token;

    public WorkflowInvocationController(Executor background, Executor uiExecutor, Resolver resolver,
            WorkflowMaterializer materializer, SessionWorkflowResourceOwner owner, Ui ui, long timeoutMillis,
            String defaultSeed, Input initial) {
        this.background = Objects.requireNonNull(background, "background");
        this.uiExecutor = Objects.requireNonNull(uiExecutor, "uiExecutor");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.materializer = Objects.requireNonNull(materializer, "materializer");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.timeoutMillis = timeoutMillis;
        this.defaultSeed = Objects.requireNonNull(defaultSeed, "defaultSeed");
        this.state = new WorkflowInvocationState<>(initial);
        this.token = 1;
    }

    /** Starts or restarts (retry, harness switch) a preparation; the caller thread only flips progress on. */
    public void prepare(Request request, Input input) {
        Cancellation mine = new Cancellation();
        long my;
        synchronized (this) {
            if (disposed) {
                return;
            }
            cancellation.cancel();
            releaseLocked();
            cancellation = mine;
            my = token = state.begin(input);
            progressShown = true;
        }
        ui.progress(true);
        background.execute(() -> run(request, my, mine));
    }

    private void run(Request request, long my, Cancellation mine) {
        try {
            WorkflowResolution resolution = resolver.resolve(request.target(), mine);
            switch (resolution) {
                case WorkflowResolution.Missing m -> finishReady(my, Prepared.DEFAULT, defaultSeed);
                case WorkflowResolution.Invalid i -> {
                    if (state.waitForFallback(my)) {
                        deliver(my, () -> ui.invalidSource(i.problem()));
                    }
                }
                case WorkflowResolution.Selected s -> materialize(request, my, mine, s.workflow());
            }
        } catch (RuntimeException | Error e) {
            deliver(my, () -> ui.failed(String.valueOf(e.getMessage())));
        }
    }

    private void materialize(Request request, long my, Cancellation mine, SelectedWorkflow selected) {
        var req = new WorkflowMaterializationRequest(UUID.randomUUID().toString(), my, selected,
                request.destination(), request.mode(), request.review());
        CompletableFuture<WorkflowMaterializer.Result> future = materializer.materialize(req, mine);
        WorkflowMaterializer.Result result;
        try {
            result = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            mine.cancel();
            future.thenAccept(WorkflowInvocationController::releaseLate);
            deliver(my, () -> ui.failed("Workflow preparation timed out"));
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mine.cancel();
            future.thenAccept(WorkflowInvocationController::releaseLate);
            deliver(my, () -> ui.failed("Workflow preparation interrupted"));
            return;
        } catch (ExecutionException e) {
            deliver(my, () -> ui.failed(String.valueOf(e.getCause())));
            return;
        }
        switch (result) {
            case WorkflowMaterializer.Success ok -> {
                var prepared = new Prepared(Optional.of(ok.workflow()), selected.bundle().totalBytes(),
                        request.destination());
                boolean taken;
                synchronized (this) {
                    taken = !disposed && state.ready(my, prepared, selected.promptText());
                    if (taken) {
                        pending = ok.workflow().lease();
                        pendingPrepared = prepared;
                    }
                }
                if (taken) {
                    deliver(my, () -> ui.ready(state.text(), state.edited()));
                } else {
                    releaseLate(ok);
                }
            }
            case WorkflowMaterializer.Failure f -> deliver(my, () -> ui.failed(f.message()));
            case WorkflowMaterializer.Cancelled c -> deliver(my, () -> { });
        }
    }

    private void finishReady(long my, Prepared prepared, String seed) {
        boolean ok;
        synchronized (this) {
            ok = !disposed && state.ready(my, prepared, seed);
        }
        if (ok) {
            deliver(my, () -> ui.ready(state.text(), state.edited()));
        }
    }

    /** Hops to the UI executor; a still-current generation clears progress, then reports. */
    private void deliver(long my, Runnable report) {
        uiExecutor.execute(() -> {
            boolean current;
            synchronized (this) {
                current = !disposed && state.isCurrent(my);
                if (current) {
                    progressShown = false;
                }
            }
            if (current) {
                ui.progress(false);
                report.run();
            }
        });
    }

    private static void releaseLate(WorkflowMaterializer.Result r) {
        if (r instanceof WorkflowMaterializer.Success s) {
            s.workflow().lease().release();
        }
    }

    /** The user chose the default for this invocation only (never persisted). */
    public void chooseDefaultOnce() {
        boolean ok;
        synchronized (this) {
            ok = !disposed && state.chooseDefaultOnce(token, defaultSeed);
        }
        if (ok) {
            ui.ready(state.text(), state.edited());
        }
    }

    public boolean edit(String text) {
        return state.edit(text);
    }

    /** Freezes the prompt for launch; empty when not ready or superseded. */
    public Optional<Accepted<Prepared>> accept() {
        synchronized (this) {
            return disposed ? Optional.empty() : state.accept(token);
        }
    }

    /**
     * The launch/delivery was accepted or its send is ambiguous: the pending lease moves to the session owner,
     * which now owns cleanup. Empty when this generation is no longer committable.
     */
    public Optional<Outcome> commitLaunch(String session, String bundleId) {
        WorkflowResourceLease lease;
        Prepared prepared;
        synchronized (this) {
            if (disposed || !state.commit(token)) {
                return Optional.empty();
            }
            lease = pending;
            prepared = pendingPrepared;
            pending = null;
        }
        if (lease == null) {
            return Optional.of(Outcome.ACCEPTED);
        }
        boolean remote = prepared.destination() == Destination.WORKSPACE_BOX;
        return Optional.of(owner.accept(session, bundleId, remote ? 0 : prepared.bytes(),
                remote ? prepared.bytes() : 0, lease));
    }

    /** A launch that definitely did not happen: the pending lease is released, not retained. */
    public void launchFailed() {
        synchronized (this) {
            releaseLocked();
        }
    }

    /** Cancel/close: invalidates every pending completion and releases the pending lease. */
    public void cancel() {
        stop(false);
    }

    @Override
    public void dispose() {
        stop(true);
    }

    private void stop(boolean dispose) {
        boolean clear;
        synchronized (this) {
            disposed |= dispose;
            if (!state.cancel()) {
                return;
            }
            cancellation.cancel();
            releaseLocked();
            clear = progressShown;
            progressShown = false;
        }
        if (clear) {
            uiExecutor.execute(() -> ui.progress(false));
        }
    }

    private void releaseLocked() {
        if (pending != null) {
            pending.release();
            pending = null;
        }
    }
}
