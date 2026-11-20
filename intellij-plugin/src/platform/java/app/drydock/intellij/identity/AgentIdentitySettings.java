package app.drydock.intellij.identity;

import app.drydock.domain.RepositoryId;

import com.intellij.ide.util.PropertiesComponent;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Per-repository agent-identity settings, stored under {@code
 * drydock.agentIdentity.<RepositoryId>.*} in the IDE's application-level
 * {@link PropertiesComponent}, exactly the way {@code JiraProjectSettings}
 * and {@code GitHubProjectSettings} store theirs.
 *
 * <p>Opt-in and off by default: a repository with no stored settings behaves
 * byte-identically to a build without the feature. The credential command is
 * a user-supplied shell command whose {@code KEY=VALUE} output mints the
 * identity agent sessions in this repository commit, push and open PRs under;
 * its contract is documented in {@code docs/AgentGithubIdentity.md}. The
 * command is stored verbatim (whitespace-stripped); it is not a secret and it
 * does not run at save time.</p>
 */
record AgentIdentitySettings(boolean enabled, String credentialCommand) {

    private static final String PREFIX = "drydock.agentIdentity.";

    AgentIdentitySettings {
        credentialCommand = credentialCommand == null ? "" : credentialCommand.strip();
    }

    /** The defaults: off, no command. */
    static AgentIdentitySettings defaults() {
        return new AgentIdentitySettings(false, "");
    }

    /** Lenient read: missing values read as the defaults, never as broken. */
    static AgentIdentitySettings read(Function<String, String> get, RepositoryId id) {
        String prefix = prefix(id);
        return new AgentIdentitySettings(
                Boolean.parseBoolean(get.apply(prefix + ".enabled")),
                Optional.ofNullable(get.apply(prefix + ".credentialCommand")).orElse(""));
    }

    static AgentIdentitySettings load(RepositoryId id) {
        return read(PropertiesComponent.getInstance()::getValue, id);
    }

    /** Writes both keys; a null value means unset, leaving no trace behind. */
    void write(BiConsumer<String, String> put, RepositoryId id) {
        String prefix = prefix(id);
        put.accept(prefix + ".enabled", Boolean.toString(enabled));
        put.accept(prefix + ".credentialCommand", credentialCommand.isEmpty() ? null : credentialCommand);
    }

    void save(RepositoryId id) {
        write(PropertiesComponent.getInstance()::setValue, id);
    }

    /**
     * The validation refusal for a candidate configuration, or empty when it
     * is usable. A disabled configuration never refuses; an enabled one needs
     * a command to mint anything at all.
     */
    static Optional<String> refusal(boolean enabled, String credentialCommand) {
        if (!enabled) {
            return Optional.empty();
        }
        String stripped = credentialCommand == null ? "" : credentialCommand.strip();
        if (stripped.isEmpty()) {
            return Optional.of("Enter the credential command, or turn agent identity off.");
        }
        return Optional.empty();
    }

    private static String prefix(RepositoryId id) {
        return PREFIX + id;
    }
}
