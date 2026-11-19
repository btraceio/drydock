package app.drydock.app;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.TaskRelation;
import app.drydock.state.ApplicationStateRepository;
import app.drydock.testing.FxSync;
import app.drydock.testing.TestPaths;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerWaitChildReleaseFxTest {

    private ExecutorService backgroundExecutor;

    @AfterEach
    void tearDown() {
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdownNow();
        }
    }

    @Test
    void onlyUserDeletedParentWaitsChildWithSurvivingParentReleasesOnFxThread() throws Exception {
        FxSync.ensurePlatform();

        ManagedAgentSession parent = SessionManagerTest.newSessionFixture();
        ManagedAgentSession waitingChild = childOf(parent.id(), TaskRelation.PARENT_WAITS);
        ManagedAgentSession followUpChild = childOf(parent.id(), TaskRelation.FOLLOW_UP);
        ManagedAgentSession mergedChild = childOf(parent.id(), TaskRelation.PARENT_WAITS);
        ManagedAgentSession handedOffChild = childOf(parent.id(), TaskRelation.PARENT_WAITS);
        ManagedAgentSession orphanedChild = childOf(ManagedSessionId.newId(), TaskRelation.PARENT_WAITS);

        InMemoryStateRepository repository = new InMemoryStateRepository(List.of(
                parent, waitingChild, followUpChild, mergedChild, handedOffChild, orphanedChild));
        backgroundExecutor = Executors.newVirtualThreadPerTaskExecutor();
        AgentContext context = new AgentContext(TestPaths.abs("/tmp/drydock-test"),
                TestPaths.abs("/tmp/drydock-test/activity"), backgroundExecutor);
        SessionManager manager = new SessionManager(repository, new AgentRegistry(List.of(), context), backgroundExecutor);
        List<ObservedRelease> releases = new CopyOnWriteArrayList<>();
        manager.setWaitChildReleaseObserver((observedParent, child) -> releases.add(
                new ObservedRelease(observedParent, child, Platform.isFxApplicationThread())));

        // The default overload is a user deletion and releases a waiting child.
        manager.deleteSession(waitingChild.id()).get(10, TimeUnit.SECONDS);
        FxSync.waitForFxEvents();
        assertEquals(1, releases.size());
        assertSame(parent, releases.get(0).parent());
        assertSame(waitingChild, releases.get(0).child());
        assertTrue(releases.get(0).onFxThread());

        // Relation, deletion intent, and parent survival are independent guards.
        manager.deleteSession(followUpChild.id()).get(10, TimeUnit.SECONDS);
        FxSync.waitForFxEvents();
        assertEquals(1, releases.size(), "a FOLLOW_UP child does not release a parent-waits gate");

        manager.deleteSession(mergedChild.id(), SessionManager.DeletionReason.MERGED_AND_FINISHED)
                .get(10, TimeUnit.SECONDS);
        FxSync.waitForFxEvents();
        assertEquals(1, releases.size(), "successful merge-and-finish is not a user deletion");

        manager.deleteSession(handedOffChild.id(), SessionManager.DeletionReason.HANDED_OFF)
                .get(10, TimeUnit.SECONDS);
        FxSync.waitForFxEvents();
        assertEquals(1, releases.size(), "handing a session to a successor is not a user deletion");

        manager.deleteSession(orphanedChild.id()).get(10, TimeUnit.SECONDS);
        FxSync.waitForFxEvents();
        assertEquals(1, releases.size(), "a missing parent cannot receive a release notification");
    }

    private static ManagedAgentSession childOf(ManagedSessionId parentId, TaskRelation relation) {
        return SessionManagerTest.newSessionFixture()
                .withTaskRelation(Optional.of(parentId), Optional.of(relation));
    }

    private record ObservedRelease(ManagedAgentSession parent, ManagedAgentSession child, boolean onFxThread) { }

    private static final class InMemoryStateRepository implements ApplicationStateRepository {
        private volatile ApplicationState state;

        private InMemoryStateRepository(List<ManagedAgentSession> sessions) {
            state = ApplicationState.empty().withSessions(sessions);
        }

        @Override
        public ApplicationState load() {
            return state;
        }

        @Override
        public void save(ApplicationState newState) {
            state = newState;
        }
    }
}
