package app.drydock.intellij.mcp;

import app.drydock.agent.api.StdioMcpServer;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.mcpserver.StdioRunnerUtilKt;
import com.intellij.mcpserver.impl.McpServerService;
import com.intellij.openapi.extensions.PluginId;

import java.nio.file.Path;
import java.util.List;

/**
 * The IDE's bundled MCP server, as a stdio server a session's agent starts
 * for itself, routed to the session's own worktree.
 *
 * <p>The IDE serves one MCP endpoint for all its windows and routes each
 * call by the {@code IJ_MCP_SERVER_PROJECT_PATH} header. Without it, a call
 * from an agent whose worktree has no window of its own is answered from
 * whichever project happens to be open, silently (cross-project spike, H1).
 * The IDE's own stdio launcher turns that environment variable into the
 * header, so setting it per launch routes every call. A worktree with no
 * window open then gets a clean "doesn't correspond to any open project"
 * error instead of another project's answer.</p>
 *
 * <p>Attached only when the MCP plugin is installed and enabled and its
 * server is running: Drydock never turns the server on for the user. The
 * port and running state come from {@code McpServerService}, which lives in
 * the plugin's {@code impl} package; the command line comes from its public
 * {@code createStdioMcpServerCommandLine}, so the classpath and main class
 * stay the IDE's own business.</p>
 */
public final class IdeMcpServer {

    /** The bundled MCP server plugin, an optional dependency in plugin.xml. */
    static final String PLUGIN_ID = "com.intellij.mcpServer";
    /** The server name the agent sees, as the IDE's own client setup names it. */
    static final String SERVER_NAME = "jetbrains";

    private IdeMcpServer() {
    }

    /** The IDE server routed to {@code worktree}, or nothing when it is not available. */
    public static List<StdioMcpServer> forWorktree(Path worktree) {
        PluginId id = PluginId.getId(PLUGIN_ID);
        if (!PluginManagerCore.isPluginInstalled(id) || PluginManagerCore.isDisabled(id)) {
            return List.of();
        }
        return Bridge.forWorktree(worktree);
    }

    /**
     * Everything that touches the MCP plugin's classes, in its own class so
     * nothing loads them unless the plugin is present.
     */
    private static final class Bridge {

        static List<StdioMcpServer> forWorktree(Path worktree) {
            McpServerService service = McpServerService.Companion.getInstance();
            if (!service.isRunning()) {
                return List.of();
            }
            GeneralCommandLine command = StdioRunnerUtilKt.createStdioMcpServerCommandLine(
                    service.getPort(), worktree.toString(), null);
            return List.of(new StdioMcpServer(SERVER_NAME, command.getExePath(),
                    command.getParametersList().getList(), command.getEnvironment()));
        }
    }
}
