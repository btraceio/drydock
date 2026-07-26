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
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpSessionRegistry registry;
    private final McpToolRouter router;

    private HttpServer server;
    private ExecutorService executor;
    private int port;

    public McpServer(McpSessionRegistry registry, McpToolRouter router) {
        this.registry = registry;
        this.router = router;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext(PATH, new McpHandler());
        server.start();
        port = server.getAddress().getPort();
    }

    public int port() {
        return port;
    }

    public String endpointUrl() {
        return "http://127.0.0.1:" + port + PATH;
    }

    /** Null-safe and idempotent: safe to call before {@link #start()} and safe to call twice. */
    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
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
            // The Host header cannot be set through java.net.http.HttpClient (it is
            // restricted), so this branch is exercised by the manual checklist, not
            // by McpServerTest -- the test substitutes X-Forwarded-Host instead.
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

            JsonValue result = dispatch(caller.get(), method, params, id);
            if (result != null) {
                sendJson(exchange, 200, result);
            }
        }

        private JsonValue dispatch(ManagedSessionId caller, String method, JsonValue params, JsonValue id) {
            return switch (method == null ? "" : method) {
                case "initialize" -> successResponse(id, initializeResult());
                case "ping" -> successResponse(id, JsonObject.empty());
                case "tools/list" -> successResponse(id, toolsListResult());
                case "tools/call" -> toolsCall(caller, params, id);
                default -> errorResponse(id, -32601, "Method not found: " + method);
            };
        }

        private JsonValue toolsCall(ManagedSessionId caller, JsonValue params, JsonValue id) {
            JsonObject args = params instanceof JsonObject object ? object : JsonObject.empty();
            String name = args.get("name") instanceof JsonString s ? s.value() : null;
            JsonValue arguments = args.get("arguments");

            try {
                JsonValue toolResult = router.call(caller, name, arguments);
                return successResponse(id, toolCallResult(toolResult, false));
            } catch (McpToolException e) {
                // A tool failure is not a transport failure: it comes back as a
                // 200 JSON-RPC result with isError: true, so the agent can read
                // and act on the message rather than the transport swallowing it.
                return successResponse(id, toolCallResult(new JsonString(e.getMessage()), true));
            }
        }

        private JsonValue initializeResult() {
            return JsonObject.empty()
                    .put("protocolVersion", new JsonString(PROTOCOL_VERSION))
                    .put("capabilities", JsonObject.empty().put("tools", JsonObject.empty()))
                    .put("serverInfo", JsonObject.empty()
                            .put("name", new JsonString("drydock"))
                            .put("version", new JsonString("1.0.0")));
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
}
