package app.drydock.intellij.terminal;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;

import java.util.Optional;

/**
 * Consumer of the session lifecycle events the {@link SessionLifecycleMonitor}
 * derives from terminal tabs (task T8). Deliberately free of IDE types so the
 * session service (persistence, T10) and the tool window list model (display,
 * T12) can subscribe separately without either tier depending on the other.
 */
public interface SessionEventSink {

    /**
     * A session's terminal tab went away. {@code tabClosed == false}: the
     * agent process ended on its own (dot EXITED). {@code tabClosed == true}:
     * the user closed the tab while the process was still running (dot
     * INACTIVE; the IDE's own tab close terminates the process, the monitor
     * never kills anything itself). A tab whose natural exit was already
     * reported does not get a second, closed-variant event — its EXITED
     * record must not be rewritten.
     */
    void sessionExited(ManagedSessionId id, boolean tabClosed);

    /**
     * A session's activity changed. The optional is empty when the session's
     * agent has no persisted agent session id (Pi before discovery, TERMINAL,
     * or any kind without an activity reporter) or reported no state file — a
     * lifecycle-only dot, never a fabricated BUSY/IDLE.
     */
    void sessionActivityChanged(ManagedSessionId id, Optional<SessionActivity> activity);

    /** The user focused the session's terminal tab (drives the watcher's acknowledge). */
    void tabFocused(ManagedSessionId id);
}
