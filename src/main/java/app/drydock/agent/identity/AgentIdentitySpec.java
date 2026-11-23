package app.drydock.agent.identity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The validated output of one run of a repository's agent-identity credential
 * command: the {@code KEY=VALUE} lines it printed, keyed by name.
 *
 * <p>The contract is deliberately narrow, and every deviation is a hard
 * failure ({@link AgentIdentityException}): the command must print one
 * {@code KEY=VALUE} line per variable on stdout, with a POSIX-valid
 * environment-variable name and a non-empty, newline-free value. Blank lines
 * are skipped. A repeated key keeps its last value, the same rule sourcing
 * the rendered env file applies. A key Drydock itself has no use for is kept
 * and exported to the session anyway ({@code set -a} exports the whole file),
 * so a user's glue can ship extra environment for its own wrappers; the
 * known keys below are the ones Drydock reads itself.</p>
 *
 * <p>All keys are optional: a command may mint only a token, or only commit
 * attribution, whichever the repository's setup needs. But a command that
 * prints <em>nothing</em> has almost certainly failed silently rather than
 * configured nothing, so that too is a failure.</p>
 */
public record AgentIdentitySpec(Map<String, String> variables) {

    /** Commit attribution: the name git records as the commit's author. */
    public static final String GIT_AUTHOR_NAME = "GIT_AUTHOR_NAME";

    /** Commit attribution: the email git records as the commit's author's. */
    public static final String GIT_AUTHOR_EMAIL = "GIT_AUTHOR_EMAIL";

    /** Commit attribution: the name git records as the committer. */
    public static final String GIT_COMMITTER_NAME = "GIT_COMMITTER_NAME";

    /** Commit attribution: the email git records as the committer's. */
    public static final String GIT_COMMITTER_EMAIL = "GIT_COMMITTER_EMAIL";

    /**
     * A git credential-helper spec the session's git uses to authenticate a
     * push. Injected as session-scoped git config (see {@link
     * AgentIdentityGitConfig}), never written to the user's global config.
     */
    public static final String GIT_CREDENTIAL_HELPER_SPEC = "GIT_CREDENTIAL_HELPER_SPEC";

    /**
     * A token the session's {@code gh} wrapper picks up. Exported as-is; the
     * wrapper shipped with the pipeline re-mints it per invocation, so this
     * value is the fallback a glue script may print for tools that read the
     * environment directly.
     */
    public static final String GH_TOKEN = "GH_TOKEN";

    /**
     * The variable the session's env file carries so the shipped {@code gh}
     * wrapper can re-invoke the credential command itself. Not read from the
     * command's output: Drydock sets it from the configured command.
     */
    public static final String CREDENTIAL_COMMAND_VARIABLE = "DRYDOCK_AGENT_CREDENTIAL_COMMAND";

    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public AgentIdentitySpec {
        variables = Map.copyOf(variables);
    }

    /**
     * Parses the command's stdout. Every non-blank line must be a
     * {@code KEY=VALUE} pair; anything else, an empty value, or no lines at
     * all is a {@link AgentIdentityException} naming the offending output.
     */
    public static AgentIdentitySpec parse(String stdout) {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String raw : stdout.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new AgentIdentityException(
                        "The credential command printed a line that is not KEY=VALUE: " + quoted(line));
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!KEY.matcher(key).matches()) {
                throw new AgentIdentityException(
                        "The credential command printed an invalid variable name: " + quoted(key));
            }
            if (value.isEmpty()) {
                throw new AgentIdentityException(
                        "The credential command printed an empty value for " + key);
            }
            parsed.put(key, value);
        }
        if (parsed.isEmpty()) {
            throw new AgentIdentityException(
                    "The credential command printed no KEY=VALUE lines; it must mint at least one variable");
        }
        return new AgentIdentitySpec(parsed);
    }

    /** The value printed for {@code key}, or empty when the command did not mint it. */
    public Optional<String> value(String key) {
        return Optional.ofNullable(variables.get(key));
    }

    private static String quoted(String line) {
        return "'" + line.replace("'", "'\\''") + "'";
    }
}
