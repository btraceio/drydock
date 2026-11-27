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
 * {@code scripts/pi-bridge-smoke.sh}, which runs the shared bridge shape
 * against a mock server.</p>
 *
 * <p>Why proxies and not Pi's {@code registerMcpServer} for the Drydock
 * server: the session-scoped tools must be <em>direct</em> tools, and an MCP
 * extension that replaces Pi's built-in MCP support (pi-mcp-adapter) gives
 * extension-registered servers no direct tools at all -- its own docs state
 * that {@code exposure: "direct"} on such registrations is ignored. Tool
 * proxies registered through {@code registerTool} cannot be second-guessed by
 * any MCP extension. This copy adds the IDE-hosted stdio servers, which have
 * no direct-tool requirement and stay on {@code registerMcpServer} -- but an
 * invisible toolset is an unused toolset (agents grepped instead of asking
 * the IDE), so the handful of IDE tools that answer code-structure questions
 * get {@code registerTool} proxies of their own, declared to the model, plus
 * a system-prompt line pointing at the rest.</p>
 */
public final class PiMcpExtensionSource {

    private PiMcpExtensionSource() {
    }

    public static final String SOURCE = """
            // Managed by Drydock -- regenerated on launch; do not edit.
            import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

            const CONFIG_ENV = "DRYDOCK_MCP_CONFIG";
            const STDIO_ENV = "DRYDOCK_STDIO_MCP_SERVERS";
            const TOKEN_HEADER = "X-Drydock-Session-Token";
            const HANDSHAKE_MS = 2000;
            // pi may spawn before the session's MCP server binds; keep
            // retrying the handshake this long before standing down.
            const HANDSHAKE_DEADLINE_MS = 20000;
            const CALL_MS = 45000;
            // One localhost round trip plus one UI-thread hop. The rebind itself
            // is a single state transform, so this is generous for a busy app
            // without holding pi's session_start open long enough to notice.
            const RECLAIM_MS = 3000;

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
              let handingOver = false;
              // Set only when a proxy is actually registered. before_agent_start
              // gates on this rather than on `instructions` alone: a half-failed
              // handshake (initialize answered, tools/list did not) leaves
              // `instructions` populated by itself, and gating on it would keep
              // telling the model to call tools that were never registered.
              let anyRegistered = false;
              // The ide_* proxies that actually registered; the system-prompt
              // nudge names only these, so a skipped registration is never
              // advertised.
              let ideToolNames: string[] = [];
              // Guard for the one-time proxy registration inside session_start.
              let ideProxiesRegistered = false;
              // Set when a gateway connect attempt failed to produce the IDE
              // tools: later proxy calls then fail fast instead of repeating
              // the connect round trip on every call while the IDE is down.
              let ideConnectFailed = false;

              // The adapter gives extension-registered MCP servers no direct
              // tools, so the IDE's whole toolset is invisible to the model --
              // which then greps instead of asking the IDE (observed on every
              // session before these proxies existed). These five cover the
              // code-structure questions agents actually ask. The full toolset
              // (run configurations, build, debugger, VCS) stays behind tool
              // search in the jetbrains namespace. Declared before the
              // registerStdioServers() call below: registerIdeProxies reads
              // them, and a const read before its declaration throws.
              const IDE_SERVER = "jetbrains";
              // pi-mcp-adapter's runtime registration event; version 1 per its
              // extension-api contract. Used, not imported: this source is a
              // string pi loads through jiti with no dependency on the adapter.
              const ADAPTER_RUNTIME_REGISTER_EVENT = "pi-mcp-adapter:runtime-register:v1";
              const IDE_PROXY_TOOLS: Array<Record<string, any>> = [
                {
                  name: "ide_search_symbol",
                  ide: "search_symbol",
                  description: "Find a symbol (class, method, function) by name through the IDE's index, with file locations. Prefer over grep for 'where is X defined'.",
                  readOnly: true,
                  schema: { type: "object", properties: {
                    q: { type: "string", description: "Symbol query text" },
                    paths: { type: "array", items: { type: "string" }, description: "Optional project-relative glob patterns filtering results; supports '!' excludes" },
                    include_external: { type: "boolean", description: "Include SDK and library symbols; retry with true when nothing suitable was found" },
                    limit: { type: "integer", description: "Maximum number of results" },
                  }, required: ["q"] },
                },
                {
                  name: "ide_symbol_info",
                  ide: "get_symbol_info",
                  description: "Type, signature and documentation for the symbol at a file position, from the IDE. Prefer over reading and re-deriving types by hand.",
                  readOnly: true,
                  schema: { type: "object", properties: {
                    filePath: { type: "string", description: "Path relative to the project root" },
                    line: { type: "integer", description: "1-based line number" },
                    column: { type: "integer", description: "1-based column number" },
                  }, required: ["filePath", "line", "column"] },
                },
                {
                  name: "ide_call_hierarchy",
                  ide: "analyze_calls",
                  description: "Call hierarchy for a fully qualified callable: its callers (INCOMING_CALLS) or what it calls (OUTGOING_CALLS). Prefer over grep for 'where is X used / who calls Y' -- the index sees references text search misses.",
                  readOnly: true,
                  schema: { type: "object", properties: {
                    symbolFqn: { type: "string", description: "Fully qualified name, optionally with a parameter list, e.g. com.example.Service.run or com.example.Service.run(String); find it with ide_search_symbol first" },
                    analysisKind: { type: "string", enum: ["INCOMING_CALLS", "OUTGOING_CALLS"], description: "INCOMING_CALLS shows callers, OUTGOING_CALLS shows callees" },
                    depth: { type: "integer", description: "Maximum number of call levels to expand" },
                  }, required: ["symbolFqn", "analysisKind"] },
                },
                {
                  name: "ide_file_problems",
                  ide: "get_file_problems",
                  description: "Compiler errors and inspection results for one file, as the IDE sees them. Run before claiming a change is clean.",
                  readOnly: true,
                  schema: { type: "object", properties: {
                    filePath: { type: "string", description: "Path relative to the project root" },
                    errorsOnly: { type: "boolean", description: "Only errors when true; errors and warnings otherwise" },
                    timeout: { type: "integer", description: "Timeout in milliseconds" },
                  }, required: ["filePath"] },
                },
                {
                  name: "ide_rename",
                  ide: "rename_refactoring",
                  description: "Project-wide rename of a symbol through the IDE's refactoring, updating every reference -- not a text replace.",
                  readOnly: false,
                  schema: { type: "object", properties: {
                    pathInProject: { type: "string", description: "Path of the file declaring the symbol, relative to the project root" },
                    symbolName: { type: "string", description: "Exact, case-sensitive name of the symbol to rename" },
                    newName: { type: "string", description: "New name; a valid identifier for the symbol's language" },
                    line: { type: "integer", description: "Optional 1-based line of the symbol, with column" },
                    column: { type: "integer", description: "Optional 1-based column of the symbol, with line" },
                  }, required: ["pathInProject", "symbolName", "newName"] },
                },
              ];

              // Handlers first, synchronously, before any await: pi.on() only
              // asserts the runtime is active and cannot throw during load,
              // whereas a rejection before this point would discard the whole
              // extension object -- handlers included -- and take the tab with it.
              pi.on("session_start", async (event: any, ctx: any) => {
                // The ide_* proxies register here, not at load: their collision
                // snapshot reads getAllTools(), which pi stubs out during
                // extension loading (registerMcpServer itself is fine at load).
                // The guard keeps a second session_start on one live instance
                // from double-registering; session_before_switch/fork clear it,
                // so a conversation switch that dropped the tools re-registers
                // instead of leaving the nudge advertising ghosts. The IDE
                // registration itself is tab-scoped -- routed to this worktree,
                // whatever conversation it shows -- so this runs before the
                // stand-down branches below.
                if (!ideProxiesRegistered) {
                  ideProxiesRegistered = true;
                  registerStdioServers();
                  registerIdeProxies();
                }
                if (event?.previousSessionFile) {
                  // An in-session switch reloaded us into a conversation drydock
                  // did not claim. /new is the one switch that CAN be reclaimed:
                  // it mints a fresh conversation in the SAME tab drydock opened,
                  // so the bridge rebinds drydock's tracked conversation id to
                  // the new one and re-registers. /resume (to a different
                  // conversation) and /fork cannot: their new conversation is not
                  // the one this tab is running, and rebinding would point resume
                  // and handoff attribution at a conversation the human did not
                  // continue here.
                  if (event?.reason === "new") {
                    const newId = typeof ctx?.sessionManager?.getSessionId === "function"
                      ? ctx.sessionManager.getSessionId()
                      : undefined;
                    if (newId && wire) {
                      try {
                        await reclaim(wire, newId, AbortSignal.timeout(RECLAIM_MS));
                        // Reclaim landed: fall through to normal registration
                        // below. Do NOT set `registered` or return here -- the
                        // registration path owns that, and a fresh switch
                        // instance has nothing of ours in the active set yet.
                      } catch (e: any) {
                        ctx?.ui?.notify?.(
                          "drydock: could not rebind this conversation after /new; drydock's tools are not available in it",
                          "warning",
                        );
                        instructions = "";
                        registered = true;
                        return;
                      }
                    } else {
                      // No wire (the handshake failed at load) or no session id
                      // from this pi build: cannot rebind. Surface the load
                      // error if there is one -- it is more informative than
                      // the generic stand-down -- then stand down.
                      instructions = "";
                      registered = true;
                      ctx?.ui?.notify?.(
                        loadError ?? "drydock: this pi conversation was replaced and drydock's tools are not available in it",
                        "warning",
                      );
                      return;
                    }
                  } else {
                    // /resume to a different conversation, or /fork: stand down.
                    // Returning before registering means nothing of ours is in
                    // the active set yet, so there is nothing to remove. Dropping
                    // the instructions matters too, or the model keeps being told
                    // to call session_rename for the rest of the session.
                    instructions = "";
                    registered = true;
                    ctx?.ui?.notify?.(
                      "drydock: this pi conversation was replaced and drydock's tools are not available in it",
                      "warning",
                    );
                    return;
                  }
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
                  // One malformed tool descriptor must cost only itself, not
                  // every tool after it in the list.
                  try {
                    registerProxy(tool);
                    anyRegistered = true;
                  } catch (e: any) {
                    ctx?.ui?.notify?.(
                      `drydock: ${tool.name} could not be registered and was skipped`,
                      "warning",
                    );
                  }
                }
              });

              // Reversible: pi throws non-fatally between here and the teardown
              // on three of four switch paths, and another extension can cancel
              // the switch outright, so this must be undoable.
              pi.on("session_before_switch", () => { handingOver = true; ideProxiesRegistered = false; });
              pi.on("session_before_fork", () => { handingOver = true; ideProxiesRegistered = false; });

              pi.on("before_agent_start", async (event: any) => {
                handingOver = false;
                // Gate each block on the tools it names having actually
                // registered, not merely on text being present: initialize and
                // tools/list share one budget, and "initialize answered,
                // tools/list did not" is the ordinary shape of a slow server --
                // it must not leave the model being told to call tools that do
                // not exist.
                const parts: string[] = [];
                if (anyRegistered && instructions) parts.push(instructions);
                if (ideToolNames.length > 0) parts.push(ideInstructions());
                if (parts.length === 0) return undefined;
                return { systemPrompt: `${event.systemPrompt}\\n\\n${parts.join("\\n\\n")}` };
              });

              // Network work only, and never allowed to reject: a failed extension
              // load exits pi 1, on every load path.
              try {
                wire = await connect();
                if (!wire) return;
              } catch (e: any) {
                wire = null;
                // A half-failed handshake must not leave `instructions`
                // populated with nothing registered to back it.
                tools = [];
                instructions = "";
                loadError = "drydock: could not reach this session's tools";
              }

              /**
               * pi is spawned in the same breath as the config file and the
               * session's MCP server -- the harness launches the agent while
               * the server is still binding -- so the FIRST attempt usually
               * fails: the config may not exist yet and the server may not
               * accept yet. A one-shot handshake therefore stood the whole
               * session down (the IDE launch of 2026-10-24 18:16 proved it
               * live: the agent had no tools and fell back to raw HTTP over
               * bash). Retry until HANDSHAKE_DEADLINE_MS; each attempt keeps
               * its own HANDSHAKE_MS budget, because a wedged socket must not
               * eat the whole deadline. A success resets the half-failed
               * state; only the final failure leaves a stand-down. No config
               * env at all means no MCP access was granted -- no point
               * retrying, return immediately.
               */
              async function connect(): Promise<Wire | null> {
                if (!process.env[CONFIG_ENV]) return null;
                const deadline = Date.now() + HANDSHAKE_DEADLINE_MS;
                while (Date.now() < deadline) {
                  let candidate: Wire | null = null;
                  try {
                    candidate = readWire();
                  } catch (e: any) {
                    // The config file may not exist yet; retry.
                  }
                  if (candidate) {
                    try {
                      // ONE budget for the pair, not one each: this bounds a
                      // wedged socket, and a wedged socket burns the whole
                      // budget twice over.
                      const handshake = AbortSignal.timeout(HANDSHAKE_MS);
                      const init = await rpc(candidate, "initialize", {
                        protocolVersion: "2025-06-18",
                        capabilities: {},
                        clientInfo: { name: "drydock-pi-bridge", version: "1" },
                      }, handshake);
                      const listed = await rpc(candidate, "tools/list", {}, handshake);
                      instructions = typeof init?.instructions === "string" ? init.instructions : "";
                      tools = Array.isArray(listed?.tools) ? listed.tools : [];
                      return candidate;
                    } catch (e: any) {
                      // Server not accepting yet, or a half-failed handshake:
                      // reset the derived state and retry.
                      tools = [];
                      instructions = "";
                    }
                  }
                  await new Promise((resolve) => setTimeout(resolve, 250));
                }
                return null;
              }

              function registerStdioServers() {
                const raw = process.env[STDIO_ENV];
                delete process.env[STDIO_ENV];
                if (!raw) return;
                let servers: Record<string, any>;
                try {
                  servers = JSON.parse(raw)?.mcpServers ?? {};
                } catch (e: any) {
                  console.warn("drydock: could not read the IDE's MCP servers;", e?.message ?? e);
                  return;
                }
                for (const [name, server] of Object.entries(servers)) {
                  try {
                    registerIdeServer(name, server);
                  } catch (e: any) {
                    console.warn(`drydock: MCP server ${name} was not registered;`, e?.message ?? e);
                  }
                }
              }

              /**
               * Registers one IDE server with literal env values. Pi's own
               * registerMcpServer translates the config through a fixed field
               * list whose translation drops `literalEnv`, after which the
               * adapter interpolates ${NAME}, $env:NAME and {env:NAME} into the
               * values and treats a leading ! as a secret command -- either
               * corrupts a literal worktree path. The adapter's
               * runtime-register event takes an adapter-native definition
               * instead, literalEnv included, so it is the primary path. The
               * registerMcpServer fallback serves a Pi without the adapter,
               * where the built-in template rules apply to the values -- so a
               * value those rules could touch must never pass silently: a
               * mis-interpolated IJ_MCP_SERVER_PROJECT_PATH routes another
               * project's answers into this session, and a leading ! is
               * arbitrary command execution. Refuse loudly instead.
               */
              function registerIdeServer(name: string, server: any) {
                const request: any = {
                  version: 1,
                  name,
                  definition: { ...server, literalEnv: true },
                };
                pi.events.emit(ADAPTER_RUNTIME_REGISTER_EVENT, request);
                if (request.result?.ok) return;
                if (request.result) throw request.result.error;
                const literal = (values: any) =>
                  Object.values(values ?? {}).every((value) =>
                    typeof value !== "string" || !(value.startsWith("!") || value.includes("$")));
                if (!literal(server?.env) || !literal(server?.headers)) {
                  throw new Error(
                    `env or header values carry template syntax (a $ or a leading !) and this Pi has no MCP adapter to keep them literal; refusing to register a server whose routing values could be rewritten`,
                  );
                }
                pi.registerMcpServer(name, server);
              }

              function registerIdeProxies() {
                // Snapshot before registering: pi resolves extension-over-builtin
                // by overwriting, so a name that is already taken must not be
                // re-declared on top of its owner.
                const taken = new Set((pi.getAllTools() ?? []).map((t: any) => t?.name));
                for (const spec of IDE_PROXY_TOOLS) {
                  if (taken.has(spec.name)) {
                    console.warn(`drydock: IDE tool ${spec.name} collides with an existing tool and was not registered`);
                    continue;
                  }
                  try {
                    pi.registerTool({
                      name: spec.name,
                      label: spec.name.replace(/^ide_/, "IDE: ").replace(/_/g, " "),
                      description: spec.description,
                      // Without promptSnippet the tool is left out of the default
                      // "Available tools" section entirely (pi >= 0.59.0).
                      promptSnippet: spec.description,
                      // A raw JSON schema is a valid parameters value: pi-ai's
                      // validator branches on the absence of TypeBox.Kind and
                      // runs a JSON-Schema coercion pass instead.
                      parameters: spec.schema,
                      annotations: spec.readOnly ? { readOnlyHint: true } : undefined,
                      async execute(_id: string, params: any, signal?: AbortSignal, _onUpdate?: any, ctx?: any) {
                        if (!ctx || typeof ctx.executeTool !== "function") {
                          // pi documents the tool context, but an opaque
                          // TypeError from a build that omits it would read as
                          // an IDE failure; name the real cause instead.
                          throw new Error(`${spec.name}: this Pi build passed no tool context, so the IDE proxy cannot forward the call`);
                        }
                        return await callIdeTool(spec, params, signal, ctx);
                      },
                    } as any);
                    ideToolNames.push(spec.name);
                  } catch (e: any) {
                    console.warn(`drydock: IDE tool ${spec.name} was not registered;`, e?.message ?? e);
                  }
                }
              }

              /**
               * Forwards one curated call to the adapter-registered jetbrains
               * tool of the same IDE name. The adapter's name prefix has varied
               * across versions (jetbrains_<tool>, mcp__jetbrains__<tool>), so
               * the registered name is resolved from Pi's registry at call time
               * instead of being hardcoded.
               */
              async function callIdeTool(spec: any, params: any, signal?: AbortSignal, ctx?: any) {
                const call = (options: { signal?: AbortSignal }, ...rest: any[]) =>
                  ctx.executeTool(...rest, options);
                let target = resolveIdeTool(spec.ide);
                if (!target && ideConnectFailed) {
                  throw new Error(
                    `${spec.name}: the ${IDE_SERVER} MCP server did not connect when an earlier call tried; is the IDE running with its MCP server enabled?`,
                  );
                }
                if (!target) {
                  // Fresh session: a runtime-registered server has no cached
                  // catalog, so its deferred tools are not registered yet and
                  // nothing else will connect it (the adapter connects lazy
                  // servers only through a call path). The adapter's gateway is
                  // itself a tool and its connect action is its documented
                  // trigger; after it, the deferred tools exist and resolve.
                  const all: any[] = (pi.getAllTools() ?? []) as any[];
                  const gateway = ["mcp", "mcp__mcp"].find((name) =>
                    all.some((t) => t?.name === name));
                  if (gateway) {
                    await call({ signal }, gateway, { connect: IDE_SERVER });
                    target = resolveIdeTool(spec.ide);
                  }
                }
                if (!target) {
                  // A failed connect must not turn every later IDE tool call
                  // into a fresh connect-then-throw round trip: remember the
                  // failure and fail fast until something reconnects.
                  ideConnectFailed = true;
                  throw new Error(
                    `${spec.name}: the IDE's tools are unavailable -- the ${IDE_SERVER} MCP server has not connected. Is the IDE running with its MCP server enabled?`,
                  );
                }
                // codemode/deferred tools are callable whenever registered, at
                // any exposure, through executeTool.
                const result = await call({ signal }, target, params ?? {});
                // An error must read as an error: AgentToolResult has no isError
                // field, so a failed nested call is signalled by throwing.
                if (result?.isError) {
                  const text = (result?.content ?? []).map((c: any) => String(c?.text ?? "")).join("\\n");
                  throw new Error(text || `${spec.name} failed`);
                }
                return result;
              }

              /** The registered jetbrains tool for one IDE tool name, or null. */
              function resolveIdeTool(ideName: string): string | null {
                const all: any[] = (pi.getAllTools() ?? []) as any[];
                const candidates = [`jetbrains_${ideName}`, `mcp__jetbrains__${ideName}`];
                const exact = candidates.find((candidate) => all.some((t) => t?.name === candidate));
                if (exact) return exact;
                // A prefix mode this bridge does not know: fall back to the one
                // registered name that carries the server and the IDE tool name.
                return all.map((t) => t?.name).find((n: any) =>
                  typeof n === "string" && n.includes(IDE_SERVER) && n.endsWith(`_${ideName}`)) ?? null;
              }

              function ideInstructions(): string {
                return "IntelliJ IDE tools are declared above: " + ideToolNames.join(", ") +
                  ". They answer code-structure questions from the compiler's index -- symbol lookup, call hierarchy (who calls this / where is this used), inspections for a file, and project-wide rename. For those questions prefer them over grep and file reads: text search misses references through overloads, inheritance and generated code. The rest of the IDE toolset (run configurations, build, debugger, VCS) is reachable through tool search in the jetbrains namespace.";
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

              async function call(wire: Wire, name: string, args: any, signal: AbortSignal): Promise<any> {
                const res = await fetch(wire.url, {
                  method: "POST",
                  headers: { "content-type": "application/json", [TOKEN_HEADER]: wire.token },
                  body: JSON.stringify({
                    jsonrpc: "2.0", id: Date.now(), method: "tools/call",
                    params: { name, arguments: args },
                  }),
                  signal,
                });
                if (!res.ok) {
                  if (res.status === 401) throw new Error(`${name}: this drydock session has ended`);
                  throw new Error(`${name}: HTTP ${res.status}`);
                }
                const body = await res.json();
                if (body?.error) throw new Error(`${name}: ${body.error.message ?? "error"}`);
                return body?.result;
              }

              /**
               * The bridge's private hand-back on /new: rebind drydock's tracked
               * conversation id to the one pi just minted. Unlike a model-driven
               * tools/call, a refusal comes back as isError:true (a tool
               * exception), NOT as a JSON-RPC error, so this checks both -- a
               * reclaim that "succeeded" while drydock refused the rebind would
               * re-register tools into a conversation drydock is not tracking,
               * which is exactly the cross-conversation write the stand-down
               * exists to prevent.
               */
              async function reclaim(wire: Wire, newId: string, signal: AbortSignal): Promise<void> {
                const res = await fetch(wire.url, {
                  method: "POST",
                  headers: { "content-type": "application/json", [TOKEN_HEADER]: wire.token },
                  body: JSON.stringify({
                    jsonrpc: "2.0", id: Date.now(), method: "tools/call",
                    params: { name: "session_reclaim", arguments: { agentSessionId: newId } },
                  }),
                  signal,
                });
                if (!res.ok) {
                  if (res.status === 401) throw new Error("session_reclaim: this drydock session has ended");
                  throw new Error(`session_reclaim: HTTP ${res.status}`);
                }
                const body = await res.json();
                if (body?.error) throw new Error(`session_reclaim: ${body.error.message ?? "error"}`);
                if (body?.result?.isError) {
                  const text = String(body?.result?.content?.[0]?.text ?? "refused");
                  throw new Error(`session_reclaim: ${text}`);
                }
              }

              /**
               * McpServer double-encodes a success payload: content[0].text is the
               * JSON-serialized result, so the outcome is one parse away. Reaching
               * for result.title directly yields undefined.
               */
              function decode(text: string): any {
                try {
                  return JSON.parse(text);
                } catch {
                  return {};
                }
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
                  async execute(_id: string, params: any, signal?: AbortSignal) {
                    if (handingOver) {
                      throw new Error(`${tool.name}: this drydock session is being handed over`);
                    }
                    // AbortSignal.any rejects on undefined, and pi types the
                    // signal as optional and guards it as signal?.aborted itself.
                    const deadline = AbortSignal.timeout(CALL_MS);
                    const combined = signal ? AbortSignal.any([signal, deadline]) : deadline;
                    let result: any;
                    try {
                      result = await call(wire!, tool.name, params, combined);
                    } catch (e: any) {
                      // Never rethrow, and never read e.cause: e.message is
                      // "fetch failed" and is the only string undici gives us
                      // that does not carry 127.0.0.1:<port>. The timeout arm is
                      // checked FIRST, because both causes can be set at once and
                      // a call that ran the full budget is exactly the one that
                      // may have landed.
                      if (deadline.aborted) {
                        throw new Error(
                          `${tool.name}: no response in ${CALL_MS / 1000}s; the call may have completed -- do not retry`,
                        );
                      }
                      if (signal?.aborted) throw new Error(`${tool.name}: cancelled`);
                      // call() already prefixes with the tool name and yields only
                      // a status or the stable 401 text, so rethrowing its message
                      // verbatim would double the prefix. Anything else reaching
                      // here is a transport error, whose message must NOT be
                      // relayed: "fetch failed" is safe but a bad URL yields
                      // "Failed to parse URL from http://127.0.0.1:<port>/mcp".
                      const known = String(e?.message ?? "");
                      if (known.startsWith(`${tool.name}: `)) throw new Error(known);
                      throw new Error(`${tool.name}: transport failure`);
                    }
                    const text = String(result?.content?.[0]?.text ?? "");
                    // A refusal must read as a refusal: AgentToolResult has no
                    // isError field, so an error is signalled by throwing.
                    if (result?.isError) throw new Error(text);
                    const outcome = decode(text);
                    // Dispatch is generic; session_rename alone has a post-call
                    // hook, because only it has a name pi also stores. The title
                    // comes from the OUTCOME, not the request: drydock may have
                    // refused or altered it.
                    if (tool.name === "session_rename" && typeof outcome?.title === "string") {
                      try {
                        pi.setSessionName(outcome.title);
                      } catch {
                        // A stale runtime after a session switch throws here.
                        // The rename already landed in drydock; pi's picker
                        // being out of step is not worth failing the call over.
                      }
                    }
                    return { content: [{ type: "text", text }], details: outcome };
                  },
                } as any);
              }
            }
            """;
}
