package app.drydock.intellij;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.McpDelivery;
import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.WorkspaceUiState;
import app.drydock.git.DiffScope;
import app.drydock.git.ReviewBase;
import app.drydock.git.UnifiedDiff;
import app.drydock.intellij.remote.RemoteForwardOwner;
import app.drydock.intellij.remote.RemoteReviewService;
import app.drydock.intellij.review.ReviewBackend;
import app.drydock.review.RemoteSnapshotCache;
import app.drydock.review.ReviewDataSnapshot;
import app.drydock.review.ReviewDataSource;
import app.drydock.workspace.RemoteEnrollmentEvidence.Verdict;
import app.drydock.mcp.McpSessionContext.RenameKind;
import app.drydock.mcp.McpSessionContext.RenameOutcome;
import app.drydock.mcp.RemoteAuthorizationPermit;
import app.drydock.sdlc.ResourceManifestJournal;
import app.drydock.sdlc.SessionWorkflowResourceOwner;
import app.drydock.state.JsonApplicationStateRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrydockPluginServiceTest {

    private static ManagedAgentSession session(SessionStatus status) {
        return new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), "Session",
                AgentBinding.unlaunched(AgentKind.CLAUDE),
                new SessionWorkspace(Path.of("/tmp/wt"), Optional.of(Path.of("/tmp/wt")), true),
                Instant.parse("2026-08-12T10:15:30Z"))
                .status(status)
                .build();
    }

    private static ApplicationState stateWith(ManagedAgentSession... sessions) {
        return new ApplicationState(List.of(), List.of(sessions), List.of(),
                WorkspaceUiState.empty(), List.of());
    }

    @Test
    void startingAndRunningSessionsNormalizeToInactive() {
        ManagedAgentSession starting = session(SessionStatus.STARTING);
        ManagedAgentSession running = session(SessionStatus.RUNNING);

        ApplicationState normalized = DrydockPluginService.normalizeOnLoad(stateWith(starting, running));

        List<ManagedAgentSession> sessions = normalized.sessions();
        assertEquals(2, sessions.size());
        assertEquals(SessionStatus.INACTIVE, sessions.get(0).status());
        assertEquals(SessionStatus.INACTIVE, sessions.get(1).status());
        // Only the status changes: identity, repository, and the rest survive.
        assertEquals(starting.id(), sessions.get(0).id());
        assertEquals(starting.repositoryId(), sessions.get(0).repositoryId());
        assertEquals(starting.binding(), sessions.get(0).binding());
        assertEquals(starting.workspace(), sessions.get(0).workspace());
        assertEquals(running.id(), sessions.get(1).id());
    }

    @Test
    void otherStatusesAreUntouched() {
        ManagedAgentSession inactive = session(SessionStatus.INACTIVE);
        ManagedAgentSession exited = session(SessionStatus.EXITED);
        ManagedAgentSession failed = session(SessionStatus.FAILED);
        ManagedAgentSession missing = session(SessionStatus.MISSING_WORKING_DIRECTORY);
        ManagedAgentSession unsupported = session(SessionStatus.UNSUPPORTED_AGENT);

        ApplicationState state = stateWith(inactive, exited, failed, missing, unsupported);
        ApplicationState normalized = DrydockPluginService.normalizeOnLoad(state);

        // Nothing to change: the same state instance comes back (so the
        // single writer's equals check skips the save entirely).
        assertSame(state, normalized);
        assertEquals(inactive, normalized.sessions().get(0));
        assertEquals(exited, normalized.sessions().get(1));
        assertEquals(failed, normalized.sessions().get(2));
        assertEquals(missing, normalized.sessions().get(3));
        assertEquals(unsupported, normalized.sessions().get(4));
    }

    @Test
    void initializationNormalizesPersistedStateOffTheTestThread(@TempDir Path base) throws Exception {
        // Seed the state file the service must find at base/state.json —
        // a leftover RUNNING session from a previous run plus one that was
        // already dead.
        new JsonApplicationStateRepository(base.resolve("state.json"))
                .save(stateWith(session(SessionStatus.RUNNING), session(SessionStatus.EXITED)));

        DrydockPluginService service = new DrydockPluginService(base);
        CountDownLatch done = new CountDownLatch(1);
        service.initialization().whenComplete((unused, failure) -> done.countDown());
        assertTrue(done.await(60, TimeUnit.SECONDS), "initialization did not complete");

        // The init work ran on the service's executor, never on this thread.
        assertNotNull(service.initializationThread());
        assertNotSame(Thread.currentThread(), service.initializationThread());

        ApplicationStateStore store = service.stateStore();
        List<ManagedAgentSession> sessions = store.state().sessions();
        assertEquals(2, sessions.size());
        // Execution-sessions migration: the persisted statuses decode as IDLE
        // at load (codec seam), so init finds nothing left to normalize — a
        // leftover RUNNING row and an EXITED one both surface as IDLE.
        assertEquals(SessionStatus.IDLE, sessions.get(0).status());
        assertEquals(SessionStatus.IDLE, sessions.get(1).status());

        // Base-dir composition: the state was found and persisted through the
        // repository rooted at the injected base dir.
        assertEquals(base, service.baseDir());
        assertTrue(Files.exists(base.resolve("state.json")));

        service.dispose();
    }

    @Test
    void disposeFlushesPendingStateAndShutsTheExecutorDown(@TempDir Path base) throws Exception {
        DrydockPluginService service = new DrydockPluginService(base);
        service.initialization().get(60, TimeUnit.SECONDS);

        ManagedAgentSession added = session(SessionStatus.INACTIVE);
        service.stateStore().update(state -> state.withSessions(
                List.of(session(SessionStatus.INACTIVE), added)));

        service.dispose();

        assertTrue(service.executor().isShutdown(), "dispose must shut the executor down");
        // flush() in dispose persisted the update added after init.
        ApplicationState onDisk = new JsonApplicationStateRepository(base.resolve("state.json")).load();
        assertEquals(2, onDisk.sessions().size());
        assertEquals(added.id(), onDisk.sessions().get(1).id());
    }

    @Test
    void testServiceKeepsTheSharedProjectsListInItsOwnDirectory(@TempDir Path base) throws Exception {
        DrydockPluginService service = new DrydockPluginService(base);
        service.initialization().get(60, TimeUnit.SECONDS);
        try {
            service.sharedProjects().register(base.resolve("repo"), "repo").get(60, TimeUnit.SECONDS);

            assertTrue(Files.exists(base.resolve("projects.json")), "the test seam must not use ~/.drydock");
            assertEquals(1, service.sharedProjects().projects().size());
        } finally {
            service.dispose();
        }
    }

    @Test
    void disposeBeforeInitializationCompletesIsSafe(@TempDir Path base) throws Exception {
        DrydockPluginService service = new DrydockPluginService(base);
        // Disposal races init: it must wait for it (bounded) rather than
        // null-flush while the normalization update is still in flight.
        service.dispose();
        service.initialization().get(5, TimeUnit.SECONDS);
        assertTrue(service.executor().isShutdown());
    }

    @Test
    void initializationStartsTheReviewBackendOffTheTestThread(@TempDir Path base) throws Exception {
        DrydockPluginService service = new DrydockPluginService(base);
        service.initialization().get(60, TimeUnit.SECONDS);
        try {
            ReviewBackend backend = service.reviewBackend().orElseThrow();
            assertTrue(backend.ready());
            assertNotSame(Thread.currentThread(), service.initializationThread());
            service.dispose();
            assertFalse(backend.ready(), "dispose must close the backend");
        } finally {
            service.dispose();
        }
    }

    @Test
    void aBackendThatCannotBeBuiltLeavesInitHealthy(@TempDir Path base) throws Exception {
        DrydockPluginService service = new DrydockPluginService(base, (dir, sessions, repositories, registry) -> {
            throw new IllegalStateException("no backend");
        });
        try {
            service.initialization().get(60, TimeUnit.SECONDS);

            assertTrue(service.reviewBackend().isEmpty());
            assertNotNull(service.stateStore());
            assertTrue(service.reviewAccess()
                    .accessFor(ManagedSessionId.newId(), McpDelivery.CONFIG_FILE).isEmpty());
        } finally {
            service.dispose();
        }
    }

    @Test
    void aBackendBuiltAfterDisposeTimedOutIsClosed(@TempDir Path base) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicReference<ReviewBackend> built = new AtomicReference<>();
        DrydockPluginService service = new DrydockPluginService(base, (dir, sessions, repositories, registry) -> {
            entered.countDown();
            try {
                proceed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ReviewBackend backend = new ReviewBackend(dir, sessions, repositories, registry);
            built.set(backend);
            return backend;
        }, Duration.ofMillis(200));
        assertTrue(entered.await(60, TimeUnit.SECONDS));

        service.dispose();
        proceed.countDown();
        // Init may fail once the executor is shut down; that is tolerated.
        service.initialization().handle((value, error) -> null).get(60, TimeUnit.SECONDS);

        ReviewBackend backend = built.get();
        assertNotNull(backend, "the factory must have built a backend");
        assertFalse(backend.ready(), "a backend built after dispose must be closed");
        assertTrue(service.reviewBackend().isEmpty(), "a backend built after dispose must not be published");
    }

    @Test
    void treeSitterLibrariesGoToTheGivenDirectoryUnlessAlreadyConfigured(@TempDir Path dir) {
        String before = System.getProperty(DrydockPluginService.TREE_SITTER_LIB_PROPERTY);
        try {
            System.clearProperty(DrydockPluginService.TREE_SITTER_LIB_PROPERTY);
            DrydockPluginService.useOwnTreeSitterLibraries(dir.resolve("ours"));
            assertEquals(dir.resolve("ours").toString(),
                    System.getProperty(DrydockPluginService.TREE_SITTER_LIB_PROPERTY));

            DrydockPluginService.useOwnTreeSitterLibraries(dir.resolve("other"));
            assertEquals(dir.resolve("ours").toString(),
                    System.getProperty(DrydockPluginService.TREE_SITTER_LIB_PROPERTY),
                    "a directory already configured is kept");
        } finally {
            if (before == null) {
                System.clearProperty(DrydockPluginService.TREE_SITTER_LIB_PROPERTY);
            } else {
                System.setProperty(DrydockPluginService.TREE_SITTER_LIB_PROPERTY, before);
            }
        }
    }

    // ---- retained workflow resources (T14: INV-32, INV-37, AC-26) -------------------------------

    @Test
    void theOneResourceOwnerIsConstructedOnceAndUsableAtOnce(@TempDir Path base) throws Exception {
        DrydockPluginService service = new DrydockPluginService(base);
        try {
            SessionWorkflowResourceOwner owner = service.workflowResources();
            assertNotNull(owner, "constructed with the service, before init completes");
            service.initialization().get(60, TimeUnit.SECONDS);
            assertSame(owner, service.workflowResources());
        } finally {
            service.dispose();
        }
    }

    @Test
    void startupRecoveryRunsOffTheEdtAdoptsRetainedAndCleansOnlyAbandonedPending(@TempDir Path base)
            throws Exception {
        String session = ManagedSessionId.newId().toString();
        ResourceManifestJournal seed = ResourceManifestJournal.open(base.resolve("sdlc/manifest.rmj"));
        seed.putPending(session, "kept", 10, 0);
        seed.retain(session, "kept");
        seed.putPending(session, "abandoned", 10, 0);
        seed.flush();
        Path sessionDir = base.resolve("sdlc/staging").resolve(URLEncoder.encode(session, StandardCharsets.UTF_8));
        Files.createDirectories(sessionDir.resolve("kept"));
        Files.createDirectories(sessionDir.resolve("abandoned"));
        Files.writeString(sessionDir.resolve("abandoned/file"), "x");

        DrydockPluginService service = new DrydockPluginService(base);
        try {
            service.initialization().get(60, TimeUnit.SECONDS);
            service.workflowRecovery().get(60, TimeUnit.SECONDS);

            assertNotNull(service.recoveryThread());
            assertNotSame(Thread.currentThread(), service.recoveryThread());
            assertSame(service.initializationThread(), service.recoveryThread(),
                    "recovery runs inside initialization, after the state store loaded");
            assertEquals("kept", service.workflowResources().requireRetained(session).get(0).bundleId());
            assertFalse(Files.exists(sessionDir.resolve("abandoned")), "abandoned pending is cleaned");
            assertTrue(Files.exists(sessionDir.resolve("kept")), "retained is never deleted by recovery");
        } finally {
            service.dispose();
        }
    }

    @Test
    void aFailingResourceFlushDoesNotSkipTheStateFlushOrTheExecutorShutdown(@TempDir Path base) throws Exception {
        // base/sdlc is a regular file, so the owner's journal flush at dispose throws.
        Files.writeString(base.resolve("sdlc"), "not a directory");
        DrydockPluginService service = new DrydockPluginService(base);
        service.initialization().get(60, TimeUnit.SECONDS);
        ManagedAgentSession added = session(SessionStatus.INACTIVE);
        service.stateStore().update(state -> state.withSessions(List.of(added)));
        assertThrows(IOException.class, () -> service.workflowResources().flush());

        service.dispose();

        assertTrue(service.executor().isShutdown(), "the executor shutdown is not skipped");
        ApplicationState onDisk = new JsonApplicationStateRepository(base.resolve("state.json")).load();
        assertEquals(added.id(), onDisk.sessions().get(0).id(), "the state flush is not skipped");
    }

    // ---- detached remote rename (application-owned callback) ----

    private static final class TestFence implements RemoteAuthorizationPermit.Fence {
        private final Object lock = new Object();
        private boolean revoked;

        @Override
        public Object lock() {
            return lock;
        }

        @Override
        public boolean revoked() {
            return revoked;
        }

        void revoke() {
            synchronized (lock) {
                revoked = true;
            }
        }
    }

    private static DrydockPluginService initialized(Path base, ManagedAgentSession... rows) throws Exception {
        DrydockPluginService service = new DrydockPluginService(base);
        service.initialization().get(60, TimeUnit.SECONDS);
        service.stateStore().update(state -> state.withSessions(List.of(rows)));
        return service;
    }

    private static String nameOf(DrydockPluginService service, ManagedSessionId id) {
        return service.stateStore().state().sessions().stream()
                .filter(row -> row.id().equals(id)).findFirst().orElseThrow().displayName();
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(30, TimeUnit.SECONDS));
        return e.getCause();
    }

    @Test
    void detachedRenameCommitsWithoutAnyAgentExecution(@TempDir Path base) throws Exception {
        ManagedAgentSession row = session(SessionStatus.IDLE);
        DrydockPluginService service = initialized(base, row);
        AtomicInteger published = new AtomicInteger();

        RenameOutcome outcome = service.renameRemoteFromAgent(row.id(), "Remote title",
                new RemoteAuthorizationPermit(row.id(), 1, new TestFence()),
                service.initialization(), published::incrementAndGet).get(30, TimeUnit.SECONDS);

        assertEquals(RenameKind.RENAMED, outcome.kind());
        assertEquals("Remote title", nameOf(service, row.id()));
        assertEquals(1, published.get());
        service.dispose();
    }

    @Test
    void revocationWinningTheBackgroundRaceLeavesStateUntouchedAndUnpublished(@TempDir Path base) throws Exception {
        ManagedAgentSession row = session(SessionStatus.IDLE);
        DrydockPluginService service = initialized(base, row);
        TestFence fence = new TestFence();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicInteger published = new AtomicInteger();

        CompletableFuture<RenameOutcome> pending = service.renameRemoteFromAgent(row.id(), "Late",
                new RemoteAuthorizationPermit(row.id(), 1, fence), gate, published::incrementAndGet);
        fence.revoke();
        gate.complete(null);

        assertInstanceOf(RemoteAuthorizationPermit.StaleAuthorizationException.class, failureOf(pending));
        assertEquals("Session", nameOf(service, row.id()));
        assertEquals(0, published.get());
        service.dispose();
    }

    @Test
    void completedCommitSurvivesDroppedResponseAndRevocationWithoutReplay(@TempDir Path base) throws Exception {
        ManagedAgentSession row = session(SessionStatus.IDLE);
        DrydockPluginService service = initialized(base, row);
        TestFence fence = new TestFence();
        AtomicInteger published = new AtomicInteger();
        RemoteAuthorizationPermit permit = new RemoteAuthorizationPermit(row.id(), 1, fence);

        // The response is dropped: the future is never consumed. Wait only for the state to change.
        CompletableFuture<RenameOutcome> dropped = service.renameRemoteFromAgent(row.id(), "Once", permit,
                service.initialization(), published::incrementAndGet);
        dropped.get(30, TimeUnit.SECONDS);
        fence.revoke();

        assertInstanceOf(RemoteAuthorizationPermit.StaleAuthorizationException.class,
                failureOf(service.renameRemoteFromAgent(row.id(), "Once", permit,
                        service.initialization(), published::incrementAndGet)));
        // Reconnect: a fresh permit retrying the same title is UNCHANGED, never a second rename.
        RenameOutcome retried = service.renameRemoteFromAgent(row.id(), "Once",
                new RemoteAuthorizationPermit(row.id(), 2, new TestFence()),
                service.initialization(), published::incrementAndGet).get(30, TimeUnit.SECONDS);

        assertEquals(RenameKind.UNCHANGED, retried.kind());
        assertEquals("Once", nameOf(service, row.id()));
        assertEquals(1, published.get());
        service.dispose();
    }

    @Test
    void foreignAbsentStalePermitsAndMissingRowsAreRefusedWithoutRecreation(@TempDir Path base) throws Exception {
        ManagedAgentSession row = session(SessionStatus.IDLE);
        DrydockPluginService service = initialized(base, row);
        ManagedSessionId other = ManagedSessionId.newId();
        TestFence fence = new TestFence();

        assertInstanceOf(RemoteAuthorizationPermit.StaleAuthorizationException.class, failureOf(
                service.renameRemoteFromAgent(row.id(), "X", new RemoteAuthorizationPermit(other, 1, fence))));
        assertInstanceOf(RemoteAuthorizationPermit.StaleAuthorizationException.class,
                failureOf(service.renameRemoteFromAgent(row.id(), "X", null)));
        fence.revoke();
        assertInstanceOf(RemoteAuthorizationPermit.StaleAuthorizationException.class, failureOf(
                service.renameRemoteFromAgent(row.id(), "X", new RemoteAuthorizationPermit(row.id(), 1, fence))));
        // Missing row: the matching live permit is refused by the mutation, and nothing is created.
        assertInstanceOf(IllegalStateException.class, failureOf(service.renameRemoteFromAgent(other, "X",
                new RemoteAuthorizationPermit(other, 1, new TestFence()))));

        assertEquals("Session", nameOf(service, row.id()));
        assertEquals(1, service.stateStore().state().sessions().size());
        service.dispose();
    }

    @Test
    void humanPinRacingTheRenameYieldsExactlyOneConsistentOutcome(@TempDir Path base) throws Exception {
        ManagedAgentSession row = session(SessionStatus.IDLE);
        DrydockPluginService service = initialized(base, row);
        // A sibling with the colliding name in the same repository.
        ManagedAgentSession sibling = row.toBuilder().id(ManagedSessionId.newId()).displayName("Taken").build();
        service.stateStore().update(state -> state.withSessions(List.of(row, sibling)));
        RemoteAuthorizationPermit permit = new RemoteAuthorizationPermit(row.id(), 1, new TestFence());

        assertEquals(RenameKind.COLLIDED, service.renameRemoteFromAgent(row.id(), "taken", permit,
                service.initialization(), () -> { }).get(30, TimeUnit.SECONDS).kind());

        CompletableFuture<RenameOutcome> rename = service.renameRemoteFromAgent(row.id(), "Agent", permit,
                service.initialization(), () -> { });
        Thread human = Thread.startVirtualThread(() -> service.stateStore().update(state -> state.withSessions(
                state.sessions().stream().map(existing -> existing.id().equals(row.id())
                        ? existing.withDisplayName("Human").withNamePinned(true) : existing).toList())));
        human.join(30_000);
        RenameOutcome outcome = rename.get(30, TimeUnit.SECONDS);

        ManagedAgentSession finalRow = service.stateStore().state().sessions().stream()
                .filter(existing -> existing.id().equals(row.id())).findFirst().orElseThrow();
        // Pin is never overwritten: either the pin won (PINNED, name Human), or the rename came first and
        // the human edit then replaced it -- the final row is always the human's pinned name.
        assertTrue(finalRow.namePinned());
        assertEquals("Human", finalRow.displayName());
        assertTrue(outcome.kind() == RenameKind.PINNED || outcome.kind() == RenameKind.RENAMED);
        service.dispose();
    }

    @Test
    void listenerRunsOutsidePermitFenceAndStateLockWithoutInversion(@TempDir Path base) throws Exception {
        ManagedAgentSession row = session(SessionStatus.IDLE);
        DrydockPluginService service = initialized(base, row);
        TestFence fence = new TestFence();
        AtomicBoolean holdsFence = new AtomicBoolean(true);
        AtomicBoolean stateWritableDuringListener = new AtomicBoolean();
        AtomicBoolean fenceTakenDuringListener = new AtomicBoolean();

        service.renameRemoteFromAgent(row.id(), "Fenced", new RemoteAuthorizationPermit(row.id(), 1, fence),
                service.initialization(), () -> {
                    holdsFence.set(Thread.holdsLock(fence.lock()));
                    // The state lock is held by no one: an update from another thread completes in time.
                    CompletableFuture<Void> probe = CompletableFuture.runAsync(() -> {
                        service.stateStore().update(state -> state);
                        synchronized (fence.lock()) {
                            fenceTakenDuringListener.set(true);
                        }
                    });
                    try {
                        probe.get(10, TimeUnit.SECONDS);
                        stateWritableDuringListener.set(true);
                    } catch (Exception e) {
                        // left false
                    }
                }).get(30, TimeUnit.SECONDS);

        assertFalse(holdsFence.get());
        assertTrue(stateWritableDuringListener.get());
        assertTrue(fenceTakenDuringListener.get());
        // Opposite order is never needed: state-then-fence acquisition from a revoker completes meanwhile.
        fence.revoke();
        assertEquals("Fenced", nameOf(service, row.id()));
        service.dispose();
    }

    private static RemoteReviewService.Session remoteSession() {
        return new RemoteReviewService.Session("box", "inst", ManagedSessionId.newId(), "agent", "nonce", 1,
                "/h.py", () -> new Verdict.Ready());
    }

    @Test
    void productionCompositionHasTheRealSharedCacheAndAFailClosedTransport(@TempDir Path base) throws Exception {
        DrydockPluginService service = new DrydockPluginService(base);
        try {
            service.initialization().get(60, TimeUnit.SECONDS);
            RemoteReviewService remote = service.remoteReview().orElseThrow();
            assertNotNull(service.remoteDataSource());
            RemoteSnapshotCache cache = service.remoteCache();
            assertNotNull(cache);
            RemoteReviewService.Session session = remoteSession();

            // The probes are fail-closed in production: a transport is refused, never presumed ready.
            assertThrows(RemoteForwardOwner.ForwardException.class, () -> remote.ensure(session));

            // The service's release drops THIS cache instance's session entry: shared identity.
            cache.put(session.id().toString(), new ReviewDataSnapshot("scope", ReviewDataSource.Backend.REMOTE, "co",
                    1, DiffScope.BASE, new ReviewBase("main", ReviewBase.Origin.PULL_REQUEST), "b", "h", "b", "fp",
                    new UnifiedDiff(List.of()), Map.of("p", new ReviewDataSnapshot.SourceFile(
                            new ReviewDataSnapshot.Content.Absent(), new ReviewDataSnapshot.Content.Text(new byte[64]))),
                    Instant.EPOCH));
            assertTrue(cache.totalBytes() > 0);
            remote.release(session, RemoteReviewService.ReleaseOutcome.DEATH);
            assertEquals(0, cache.totalBytes());
        } finally {
            service.dispose();
        }
    }

    @Test
    void disposalClosesTheRemoteServiceAndNoBackendMeansNoRemoteService(@TempDir Path base) throws Exception {
        DrydockPluginService service = new DrydockPluginService(base);
        service.initialization().get(60, TimeUnit.SECONDS);
        RemoteReviewService remote = service.remoteReview().orElseThrow();
        service.dispose();
        RemoteForwardOwner.ForwardException refused = assertThrows(RemoteForwardOwner.ForwardException.class,
                () -> remote.ensure(remoteSession()));
        assertTrue(refused.getMessage().contains("closed"), "disposal closed the service: " + refused.getMessage());

        DrydockPluginService bare = new DrydockPluginService(base.resolve("bare"), (dir, sessions, repositories, registry) -> {
            throw new IllegalStateException("no backend");
        });
        try {
            bare.initialization().get(60, TimeUnit.SECONDS);
            assertTrue(bare.remoteReview().isEmpty());
        } finally {
            bare.dispose();
        }
    }
}
