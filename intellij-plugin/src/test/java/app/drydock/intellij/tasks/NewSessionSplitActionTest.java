package app.drydock.intellij.tasks;

import app.drydock.domain.RepositoryId;
import app.drydock.domain.SshRemote;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.tasks.NewSessionSplitAction.Entry;
import app.drydock.intellij.tasks.NewSessionSplitAction.Picker;
import app.drydock.projects.SharedProject;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The toolbar's New Session split: a fixed primary (the registered
 * {@code Drydock.NewTask}) and an arrow listing the shared registry's
 * current snapshot. The popup display, the background executor, the listing
 * and the launcher are seams, so the suite runs headless, controls when the
 * listing completes, and records which root a pick launches — no dialog
 * opens and no registry file is read or written.
 */
public class NewSessionSplitActionTest extends BasePlatformTestCase {

    private static final Instant ADDED = Instant.parse("2026-11-13T10:00:00Z");

    private final List<Runnable> queued = new CopyOnWriteArrayList<>();
    private final List<Path> launched = new CopyOnWriteArrayList<>();
    private final List<Project> launchedFrom = new CopyOnWriteArrayList<>();
    private final List<JPopupMenu> displayed = new ArrayList<>();
    private final List<Thread> listingThreads = new CopyOnWriteArrayList<>();

    // ---- the pure projection --------------------------------------------------

    public void testEntriesKeepTheRegistryOrderAndDuplicateNamesBoundToTheirOwnRecord() {
        SharedProject first = local("backend", Path.of("/work/a/backend"));
        SharedProject second = local("backend", Path.of("/work/b/backend"));
        SharedProject third = local("frontend", Path.of("/work/frontend"));

        List<Entry> entries = NewSessionSplitAction.projectEntries(List.of(first, second, third));

        assertEquals(List.of("backend", "backend", "frontend"), entries.stream().map(Entry::text).toList());
        assertSame(first, entries.get(0).target().orElseThrow());
        assertSame(second, entries.get(1).target().orElseThrow());
        assertSame(third, entries.get(2).target().orElseThrow());
        assertTrue(entries.stream().allMatch(Entry::enabled));
    }

    public void testARemoteEntryIsListedDisabledWithItsReasonAndAMissingLocalFolderStaysSelectable() {
        SharedProject remote = new SharedProject(RepositoryId.newId(),
                new SshRemote("box", "/srv/repo").placeholderRoot(), "on-box", ADDED,
                Optional.of(new SshRemote("box", "/srv/repo")));
        Path gone = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()
                .resolve("drydock-missing-" + System.nanoTime());
        assertFalse(Files.exists(gone));
        SharedProject missing = local("missing", gone);

        List<Entry> entries = NewSessionSplitAction.projectEntries(List.of(remote, missing));

        assertEquals(2, entries.size());
        Entry remoteEntry = entries.get(0);
        assertEquals("on-box" + NewSessionSplitAction.REMOTE_SUFFIX, remoteEntry.text());
        assertFalse(remoteEntry.enabled());
        assertEquals(Optional.of(NewSessionSplitAction.REMOTE_REASON), remoteEntry.disabledReason());
        assertSame(remote, remoteEntry.target().orElseThrow());
        Entry missingEntry = entries.get(1);
        assertEquals("missing", missingEntry.text());
        assertTrue("a local folder that vanished is the launcher's to refuse", missingEntry.enabled());
    }

    public void testAnEmptyRegistryIsOneDisabledEntry() {
        List<Entry> entries = NewSessionSplitAction.projectEntries(List.of());

        assertEquals(1, entries.size());
        assertEquals(NewSessionSplitAction.EMPTY, entries.get(0).text());
        assertFalse(entries.get(0).enabled());
        assertTrue(entries.get(0).target().isEmpty());
    }

    // ---- the fixed primary ---------------------------------------------------

    public void testThePrimaryIsTheRegisteredActionAndAPickNeverRetargetsIt() throws Exception {
        AnAction registered = ActionManager.getInstance().getAction("Drydock.NewTask");
        assertTrue(registered instanceof NewTaskAction);
        assertEquals("New Session…", registered.getTemplatePresentation().getText());
        Path picked = Path.of("/work/other-project");
        NewSessionSplitAction split = split(registered, () -> List.of(local("other", picked)));
        AnActionEvent conflicting = event(SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject())
                .add(TaskTreePanel.SELECTED_PROJECT_ROOT, picked));

        assertSame(registered, split.getMainAction(conflicting));
        assertFalse("a pick must not become the next primary target", split.useDynamicSplitButton());
        assertEquals(ActionUpdateThread.BGT, split.getActionUpdateThread());

        Picker picker = split.openPicker(getProject(), new JPanel());
        complete();
        items(picker).get(0).doClick(0);

