package app.drydock.intellij.remote;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SshRemote;
import app.drydock.intellij.remote.RemoteCredentialGenerations.Credential;
import app.drydock.intellij.remote.RemoteCredentialGenerations.Delivery;
import app.drydock.intellij.remote.RemoteCredentialGenerations.Request;
import app.drydock.intellij.remote.RemoteForwardOwner.ForwardException;
import app.drydock.intellij.remote.RemoteReviewService.Ready;
import app.drydock.intellij.remote.RemoteReviewService.ReleaseOutcome;
import app.drydock.intellij.remote.RemoteReviewService.Session;
import app.drydock.intellij.remote.RemoteReviewService.Wiring;
import app.drydock.intellij.review.ReviewBackend;
import app.drydock.git.DiffScope;
import app.drydock.git.ReviewBase;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.RemoteReviewDataSource;
import app.drydock.review.ReviewDataSnapshot;
import app.drydock.review.ReviewDataSource;
import app.drydock.review.RemoteSnapshotCache;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.workspace.RemoteEnrollmentEvidence.Reason;
import app.drydock.workspace.RemoteEnrollmentEvidence.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ownership of the remote transport over the real forward owner, generations, backend and data source. */
class RemoteReviewServiceTest {

    private static final Verdict READY = new Verdict.Ready();

    static final class Handle implements RemoteForwardOwner.ForwardHandle {
        final CompletableFuture<Integer> exit = new CompletableFuture<>();
        final AtomicInteger closes = new AtomicInteger();
        final List<String> argv;
        Handle(List<String> argv) { this.argv = argv; }
        public CompletableFuture<Integer> exit() { return exit; }
        public void close() { closes.incrementAndGet(); exit.complete(143); }
    }

    @TempDir
    Path base;

    private final List<Handle> handles = new ArrayList<>();
    private volatile Runnable duringProbe = () -> { };
    private volatile boolean deliverOk = true;
    private long now;
    private ExecutorService executor;
    private ReviewBackend backend;
    private RemoteSnapshotCache cache;
    private RemoteForwardOwner forwards;
    private RemoteCredentialGenerations generations;
    private RemoteReviewService service;

