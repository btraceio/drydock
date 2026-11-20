package app.drydock.mcp;

/**
 * A tool call that failed for a reason the agent can act on. The message is
 * surfaced verbatim as the MCP {@code isError} content, so it must name what
 * went wrong and what would be different -- never a bare "failed" (AGENTS.md:
 * a failed command is never silently equal to an empty result).
 */
public class McpToolException extends Exception {

    public McpToolException(String message) {
        super(message);
    }

    public McpToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
