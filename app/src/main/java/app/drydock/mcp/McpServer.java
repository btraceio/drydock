package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.time.Instant;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A localhost-only HTTP transport for the MCP tool protocol (JSON-RPC 2.0
 * over {@code POST /mcp}). Binds {@code 127.0.0.1} on an ephemeral port and
 * never {@code 0.0.0.0} -- this server is a bridge to a single hosted
 * {@code claude} process on the same machine, not a network service.
 *
 * <p>The {@code X-Drydock-Session-Token} header identifies which managed
 * session is calling, so tools resolve to the right repository. As {@link
 * McpSessionRegistry}'s Javadoc explains, the token is attribution, not a
 * secret between sessions: any process running as this user's uid can already
 * read a sibling session's config file. {@code Origin}/{@code Host} checks
 * below are defense in depth against a browser tab rebinding to this port --
 * they add nothing against a local process, which is why an absent {@code
 * Origin} (as CLI clients send) is accepted rather than rejected.</p>
 */
public final class McpServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(McpServer.class.getName());

    private static final String TOKEN_HEADER = "X-Drydock-Session-Token";
    private static final String PATH = "/mcp";

    /** Bounded drain for in-flight handlers on close; matches SessionManager's own close budget. */
    private static final long CLOSE_AWAIT_TERMINATION_SECONDS = 2;

    /**
     * Answered when the client asks for a version this server does not know.
     * The oldest of {@link #SUPPORTED_PROTOCOL_VERSIONS}, because a client that
     * does not recognise it is required to disconnect -- so the fallback must
     * be the one most likely to be understood.
     */
    private static final String FALLBACK_PROTOCOL_VERSION = "2024-11-05";

    /**
     * Versions this server will echo back. All three describe the same wire
     * shape as far as this server uses it: a POST of one JSON-RPC request
     * answered with one JSON response, a tools-only capability set, and no
     * server-initiated messages. Nothing here depends on the SSE stream,
     * resources, prompts or session headers that differ between them, so the
     * version is the client's to choose.
     *
     * <p>Echoing rather than asserting one version matters because the MCP
     * spec makes the client disconnect when it does not recognise the version
     * the server names -- the handshake is the one part of this feature no
     * automated test can verify against a real client.</p>
     */
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS =
            Set.of("2024-11-05", "2025-03-26", "2025-06-18");

    private final McpSessionRegistry registry;
    private final McpToolRouter router;

    /**
     * The bounded traffic log the Review destination's activity panel renders
     * (spec §4.7). Supplied rather than owned: the Review view is built
     * before the server starts, and both must see the same log.
     */
    private final McpActivityLog activityLog;

    // Volatile: {@link #start()} runs on a startup virtual thread while
    // {@link #close()} is called from the JavaFX shutdown path, so a
    // non-volatile field would let a stop() racing startup read a stale null
    // and leak the bound listener socket.
    private volatile HttpServer server;
    private volatile ExecutorService executor;
    private volatile int port;

    /**
     * Set by {@link #close()} before it looks at anything else, and honoured by
     * {@link #start()} both before and after it binds. Publishing this object
     * ahead of {@code start()} is NOT enough on its own: a {@code close()} that
     * runs first sees a null {@link #server}, no-ops, and {@code start()} then
     * goes on to bind a listener nobody will ever stop.
     */
    private volatile boolean closed;

    /** The traffic log, for the Review activity panel. */
    public McpActivityLog activityLog() {
        return activityLog;
    }

    public McpServer(McpSessionRegistry registry, McpToolRouter router) {
        this(registry, router, new McpActivityLog());
    }

    public McpServer(McpSessionRegistry registry, McpToolRouter router, McpActivityLog activityLog) {
        this.activityLog = activityLog;
        this.registry = registry;
        this.router = router;
    }

    /** A no-op once {@link #close()} has been called; never binds after shutdown. */
    public void start() throws IOException {
        if (closed) {
            return;
        }
        HttpServer created = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService createdExecutor = Executors.newVirtualThreadPerTaskExecutor();
        created.setExecutor(createdExecutor);
        created.createContext(PATH, new McpHandler());
        server = created;
        executor = createdExecutor;
        created.start();
        port = created.getAddress().getPort();
        if (closed) {
            // close() ran while this method was binding: it either saw the
            // fields (and stopped what it found) or did not, so undo it here.
            // close() is idempotent, which makes the double-stop harmless.
            close();
        }
    }

    public int port() {
        return port;
    }

    public String endpointUrl() {
        return "http://127.0.0.1:" + port + PATH;
    }

    /** The socket's actual bound address, for tests that must not trust a literal-built string. */
    public InetSocketAddress boundAddress() {
        return server.getAddress();
    }

    /**
     * Null-safe and idempotent: safe to call before {@link #start()} and safe
     * to call twice. Calling it before {@code start()} also permanently
     * prevents that start from binding.
     *
     * <p>Drains in-flight handlers before returning. {@code stop(0)} closes the
     * listener but does not wait for exchanges already being handled, and
     * {@code shutdownNow()} only <em>attempts</em> interruption -- so without a
     * bounded await, a handler blocked in {@code WorkspaceMcpSessionContext}'s
     * {@code join} could still be touching {@code SessionManager} while
     * {@code DrydockApplication.stop()} closes it on the next line. Mirrors
     * {@code SessionManager.close}'s bounded-drain pattern.</p>
     */
    @Override
    public void close() {
        closed = true;
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(CLOSE_AWAIT_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
                    LOG.log(Level.WARNING, "MCP request handlers did not drain within "
                            + CLOSE_AWAIT_TERMINATION_SECONDS + "s; forcing shutdown");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
            executor = null;
        }
    }

    @Override
    public String toString() {
        // Never the port or a token -- see class Javadoc and McpSessionRegistry's.
        return getClass().getSimpleName();
    }

    private final class McpHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                handleSafely(exchange);
            } catch (Exception e) {
                // One bad request must not kill the server or this connection.
                LOG.log(Level.WARNING, "Unhandled error serving MCP request", e);
                sendJson(exchange, 200, errorResponse(JsonValue.JsonNull.INSTANCE, -32603, "Internal error"));
            } finally {
                exchange.close();
            }
        }

        private void handleSafely(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendEmpty(exchange, 405);
                return;
            }

            if (!originAllowed(exchange.getRequestHeaders().getFirst("Origin"))) {
                sendEmpty(exchange, 403);
                return;
            }
            if (!originAllowed(exchange.getRequestHeaders().getFirst("Host"))) {
                sendEmpty(exchange, 403);
                return;
            }

            String presentedToken = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
            Optional<ManagedSessionId> caller = registry.resolve(presentedToken);
            if (caller.isEmpty()) {
                sendEmpty(exchange, 401);
                return;
            }

            String body = readBody(exchange.getRequestBody());
            JsonValue parsed;
            try {
                parsed = JsonParser.parse(body);
            } catch (JsonParseException e) {
                sendJson(exchange, 200, errorResponse(JsonValue.JsonNull.INSTANCE, -32700, "Parse error"));
                return;
            }

            if (!(parsed instanceof JsonObject request)) {
                sendJson(exchange, 200, errorResponse(JsonValue.JsonNull.INSTANCE, -32700, "Parse error"));
                return;
            }

            // Notification first, before any method dispatch: a request with no
            // "id" is a notification (e.g. notifications/initialized, sent by
            // claude right after initialize) and must never receive an error
            // object, whatever its method -- see class and package Javadoc.
            JsonValue id = request.get("id");
            if (id == null) {
                sendEmpty(exchange, 204);
                return;
            }

            String method = request.get("method") instanceof JsonString s ? s.value() : null;
            JsonValue params = request.get("params");

            sendJson(exchange, 200, dispatch(caller.get(), method, params, id));
        }

        private JsonValue dispatch(ManagedSessionId caller, String method, JsonValue params, JsonValue id) {
            return switch (method == null ? "" : method) {
                case "initialize" -> successResponse(id, initializeResult(params));
                case "ping" -> successResponse(id, JsonObject.empty());
                case "tools/list" -> successResponse(id, toolsListResult());
                case "tools/call" -> toolsCall(caller, params, id);
                default -> errorResponse(id, -32601, "Method not found: " + method);
            };
        }

        private JsonValue toolsCall(ManagedSessionId caller, JsonValue params, JsonValue id) {
            JsonObject args = params instanceof JsonObject object ? object : JsonObject.empty();
            JsonValue nameValue = args.get("name");
            if (!(nameValue instanceof JsonString nameString)) {
                // The router's dispatch is a plain String switch with no `case
                // null`, so passing a missing/non-string name through would NPE
                // and degrade into a -32603 internal error. Missing tool name is
                // a predictable bad argument, not an internal failure.
                return successResponse(id, toolCallResult(new JsonString("Missing required \"name\""), true));
            }
            String name = nameString.value();
            JsonValue arguments = args.get("arguments");

            try {
                JsonValue toolResult = router.call(caller, name, arguments);
                logActivity(name, arguments, toolResult, false);
                return successResponse(id, toolCallResult(toolResult, false));
            } catch (McpToolException e) {
                // A tool failure is not a transport failure: it comes back as a
                // 200 JSON-RPC result with isError: true, so the agent can read
                // and act on the message rather than the transport swallowing it.
                logActivity(name, arguments, new JsonString(e.getMessage()), true);
                return successResponse(id, toolCallResult(new JsonString(e.getMessage()), true));
            }
        }

        /**
         * Records one call in the activity log the Review panel renders.
         * Never allowed to affect the call: a logging failure is not a tool
         * failure, and the agent must get its answer either way.
         */
        private void logActivity(String tool, JsonValue arguments, JsonValue result, boolean failed) {
            try {
                int bytes = JsonWriter.write(result)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                Optional<String> scopeId = arguments instanceof JsonObject args
                        && args.get("scopeId") instanceof JsonString scope
                        ? Optional.of(scope.value())
                        : Optional.empty();
                activityLog.record(new McpActivityLog.Entry(Instant.now(),
                        directionOf(tool),
                        tool, McpServer.summarize(arguments), scopeId, bytes, failed));
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "Could not log MCP activity for " + tool, e);
            }
        }

        /**
         * Injected into the hosted agent's system prompt by the client. This
         * is the only thing that makes a tool get called without the agent
         * already hunting for one -- a tool description is read at selection
         * time, not at the moment the agent learns what its work is.
         */
        private static final String INSTRUCTIONS = """
                Drydock hosts this session in a tab a human is watching. As soon as you know what \
                the work actually is, call session_rename with a short title naming the work -- not \
                the branch. Re-title it if the work turns out to be something else. Two refusals are \
                normal and both explain themselves: if the human has named the session, leave it \
                alone; if another session here already has that title, pick one that tells them apart.\
                """;

        private JsonValue initializeResult(JsonValue params) {
            return JsonObject.empty()
                    .put("protocolVersion", new JsonString(negotiatedProtocolVersion(params)))
                    .put("capabilities", JsonObject.empty().put("tools", JsonObject.empty()))
                    .put("serverInfo", JsonObject.empty()
                            .put("name", new JsonString("drydock"))
                            .put("version", new JsonString("1.0.0")))
                    .put("instructions", new JsonString(INSTRUCTIONS));
        }

        /**
         * The client's requested version when this server supports it,
         * otherwise {@link #FALLBACK_PROTOCOL_VERSION}. Per the MCP spec the
         * server echoes a version it supports and names its own preferred one
         * when it does not -- and the client is then free to disconnect.
         */
        private String negotiatedProtocolVersion(JsonValue params) {
            if (params instanceof JsonObject object
                    && object.get("protocolVersion") instanceof JsonString requested
                    && SUPPORTED_PROTOCOL_VERSIONS.contains(requested.value())) {
                return requested.value();
            }
            return FALLBACK_PROTOCOL_VERSION;
        }

        private JsonValue toolsListResult() {
            List<JsonValue> descriptors = router.toolDescriptors();
            return JsonObject.empty().put("tools", new JsonArray(descriptors));
        }

        private JsonValue toolCallResult(JsonValue content, boolean isError) {
            JsonValue textContent;
            if (isError) {
                textContent = content;
            } else {
                textContent = new JsonString(JsonWriter.write(content));
            }
            JsonArray contentArray = new JsonArray(List.of(
                    JsonObject.empty()
                            .put("type", new JsonString("text"))
                            .put("text", textContent)));
            return JsonObject.empty()
                    .put("content", contentArray)
                    .put("isError", new JsonBoolean(isError));
        }

        private boolean originAllowed(String presented) {
            if (presented == null || presented.isEmpty()) {
                return true;
            }
            return presented.equals("http://127.0.0.1:" + port) || presented.equals("http://localhost:" + port)
                    || presented.equals("127.0.0.1:" + port) || presented.equals("localhost:" + port);
        }

        private String readBody(InputStream in) throws IOException {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        private void sendJson(HttpExchange exchange, int status, JsonValue value) throws IOException {
            byte[] bytes = JsonWriter.write(value).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        private void sendEmpty(HttpExchange exchange, int status) throws IOException {
            exchange.sendResponseHeaders(status, -1);
        }
    }

    private static JsonValue successResponse(JsonValue id, JsonValue result) {
        return JsonObject.empty()
                .put("jsonrpc", new JsonString("2.0"))
                .put("id", id)
                .put("result", result);
    }

    private static JsonValue errorResponse(JsonValue id, int code, String message) {
        return JsonObject.empty()
                .put("jsonrpc", new JsonString("2.0"))
                .put("id", id)
                .put("error", JsonObject.empty()
                        .put("code", JsonNumber.of(code))
                        .put("message", new JsonString(message)));
    }

    /**
     * The tools that WRITE into drydock. Everything else is drydock answering
     * a question, including the tools whose names begin "review_".
     *
     * <p>An explicit set rather than the name-prefix rule this replaced: that
     * rule classified {@code review_comments} as a write, which it is not --
     * the agent asks for open threads and drydock answers -- and it would
     * have classified {@code session_rename} as a read. A prefix cannot
     * express either, and every tool added later must be classified on
     * purpose rather than by how it was named.</p>
     */
    private static final Set<String> AGENT_WRITE_TOOLS = Set.of(
            "review_reply", "review_intents", "review_finding", "review_answer", "session_rename");

    static McpActivityLog.Direction directionOf(String tool) {
        return AGENT_WRITE_TOOLS.contains(tool)
                ? McpActivityLog.Direction.INBOUND
                : McpActivityLog.Direction.OUTBOUND;
    }

    /**
     * A one-line detail for the panel: the arguments, bounded and stripped of
     * anything that can lie in a Label.
     *
     * <p>Order matters. The whitespace collapse runs FIRST: Java's {@code \s}
     * is ASCII-only, so it flattens {@link JsonWriter}'s own pretty-print
     * newlines and indents and nothing else. Only then are the invisible and
     * control categories replaced -- sanitizing first would replace those
     * structural newlines too and turn every row into "{&#xFFFD; ...".
     *
     * <p>{@code JsonWriter} escapes only {@code c < 0x20}, so U+007F, the C1
     * block, the bidi overrides and the tag block all arrive here verbatim.
     * The panel renders this as a {@code Label}, on failed calls as well as
     * successful ones, so this is the boundary that has to remove them.
     */
    static String summarize(JsonValue arguments) {
        if (arguments == null) {
            return "";
        }
        String collapsed = JsonWriter.write(arguments).replaceAll("\\s+", " ");
        StringBuilder clean = new StringBuilder(collapsed.length());
        collapsed.codePoints().forEach(cp -> clean.appendCodePoint(isUnrenderable(cp) ? '�' : cp));
        return truncateByCodePoints(clean.toString(), 160);
    }

    /** Categories that must never reach a Label: invisible, reordering, or not a character at all. */
    private static boolean isUnrenderable(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                || type == Character.PRIVATE_USE || type == Character.UNASSIGNED
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }

    /** Truncates on a code-point boundary, so a cut never re-creates a lone surrogate. */
    private static String truncateByCodePoints(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxCodePoints - 1);
        return text.substring(0, end) + "…";
    }
}