    @BeforeEach
    void setUp() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        AgentRegistry registry = new AgentRegistry(List.of(), new AgentContext(base, base.resolve("activity"), executor));
        backend = new ReviewBackend(base, List::of, List::of, registry);
        cache = new RemoteSnapshotCache();
        int[] nextPort = {40000};
        forwards = new RemoteForwardOwner(argv -> {
            Handle h = new Handle(argv);
            handles.add(h);
            return h;
        }, (host, exclude) -> {
            while (exclude.contains(nextPort[0])) {
                nextPort[0]++;
            }
            return nextPort[0]++;
        }, (id, host, port) -> {
            duringProbe.run();
            return true;
        }, () -> now, d -> now += d.toNanos());
        generations = new RemoteCredentialGenerations(new Delivery() {
            public boolean deliver(Credential c, BooleanSupplier cancelled) throws IOException {
                return deliverOk;
            }
            public void withdraw(Request r, boolean published) { }
        }, Clock.systemUTC());
        RemoteReviewDataSource data = new RemoteReviewDataSource(new SshRemote("box", "/repo"),
                (argv, options, cap) -> { throw new IOException("no box"); }, new ReviewScopeRegistry(), cache,
                new RemoteReviewDataSource.Helpers("git.py", "data.py"), "checkout-1", 1, Runnable::run);
        service = new RemoteReviewService(backend, new Wiring(List::of, List::of, registry,
                (id, title, permit) -> CompletableFuture.failedFuture(new IllegalStateException("unused"))),
                data, cache, forwards, generations);
        service.start();
        this.data = data;
    }

    private RemoteReviewDataSource data;

    @AfterEach
    void tearDown() {
        service.close();
        backend.close();
        executor.shutdownNow();
    }

    private static Session session(String host) {
        return new Session(host, "inst1", ManagedSessionId.newId(), "agent-1", "nonce-1", 1, "/h/helper.py",
                () -> READY);
    }

    private long liveHandles() {
        return handles.stream().filter(h -> h.closes.get() == 0).count();
    }

    @Test
    void ensureYieldsLiveForwardWithCurrentGenerationCredentialAndSharedData() throws Exception {
        Session s = session("box1");
        Ready r = service.ensure(s);
        assertEquals(1, liveHandles());
        assertEquals(1, forwards.handleCount());
        assertTrue(handles.get(0).argv.contains("127.0.0.1:" + r.remotePort() + ":127.0.0.1:"
                + service.composition().server().port()), "forward targets the remote endpoint, not the local one");
        assertEquals(1, generations.liveTokenCount());
        assertEquals(generations.permitFor(s.id()).orElseThrow().generation(), r.generation());
        assertSame(data, r.dataSource());
        assertSame(cache, r.snapshots());
    }

    @Test
    void closeDuringPreparationLeavesNoHandleOrToken() {
        duringProbe = () -> service.close();
        assertThrows(ForwardException.class, () -> service.ensure(session("box1")));
        assertEquals(0, liveHandles());
        assertEquals(0, generations.liveTokenCount());
    }

    @Test
    void unpublishedCredentialClosesTheForward() {
        deliverOk = false;
        assertThrows(ForwardException.class, () -> service.ensure(session("box1")));
        assertEquals(0, liveHandles());
        assertEquals(0, generations.liveTokenCount());
        Session notReady = new Session("box1", "inst1", ManagedSessionId.newId(), "agent-1", "nonce-1", 1, "/h",
                () -> new Verdict.Unavailable(Reason.PROCESS_GONE, "x"));
        deliverOk = true;
        assertThrows(ForwardException.class, () -> service.ensure(notReady));
        assertEquals(0, liveHandles());
    }

    private static ReviewDataSnapshot snapshot() {
        return new ReviewDataSnapshot("scope", ReviewDataSource.Backend.REMOTE, "co", 1, DiffScope.BASE,
                new ReviewBase("main", ReviewBase.Origin.PULL_REQUEST), "b", "h", "b", "fp",
                new UnifiedDiff(List.of()), Map.of("p", new ReviewDataSnapshot.SourceFile(
                        new ReviewDataSnapshot.Content.Absent(), new ReviewDataSnapshot.Content.Text(new byte[100]))),
                Instant.EPOCH);
    }

    @Test
    void detachKeepsForwardAndCredentialAndReuseDoesNotRepublish() throws Exception {
        Session s = session("box1");
        Ready first = service.ensure(s);
        service.release(s, ReleaseOutcome.DETACH);
        assertEquals(1, liveHandles());
        assertEquals(1, generations.liveTokenCount());
        assertEquals(first.generation(), service.ensure(s).generation());
        assertEquals(1, handles.size());
    }

    @Test
    void deathStopForgetCloseForwardRevokeAndDropCache() throws Exception {
        for (ReleaseOutcome o : List.of(ReleaseOutcome.DEATH, ReleaseOutcome.STOP, ReleaseOutcome.FORGET)) {
            Session s = session("box1");
            service.ensure(s);
            long before = liveHandles();
            cache.put(s.id().toString(), snapshot());
            service.release(s, o);
            assertEquals(before - 1, liveHandles(), o + " closes the forward");
            assertTrue(generations.permitFor(s.id()).isEmpty(), o + " revokes the generation");
            assertEquals(0, cache.totalBytes(), o + " drops the session cache");
        }
        assertEquals(0, generations.liveTokenCount());
    }

    @Test
    void releaseDuringPreparationClosesTheForward() {
        Session s = session("box1");
        duringProbe = () -> service.release(s, ReleaseOutcome.STOP);
        assertThrows(ForwardException.class, () -> service.ensure(s));
        assertEquals(0, liveHandles());
        assertEquals(0, generations.liveTokenCount());
    }

    @Test
    void closeSettlesEverySession() throws Exception {
        service.ensure(session("box1"));
        service.ensure(session("box2"));
        service.close();
        assertEquals(0, liveHandles());
        assertEquals(0, generations.liveTokenCount());
    }
}
