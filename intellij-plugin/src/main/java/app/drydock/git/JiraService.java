package app.drydock.git;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * READ-ONLY queries against a Jira site's search API: the unresolved issues
 * that a registered project's Issues section lists, and nothing else. This
 * service never transitions, comments on, assigns or creates anything -- it
 * only observes, exactly as {@link GhCliService} observes GitHub. Writes are
 * a hand-off's job, in the agent, not the tree's.
 *
 * <p>Same three-outcome shape as {@code GhCliService.IssueListing} through
 * {@link JiraIssueListing}: a project with no Jira configured is
 * {@link JiraIssueListing.Unsupported Unsupported} and stays silent, while a
 * configured site that does not answer is
 * {@link JiraIssueListing.Failed Failed} and says so with something
 * actionable. A failed query never looks like an empty tracker.</p>
 *
 * <p>The {@link HttpClient.Builder} is supplied rather than owned, so the
 * plugin glue can hand in the IDE's proxy selector, certificate manager and
 * (proxy-only) authenticator; core itself stays platform-free. Redirects are
 * never followed, so the {@code Authorization} header cannot travel to a
 * different site.</p>
 */
public final class JiraService implements AutoCloseable {

    private static final Logger LOG = System.getLogger(JiraService.class.getName());

    /** The connection budget; a wedged Jira must not park the future forever. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** The exchange budget, shorter than the tree's own wait for this future. */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** One bounded page, the same cap as the GitHub issue listing. */
    public static final int MAX_RESULTS = GhCliService.ISSUE_LIST_LIMIT;

    /** What a 200 response asks for: the row the tree renders, and nothing more. */
    private static final String FIELDS = "summary,labels,updated";

    /** The mask every secret-bearing value is replaced with before reuse. */
    private static final String REDACTED = "***";

    private final Supplier<HttpClient.Builder> clientBuilder;
    private final Duration requestTimeout;
    private final boolean allowLoopbackHttp;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private HttpClient client;

    /** Production client: the glue's builder, a 10 s request budget, no plain http. */
    public JiraService(Supplier<HttpClient.Builder> clientBuilder) {
        this(clientBuilder, REQUEST_TIMEOUT, false);
    }

    /**
     * Test client. {@code allowLoopbackHttp} lets the loopback test server
     * serve plain {@code http}; it exists only so the HTTP behaviour itself
     * is testable -- real tokens must never travel unencrypted, so production
     * keeps it off.
     */
    JiraService(Supplier<HttpClient.Builder> clientBuilder, Duration requestTimeout, boolean allowLoopbackHttp) {
        this.clientBuilder = Objects.requireNonNull(clientBuilder, "clientBuilder");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.allowLoopbackHttp = allowLoopbackHttp;
    }

    /**
     * The project's unresolved Jira issues, or {@link JiraIssueListing.Unsupported}
     * when the project has no Jira connection at all -- no request is sent and
     * the secret supplier is never touched in that case. The secret is read
     * on this service's executor, never by the caller's thread.
     */
    public CompletableFuture<JiraIssueListing> openIssues(Optional<JiraConnection> connection,
                                                          Supplier<Optional<String>> secret) {
        if (connection.isEmpty()) {
            return CompletableFuture.completedFuture(new JiraIssueListing.Unsupported());
        }
        JiraConnection c = connection.get();
        return CompletableFuture.supplyAsync(() -> openIssuesBlocking(c, secret), executor);
    }

