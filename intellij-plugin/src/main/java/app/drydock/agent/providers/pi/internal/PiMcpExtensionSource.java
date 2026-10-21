package app.drydock.agent.providers.pi.internal;

/**
 * A small registration adapter for Pi's native MCP client.
 *
 * <p>Drydock's HTTP server is described by the owner-only config file named
 * in {@code DRYDOCK_MCP_CONFIG}; IDE-hosted stdio servers arrive as the
 * standard {@code mcpServers} JSON in {@code DRYDOCK_STDIO_MCP_SERVERS}.
 * This extension does no MCP transport work. It only hands those dynamic,
 * per-session definitions to Pi's native {@code registerMcpServer} API.</p>
 */
public final class PiMcpExtensionSource {

    private PiMcpExtensionSource() {
    }

    public static final String SOURCE = """
            // Managed by Drydock -- regenerated on launch; do not edit.
            import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

            const CONFIG_ENV = "DRYDOCK_MCP_CONFIG";
            const STDIO_ENV = "DRYDOCK_STDIO_MCP_SERVERS";

            export default function (pi: ExtensionAPI) {
              const servers: Record<string, any> = {};
              readConfigFile(servers);
              readInlineServers(servers);

              for (const [name, server] of Object.entries(servers)) {
                try {
                  pi.registerMcpServer(name, {
                    ...server,
                    env: literalValues(server?.env),
                    headers: literalValues(server?.headers),
                    exposure: "direct",
                  });
                } catch (e: any) {
                  console.warn(`drydock: MCP server ${name} was not registered;`, e?.message ?? e);
                }
              }

              function readConfigFile(target: Record<string, any>) {
                const path = process.env[CONFIG_ENV];
                delete process.env[CONFIG_ENV];
                if (!path) return;
                try {
                  const raw = require("node:fs").readFileSync(path, "utf8");
                  Object.assign(target, JSON.parse(raw)?.mcpServers ?? {});
                } catch (e: any) {
                  console.warn("drydock: could not read its MCP config;", e?.message ?? e);
                }
              }

              function readInlineServers(target: Record<string, any>) {
                const raw = process.env[STDIO_ENV];
                delete process.env[STDIO_ENV];
                if (!raw) return;
                try {
                  Object.assign(target, JSON.parse(raw)?.mcpServers ?? {});
                } catch (e: any) {
                  console.warn("drydock: could not read the IDE's MCP servers;", e?.message ?? e);
                }
              }

              // Pi treats values as templates: $NAME expands and leading !
              // executes a command. Drydock supplied these as literal values.
              function literalValues(values: any): Record<string, string> | undefined {
                if (!values || typeof values !== "object") return undefined;
                const literal: Record<string, string> = {};
                for (const [key, value] of Object.entries(values)) {
                  const text = String(value).replace(/\\$/g, "$$$$");
                  literal[key] = text.startsWith("!") ? "$" + text : text;
                }
                return literal;
              }
            }
            """;
}
