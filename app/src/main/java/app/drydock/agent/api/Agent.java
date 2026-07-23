package app.drydock.agent.api;

/** The registry's identity/availability view of a provider, for the UI. */
public interface Agent {
    AgentKind kind();
    String displayName();
    boolean isAvailable();
    /** Human-readable list of places the executable was looked for (for an unavailable tooltip). */
    String describeSearched();
}
