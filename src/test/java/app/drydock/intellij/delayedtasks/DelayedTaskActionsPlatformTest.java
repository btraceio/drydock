package app.drydock.intellij.delayedtasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.domain.*;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.IsolatedCatalog;
import app.drydock.intellij.tasks.TaskMirroring;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.TaskState.Presentation;
import app.drydock.tasks.*;
import com.intellij.notification.Notification;
import com.intellij.notification.Notifications;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.TimeoutUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * The T20b matrix for the accepted tree actions (T20a) in the booted IDE:
 * real update()/actionPerformed over constructed events; writes through the
 * plugin's booted store-backed service seam (zero mocking); the Drop ask via
 * the platform's headless dialog seam (nothing shown; the accepted code has
 * a plain OK/Cancel ask, so the plan's default-checked wording has no
 * analog); zero-write proofs by settle-then-decode; the blocker refusals
 * off the CACHED watched snapshot. A session row's TaskRow is
 * package-private and unconstructible here; its event carries no
 * SELECTED_TASK, which the section and empty cases cover. The one
 * missing-snapshot dispatch parks the publication field, restored in a finally.
 */
public class DelayedTaskActionsPlatformTest extends BasePlatformTestCase {

    /** The repository for rows whose repository is irrelevant to the behavior tested. */
    private static final RepositoryId ANYWHERE = RepositoryId.newId();
    private DrydockPluginService plugin;
    private Path storeRoot;
    private final List<Path> repoDirs = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        IsolatedCatalog.assertSandboxed();
        super.setUp();
        plugin = DrydockPluginService.getInstance();
        try { plugin.initialization().get(60, TimeUnit.SECONDS); } catch (Exception e) { fail("init: " + e); }
        storeRoot = plugin.taskService().root().toAbsolutePath().normalize();
        assertTrue("the store root must be the Gradle test sandbox: " + storeRoot,
                !storeRoot.startsWith(Path.of(System.getProperty("user.home"), ".drydock")));
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), storeRoot.toString());
    }

    @Override
    protected void tearDown() throws Exception {
        try { perm("rwxr-xr-x"); } catch (Exception ignored) { }
        for (Path dir : repoDirs) try (Stream<Path> entries = Files.walk(dir)) {
            entries.sorted(Comparator.reverseOrder()).forEach(
                    p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
        super.tearDown();
    }

    // 1. Enablement: the presentation CARRIED on the row decides; a section or nothing carries no task data.
    public void testUpdateEnablesPerSelectionTypeAndPlace() {
        DelayedTaskActions.Entry[] menu = DelayedTaskActions.menu(); // Item.values() order
        DelayedTaskActions.Item[] items = DelayedTaskActions.Item.values();
        for (int i = 0; i < menu.length; i++) {
            AnAction action = ActionManager.getInstance().getAction(menu[i].id());
            assertNotNull(menu[i].id() + " must be registered", action);
            assertSame(menu[i].id() + " resolves to its class", menu[i].local().getClass(), action.getClass());
            for (Presentation presentation : Presentation.values())
                check(action, items[i], row(TaskId.of("row-" + presentation.name().toLowerCase()),
                        presentation, "row task", ANYWHERE));
            for (Object selection : Arrays.asList(new TaskNode.TasksSection("Tasks", List.of()), null))
                check(action, items[i], selection);
        }
    }

    /** The real update() on this selection, for the tree's menu and everywhere else. */
    private void check(AnAction action, DelayedTaskActions.Item item, Object selection) {
        boolean applies = selection instanceof DelayedTaskRow row && switch (item) {
            // The mirror actions key on the row's mirrors, not its presentation.
            case MIRROR_TO_GITHUB -> TaskMirroring.offersGitHub(row);
            case MIRROR_TO_JIRA -> TaskMirroring.offersJira(row);
            case OPEN_MIRRORS -> !row.mirrors().isEmpty();
            default -> DelayedTaskActions.applicable(row.presentation()).contains(item);
        };
        var menu = new com.intellij.openapi.actionSystem.Presentation();
        action.update(event(selection, menu, true));
        assertTrue(selection + " menu shows what applies",
                menu.isEnabled() == applies && menu.isVisible() == applies);
        var elsewhere = new com.intellij.openapi.actionSystem.Presentation();
        action.update(event(selection, elsewhere, false));
        assertTrue(selection + " elsewhere greyed",
                elsewhere.isVisible() && elsewhere.isEnabled() == applies);
    }
    private AnActionEvent event(Object selection, com.intellij.openapi.actionSystem.Presentation
            shown, boolean popup) {
        return AnActionEvent.createEvent(context(selection), shown,
                popup ? ActionPlaces.POPUP : ActionPlaces.ACTION_SEARCH,
                popup ? ActionUiKind.POPUP : ActionUiKind.NONE, null);
    }

    // 2-4. Dispatch rides the updateStatus seam; the ask comes first; canceled ask writes nothing.
    public void testDispatchRidesTheSeamAndCancelAndFailureAreVisible() throws IOException {
        TaskId shipped = task("Ship the release");
        plugin.taskService().updateStatus(shipped, TaskState.Actor.AGENT, TaskStatus.DONE);
        await(() -> status(shipped) == TaskStatus.REVIEW, "the agent's done parking in review (D16)");
        List<String> dialogs = new ArrayList<>();
        TestDialogManager.setTestDialog(message -> { dialogs.add(message);
            return message.startsWith("Drop the task 'Never dropped") ? 1 : 0; }, getTestRootDisposable());
        dispatch(DelayedTaskActions.Confirm.ID, row(shipped, Presentation.REVIEW, "Ship the release", ANYWHERE));
        await(() -> status(shipped) == TaskStatus.DONE, "the Confirm dispatch committing done");
        dispatch(DelayedTaskActions.Reopen.ID, row(shipped, Presentation.TERMINAL, "Ship the release", ANYWHERE));
        await(() -> status(shipped) == TaskStatus.OPEN, "the Reopen dispatch committing open");
        dispatch(DelayedTaskActions.Drop.ID, row(shipped, Presentation.READY, "Ship the release", ANYWHERE));
        await(() -> status(shipped) == TaskStatus.DROPPED, "the Drop dispatch committing dropped");
        assertTrue("Drop asks first, exactly once, with the human-only wording", dialogs.size() == 1
                && dialogs.get(0).contains("Drop the task 'Ship the release'")
                && dialogs.get(0).contains("only a human may drop one"));
        TaskId never = task("Never dropped");
        Map<String, String> before = settledTree();
        dispatch(DelayedTaskActions.Drop.ID, row(never, Presentation.READY, "Never dropped", ANYWHERE));
        assertTrue("the ask was canceled", dialogs.size() == 2
                && dialogs.get(1).contains("Drop the task 'Never dropped'"));
        assertEquals("a canceled ask wrote nothing: the settled store is identical", before, settledTree());
        perm("---------"); // every scan and write now fails; the watcher retains its last composition
        try {
            dispatch(DelayedTaskActions.Confirm.ID, row(never, Presentation.REVIEW, "Never dropped", ANYWHERE));
            await(() -> dialogs.size() == 3, "the visible refusal of the failed write");
        } finally {
            perm("rwxr-xr-x");
        }
        assertTrue("the refusal names the change", dialogs.get(2).contains("Drydock could not change"));
        assertEquals("the failed write changed nothing on disk", before, settledTree());
    }

    // 5. Three blocker refusals, all off the CACHED snapshot; an unreadable store still enables.
    public void testGotoBlockerSessionRefusesVisiblyFromTheCachedSnapshot() throws Exception {
        List<String> shown = new ArrayList<>();
        TestDialogManager.setTestDialog(message -> { shown.add(message); return 0; }, getTestRootDisposable());
        RepositoryId repository = RepositoryId.newId();
        ManagedSessionId live = ManagedSessionId.newId();
        TaskId blocker = write("unblock-build", "Unblock the build", List.of(), List.of(live.toString()), repository);
        TaskId dependent = write("ship-release", "Ship the release", List.of(blocker.text()), List.of(), repository);
        awaitWatched(dependent, "the pair landing in the watched snapshot");
        DelayedTaskRow blocked = row(dependent, Presentation.BLOCKED, "Ship the release", repository);
        dispatch(DelayedTaskActions.GotoBlockerSession.ID, blocked);
        await(() -> !shown.isEmpty(), "the no-live-blocker refusal");
        assertTrue("no live blocker refuses", shown.get(0).contains("no blocker that a live session is running"));
        IsolatedCatalog.seed(session(live, "runs the blocker"));
        plugin.taskService().snapshot().get(30, TimeUnit.SECONDS); // one fresh scan: the session is live
        assertTrue("the session is live", plugin.taskService().liveSessionIds().contains(live.toString()));
        perm("---------");
        IsolatedCatalog.remove(live); // live in the cached view, gone from the catalog
        try {
            AnAction action = ActionManager.getInstance().getAction(DelayedTaskActions.GotoBlockerSession.ID);
            var menu = new com.intellij.openapi.actionSystem.Presentation();
            action.update(event(blocked, menu, true));
            assertTrue("no disk probe: unreadable store still enables", menu.isEnabled() && menu.isVisible());
            dispatch(DelayedTaskActions.GotoBlockerSession.ID, blocked);
            await(() -> shown.size() == 2, "the vanished-session refusal");
            assertTrue("a vanished session refuses", shown.get(1).contains("no longer in Drydock's session catalog"));
            Field publications = DelayedTaskService.class.getDeclaredField("watchedSnapshot");
            publications.setAccessible(true);
            Object parked = publications.get(plugin.taskService());
            publications.set(plugin.taskService(), null);
            try {
                dispatch(DelayedTaskActions.GotoBlockerSession.ID, blocked);
                await(() -> shown.size() == 3, "the no-snapshot refusal");
                assertTrue("no snapshot refuses", shown.get(2).contains("has not read the task store yet"));
            } finally {
                publications.set(plugin.taskService(), parked);
            }
        } finally {
            perm("rwxr-xr-x");
        }
    }

    // 6. Drydock.NewTask keeps id/class/shortcut; the five task-node ids are the classes' ID constants.
    public void testPluginXmlKeepsTheWireContract() throws Exception {
        Map<String, Element> actions = new LinkedHashMap<>();
        try (InputStream xml = getClass().getResourceAsStream("/META-INF/plugin.xml")) {
            assertNotNull("the plugin descriptor must be on the classpath", xml);
            Document parsed = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml);
            NodeList nodes = parsed.getElementsByTagName("action");
            for (int i = 0; i < nodes.getLength(); i++)
                actions.put(((Element) nodes.item(i)).getAttribute("id"), (Element) nodes.item(i));
        }
        Element newTask = actions.get("Drydock.NewTask");
        assertNotNull("Drydock.NewTask stays registered", newTask);
        assertTrue("the NewTask wire contract is intact (class + shortcut)",
                "app.drydock.intellij.tasks.NewTaskAction".equals(newTask.getAttribute("class"))
                        && tagCount(newTask, "keyboard-shortcut") == 1
                        && "alt meta N".equals(newTask.getElementsByTagName("keyboard-shortcut").item(0)
                                .getAttributes().getNamedItem("first-keystroke").getNodeValue()));
        for (DelayedTaskActions.Entry entry : DelayedTaskActions.menu()) {
            Element action = actions.get(entry.id()); // the ids ARE the classes' ID constants
            assertNotNull(entry.id() + " must be registered", action);
            assertTrue(entry.id() + " names its action class, with no shortcut and no group",
                    entry.local().getClass().getName().equals(action.getAttribute("class"))
                            && tagCount(action, "keyboard-shortcut") == 0
                            && tagCount(action, "add-to-group") == 0);
        }
    }

    // 7. Refresh applies whatever the presentation; applicable() hands back a fresh set per call.
    public void testRefreshAppliesToEveryPresentation() {
        for (Presentation presentation : Presentation.values()) {
            Set<DelayedTaskActions.Item> applicable = DelayedTaskActions.applicable(presentation);
            assertTrue("Refresh applies whatever the presentation (" + presentation + ")",
                    applicable.contains(DelayedTaskActions.Item.REFRESH));
            assertNotSame("applicable() returns a fresh set per call",
                    applicable, DelayedTaskActions.applicable(presentation));
        }
    }

    /** Notifications is not a functional interface in the IDE build (notify and
     * register are all default), so the balloon sink needs the anonymous-class
     * recorder idiom used by the sibling platform tests. */
    private static Notifications recorder(List<Notification> into) {
        return new Notifications() { @Override public void notify(Notification n) { into.add(n); } };
    }

    /** The refusal balloons the manual Refresh publishes, by title -- never by
     * position, so the fixture's own READY balloon can never stand in. */
    private static List<Notification> refusalBalloons(List<Notification> shown) {
        return shown.stream()
                .filter(n -> "Drydock did not refresh the tasks".equals(n.getTitle()))
                .toList();
    }

    // 8. The save-conflict refusal of a clicked Refresh is exactly one visible balloon.
    public void testTheManualRefreshsUnsavedRefusalIsOneVisibleBalloon() throws Exception {
        // Create and settle FIRST, drain the fixture's own READY balloon, subscribe LAST.
        TaskId id = task("Manual refresh refusal subject");
        awaitWatched(id, "the created task settling into the watched snapshot");
        pump(); pump(); pump();
        List<Notification> shown = new CopyOnWriteArrayList<>();
        getProject().getMessageBus().connect(getTestRootDisposable())
                .subscribe(Notifications.TOPIC, recorder(shown));
        // Dirty the task file WITHOUT saving: checkUnsaved reads unsaved documents, not editors.
        TaskDocument document = plugin.taskService().snapshot()
                .get(30, TimeUnit.SECONDS).byId().get(id);
        Path file = storeRoot.resolve(document.repository().toString())
                .resolve(id.text() + ".md");
        VirtualFile virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
        assertNotNull("the task file must be visible in the VFS: " + file, virtual);
        com.intellij.openapi.editor.Document unsaved =
                FileDocumentManager.getInstance().getDocument(virtual);
        assertNotNull("no document for " + file, unsaved);
        ApplicationManager.getApplication().runWriteAction(
                () -> unsaved.setText(unsaved.getText() + "\n<!-- unsaved edit -->\n"));
        dispatch(DelayedTaskActions.Refresh.ID,
                row(id, Presentation.READY, "Manual refresh refusal subject", ANYWHERE));
        await(() -> !refusalBalloons(shown).isEmpty(), "the refusal balloon");
        List<Notification> refusals = refusalBalloons(shown);
        assertEquals(1, refusals.size());
        assertEquals(NotificationType.WARNING, refusals.get(0).getType());
        assertEquals("Drydock did not refresh the tasks", refusals.get(0).getTitle());
        assertTrue("the balloon names the unsaved file",
                refusals.get(0).getContent().contains("The reload was refused"));
        pump(); pump();
        assertEquals("the invariant is ONE balloon, not at-least-one", 1, refusalBalloons(shown).size());
    }

    // The RuntimeException catch in DelayedTaskActions.refresh is defensive only:
    // markDirty's foreign-path startsWith on the IDE's MultiRoutingFileSystem
    // answers false without touching the argument, so no watcher-root injection
    // can make refreshStore throw -- the failure balloon has no sound automated
    // seam and stays covered by the refusal test above (same title, one balloon).

    /** The actions key only on SELECTED_TASK, which the panel sinks for a task node;
     * a session row or section carries none, so their context carries none either. */
    private DataContext context(Object selection) {
        SimpleDataContext.Builder data = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject());
        if (selection instanceof DelayedTaskRow row) data = data.add(DelayedTaskActions.SELECTED_TASK, row);
        return data.build();
    }
    private void dispatch(String id, DelayedTaskRow row) {
        AnAction action = ActionManager.getInstance().getAction(id);
        assertNotNull(id + " must be registered", action);
        var menu = new com.intellij.openapi.actionSystem.Presentation();
        action.actionPerformed(event(row, menu, true));
    }

    private static DelayedTaskRow row(TaskId id, Presentation presentation, String title,
            RepositoryId repository) {
        return new DelayedTaskRow(new TaskState.TaskView(id, presentation, List.of(), List.of()), title,
                repository, List.of());
    }
    private static ManagedAgentSession session(ManagedSessionId id, String title) {
        return new ManagedAgentSession.Builder(id, RepositoryId.newId(), title,
                AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/wt/" + title)), Instant.EPOCH).build();
    }
    private TaskId task(String title) {
        var outcome = plugin.taskService().create(title, List.of(), RepositoryId.newId()).join();
        assertFalse("the fixture's task creation was refused: " + outcome.refusal(), outcome.refused());
        repoDirs.add(storeRoot.resolve(outcome.document().repository().toString()));
        return outcome.document().id();
    }
    /** One task file written directly under the store root (the D10 stored-sessions route). */
    private TaskId write(String slug, String title, List<String> after, List<String> sessions,
            RepositoryId repository) throws IOException {
        TaskId id = TaskId.of(slug);
        Path dir = storeRoot.resolve(repository.toString());
        repoDirs.add(dir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id.text() + ".md"), "---\ntitle: \"" + title + "\"\nstatus: open\n"
                + "done_by: human\ncreated: " + Instant.now() + "\nafter: [" + quoted(after)
                + "]\nsessions: [" + quoted(sessions) + "]\n---\n\nFixture body.\n");
        return id;
    }
    private static String quoted(List<String> refs) {
        return String.join(", ", refs.stream().map(ref -> "'" + ref + "'").toList());
    }
    private TaskStatus status(TaskId task) {
        try { return plugin.taskService().snapshot().get(30, TimeUnit.SECONDS).byId().get(task).status(); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    /** Spins (never sleeps) until the condition holds, pumping the EDT queue between polls. */
    private void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!condition.getAsBoolean()) { assertTrue("timed out waiting for " + what,
                System.nanoTime() < deadline); pump(); }
    }
    /** Waits until the watched snapshot holds this OPEN task, pump-parking in between. */
    private void awaitWatched(TaskId task, String what) {
        await(() -> watched(task) != null && watched(task).status() == TaskStatus.OPEN, what);
    }
    private TaskDocument watched(TaskId task) {
        var watched = plugin.taskService().watchedSnapshot();
        return watched == null ? null : watched.snapshot().byId().get(task);
    }
    private static void pump() {
        for (int i = 0; i < 3; i++) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); TimeoutUtil.sleep(20); }
    }
    /** Settled-then-decoded store content: never race a pending write (the T20a fix). */
    private Map<String, String> settledTree() throws IOException {
        Map<String, String> tree = decoded();
        for (int i = 0; i < 100; i++) { pump(); if (decoded().equals(tree)) return tree; tree = decoded(); }
        return tree;
    }
    private Map<String, String> decoded() throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(storeRoot)) {
            for (Path path : paths.toList()) if (Files.isRegularFile(path))
                out.put(storeRoot.relativize(path).toString(), Files.readString(path));
        }
        return out;
    }
    private void perm(String mode) throws IOException {
        Files.setPosixFilePermissions(storeRoot, PosixFilePermissions.fromString(mode));
    }
    private static int tagCount(Element action, String tag) {
        return action.getElementsByTagName(tag).getLength();
    }
}
