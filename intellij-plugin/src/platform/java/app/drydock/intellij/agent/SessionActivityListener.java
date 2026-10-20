package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;

import com.intellij.util.messages.Topic;

import java.util.Optional;

/**
 * Application-level notification that a session's live activity changed —
 * working, at the prompt, or waiting for a human.
 *
 * <p>Application-scoped, not project-scoped: a session that needs attention
 * turns the Agents widget amber in <em>every</em> window, and the Welcome
 * screen shows the same rows with no window at all. Scoping this per project
 * would mean the signal never reaches either.</p>
 *
 * <p>The activity is an {@link Optional} rather than a defaulted value: an
 * agent with no activity reporter, or one whose first report has not landed,
 * genuinely has no activity, and that is different from being idle. The UI
 * must be able to tell the two apart, so the emptiness travels.</p>
 */
public interface SessionActivityListener {

    Topic<SessionActivityListener> TOPIC =
            Topic.create("Drydock session activity", SessionActivityListener.class);

    void activityChanged(ManagedSessionId sessionId, Optional<SessionActivity> activity);
}
