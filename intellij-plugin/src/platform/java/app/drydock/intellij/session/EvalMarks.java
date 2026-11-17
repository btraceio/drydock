package app.drydock.intellij.session;

import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.domain.ManagedAgentSession;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * The one implementation of the eval unmark both hosts of the plugin's
 * session orchestration share ({@code DrydockSessionService} and {@code
 * SessionEnds}): reversing an eval session's out-of-band provider marking
 * (e.g. Claude's proxy side channel) on a background executor. One copy,
 * not two — a lifecycle-critical cleanup duplicated across classes drifts.
 */
final class EvalMarks {

    private static final Logger LOG = System.getLogger(EvalMarks.class.getName());

    private EvalMarks() {
    }

    /**
     * Reverses {@code session}'s eval marking under {@code key} on {@code
     * executor}. No-op for non-eval sessions and for providers with no
     * marking (Pi injects eval through the launch env alone, and {@code
     * markEvalSession} is a default no-op there), and for blank keys —
     * there is no key to unmark by. The provider's unmark is idempotent,
     * so unmarking a key that was never marked, or an end racing a later
     * close, is safe.
     */
    static void unmarkAsync(Supplier<AgentRegistry> registry, Executor executor,
                            ManagedAgentSession session, String key) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(session, "session");
        if (!session.evalMode() || key == null || key.isBlank()) {
            return;
        }
        AgentProvider provider = registry.get().provider(session.agentKind()).orElse(null);
        if (provider == null) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    provider.unmarkEvalSession(key);
                } catch (RuntimeException | Error e) {
                    LOG.log(Level.WARNING, () -> "Eval unmark for " + session.id() + " failed: " + e);
                }
            });
        } catch (RejectedExecutionException e) {
            // Shutdown drained the executor; the proxy's mark is in-memory
            // and dies with the proxy's own restart, so nothing leaks
            // permanently.
            LOG.log(Level.DEBUG, "Skipping eval unmark for " + session.id() + " during shutdown");
        }
    }
}
