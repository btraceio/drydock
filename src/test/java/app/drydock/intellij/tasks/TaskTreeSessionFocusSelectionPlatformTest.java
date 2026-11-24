package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.IsolatedCatalog;
import app.drydock.intellij.agent.SessionHostListener;
import app.drydock.projects.SharedProject;

import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.TimeoutUtil;

import javax.swing.JTree;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Session-area focus selects the session's row in the tool window's inbox,
 * and selects nothing while the Projects view is up: the bus event of a pane
 * focus drives the panel the way the controller publishes it in production.
 */
public class TaskTreeSessionFocusSelectionPlatformTest extends BasePlatformTestCase {

    private static final long TREE_SECONDS = 20;
    private static final String SESSION = "the focused session";
    private DrydockPluginService plugin;
    private Path repoRoot;
    private SharedProject registered;
    private ManagedSessionId sessionId;
    private ManagedSessionId otherSessionId;
    private TaskTreePanel panel;
    private InboxList inbox;

    @Override
    protected void setUp() throws Exception {
        IsolatedCatalog.assertSandboxed(); // before anything can touch the plugin (T14 discipline)
        super.setUp();
        plugin = DrydockPluginService.getInstance();
        try { plugin.initialization().get(60, TimeUnit.SECONDS); }
        catch (Exception e) { fail("Drydock initialization did not complete within 60 s: " + e); }
        Path root = plugin.taskService().root().toAbsolutePath().normalize();
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), root.toString());
        repoRoot = Files.createTempDirectory("drydock-focus-plate");
        registered = plugin.sharedProjects().register(repoRoot, "focus-plate-" + System.nanoTime())
                .get(30, TimeUnit.SECONDS);
        sessionId = ManagedSessionId.newId();
        Instant anHourAgo = Instant.now().minusSeconds(3_600);
        IsolatedCatalog.seed(new ManagedAgentSession(sessionId, RepositoryId.newId(),
                SESSION, AgentBinding.unlaunched(AgentKind.CLAUDE),
                new SessionWorkspace(repoRoot, Optional.empty(), false), SessionStatus.INACTIVE,
                anHourAgo, anHourAgo, Optional.empty(), PrLink.none(), false, Optional.empty(), false));
        otherSessionId = ManagedSessionId.newId();
        IsolatedCatalog.seed(new ManagedAgentSession(otherSessionId, RepositoryId.newId(),
                "the other focused session", AgentBinding.unlaunched(AgentKind.CLAUDE),
                new SessionWorkspace(repoRoot, Optional.empty(), false), SessionStatus.INACTIVE,
                anHourAgo, anHourAgo, Optional.empty(), PrLink.none(), false, Optional.empty(), false));
        // The light project is shared across cases: start every panel from no saved view.
        InboxViewState.getInstance(getProject()).loadState(new InboxViewState.Stored());
        panel = new TaskTreePanel(getProject());
        JTree tree = panel.tree();
        // Headless harness: the Swing layout cache would ask the panel's cell renderer
        // for the invisible root's null user object, which the renderer cannot answer
        // (a pre-existing production path); nothing paints, so the tree keeps no UI.
        tree.setUI(null);
        inbox = panel.inbox();
        // The seeded session is stopped: its row sits in the Idle group, collapsed
        // by default; expand it so the row is among the list's entries.
        inbox.toggleCollapsed(InboxModel.GroupKind.IDLE);
        awaitTree(() -> sessionRow(sessionId) >= 0 && sessionRow(otherSessionId) >= 0,
                "the seeded sessions' rows");
    }

    @Override
    protected void tearDown() throws Exception {
        if (panel != null) { try { Disposer.dispose(panel); } catch (RuntimeException e) { } }
        if (sessionId != null) { try { IsolatedCatalog.remove(sessionId); } catch (RuntimeException e) { } }
        if (otherSessionId != null) { try { IsolatedCatalog.remove(otherSessionId); } catch (RuntimeException e) { } }
        try { plugin.sharedProjects().remove(registered.id()).get(30, TimeUnit.SECONDS); } catch (Exception e) { }
        delete(repoRoot);
        super.tearDown();
    }

    public void testAFocusedSessionSelectsItsRowInTheInbox() {
        getProject().getMessageBus().syncPublisher(SessionHostListener.TOPIC).sessionFocused(sessionId);
        awaitTree(() -> sessionRow(sessionId) >= 0 && inbox.getSelectedIndex() == sessionRow(sessionId),
                "the focused session's row selected");
        assertTrue("the selection is the focused session", inbox.selectedItems().stream()
                .allMatch(item -> item instanceof InboxModel.SessionItem session
                        && session.row().session().id().equals(sessionId)));
    }

    public void testAFocusOfAnUnknownSessionLeavesTheInboxSelectionUnchanged() {
        getProject().getMessageBus().syncPublisher(SessionHostListener.TOPIC).sessionSelected(sessionId);
        awaitTree(() -> !inbox.selectedItems().isEmpty(), "the seeded session selected");
        List<InboxModel.Item> baseline = List.copyOf(inbox.selectedItems());
        assertTrue("the guard starts from a non-empty selection", !baseline.isEmpty());

        getProject().getMessageBus().syncPublisher(SessionHostListener.TOPIC)
                .sessionFocused(ManagedSessionId.newId());
        pump();
        assertEquals("a session the inbox does not show leaves the prior selection alone",
                baseline, inbox.selectedItems());
    }

    public void testAFocusWhileBrowsingTheProjectsViewLeavesTheSelectionAlone() {
        getProject().getMessageBus().syncPublisher(SessionHostListener.TOPIC).sessionSelected(sessionId);
        awaitTree(() -> !inbox.selectedItems().isEmpty(), "the first seeded session selected");
        List<InboxModel.Item> baseline = List.copyOf(inbox.selectedItems());
        assertTrue("the Projects guard starts from a non-empty selection", !baseline.isEmpty());

        panel.showProjects(true);
        awaitTree(() -> panel.tree().getModel().getChildCount(panel.tree().getModel().getRoot()) > 0,
                "the Projects view");
        getProject().getMessageBus().syncPublisher(SessionHostListener.TOPIC).sessionFocused(otherSessionId);
        pump();
        assertEquals("the Projects view preserves the selected inbox row",
                baseline, inbox.selectedItems());
    }

    /** The index of the given seeded session's inbox row, or -1. */
    private int sessionRow(ManagedSessionId id) {
        var entries = inbox.entries();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) instanceof InboxList.Row row
                    && row.item() instanceof InboxModel.SessionItem session
                    && session.row().session().id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** Spins (never sleeps) until the panel's own state satisfies the condition. */
    private void awaitTree(BooleanSupplier state, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TREE_SECONDS);
        while (!state.getAsBoolean()) {
            assertTrue(what + " did not arrive within " + TREE_SECONDS + " s", System.nanoTime() < deadline);
            pump();
        }
    }

    /** Drains the EDT queue a few rounds so queued rebuild passes and events land. */
    private static void pump() {
        for (int i = 0; i < 3; i++) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            TimeoutUtil.sleep(20);
        }
    }

    private static void delete(Path directory) {
        try (Stream<Path> entries = Files.walk(directory)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(
                    p -> { try { Files.deleteIfExists(p); } catch (java.io.IOException e) { } });
        } catch (java.io.IOException ignored) { }
    }
}
