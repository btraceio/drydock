package app.drydock.git;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * The non-secret connection to a Jira site: where it lives, which flavour
 * of the REST API it serves, which account is authenticating, and the JQL
 * that scopes the listing. The secret itself never travels through this
 * record -- it stays in the IDE password safe and reaches the client as a
 * plain value at request time.
 */
public record JiraConnection(Deployment deployment, String baseUrl, Optional<String> email, String jql) {

    /** The REST flavour of the site; the two deployments search differently. */
    public enum Deployment {
        CLOUD("/rest/api/3/search/jql", "Cloud"),
        DATA_CENTER("/rest/api/2/search", "Data Center");

        private final String searchPath;
        private final String label;

        Deployment(String searchPath, String label) {
            this.searchPath = searchPath;
            this.label = label;
        }

        /** The search endpoint this deployment serves. */
        public String searchPath() {
            return searchPath;
        }

        /** The user-facing name, as the settings dialog shows it. */
        public String label() {
            return label;
        }
    }

    /** The pinned default query: my unresolved issues, newest first. */
    public static final String DEFAULT_JQL =
            "assignee = currentUser() AND resolution = Unresolved ORDER BY updated DESC";

    public JiraConnection {
        Objects.requireNonNull(deployment, "deployment");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(jql, "jql");
        baseUrl = normalizeBaseUrl(baseUrl);
        email = email.filter(value -> !value.isBlank());
        jql = jql.isBlank() ? DEFAULT_JQL : jql;
    }

    /**
     * Trims the site address and drops a trailing slash, so
     * {@code https://acme.atlassian.net/} and {@code https://acme.atlassian.net}
     * are one key. Null becomes the empty string.
     */
    public static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String normalized = baseUrl.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * The base-URL refusal message, or empty when the address is usable:
     * an absolute {@code https://} URI with a host and no user info, query
     * or fragment. Tokens must not travel in clear text or leak in a query
     * string, so {@code http://} is refused outright (spec open question 5).
     */
    public static Optional<String> baseUrlRefusal(String baseUrl) {
        try {
            URI uri = URI.create(normalizeBaseUrl(baseUrl));
            if (!uri.isAbsolute()
                    || !"https".equals(uri.getScheme())
                    || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                return Optional.of("Use the site's https:// address, for example https://your-site.atlassian.net");
            }
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of("Use the site's https:// address, for example https://your-site.atlassian.net");
        }
    }

    /**
     * The address a browser opens for {@code key}. Built from the site
     * address, not from the API's {@code self} URL: a Data Center site
     * behind a context path keeps that path in {@code baseUrl}, and the
     * REST self link points at the API, not at the issue page.
     */
    public String browseUrl(String key) {
        return baseUrl + "/browse/" + key;
    }
}
