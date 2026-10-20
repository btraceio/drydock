package app.drydock.agent.providers.pi.internal;

/**
 * The extension that hands the IDE's MCP servers to pi, as TypeScript pi
 * loads with {@code -e}.
 *
 * <p>Pi has connected MCP servers itself since 0.99.0, so this is not a
 * client: it reads the {@code StdioMcpServer} entries the launch put in
 * {@code DRYDOCK_STDIO_MCP_SERVERS} (the {@code mcpServers} shape Claude is
 * handed) and passes each to {@code pi.registerMcpServer}. Pi then starts the
 * server, names its tools {@code mcp__<server>__<tool>}, applies its own
 * timeouts, lists it in {@code /mcp}, and stops it with the session. There is
 * no URL or token here: the IDE's stdio runner finds the server by port and
 * routes every call to the session's worktree.</p>
 *
 * <p>Separate from {@link PiExtensionSource}, the bridge to Drydock's own
 * server, because the two are offered independently: inside the IDE there is
 * no Drydock server, only the IDE's.</p>
 *
 * <p>As in {@link PiExtensionSource}, <strong>every backslash in the
 * TypeScript must be doubled here</strong>, and nothing type-checks it: pi
 * loads it through {@code jiti}, which strips types without checking
 * them.</p>
 */
public final class PiIdeMcpExtensionSource {

    private PiIdeMcpExtensionSource() {
    }

    public static final String SOURCE = """
            // Managed by Drydock -- regenerated on launch; do not edit.
            import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

            const SERVERS_ENV = "DRYDOCK_STDIO_MCP_SERVERS";

            // Synchronous and never throwing: a failed extension load exits pi 1
            // and takes the tab with it, and no MCP server is worth that.
            // Registered while loading, so pi connects them when the session
            // starts, together with the user's own mcp.json servers.
            export default function (pi: ExtensionAPI) {
              let servers: Record<string, any> = {};
              try {
                const raw = process.env[SERVERS_ENV];
                if (!raw) return;
                servers = JSON.parse(raw)?.mcpServers ?? {};
              } catch (e: any) {
                console.warn("drydock: could not read the IDE's MCP servers;", e?.message ?? e);
                return;
              }
              for (const [name, server] of Object.entries(servers)) {
                // One bad entry costs only itself, not the servers after it.
                try {
                  pi.registerMcpServer(name, {
                    command: String(server?.command),
                    args: Array.isArray(server?.args) ? server.args.map(String) : [],
                    env: literalEnv(server?.env ?? {}),
                    // Declared to the model like a built-in tool, as Claude and
                    // Codex see them. Pi's default, codemode, reaches them only
                    // from model-written scripts.
                    exposure: "direct",
                  });
                } catch (e: any) {
                  console.warn(`drydock: MCP server ${name} was not registered;`, e?.message ?? e);
                }
              }

              // Pi reads env values as templates: $NAME and ${NAME} expand, and a
              // leading ! runs a command. These are literals -- a port and the
              // worktree path, which may well contain a $ -- so every $ is
              // doubled and a leading ! escaped, as pi's own parser defines.
              function literalEnv(env: Record<string, any>): Record<string, string> {
                const literal: Record<string, string> = {};
                for (const [key, value] of Object.entries(env)) {
                  const text = String(value).replace(/\\$/g, "$$$$");
                  literal[key] = text.startsWith("!") ? "$" + text : text;
                }
                return literal;
              }
            }
            """;
}
