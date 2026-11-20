package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.mcp.RemoteAuthorizationPermit.Bound;
import app.drydock.mcp.RemoteAuthorizationPermit.Fence;
import app.drydock.mcp.RemoteAuthorizationPermit.StaleAuthorizationException;
import org.junit.jupiter.api.Test;

import app.drydock.mcp.McpSessionRegistry.Spawn;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteAuthorizationPermitTest {

    /** An owner-style fence: one lock, revocation marks the flag under that lock alone. */
    private static final class TestFence implements Fence {
        private final Object lock = new Object();
        private boolean revoked;

        @Override public Object lock() { return lock; }
        @Override public boolean revoked() { return revoked; }

        void revived() {
            synchronized (lock) {
                revoked = false;
            }
        }

        void revoke() {
            synchronized (lock) {
                revoked = true;
            }
        }
    }

    private final ManagedSessionId session = ManagedSessionId.newId();
    private final TestFence fence = new TestFence();
    private final RemoteAuthorizationPermit permit = new RemoteAuthorizationPermit(session, 1, fence);

    @Test
    void staleGenerationPermitIsNotCurrentSoDispatchCanRefuseIt() {
        assertTrue(permit.isCurrent());
        assertEquals(1, permit.generation());

        fence.revoke();

        assertFalse(permit.isCurrent());
    }

    @Test
    void revokedPermitRefusesCommit() {
        AtomicInteger ran = new AtomicInteger();
        assertEquals("ok", permit.commitIfCurrent(() -> {
            ran.incrementAndGet();
            return "ok";
        }));

        fence.revoke();

        assertThrows(StaleAuthorizationException.class, () -> permit.commitIfCurrent(() -> ran.incrementAndGet()));
        assertEquals(1, ran.get(), "the supplier must not run once revoked");
    }

    @Test
    void commitRequiresTheAuthenticatedSession() {
        AtomicInteger ran = new AtomicInteger();
        assertThrows(StaleAuthorizationException.class,
                () -> permit.commitIfCurrent(ManagedSessionId.newId(), () -> ran.incrementAndGet()));
        assertEquals(0, ran.get());
        assertEquals(1, permit.commitIfCurrent(session, () -> ran.incrementAndGet()));
    }

    @Test
    void commitHoldsTheFenceLockThroughTheSupplier() {
        assertTrue(permit.commitIfCurrent(() -> Thread.holdsLock(fence.lock())));
    }

    @Test
    void boundIsScopedAndRestoresThePreviousPermit() {
        RemoteAuthorizationPermit inner = new RemoteAuthorizationPermit(session, 2, fence);
        assertTrue(Bound.current().isEmpty());
        try (Bound outer = Bound.bind(permit)) {
            try (Bound nested = Bound.bind(inner)) {
                assertSame(inner, Bound.current().orElseThrow());
            }
            assertSame(permit, Bound.current().orElseThrow());
        }
        assertTrue(Bound.current().isEmpty());
        assertEquals(null, RemoteAuthorizationPermit.Seams.NONE.permits());
    }

    // ---- McpServer integration ----

    private static final String INIT = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""";
    private static final String RECLAIM = """
            {"jsonrpc":"2.0","id":2,"method":"tools/call",
             "params":{"name":"session_reclaim","arguments":{"agentSessionId":"c"}}}""";
    private static final String PING = """
            {"jsonrpc":"2.0","id":3,"method":"ping","params":{}}""";

    private record Reply(int status, String body) {}

    /** A server over a real registry; the context records the permit bound while each delegate call runs. */
    private final class Harness implements AutoCloseable {
        final McpSessionRegistry registry = new McpSessionRegistry();
        final String token = registry.mint(session, Spawn.ALLOWED);
        final List<Optional<RemoteAuthorizationPermit>> seenByContext = new CopyOnWriteArrayList<>();
        final McpServer server;
        final HttpClient client = HttpClient.newHttpClient();

        Harness(RemoteMcpTransportPolicy policy, RemoteAuthorizationPermit.Seams seams, boolean twoArg) {
            FakeMcpSessionContext fake = new FakeMcpSessionContext();
            McpSessionContext spy = (McpSessionContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {McpSessionContext.class}, (proxy, method, args) -> {
                        seenByContext.add(Bound.current());
                        try {
                            return method.invoke(fake, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            McpToolRouter router = new McpToolRouter(spy, registry);
            server = twoArg ? new McpServer(registry, router, null)
                    : new McpServer(registry, router, null, policy, seams);
            try {
                server.start();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        Reply post(String body, String... headers) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server.endpointUrl()))
                    .header("X-Drydock-Session-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            for (int i = 0; i < headers.length; i += 2) {
                b.header(headers[i], headers[i + 1]);
            }
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(r.statusCode(), r.body());
        }

        @Override public void close() { server.close(); }
    }

    private RemoteAuthorizationPermit.Seams seams(RemoteAuthorizationPermit.InitializeObserver observer) {
        return new RemoteAuthorizationPermit.Seams(id -> Optional.of(permit), observer);
    }

    @Test
    void staleGenerationRequestIsRefusedBeforeDispatch() throws Exception {
        for (RemoteMcpTransportPolicy policy : List.of(RemoteMcpTransportPolicy.LOCAL, RemoteMcpTransportPolicy.REMOTE)) {
            List<String> observed = new ArrayList<>();
            try (Harness h = new Harness(policy, seams((id, n, p) -> observed.add(n)), false)) {
                fence.revoke();
                assertEquals(401, h.post(RECLAIM, "X-Drydock-Launch-Nonce", "n").status());
                assertEquals(401, h.post(INIT, "X-Drydock-Launch-Nonce", "n").status());
                assertTrue(h.seenByContext.isEmpty(), "no context delegate may run for a stale permit");
                assertTrue(observed.isEmpty());
            }
            fence.revived();
        }
        // A source that yields no permit, or a permit of another session, is refused too.
        RemoteAuthorizationPermit foreign = new RemoteAuthorizationPermit(ManagedSessionId.newId(), 1, new TestFence());
        for (Optional<RemoteAuthorizationPermit> given : List.of(Optional.<RemoteAuthorizationPermit>empty(), Optional.of(foreign))) {
            try (Harness h = new Harness(RemoteMcpTransportPolicy.LOCAL,
                    new RemoteAuthorizationPermit.Seams(id -> given, null), false)) {
                assertEquals(401, h.post(RECLAIM).status());
                assertTrue(h.seenByContext.isEmpty());
            }
        }
    }

    @Test
    void boundPermitIsVisibleToContextDelegatesAndClearedAfterwards() throws Exception {
        for (RemoteMcpTransportPolicy policy : List.of(RemoteMcpTransportPolicy.LOCAL, RemoteMcpTransportPolicy.REMOTE)) {
            try (Harness h = new Harness(policy, seams(null), false)) {
                assertEquals(200, h.post(RECLAIM).status());
                assertFalse(h.seenByContext.isEmpty());
                h.seenByContext.forEach(seen -> assertSame(permit, seen.orElseThrow()));
                assertTrue(Bound.current().isEmpty(), "the binding must not leak to other threads");
            }
        }
    }

    @Test
    void observerFiresOnlyForAuthenticatedInitializeWithLaunchHeaders() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        try (Harness h = new Harness(RemoteMcpTransportPolicy.REMOTE,
                seams((id, nonce, pid) -> calls.add(id + "|" + nonce + "|" + pid)), false)) {
            h.post(PING, "X-Drydock-Launch-Nonce", "x");
            h.post(RECLAIM);
            assertTrue(calls.isEmpty(), "only initialize may notify: " + calls);
            h.post(INIT, "X-Drydock-Launch-Nonce", "nonce1", "X-Drydock-Bridge-Pid", "4242");
            h.post(INIT);
            assertEquals(List.of(session + "|nonce1|4242", session + "|null|null"), calls);
            // Unauthenticated initialize never reaches the observer.
            HttpResponse<String> r = h.client.send(HttpRequest.newBuilder(URI.create(h.server.endpointUrl()))
                    .header("X-Drydock-Session-Token", "wrong")
                    .POST(HttpRequest.BodyPublishers.ofString(INIT)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, r.statusCode());
            assertEquals(2, calls.size());
        }
    }

    @Test
    void observerFailureDoesNotFailInitialize() throws Exception {
        try (Harness h = new Harness(RemoteMcpTransportPolicy.LOCAL,
                seams((id, n, p) -> { throw new IllegalStateException("secret-nonce"); }), false)) {
            Reply r = h.post(INIT, "X-Drydock-Launch-Nonce", "secret-nonce");
            assertEquals(200, r.status());
            assertTrue(r.body().contains("protocolVersion"), r.body());
        }
    }

    @Test
    void noSeamsLeavesTheLocalEndpointUnchanged() throws Exception {
        try (Harness h = new Harness(null, null, true)) {
            assertEquals(200, h.post(INIT, "X-Drydock-Launch-Nonce", "n").status());
            assertEquals(200, h.post(RECLAIM).status());
            assertFalse(h.seenByContext.isEmpty());
            h.seenByContext.forEach(seen -> assertTrue(seen.isEmpty()));
        }
    }
}
