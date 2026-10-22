package app.drydock.intellij.review;

import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.McpDelivery;
import app.drydock.domain.ManagedSessionId;

import java.util.Optional;

/**
 * Hands a launching session its Drydock MCP credentials and takes them back.
 *
 * <p>Both methods do file I/O and run off the EDT. {@link #accessFor} never
 * throws for an I/O failure; it returns an empty result instead.</p>
 */
public interface SessionMcpAccess {

    /**
     * The credentials for {@code sessionId} in the form {@code delivery}
     * needs, or empty when the session launches without Drydock tools.
     */
    Optional<McpAccess> accessFor(ManagedSessionId sessionId, McpDelivery delivery);

    /** Revokes the session's token and deletes its credential files. */
    void release(ManagedSessionId sessionId);

    /** An implementation that serves no session and does nothing on release. */
    static SessionMcpAccess none() {
        return new SessionMcpAccess() {
            @Override
            public Optional<McpAccess> accessFor(ManagedSessionId sessionId, McpDelivery delivery) {
                return Optional.empty();
            }

            @Override
            public void release(ManagedSessionId sessionId) {
            }
        };
    }
}
