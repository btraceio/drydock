package app.drydock.intellij.tasks;

import app.drydock.agent.api.ActivityReporter;
import app.drydock.agent.api.AgentCapabilities;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.McpDelivery;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.api.SphinxReviewSupport;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review actions' presentation answers, which their {@code update()}
 * computes on a background thread: the quick review label and whether
 * Review in Sphinx is offered. Driven over a real {@link AgentRegistry}
 * built from fake providers, a root -> id map and a repository list — the
 * same inputs the panel passes from its precomputed map and the state store.
 */
class TaskTreeReviewPresentationTest {

    private static final Path ROOT = Path.of("/work/profiling-backend");
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    @Test
    void quickReviewNamesTheRememberedModelOfTheReviewHarness(@TempDir Path base) {
        AgentRegistry registry = registry(base, new FakeProvider(AgentKind.CLAUDE, true, false),
                new FakeProvider(AgentKind.PI, true, false));
        Repository repository = repository(RepositorySettings.DEFAULT.withLastUsedAgent(AgentKind.PI)
                .withLastUsedModel(AgentKind.PI, Optional.of("sonnet"))
                .withLastUsedModel(AgentKind.CLAUDE, Optional.of("opus")));

        assertEquals("Review (sonnet)", TaskTreePanel.quickReviewLabel(
                Map.of(ROOT, repository.id()), List.of(repository), registry, ROOT));
    }

    @Test
    void quickReviewSaysDefaultWithoutARememberedModelOrAKnownRoot(@TempDir Path base) {
        AgentRegistry registry = registry(base, new FakeProvider(AgentKind.CLAUDE, true, false));
        Repository repository = repository(RepositorySettings.DEFAULT);

        assertEquals("Review (default)", TaskTreePanel.quickReviewLabel(
                Map.of(ROOT, repository.id()), List.of(repository), registry, ROOT));
        // A root the last rebuild did not map yet reads "default", never another repository's model.
        Repository other = repository(RepositorySettings.DEFAULT.withLastUsedAgent(AgentKind.CLAUDE)
                .withLastUsedModel(AgentKind.CLAUDE, Optional.of("opus")));
        assertEquals("Review (default)", TaskTreePanel.quickReviewLabel(
                Map.of(), List.of(other), registry, ROOT));
        assertEquals("Review (default)", TaskTreePanel.quickReviewLabel(Map.of(), List.of(), null, null));
    }

    @Test
    void sphinxIsOfferedOnlyWhenTheReviewHarnessReachesIt(@TempDir Path base) {
        AgentRegistry registry = registry(base, new FakeProvider(AgentKind.CLAUDE, true, true),
                new FakeProvider(AgentKind.PI, true, false));
        Repository claude = repository(new RepositorySettings(Optional.of(AgentKind.CLAUDE)));
        Repository pi = repository(new RepositorySettings(Optional.of(AgentKind.PI)));

        assertTrue(TaskTreePanel.sphinxAvailable(Map.of(ROOT, claude.id()), List.of(claude, pi), registry, ROOT));
        assertFalse(TaskTreePanel.sphinxAvailable(Map.of(ROOT, pi.id()), List.of(claude, pi), registry, ROOT));
        assertFalse(TaskTreePanel.sphinxAvailable(Map.of(), List.of(claude), registry, null));
    }

    private static Repository repository(RepositorySettings settings) {
        return new Repository(RepositoryId.newId(), ROOT, "profiling-backend", NOW, NOW, settings);
    }

    private static AgentRegistry registry(Path base, AgentProvider... providers) {
        return new AgentRegistry(List.of(providers),
                new AgentContext(base, base.resolve("activity"), new SameThreadExecutorService()));
    }

    /** A provider fake: availability and the sphinx probe are the answers under test; the rest is inert. */
    private static final class FakeProvider implements AgentProvider {

        private final AgentKind kind;
        private final boolean available;
        private final boolean sphinx;

        FakeProvider(AgentKind kind, boolean available, boolean sphinx) {
            this.kind = kind;
            this.available = available;
            this.sphinx = sphinx;
        }

        @Override public AgentKind kind() { return kind; }
        @Override public String displayName() { return "Fake " + kind; }
        @Override public void init(AgentContext ctx) { }
        @Override public Optional<Path> locateExecutable() {
            return available ? Optional.of(Path.of("/bin/true")) : Optional.empty();
        }
        @Override public String describeSearched() { return "PATH"; }
        @Override public AgentCapabilities probeCapabilities() { return new AgentCapabilities(true, true, "1.0"); }
        @Override public boolean supportsRemote() { return false; }
        @Override public McpDelivery mcpDelivery() { return McpDelivery.NONE; }
        @Override public LaunchPlan buildCreateCommand(CreateContext c) { return LaunchPlan.of("fake create", false); }
        @Override public LaunchPlan buildResumeCommand(ResumeContext r) { return LaunchPlan.of("fake resume", false); }
        @Override public SessionIdStrategy idStrategy() { return SessionIdStrategy.PRESET; }
        @Override public Optional<ConversationSource> conversations() { return Optional.empty(); }
        @Override public Optional<ActivityReporter> activity() { return Optional.empty(); }
        @Override public Optional<SessionIdDiscovery> idDiscovery() { return Optional.empty(); }
        @Override public Optional<SphinxReviewSupport> sphinxReview() {
            return sphinx ? Optional.of(new SphinxReviewSupport())
                    : Optional.empty();
        }
    }

    /** The registry's AgentContext wants an ExecutorService; this one runs everything inline. */
    private static final class SameThreadExecutorService extends AbstractExecutorService {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }
}
