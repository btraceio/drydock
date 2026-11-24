package app.drydock.intellij.session;

import app.drydock.activity.SessionActivityWatcher;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.PullRequestTarget;
import app.drydock.domain.PrState;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.intellij.agent.LaunchClaims;
import app.drydock.intellij.review.SessionMcpAccess;
import app.drydock.state.JsonApplicationStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PullRequestAssociationServiceTest {
    @Test
    void targetIsPersistedAtLaunchAndExplicitChangesUseTheLatestSession(@TempDir Path base) throws Exception {
        var repositoryFile = new JsonApplicationStateRepository(base.resolve("state.json"));
        var store = ApplicationStateStore.forRepository(repositoryFile);
        var repository = new Repository(RepositoryId.newId(), base, "repo", Instant.EPOCH, Instant.EPOCH,
                RepositorySettings.DEFAULT);
        store.update(state -> state.withRepositories(List.of(repository)));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var provider = new DrydockSessionServiceTest.RecordingProvider(AgentKind.PI, SessionIdStrategy.PRESET,
                    null, true, false, null, new CopyOnWriteArrayList<>());
            var registry = new AgentRegistry(List.of(provider),
                    new AgentContext(base, base.resolve("activity"), executor));
            var consoles = new DrydockSessionServiceTest.FakeConsoleHost(new CopyOnWriteArrayList<>());
            var service = new DrydockSessionService(CompletableFuture.completedFuture(null), () -> store,
                    () -> registry, () -> new SessionActivityWatcher(base.resolve("activity"), executor),
                    path -> CompletableFuture.completedFuture(repository.id()), consoles, executor,
                    path -> List.of(), SessionMcpAccess.none(), new LaunchClaims(id -> false), null);
            var target = new PullRequestTarget("github.com/DataDog/java-profiler", 898);
            var prepared = service.prepareSession(base, AgentKind.PI, "target session").get(5, TimeUnit.SECONDS)
                    .toBuilder().targetPullRequest(Optional.of(target)).build();
            var running = service.launchSession(prepared).get(5, TimeUnit.SECONDS);
            assertEquals(Optional.of(target), running.targetPullRequest());
            service.renameSession(running.id(), "Renamed by the human").get(5, TimeUnit.SECONDS);
            var replacement = new PullRequestTarget(target.repository(), 943);
            var changed = service.associatePullRequest(running.id(), Optional.of(replacement))
                    .get(5, TimeUnit.SECONDS);
            assertEquals("Renamed by the human", changed.displayName());
            assertTrue(changed.namePinned());
            store.flush();
            assertEquals(Optional.of(replacement), repositoryFile.load().sessions().getFirst().targetPullRequest());
            var cleared = service.associatePullRequest(running.id(), Optional.empty()).get(5, TimeUnit.SECONDS);
            assertTrue(cleared.targetPullRequest().isEmpty());
            assertEquals(changed.pr(), cleared.pr());
            store.flush();
            assertTrue(repositoryFile.load().sessions().getFirst().targetPullRequest().isEmpty());
        }
    }

    @Test
    void recordPullRequestMergedPersistsTheStateAndIsIdempotent(@TempDir Path base) throws Exception {
        var repositoryFile = new JsonApplicationStateRepository(base.resolve("state.json"));
        var store = ApplicationStateStore.forRepository(repositoryFile);
        var repository = new Repository(RepositoryId.newId(), base, "repo", Instant.EPOCH, Instant.EPOCH,
                RepositorySettings.DEFAULT);
        store.update(state -> state.withRepositories(List.of(repository)));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var provider = new DrydockSessionServiceTest.RecordingProvider(AgentKind.PI, SessionIdStrategy.PRESET,
                    null, true, false, null, new CopyOnWriteArrayList<>());
            var registry = new AgentRegistry(List.of(provider),
                    new AgentContext(base, base.resolve("activity"), executor));
            var consoles = new DrydockSessionServiceTest.FakeConsoleHost(new CopyOnWriteArrayList<>());
            var service = new DrydockSessionService(CompletableFuture.completedFuture(null), () -> store,
                    () -> registry, () -> new SessionActivityWatcher(base.resolve("activity"), executor),
                    path -> CompletableFuture.completedFuture(repository.id()), consoles, executor,
                    path -> List.of(), SessionMcpAccess.none(), new LaunchClaims(id -> false), null);
            var launched = service.launchSession(
                    service.prepareSession(base, AgentKind.PI, "merged session").get(5, TimeUnit.SECONDS))
                    .get(5, TimeUnit.SECONDS);
            var merged = service.recordPullRequestMerged(launched.id(), 898).get(5, TimeUnit.SECONDS);
            assertEquals(PrState.MERGED, merged.prState());
            assertEquals(Optional.of(898), merged.prNumber());
            store.flush();
            assertEquals(PrState.MERGED, repositoryFile.load().sessions().getFirst().prState());
            // A re-observed merge changes nothing and returns the same state.
            var again = service.recordPullRequestMerged(launched.id(), 898).get(5, TimeUnit.SECONDS);
            assertEquals(merged.pr(), again.pr());
        }
    }

    @Test
    void discoveredPullRequestsPersistOpenLinksAndStaleLinksClear(@TempDir Path base) throws Exception {
        var repositoryFile = new JsonApplicationStateRepository(base.resolve("state.json"));
        var store = ApplicationStateStore.forRepository(repositoryFile);
        var repository = new Repository(RepositoryId.newId(), base, "repo", Instant.EPOCH, Instant.EPOCH,
                RepositorySettings.DEFAULT);
        store.update(state -> state.withRepositories(List.of(repository)));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var provider = new DrydockSessionServiceTest.RecordingProvider(AgentKind.PI, SessionIdStrategy.PRESET,
                    null, true, false, null, new CopyOnWriteArrayList<>());
            var registry = new AgentRegistry(List.of(provider),
                    new AgentContext(base, base.resolve("activity"), executor));
            var consoles = new DrydockSessionServiceTest.FakeConsoleHost(new CopyOnWriteArrayList<>());
            var service = new DrydockSessionService(CompletableFuture.completedFuture(null), () -> store,
                    () -> registry, () -> new SessionActivityWatcher(base.resolve("activity"), executor),
                    path -> CompletableFuture.completedFuture(repository.id()), consoles, executor,
                    path -> List.of(), SessionMcpAccess.none(), new LaunchClaims(id -> false), null);
            var discovered = service.launchSession(
                    service.prepareSession(base, AgentKind.PI, "branch carries a PR").get(5, TimeUnit.SECONDS))
                    .get(5, TimeUnit.SECONDS);
            var stale = service.launchSession(
                    service.prepareSession(base, AgentKind.PI, "branch moved on").get(5, TimeUnit.SECONDS))
                    .get(5, TimeUnit.SECONDS);
            // The stale session first carries a discovered open PR, then its branch stops carrying it.
            assertTrue(service
                    .recordDiscoveredPullRequests(Map.of(discovered.id(), 160, stale.id(), 161), Set.of())
                    .get(5, TimeUnit.SECONDS), "a first discovery changes the sessions");
            store.flush();
            var persisted = repositoryFile.load().sessions();
            assertEquals(PrState.OPEN, persisted.get(0).prState());
            assertEquals(Optional.of(160), persisted.get(0).prNumber());
            assertEquals(Optional.of(161), persisted.get(1).prNumber());

            assertFalse(service
                    .recordDiscoveredPullRequests(Map.of(discovered.id(), 160, stale.id(), 161), Set.of())
                    .get(5, TimeUnit.SECONDS),
                    "an identical re-observed answer is a no-op: no rewrite, no republish");
            store.flush();
            var unchanged = repositoryFile.load().sessions();
            assertEquals(Optional.of(160), unchanged.get(0).prNumber(), "an unchanged discovery is a no-op");
            assertEquals(Optional.of(161), unchanged.get(1).prNumber());

            assertTrue(service
                    .recordDiscoveredPullRequests(Map.of(discovered.id(), 160), Set.of(stale.id()))
                    .get(5, TimeUnit.SECONDS), "a stale session's clearing is a change");
            store.flush();
            var after = repositoryFile.load().sessions();
            assertEquals(Optional.of(160), after.get(0).prNumber());
            assertEquals(PrState.NONE, after.get(1).prState(), "a branch that stopped carrying its PR clears");
            assertEquals(Optional.empty(), after.get(1).prNumber());
        }
    }

    @Test
    void discoveredPullRequestsNeverTouchAMergedLinkOrATarget(@TempDir Path base) throws Exception {
        var repositoryFile = new JsonApplicationStateRepository(base.resolve("state.json"));
        var store = ApplicationStateStore.forRepository(repositoryFile);
        var repository = new Repository(RepositoryId.newId(), base, "repo", Instant.EPOCH, Instant.EPOCH,
                RepositorySettings.DEFAULT);
        store.update(state -> state.withRepositories(List.of(repository)));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var provider = new DrydockSessionServiceTest.RecordingProvider(AgentKind.PI, SessionIdStrategy.PRESET,
                    null, true, false, null, new CopyOnWriteArrayList<>());
            var registry = new AgentRegistry(List.of(provider),
                    new AgentContext(base, base.resolve("activity"), executor));
            var consoles = new DrydockSessionServiceTest.FakeConsoleHost(new CopyOnWriteArrayList<>());
            var service = new DrydockSessionService(CompletableFuture.completedFuture(null), () -> store,
                    () -> registry, () -> new SessionActivityWatcher(base.resolve("activity"), executor),
                    path -> CompletableFuture.completedFuture(repository.id()), consoles, executor,
                    path -> List.of(), SessionMcpAccess.none(), new LaunchClaims(id -> false), null);
            var mergedSession = service.launchSession(
                    service.prepareSession(base, AgentKind.PI, "merged already").get(5, TimeUnit.SECONDS))
                    .get(5, TimeUnit.SECONDS);
            service.recordPullRequestMerged(mergedSession.id(), 898).get(5, TimeUnit.SECONDS);
            var targetedSession = service.launchSession(
                    service.prepareSession(base, AgentKind.PI, "humanly targeted").get(5, TimeUnit.SECONDS))
                    .get(5, TimeUnit.SECONDS);
            var target = new PullRequestTarget("github.com/o/r", 42);
            service.associatePullRequest(targetedSession.id(), Optional.of(target)).get(5, TimeUnit.SECONDS);

            service.recordDiscoveredPullRequests(
                    Map.of(mergedSession.id(), 12, targetedSession.id(), 13),
                    Set.of(mergedSession.id(), targetedSession.id())).get(5, TimeUnit.SECONDS);
            store.flush();
            var persisted = repositoryFile.load().sessions();
            assertEquals(PrState.MERGED, persisted.get(0).prState(), "a merged link is the merge flow's, never downgraded");
            assertEquals(Optional.of(898), persisted.get(0).prNumber());
            assertEquals(Optional.of(target), persisted.get(1).targetPullRequest(),
                    "a human target is never touched by the discovery");
            assertEquals(PrState.NONE, persisted.get(1).prState());
        }
    }
}
