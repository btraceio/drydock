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
 * its contract is documented in {@code docs/AgentGithubIdentity.md}. A blank
 * command is not an error: it runs the user-local default minting script at
 * {@link #DEFAULT_COMMAND}. The command is stored verbatim
 * (whitespace-stripped); it is not a secret and it does not run at save
time.</p>
 */
public record AgentIdentitySettings(boolean enabled, String credentialCommand) {

    private static final String PREFIX = "drydock.agentIdentity.";

    /**
     * The command that runs when the stored command is blank: the user-local
     * minting script. A per-repository command overrides it; the shell
     * expands the {@code ~}, so the literal is stored and passed through
     * verbatim.
     */
    public static final String DEFAULT_COMMAND = "~/.sdlc/agent-identity";

    public AgentIdentitySettings {
        credentialCommand = credentialCommand == null ? "" : credentialCommand.strip();
    }

    /** The defaults: off, no command. */
    public static AgentIdentitySettings defaults() {
        return new AgentIdentitySettings(false, "");
    }

    /** The command the identity pipeline runs: the stored one, or the default location when blank. */
    public String effectiveCommand() {
        return credentialCommand.isEmpty() ? DEFAULT_COMMAND : credentialCommand;
    }

    /** Lenient read: missing values read as the defaults, never as broken. */
    public static AgentIdentitySettings read(Function<String, String> get, RepositoryId id) {
        String prefix = prefix(id);
        return new AgentIdentitySettings(
                Boolean.parseBoolean(get.apply(prefix + ".enabled")),
                Optional.ofNullable(get.apply(prefix + ".credentialCommand")).orElse(""));
    }

    public static AgentIdentitySettings load(RepositoryId id) {
        return read(PropertiesComponent.getInstance()::getValue, id);
    }

    /** Writes both keys; a null value means unset, leaving no trace behind. */
    public void write(BiConsumer<String, String> put, RepositoryId id) {
        String prefix = prefix(id);
        put.accept(prefix + ".enabled", Boolean.toString(enabled));
        put.accept(prefix + ".credentialCommand", credentialCommand.isEmpty() ? null : credentialCommand);
    }

    public void save(RepositoryId id) {
        write(PropertiesComponent.getInstance()::setValue, id);
    }

    private static String prefix(RepositoryId id) {
        return PREFIX + id;
    }
}
