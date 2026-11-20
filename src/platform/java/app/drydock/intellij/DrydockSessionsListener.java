package app.drydock.intellij;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.messages.Topic;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Application-level notification that the durable session catalog changed.
 *
 * <p>The catalog is application-scoped while its readers are spread across
 * every open window (the Agents widget, each agent editor's strip) and the
 * Welcome screen, which belongs to no window at all. A per-project push —
 * the v2 shape, a static {@code refreshFor(project)} on the tool-window panel
 * — cannot reach any of those, so the fan-out goes through the application
 * message bus instead.</p>
 *
 * <p>The event carries no payload on purpose: a subscriber re-reads the
 * catalog, so two changes landing close together cannot be applied out of
 * order, and a subscriber that misses one still converges. Publication is
 * {@link #publishChanged()}, called from the session service after every
 * mutation it makes through the single writer.</p>
 *
 * <p>Subscribers are called on whatever thread published — typically the
 * plugin's background executor — and must hop to the EDT themselves before
 * touching Swing.</p>
 */
public interface DrydockSessionsListener {

    Logger LOG = System.getLogger(DrydockSessionsListener.class.getName());

    Topic<DrydockSessionsListener> TOPIC =
            Topic.create("Drydock sessions changed", DrydockSessionsListener.class);

    /** Some session in the catalog was added, updated, or removed. */
    void sessionsChanged();

    /**
     * Publishes {@link #sessionsChanged()} to every subscriber, or does
     * nothing when no application is up (unit tests constructing the service
     * without an IDE). Never throws into the caller's persistence path: a
     * failing listener must not roll back a state write that already
     * happened.
     */
    static void publishChanged() {
        Application application = ApplicationManager.getApplication();
        if (application == null || application.isDisposed()) {
            return;
        }
        try {
            application.getMessageBus().syncPublisher(TOPIC).sessionsChanged();
        } catch (RuntimeException e) {
            // syncPublisher propagates a subscriber's failure to the
            // publisher. The publisher here is a persistence path whose write
            // has already happened, so a broken listener must not surface as
            // a failed state update.
            LOG.log(Level.WARNING, "A Drydock sessions listener failed: " + e);
        }
    }
}
