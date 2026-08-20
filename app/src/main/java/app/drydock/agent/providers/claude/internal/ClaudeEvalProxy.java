package app.drydock.agent.providers.claude.internal;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Talks to the local omlx_proxy that sits between Claude Code and the AI
 * gateway, to route an eval session's traffic to the eval account.
 *
 * <p>Claude Code's {@code ANTHROPIC_*} env is locked to user/managed scope, so
 * drydock cannot inject an {@code x-target-account: eval} header (or a custom
 * base URL, or an auth token) per session. But claude is already proxied
 * through omlx_proxy at {@code 127.0.0.1:4000}, and every request -- parent and
 * sub-agent -- carries {@code x-claude-code-session-id}, which equals the
 * {@code --session-id} drydock passes. omlx_proxy can therefore add the header
 * for a session id drydock marks here, covering the whole session including
 * model-pinned subagents (the {@code --model} sentinel could not).</p>
 *
 * <p>All methods are blocking and must be called off the JavaFX application
 * thread. {@link #probe} is run once at provider init (background) and its
 * result cached, so the UI's {@code evalAvailable()} read never blocks.</p>
 */
public final class ClaudeEvalProxy {

    private static final Logger LOG = System.getLogger(ClaudeEvalProxy.class.getName());

    /** Where omlx_proxy listens. Overridable for tests / non-standard setups. */
    private static final String BASE_URL = System.getProperty(
            "app.drydock.eval.claude.proxyBaseUrl", "http://127.0.0.1:4000");

    private static final String HEADER = "x-target-account";
    private static final String HEADER_VALUE = "eval";

    private final String baseUrl;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    public ClaudeEvalProxy() {
        this(BASE_URL);
    }

    /** For tests: an explicit base URL. */
    ClaudeEvalProxy(String baseUrl) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl").endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** The header omlx_proxy injects for a marked session. */
    public static String headerName() {
        return HEADER;
    }

    public static String headerValue() {
        return HEADER_VALUE;
    }

    /**
     * Whether omlx_proxy is reachable and answering its health endpoint.
     * Best-effort: any failure (refused, timeout, non-OK, malformed) is
     * {@code false}, so eval mode is disabled rather than half-working.
     */
    public boolean probe() {
        try {
            HttpResponse<String> res = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return false;
            }
            // The body is JSON with "status":"ok"; accept it loosely.
            return res.body().contains("\"status\":\"ok\"")
                    || res.body().contains("\"status\": \"ok\"");
        } catch (Exception e) {
            LOG.log(Level.DEBUG, () -> "omlx_proxy probe failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Marks {@code sessionKey} as eval. Idempotent on the proxy side. Never
     * throws: a failure only means the eval header will not be injected for
     * this session (degraded, not fatal).
     */
    public void mark(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        post("/omlx/eval-session", "{\"session_id\":\"" + jsonEscape(sessionKey) + "\"}");
    }

    /** Reverses {@link #mark}. Idempotent; never throws. */
    public void unmark(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        try {
            URI uri = URI.create(baseUrl + "/omlx/eval-session?session_id="
                    + URLEncoder.encode(sessionKey, StandardCharsets.UTF_8));
            client.send(HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(2)).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            LOG.log(Level.DEBUG, () -> "omlx_proxy unmark failed for " + sessionKey + ": " + e.getMessage());
        }
    }

    private void post(String path, String jsonBody) {
        try {
            client.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                            .header("content-type", "application/json")
                            .timeout(Duration.ofSeconds(2))
                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            LOG.log(Level.WARNING, () -> "omlx_proxy mark failed (" + path + "): " + e.getMessage());
        }
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}