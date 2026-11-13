package app.drydock.agent.api;

import app.drydock.domain.ManagedSessionId;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The box-side persistence contract for a remote agent launch. A
 * {@link CreateContext} or {@link ResumeContext} carrying one means the
 * session must live on a workspace box, inside the tmux session named after
 * {@code managedSessionId} (see {@code SshCommandBuilder.tmuxSessionName}),
 * so it survives the local console closing.
 *
 * <p>{@code managedSessionId} is the application-assigned session identity
 * (the row's id in {@code ManagedAgentSession}) — deliberately not the
 * agent's conversation id, which is a different identifier that must never
 * be overloaded onto this one.</p>
 *
 * <p>{@code agentEnvironment} is the environment the agent CLI must run
 * with on the box (for example the gateway-only proxy base URL). The map is
 * copied at construction, so a caller mutating its map afterwards cannot
 * change what the launch renders, and it renders deterministically in
 * sorted key order regardless of the caller's iteration order.</p>
 */
public record RemotePersistence(ManagedSessionId managedSessionId,
                                Map<String, String> agentEnvironment) {

    public RemotePersistence {
        Objects.requireNonNull(managedSessionId, "managedSessionId");
        // Copied: the caller's map is a snapshot at call time, and a later
        // mutation must not change what the launch renders.
        agentEnvironment = Map.copyOf(Objects.requireNonNull(agentEnvironment, "agentEnvironment"));
    }

    /**
     * Renders the environment in sorted key order, so the same map always
     * renders to the same string regardless of the caller's iteration
     * order.
     */
    @Override
    public String toString() {
        return "RemotePersistence[managedSessionId=" + managedSessionId
                + ", agentEnvironment=" + new TreeMap<>(agentEnvironment) + "]";
    }
}