        assertEquals(List.of(picked), launched);
        assertSame(registered, split.getMainAction(conflicting));
        assertEquals("the template text is the registered action's", "New Session…",
                split.getTemplatePresentation().getText());
        assertEquals("the registered action's own template is untouched", "New Session…",
                registered.getTemplatePresentation().getText());
    }

    public void testTheRegisteredShortcutStaysOnTheRegisteredAction() throws Exception {
        try (InputStream descriptor = getClass().getClassLoader().getResourceAsStream("META-INF/plugin.xml")) {
            assertNotNull("the packaged plugin descriptor is visible to the platform test classloader", descriptor);
            NodeList actions = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(descriptor).getElementsByTagName("action");
            Element newTask = null;
            for (int index = 0; index < actions.getLength(); index++) {
                Element candidate = (Element) actions.item(index);
                if ("Drydock.NewTask".equals(candidate.getAttribute("id"))) {
                    newTask = candidate;
                    break;
                }
            }

            assertNotNull("plugin.xml registers Drydock.NewTask", newTask);
            assertEquals("New Session…", newTask.getAttribute("text"));
            NodeList shortcuts = newTask.getElementsByTagName("keyboard-shortcut");
            boolean registered = false;
            for (int index = 0; index < shortcuts.getLength(); index++) {
                Element shortcut = (Element) shortcuts.item(index);
                registered |= "$default".equals(shortcut.getAttribute("keymap"))
                        && "alt meta N".equals(shortcut.getAttribute("first-keystroke"));
            }
            assertTrue("Drydock.NewTask keeps the plugin.xml $default alt meta N shortcut", registered);
        }
    }

    // ---- the picker -----------------------------------------------------------

    public void testPickingALocalProjectLaunchesExactlyItsMainCheckout() throws Exception {
        Path one = Files.createTempDirectory("drydock-pick-one");
        Path two = Files.createTempDirectory("drydock-pick-two");
        SharedProject first = local("one", one);
        SharedProject second = local("two", two);
        SharedProject remote = new SharedProject(RepositoryId.newId(),
                new SshRemote("box", "/srv/x").placeholderRoot(), "boxed", ADDED,
                Optional.of(new SshRemote("box", "/srv/x")));
        NewSessionSplitAction split = split(stub(), () -> List.of(first, remote, second));

        Picker picker = split.openPicker(getProject(), new JPanel());
        complete();
        List<JMenuItem> items = items(picker);
        assertEquals(List.of("one", "boxed" + NewSessionSplitAction.REMOTE_SUFFIX, "two"), items.stream().map(JMenuItem::getText).toList());
        assertFalse(items.get(1).isEnabled());
        assertEquals(NewSessionSplitAction.REMOTE_REASON, items.get(1).getToolTipText());
        assertEquals(0, items.get(1).getActionListeners().length);
        items.get(1).doClick(0);
        items.get(2).doClick(0);

        assertEquals(List.of(second.root()), launched);
        assertEquals(List.of(getProject()), launchedFrom);
    }

    public void testTheArrowShowsLoadingAtOnceListsOffTheEdtAndFillsOnTheEdt() throws Exception {
        NewSessionSplitAction split = split(stub(), () -> {
            listingThreads.add(Thread.currentThread());
            return List.of(local("one", Path.of("/work/one")));
        });

        Picker picker = split.openPicker(getProject(), new JPanel());

        assertEquals("the popup is displayed before the listing answers", List.of(picker.menu), displayed);
        assertEquals(List.of(NewSessionSplitAction.LOADING), texts(picker));
        assertFalse(items(picker).get(0).isEnabled());
        assertTrue("nothing listed on the EDT", listingThreads.isEmpty());

        complete();

        assertEquals(1, listingThreads.size());
        assertNotSame(Thread.currentThread(), listingThreads.get(0));
        assertEquals(List.of("one"), texts(picker));
        assertEquals("the loading entry is gone", 1, items(picker).size());
    }

    public void testEachArrowInvocationReadsAFreshSnapshot() throws Exception {
        AtomicReference<List<SharedProject>> registry =
                new AtomicReference<>(List.of(local("before", Path.of("/work/before"))));
        NewSessionSplitAction split = split(stub(), registry::get);

        Picker first = split.openPicker(getProject(), new JPanel());
        complete();
        registry.set(List.of(local("after", Path.of("/work/after")), local("added", Path.of("/work/added"))));
        Picker second = split.openPicker(getProject(), new JPanel());
        complete();

        assertEquals(List.of("before"), texts(first));
        assertEquals(List.of("after", "added"), texts(second));
    }

    public void testADismissedPopupIsNeitherFilledNorReopened() throws Exception {
        NewSessionSplitAction split = split(stub(), () -> List.of(local("one", Path.of("/work/one"))));

        Picker picker = split.openPicker(getProject(), new JPanel());
        for (PopupMenuListener listener : picker.menu.getPopupMenuListeners()) {
            listener.popupMenuWillBecomeInvisible(new PopupMenuEvent(picker.menu));
        }
        complete();

        assertFalse(picker.live());
        assertEquals(List.of(NewSessionSplitAction.LOADING), texts(picker));
        assertFalse(picker.menu.isVisible());
        assertEquals("displayed once, never again", 1, displayed.size());
    }

    public void testAClosedProjectNeitherFillsThePopupNorLaunches() throws Exception {
        AtomicBoolean disposed = new AtomicBoolean();
        Project project = project(disposed);
        NewSessionSplitAction split = split(stub(), () -> List.of(local("one", Path.of("/work/one"))));

        Picker open = split.openPicker(project, new JPanel());
        complete();
        disposed.set(true);
        items(open).get(0).doClick(0);
        assertTrue("a pick after the window closed launches nothing", launched.isEmpty());

        Picker late = split.openPicker(project, new JPanel());
        complete();
        assertEquals(List.of(NewSessionSplitAction.LOADING), texts(late));
    }

    public void testAListingFailureReplacesLoadingWithAnError() throws Exception {
        NewSessionSplitAction split = split(stub(), () -> {
            throw new IOException("registry unreadable");
        });

        Picker picker = split.openPicker(getProject(), new JPanel());
        complete();

        List<JMenuItem> items = items(picker);
        assertEquals(List.of(NewSessionSplitAction.FAILED), texts(picker));
        assertFalse(items.get(0).isEnabled());
        assertEquals("registry unreadable", items.get(0).getToolTipText());
    }

    public void testTheArrowNeedsAProjectAndShowsItsOwnPopupUnderTheButton() {
        NewSessionSplitAction split = split(stub(), List::of);
        JPanel button = new JPanel();

        assertTrue("no fallback group without a project",
                split.showPopup(event(SimpleDataContext.builder())));
        assertTrue(displayed.isEmpty());

        MouseEvent press = new MouseEvent(button, MouseEvent.MOUSE_PRESSED, 0, 0, 1, 1, 1, false);
        AnActionEvent arrow = AnActionEvent.createEvent(SimpleDataContext.getProjectContext(getProject()),
                new Presentation(), ActionPlaces.TOOLBAR, ActionUiKind.TOOLBAR, press);
        assertTrue("the platform's fallback group popup is suppressed", split.showPopup(arrow));
        assertEquals(1, displayed.size());
        assertEquals(List.of(NewSessionSplitAction.LOADING), texts(displayed.get(0)));
    }

    public void testTheDefaultListingIsTheSharedServiceSnapshot() throws Exception {
        List<SharedProject> listed = offEdt(NewSessionSplitAction::currentProjects);

        assertEquals(DrydockPluginService.getInstance().sharedProjects().projects(), listed);
    }

    // ---- helpers --------------------------------------------------------------

    private NewSessionSplitAction split(AnAction primary, Callable<List<SharedProject>> listing) {
        return new NewSessionSplitAction(primary, listing, queued::add,
                (from, root) -> {
                    assertTrue(ApplicationManager.getApplication().isDispatchThread());
                    launchedFrom.add(from);
                    launched.add(root);
                },
                (menu, anchor) -> {
                    assertTrue(ApplicationManager.getApplication().isDispatchThread());
                    displayed.add(menu);
                });
    }

    /** Runs every queued listing on its own thread, then the EDT hops it posted. */
    private void complete() throws InterruptedException {
        List<Runnable> work = new ArrayList<>(queued);
        queued.clear();
        for (Runnable runnable : work) {
            Thread thread = new Thread(runnable, "listing");
            thread.start();
            thread.join(TimeUnit.SECONDS.toMillis(30));
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
    }

    private static List<JMenuItem> items(Picker picker) {
        return items(picker.menu);
    }

    private static List<JMenuItem> items(JPopupMenu menu) {
        List<JMenuItem> items = new ArrayList<>();
        for (Component component : menu.getComponents()) {
            items.add((JMenuItem) component);
        }
        return items;
    }

    private static List<String> texts(Picker picker) {
        return texts(picker.menu);
    }

    private static List<String> texts(JPopupMenu menu) {
        return items(menu).stream().map(JMenuItem::getText).toList();
    }

    private static SharedProject local(String name, Path root) {
        return SharedProject.local(RepositoryId.newId(), root, name, ADDED);
    }

    private static AnAction stub() {
        return new NewTaskAction();
    }

    /** A project whose only behaviour is {@code isDisposed()}: the picker reads nothing else. */
    private static Project project(AtomicBoolean disposed) {
        return (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[]{Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isDisposed" -> disposed.get();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "closing project";
                    default -> method.getReturnType() == boolean.class ? false : null;
                });
    }

    private static AnActionEvent event(SimpleDataContext.Builder data) {
        return AnActionEvent.createEvent(data.build(), new Presentation(), ActionPlaces.TOOLBAR,
                ActionUiKind.TOOLBAR, null);
    }

    private static <T> T offEdt(Callable<T> body) throws Exception {
        return ApplicationManager.getApplication().executeOnPooledThread(() -> {
            assertFalse(ApplicationManager.getApplication().isDispatchThread());
            return body.call();
        }).get(30, TimeUnit.SECONDS);
    }
}
