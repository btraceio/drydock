package app.drydock.intellij.session;

import app.drydock.activity.SessionActivityWatcher;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.PullRequestTarget;
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
import java.util.Optional;
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
}
