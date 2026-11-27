package app.drydock.intellij.editorqa;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import app.drydock.agent.api.AgentKind;
import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedAgentSession.SessionPurpose;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.editorqa.EditorAskCoordinator;
import app.drydock.editorqa.EditorAskHarnessPolicy.Choice;
import app.drydock.editorqa.EditorAskHistoryStore;
import app.drydock.editorqa.EditorAskLane.WriteOutcome;
import app.drydock.editorqa.EditorAskPorts.LaunchOutcome;
import app.drydock.editorqa.EditorAskPorts.LaunchRequest;
import app.drydock.editorqa.EditorAskPorts.LaunchResult;
import app.drydock.editorqa.EditorAskPorts.Launcher;
import app.drydock.editorqa.EditorAskPorts.Resumability;
import app.drydock.editorqa.EditorAskPorts.Result;
import app.drydock.editorqa.EditorAskPorts.Settings;
import app.drydock.editorqa.EditorAskPorts.Submission;
import app.drydock.editorqa.EditorAskPorts.Wiring;
import app.drydock.editorqa.EditorAskRecord.Status;
import app.drydock.editorqa.EditorAskRowBinder;
import app.drydock.state.ApplicationStateRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorAskComposerPanelTest {

    @TempDir
    Path tmp;

    private EditorAskCoordinator coordinator;
    private EditorAskHistoryStore store;
    private EditorAskUiModel model;

    private static final class Repo implements ApplicationStateRepository {
        volatile ApplicationState state = ApplicationState.empty();

        @Override
        public ApplicationState load() {
            return state;
        }

        @Override
        public void save(ApplicationState s) {
            state = s;
        }
    }

    private static final class Lane implements Launcher {
        final List<LaunchRequest> launches = new CopyOnWriteArrayList<>();

        @Override
        public LaunchResult launch(LaunchRequest r) {
            launches.add(r);
            return new LaunchResult(LaunchOutcome.STARTED, 7, null);
        }

        @Override
        public WriteOutcome write(String executionId, long generation, String prompt) {
            return WriteOutcome.DELIVERED;
        }

        @Override
        public Resumability verifyConversation(ManagedSessionId row, AgentKind kind, String id) {
            return Resumability.EXACT;
        }

        @Override
        public void stop(String executionId) {
        }
    }

    @AfterEach
    void close() {
        if (coordinator != null) {
            coordinator.close();
        }
        if (store != null) {
            store.close();
        }
        if (model != null) {
            model.close();
        }
    }

    private static void await(BooleanSupplier c) throws InterruptedException {
        for (int i = 0; i < 500 && !c.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
        assertTrue(c.getAsBoolean(), "condition not reached in time");
    }

    private Submission tabQuestion(Path root, String question) {
        Instant now = Instant.EPOCH;
        ManagedAgentSession row = new ManagedAgentSession(ManagedSessionId.newId(), RepositoryId.newId(), "Drydock Q&A — repo",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty(), SessionMode.READ_ONLY),
                new SessionWorkspace(root, Optional.empty(), true), SessionStatus.INACTIVE, now, now,
                Optional.empty(), PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty())
                .toBuilder().purpose(SessionPurpose.EDITOR_QA).build();
        return EditorAskComposerPanel.tabSubmission(root.toString(), row, question);
    }

    private Lane wire(Path root, EditorAskUiModel m) throws IOException {
        Lane lane = new Lane();
        Repo repo = new Repo();
        store = new EditorAskHistoryStore(tmp.resolve("base"));
        Settings settings = new Settings() {
            @Override
            public Choice choice() {
                return Choice.CLAUDE;
            }

            @Override
            public boolean available(AgentKind kind) {
                return true;
            }

            @Override
            public Optional<String> model(AgentKind kind) {
                return Optional.empty();
            }
        };
        coordinator = new EditorAskCoordinator(store, new EditorAskRowBinder(ApplicationStateStore.forRepository(repo)),
                new Wiring(lane, settings, (row, gen) -> gen == 7, m), () -> Instant.parse("2030-01-01T00:00:00Z"));
        return lane;
    }

    @Test
    void tabComposerRunsARealFifoTurnThroughTheCoordinator() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("repo")).toRealPath();
        model = new EditorAskUiModel(Runnable::run, 1);
        Lane lane = wire(root, model);
        List<CompletableFuture<Result>> admissions = new ArrayList<>();
        var ui = new LinkedBlockingQueue<Runnable>();
        EditorAskComposerPanel panel = new EditorAskComposerPanel(q -> {
            var admitted = coordinator.submit(tabQuestion(root, q));
            admissions.add(admitted);
            return admitted;
        }, Runnable::run, ui::add);
        panel.setQuestion("what does this do?");
        panel.submit();
        Result first = admissions.getFirst().get(10, TimeUnit.SECONDS);
        assertTrue(first.accepted(), first.refusal());
        Runnable showFirst = ui.poll(10, TimeUnit.SECONDS);
        assertNotNull(showFirst, "admission completion was not delivered to the UI");
        showFirst.run();
        await(() -> !lane.launches.isEmpty());
        LaunchRequest launch = lane.launches.getFirst();
        assertTrue(launch.prompt().contains("what does this do?"));
        String id = first.id();
        assertTrue(launch.prompt().contains(id), "same ID is the turn marker");
        assertEquals(0, store.records(root.toString()).getFirst().acceptanceSequence());
        assertTrue(panel.isSendEnabled(), "progress is cleared once admitted");
        assertTrue(panel.statusText().contains(id));
        assertTrue(coordinator.commit(store.records(root.toString()).getFirst().owner(), 7, id, "an answer"));
        assertEquals(Status.ANSWERED, store.records(root.toString()).getFirst().status());
        // A record is visible in memory before admission saves it. Wait for
        // durable admission, not just the row count, before allowing teardown.
        panel.setQuestion("and then?");
        panel.submit();
        Result second = admissions.get(1).get(10, TimeUnit.SECONDS);
        assertTrue(second.accepted(), second.refusal());
        assertEquals(2, store.records(root.toString()).size());
        assertEquals(1, store.records(root.toString()).get(1).acceptanceSequence());
    }

    @Test
    void disabledWithReasonAndReenablesWithoutLeavingStaleText() {
        EditorAskComposerPanel panel = new EditorAskComposerPanel(q -> new CompletableFuture<>());
        panel.setUnavailable("No agent harness is available. Open Settings → Drydock.");
        assertFalse(panel.isSendEnabled());
        assertTrue(panel.statusText().contains("Settings"));
        panel.setQuestion("x");
        panel.submit();
        assertFalse(panel.isSendEnabled(), "a disabled composer submits nothing");
        panel.setUnavailable(null);
        assertTrue(panel.isSendEnabled());
        assertEquals(" ", panel.statusText());
    }

    @Test
    void everyCompletionPathClearsProgress() throws Exception {
        CompletableFuture<Result> pending = new CompletableFuture<>();
        EditorAskComposerPanel panel = new EditorAskComposerPanel(q -> pending, Runnable::run, Runnable::run);
        panel.setQuestion("q");
        panel.submit();
        assertFalse(panel.isSendEnabled());
        assertEquals("Submitting…", panel.statusText());
        pending.completeExceptionally(new IllegalStateException("boom"));
        assertTrue(panel.isSendEnabled());
        assertFalse(panel.statusText().contains("boom"));

        EditorAskComposerPanel thrower = new EditorAskComposerPanel(q -> {
            throw new IllegalStateException("x");
        }, Runnable::run, Runnable::run);
        thrower.setQuestion("q");
        thrower.submit();
        assertTrue(thrower.isSendEnabled());

        EditorAskComposerPanel refused = new EditorAskComposerPanel(
                q -> CompletableFuture.completedFuture(new Result(false, null, -1, null, "Shutting down")),
                Runnable::run, Runnable::run);
        refused.setQuestion("q");
        refused.submit();
        assertTrue(refused.isSendEnabled());
        assertEquals("Shutting down", refused.statusText());

        EditorAskComposerPanel blank = new EditorAskComposerPanel(q -> pending, Runnable::run, Runnable::run);
        blank.setQuestion("   ");
        blank.submit();
        assertTrue(blank.isSendEnabled(), "an invalid question never enters the busy state");
    }

    @Test
    void closingTheUiDoesNotCancelAdmission() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("repo")).toRealPath();
        model = new EditorAskUiModel(Runnable::run, 1);
        Lane lane = wire(root, model);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        EditorAskComposerPanel panel = new EditorAskComposerPanel(
                q -> gate.thenCompose(v -> coordinator.submit(tabQuestion(root, q))));
        panel.setQuestion("kept");
        panel.submit();
        panel.dispose();
        gate.complete(null);
        await(() -> !lane.launches.isEmpty());
        assertNotNull(store.records(root.toString()).getFirst());
        assertEquals("kept", store.records(root.toString()).getFirst().question());
        assertFalse(panel.statusText().contains("Queued"), "a disposed view is not touched");
    }
}