    private JiraIssueListing openIssuesBlocking(JiraConnection connection, Supplier<Optional<String>> secret) {
        String baseUrl = connection.baseUrl();
        Optional<String> refusal = JiraConnection.baseUrlRefusal(baseUrl);
        if (refusal.isPresent() && !loopbackHttpAllowed(baseUrl)) {
            return new JiraIssueListing.Failed(refusal.get());
        }
        if (connection.deployment() == JiraConnection.Deployment.CLOUD && connection.email().isEmpty()) {
            return new JiraIssueListing.Failed("Jira Cloud needs the account email; open Jira Settings");
        }
        String secretValue = secret.get().orElse("");
        if (secretValue.isBlank()) {
            return new JiraIssueListing.Failed("No Jira API token is stored for this project; open Jira Settings");
        }
        String auth = authorization(connection, secretValue);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(searchUrl(connection)))
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .header("Authorization", auth)
                    .GET()
                    .build();
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
            return answer(secretValue, auth, baseUrl, response);
        } catch (HttpTimeoutException e) {
            LOG.log(Level.WARNING, "Jira search at " + baseUrl + " failed (" + e.getClass().getSimpleName() + "): "
                    + redact(String.valueOf(e.getMessage()), secretValue, auth));
            return new JiraIssueListing.Failed(redact(
                    "Jira did not answer within " + timeoutSeconds() + "s", secretValue, auth));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Jira search at " + baseUrl + " failed (" + e.getClass().getSimpleName() + "): "
                    + redact(String.valueOf(e.getMessage()), secretValue, auth));
            return new JiraIssueListing.Failed(redact(
                    "Could not reach Jira at " + baseUrl + " (" + e.getClass().getSimpleName() + ")",
                    secretValue, auth));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, "Jira search at " + baseUrl + " failed (" + e.getClass().getSimpleName() + "): "
                    + redact(String.valueOf(e.getMessage()), secretValue, auth));
            return new JiraIssueListing.Failed(redact("Jira lookup was interrupted", secretValue, auth));
        } catch (IllegalArgumentException e) {
            // Unreachable after the https / loopback check above; the refusal
            // message still says why an address could not become a request.
            LOG.log(Level.WARNING, "Jira search at " + baseUrl + " failed (" + e.getClass().getSimpleName() + "): "
                    + redact(String.valueOf(e.getMessage()), secretValue, auth));
            return new JiraIssueListing.Failed(JiraConnection.baseUrlRefusal(baseUrl)
                    .orElse("Use the site's https:// address, for example https://your-site.atlassian.net"));
        }
    }

    /** A non-200 answer mapped to an actionable failure; only a 200 is parsed. */
    private JiraIssueListing answer(String secret, String auth, String baseUrl, HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 200) {
            return JiraSearchResponse.parse(response.body(), baseUrl);
        }
        JiraIssueListing.Failed failure = statusFailure(status, response.headers(), response.body(), baseUrl);
        LOG.log(Level.WARNING, "Jira search at " + baseUrl + " answered HTTP " + status);
        return new JiraIssueListing.Failed(redact(failure.message(), secret, auth));
    }

    /**
     * The failure a non-200 status maps to. 401/403 name the credentials and
     * permissions; a CAPTCHA-challenged site says so, because only a browser
     * sign-in clears it; 404 names the endpoint choice; 400 carries Jira's own
     * JQL complaint. No status ever maps to an empty listing.
     */
    static JiraIssueListing.Failed statusFailure(int status, HttpHeaders headers, String body, String baseUrl) {
        if (status == 401 || status == 403) {
            if (headers.firstValue("X-Authentication-Denied-Reason")
                    .map(value -> value.toUpperCase(Locale.ROOT).contains("CAPTCHA"))
                    .orElse(false)) {
                return new JiraIssueListing.Failed("Jira requires a browser sign-in (CAPTCHA) after failed logins;"
                        + " sign in at " + baseUrl + ", then Refresh");
            }
            return new JiraIssueListing.Failed("Jira refused the credentials (HTTP " + status + "). Check the"
                    + " account email and API token (Cloud) or the personal access token (Data Center),"
                    + " and that the account can browse the project");
        }
        if (status == 404) {
            return new JiraIssueListing.Failed("Jira search endpoint not found (HTTP 404)."
                    + " Check the base URL and the Cloud / Data Center choice");
        }
        if (status == 400) {
            return JiraSearchResponse.errorMessages(body)
                    .map(messages -> new JiraIssueListing.Failed("Jira rejected the query: " + messages))
                    .orElseGet(() -> new JiraIssueListing.Failed("Jira rejected the query (HTTP 400)"));
        }
        if (status == 429) {
            return new JiraIssueListing.Failed("Jira rate-limited the request (HTTP 429); try Refresh later");
        }
        if (status >= 500 && status < 600) {
            return new JiraIssueListing.Failed("Jira server error (HTTP " + status + ")");
        }
        if (status >= 300 && status < 400) {
            return new JiraIssueListing.Failed("Jira redirected the request (HTTP " + status + ");"
                    + " use the site's final https:// address");
        }
        return new JiraIssueListing.Failed("Jira answered HTTP " + status);
    }

    /**
     * The {@code Authorization} value for one deployment: Cloud pairs the
     * account email with the API token in HTTP Basic, Data Center presents
     * the PAT as a bearer token.
     */
    static String authorization(JiraConnection connection, String secret) {
        if (connection.deployment() == JiraConnection.Deployment.CLOUD) {
            String credentials = connection.email().orElse("") + ":" + secret;
            return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }
        return "Bearer " + secret;
    }

    /**
     * Replaces the secret and the whole {@code Authorization} value with
     * {@code ***} wherever they appear: neither may reach a tree row or a log
     * line, and a defensive pass over every failure message catches a value
     * smuggled in through an exception or a response body.
     */
    static String redact(String text, String secret, String authHeader) {
        String result = text;
        if (authHeader != null && !authHeader.isBlank()) {
            result = result.replace(authHeader, REDACTED);
        }
        if (secret != null && !secret.isBlank()) {
            result = result.replace(secret, REDACTED);
        }
        return result;
    }

    private static String searchUrl(JiraConnection connection) {
        return connection.baseUrl() + connection.deployment().searchPath()
                + "?jql=" + URLEncoder.encode(connection.jql(), StandardCharsets.UTF_8)
                + "&maxResults=" + MAX_RESULTS
                + "&fields=" + FIELDS;
    }

    /** The request budget in whole seconds, so a sub-second test budget still reads honestly. */
    private long timeoutSeconds() {
        return Math.max(1, (requestTimeout.toMillis() + 999) / 1000);
    }

    /**
     * Whether the plain-http loopback exception applies. Real sites must be
     * https; the exception exists so the loopback test server can run without
     * TLS and covers only {@code http://127.0.0.1} and {@code http://localhost}.
     */
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

    /**
     * The shared client, built once on this service's executor thread so the
     * (possibly platform-configuring) builder supplier never runs on a caller's
     * thread. Redirects are refused: the credentials in the
     * {@code Authorization} header must not follow a redirect to another site.
     */
    private synchronized HttpClient client() {
        if (client == null) {
            client = clientBuilder.get()
                    .connectTimeout(CONNECT_TIMEOUT)
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
        executor.shutdown();
    }
}
