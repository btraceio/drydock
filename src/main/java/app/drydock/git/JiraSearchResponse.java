package app.drydock.git;

import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The pure half of the Jira client: a search-response body to a
 * {@link JiraIssueListing}, no network involved. Malformed entries are
 * skipped and the rest of the page kept, exactly as
 * {@code GhCliService.parseIssueListing} treats a bad row; a body that is
 * not a JSON object with an {@code issues} array is a failure, never an
 * empty tracker.
 */
public final class JiraSearchResponse {

    private static final Logger LOG = System.getLogger(JiraSearchResponse.class.getName());

    /** A Jira issue key: {@code PROJ-123}. Anything else is not a row we can open. */
    private static final Pattern KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*-[0-9]+$");

    /** Jira's {@code updated} stamp: {@code 2026-10-20T10:15:30.000+0000}, offset without a colon. */
    private static final DateTimeFormatter UPDATED = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    /** Error excerpts must fit a tree row; 300 chars says everything a user needs. */
    private static final int ERROR_EXCERPT = 300;

    private JiraSearchResponse() { }

    /**
     * Parses a Jira search response from the site at {@code baseUrl}.
     * Both the Cloud {@code search/jql} body ({@code issues},
     * {@code nextPageToken}, {@code isLast}) and the Data Center
     * {@code search} body ({@code startAt}, {@code total}) carry the same
     * {@code issues} array, so one parser serves both deployments.
     */
    public static JiraIssueListing parse(String body, String baseUrl) {
        try {
            if (!(JsonParser.parse(body) instanceof JsonObject root)
                    || !(root.get("issues") instanceof JsonArray array)) {
                return new JiraIssueListing.Failed("Jira search did not return an issues list");
            }
            List<JiraIssue> issues = new ArrayList<>();
            for (JsonValue element : array.elements()) {
                if (!(element instanceof JsonObject issue)
                        || !(issue.get("key") instanceof JsonString key)
                        || !KEY.matcher(key.value()).matches()
                        || !(issue.get("fields") instanceof JsonObject fields)
                        || !(fields.get("summary") instanceof JsonString summary)) {
                    continue;
                }
                issues.add(new JiraIssue(key.value(), summary.value(),
                        JiraConnection.normalizeBaseUrl(baseUrl) + "/browse/" + key.value(),
                        labels(fields.get("labels")), parseUpdated(string(fields.get("updated")))));
            }
            return new JiraIssueListing.Listed(issues);
        } catch (JsonParseException | NumberFormatException e) {
            LOG.log(Level.DEBUG, "Unparseable Jira search response", e);
            return new JiraIssueListing.Failed("Jira returned a response that could not be parsed");
        }
    }

    /**
     * Jira's {@code updated} timestamp, or empty when Jira's clock did not
     * make sense. A bad stamp does not make the issue itself unusable, so
     * the row stays, as {@code GhCliService.parseIssueListing} keeps a row
     * with an unparseable {@code updatedAt}.
     */
    static Optional<Instant> parseUpdated(String updated) {
        if (updated == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(updated, UPDATED).toInstant());
        } catch (RuntimeException ignored) {
            // Fall through to the ISO form: some deployments report +00:00.
        }
        try {
            return Optional.of(OffsetDateTime.parse(updated).toInstant());
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    /**
     * The user-facing part of a Jira error body: {@code errorMessages[]}
     * plus the string values of {@code errors{}}, joined with
     * {@code "; "} and truncated. Empty when there is nothing to say or
     * the body is not a JSON object -- the HTTP status then carries the
     * message alone.
     */
    static Optional<String> errorMessages(String body) {
        try {
            if (!(JsonParser.parse(body) instanceof JsonObject root)) {
                return Optional.empty();
            }
            List<String> parts = new ArrayList<>();
            if (root.get("errorMessages") instanceof JsonArray messages) {
                for (JsonValue element : messages.elements()) {
                    if (element instanceof JsonString message && !message.value().isBlank()) {
                        parts.add(message.value());
                    }
                }
            }
            if (root.get("errors") instanceof JsonObject errors) {
                for (JsonValue value : errors.members().values()) {
                    if (value instanceof JsonString message && !message.value().isBlank()) {
                        parts.add(message.value());
                    }
                }
            }
            if (parts.isEmpty()) {
                return Optional.empty();
            }
            String joined = String.join("; ", parts);
            return Optional.of(joined.length() <= ERROR_EXCERPT ? joined
                    : joined.substring(0, ERROR_EXCERPT));
        } catch (JsonParseException e) {
            return Optional.empty();
        }
    }

    private static List<String> labels(JsonValue value) {
        if (!(value instanceof JsonArray array)) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (JsonValue element : array.elements()) {
            if (element instanceof JsonString label && !label.value().isBlank()) {
                labels.add(label.value());
            }
        }
        return labels;
    }

    private static String string(JsonValue value) {
        return value instanceof JsonString s ? s.value() : null;
    }
}
