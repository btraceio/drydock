package app.drydock.intellij.session;

import app.drydock.agent.api.AgentKind;
import app.drydock.babysit.BabysitPairing;
import app.drydock.babysit.BabysitState;
import app.drydock.babysit.BabysitWriteGuard.Verdict;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionWorkspace;
import app.drydock.app.ApplicationStateStore;
import app.drydock.git.PrMonitorRead.PrSnapshot;
import app.drydock.state.ApplicationStateRepository;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The babysitting engagement's life follows the watched session's: the
 * suspend and resume cascades flip exactly the pairings of the named
 * watched session, publish once per real change, and are idempotent — in a
 * booted IDE, because the cascades consult the application-wide execution
 * registry's liveness before they act.
 *
 * <p>The babysitter row is deliberately local and unbound (no agent session
 * id): an unbound local babysitter that does not run is nothing to stop and
 * nothing to resume, so the cascades need no project session service and
 * the test pins the pairing bookkeeping alone. The stop and resume side
 * effects on a live agent are the session service's and the waker's
 * contracts, tested there.</p>
 */
public class BabysitMonitorLifecyclePlatformTest extends BasePlatformTestCase {

    private static final Instant OPENED_AT = Instant.parse("2026-11-19T10:15:30Z");

    private static final class InMemoryRepository implements ApplicationStateRepository {

        private ApplicationState state = ApplicationState.empty();

        @Override
        public ApplicationState load() {
            return state;
        }

        @Override
        public void save(ApplicationState saved) {
            state = saved;
        }
    }

    private ApplicationStateStore store;
    private BabysitMonitorService monitor;
    private final AtomicInteger published = new AtomicInteger();

    private ManagedSessionId watched;
    private ManagedSessionId babysitter;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        InMemoryRepository repository = new InMemoryRepository();
        store = ApplicationStateStore.forRepository(repository);
        watched = ManagedSessionId.newId();
        babysitter = ManagedSessionId.newId();
        store.update(state -> state
                .withSessions(List.of(localRow(watched), localRow(babysitter)))
                .withPairings(List.of(new BabysitPairing(babysitter, watched, RepositoryId.newId(), 42,
                        BabysitState.WATCHING))));
        monitor = new BabysitMonitorService(() -> store, Path.of("/never/babysit"),
                (root, repositoryName, prNumber) -> CompletableFuture.completedFuture(
                        new PrSnapshot.Failed("never read")),
                root -> Optional.empty(),
                (pairing, branch, base) -> CompletableFuture.completedFuture(
                        new Verdict.GitFailed("never checked")),
                (pairing, pointer) -> CompletableFuture.completedFuture(new BabysitWaker.WakeOutcome.Inactive()),
                published::incrementAndGet,
                pairing -> { },
                System::currentTimeMillis,
                0L, 0L, 0L,
                null);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (monitor != null) {
                monitor.dispose();
            }
        } finally {
            super.tearDown();
        }
    }

    private void assertPairingState(String message, BabysitState expected) {
        assertEquals(message, expected, store.state().pairings().get(0).state());
    }

    public void testSuspendsExactlyTheWatchedSessionsEngagement() {
        monitor.suspendEngagementsOf(watched);

        assertPairingState("the engagement suspends", BabysitState.SUSPENDED);
        assertEquals("one real change, one publish", 1, published.get());

        monitor.suspendEngagementsOf(watched);
        assertEquals("already suspended: no change, no publish", 1, published.get());
        monitor.suspendEngagementsOf(ManagedSessionId.newId());
        assertEquals("nothing watches that session: no change, no publish", 1, published.get());
    }

    public void testResumeReturnsTheSuspendedEngagementToWatching() {
        monitor.suspendEngagementsOf(watched);
        monitor.resumeEngagementsOf(watched);

        assertPairingState("the engagement resumes", BabysitState.WATCHING);
        assertEquals("two real changes, two publishes", 2, published.get());

        monitor.resumeEngagementsOf(watched);
        assertEquals("nothing suspended: no change, no publish", 2, published.get());
        monitor.resumeEngagementsOf(ManagedSessionId.newId());
        assertEquals("nothing suspended there: no change, no publish", 2, published.get());
    }

    public void testResumeOfNeverSuspendedEngagementDoesNothing() {
        monitor.resumeEngagementsOf(watched);

        assertPairingState("stays watching", BabysitState.WATCHING);
        assertEquals("no change, no publish", 0, published.get());
    }

    private static ManagedAgentSession localRow(ManagedSessionId id) {
        // An unbound row keeps the cascades off the session service: nothing
        // to stop, nothing to resume without a bound conversation.
        return new ManagedAgentSession.Builder(id, RepositoryId.newId(), "Row " + id,
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                SessionWorkspace.inRepository(Path.of("/tmp/local-" + id)),
                OPENED_AT)
                .build();
    }
}
