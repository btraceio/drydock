package app.drydock.agent.identity;

import app.drydock.agent.providers.AgentCommands;
import app.drydock.domain.ManagedSessionId;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Prepares one session's agent-identity injection: runs the repository's
 * credential command, renders the session env file, installs the {@code gh}
 * wrapper, and returns the pieces the launch prefix needs.
 *
 * <p>The returned session carries the env-file path for the {@code set -ea;
 * . <env>; rm -f <env>; exec} prefix (see {@link #prefix}) and the bin
 * directory to put first on the session's PATH. The env file's variables are,
 * in order: everything the credential command minted (its own order), then
 * Drydock's plumbing — the credential command itself, for the wrapper to
 * re-invoke per {@code gh} call, and the session-scoped git config entries
 * ({@link AgentIdentityGitConfig}). Plumbing comes last, so a command that
 * mints a variable Drydock must own cannot break the injection.</p>
 */
public final class AgentIdentityPipeline {

    /** What runs a repository's credential command; {@link AgentIdentityCommand} is the production form. */
    @FunctionalInterface
    public interface CredentialRunner {

        AgentIdentitySpec run(String command, Path workingDirectory) throws AgentIdentityException;
    }

    private final CredentialRunner command;
    private final AgentIdentityEnvFile envFile;
    private final Path baseDirectory;

    public AgentIdentityPipeline(Path baseDirectory) {
        this(new AgentIdentityCommand(), new AgentIdentityEnvFile(baseDirectory), baseDirectory);
    }

    /** Test seam: inject a canned credential runner or a different base directory. */
    public AgentIdentityPipeline(CredentialRunner command, AgentIdentityEnvFile envFile, Path baseDirectory) {
        this.command = command;
        this.envFile = envFile;
        this.baseDirectory = baseDirectory;
    }

    /** One prepared session: what the launch prefix and the PATH statement need. */
    public record Session(Path envFile, Path wrapperBin) {
    }

    /**
     * Runs the whole preparation for one launching session.
     *
     * @param credentialCommand the repository's configured credential command
     * @param workingDirectory the session's working directory, the credential
     *     command's working directory too
     * @param originUrl the repository's origin URL, when one could be resolved;
     *     an SSH origin paired with a credential helper must be rewritable to
     *     HTTPS or the preparation fails (see {@link AgentIdentityGitConfig})
     * @throws AgentIdentityException when the command fails, its output does
     *     not conform, or the git-config injection is unservable — all hard,
     *     loud launch failures
     * @throws IOException when the env file or the wrapper cannot be written —
     *     also a launch failure, not a degradation
     */
    public Session prepare(ManagedSessionId sessionId, String credentialCommand, Path workingDirectory,
                           Optional<String> originUrl) throws AgentIdentityException, IOException {
        AgentIdentitySpec spec = command.run(credentialCommand, workingDirectory);
        List<AgentIdentityGitConfig.Entry> gitConfig = AgentIdentityGitConfig.forSession(spec, originUrl);
        Path wrapperBin = AgentIdentityWrapper.install(baseDirectory);
        Map<String, String> variables = new LinkedHashMap<>(spec.variables());
        variables.put(AgentIdentitySpec.CREDENTIAL_COMMAND_VARIABLE, credentialCommand);
        variables.put("GIT_CONFIG_COUNT", Integer.toString(gitConfig.size()));
        for (int i = 0; i < gitConfig.size(); i++) {
            AgentIdentityGitConfig.Entry entry = gitConfig.get(i);
            variables.put("GIT_CONFIG_KEY_" + i, entry.key());
            variables.put("GIT_CONFIG_VALUE_" + i, entry.value());
        }
        Path file = envFile.write(sessionId, variables);
        return new Session(file, wrapperBin);
    }

    /** Deletes a session's env file; the failed-launch cleanup the launcher hands out. */
    public void deleteEnvFile(ManagedSessionId sessionId) {
        envFile.delete(sessionId);
    }

    /**
     * The launch prefix sourcing {@code envFile} around {@code agentCommand}:
     * {@code set -ea} so sourcing exports, the sourced file deleted the moment
     * it has been read (its life is the milliseconds before exec), then
     * {@code exec} so the agent replaces the shell and carries the environment
     * — and no shell lingers holding the values in its argv.
     *
     * <p>POSIX only; the caller never builds this for a fish or Windows shell
     * (it skips the injection with a warning instead).</p>
     */
    public static String prefix(Path envFile, String agentCommand) {
        String quoted = AgentCommands.shellQuote(envFile.toString());
        return "set -ea; . " + quoted + "; rm -f " + quoted + "; exec " + agentCommand;
    }
}
