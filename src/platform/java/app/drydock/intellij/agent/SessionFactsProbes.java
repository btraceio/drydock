package app.drydock.intellij.agent;

import app.drydock.agent.spi.AgentProvider;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.intellij.DrydockPluginService;

import com.intellij.openapi.project.Project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The blocking production reads behind one {@link SessionFactsLoader} load,
 * ported from the editor's {@code reloadSession}. Executor work only: the
 * loader calls these after readiness succeeded, on the plugin executor, never
 * on the EDT. Nothing here waits on the initialization future — a failed
 * readiness is settled by the loader before any probe — and nothing launches,
 * moves, attaches or releases a session: the registry and service reads are
 * read-only, and every read goes to the provided {@code plugin}, never a
 * different global service.
 *
 * <p>I/O is provider-defined and unmeasured here: {@code transcriptExists}
 * and {@code estimateResumeCost} are provider code (the shipped sources stat
 * a file and read a whole transcript respectively, but the SPI promises
 * neither cost). The token estimate is skipped for a running session and
 * for one without a conversation, so it is never read when its result could
 * not be shown.</p>
 *
 * <p>Failure classes differ: an exception from the existence read propagates
 * and the loader settles it as {@code FAILED}; an absent conversation source
 * is "provider cannot tell" (empty), which the strip maps to Resume for an
 * existing agent id. Only the cosmetic token estimate swallows a failure.</p>
 */
final class SessionFactsProbes {

    private SessionFactsProbes() {
    }

    /** A probe over one loader's own bounded catalog snapshot index; the index lock guards memory only. */
    static SessionFactsLoader.Probes indexed(Project project, DrydockPluginService plugin) {
        CatalogSessionIndex index = new CatalogSessionIndex();
        return id -> probe(project, plugin, id, sessions -> {
            synchronized (index) { // memory-only rebuild/lookup; released before any filesystem or transcript read
                return index.find(sessions, id);
            }
        });
    }

    /** The facts inputs for {@code id}, or empty when the provided service's catalog does not hold it. */
    static Optional<SessionPaneFacts.Inputs> probe(Project project, DrydockPluginService plugin, ManagedSessionId id)
            throws Exception {
        return probe(project, plugin, id, sessions -> sessions.stream()
                .filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null));
    }

    private static Optional<SessionPaneFacts.Inputs> probe(
            Project project, DrydockPluginService plugin, ManagedSessionId id, Lookup lookup) {
        List<ManagedAgentSession> catalog = plugin.stateStore().state().sessions();
        ManagedAgentSession found = lookup.find(catalog);
        if (found == null) {
            return Optional.empty();
        }
        Path directory = found.workingDirectory();
        Optional<AgentExecution> execution = AgentExecutionRegistry.getInstance().execution(id);
        boolean running = execution.map(AgentExecution::isRunning).orElse(false);
        boolean conversation = AgentStripText.resumable(found.agentSessionId().isPresent(),
                transcriptExists(plugin, found, directory));
        boolean exists = Files.isDirectory(directory);
        return Optional.of(new SessionPaneFacts.Inputs(
                Optional.ofNullable(project.getBasePath()).map(path -> RepositoryCatalog.canonicalize(Path.of(path))),
                RepositoryCatalog.canonicalize(directory),
                found.agentSessionId().isPresent(),
                running,
                scansResumeCost(found.agentSessionId().isPresent(), running, conversation)
                        ? resumeTokens(plugin, found) : OptionalLong.empty(),
                conversation,
                execution.map(AgentExecution::place),
                plugin.stateStore().state().repositories().stream()
                        .filter(candidate -> candidate.id().equals(found.repositoryId()))
                        .map(Repository::displayName).findFirst(),
                String.valueOf(directory.getFileName()),
                exists,
                exists && Files.exists(directory.resolve(".git"))));
    }

    /**
     * Empty only when there is no agent id or the provider has no conversation source ("cannot
     * tell", Resume for an existing id). A throw from the provider's existence read is NOT
     * caught: empty would turn an I/O failure into Resume, so it must reach the loader's FAILED path.
     */
    private static Optional<Boolean> transcriptExists(
            DrydockPluginService plugin, ManagedAgentSession found, Path directory) {
        return found.agentSessionId().flatMap(agentId -> plugin.registry().conversations(found.agentKind())
                .map(source -> source.transcriptExists(directory, agentId)));
    }

    /** The resume-cost read is for a stopped session that has a conversation, nothing else. */
    static boolean scansResumeCost(boolean hasAgentSessionId, boolean running, boolean hasConversation) {
        return hasAgentSessionId && !running && hasConversation;
    }

    /** Cosmetic: an unreadable transcript is an absent figure, never a failed load. */
    private static OptionalLong resumeTokens(DrydockPluginService plugin, ManagedAgentSession found) {
        try {
            return plugin.registry().provider(found.agentKind())
                    .flatMap(AgentProvider::conversations)
                    .flatMap(source -> source.estimateResumeCost(found.workingDirectory(), found.agentSessionId().get()))
                    .map(estimate -> OptionalLong.of(estimate.contextTokens()))
                    .orElse(OptionalLong.empty());
        } catch (RuntimeException e) {
            return OptionalLong.empty();
        }
    }

    /** Finds the session in the catalog list; null when absent. */
    private interface Lookup {
        ManagedAgentSession find(List<ManagedAgentSession> sessions);
    }
}
