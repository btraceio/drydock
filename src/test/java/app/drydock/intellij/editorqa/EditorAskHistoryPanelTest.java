package app.drydock.intellij.editorqa;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javax.swing.JButton;
import javax.swing.JLabel;

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
import app.drydock.editorqa.EditorAskPorts.LaunchResult;
import app.drydock.editorqa.EditorAskPorts.LaunchRequest;
import app.drydock.editorqa.EditorAskPorts.Launcher;
import app.drydock.editorqa.EditorAskPorts.Resumability;
import app.drydock.editorqa.EditorAskPorts.Result;
import app.drydock.editorqa.EditorAskPorts.Settings;
import app.drydock.editorqa.EditorAskPorts.Wiring;
import app.drydock.editorqa.EditorAskRecord;
import app.drydock.editorqa.EditorAskRecord.Status;
import app.drydock.editorqa.EditorAskRowBinder;
import app.drydock.state.ApplicationStateRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorAskHistoryPanelTest {

    @TempDir
    Path tmp;

    private final List<AutoCloseable> closeables = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : closeables) {
            c.close();
        }
    }

    private static void await(BooleanSupplier c) throws InterruptedException {
        for (int i = 0; i < 500 && !c.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
        assertTrue(c.getAsBoolean(), "condition not reached in time");
    }

    private static final ManagedSessionId ROW = ManagedSessionId.newId();

    private static EditorAskRecord record(long seq, Status status, String reason, String answer) {
        EditorAskRecord base = new EditorAskRecord(EditorAskRecord.newId(), seq, ROW, "src/A.java",
                new EditorAskRecord.TextRange(0, 4, 0, 0, 0, 4), "text", "question " + seq,
                Instant.EPOCH, Status.ACCEPTED, null, null, null, null);
        if (status == Status.ANSWERED) {
            return base.answered(answer, Instant.EPOCH);
        }
        return status == Status.ACCEPTED ? base : base.settled(status, reason);
    }

    private static final class NoActions implements EditorAskHistoryPanel.Actions {
        final AtomicInteger settings = new AtomicInteger();
        final AtomicInteger terminal = new AtomicInteger();
        final List<EditorAskRecord> retries = new CopyOnWriteArrayList<>();
        volatile CompletableFuture<Result> retryResult = new CompletableFuture<>();

        @Override
        public void openSettings() {
            settings.incrementAndGet();
        }

        @Override
        public void openSessionTerminal() {
            terminal.incrementAndGet();
        }

        @Override
        public CompletableFuture<Result> retry(EditorAskRecord record) {
            retries.add(record);
            return retryResult;
        }
    }

    private EditorAskUiModel model() {
        EditorAskUiModel m = new EditorAskUiModel(Runnable::run, 20);
        closeables.add(m);
        return m;
    }

    private EditorAskHistoryPanel panel(EditorAskUiModel m, String root, EditorAskHistoryPanel.Actions a) {
        EditorAskHistoryPanel p = new EditorAskHistoryPanel(m, root, a, Runnable::run);
        closeables.add(p::dispose);
        return p;
    }

    private static List<JButton> buttons(EditorAskHistoryPanel p, String id) {
        return Arrays.stream(p.actionsOf(id).getComponents()).filter(JButton.class::isInstance)
                .map(JButton.class::cast).toList();
    }

    @Test
    void hostileAnswerIsPlainUntrustedTextAndReadableInFull() throws Exception {
        EditorAskUiModel m = model();
        String hostile = "<html><b onclick=x>bold</b><img src='http://evil/x.png'></html>\n" + "tail ".repeat(1500);
        EditorAskRecord rec = record(0, Status.ANSWERED, null, hostile);
        m.recorded("/r", rec);
        EditorAskHistoryPanel p = panel(m, "/r", new NoActions());
        assertEquals(rec.answer(), p.answerAreaOf(rec.id()).getText(), "the full answer, unchanged");
        assertFalse(p.answerAreaOf(rec.id()).isEditable());
        assertNull(p.answerAreaOf(rec.id()).getClientProperty("html"), "a text area never builds an HTML view");
        assertEquals("text/plain", p.answerAreaOf(rec.id()).getDocument().getProperty("contentType") == null
                ? "text/plain" : p.answerAreaOf(rec.id()).getDocument().getProperty("contentType"));
        JLabel status = (JLabel) Arrays.stream(p.cardOf(rec.id()).getComponents())
                .filter(JLabel.class::isInstance).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, status.getClientProperty("html.disable"));
    }

    @Test
    void failureReasonWithMarkupIsNeverRenderedAsHtml() {
        EditorAskUiModel m = model();
        EditorAskRecord rec = record(0, Status.FAILED_NOT_SENT, "<html><h1>Not sent</h1> harness is unavailable; see Settings", null);
        m.recorded("/r", rec);
        EditorAskHistoryPanel p = panel(m, "/r", new NoActions());
        assertTrue(p.statusOf(rec.id()).contains("<html>"), "shown literally, with html.disable on the label");
        JLabel status = (JLabel) Arrays.stream(p.cardOf(rec.id()).getComponents())
                .filter(JLabel.class::isInstance).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, status.getClientProperty("html.disable"));
    }

    @Test
    void recoveryActionsPointToSettingsTerminalAndRetryWithNewId() throws Exception {
        EditorAskUiModel m = model();
        EditorAskRecord noHarness = record(0, Status.FAILED_NOT_SENT, "Not sent: no agent harness is available. Open Settings → Drydock.", null);
        EditorAskRecord trust = record(1, Status.FAILED_NOT_SENT, "Not sent: the agent asks for trust or permission in its terminal.", null);
        EditorAskRecord answered = record(2, Status.ANSWERED, null, "fine");
        m.load("/r", List.of(noHarness, trust, answered));
        NoActions actions = new NoActions();
        EditorAskHistoryPanel p = panel(m, "/r", actions);
        assertEquals(List.of("Open Settings", "Retry"), buttons(p, noHarness.id()).stream().map(JButton::getText).toList());
        assertEquals(List.of("Open the session terminal", "Retry"), buttons(p, trust.id()).stream().map(JButton::getText).toList());
        assertTrue(buttons(p, answered.id()).isEmpty());
        buttons(p, noHarness.id()).getFirst().doClick();
        buttons(p, trust.id()).getFirst().doClick();
        assertEquals(1, actions.settings.get());
        assertEquals(1, actions.terminal.get());
        buttons(p, trust.id()).getLast().doClick();
        assertEquals(List.of(trust), actions.retries);
        assertEquals("Retrying…", buttons(p, trust.id()).getLast().getText());
        assertFalse(buttons(p, trust.id()).getLast().isEnabled());
        actions.retryResult.completeExceptionally(new IllegalStateException("x"));
        assertEquals("Retry", buttons(p, trust.id()).getLast().getText(), "progress cleared on failure too");
    }

    @Test
    void queueLabelsComeFromARealCoordinatorFifo() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("repo")).toRealPath();
        EditorAskUiModel m = model();
        List<LaunchRequest> launches = new CopyOnWriteArrayList<>();
        Launcher lane = new Launcher() {
            @Override
            public LaunchResult launch(LaunchRequest r) {
                launches.add(r);
                return new LaunchResult(LaunchOutcome.STARTED, 7, null);
            }

            @Override
            public WriteOutcome write(String e, long g, String p) {
                return WriteOutcome.DELIVERED;
            }

            @Override
            public Resumability verifyConversation(ManagedSessionId r, AgentKind k, String id) {
                return Resumability.EXACT;
            }

            @Override
            public void stop(String e) {
            }
        };
        Settings settings = new Settings() {
            @Override
            public Choice choice() {
                return Choice.CLAUDE;
            }

            @Override
            public boolean available(AgentKind k) {
                return true;
            }

            @Override
            public Optional<String> model(AgentKind k) {
                return Optional.empty();
            }
        };
        ApplicationStateRepository repo = new ApplicationStateRepository() {
            ApplicationState s = ApplicationState.empty();

            @Override
            public ApplicationState load() {
                return s;
            }

            @Override
            public void save(ApplicationState x) {
                s = x;
            }
        };
        EditorAskHistoryStore store = new EditorAskHistoryStore(tmp.resolve("base"));
        closeables.add(store);
        EditorAskCoordinator coordinator = new EditorAskCoordinator(store,
                new EditorAskRowBinder(ApplicationStateStore.forRepository(repo)),
                new Wiring(lane, settings, (row, gen) -> gen == 7, m), () -> Instant.parse("2030-01-01T00:00:00Z"));
        closeables.add(coordinator);
        Instant now = Instant.EPOCH;
        ManagedAgentSession row = new ManagedAgentSession(ManagedSessionId.newId(), RepositoryId.newId(), "Drydock Q&A — repo",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty(), SessionMode.READ_ONLY),
                new SessionWorkspace(root, Optional.empty(), true), SessionStatus.INACTIVE, now, now,
                Optional.empty(), PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty())
                .toBuilder().purpose(SessionPurpose.EDITOR_QA).build();
        Result a = coordinator.submit(EditorAskComposerPanel.tabSubmission(root.toString(), row, "first")).get();
        Result b = coordinator.submit(EditorAskComposerPanel.tabSubmission(root.toString(), row, "second")).get();
        Result c = coordinator.submit(EditorAskComposerPanel.tabSubmission(root.toString(), row, "third")).get();
        m.load(root.toString(), store.records(root.toString()));
        EditorAskHistoryPanel p = panel(m, root.toString(), new NoActions());
        await(() -> {
            p.refresh();
            return p.viewCount() == 3 && p.statusOf(b.id()).contains("Queued, 1 ahead")
                    && p.statusOf(c.id()).contains("Queued, 2 ahead");
        });
        assertTrue(p.statusOf(a.id()).contains("Waiting for answer…") || p.statusOf(a.id()).contains("Starting…"));
        assertTrue(coordinator.commit(a.row(), 7, a.id(), "answer one"));
        await(() -> {
            p.refresh();
            return p.answerAreaOf(a.id()).getText().equals("answer one");
        });
        assertTrue(p.statusOf(a.id()).contains("Answered"));
        assertTrue(p.statusOf(b.id()).contains("Queued, 1 ahead"),
                "an answered turn still holds the slot until it is over: the next stays queued");
    }

    @Test
    void notificationsAreCoalescedIntoOneDelivery() throws Exception {
        EditorAskUiModel m = model();
        EditorAskRecord rec = record(0, Status.SUBMITTING, null, null);
        m.recorded("/r", rec);
        AtomicInteger deliveries = new AtomicInteger();
        m.addListener(deliveries::incrementAndGet);
        for (int i = 0; i < 200; i++) {
            m.progress("/r", rec.id(), Status.SUBMITTING, "Starting… " + i, null);
        }
        await(() -> deliveries.get() >= 1);
        Thread.sleep(80);
        assertTrue(deliveries.get() <= 2, "200 events coalesce, got " + deliveries.get());
        assertEquals("Starting… 199", m.rows("/r").getFirst().label());
    }

    @Test
    void lateProgressNeverReopensASettledExchange() {
        EditorAskUiModel m = model();
        EditorAskRecord rec = record(0, Status.ANSWERED, null, "done");
        m.recorded("/r", rec);
        m.progress("/r", rec.id(), Status.AWAITING, "Waiting for answer…", null);
        assertEquals("Answered", m.rows("/r").getFirst().label());
    }

    @Test
    void selectScrollsToTheIdKeyedComponentEvenWhenItMountsLater() {
        EditorAskUiModel m = model();
        EditorAskRecord one = record(0, Status.ANSWERED, null, "a");
        EditorAskRecord two = record(1, Status.ANSWERED, null, "b");
        m.recorded("/r", one);
        EditorAskHistoryPanel p = panel(m, "/r", new NoActions());
        assertFalse(p.select(two.id()));
        m.recorded("/r", two);
        p.refresh();
        assertTrue(p.select(two.id()));
    }
}
