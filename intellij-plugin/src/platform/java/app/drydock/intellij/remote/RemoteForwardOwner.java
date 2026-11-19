package app.drydock.intellij.remote;

import app.drydock.process.ProcessRunner;
import app.drydock.process.SshCommandBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Application-scoped owner of the managed reverse SSH forwards: one isolated forward handle per managed
 * remote session (never shared or reference-counted), each on its own remote port. {@link #prepare} blocks
 * (call it off the FX thread) and only returns a {@link Prepared} after an authenticated initialize round
 * trip succeeded over the real forward; spawn success alone is never readiness. Every partial preparation,
 * cancel and owner-close race closes the handle (which joins its readers and timers) exactly once.
 */
public final class RemoteForwardOwner implements AutoCloseable {

    public static final Duration READINESS_BUDGET = Duration.ofSeconds(15);
    public static final int MAX_BIND_ATTEMPTS = 3;
    static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    /**
     * The lifecycle slice of a managed forward. ProcessRunner.ManagedForward is final with a package-private
     * starter, so it cannot be faked from this package; production adapts it in {@link #sshStarter()}.
     */
    public interface ForwardHandle extends AutoCloseable {
        CompletableFuture<Integer> exit();

        @Override
        void close();
    }

    /** Spawns the forward for an argv built by {@link SshCommandBuilder#reverseForwardCommand}. */
    @FunctionalInterface
    public interface ForwardStarter {
        ForwardHandle start(List<String> argv) throws IOException;
    }

    /** Picks a candidate remote loopback port on {@code host}, avoiding {@code exclude}. */
    @FunctionalInterface
    public interface PortProbe {
        int pick(String host, Set<Integer> exclude) throws IOException;
    }

    /** One authenticated initialize attempt through the real forward; true only on a valid response. */
    @FunctionalInterface
    public interface ReadinessProbe {
        boolean initialize(String sessionId, String host, int remotePort) throws IOException;
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** Preparation failed; nothing is left open. */
    public static final class ForwardException extends Exception {
        public ForwardException(String message) {
            super(message);
        }
    }

    /** A published, ready forward generation. */
    public final class Prepared implements AutoCloseable {
        private final String sessionId;
        private final String host;
        private final int remotePort;
        private final long generation;
        private final ForwardHandle handle;
        private volatile boolean ready = true;
        private volatile Runnable invalidated = () -> { };

        private Prepared(String sessionId, String host, int remotePort, long generation, ForwardHandle handle) {
            this.sessionId = sessionId;
            this.host = host;
            this.remotePort = remotePort;
            this.generation = generation;
            this.handle = handle;
        }

        public int remotePort() {
            return remotePort;
        }

        public long generation() {
            return generation;
        }

        public boolean isReady() {
            return ready;
        }

        /** Runs once when exit/keepalive failure (not an owner-initiated close) invalidates readiness. */
        public void onInvalidated(Runnable listener) {
            invalidated = listener;
            if (!ready) {
                listener.run();
            }
        }

        @Override
        public void close() {
            release(this, false);
        }
    }

    private final ForwardStarter starter;
    private final PortProbe portProbe;
    private final ReadinessProbe readiness;
    private final LongSupplier nanoClock;
    private final Sleeper sleeper;
    private final AtomicLong generations = new AtomicLong();
    private final Object lock = new Object();
    private final Map<String, Prepared> active = new HashMap<>();
    private final Map<ForwardHandle, Boolean> inFlight = new IdentityHashMap<>();
    private final Map<String, Set<Integer>> reservedPorts = new HashMap<>();
    private boolean closed;

    public RemoteForwardOwner(ForwardStarter starter, PortProbe portProbe, ReadinessProbe readiness,
                              LongSupplier nanoClock, Sleeper sleeper) {
        this.starter = starter;
        this.portProbe = portProbe;
        this.readiness = readiness;
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
    }

    /** Production starter: the narrow ProcessRunner lifecycle API, argv and pipes only. */
    public static ForwardStarter sshStarter() {
        return argv -> {
            ProcessRunner.ManagedForward forward =
                    ProcessRunner.startSshForward(argv, new ProcessRunner.ForwardOptions(null, Map.of()));
            return new ForwardHandle() {
                @Override
                public CompletableFuture<Integer> exit() {
                    return forward.exit();
                }

                @Override
                public void close() {
                    forward.close();
                }
            };
        };
    }

    /**
     * Brings up a forward for {@code sessionId} and proves it works. A previous forward of the same session
     * is replaced (new generation). Throws {@link ForwardException} after closing everything it opened.
     */
    public Prepared prepare(String sessionId, String host, int idePort, BooleanSupplier cancelled)
            throws ForwardException, InterruptedException {
        long deadline = nanoClock.getAsLong() + READINESS_BUDGET.toNanos();
        Prepared previous;
        synchronized (lock) {
            previous = active.remove(sessionId);
        }
        if (previous != null) {
            release(previous, true);
        }
        String failure = "forward not ready";
        for (int attempt = 1; attempt <= MAX_BIND_ATTEMPTS; attempt++) {
            int port = reserve(host);
            ForwardHandle handle = null;
            boolean published = false;
            try {
                handle = starter.start(SshCommandBuilder.reverseForwardCommand(host, port, idePort));
                track(handle);
                String result = awaitReady(sessionId, host, port, handle, deadline, cancelled);
                if (result == null) {
                    Prepared prepared = publish(sessionId, host, port, handle);
                    published = true;
                    return prepared;
                }
                failure = result;
                if (result.startsWith("timeout") || result.startsWith("cancelled")) {
                    throw new ForwardException(result);
                }
            } catch (IOException e) {
                failure = "forward could not start";
            } finally {
                if (!published) {
                    untrackAndClose(handle);
                    unreserve(host, port);
                }
            }
        }
        throw new ForwardException(failure);
    }

    /** Null when ready; otherwise why not. An early exit is a (retryable) bind failure. */
    private String awaitReady(String sessionId, String host, int port, ForwardHandle handle, long deadline,
                              BooleanSupplier cancelled) throws InterruptedException {
        while (true) {
            if (cancelled.getAsBoolean() || isClosed()) {
                return "cancelled";
            }
            if (handle.exit().isDone()) {
                return "forward exited before readiness";
            }
            try {
                if (readiness.initialize(sessionId, host, port)) {
                    return handle.exit().isDone() ? "forward exited before readiness" : null;
                }
            } catch (IOException e) {
                // not yet reachable through the forward; keep polling until the budget ends
            }
            if (nanoClock.getAsLong() - deadline >= 0) {
                return "timeout waiting for authenticated initialize";
            }
            sleeper.sleep(POLL_INTERVAL);
        }
    }

    private int reserve(String host) throws ForwardException {
        synchronized (lock) {
            if (closed) {
                throw new ForwardException("cancelled: owner closed");
            }
            Set<Integer> used = reservedPorts.computeIfAbsent(host, h -> new HashSet<>());
            int port;
            try {
                port = portProbe.pick(host, Set.copyOf(used));
            } catch (IOException e) {
                throw new ForwardException("remote port probe failed");
            }
            if (!used.add(port)) {
                throw new ForwardException("remote port probe returned a reserved port");
            }
            return port;
        }
    }

    private void unreserve(String host, int port) {
        synchronized (lock) {
            Set<Integer> used = reservedPorts.get(host);
            if (used != null) {
                used.remove(port);
            }
        }
    }

    private void track(ForwardHandle handle) {
        synchronized (lock) {
            inFlight.put(handle, Boolean.TRUE);
        }
    }

    private void untrackAndClose(ForwardHandle handle) {
        if (handle != null) {
            synchronized (lock) {
                inFlight.remove(handle);
            }
            handle.close();
        }
    }

    private boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    private Prepared publish(String sessionId, String host, int port, ForwardHandle handle)
            throws ForwardException {
        Prepared prepared;
        synchronized (lock) {
            if (closed) {
                throw new ForwardException("cancelled: owner closed");
            }
            inFlight.remove(handle);
            prepared = new Prepared(sessionId, host, port, generations.incrementAndGet(), handle);
            active.put(sessionId, prepared);
        }
        handle.exit().whenComplete((code, failure) -> release(prepared, false, true));
        return prepared;
    }

    private void release(Prepared prepared, boolean ownerInitiated) {
        release(prepared, ownerInitiated, false);
    }

    private void release(Prepared prepared, boolean ownerInitiated, boolean fromExit) {
        boolean wasCurrent;
        synchronized (lock) {
            wasCurrent = active.remove(prepared.sessionId, prepared);
            Set<Integer> used = reservedPorts.get(prepared.host);
            if (used != null) {
                used.remove(prepared.remotePort);
            }
        }
        boolean wasReady = prepared.ready;
        prepared.ready = false;
        prepared.handle.close();
        if (fromExit && wasReady && wasCurrent) {
            prepared.invalidated.run();
        }
    }

    /** Number of live forward handles (published plus still preparing); for tests and diagnostics. */
    public int handleCount() {
        synchronized (lock) {
            return active.size() + inFlight.size();
        }
    }

    /** Settles every forward, published or in flight; later {@link #prepare} calls fail. Idempotent. */
    @Override
    public void close() {
        List<Prepared> prepared;
        List<ForwardHandle> pending;
        synchronized (lock) {
            closed = true;
            prepared = new ArrayList<>(active.values());
            pending = new ArrayList<>(inFlight.keySet());
        }
        for (Prepared p : prepared) {
            release(p, true);
        }
        for (ForwardHandle h : pending) {
            h.close();
        }
    }
}
