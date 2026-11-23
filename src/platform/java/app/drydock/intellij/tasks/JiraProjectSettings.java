package app.drydock.intellij.tasks;

import app.drydock.domain.RepositoryId;
import app.drydock.git.JiraConnection;

import com.intellij.ide.util.PropertiesComponent;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Non-secret Jira connection settings for one registered Drydock
 * project, stored under {@code drydock.jira.<RepositoryId>.*} exactly the
 * way {@link GitHubProjectSettings} stores the GitHub ones. The secret
 * never travels through this record: it stays in the IDE password safe
 * behind {@link JiraCredentials}, so no {@code drydock.jira.*} property
 * can ever hold the token (spec AC13).
 *
 * <p>The queries Jira rows come from are the project's user-defined
 * groups ({@link JiraGroups}), not a setting here: a connection record is
 * built per group, with that group's JQL. A legacy {@code .jql} property
 * from before the groups existed is simply ignored.</p>
 */
record JiraProjectSettings(
        boolean enabled,
        JiraConnection.Deployment deployment,
        String baseUrl,
        Optional<String> email) {

    private static final String PREFIX = "drydock.jira.";

    /** The settings field a {@link Refusal} points at, so a dialog can focus it. */
    enum Field {BASE_URL, EMAIL, SECRET}

    /** One validation problem: the offending field and a user-facing message. */
    record Refusal(Field field, String message) {

        Refusal {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(message, "message");
        }
    }

    JiraProjectSettings {
        Objects.requireNonNull(deployment, "deployment");
        Objects.requireNonNull(email, "email");
        baseUrl = JiraConnection.normalizeBaseUrl(baseUrl);
        email = email.map(String::strip).filter(value -> !value.isEmpty());
    }

    /**
     * Lenient read of the stored properties: an unknown deployment falls
     * back to CLOUD, missing values take the defaults, and {@code enabled}
     * defaults to false, so a never-configured project reads as "off",
     * never as broken.
     */
    static JiraProjectSettings read(Function<String, String> get, RepositoryId id) {
        String prefix = prefix(id);
        return new JiraProjectSettings(
                Boolean.parseBoolean(get.apply(prefix + ".enabled")),
                deploymentOf(get.apply(prefix + ".deployment")),
                get.apply(prefix + ".baseUrl"),
                optionalOf(get.apply(prefix + ".email")));
    }

    /**
     * Writes exactly the four non-secret keys. A null value means unset —
     * a consumer like {@code PropertiesComponent.setValue} removes such a
     * key, and the pure readers see the default again.
     */
    void write(BiConsumer<String, String> put, RepositoryId id) {
        String prefix = prefix(id);
        put.accept(prefix + ".enabled", Boolean.toString(enabled));
        put.accept(prefix + ".deployment", deployment.name());
        put.accept(prefix + ".baseUrl", baseUrl);
        put.accept(prefix + ".email", email.orElse(null));
    }

    static JiraProjectSettings load(RepositoryId id) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        return read(properties::getValue, id);
    }

    void save(RepositoryId id) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        write(properties::setValue, id);
    }

    /**
     * The connection to make for the group query {@code jql}, or empty
     * when Jira is disabled for the project; a disabled project must not
     * produce requests at all. Each group builds its own connection, so
     * the query travels with the group, not with the settings.
     */
    Optional<JiraConnection> connection(String jql) {
        if (!enabled) {
            return Optional.empty();
        }
        return Optional.of(new JiraConnection(deployment, baseUrl, email, jql));
    }

    /**
     * The validation refusal for a candidate configuration, or empty when
     * the configuration is usable. Checked in order: the site address
     * (https only, spec open question 5), then the Cloud account email,
     * then the stored secret. A disabled configuration never refuses.
     */
    static Optional<Refusal> refusal(
            boolean enabled, JiraConnection.Deployment deployment, String baseUrl, String email, boolean hasSecret) {
        if (!enabled) {
            return Optional.empty();
        }
        Optional<String> url = JiraConnection.baseUrlRefusal(baseUrl);
        if (url.isPresent()) {
            return Optional.of(new Refusal(Field.BASE_URL, url.orElseThrow()));
        }
        if (deployment == JiraConnection.Deployment.CLOUD && (email == null || email.isBlank())) {
            return Optional.of(new Refusal(Field.EMAIL, "Jira Cloud needs the account email"));
        }
        if (!hasSecret) {
            return Optional.of(new Refusal(Field.SECRET,
                    deployment == JiraConnection.Deployment.CLOUD
                            ? "Enter the API token"
                            : "Enter the personal access token"));
        }
        return Optional.empty();
    }

    private static JiraConnection.Deployment deploymentOf(String value) {
        try {
            return JiraConnection.Deployment.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return JiraConnection.Deployment.CLOUD;
        }
    }

    private static Optional<String> optionalOf(String value) {
        return Optional.ofNullable(value).map(String::strip).filter(stripped -> !stripped.isEmpty());
    }

    private static String prefix(RepositoryId id) {
        return PREFIX + id;
    }
}
