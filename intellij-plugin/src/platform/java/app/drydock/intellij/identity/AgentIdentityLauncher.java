package app.drydock.intellij.identity;

import app.drydock.agent.identity.AgentIdentityException;
import app.drydock.agent.identity.AgentIdentityPipeline;
import app.drydock.agent.providers.terminal.TerminalAgentProvider;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.git.GitExecutableLocator;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Decides whether a launching session gets agent-identity injection, and when
 * it does, mints everything the launch prefix needs: runs the repository's
 * credential command, writes the session env file, installs the {@code gh}
 * wrapper, and wraps the agent command in the sourcing prefix.
 *
 * <p>The single launch funnel is {@code AgentExecutionRegistry.start}, which
 * consults this launcher before spawning. The decision is per repository
 * (through the launching session's row), opt-in, and defaults to "no
 * injection" — a session in a repository without agent identity launches
 * exactly as before.</p>
 *
 * <p>Every failure of the credential command or the env-file write is a hard
 * launch failure ({@link AgentIdentityException}): a session whose identity
 * was requested but cannot be minted must not launch under the human's own
 * identity by accident. The skips, by contrast, degrade deliberately and are
 * surfaced: a plain-terminal session carries no agent to attribute, and a
 * fish or Windows shell cannot run the POSIX prefix, so those launch without
 * injection behind a warning notification.</p>
 */
public final class AgentIdentityLauncher {

    private static final Logger LOG = System.getLogger(AgentIdentityLauncher.class.getName());

    /** How long resolving the origin remote may take; a local git call. */
    private static final Duration ORIGIN_TIMEOUT = Duration.ofSeconds(10);

    /** The notification group the skips surface through (see plugin.xml). */
    public static final String NOTIFICATION_GROUP = "Drydock agent identity";

    /** Declines everything: the fallback when the production launcher cannot be resolved
     *  (its repository resolver answers empty for every session, so nothing is ever prepared). */
    public static final AgentIdentityLauncher NONE = new AgentIdentityLauncher(
            new AgentIdentityPipeline(Path.of(".")), id -> Optional.empty(), settings -> AgentIdentitySettings.defaults());

    /** What a prepared launch needs: the wrapped command, the bin directory for PATH,
     *  and the env-file cleanup for a launch that fails after preparation. */
    public record Prepared(String command, Path wrapperBin, Runnable onFailureCleanup) {

        /** Deletes the session's env file, best-effort; for failed-start paths only. */
        public void cleanupAfterFailure() {
            onFailureCleanup.run();
        }
    }

    private final AgentIdentityPipeline pipeline;
    private final Function<ManagedSessionId, Optional<RepositoryId>> repositoryOf;
    private final Function<RepositoryId, AgentIdentitySettings> settingsFor;

    /** Production: the plugin service's state store resolves the session's repository. */
    public AgentIdentityLauncher(Path baseDirectory) {
        this(new AgentIdentityPipeline(baseDirectory), AgentIdentityLauncher::repositoryOfSession,
                AgentIdentitySettings::load);
    }

    /** Test seam: inject the pipeline, the repository resolver, and the settings reader. */
    AgentIdentityLauncher(AgentIdentityPipeline pipeline,
                          Function<ManagedSessionId, Optional<RepositoryId>> repositoryOf,
                          Function<RepositoryId, AgentIdentitySettings> settingsFor) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.repositoryOf = Objects.requireNonNull(repositoryOf, "repositoryOf");
        this.settingsFor = Objects.requireNonNull(settingsFor, "settingsFor");
    }

    /**
     * Prepares identity injection for a launching session, or explains why
     * there is none.
     *
     * @return the wrapped launch command, or empty when the session launches
     *     without injection (identity disabled, plain-terminal session, a
     *     shell the POSIX prefix cannot serve — the last surfaced by a
     *     warning notification)
     * @throws AgentIdentityException when identity is configured for the
     *     repository but its minting failed — the launch fails, loudly
     */
    public Optional<Prepared> prepare(ManagedSessionId sessionId, Path workingDirectory, String planCommand) {
        Optional<RepositoryId> repository = repositoryOf.apply(sessionId);
        if (repository.isEmpty()) {
            // No row (yet) for this session: no repository to read settings
            // from. A launch always prepares its row first, so this is the
            // not-yet-persisted corner, not a configured repository.
            LOG.log(Level.DEBUG, () -> "No repository row for " + sessionId + "; launching without agent identity");
            return Optional.empty();
        }
        AgentIdentitySettings settings = settingsFor.apply(repository.get());
        if (!settings.enabled() || settings.credentialCommand().isEmpty()) {
            return Optional.empty();
        }
        if (TerminalAgentProvider.SHELL_COMMAND.equals(planCommand)) {
            // A plain interactive shell hosts no agent to attribute, and it
            // has no -c command to prefix anyway.
            return Optional.empty();
        }
        String skip = posixSkipReason();
        if (skip != null) {
            LOG.log(Level.WARNING, () -> "Agent identity for " + repository.get() + " skipped: " + skip);
            notifySkip(skip);
            return Optional.empty();
        }
        Optional<String> origin = originUrl(workingDirectory);
        try {
            AgentIdentityPipeline.Session session =
                    pipeline.prepare(sessionId, settings.credentialCommand(), workingDirectory, origin);
            return Optional.of(new Prepared(
                    AgentIdentityPipeline.prefix(session.envFile(), planCommand), session.wrapperBin(),
                    () -> pipeline.deleteEnvFile(sessionId)));
        } catch (IOException e) {
            throw new AgentIdentityException(
                    "The agent-identity env file for " + sessionId + " could not be written: " + e.getMessage(), e);
        }
    }

    private static String posixSkipReason() {
        return posixSkipReason(System.getProperty("os.name", ""), System.getenv("SHELL"));
    }

    /** Why the POSIX prefix cannot run in this launch shell, or {@code null} when it can. */
    static String posixSkipReason(String osName, String shell) {
        if (osName.toLowerCase().contains("windows")) {
            return "Windows shells cannot run the POSIX identity prefix";
        }
        String basename = shell == null ? "" : shell.substring(shell.lastIndexOf('/') + 1);
        if (basename.equals("fish")) {
            return "the launch shell is fish, which cannot run the POSIX identity prefix";
        }
        return null;
    }

    private static void notifySkip(String reason) {
        try {
            NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification("Agent identity not applied", reason, NotificationType.WARNING)
                    .notify();
        } catch (RuntimeException e) {
            // No IDE application (plain unit tests): the log line above is
            // the surface.
        }
    }

    /**
     * The launching repository's id, through the session's persisted row.
     * Empty (no injection) when the row is not there or the state store is
     * not usable yet; a launch never fails on this lookup.
     */
    private static Optional<RepositoryId> repositoryOfSession(ManagedSessionId sessionId) {
        try {
            return DrydockPluginService.getInstance().stateStore().state().sessions().stream()
                    .filter(session -> session.id().equals(sessionId))
                    .map(ManagedAgentSession::repositoryId)
                    .findFirst();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Could not resolve the repository of " + sessionId
                    + "; launching without agent identity: " + e);
            return Optional.empty();
        }
    }

    /**
     * The working directory's origin URL, or empty when git cannot say (no
     * origin, no git). Best-effort by design: an origin the pipeline cannot
     * see cannot be rewritten, and {@link AgentIdentityGitConfig} turns a
     * credential helper paired with an unrewritable origin into a loud
     * failure at the pipeline, not here.
     */
    private static Optional<String> originUrl(Path workingDirectory) {
        return new GitExecutableLocator().locate().flatMap(git -> {
            try {
                ProcessResult result = ProcessRunner.run(
                        List.of(git.toString(), "remote", "get-url", "origin"),
                        new ProcessRunner.Options(workingDirectory, ORIGIN_TIMEOUT, true, Map.of()));
                String url = result.stdout().strip();
                return result.exitCode() == 0 && !url.isEmpty() ? Optional.of(url) : Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (RuntimeException | IOException e) {
                LOG.log(Level.DEBUG, () -> "No origin URL for " + workingDirectory + ": " + e);
                return Optional.empty();
            }
        });
    }
}
