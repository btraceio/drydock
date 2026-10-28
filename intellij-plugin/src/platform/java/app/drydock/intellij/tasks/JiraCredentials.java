package app.drydock.intellij.tasks;

import app.drydock.git.JiraConnection;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;

import java.util.Locale;
import java.util.Optional;

/**
 * The Jira secret (a Cloud API token or a Data Center personal access
 * token) lives only in the IDE password safe, keyed by the site and the
 * account. Nothing here ever logs the secret or stores it anywhere else
 * (spec AC13); both the read and the write assert they are off the EDT,
 * because the password safe can reach the OS keychain and block (spec
 * AC14).
 */
final class JiraCredentials {

    private JiraCredentials() {
    }

    /**
     * The password-safe account key: the normalized, lowercased site
     * address, a separator, then the lowercased account email for Cloud —
     * or {@code pat} in the account slot for Data Center, whose PAT is not
     * tied to an email. The site is part of the key, so two sites never
     * share a stored token, and the key ignores a trailing slash and
     * letter case in the address.
     */
    static String accountKey(JiraConnection.Deployment deployment, String baseUrl, Optional<String> email) {
        String base = JiraConnection.normalizeBaseUrl(baseUrl).toLowerCase(Locale.ROOT);
        String account = deployment == JiraConnection.Deployment.CLOUD
                ? email.map(value -> value.toLowerCase(Locale.ROOT)).orElse("")
                : "pat";
        return base + "|" + account;
    }

    static CredentialAttributes attributes(JiraProjectSettings settings) {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName(
                "Drydock Jira", accountKey(settings.deployment(), settings.baseUrl(), settings.email())));
    }

    /**
     * Reads the stored secret, or empty when nothing (or only blanks) is
     * stored. Must not run on the EDT.
     */
    static Optional<String> read(JiraProjectSettings settings) {
        ApplicationManager.getApplication().assertIsNonDispatchThread();
        String secret = PasswordSafe.getInstance().getPassword(attributes(settings));
        return Optional.ofNullable(secret).filter(value -> !value.isBlank());
    }

    /**
     * Stores the secret. A blank or null value removes the credential, so
     * clearing the field in the dialog and saving takes the token out of
     * the password safe (spec AC13). Must not run on the EDT.
     */
    static void write(JiraProjectSettings settings, String secret) {
        ApplicationManager.getApplication().assertIsNonDispatchThread();
        PasswordSafe.getInstance().setPassword(attributes(settings),
                secret == null || secret.isBlank() ? null : secret);
    }
}
