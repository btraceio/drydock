package app.drydock.intellij.actions;

import app.drydock.agent.api.ActivityReporter;
import app.drydock.agent.api.Agent;
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
import app.drydock.agent.spi.AgentProvider;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionWorkspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The T13 pure seams, pinned without opening a dialog (and without the
 * IDE): the default-agent resolution (AC8), the resume provider choice and
 * its disabled-when-empty rule (AC22), the newest-first conversation
 * ordering (AC20), and the availability/row rendering texts (AC6). The
 * Swing halves are the manual runIde gate (Pi create + Pi resume picker +
 * Claude resume).
 */
class SessionDialogsTest {

    // ---- fakes ------------------------------------------------------------------

    /**
     * A provider fake over the whole SPI: availability is what the registry
     * probes ({@code locateExecutable}), and the optional conversation
     * source is what the resume seams ask for. Everything else is inert.
     */
    static final class FakeProvider implements AgentProvider {

        private final AgentKind kind;
        private final boolean available;
        private final ConversationSource conversations;

        FakeProvider(AgentKind kind, boolean available, ConversationSource conversations) {
            this.kind = kind;
            this.available = available;
            this.conversations = conversations;
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
        @Override public Optional<ConversationSource> conversations() { return Optional.ofNullable(conversations); }
        @Override public Optional<ActivityReporter> activity() { return Optional.empty(); }
        @Override public Optional<SessionIdDiscovery> idDiscovery() { return Optional.empty(); }
    }

    /** A conversation source answering a fixed list regardless of the directory. */
    static final class FixedConversations implements ConversationSource {
        private final List<Conversation> conversations;

        FixedConversations(Conversation... conversations) {
            this.conversations = List.of(conversations);
        }

        @Override public List<Conversation> listConversations(Path workingDirectory) { return conversations; }
        @Override public boolean transcriptExists(Path workingDirectory, String agentSessionId) { return true; }
    }

    /** The registry's AgentContext wants an ExecutorService; this one runs everything inline. */
    static final class SameThreadExecutorService extends AbstractExecutorService {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    /** A fake of the registry's {@link Agent} view for the row-text seam. */
    static final class FakeAgent implements Agent {
        private final AgentKind kind;
        private final boolean available;

        FakeAgent(AgentKind kind, boolean available) {
            this.kind = kind;
            this.available = available;
        }

        @Override public AgentKind kind() { return kind; }
        @Override public String displayName() { return "Fake " + kind; }
        @Override public boolean isAvailable() { return available; }
        @Override public String describeSearched() { return "/usr/local/bin, /opt/homebrew/bin"; }
    }

    private static AgentRegistry registry(@TempDir Path base, AgentProvider... providers) {
        return new AgentRegistry(List.of(providers),
                new AgentContext(base, base.resolve("activity"), new SameThreadExecutorService()));
    }

    // ---- default agent (AC8) -------------------------------------------------

    @Test
    void defaultAgentPrefersLastUsedWhenStillAvailable(@TempDir Path base) {
        // CLAUDE is first in preferenceOrder; the repo's last-used PI must
        // still win because it is available (AC8: last-used wins).
        AgentRegistry registry = registry(base,
                new FakeProvider(AgentKind.CLAUDE, true, null),
                new FakeProvider(AgentKind.PI, true, null));
        assertEquals(Optional.of(AgentKind.PI),
                SessionDialogs.defaultAgent(registry, Optional.of(AgentKind.PI)));
    }

    @Test
    void defaultAgentFallsToPreferenceOrderFirstAvailable(@TempDir Path base) {
        // No last-used agent: the first AVAILABLE kind in preferenceOrder
        // (CLAUDE, CODEX, PI, TERMINAL) is the default — here CODEX, with
        // CLAUDE registered but unavailable (Q3: preferenceOrder unchanged).
        AgentRegistry registry = registry(base,
                new FakeProvider(AgentKind.CLAUDE, false, null),
                new FakeProvider(AgentKind.CODEX, true, null),
                new FakeProvider(AgentKind.PI, true, null));
        assertEquals(Optional.of(AgentKind.CODEX),
                SessionDialogs.defaultAgent(registry, Optional.empty()));
    }

