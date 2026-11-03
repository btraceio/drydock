package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.delayedtasks.TaskStoreWatcher.WatchedSnapshot;
import app.drydock.domain.*;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.IsolatedCatalog;
import app.drydock.projects.SharedProject;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.TaskNode.TasksSection;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.TimeoutUtil;

import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The T17b acceptance matrix for the accepted tree wiring (T17a), in the
 * booted IDE: the REAL panel over the plugin's booted services, driven by
 * store tasks saved through real IDE documents. One save publishes a
 * placement, one more rebuilds on it (the watcher's debounced re-read lags
 * the rebuild). Waits pump the EDT and poll a deadline; nothing is shown.
 */
public class DelayedTaskTreePlatformTest extends BasePlatformTestCase {

    private static final long PUBLISH_SECONDS = 25; // the watcher re-reads every 5 s
    private static final long TREE_SECONDS = 20;
    private DrydockPluginService plugin;
    private Path root;
    private Path repoRoot;
    private SharedProject registered;
    private ManagedSessionId sessionId;
    private TaskTreePanel panel;
    private JTree tree;
    /** The rebuild passes observed so far: one new tree root per applied pass. */
    private int applies;
    private Object appliedRoot;
    private final List<Path> fixtures = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        IsolatedCatalog.assertSandboxed(); // before anything can touch the plugin (T14 discipline)
        super.setUp();
        plugin = DrydockPluginService.getInstance();
        try { plugin.initialization().get(60, TimeUnit.SECONDS); }
        catch (Exception e) { fail("Drydock initialization did not complete within 60 s: " + e); }
        root = plugin.taskService().root().toAbsolutePath().normalize();
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), root.toString());
        assertTrue("the store root must be the Gradle test sandbox: " + root,
                System.getProperty(DelayedTaskService.OVERRIDE_PROPERTY) != null
                        && !root.startsWith(Path.of(System.getProperty("user.home"), ".drydock")));
        repoRoot = Files.createTempDirectory("drydock-tree-plate");
        registered = plugin.sharedProjects().register(repoRoot, "tree-plate-" + System.nanoTime())
                .get(30, TimeUnit.SECONDS);
        sessionId = ManagedSessionId.newId();
        Instant anHourAgo = Instant.now().minusSeconds(3_600);
        IsolatedCatalog.seed(new ManagedAgentSession(sessionId, RepositoryId.newId(),
                "session with a task", AgentBinding.unlaunched(AgentKind.CLAUDE),
                new SessionWorkspace(repoRoot, Optional.empty(), false), SessionStatus.INACTIVE,
                anHourAgo, anHourAgo, Optional.empty(), PrLink.none(), false, Optional.empty(), false));
        panel = new TaskTreePanel(getProject());
        tree = (JTree) ((JScrollPane) panel.getContent()).getViewport().getView();
        // Headless harness: the Swing layout cache would ask the panel's cell renderer
        // for the invisible root's null user object, which the renderer cannot answer
        // (a pre-existing production path); nothing paints, so the tree keeps no UI.
        tree.setUI(null);
        awaitTree(() -> sessionPath() != null, "the seeded session's row");
    }

    @Override
    protected void tearDown() throws Exception {
        if (panel != null) { try { Disposer.dispose(panel); } catch (RuntimeException e) { } }
        if (sessionId != null) { try { IsolatedCatalog.remove(sessionId); } catch (RuntimeException e) { } }
        try { plugin.sharedProjects().remove(registered.id()).get(30, TimeUnit.SECONDS); } catch (Exception e) { }
        for (Path fixture : fixtures) { delete(fixture.getParent()); } // the unique repository dir
        delete(repoRoot);
        super.tearDown();
    }

    // 1. A snapshot publication followed by a save rebuilds the tree through the coalescer.
    public void testASaveRebuildsTheTreeOnThePublishedSnapshot() throws IOException {
        Task task = newTask("A save rebuilds the tree on the publication");
        publish(task, List.of()); // the trigger save rebuilds on the already-published placement
        awaitTree(() -> underSection(task.id()), "the saved task in the Tasks section");
    }

    // 2. A placement change re-parents the row under its session row and back (T15a rules).
    public void testATaskMovesUnderItsSessionRowAndBackPerThePlacementRules() throws IOException {
        Task task = newTask("A task that moves under its session row");
        publish(task, List.of());
        awaitTree(() -> underSection(task.id()), "the task in the Tasks section");
        publish(task, List.of(sessionId.toString()));
        awaitTree(() -> underSession(task.id()), "the task under its session row");
        publish(task, List.of());
        awaitTree(() -> underSection(task.id()) && !underSession(task.id()), "the task back in the section");
    }

    // 3. The selection survives a coalesced rebuild keyed on its task id; the collapsed
    // session row and Tasks section are restored on their own row identities.
    public void testSelectionAndCollapsesSurviveACoalescedRebuild() throws IOException {
        Task attached = newTask("A task attached for the selection");
        publish(attached, List.of(sessionId.toString()));
        Task section = newTask("A task that keeps the Tasks section open");
        publish(section, List.of());
        awaitTree(() -> underSession(attached.id()) && underSection(section.id()), "both tasks placed");
        assertTrue("the session row opens with its task children", tree.isExpanded(sessionPath()));
        tree.collapsePath(sessionPath());
        tree.collapsePath(tasksSectionPath());
        // The collapses come first: JTree hands a collapsed row's selected child to the
        // collapsed row itself, so the task is selected last, under its session row.
        tree.setSelectionPath(taskPath(attached.id()));
        burst(attached, List.of(sessionId.toString()), 3);
        Object selected = ((DefaultMutableTreeNode) tree.getSelectionPath().getLastPathComponent()).getUserObject();
        assertTrue("the selection survives the rebuild, keyed on the task id",
                selected instanceof DelayedTaskRow row && row.id().equals(attached.id()));
        assertTrue("the session row stays collapsed, keyed on its session id", tree.isCollapsed(sessionPath()));
        assertTrue("the Tasks section stays collapsed", tree.isCollapsed(tasksSectionPath()));
    }

    // 4. Eight rapid saves produce one applied pass - the at-most-one-pending coalescer.
    public void testABurstOfSavesProducesOneAppliedPassAndNoFollowUp() throws IOException {
        Task task = newTask("A task that bursts the tree with saves");
        publish(task, List.of(sessionId.toString()));
        awaitTree(() -> underSession(task.id()), "the task under its session row");
        burst(task, List.of(sessionId.toString()), 8);
        settledAt(applies, "eight rapid saves coalesce into ONE applied pass");
    }

    // 5. A disposed panel rebuilds on nothing: publications continue, the tree stands still.
    public void testDisposeUnsubscribesPublicationsCauseNoRebuildAndNoLeak() throws IOException {
        Task task = newTask("A task that must stop rebuilding after the dispose");
        publish(task, List.of(sessionId.toString()));
        awaitTree(() -> underSession(task.id()), "the task under its session row");
        int before = applies;
        Object row = ((DefaultMutableTreeNode) taskPath(task.id()).getLastPathComponent()).getUserObject();
        Disposer.dispose(panel);
        panel = null;
        WatchedSnapshot last = plugin.taskService().watchedSnapshot();
        save(task, List.of(sessionId.toString()), "a post-dispose save\n");
        awaitWatched(c -> c != last, "the post-dispose publication");
        settledAt(before, "a disposed panel rebuilds on nothing");
        assertSame("no rebuild replaced the tree: the very row object is still in place", row,
                ((DefaultMutableTreeNode) taskPath(task.id()).getLastPathComponent()).getUserObject());
        List<String> lifecycle = new ArrayList<>(); // the inputs' own seam: close unsubscribes,
        TaskTreeInputs inputs = new TaskTreeInputs(onChange -> { // a post-close subscribe undoes itself
            lifecycle.add("sub");
            return () -> lifecycle.add("unsub");
        });
        inputs.subscribeToTasks(() -> { });
        inputs.close();
        inputs.subscribeToTasks(() -> { });
        assertEquals("no subscription survives close", List.of("sub", "unsub", "sub", "unsub"), lifecycle);
    }

    /** One store task: its title, id and the open IDE document of its file. */
    private record Task(String title, TaskId id, Document document) { }

    /** Writes the task under the sandbox store root and opens it as a real document. */
    private Task newTask(String title) throws IOException {
        TaskId id = TaskId.of("plate-" + Long.toString(System.nanoTime(), 36));
        Path file = root.resolve(RepositoryId.newId().toString()).resolve(id.text() + ".md");
        Files.createDirectories(file.getParent()); Files.writeString(file, taskFile(title, List.of(), "created\n"));
        fixtures.add(file);
        VirtualFile virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
        assertNotNull("the task file is visible in the VFS: " + file, virtual);
        return new Task(title, id, FileDocumentManager.getInstance().getDocument(virtual));
    }

    /** One IDE save of the whole file: the platform's own write path and its events. */
    private void save(Task task, List<String> sessions, String body) {
        ApplicationManager.getApplication().runWriteAction(
                () -> task.document().setText(taskFile(task.title(), sessions, body)));
        FileDocumentManager.getInstance().saveDocument(task.document());
    }

    /** Publishes a placement, then saves once more so the rebuild reads the ALREADY-published one. */
    private void publish(Task task, List<String> sessions) {
        save(task, sessions, "the placement settles\n");
        awaitWatched(c -> c.snapshot().byId().get(task.id()) != null
                && c.snapshot().byId().get(task.id()).sessions().equals(sessions), "the publication of " + task.id());
        save(task, sessions, "the placement settled\n"); // the panel's next own event
    }

    /** One burst of {@code rounds} rapid saves, then parks for its single applied pass. */
    private void burst(Task task, List<String> sessions, int rounds) {
        int before = applies;
        for (int round = 1; round <= rounds; round++) { // no pumping between the saves
            save(task, sessions, "burst round " + round + "\n");
        }
        awaitTree(() -> applies == before + 1, "the burst's single applied pass");
    }

    /** The full task file: the store writer's front-matter format plus the body. */
    private static String taskFile(String title, List<String> sessions, String body) {
        StringBuilder out = new StringBuilder("---\ntitle: \"").append(title)
                .append("\"\nstatus: open\ndone_by: human\ncreated: ")
                .append(Instant.now().minusSeconds(3_600))
                .append("\nafter: []\nsessions: [");
        for (int i = 0; i < sessions.size(); i++) {
            out.append(i > 0 ? ", " : "").append('\'').append(sessions.get(i)).append('\'');
        }
        return out.append("]\n---\n\n").append(body).toString();
    }

    private TreePath sessionPath() { return find(sessionRow); }
    private TreePath tasksSectionPath() { return find(o -> o instanceof TasksSection); }
    private TreePath taskPath(TaskId id) { return find(o -> o instanceof DelayedTaskRow r && r.id().equals(id)); }
    private boolean underSection(TaskId id) { return parentOf(id) instanceof TasksSection; } // D9
    private boolean underSession(TaskId id) { return sessionRow.test(parentOf(id)); } // D10
    private final Predicate<Object> sessionRow = o -> o instanceof TaskNode.TaskRow r
            && r.session().id().equals(sessionId);
    private Object parentOf(TaskId id) {
        TreePath path = taskPath(id);
        return path == null ? null
                : ((DefaultMutableTreeNode) path.getParentPath().getLastPathComponent()).getUserObject();
    }

    /** First depth-first path whose node carries a matching row, or null. */
    private TreePath find(Predicate<Object> match) {
        Object top = tree.getModel().getRoot();
        return top instanceof DefaultMutableTreeNode node ? find(node, new TreePath(node), match) : null;
    }

    private static TreePath find(DefaultMutableTreeNode node, TreePath path, Predicate<Object> match) {
        if (match.test(node.getUserObject())) return path;
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            TreePath found = find(child, path.pathByAddingChild(child), match);
            if (found != null) return found;
        }
        return null;
    }

    /** Spins (never sleeps) until the published composition satisfies the condition. */
    private void awaitWatched(Predicate<WatchedSnapshot> condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PUBLISH_SECONDS);
        WatchedSnapshot composition = plugin.taskService().watchedSnapshot();
        while (composition == null || !condition.test(composition)) {
            assertTrue(what + " did not arrive within " + PUBLISH_SECONDS + " s", System.nanoTime() < deadline);
            pump();
            composition = plugin.taskService().watchedSnapshot();
        }
    }

    /** Spins (never sleeps) until the panel's own tree state satisfies the condition. */
    private void awaitTree(BooleanSupplier state, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TREE_SECONDS);
        while (!state.getAsBoolean()) {
            assertTrue(what + " did not arrive within " + TREE_SECONDS + " s", System.nanoTime() < deadline);
            pump();
        }
    }

    /** Pumps a grace window over which the applied-pass count must stay at {@code count}. */
    private void settledAt(int count, String what) {
        long end = System.currentTimeMillis() + 1_500;
        while (System.currentTimeMillis() < end) { assertEquals(what, count, applies); pump(); }
    }

    /** Drains the EDT queue and counts each applied pass: every rebuild replaces the root. */
    private void pump() {
        for (int i = 0; i < 3; i++) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); TimeoutUtil.sleep(20); }
        Object root = tree.getModel().getRoot();
        if (root != appliedRoot) { appliedRoot = root; applies++; }
    }

    private static void delete(Path directory) {
        try (Stream<Path> entries = Files.walk(directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(
                    p -> { try { Files.deleteIfExists(p); } catch (IOException e) { } });
        } catch (IOException ignored) { }
    }
}
