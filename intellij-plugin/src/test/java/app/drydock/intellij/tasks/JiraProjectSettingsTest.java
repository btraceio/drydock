package app.drydock.intellij.tasks;

import app.drydock.domain.RepositoryId;
import app.drydock.git.JiraConnection;
import app.drydock.git.JiraConnection.Deployment;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JiraProjectSettingsTest {

    private static final RepositoryId ID = new RepositoryId(UUID.randomUUID());

    /** The five, and only the five, properties the record persists (AC13). */
    private static Set<String> keys(RepositoryId id) {
        String prefix = "drydock.jira." + id;
        return Set.of(
                prefix + ".enabled",
                prefix + ".deployment",
                prefix + ".baseUrl",
                prefix + ".email",
                prefix + ".jql");
    }

    @Test
    void everyFieldRoundTripsUnderTheProjectPrefix() {
        JiraProjectSettings settings = new JiraProjectSettings(
                true,
                Deployment.DATA_CENTER,
                "https://jira.example.com/jira/",
                Optional.of("  ops@example.com  "),
                Optional.of("  project = DRY ORDER BY updated ASC  "));
        Map<String, String> store = new HashMap<>();
        settings.write(store::put, ID);

        assertEquals(keys(ID), store.keySet());
        assertEquals("true", store.get("drydock.jira." + ID + ".enabled"));
        assertEquals("DATA_CENTER", store.get("drydock.jira." + ID + ".deployment"));

        JiraProjectSettings read = JiraProjectSettings.read(store::get, ID);
        assertTrue(read.enabled());
        assertEquals(Deployment.DATA_CENTER, read.deployment());
        assertEquals("https://jira.example.com/jira", read.baseUrl());
        assertEquals(Optional.of("ops@example.com"), read.email());
        assertEquals(Optional.of("project = DRY ORDER BY updated ASC"), read.jqlOverride());
        assertEquals(settings, read);
    }

    @Test
    void aBlankJqlReadsBackEmptyAndEffectiveJqlIsTheDefault() {
        JiraProjectSettings settings = new JiraProjectSettings(
                true, Deployment.CLOUD, "https://acme.atlassian.net", Optional.of("me@acme.com"), Optional.of("   "));
        assertTrue(settings.jqlOverride().isEmpty());
        assertEquals(JiraConnection.DEFAULT_JQL, settings.effectiveJql());

        Map<String, String> store = new HashMap<>();
        settings.write(store::put, ID);
        assertNull(store.get("drydock.jira." + ID + ".jql"));
        JiraProjectSettings read = JiraProjectSettings.read(store::get, ID);
        assertTrue(read.jqlOverride().isEmpty());
        assertEquals(JiraConnection.DEFAULT_JQL, read.effectiveJql());
    }

    @Test
    void writeTouchesOnlyTheFiveNonSecretKeys() {
        JiraProjectSettings settings = new JiraProjectSettings(
                true, Deployment.CLOUD, "https://acme.atlassian.net", Optional.of("me@acme.com"), Optional.empty());
        Map<String, String> store = new HashMap<>();
        settings.write(store::put, ID);
        assertEquals(keys(ID), store.keySet());
        // The record holds no secret component, so no stored value can be one.
        assertEquals(5, store.size());
    }

    @Test
    void readDefaultsWhenNothingIsStored() {
        JiraProjectSettings settings = JiraProjectSettings.read(key -> null, ID);
        assertFalse(settings.enabled());
        assertEquals(Deployment.CLOUD, settings.deployment());
        assertEquals("", settings.baseUrl());
        assertTrue(settings.email().isEmpty());
        assertTrue(settings.jqlOverride().isEmpty());
        assertEquals(JiraConnection.DEFAULT_JQL, settings.effectiveJql());
        assertEquals(Optional.empty(), settings.connection());
    }

    @Test
    void anUnknownDeploymentLenientlyFallsBackToCloud() {
        Map<String, String> store = new HashMap<>();
        store.put("drydock.jira." + ID + ".deployment", "MAINFRAME");
        assertEquals(Deployment.CLOUD, JiraProjectSettings.read(store::get, ID).deployment());
    }

    @Test
    void disabledAndValidDataCenterWithoutAnEmailDoNotRefuse() {
        assertTrue(JiraProjectSettings.refusal(false, Deployment.CLOUD, "", null, false).isEmpty());
        assertTrue(JiraProjectSettings.refusal(
                true, Deployment.DATA_CENTER, "https://jira.example.com", "  ", true).isEmpty());
        assertTrue(JiraProjectSettings.refusal(
                true, Deployment.DATA_CENTER, "https://jira.example.com", null, true).isEmpty());
    }

    @Test
    void anEmptyOrNonHttpsUrlRefuses() {
        String message = "Use the site's https:// address, for example https://your-site.atlassian.net";
        assertEquals(
                Optional.of(new JiraProjectSettings.Refusal(JiraProjectSettings.Field.BASE_URL, message)),
                JiraProjectSettings.refusal(true, Deployment.CLOUD, "", "me@acme.com", true));
        assertEquals(
                Optional.of(new JiraProjectSettings.Refusal(JiraProjectSettings.Field.BASE_URL, message)),
                JiraProjectSettings.refusal(true, Deployment.CLOUD, "http://acme.atlassian.net", "me@acme.com", true));
        assertEquals(
                Optional.of(new JiraProjectSettings.Refusal(JiraProjectSettings.Field.BASE_URL, message)),
                JiraProjectSettings.refusal(true, Deployment.CLOUD, "acme.atlassian.net", "me@acme.com", true));
    }

    @Test
    void cloudWithoutAnEmailRefusesBeforeTheSecretCheck() {
        assertEquals(
                Optional.of(new JiraProjectSettings.Refusal(JiraProjectSettings.Field.EMAIL,
                        "Jira Cloud needs the account email")),
                JiraProjectSettings.refusal(true, Deployment.CLOUD, "https://acme.atlassian.net", "  ", false));
        assertEquals(
                Optional.of(new JiraProjectSettings.Refusal(JiraProjectSettings.Field.EMAIL,
                        "Jira Cloud needs the account email")),
                JiraProjectSettings.refusal(true, Deployment.CLOUD, "https://acme.atlassian.net", null, false));
    }

    @Test
    void enabledWithoutASecretRefusesWithTheDeploymentWordForToken() {
        assertEquals(
                Optional.of(new JiraProjectSettings.Refusal(JiraProjectSettings.Field.SECRET, "Enter the API token")),
                JiraProjectSettings.refusal(
                        true, Deployment.CLOUD, "https://acme.atlassian.net", "me@acme.com", false));
        assertEquals(
                Optional.of(new JiraProjectSettings.Refusal(
                        JiraProjectSettings.Field.SECRET, "Enter the personal access token")),
                JiraProjectSettings.refusal(true, Deployment.DATA_CENTER, "https://jira.example.com", null, false));
    }

    @Test
    void accountKeyIsStableAcrossTrailingSlashAndLetterCaseAndSeparatesAccounts() {
        assertEquals(
                JiraCredentials.accountKey(Deployment.CLOUD, "https://ACME.Atlassian.net/", Optional.of("Me@Acme.com")),
                JiraCredentials.accountKey(Deployment.CLOUD, "https://acme.atlassian.net", Optional.of("me@acme.com")));
        assertNotEquals(
                JiraCredentials.accountKey(Deployment.CLOUD, "https://acme.atlassian.net", Optional.of("me@acme.com")),
                JiraCredentials.accountKey(
                        Deployment.CLOUD, "https://acme.atlassian.net", Optional.of("you@acme.com")));
        assertEquals(
                JiraCredentials.accountKey(Deployment.DATA_CENTER, "https://Jira.Example.com/Jira/", Optional.empty()),
                JiraCredentials.accountKey(Deployment.DATA_CENTER, "https://jira.example.com/jira", Optional.empty()));
        assertNotEquals(
                JiraCredentials.accountKey(Deployment.DATA_CENTER, "https://jira.example.com", Optional.empty()),
                JiraCredentials.accountKey(Deployment.DATA_CENTER, "https://other.example.com", Optional.empty()));
    }

    @Test
    void connectionIsEmptyWhenDisabledAndCarriesTheOverrideWhenEnabled() {
        JiraProjectSettings disabled = new JiraProjectSettings(
                false, Deployment.CLOUD, "https://acme.atlassian.net", Optional.of("me@acme.com"), Optional.empty());
        assertTrue(disabled.connection().isEmpty());

        JiraProjectSettings enabled = new JiraProjectSettings(
                true, Deployment.CLOUD, "https://acme.atlassian.net/", Optional.of("me@acme.com"),
                Optional.of("project = DRY"));
        JiraConnection connection = enabled.connection().orElseThrow();
        assertEquals(Deployment.CLOUD, connection.deployment());
        assertEquals("https://acme.atlassian.net", connection.baseUrl());
        assertEquals(Optional.of("me@acme.com"), connection.email());
        assertEquals("project = DRY", connection.jql());
    }
}