    @Test
    void defaultAgentIgnoresAnUnavailableLastUsed(@TempDir Path base) {
        // The last-used agent is no longer installed: fall to the first
        // available in preferenceOrder instead of offering a dead default.
        AgentRegistry registry = registry(base,
                new FakeProvider(AgentKind.CLAUDE, false, null),
                new FakeProvider(AgentKind.PI, true, null));
        assertEquals(Optional.of(AgentKind.PI),
                SessionDialogs.defaultAgent(registry, Optional.of(AgentKind.CLAUDE)));
    }

    // ---- resume provider choice (AC22) ---------------------------------------

    @Test
    void resumeProviderEmptyWhenNoProviderListsConversations(@TempDir Path base) {
        // AC22's logic level: with no ConversationSource anywhere, the
        // action is disabled — chosenConversationKind is the decision.
        AgentRegistry registry = registry(base,
                new FakeProvider(AgentKind.CLAUDE, true, null),
                new FakeProvider(AgentKind.PI, true, null));
        assertTrue(SessionDialogs.chosenConversationKind(registry, Optional.empty()).isEmpty());
    }

    @Test
    void resumeProviderPrefersLastUsedThatHasConversations(@TempDir Path base) {
        ConversationSource none = new FixedConversations();
        ConversationSource some = new FixedConversations();
        AgentRegistry registry = registry(base,
                new FakeProvider(AgentKind.CLAUDE, true, some),
                new FakeProvider(AgentKind.PI, true, some));
        assertEquals(Optional.of(AgentKind.PI),
                SessionDialogs.chosenConversationKind(registry, Optional.of(AgentKind.PI)));
    }

    @Test
    void resumeProviderFallsPastLastUsedWithoutConversations(@TempDir Path base) {
        // The last-used agent (TERMINAL) exposes no conversation source:
        // the choice falls to the first kind with one in preferenceOrder.
        ConversationSource claudeSource = new FixedConversations();
        AgentRegistry registry = registry(base,
                new FakeProvider(AgentKind.CLAUDE, true, claudeSource),
                new FakeProvider(AgentKind.PI, true, null),
                new FakeProvider(AgentKind.TERMINAL, true, null));
        assertEquals(Optional.of(AgentKind.CLAUDE),
                SessionDialogs.chosenConversationKind(registry, Optional.of(AgentKind.TERMINAL)));
    }

    // ---- newest-first ordering (AC20) ----------------------------------------

    @Test
    void newestFirstOrdersConversationsByLastModifiedDescending() {
        ConversationSource.Conversation oldest = new ConversationSource.Conversation(
                "s-oldest", "Oldest", 3, Instant.parse("2026-08-10T10:00:00Z"));
        ConversationSource.Conversation newest = new ConversationSource.Conversation(
                "s-newest", "Newest", 5, Instant.parse("2026-08-12T10:00:00Z"));
        ConversationSource.Conversation middle = new ConversationSource.Conversation(
                "s-middle", "Middle", 1, Instant.parse("2026-08-11T10:00:00Z"));
        // Given oldest-first — the picker must show newest-first.
        List<ConversationSource.Conversation> ordered =
                SessionDialogs.newestFirst(List.of(oldest, middle, newest));
        assertEquals(List.of("s-newest", "s-middle", "s-oldest"),
                ordered.stream().map(ConversationSource.Conversation::sessionId).toList());
    }

