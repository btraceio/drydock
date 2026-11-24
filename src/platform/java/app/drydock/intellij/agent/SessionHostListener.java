package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.util.messages.Topic;

/**
 * A window's session host changed which session it intentionally shows, or
 * which of the shown panes holds the keyboard.
 *
 * <p>Project-scoped: each IDE window has its own host and its own selection,
 * so a subscriber on one project's bus never hears another window's choice.
 * The id travels explicitly rather than being re-read from the host, so a
 * subscriber sees the selection that triggered the event even if a later one
 * has already replaced it. Published on the EDT.</p>
 */
public interface SessionHostListener {

    Topic<SessionHostListener> TOPIC =
            Topic.create("Drydock session host", SessionHostListener.class, Topic.BroadcastDirection.NONE);

    /** {@code sessionId} is now the session this window's host shows. EDT. */
    default void sessionSelected(ManagedSessionId sessionId) {
    }

    /**
     * The pane of {@code sessionId} took keyboard focus. Published on every
     * accepted deliberate pane focus, even when the shown identity is
     * unchanged and {@link #sessionSelected} therefore stays silent. EDT.
     */
    default void sessionFocused(ManagedSessionId sessionId) {
    }
}
