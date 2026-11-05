package app.drydock.git;

import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Creates the Jira ticket a Drydock task is mirrored into, and stores the
 * task's metadata on it as an issue property. The only Jira write in the
 * app; {@link JiraService} stays read-only. It shares that class's rules:
 * https only, the secret only in the {@code Authorization} header, every
 * message redacted, redirects never followed, every request bounded.
 *
 * <p>Both deployments are served by the v2 issue API
 * ({@code POST /rest/api/2/issue} and
 * {@code PUT /rest/api/2/issue/<key>/properties/<property>}), which takes a
 * wiki-markup description string on Cloud and Data Center alike. Blocking:
 * the caller runs it off the EDT.</p>
 */
public final class JiraIssueWriter implements AutoCloseable {

    private static final Logger LOG = System.getLogger(JiraIssueWriter.class.getName());

    /** A project key as Jira accepts it: an uppercase letter, then uppercase letters, digits or underscores. */
    private static final Pattern PROJECT_KEY = Pattern.compile("[A-Z][A-Z0-9_]{0,254}");

    /** What creating the ticket resolved to. */
    public sealed interface Outcome permits Created, Failed {
    }

    /**
     * The ticket exists at {@code url}. {@code propertyProblem} says why the
     * metadata property could not be stored, when it could not; the ticket
     * itself is not undone for that.
     */
    public record Created(String key, String url, Optional<String> propertyProblem) implements Outcome {
        public Created {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(propertyProblem, "propertyProblem");
        }
    }

    /** Nothing was created, or whether it was is unknown; the message says which. */
    public record Failed(String message) implements Outcome {
        public Failed {
            Objects.requireNonNull(message, "message");
        }
    }

    private final Supplier<HttpClient.Builder> clientBuilder;
    private final Duration requestTimeout;
    private final boolean allowLoopbackHttp;
    private HttpClient client;

    /** Production writer: the glue's builder, the read service's request budget, no plain http. */
    public JiraIssueWriter(Supplier<HttpClient.Builder> clientBuilder) {
        this(clientBuilder, JiraService.REQUEST_TIMEOUT, false);
    }

    /** Test writer; {@code allowLoopbackHttp} exists only for the loopback test server. */
    JiraIssueWriter(Supplier<HttpClient.Builder> clientBuilder, Duration requestTimeout, boolean allowLoopbackHttp) {
        this.clientBuilder = Objects.requireNonNull(clientBuilder, "clientBuilder");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.allowLoopbackHttp = allowLoopbackHttp;
    }

    /** The project-key refusal, or empty when {@code key} is usable. Pure. */
    public static Optional<String> projectKeyRefusal(String key) {
        return key != null && PROJECT_KEY.matcher(key).matches() ? Optional.empty()
                : Optional.of("A Jira project key is uppercase letters, digits and underscores, e.g. PROJ");
    }