    @Test
    void newestFirstBreaksTimestampTiesBySessionId() {
        Instant same = Instant.parse("2026-08-12T10:00:00Z");
        List<ConversationSource.Conversation> ordered = SessionDialogs.newestFirst(List.of(
                new ConversationSource.Conversation("b", "B", 1, same),
                new ConversationSource.Conversation("a", "A", 1, same)));
        // A total order, so identical timestamps still render deterministically.
        assertEquals(List.of("b", "a"),
                ordered.stream().map(ConversationSource.Conversation::sessionId).toList());
    }

    @Test
    void newestFirstDoesNotMutateTheGivenList() {
        List<ConversationSource.Conversation> given = List.of(
                new ConversationSource.Conversation("old", "Old", 1, Instant.parse("2026-08-10T10:00:00Z")),
                new ConversationSource.Conversation("new", "New", 1, Instant.parse("2026-08-12T10:00:00Z")));
        SessionDialogs.newestFirst(given);
        assertEquals(List.of("old", "new"),
                given.stream().map(ConversationSource.Conversation::sessionId).toList());
    }

    // ---- row texts (AC6 / AC20) ------------------------------------------------

    @Test
    void unavailableAgentRowCarriesTheSearchedText() {
        String row = SessionDialogs.agentRowText(new FakeAgent(AgentKind.PI, false));
        // AC6 UI half: an unavailable provider shows its describeSearched()
        // text, right in the row.
        assertTrue(row.contains("Fake PI"), row);
        assertTrue(row.contains("/usr/local/bin, /opt/homebrew/bin"), row);
        assertTrue(row.contains("not found"), row);
    }

    @Test
    void availableAgentRowIsJustTheDisplayName() {
        assertEquals("Fake PI", SessionDialogs.agentRowText(new FakeAgent(AgentKind.PI, true)));
    }

    @Test
    void conversationRowShowsTitleCountAndLastModified() {
        String row = SessionDialogs.conversationRowText(new ConversationSource.Conversation(
                "s1", "Fix the flaky test", 3, Instant.parse("2026-08-12T10:07:00Z")));
        assertTrue(row.contains("Fix the flaky test"), row);
        assertTrue(row.contains("3 messages"), row);
        // UTC, so the assertion is machine-independent.
        assertTrue(row.contains("2026-08-12 10:07"), row);
    }

    @Test
    void conversationRowSingularizesOneMessage() {
        String row = SessionDialogs.conversationRowText(new ConversationSource.Conversation(
                "s1", "Title", 1, Instant.parse("2026-08-12T10:07:00Z")));
        assertTrue(row.contains("1 message"), row);
        assertFalse(row.contains("1 messages"), row);
    }

    // ---- name suggestion ------------------------------------------------------

    @Test
    void suggestedNameCountsTheRepositorysExistingSessions() {
        assertEquals("Session 1", SessionDialogs.suggestedSessionName(0));
        assertEquals("Session 3", SessionDialogs.suggestedSessionName(2));
    }

    @Test
    void sessionCountFiltersByRepository(@TempDir Path base) {
        // The count behind the suggestion is per-repository: the next
        // session in repo B is not numbered after repo A's sessions.
        RepositoryId repoA = RepositoryId.newId();
        RepositoryId repoB = RepositoryId.newId();
        ApplicationState state = ApplicationState.empty()
                .withSessions(List.of(
                        session(AgentBinding.unlaunched(AgentKind.PI), ManagedSessionId.newId(), repoA),
                        session(AgentBinding.unlaunched(AgentKind.PI), ManagedSessionId.newId(), repoA)));
        assertEquals(2, SessionDialogs.sessionCount(state, repoA));
        assertEquals(0, SessionDialogs.sessionCount(state, repoB));
    }

    private static ManagedAgentSession session(AgentBinding binding, ManagedSessionId id, RepositoryId repositoryId) {
        return new ManagedAgentSession.Builder(
                id, repositoryId, "Session " + id,
                binding, SessionWorkspace.inRepository(Path.of("/tmp/repo")),
                Instant.parse("2026-08-12T10:00:00Z"))
                .build();
    }
}
