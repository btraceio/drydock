package app.drydock.intellij.terminal;

import app.drydock.domain.ManagedSessionId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Registry of live terminal tabs: which managed session owns which tab. Pure
 * bookkeeping — no IntelliJ type appears in its API, so it is fully
 * unit-testable without an IDE.
 *
 * <p>Keys are the tabs' opaque identity ({@link TerminalSessionLauncher.TerminalSessionTab#tabKey()});
 * the IDE tab object itself serves as the key for real tabs, so code holding
 * an IDE tab (the restored-tab guard, content-manager listeners) can look the
 * handle up without going through the handle first.</p>
 *
 * <p>Thread-safe. Mutations are serialized on an internal lock because a
 * register/remove must update two indexes (by key, by session) as one step —
 * concurrent map sweeping could otherwise leave a session index entry whose
 * key entry was already removed. Registry traffic is one burst per session
 * open/close, so a coarse lock costs nothing.</p>
 */
public final class SessionTabRegistry {

    private final Map<Object, TerminalSessionLauncher.TerminalSessionTab> tabsByKey = new HashMap<>();
    private final Map<ManagedSessionId, Set<TerminalSessionLauncher.TerminalSessionTab>> tabsBySession =
            new HashMap<>();
    private final Object lock = new Object();

    /**
     * Registers a tab. Re-registering under a key already present replaces
     * the previous handle (both indexes drop it) — a re-opened tab must not
     * leave its predecessor behind.
     */
    public void register(TerminalSessionLauncher.TerminalSessionTab tab) {
        Objects.requireNonNull(tab, "tab");
        synchronized (lock) {
            TerminalSessionLauncher.TerminalSessionTab previous = tabsByKey.put(tab.tabKey(), tab);
            if (previous != null) {
                removeFromSessionIndex(previous);
            }
            tabsBySession.computeIfAbsent(tab.sessionId(), k -> new HashSet<>()).add(tab);
        }
    }

    /** The handle for a tab key, or {@code null} when not registered. */
    public TerminalSessionLauncher.TerminalSessionTab lookup(Object tabKey) {
        Objects.requireNonNull(tabKey, "tabKey");
        synchronized (lock) {
            return tabsByKey.get(tabKey);
        }
    }

    /**
     * Removes the handle for a tab key from both indexes. Returns the removed
     * handle, or {@code null} when nothing was registered under the key.
     */
    public TerminalSessionLauncher.TerminalSessionTab remove(Object tabKey) {
        Objects.requireNonNull(tabKey, "tabKey");
        synchronized (lock) {
            TerminalSessionLauncher.TerminalSessionTab tab = tabsByKey.remove(tabKey);
            if (tab == null) {
                return null;
            }
            removeFromSessionIndex(tab);
            return tab;
        }
    }

    /** All registered tabs of one session (a resumed session can have more). */
    public List<TerminalSessionLauncher.TerminalSessionTab> tabsFor(ManagedSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        synchronized (lock) {
            return List.copyOf(tabsBySession.getOrDefault(sessionId, Set.of()));
        }
    }

    /** Snapshot of every registered tab, in no particular order. */
    public List<TerminalSessionLauncher.TerminalSessionTab> tabs() {
        synchronized (lock) {
            return List.copyOf(tabsByKey.values());
        }
    }

    /** Callers hold {@code lock}. */
    private void removeFromSessionIndex(TerminalSessionLauncher.TerminalSessionTab tab) {
        Set<TerminalSessionLauncher.TerminalSessionTab> sessionTabs = tabsBySession.get(tab.sessionId());
        if (sessionTabs == null) {
            return;
        }
        sessionTabs.remove(tab);
        if (sessionTabs.isEmpty()) {
            tabsBySession.remove(tab.sessionId());
        }
    }
}
