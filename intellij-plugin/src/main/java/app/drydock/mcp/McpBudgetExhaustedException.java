package app.drydock.mcp;

/** A session hit its per-session creation limit. Carries the limit in its message. */
public class McpBudgetExhaustedException extends Exception {

    public McpBudgetExhaustedException(String message) {
        super(message);
    }
}
