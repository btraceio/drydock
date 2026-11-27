package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.editorqa.EditorAskPorts;
import app.drydock.editorqa.EditorAskRecord;
import app.drydock.editorqa.EditorAskRecord.Status;
import app.drydock.intellij.editorqa.EditorAskComposerPanel;
import app.drydock.intellij.editorqa.EditorAskHistoryPanel;
import app.drydock.intellij.editorqa.EditorAskNavigation;
import app.drydock.intellij.editorqa.EditorAskUiModel;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;

import java.awt.BorderLayout;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Show-only exact-exchange navigation into a retained pane; nothing launches or sends. */
public class SessionPaneEditorAskRevealTest extends BasePlatformTestCase {

    private static final String ROOT = "/repo";
    private final ManagedSessionId session = ManagedSessionId.newId();
    private final AtomicInteger submits = new AtomicInteger();
    private final List<Runnable> uiQueue = new ArrayList<>();
    private final EditorAskUiModel model = new EditorAskUiModel();
    private List<EditorAskRecord> history = List.of();
    private final List<JComponent> mountedViews = new ArrayList<>();
    private long epoch;
    private long currentEpoch;
    private boolean hostOpen = true;
    private int shows;

    private final EditorAskNavigation.Source source = new EditorAskNavigation.Source() {
        @Override public List<EditorAskRecord> loadHistory(String checkout) { return history; }
        @Override public EditorAskUiModel model() { return model; }
        @Override public EditorAskHistoryPanel.Actions actions(ManagedSessionId s) {
            return new EditorAskHistoryPanel.Actions() {
                @Override public void openSettings() { }
                @Override public void openSessionTerminal() { }
                @Override public CompletableFuture<EditorAskPorts.Result> retry(EditorAskRecord r) {
                    submits.incrementAndGet();
                    return new CompletableFuture<>();
                }
            };
        }
        @Override public EditorAskComposerPanel.Sink composer(String checkout, ManagedSessionId s) {
            return q -> {
                submits.incrementAndGet();
                return new CompletableFuture<>();
            };
        }
    };

    private final EditorAskNavigation.Host host = new EditorAskNavigation.Host() {
        @Override public long show(Project p, ManagedSessionId s) {
            shows++;
            return epoch = ++currentEpoch;
        }
        @Override public boolean mount(Project p, ManagedSessionId s, long e, JComponent v) {
            if (!hostOpen || e != currentEpoch) {
                return false;
            }
            mountedViews.add(v);
            return true;
        }
    };

    @Override
    protected void tearDown() throws Exception {
        try {
            model.close();
        } finally {
            super.tearDown();
        }
    }

    private EditorAskNavigation navigation() {
        return new EditorAskNavigation(source, host, Runnable::run, uiQueue::add);
    }

    private void drain() {
        assertTrue("navigation completion must run on the EDT", SwingUtilities.isEventDispatchThread());
        List<Runnable> run = new ArrayList<>(uiQueue);
        uiQueue.clear();
        run.forEach(Runnable::run);
    }

    private static EditorAskRecord answered(long seq) {
        return new EditorAskRecord(EditorAskRecord.newId(), seq, ManagedSessionId.newId(), "A.java",
                new EditorAskRecord.TextRange(0, 4, 0, 0, 0, 4), "t", "same question", Instant.EPOCH,
                Status.ACCEPTED, null, null, null, null).answered("same answer", Instant.EPOCH);
    }

