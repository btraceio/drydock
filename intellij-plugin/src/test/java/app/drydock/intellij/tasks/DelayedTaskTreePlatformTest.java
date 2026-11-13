package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.delayedtasks.TaskStoreWatcher.WatchedSnapshot;
import app.drydock.domain.*;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.IsolatedCatalog;
import app.drydock.projects.SharedProject;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskNode.TasksSection;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.CustomizedDataContext;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
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

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import java.awt.event.KeyListener;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The panel's task wiring (T17a) in the booted IDE, observed in the inbox:
 * the REAL panel over the plugin's booted services, driven by store tasks
 * saved through real IDE documents. One save publishes a placement, one
 * more rebuilds on it (the watcher's debounced re-read lags the rebuild).
 * Every applied pass still replaces the projects tree's root, which is how
 * the passes are counted. Waits pump the EDT and poll a deadline; nothing
 * is shown.
 */
public class DelayedTaskTreePlatformTest extends BasePlatformTestCase {

    private static final long PUBLISH_SECONDS = 25; // the watcher re-reads every 5 s
    private static final long TREE_SECONDS = 20;
    private static final String SESSION = "session with a task";
    private DrydockPluginService plugin;
    private Path root;
    private Path repoRoot;
    private SharedProject registered;
    private ManagedSessionId sessionId;
    private TaskTreePanel panel;
    private JTree tree;
    private InboxList inbox;
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
                SESSION, AgentBinding.unlaunched(AgentKind.CLAUDE),
                new SessionWorkspace(repoRoot, Optional.empty(), false), SessionStatus.INACTIVE,
                anHourAgo, anHourAgo, Optional.empty(), PrLink.none(), false, Optional.empty(), false));
        // The light project is shared across cases: start every panel from no saved view.
        InboxViewState.getInstance(getProject()).loadState(new InboxViewState.Stored());
        panel = new TaskTreePanel(getProject());
        tree = panel.tree();
        inbox = panel.inbox();
        // The seeded session is stopped: its row is in the Idle group, collapsed by default.
        inbox.toggleCollapsed(InboxModel.GroupKind.IDLE);
        // Headless harness: the Swing layout cache would ask the panel's cell renderer
        // for the invisible root's null user object, which the renderer cannot answer
        // (a pre-existing production path); nothing paints, so the tree keeps no UI.
        tree.setUI(null);
        awaitTree(() -> inboxRow(inbox, SESSION) >= 0, "the seeded session's row");
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

    // 1. A snapshot publication followed by a save rebuilds the inbox through the coalescer.
    public void testASaveRebuildsTheInboxOnThePublishedSnapshot() throws IOException {
        Task task = newTask("A save rebuilds the inbox on the publication");
        publish(task, "open", List.of()); // the trigger save rebuilds on the already-published placement
        awaitTree(() -> groupOf(task.title()) == InboxModel.GroupKind.READY, "the saved task in Ready to start");
    }

    // 2. A started task rides on its live session's row and is its own row again once
    // no session holds it (T15a placement, D10): interrupted tasks are ready to start.
    public void testAStartedTaskRidesOnItsSessionRowAndComesBackWhenInterrupted() throws IOException {
        Task task = newTask("A started task that rides on its session row");
        publish(task, "started", List.of(sessionId.toString()));
        awaitTree(() -> inboxRow(inbox, task.title()) < 0 && sessionItem().taskTitles().contains(task.title()),
                "the started task carried by its session row");
        publish(task, "started", List.of());
        awaitTree(() -> groupOf(task.title()) == InboxModel.GroupKind.READY
                        && !sessionItem().taskTitles().contains(task.title()),
                "the interrupted task back as its own row");
    }

    // 3. The selection survives a coalesced rebuild, keyed on its task id, and the
    // group the user collapsed stays collapsed.
    public void testSelectionAndCollapsesSurviveACoalescedRebuild() throws IOException {
        Task selectedTask = newTask("A task that keeps the selection");
        publish(selectedTask, "open", List.of(sessionId.toString()));
        awaitTree(() -> groupOf(selectedTask.title()) == InboxModel.GroupKind.READY, "the task in Ready to start");
        inbox.toggleCollapsed(InboxModel.GroupKind.IDLE);
        inbox.setSelectedIndex(inboxRow(inbox, selectedTask.title()));
        burst(selectedTask, "open", List.of(sessionId.toString()), 3);
        assertEquals("the selection survives the rebuild, keyed on the task id",
                List.of(selectedTask.title()), inbox.selectedItems().stream().map(InboxModel.Item::title).toList());
        assertTrue("the collapsed Idle group stays collapsed", inbox.entries().stream().anyMatch(entry ->
                entry instanceof InboxList.Header header && header.group().kind() == InboxModel.GroupKind.IDLE
                        && header.collapsed()));
    }

    // 4. Eight rapid saves produce one applied pass - the at-most-one-pending coalescer.
    public void testABurstOfSavesProducesOneAppliedPassAndNoFollowUp() throws IOException {
        Task task = newTask("A task that bursts the panel with saves");
        publish(task, "open", List.of(sessionId.toString()));
        awaitTree(() -> inboxRow(inbox, task.title()) >= 0, "the task in the inbox");
        burst(task, "open", List.of(sessionId.toString()), 8);
        settledAt(applies, "eight rapid saves coalesce into ONE applied pass");
    }

    // 5. A disposed panel rebuilds on nothing: publications continue, the inbox stands still.
    public void testDisposeUnsubscribesPublicationsCauseNoRebuildAndNoLeak() throws IOException {
        Task task = newTask("A task that must stop rebuilding after the dispose");
        publish(task, "open", List.of(sessionId.toString()));
        awaitTree(() -> inboxRow(inbox, task.title()) >= 0, "the task in the inbox");
        int before = applies;
        Object row = inbox.entries().get(inboxRow(inbox, task.title()));
        Disposer.dispose(panel);
        panel = null;
        WatchedSnapshot last = plugin.taskService().watchedSnapshot();
        save(task, "open", List.of(sessionId.toString()), "a post-dispose save\n");
        awaitWatched(c -> c != last, "the post-dispose publication");
        settledAt(before, "a disposed panel rebuilds on nothing");
        assertSame("no rebuild replaced the inbox: the very entry is still in place", row,
                inbox.entries().get(inboxRow(inbox, task.title())));
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

    // 6. The jump action reveals the linked task row in the inbox. The reveal resolves
    // the target from the live selection on the EDT -- the seeded session row is
    // selected before the dispatch.
    public void testTheJumpActionRevealsTheLinkedTaskRow() throws IOException {
        Task task = newTask("A task the jump action reveals");
        publish(task, "open", List.of(sessionId.toString()));
        awaitTree(() -> inboxRow(inbox, task.title()) >= 0, "the task in the inbox");
        inbox.setSelectedIndex(inboxRow(inbox, SESSION));
        AnAction showTask = panel.showLinkedAction(TaskTreePanel.ShowKind.TASK,
                "Show Task", "Reveal the task row this row is linked to");
        showTask.actionPerformed(jumpEvent());
        assertEquals("the jump revealed the linked task row", List.of(task.title()),
                inbox.selectedItems().stream().map(InboxModel.Item::title).toList());
    }

    // 7. A disposed panel reveals nothing: the disposed guard is the only stopper.
    public void testADisposedPanelRevealsNothing() throws IOException {
        Task task = newTask("A task a disposed panel never reveals");
        publish(task, "open", List.of(sessionId.toString()));
        awaitTree(() -> inboxRow(inbox, task.title()) >= 0, "the task in the inbox");
        inbox.setSelectedIndex(inboxRow(inbox, SESSION));
        AnAction showTask = panel.showLinkedAction(TaskTreePanel.ShowKind.TASK,
                "Show Task", "Reveal the task row this row is linked to");
        Disposer.dispose(panel);
        panel = null;
        showTask.actionPerformed(jumpEvent());
        assertEquals("a disposed panel leaves the selection alone", List.of(SESSION),
                inbox.selectedItems().stream().map(InboxModel.Item::title).toList());
    }

    // 8. The console-selection twin of the reveal: the selection lands on the session row.
    public void testConsoleSelectionSelectsTheSessionRow() throws IOException {
        Task task = newTask("A task whose console selection selects the session");
        publish(task, "open", List.of(sessionId.toString()));
        awaitTree(() -> inboxRow(inbox, task.title()) >= 0, "the task in the inbox");
        inbox.setSelectedIndex(inboxRow(inbox, task.title()));
        panel.selectSessionRow(sessionId);
        assertEquals("the console selection landed on the session row", List.of(SESSION),
                inbox.selectedItems().stream().map(InboxModel.Item::title).toList());
    }

    public void testTheInboxSelectionIsThePanelsSelectedNode() throws IOException {
        inbox.setSelectedIndex(inboxRow(inbox, SESSION));
        // The headless DataManager does not walk components: snapshot the panel directly.
        DataContext context = CustomizedDataContext.withSnapshot(DataContext.EMPTY_CONTEXT,
                sink -> sink.uiDataSnapshot((UiDataProvider) panel));
        Object node = TaskTreePanel.SELECTED_NODE.getData(context);
        assertTrue("the inbox selection is the panel's selected node",
                node instanceof TaskNode.TaskRow row && row.session().id().equals(sessionId));
        assertEquals(repoRoot.toRealPath(), TaskTreePanel.SELECTED_PROJECT_ROOT.getData(context));
    }

    public void testGroupWordsDoNotNarrowTheProjectsTree() {
        panel.showProjects(true);
        Predicate<Object> projectRow = o -> o instanceof TaskNode.ProjectRow row
                && row.project().id().equals(registered.id());
        awaitTree(() -> find(projectRow) != null, "the registered project's row");
        int before = applies;
        panel.search().setText("is:idle");
        awaitTree(() -> applies > before, "the pass the search asks for");
        assertNotNull("is:<group> words are the inbox's alone", find(projectRow));
        assertEquals("No project matches the search.", TaskTreePanel.emptyText("x"));
    }

    public void testTheProjectsViewHasNoSessionsAndKeepsItsProjectRow() {
        panel.showProjects(true);
        assertNull("the projects tree holds no session rows",
                find(o -> o instanceof TaskNode.TaskRow r && r.session().id().equals(sessionId)));
        assertNull("nor the Tasks section", find(o -> o instanceof TasksSection));
        TreePath project = find(o -> o instanceof TaskNode.ProjectRow row
                && row.project().id().equals(registered.id()));
        assertNotNull("the registered project's row is there", project);
        assertTrue("a project row starts collapsed", tree.isCollapsed(project));
    }

    public void testPickingAProjectFiltersTheInboxAndBackspaceClearsIt() throws IOException {
        Task task = newTask("A task outside every registered project");
        publish(task, "open", List.of());
        InboxSearchField search = panel.search();
        awaitTree(() -> inboxRow(inbox, task.title()) >= 0, "the task in the inbox");
        search.setText("project:" + registered.displayName());
        assertEquals(List.of(registered.displayName()),
                search.suggestionsShown().stream().map(s -> s.project().name()).toList());
        press(search, KeyEvent.VK_ENTER);
        assertEquals(Optional.of(registered.displayName()), search.project().map(InboxModel.ProjectRef::name));
        awaitTree(() -> inboxRow(inbox, task.title()) < 0, "the project filter hiding the unrelated task");
        assertTrue("the project's own session stays", inbox.entries().stream().anyMatch(entry ->
                entry instanceof InboxList.Header header && header.group().kind() == InboxModel.GroupKind.IDLE));
        press(search, KeyEvent.VK_BACK_SPACE);
        assertEquals(Optional.empty(), search.project());
        awaitTree(() -> inboxRow(inbox, task.title()) >= 0, "every project again");
    }

    public void testASecondPanelRestoresTheSavedViewAndDropsAnUnregisteredFilter() throws IOException {
        InboxSearchField search = panel.search();
        search.setText("project:" + registered.displayName());
        press(search, KeyEvent.VK_ENTER);
        InboxViewState saved = InboxViewState.getInstance(getProject());
        assertEquals(Optional.of(registered.displayName()), saved.filter().map(InboxModel.ProjectRef::name));
        assertEquals("setUp's expanded Idle group is saved", Set.of(InboxModel.GroupKind.IDLE),
                saved.toggles().expanded());
        Disposer.dispose(panel);

        panel = new TaskTreePanel(getProject());
        inbox = panel.inbox();
        tree = panel.tree();
        tree.setUI(null);
        assertEquals("the chip is back before any rebuild", Optional.of(registered.displayName()),
                panel.search().project().map(InboxModel.ProjectRef::name));
        awaitTree(() -> inboxRow(inbox, SESSION) >= 0, "the session in the restored, expanded Idle group");

        // A saved filter whose project is gone: the next pass drops it.
        saved.setFilter(Optional.of(new InboxModel.ProjectRef(repoRoot.resolve("gone"), "gone")));
        Disposer.dispose(panel);
        panel = new TaskTreePanel(getProject());
        inbox = panel.inbox();
        tree = panel.tree();
        tree.setUI(null);
        TaskTreePanel restored = panel;
        awaitTree(() -> restored.search().project().isEmpty() && saved.filter().isEmpty(),
                "the unregistered filter dropped");
    }

    /** A key press straight to the field's listeners: headless, AWT retargets dispatched keys to a focus owner. */
    private static void press(InboxSearchField field, int keyCode) {
        KeyEvent event = new KeyEvent(field, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, keyCode,
                KeyEvent.CHAR_UNDEFINED);
        for (KeyListener listener : field.getKeyListeners()) {
            listener.keyPressed(event);
        }
    }

    /** The index of the inbox row titled {@code title}, or -1. */
    private static int inboxRow(InboxList inbox, String title) {
        List<InboxList.Entry> entries = inbox.entries();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) instanceof InboxList.Row row && row.item().title().equals(title)) {
                return i;
            }
        }
        return -1;
    }

    /** A click event for a jump action's EDT path: the reveal reads only the tree. */
    private static AnActionEvent jumpEvent() {
        return AnActionEvent.createEvent(SimpleDataContext.builder()
                        .add(CommonDataKeys.PROJECT, null).build(),
                new Presentation(), ActionPlaces.UNKNOWN, ActionUiKind.NONE, null);
    }

    /** One store task: its title, id and the open IDE document of its file. */
    private record Task(String title, TaskId id, Document document) { }

    /** Writes the task under the sandbox store root and opens it as a real document. */
    private Task newTask(String title) throws IOException {
        TaskId id = TaskId.of("plate-" + Long.toString(System.nanoTime(), 36));
        Path file = root.resolve(RepositoryId.newId().toString()).resolve(id.text() + ".md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, taskFile(title, "open", List.of(), "created\n"));
        fixtures.add(file);
        VirtualFile virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
        assertNotNull("the task file is visible in the VFS: " + file, virtual);
        return new Task(title, id, FileDocumentManager.getInstance().getDocument(virtual));
    }

    /** One IDE save of the whole file: the platform's own write path and its events. */
    private void save(Task task, String status, List<String> sessions, String body) {
        ApplicationManager.getApplication().runWriteAction(
                () -> task.document().setText(taskFile(task.title(), status, sessions, body)));
        FileDocumentManager.getInstance().saveDocument(task.document());
    }

    /** Publishes a placement, then saves once more so the rebuild reads the ALREADY-published one. */
    private void publish(Task task, String status, List<String> sessions) {
        save(task, status, sessions, "the placement settles\n");
        awaitWatched(c -> c.snapshot().byId().get(task.id()) != null
                && c.snapshot().byId().get(task.id()).sessions().equals(sessions), "the publication of " + task.id());
        save(task, status, sessions, "the placement settled\n"); // the panel's next own event
    }

    /** One burst of {@code rounds} rapid saves, then parks for its single applied pass. */
    private void burst(Task task, String status, List<String> sessions, int rounds) {
        int before = applies;
        for (int round = 1; round <= rounds; round++) { // no pumping between the saves
            save(task, status, sessions, "burst round " + round + "\n");
        }
        awaitTree(() -> applies == before + 1, "the burst's single applied pass");
    }

    /** The full task file: the store writer's front-matter format plus the body. */
    private static String taskFile(String title, String status, List<String> sessions, String body) {
        StringBuilder out = new StringBuilder("---\ntitle: \"").append(title)
                .append("\"\nstatus: ").append(status).append("\ndone_by: human\ncreated: ")
                .append(Instant.now().minusSeconds(3_600))
                .append("\nafter: []\nsessions: [");
        for (int i = 0; i < sessions.size(); i++) {
            out.append(i > 0 ? ", " : "").append('\'').append(sessions.get(i)).append('\'');
        }
        return out.append("]\n---\n\n").append(body).toString();
    }

    /** The group the inbox shows the row titled {@code title} in, or null. */
    private InboxModel.GroupKind groupOf(String title) {
        for (InboxList.Entry entry : inbox.entries()) {
            if (entry instanceof InboxList.Row row && row.item().title().equals(title)) {
                return row.group().kind();
            }
        }
        return null;
    }

    /** The seeded session's inbox item. */
    private InboxModel.SessionItem sessionItem() {
        return inbox.entries().stream()
                .filter(InboxList.Row.class::isInstance).map(entry -> ((InboxList.Row) entry).item())
                .filter(InboxModel.SessionItem.class::isInstance).map(InboxModel.SessionItem.class::cast)
                .filter(item -> item.row().session().id().equals(sessionId))
                .findFirst().orElseThrow();
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