    /**
     * Creates one ticket in {@code projectKey} of type {@code issueType},
     * then stores {@code property} under {@code propertyKey} on it.
     */
    public Outcome create(JiraConnection connection, String secret, String projectKey, String issueType,
                          String summary, String description, String propertyKey, JsonValue property) {
        Objects.requireNonNull(connection, "connection");
        String baseUrl = connection.baseUrl();
        Optional<String> refusal = JiraConnection.baseUrlRefusal(baseUrl);
        if (refusal.isPresent() && !loopbackHttpAllowed(baseUrl)) {
            return new Failed(refusal.get());
        }
        if (connection.deployment() == JiraConnection.Deployment.CLOUD && connection.email().isEmpty()) {
            return new Failed("Jira Cloud needs the account email; open Jira Settings");
        }
        if (secret == null || secret.isBlank()) {
            return new Failed("No Jira API token is stored for this project; open Jira Settings");
        }
        Optional<String> keyRefusal = projectKeyRefusal(projectKey);
        if (keyRefusal.isPresent()) {
            return new Failed(keyRefusal.get());
        }
        if (issueType == null || issueType.isBlank()) {
            return new Failed("Name the Jira issue type, e.g. Task");
        }
        String auth = JiraService.authorization(connection, secret);
        String payload = JsonWriter.write(JsonObject.empty().put("fields", JsonObject.empty()
                .put("project", JsonObject.empty().put("key", new JsonString(projectKey)))
                .put("issuetype", JsonObject.empty().put("name", new JsonString(issueType.strip())))
                .put("summary", new JsonString(summary))
                .put("description", new JsonString(description))));
        HttpResponse<String> created;
        try {
            created = client().send(request(baseUrl + "/rest/api/2/issue", auth)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            log("create", baseUrl, e, secret, auth);
            return new Failed("Jira did not answer within " + timeoutSeconds() + "s; whether the ticket was"
                    + " created is unknown -- check " + projectKey + " before retrying");
        } catch (IOException e) {
            log("create", baseUrl, e, secret, auth);
            return new Failed(JiraService.redact("Could not reach Jira at " + baseUrl + " ("
                    + e.getClass().getSimpleName() + ")", secret, auth));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Failed("Creating the Jira ticket was interrupted; whether it was created is unknown"
                    + " -- check " + projectKey + " before retrying");
        }
        if (created.statusCode() != 201 && created.statusCode() != 200) {
            LOG.log(Level.WARNING, "Jira issue create at " + baseUrl + " answered HTTP " + created.statusCode());
            return new Failed(JiraService.redact(createFailure(created.statusCode(), created.body()), secret, auth));
        }
        Optional<String> key = createdKey(created.body());
        if (key.isEmpty()) {
            return new Failed("Jira answered without the new ticket's key; check " + projectKey
                    + " before retrying");
        }
        return new Created(key.get(), connection.browseUrl(key.get()),
                storeProperty(baseUrl, auth, secret, key.get(), propertyKey, property));
    }

    /** Stores the metadata; empty on success, else why it was not stored. */
    private Optional<String> storeProperty(String baseUrl, String auth, String secret, String key,
                                           String propertyKey, JsonValue property) {
        String url = baseUrl + "/rest/api/2/issue/" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "/properties/" + URLEncoder.encode(propertyKey, StandardCharsets.UTF_8);
        try {
            HttpResponse<String> response = client().send(request(url, auth)
                    .PUT(HttpRequest.BodyPublishers.ofString(JsonWriter.write(property), StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 || response.statusCode() == 201) {
                return Optional.empty();
            }
            LOG.log(Level.WARNING, "Jira issue property on " + key + " answered HTTP " + response.statusCode());
            return Optional.of("Jira did not store the task metadata on " + key + " (HTTP "
                    + response.statusCode() + ")");
        } catch (IOException e) {
            log("property", baseUrl, e, secret, auth);
            return Optional.of("Jira did not store the task metadata on " + key + " ("
                    + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("Storing the task metadata on " + key + " was interrupted");
        }
    }

    /**
     * The failure a non-success create status maps to. 400 carries Jira's
     * own field complaints (a wrong issue type, a required field); the rest
     * name the credentials, the permission or the site. Pure.
     */
    static String createFailure(int status, String body) {
        return switch (status) {
            case 400 -> JiraSearchResponse.errorMessages(body)
                    .map(messages -> "Jira refused the ticket: " + messages)
                    .orElse("Jira refused the ticket (HTTP 400)");
            case 401 -> "Jira refused the credentials (HTTP 401). Check the account email and API token (Cloud)"
                    + " or the personal access token (Data Center)";
            case 403 -> "Jira refused the request (HTTP 403); the account may not create issues in this project";
            case 404 -> "Jira issue endpoint not found (HTTP 404). Check the base URL";
            case 429 -> "Jira rate-limited the request (HTTP 429); try again later";
            default -> status >= 300 && status < 400
                    ? "Jira redirected the request (HTTP " + status + "); use the site's final https:// address"
                    : "Jira answered HTTP " + status;
        };
    }

    /** The {@code key} of a create answer, {@code {"id":"…","key":"PROJ-7","self":"…"}}. */
    static Optional<String> createdKey(String body) {
        try {
            if (JsonParser.parse(body) instanceof JsonObject obj
                    && obj.get("key") instanceof JsonString key && !key.value().isBlank()) {
                return Optional.of(key.value());
            }
        } catch (JsonParseException e) {
            LOG.log(Level.DEBUG, "Unparseable Jira issue create answer", e);
        }
        return Optional.empty();
    }

    private HttpRequest.Builder request(String url, String auth) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", auth);
    }

    private static void log(String step, String baseUrl, Exception e, String secret, String auth) {
        LOG.log(Level.WARNING, "Jira issue " + step + " at " + baseUrl + " failed (" + e.getClass().getSimpleName()
                + "): " + JiraService.redact(String.valueOf(e.getMessage()), secret, auth));
    }

    private long timeoutSeconds() {
        return Math.max(1, (requestTimeout.toMillis() + 999) / 1000);
    }

    private boolean loopbackHttpAllowed(String baseUrl) {
        if (!allowLoopbackHttp) {
            return false;
        }
        try {
            URI uri = URI.create(baseUrl);
            return "http".equals(uri.getScheme())
                    && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Built on first use, on the caller's (background) thread; redirects are refused. */
    private synchronized HttpClient client() {
        if (client == null) {
            client = clientBuilder.get()
                    .connectTimeout(JiraService.CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }
        return client;
    }

    @Override
    public synchronized void close() {
        if (client != null) {
            client.close();
        }
    }
}
