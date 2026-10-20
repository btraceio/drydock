package app.drydock.agent.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * An MCP server the agent starts itself, over stdio: a command, its
 * arguments and the environment it needs. The IDE's bundled MCP server is
 * reached this way, through its own stdio launcher, with the session's
 * worktree in {@code IJ_MCP_SERVER_PROJECT_PATH} so every call is routed to
 * that project.
 *
 * <p>Carries no credential: the IDE server is loopback-only and the values
 * here are a port and paths, so unlike {@link McpAccess} it may appear on a
 * command line. {@code env} is kept sorted so the rendered command is
 * stable.</p>
 */
public record StdioMcpServer(String name, String command, List<String> args, Map<String, String> env) {

    public StdioMcpServer {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(command, "command");
        args = List.copyOf(Objects.requireNonNull(args, "args"));
        env = new TreeMap<>(Objects.requireNonNull(env, "env"));
    }
}
