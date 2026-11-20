package app.drydock.agent.api;

/**
 * How the id a tool assigns to a session becomes known to Drydock.
 *
 * <ul>
 *   <li>{@code PRESET} — Drydock generates the id and the launch command
 *       carries it (Claude {@code --session-id}, Pi {@code --session}).</li>
 *   <li>{@code DISCOVERED} — the tool mints its own id; Drydock captures it
 *       after launch (Codex). Discovery itself is a provider concern.</li>
 * </ul>
 */
public enum SessionIdStrategy {
    PRESET,
    DISCOVERED
}
