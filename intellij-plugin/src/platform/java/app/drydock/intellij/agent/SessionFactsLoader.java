package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.DrydockPluginService;

import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Loads {@link SessionPaneFacts} off the EDT and remembers the answers, so
 * selecting a session again costs nothing: the transcript scan, resumability
 * discovery and token estimate run once per session, not once per switch.
 * Replaces the editor's per-catalog-change {@code reloadSession}.
 *
 * <p>Every load is a request with an immutable ID and generation. A request
 * is settled once, by the single terminal gate {@link #complete}: only while
 * the loader is not disposed and the request is still the current one for its
 * session. A failed or rejected load is settled as a {@code FAILED} result and
 * is <em>not</em> cached, so the next selection retries instead of showing a
 * stale placeholder forever.</p>
 *
 * <p>The monitor guards state only. Work is submitted to the executor and the
 * listener is called after releasing it, so a synchronous executor or a
 * re-entrant listener cannot deadlock. The listener is called once per
 * accepted terminal outcome, on whichever thread settled it (normally the
 * executor's, or the caller's for a synchronous submission failure); the
 * receiver hops to the UI thread itself and must still check its own
 * lifetime and the result's generation, because a call already in flight
 * cannot be revoked by {@link #dispose}. Thread-safe.</p>
 */
final class SessionFactsLoader {

    /** Immutable answer for one session: ID, the generation it answers, the facts (possibly {@code FAILED}). */
    record Result(ManagedSessionId id, long generation, SessionPaneFacts facts) {
        Result {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(facts, "facts");
        }
    }

    /**
     * The blocking probes behind one load, run on the executor. Empty means
     * the catalog does not hold the session; a throw is a failed load.
     */
    interface Probes {
        Optional<SessionPaneFacts.Inputs> probe(ManagedSessionId id) throws Exception;
    }

    /** One load of one session; {@code terminal} is its settled answer, guarded by the loader monitor. */
    private static final class Request {
        final ManagedSessionId id;
        final long generation;
        Result terminal;

        Request(ManagedSessionId id, long generation) {
            this.id = id;
            this.generation = generation;
        }
    }

    private final CompletableFuture<?> initialization;
    private final Executor executor;
    private final Probes probes;
    private final Consumer<Result> listener;
    private final Map<ManagedSessionId, Result> cache = new HashMap<>();
    private final Map<ManagedSessionId, Request> pending = new HashMap<>();
    private final Set<ManagedSessionId> resolvedBefore = new HashSet<>();
    private long nextGeneration;
    private boolean disposed;

    /** A loader whose work needs no initialization: readiness is already successfully complete. */
    SessionFactsLoader(Executor executor, Probes probes, Consumer<Result> listener) {
        this(CompletableFuture.completedFuture(null), executor, probes, listener);
    }

    /**
     * A loader that submits no work until {@code initialization} completes
     * successfully. Its failure or cancellation settles each active request
     * as {@code FAILED} without a probe; {@link #dispose} never cancels it.
     */
    SessionFactsLoader(CompletableFuture<?> initialization, Executor pluginExecutor, Probes probes, Consumer<Result> listener) {
        this.initialization = Objects.requireNonNull(initialization, "initialization");
        this.executor = Objects.requireNonNull(pluginExecutor, "executor");
        this.probes = Objects.requireNonNull(probes, "probes");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** The production loader: the plugin's own readiness and executor, with the live probes. */
    static SessionFactsLoader forProject(Project project, DrydockPluginService plugin, Consumer<Result> listener) {
        return new SessionFactsLoader(plugin.initialization(), plugin.executor(),
                SessionFactsProbes.indexed(project, plugin), listener);
    }

    /**
     * The facts for {@code id} now: the cached answer with no probe, or a
     * {@code LOADING} result while a load (started here unless one is already
     * in flight) is pending, or that request's own {@code FAILED} answer if it
     * was already settled by a synchronous submission failure. Memory-only on
     * the caller's thread; after {@link #dispose} an inert generation-0
     * {@code LOADING} result and nothing is scheduled.
     */
    Result select(ManagedSessionId id) {
        Objects.requireNonNull(id, "id");
        Request request;
        boolean started = false;
        synchronized (this) {
            if (disposed) {
                return inert(id);
            }
            Result cached = cache.get(id);
            if (cached != null) {
                return cached;
            }
            request = pending.get(id);
            if (request == null) {
                request = new Request(id, ++nextGeneration);
                pending.put(id, request);
                started = true;
            }
        }
        if (started) {
            submit(request);
        }
        synchronized (this) {
            if (disposed) {
                return inert(id);
            }
            Result cached = cache.get(id);
            if (cached != null) {
                return cached;
            }
            return request.terminal != null ? request.terminal : new Result(id, request.generation, SessionPaneFacts.loading());
        }
    }

    /**
     * The catalog changed: reload every initialized session. Only IDs that
     * already hold a cached answer are refreshed; a load still pending for an
     * ID that has no answer yet is not started a second time. Replacement
     * requests are installed under the monitor, each superseding any earlier
     * request for its ID, then submitted after releasing it. The old answers
     * stay readable until a replacement settles; an accepted replacement
     * failure removes the old answer, a stale one cannot. No-op after
     * {@link #dispose}.
     */
    void refreshInitialized() {
        List<Request> replacements = new ArrayList<>();
        synchronized (this) {
            if (disposed) {
                return;
            }
            for (ManagedSessionId id : cache.keySet()) {
                Request request = new Request(id, ++nextGeneration);
                pending.put(id, request);
                replacements.add(request);
            }
        }
        replacements.forEach(this::submit);
    }

    /** Drops the cache and every pending request; later completions settle nothing. */
    synchronized void dispose() {
        disposed = true;
        cache.clear();
        pending.clear();
        resolvedBefore.clear();
    }

    private static Result inert(ManagedSessionId id) {
        return new Result(id, 0L, SessionPaneFacts.loading());
    }

    /**
     * Observes readiness for the request, outside the monitor, with a
     * non-async callback (it may run here or on the completing thread). A
     * listener exception raised by a callback that ran synchronously on this
     * thread is rethrown here, as it was when the executor ran the load
     * directly; on any other thread it ends in the returned stage.
     */
    private void submit(Request request) {
        Thread caller = Thread.currentThread();
        RuntimeException[] thrown = new RuntimeException[1];
        boolean[] registering = {true};
        initialization.whenComplete((ignored, failure) -> {
            try {
                ready(request, failure);
            } catch (RuntimeException e) {
                if (registering[0] && Thread.currentThread() == caller) {
                    thrown[0] = e;
                } else {
                    throw e;
                }
            }
        });
        registering[0] = false;
        if (thrown[0] != null) {
            throw thrown[0];
        }
    }

    /** Readiness settled: memory checks only; a failed readiness is reported, a successful one is handed to the executor. */
    private void ready(Request request, Throwable failure) {
        synchronized (this) {
            if (disposed || pending.get(request.id) != request || request.terminal != null) {
                return; // inactive, superseded or already settled
            }
        }
        if (failure != null) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            complete(request, Optional.empty(), Optional.of(message(cause, cause.getClass().getName())));
            return;
        }
        try {
            executor.execute(() -> load(request));
        } catch (RuntimeException e) {
            // Shutdown drained the executor (typically RejectedExecutionException): say
            // so rather than leave the pane loading, and forget the request so
            // a retry can start. A listener exception from a synchronous
            // executor is not a submission failure: complete() refuses to
            // settle twice, so it is rethrown rather than swallowed or notified again.
            if (!complete(request, Optional.empty(), Optional.of(message(e, "the session loader is shutting down"))) && isSettled(request)) {
                throw e;
            }
        }
    }

    private void load(Request request) {
        synchronized (this) {
            if (disposed || pending.get(request.id) != request) {
                return; // disposed or superseded while queued: skip the probes
            }
        }
        Optional<SessionPaneFacts.Inputs> inputs = Optional.empty();
        Optional<String> failure = Optional.empty();
        try {
            inputs = probes.probe(request.id);
        } catch (Exception | LinkageError e) {
            failure = Optional.of(message(e, e.getClass().getName()));
        }
        complete(request, inputs, failure);
    }

    private static String message(Throwable t, String fallback) {
        String m = t.getMessage();
        return m != null && !m.isBlank() ? m : fallback;
    }

    private synchronized boolean isSettled(Request request) {
        return request.terminal != null;
    }

    /**
     * The one terminal gate, for every outcome: success, absent from the
     * catalog, probe failure, refused submission. Accepts it only if the
     * loader is live, the request is still current and it has not settled;
     * then records the answer, clears the pending entry, updates the cache,
     * and notifies the listener once, outside the monitor.
     *
     * @return whether this outcome was accepted
     */
    private boolean complete(Request request, Optional<SessionPaneFacts.Inputs> inputs, Optional<String> failure) {
        Result result;
        synchronized (this) {
            if (disposed || pending.get(request.id) != request || request.terminal != null) {
                return false; // disposed, an older request finishing late, or already settled
            }
            SessionPaneFacts.State state = SessionPaneFacts.resolveState(
                    true, failure, inputs.isPresent(), resolvedBefore.contains(request.id));
            SessionPaneFacts facts = switch (state) {
                case KNOWN -> SessionPaneFacts.known(inputs.get());
                case FAILED -> SessionPaneFacts.failed(failure.get());
                case DELETED -> SessionPaneFacts.deleted();
                default -> SessionPaneFacts.unknown();
            };
            result = new Result(request.id, request.generation, facts);
            request.terminal = result;
            pending.remove(request.id);
            if (state == SessionPaneFacts.State.FAILED) {
                cache.remove(request.id);
            } else {
                cache.put(request.id, result);
                if (state == SessionPaneFacts.State.KNOWN) {
                    resolvedBefore.add(request.id);
                }
            }
        }
        listener.accept(result);
        return true;
    }
}
