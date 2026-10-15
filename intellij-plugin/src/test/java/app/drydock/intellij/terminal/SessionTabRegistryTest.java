package app.drydock.intellij.terminal;

import app.drydock.domain.ManagedSessionId;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTabRegistryTest {

    /**
     * Identity-semantics fake: two handles must stay distinct even when they
     * carry the same session and key, so the registry's replace behavior is
     * observable. The IDE tab object is never dereferenced (the registry keys
     * on the opaque {@code tabKey}), so {@code tab()} returns null here.
     */
    private static final class FakeTab implements TerminalSessionLauncher.TerminalSessionTab {

        private final ManagedSessionId sessionId;
        private final Object tabKey;

        FakeTab(ManagedSessionId sessionId, Object tabKey) {
            this.sessionId = sessionId;
            this.tabKey = tabKey;
        }

        @Override
        public ManagedSessionId sessionId() {
            return sessionId;
        }

        @Override
        public Object tabKey() {
            return tabKey;
        }

        @Override
        public TerminalToolWindowTab tab() {
            return null;
        }

        @Override
        public void close() {
            // no-op
        }
    }

    @Test
    void registerThenLookupByKeyFindsTheHandle() {
        SessionTabRegistry registry = new SessionTabRegistry();
        ManagedSessionId session = ManagedSessionId.newId();
        FakeTab tab = new FakeTab(session, "tab-key-1");

        registry.register(tab);

        assertSame(tab, registry.lookup("tab-key-1"));
        assertNull(registry.lookup("never-registered"));
        assertEquals(List.of(tab), registry.tabsFor(session));
    }

    @Test
    void removeUnregistersBothIndexes() {
        SessionTabRegistry registry = new SessionTabRegistry();
        ManagedSessionId session = ManagedSessionId.newId();
        FakeTab tab = new FakeTab(session, "tab-key-1");
        registry.register(tab);

        assertSame(tab, registry.remove("tab-key-1"));
        assertNull(registry.lookup("tab-key-1"));
        assertTrue(registry.tabsFor(session).isEmpty(), "session view must drop with the last tab");
        assertTrue(registry.tabs().isEmpty());
        // Removing an absent key is a no-op, not an error.
        assertNull(registry.remove("tab-key-1"));
    }

    @Test
    void tabsForReturnsEveryTabOfThatSessionOnly() {
        SessionTabRegistry registry = new SessionTabRegistry();
        ManagedSessionId session = ManagedSessionId.newId();
        ManagedSessionId otherSession = ManagedSessionId.newId();
        FakeTab first = new FakeTab(session, "tab-1");
        FakeTab second = new FakeTab(session, "tab-2");
        FakeTab other = new FakeTab(otherSession, "tab-3");

        registry.register(first);
        registry.register(second);
        registry.register(other);

        assertEquals(2, registry.tabsFor(session).size());
        assertTrue(registry.tabsFor(session).contains(first));
        assertTrue(registry.tabsFor(session).contains(second));
        assertFalse(registry.tabsFor(session).contains(other));
        assertEquals(List.of(other), registry.tabsFor(otherSession));
        assertEquals(3, registry.tabs().size());
    }

    @Test
    void reRegisteringTheSameKeyReplacesThePreviousHandle() {
        SessionTabRegistry registry = new SessionTabRegistry();
        ManagedSessionId oldSession = ManagedSessionId.newId();
        ManagedSessionId newSession = ManagedSessionId.newId();
        FakeTab old = new FakeTab(oldSession, "tab-key-1");
        FakeTab reOpened = new FakeTab(newSession, "tab-key-1");

        registry.register(old);
        registry.register(reOpened);

        assertSame(reOpened, registry.lookup("tab-key-1"));
        assertTrue(registry.tabsFor(oldSession).isEmpty(),
                "the replaced handle must vanish from its session's view");
        assertEquals(List.of(reOpened), registry.tabsFor(newSession));
        // Removing the key drops only the current handle.
        assertSame(reOpened, registry.remove("tab-key-1"));
        assertTrue(registry.tabs().isEmpty());
    }

    @Test
    void returnedCollectionsAreSnapshots() {
        SessionTabRegistry registry = new SessionTabRegistry();
        ManagedSessionId session = ManagedSessionId.newId();
        registry.register(new FakeTab(session, "tab-key-1"));

        List<TerminalSessionLauncher.TerminalSessionTab> sessionTabs = registry.tabsFor(session);
        List<TerminalSessionLauncher.TerminalSessionTab> allTabs = registry.tabs();
        registry.remove("tab-key-1");

        assertEquals(1, sessionTabs.size(), "snapshot must not reflect later mutations");
        assertEquals(1, allTabs.size());
        assertTrue(registry.tabsFor(session).isEmpty());
        assertTrue(registry.tabs().isEmpty());
    }

    @Test
    void concurrentRegisterAndRemoveKeepsTheIndexesConsistent() throws Exception {
        SessionTabRegistry registry = new SessionTabRegistry();
        int sessions = 8;
        ManagedSessionId[] sessionIds = new ManagedSessionId[sessions];
        for (int i = 0; i < sessions; i++) {
            sessionIds[i] = ManagedSessionId.newId();
        }
        int keysPerSession = 8;
        int threads = 4;
        int iterations = 500;
        List<Throwable> failures = new ArrayList<>();
        AtomicInteger nextKey = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            CountDownLatch start = new CountDownLatch(1);
            for (int t = 0; t < threads; t++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            int slot = nextKey.getAndIncrement() % (sessions * keysPerSession);
                            int session = slot % sessions;
                            Object key = "tab-" + slot;
                            FakeTab tab = new FakeTab(sessionIds[session], key);
                            registry.register(tab);
                            // Remove a neighbor key — often the one another
                            // thread just registered, sometimes an absent one.
                            registry.remove("tab-" + ((slot + 1) % (sessions * keysPerSession)));
                        }
                    } catch (Throwable e) {
                        synchronized (failures) {
                            failures.add(e);
                        }
                    }
                });
            }
            start.countDown();
            // pool.close() on try-with-resources awaits termination.
        }
        assertTrue(failures.isEmpty(), "worker threads must not throw: " + failures);

        // Invariant: the two indexes describe exactly the same set of handles.
        List<TerminalSessionLauncher.TerminalSessionTab> allTabs = registry.tabs();
        for (TerminalSessionLauncher.TerminalSessionTab tab : allTabs) {
            assertSame(tab, registry.lookup(tab.tabKey()),
                    "every key entry must be present under its key");
            assertTrue(registry.tabsFor(tab.sessionId()).contains(tab),
                    "every key entry must be present in its session's view");
        }
        for (ManagedSessionId sessionId : sessionIds) {
            for (TerminalSessionLauncher.TerminalSessionTab tab : registry.tabsFor(sessionId)) {
                assertSame(tab, registry.lookup(tab.tabKey()),
                        "every session-view entry must be present under its key");
                assertSame(sessionId, tab.sessionId(),
                        "a session view must hold only its own session's tabs");
            }
        }
    }
}