    /** The positions, in list order, of history cards with the highlight border. */
    private static List<Integer> highlighted(JComponent view, int expectedCards) {
        var history = (EditorAskHistoryPanel) ((BorderLayout) view.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        var scroll = (JScrollPane) ((BorderLayout) history.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        var list = (JPanel) scroll.getViewport().getView();
        assertEquals("one history card per exchange", expectedCards, list.getComponentCount());
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < list.getComponentCount(); i++) {
            if (((JPanel) list.getComponent(i)).getBorder() instanceof CompoundBorder b
                    && b.getOutsideBorder() instanceof LineBorder) {
                out.add(i);
            }
        }
        return out;
    }

    private void open(String id) {
        navigation().open(getProject(), ROOT, session, id, () -> true);
        drain();
    }

    public void testIdenticalExchangesOlderLinkRevealsOlderId() {
        EditorAskRecord older = answered(1), newer = answered(2);
        history = List.of(newer, older);
        open(older.id());
        assertEquals(1, mountedViews.size());
        assertEquals(List.of(0), highlighted(mountedViews.get(0), 2));
        assertEquals(1, shows);
    }

    public void testHostClosedMountsNothingAndDoesNotThrow() {
        hostOpen = false;
        history = List.of(answered(1));
        open("x");
        assertTrue(mountedViews.isEmpty());
    }

    public void testReplayTrimmedExchangeSelectsNothingElse() {
        EditorAskRecord kept = answered(5);
        history = List.of(kept);
        open(EditorAskRecord.newId());   // an ID that trim already dropped
        assertEquals(1, mountedViews.size());
        assertEquals(List.of(), highlighted(mountedViews.get(0), 1));
    }

    public void testStaleNavigationIsFencedAndLaterOneWins() {
        EditorAskRecord a = answered(1), b = answered(2);
        history = List.of(a, b);
        EditorAskNavigation nav = navigation();
        nav.open(getProject(), ROOT, session, a.id(), () -> true);
        nav.open(getProject(), ROOT, session, b.id(), () -> true);
        drain();
        assertEquals(1, mountedViews.size());
        assertEquals(List.of(1), highlighted(mountedViews.get(0), 2));
    }

    public void testStaleLoadCompletingLateLeavesNewerModelIntact() {
        EditorAskRecord a = answered(1), b = answered(2);
        history = List.of(a);
        EditorAskNavigation nav = navigation();
        nav.open(getProject(), ROOT, session, a.id(), () -> true);   // epoch 1 queued
        history = List.of(b);
        nav.open(getProject(), ROOT, session, b.id(), () -> true);   // epoch 2 queued
        assertEquals(2, uiQueue.size());
        uiQueue.remove(1).run();   // newer completes first
        uiQueue.remove(0).run();   // stale completes late: fenced before touching the model
        assertEquals(1, mountedViews.size());
        assertEquals(List.of(b.id()), model.rows(ROOT).stream().map(EditorAskUiModel.Row::id).toList());
    }

    public void testClosedEditorFencesLateCompletion() {
        history = List.of(answered(1));
        navigation().open(getProject(), ROOT, session, "x", () -> false);
        drain();
        assertTrue(mountedViews.isEmpty());
    }

    public void testReadNeverSendsOrResumes() {
        history = List.of(answered(1));
        open(history.get(0).id());
        assertEquals(0, submits.get());
    }

    public void testStoppedPaneKeepsItsBodyAndHostsTheViewWithoutResuming() {
        SessionPane pane = new SessionPane(getProject(), session, new SessionPane.Host() {
            @Override public boolean focusPermitted(long e) { return false; }
            @Override public long epoch() { return 0; }
            @Override public void paneFocused(ManagedSessionId id) { }
            @Override public Optional<SessionPaneFacts> facts(ManagedSessionId id) { return Optional.empty(); }
            @Override public void onStripAction(SessionPane p, AgentConsoleStrip.StripAction a) { }
        });
        assertNull(pane.sideView());
        int before = pane.root().getComponentCount();
        SessionPane.Body body = pane.shown();
        JPanel view = new JPanel(new BorderLayout());
        pane.setSideView(view);
        assertSame(view, pane.sideView());
        assertEquals(before + 1, pane.root().getComponentCount());
        assertEquals(body, pane.shown());
        pane.setSideView(null);
        assertEquals(before, pane.root().getComponentCount());
        pane.dispose();
    }

    public void testRealControllerFencesByEpochAndShownSlot() {
        SessionHostController controller = new SessionHostController(getProject(), new SessionHostState(), id -> { });
        Disposer.register(getTestRootDisposable(), controller);
        assertTrue(controller.paneIfCurrent(session, controller.epoch()).isEmpty());   // not shown
        controller.show(session);
        long issued = controller.epoch();
        assertTrue(controller.paneIfCurrent(session, issued).isPresent());
        controller.show(ManagedSessionId.newId());
        assertTrue(controller.paneIfCurrent(session, issued).isEmpty());   // superseded
    }
}
