package app.drydock.agent.providers.pi.internal;

/**
 * The drydock bridge extension, as TypeScript pi loads with {@code -e}.
 *
 * <p>Held as a text block rather than a jar resource so there is no extraction
 * step and no temp-file fallback to fail silently. <strong>Every backslash in
 * the TypeScript must be doubled here</strong>, exactly as
 * {@code ClaudeHookInstaller.HOOK_SCRIPT} does for its {@code sed}
 * expressions.</p>
 *
 * <p>Nothing type-checks this string: pi loads it through {@code jiti}, which
 * strips types without checking them. The only guard is
 * {@code scripts/pi-bridge-smoke.sh}, which runs it against a mock server.</p>
 */
public final class PiExtensionSource {

    private PiExtensionSource() {
    }

    public static final String SOURCE = """
            // Managed by Drydock -- regenerated on launch; do not edit.
            import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

            const CONFIG_ENV = "DRYDOCK_MCP_CONFIG";
            const TOKEN_HEADER = "X-Drydock-Session-Token";
            const HANDSHAKE_MS = 2000;

            type Wire = { url: string; token: string };

            // EVERYTHING lives inside the default export, so the handlers and the
            // tool proxies close over the same state. Module scope is not an
            // option: loadExtension re-invokes this factory on every load while
            // jiti caches the module, so module-level state would leak from one
            // load into the next.
            export default async function (pi: ExtensionAPI) {
              let wire: Wire | null = null;
              let tools: any[] = [];
              let instructions = "";
              let loadError: string | null = null;
              let registered = false;

              // Handlers first, synchronously, before any await: pi.on() only
              // asserts the runtime is active and cannot throw during load,
              // whereas a rejection before this point would discard the whole
              // extension object -- handlers included -- and take the tab with it.
              pi.on("session_start", async (event: any, ctx: any) => {
                if (event?.previousSessionFile) {
                  // An in-session switch (/new, /resume, /fork) reloaded us into a
                  // conversation drydock did not claim. Standing down here means
                  // returning before registering: nothing of ours is in the active
                  // set yet, so there is nothing to remove. Dropping the
                  // instructions matters too, or the model keeps being told to call
                  // session_rename for the rest of the session.
                  instructions = "";
                  registered = true;
                  return;
                }
                if (registered) {
                  // A second session_start on one live instance. Registering again
                  // would snapshot our own tools and refuse every one of them.
                  return;
                }
                registered = true;
                if (loadError) {
                  ctx?.ui?.notify?.(loadError, "warning");
                  return;
                }
                if (!wire || tools.length === 0) return;

                // Snapshot BEFORE registering: pi resolves extension-over-builtin
                // by overwriting, so afterwards getAllTools() shows one `read`
                // -- ours -- and a name comparison finds nothing wrong.
                const taken = new Set((pi.getAllTools() ?? []).map((t: any) => t?.name));
                for (const tool of tools) {
                  if (taken.has(tool.name)) {
                    ctx?.ui?.notify?.(
                      `drydock: ${tool.name} collides with an existing tool and was not registered`,
                      "warning",
                    );
                    continue;
                  }
                  registerProxy(tool);
                }
              });

              pi.on("before_agent_start", async (event: any) => {
                if (!instructions) return undefined;
                return { systemPrompt: `${event.systemPrompt}\\n\\n${instructions}` };
              });

              // Network work only, and never allowed to reject: a failed extension
              // load exits pi 1, on every load path.
              try {
                wire = readWire();
                if (!wire) return;
                // ONE budget for the pair, not one each: this bounds a wedged
                // socket, and a wedged socket burns the whole budget twice over.
                const handshake = AbortSignal.timeout(HANDSHAKE_MS);
                const init = await rpc(wire, "initialize", {
                  protocolVersion: "2025-06-18",
                  capabilities: {},
                  clientInfo: { name: "drydock-pi-bridge", version: "1" },
                }, handshake);
                instructions = typeof init?.instructions === "string" ? init.instructions : "";
                const listed = await rpc(wire, "tools/list", {}, handshake);
                tools = Array.isArray(listed?.tools) ? listed.tools : [];
              } catch (e: any) {
                wire = null;
                loadError = "drydock: could not reach this session's tools";
              }

              function readWire(): Wire | null {
                const path = process.env[CONFIG_ENV];
                if (!path) return null;
                const raw = require("node:fs").readFileSync(path, "utf8");
                const server = JSON.parse(raw)?.mcpServers?.drydock;
                const url = server?.url;
                const token = server?.headers?.[TOKEN_HEADER];
                return url && token ? { url, token } : null;
              }

              async function rpc(w: Wire, method: string, params: any, signal: AbortSignal): Promise<any> {
                // Post the config's url verbatim: the server 403s any Host or
                // Origin that is not exactly {http://,}{127.0.0.1,localhost}:<port>.
                const res = await fetch(w.url, {
                  method: "POST",
                  headers: { "content-type": "application/json", [TOKEN_HEADER]: w.token },
                  body: JSON.stringify({ jsonrpc: "2.0", id: Date.now(), method, params }),
                  signal,
                });
                // 401/403/405 arrive with an empty body, so never parse first.
                if (!res.ok) throw new Error(`drydock ${method}: HTTP ${res.status}`);
                const body = await res.json();
                if (body?.error) throw new Error(`drydock ${method}: ${body.error.message ?? "error"}`);
                return body?.result;
              }

              function registerProxy(tool: any) {
                const label = String(tool.name)
                  .replace(/_/g, " ")
                  .replace(/^./, (c: string) => c.toUpperCase());
                pi.registerTool({
                  name: tool.name,
                  label,
                  description: tool.description ?? "",
                  // Without promptSnippet the tool is left out of the default
                  // "Available tools" section entirely (pi >= 0.59.0).
                  promptSnippet: String(tool.description ?? "").split("\\n")[0],
                  // A raw MCP inputSchema is a valid parameters value: pi-ai's
                  // validator branches on the absence of TypeBox.Kind and runs a
                  // JSON-Schema coercion pass instead.
                  parameters: tool.inputSchema ?? { type: "object", properties: {} },
                  async execute(_id: string, params: any) {
                    const result = await rpc(wire!, "tools/call",
                      { name: tool.name, arguments: params }, AbortSignal.timeout(45000));
                    const text = String(result?.content?.[0]?.text ?? "");
                    // A refusal must read as a refusal: AgentToolResult has no
                    // isError field, so an error is signalled by throwing.
                    if (result?.isError) throw new Error(text);
                    return { content: [{ type: "text", text }], details: {} };
                  },
                } as any);
              }
            }
            """;
}
