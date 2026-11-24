package app.drydock.git;

import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Creates the Jira ticket a Drydock task is mirrored into, stores the
 * task's metadata on it as an issue property, adds Blocks links between
 * tickets, and adds a label to an issue. The only Jira write in the
 * app; {@link JiraService} stays read-only. It shares that class's rules:
 * https only, the secret only in the {@code Authorization} header, every
 * message redacted, redirects never followed, every request bounded.
 *
 * <p>Both deployments are served by the v2 issue API: ticket creation
 * ({@code POST /rest/api/2/issue}), the metadata property
 * ({@code PUT /rest/api/2/issue/<key>/properties/<property>}), issue links
 * ({@code POST /rest/api/2/issueLink}) and the label edit
 * ({@code PUT /rest/api/2/issue/<key>} with an {@code update.labels} {@code
 * add} operation -- there is no {@code /labels} sub-resource), which takes a
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
    public record Created(String key, String url, Optional<String> propertyProblem)
            implements Outcome, CreateResult {
        public Created {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(propertyProblem, "propertyProblem");
        }
    }

    /** Nothing was created, or whether it was is unknown; the message says which. */
    public record Failed(String message) implements Outcome, CreateResult {
        public Failed {
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * What a create resolved to when the caller must tell an unknown effect
     * ({@link Uncertain}) from a confirmed failure. {@link Outcome} stays as
     * it was for existing callers; {@link #create} folds an uncertain result
     * into a {@link Failed} carrying the same check-the-tracker message.
     */
    public sealed interface CreateResult permits Created, Failed, Uncertain {
    }

    /**
     * Whether the ticket was created is unknown: the request was sent but
     * the answer never arrived (timeout, interrupt) or carried no usable
     * key. The message tells the user to check the tracker before retrying;
     * no key is fabricated and nothing is retried.
     */
    public record Uncertain(String message) implements CreateResult {
        public Uncertain {
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * One entry of an issue's {@code issuelinks}: the link type's name and
     * the key of the other issue, which Jira reports under either
     * {@code inwardIssue} or {@code outwardIssue} (exactly one is set).
     */
    public record LinkEntry(String typeName, Optional<String> inwardKey, Optional<String> outwardKey) {
        public LinkEntry {
            Objects.requireNonNull(typeName, "typeName");
            Objects.requireNonNull(inwardKey, "inwardKey");
            Objects.requireNonNull(outwardKey, "outwardKey");
        }
    }

    /** What reading an issue's links resolved to. */
    public sealed interface LinkLookup permits Links, LookupFailed {
    }

    /** A well-formed {@code issuelinks} array; an empty list is confirmed absence. */
    public record Links(List<LinkEntry> entries) implements LinkLookup {
        public Links {
            entries = List.copyOf(entries);
        }
    }

    /** The links could not be read or the answer was malformed; this is never absence. */
    public record LookupFailed(String message) implements LinkLookup {
        public LookupFailed {
            Objects.requireNonNull(message, "message");
        }
    }

    /** What creating one Blocks link resolved to. */
    public sealed interface LinkOutcome permits Linked, LinkFailed, LinkUncertain {
    }

    /** Jira accepted the link. */
    public record Linked() implements LinkOutcome {
    }

    /** Jira confirmed it did not create the link (or it was refused before sending). */
    public record LinkFailed(String message) implements LinkOutcome {
        public LinkFailed {
            Objects.requireNonNull(message, "message");
        }
    }

    /** The request was sent but whether the link exists is unknown; never retried. */
    public record LinkUncertain(String message) implements LinkOutcome {
        public LinkUncertain {
            Objects.requireNonNull(message, "message");
        }
    }

    /** What adding one label to an issue resolved to. */
    public sealed interface LabelOutcome permits Labeled, LabelFailed, LabelUncertain {
    }

    /** Jira accepted the label (or it was already present: the add is idempotent). */
    public record Labeled() implements LabelOutcome {
    }

    /** Jira confirmed it did not add the label (or it was refused before sending). */
    public record LabelFailed(String message) implements LabelOutcome {
        public LabelFailed {
            Objects.requireNonNull(message, "message");
        }
    }

    /** The request was sent but whether the label now exists is unknown; never retried here. */
    public record LabelUncertain(String message) implements LabelOutcome {
        public LabelUncertain {
            Objects.requireNonNull(message, "message");
        }
    }

    /** Which end of the relationship the queried issue is. */
    public enum Queried { PREREQUISITE, DEPENDENT }

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
        return create(connection, secret, projectKey, issueType, summary, description,
                propertyKey, property, Optional.empty());
    }

    /**
     * As {@link #create}, with the ticket's priority named by
     * {@code priorityName} when present -- the task's stored priority name,
     * sent verbatim so the ticket wears the same priority the task does. A
     * name the destination site does not know is Jira's refusal, surfaced
     * like any other create failure.
     */
    public Outcome create(JiraConnection connection, String secret, String projectKey, String issueType,
                          String summary, String description, String propertyKey, JsonValue property,
                          Optional<String> priorityName) {
        return switch (createChecked(connection, secret, projectKey, issueType, summary, description,
                propertyKey, property, priorityName)) {
            case Created created -> created;
            case Failed failed -> failed;
            case Uncertain uncertain -> new Failed(uncertain.message());
        };
    }

    /** As {@link #createChecked}, but an unknown effect is reported as {@link Uncertain}, never as a failure. */
    public CreateResult createChecked(JiraConnection connection, String secret, String projectKey,
                                      String issueType, String summary, String description,
                                      String propertyKey, JsonValue property) {
        return createChecked(connection, secret, projectKey, issueType, summary, description,
                propertyKey, property, Optional.empty());
    }

    /** As {@link #create}, but an unknown effect is reported as {@link Uncertain}, never as a failure. */
    public CreateResult createChecked(JiraConnection connection, String secret, String projectKey,
                                      String issueType, String summary, String description,
                                      String propertyKey, JsonValue property, Optional<String> priorityName) {
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
        JsonObject fields = JsonObject.empty()
                .put("project", JsonObject.empty().put("key", new JsonString(projectKey)))
                .put("issuetype", JsonObject.empty().put("name", new JsonString(issueType.strip())))
                .put("summary", new JsonString(summary))
                .put("description", new JsonString(description));
        priorityName.ifPresent(name -> fields.put("priority",
                JsonObject.empty().put("name", new JsonString(name))));
        String payload = JsonWriter.write(JsonObject.empty().put("fields", fields));
        HttpResponse<String> created;
        try {
            created = client().send(request(baseUrl + "/rest/api/2/issue", auth)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            log("create", baseUrl, e, secret, auth);
            return new Uncertain("Jira did not answer within " + timeoutSeconds() + "s; whether the ticket was"
                    + " created is unknown -- check " + projectKey + " before retrying");
        } catch (IOException e) {
            log("create", baseUrl, e, secret, auth);
            return new Failed(JiraService.redact("Could not reach Jira at " + baseUrl + " ("
                    + e.getClass().getSimpleName() + ")", secret, auth));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Uncertain("Creating the Jira ticket was interrupted; whether it was created is unknown"
                    + " -- check " + projectKey + " before retrying");
        }
        if (created.statusCode() != 201 && created.statusCode() != 200) {
            LOG.log(Level.WARNING, "Jira issue create at " + baseUrl + " answered HTTP " + created.statusCode());
            return new Failed(JiraService.redact(createFailure(created.statusCode(), created.body()), secret, auth));
        }
        Optional<String> key = createdKey(created.body());
        if (key.isEmpty()) {
            return new Uncertain("Jira answered without the new ticket's key; check " + projectKey
                    + " before retrying");
        }
        return new Created(key.get(), connection.browseUrl(key.get()),
                storeProperty(baseUrl, auth, secret, key.get(), propertyKey, property));
    }

    /**
     * Reads the links of {@code issueKey} on the site {@code connection}
     * addresses, with that connection's own credentials. A missing or
     * ill-typed {@code fields.issuelinks}, or an entry whose type or
     * direction cannot be determined, is a failure, never absence.
     */
    public LinkLookup lookupLinks(JiraConnection connection, String secret, String issueKey) {
        Optional<String> refusal = writeRefusal(connection, secret);
        if (refusal.isPresent()) {
            return new LookupFailed(refusal.get());
        }
        if (issueKey == null || issueKey.isBlank()) {
            return new LookupFailed("Name the Jira issue whose links to read");
        }
        String baseUrl = connection.baseUrl();
        String auth = JiraService.authorization(connection, secret);
        String url = baseUrl + "/rest/api/2/issue/" + URLEncoder.encode(issueKey, StandardCharsets.UTF_8)
                + "?fields=issuelinks";
        HttpResponse<String> response;
        try {
            response = client().send(request(url, auth).GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            log("link lookup", baseUrl, e, secret, auth);
            return new LookupFailed("Jira did not answer within " + timeoutSeconds() + "s reading the links of "
                    + issueKey);
        } catch (IOException e) {
            log("link lookup", baseUrl, e, secret, auth);
            return new LookupFailed(JiraService.redact("Could not reach Jira at " + baseUrl + " ("
                    + e.getClass().getSimpleName() + ")", secret, auth));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LookupFailed("Reading the links of " + issueKey + " was interrupted");
        }
        if (response.statusCode() != 200) {
            LOG.log(Level.WARNING, "Jira link lookup at " + baseUrl + " answered HTTP " + response.statusCode());
            return new LookupFailed(JiraService.redact("Could not read the links of " + issueKey + ": "
                    + createFailure(response.statusCode(), response.body()), secret, auth));
        }
        return parseLinks(response.body(), issueKey);
    }

    /**
     * Creates the Blocks link with {@code inwardIssue = prerequisite} and
     * {@code outwardIssue = dependent}, once, without retry. A missing
     * Blocks type, a 403 or any other status is a confirmed failure; a
     * timeout or interrupt after sending is uncertain.
     */
    public LinkOutcome createBlocksLink(JiraConnection connection, String secret, String prerequisiteKey,
                                        String dependentKey) {
        Optional<String> refusal = writeRefusal(connection, secret);
        if (refusal.isPresent()) {
            return new LinkFailed(refusal.get());
        }
        if (prerequisiteKey == null || prerequisiteKey.isBlank() || dependentKey == null
                || dependentKey.isBlank()) {
            return new LinkFailed("Both Jira issue keys are needed to link them");
        }
        String baseUrl = connection.baseUrl();
        String auth = JiraService.authorization(connection, secret);
        String payload = JsonWriter.write(JsonObject.empty()
                .put("type", JsonObject.empty().put("name", new JsonString("Blocks")))
                .put("inwardIssue", JsonObject.empty().put("key", new JsonString(prerequisiteKey)))
                .put("outwardIssue", JsonObject.empty().put("key", new JsonString(dependentKey))));
        HttpResponse<String> response;
        try {
            response = client().send(request(baseUrl + "/rest/api/2/issueLink", auth)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            log("link", baseUrl, e, secret, auth);
            return new LinkUncertain("Jira did not answer within " + timeoutSeconds() + "s; whether "
                    + prerequisiteKey + " now blocks " + dependentKey + " is unknown -- check the issue links"
                    + " before retrying");
        } catch (IOException e) {
            log("link", baseUrl, e, secret, auth);
            return new LinkFailed(JiraService.redact("Could not reach Jira at " + baseUrl + " ("
                    + e.getClass().getSimpleName() + ")", secret, auth));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LinkUncertain("Linking " + prerequisiteKey + " to " + dependentKey + " was interrupted;"
                    + " whether the link exists is unknown -- check the issue links before retrying");
        }
        if (response.statusCode() == 201 || response.statusCode() == 200) {
            return new Linked();
        }
        LOG.log(Level.WARNING, "Jira issue link at " + baseUrl + " answered HTTP " + response.statusCode());
        return new LinkFailed(JiraService.redact(linkFailure(response.statusCode(), response.body()), secret, auth));
    }

    /**
     * Adds {@code label} to {@code issueKey} on the site {@code connection}
     * addresses, once, without retry: {@code PUT
     * /rest/api/2/issue/<key>} with an {@code update.labels} {@code add}
     * operation, which leaves the issue's other labels untouched and is idempotent for a
     * label already present. A 403 or any other status is a confirmed
     * failure; a timeout or interrupt after sending is uncertain.
     */
    public LabelOutcome addLabel(JiraConnection connection, String secret, String issueKey, String label) {
        Optional<String> refusal = writeRefusal(connection, secret);
        if (refusal.isPresent()) {
            return new LabelFailed(refusal.get());
        }
        if (issueKey == null || issueKey.isBlank()) {
            return new LabelFailed("Name the Jira issue to label");
        }
        if (label == null || label.isBlank()) {
            return new LabelFailed("Name the label to add");
        }
        String baseUrl = connection.baseUrl();
        String auth = JiraService.authorization(connection, secret);
        String payload = JsonWriter.write(JsonObject.empty().put("update", JsonObject.empty()
                .put("labels", JsonArray.of(List.of(JsonObject.empty().put("add", new JsonString(label)))))));
        HttpResponse<String> response;
        try {
            response = client().send(request(baseUrl + "/rest/api/2/issue/"
                            + URLEncoder.encode(issueKey, StandardCharsets.UTF_8), auth)
                    .PUT(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            log("label", baseUrl, e, secret, auth);
            return new LabelUncertain("Jira did not answer within " + timeoutSeconds() + "s; whether " + issueKey
                    + " now carries the label " + label + " is unknown -- check the issue before retrying");
        } catch (IOException e) {
            log("label", baseUrl, e, secret, auth);
            return new LabelFailed(JiraService.redact("Could not reach Jira at " + baseUrl + " ("
                    + e.getClass().getSimpleName() + ")", secret, auth));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LabelUncertain("Labeling " + issueKey + " was interrupted; whether the label " + label
                    + " was added is unknown -- check the issue before retrying");
        }
        if (response.statusCode() == 204 || response.statusCode() == 200) {
            return new Labeled();
        }
        LOG.log(Level.WARNING, "Jira issue label at " + baseUrl + " answered HTTP " + response.statusCode());
        return new LabelFailed(JiraService.redact(labelFailure(response.statusCode(), response.body(), label),
                secret, auth));
    }

    private static String labelFailure(int status, String body, String label) {
        return switch (status) {
            case 400 -> JiraSearchResponse.errorMessages(body)
                    .map(messages -> "Jira refused the label " + label + ": " + messages)
                    .orElse("Jira refused the label " + label + " (HTTP 400)");
            case 403 -> "Jira refused the request (HTTP 403); the account may not edit this issue";
            case 404 -> "Jira did not find the issue (HTTP 404)";
            default -> createFailure(status, body);
        };
    }

    /**
     * Whether {@code entries}, read from the {@code queried} end, already
     * hold prerequisite -blocks-> dependent: from the prerequisite the other
     * issue must be the outward one and the dependent; from the dependent it
     * must be the inward one and the prerequisite. Another type or the
     * reverse direction is not presence. Pure.
     */
    public static boolean blocksPresent(List<LinkEntry> entries, Queried queried, String prerequisiteKey,
                                        String dependentKey) {
        for (LinkEntry entry : entries) {
            if (!"Blocks".equals(entry.typeName())) {
                continue;
            }
            boolean match = queried == Queried.PREREQUISITE
                    ? entry.outwardKey().filter(dependentKey::equals).isPresent()
                    : entry.inwardKey().filter(prerequisiteKey::equals).isPresent();
            if (match) {
                return true;
            }
        }
        return false;
    }

    /** The links of an {@code ?fields=issuelinks} answer; any structural doubt is a failure. Pure. */
    static LinkLookup parseLinks(String body, String issueKey) {
        String malformed = "Jira answered the links of " + issueKey + " in an unexpected shape";
        try {
            if (!(JsonParser.parse(body) instanceof JsonObject root)
                    || !(root.get("fields") instanceof JsonObject fields)
                    || !(fields.get("issuelinks") instanceof JsonArray array)) {
                return new LookupFailed(malformed);
            }
            List<LinkEntry> entries = new ArrayList<>();
            for (JsonValue element : array.elements()) {
                if (!(element instanceof JsonObject entry)
                        || !(entry.get("type") instanceof JsonObject type)
                        || !(type.get("name") instanceof JsonString name) || name.value().isBlank()) {
                    return new LookupFailed(malformed);
                }
                Optional<String> inward = issueKeyOf(entry.get("inwardIssue"));
                Optional<String> outward = issueKeyOf(entry.get("outwardIssue"));
                if (inward.isPresent() == outward.isPresent()) {
                    return new LookupFailed(malformed);
                }
                entries.add(new LinkEntry(name.value(), inward, outward));
            }
            return new Links(entries);
        } catch (JsonParseException e) {
            LOG.log(Level.DEBUG, "Unparseable Jira issue links answer", e);
            return new LookupFailed(malformed);
        }
    }

    private static Optional<String> issueKeyOf(JsonValue value) {
        return value instanceof JsonObject obj && obj.get("key") instanceof JsonString key
                && !key.value().isBlank() ? Optional.of(key.value()) : Optional.empty();
    }

    private static String linkFailure(int status, String body) {
        return switch (status) {
            case 400 -> JiraSearchResponse.errorMessages(body)
                    .map(messages -> "Jira refused the link: " + messages)
                    .orElse("Jira refused the link (HTTP 400); the Blocks link type may not exist");
            case 403 -> "Jira refused the request (HTTP 403); the account may not link issues in this project";
            case 404 -> "Jira did not find the issues or the Blocks link type (HTTP 404)";
            default -> createFailure(status, body);
        };
    }

    /** The refusal for a connection/secret not usable for a write, or empty. */
    private Optional<String> writeRefusal(JiraConnection connection, String secret) {
        Objects.requireNonNull(connection, "connection");
        String baseUrl = connection.baseUrl();
        Optional<String> refusal = JiraConnection.baseUrlRefusal(baseUrl);
        if (refusal.isPresent() && !loopbackHttpAllowed(baseUrl)) {
            return refusal;
        }
        if (connection.deployment() == JiraConnection.Deployment.CLOUD && connection.email().isEmpty()) {
            return Optional.of("Jira Cloud needs the account email; open Jira Settings");
        }
        if (secret == null || secret.isBlank()) {
            return Optional.of("No Jira API token is stored for this project; open Jira Settings");
        }
        return Optional.empty();
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
