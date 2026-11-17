package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.IsolatedCatalog;
import app.drydock.intellij.tasks.BackgroundPopupDispatcher.Hit;
import app.drydock.intellij.tasks.InboxList.Header;
import app.drydock.intellij.tasks.InboxList.More;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.projects.SharedProject;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link BackgroundPopupDispatcher} in a booted IDE: occupied versus blank
 * geometry, viewport routing, one popup per gesture, keyboard row popups,
 * the owning-window launch and its guards, and listener cleanup. Headless;
 * popups and the launcher are recorded through the helper's seams.
 */
public class BackgroundPopupDispatcherPlatformTest extends BasePlatformTestCase {

    private static final String PLACE = "Drydock.TaskTree.Popup";
    private static final Instant NOW = Instant.parse("2026-11-13T15:00:00Z");

    private record Shown(ActionGroup group, JComponent target, Component invoker, int x, int y) {
    }

    private final List<Shown> shown = new ArrayList<>();
    private final List<Project> launched = new ArrayList<>();
    private final DefaultActionGroup rowGroup = new DefaultActionGroup(DumbAwareAction.create("Row", e -> { }));
    private final Project owning = project(false, "/repos/alpha");
    private JTree tree;
    private JList<String> list;

    @Override
    protected void setUp() throws Exception {
        IsolatedCatalog.assertSandboxed();
        super.setUp();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
        for (String name : List.of("a", "b", "c")) {
            root.add(new DefaultMutableTreeNode(name));
        }
        tree = new JTree(new DefaultTreeModel(root));
        tree.setRowHeight(20);
        tree.setSize(300, 400);
        list = new JList<>(new DefaultListModel<>());
        ((DefaultListModel<String>) list.getModel()).addAll(List.of("one", "two"));
        list.setFixedCellHeight(20);
        list.setSize(300, 400);
    }

    private BackgroundPopupDispatcher dispatcher(Project project) {
        BackgroundPopupDispatcher dispatcher = new BackgroundPopupDispatcher(project, rowGroup, PLACE,
                (group, place, target, invoker, x, y) -> shown.add(new Shown(group, target, invoker, x, y)),
                launched::add, BackgroundPopupDispatcher::keyboardInvoked);
        dispatcher.register(tree, null);
        dispatcher.register(list, null);
        return dispatcher;
    }

    private static MouseEvent mouse(Component on, int id, int x, int y, boolean trigger, int button, int down) {
        return new MouseEvent(on, id, System.currentTimeMillis(), down, x, y, 1, trigger, button);
    }

    private static MouseEvent rightPress(Component on, int x, int y) {
        return mouse(on, MouseEvent.MOUSE_PRESSED, x, y, true, MouseEvent.BUTTON3, MouseEvent.BUTTON3_DOWN_MASK);
    }

    private static Project project(boolean disposed, String basePath) {
        return (Project) Proxy.newProxyInstance(BackgroundPopupDispatcherPlatformTest.class.getClassLoader(),
                new Class<?>[]{Project.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isDisposed" -> disposed;
                    case "getBasePath" -> basePath;
                    default -> method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                });
    }

    private AnAction newTask(BackgroundPopupDispatcher dispatcher) {
        AnAction[] children = dispatcher.backgroundGroup().getChildren((AnActionEvent) null);
        assertEquals(1, children.length);
        return children[0];
    }

    /** Updates then performs {@code action} in a context naming another project; returns whether it was enabled. */
    private boolean invoke(AnAction action) {
        AnActionEvent event = AnActionEvent.createEvent(SimpleDataContext.getProjectContext(getProject()),
                new Presentation(), ActionPlaces.POPUP, ActionUiKind.POPUP, null);
        action.update(event);
        action.actionPerformed(event);
        return event.getPresentation().isEnabled();
    }

    public void testTreeRowsAreOccupiedAcrossTheirWidthAndSpaceBelowIsBlank() {
        assertEquals(4, tree.getRowCount());
        assertEquals(Hit.OCCUPIED, BackgroundPopupDispatcher.classify(tree, new Point(5, 5)));
        assertEquals("wide-selection row", Hit.OCCUPIED, BackgroundPopupDispatcher.classify(tree, new Point(290, 70)));
        assertEquals(3, tree.getClosestRowForLocation(10, 200));
        assertEquals(Hit.BLANK, BackgroundPopupDispatcher.classify(tree, new Point(10, 200)));
        assertEquals(Hit.BLANK, BackgroundPopupDispatcher.classify(tree, new Point(310, 5)));
    }

    public void testListNearestIndexBelowTheCellsIsNotAHit() {
        assertEquals(Hit.OCCUPIED, BackgroundPopupDispatcher.classify(list, new Point(10, 30)));
        assertEquals("nearest index is the last cell", 1, list.locationToIndex(new Point(10, 200)));
        assertEquals(Hit.BLANK, BackgroundPopupDispatcher.classify(list, new Point(10, 200)));
    }

    public void testEmptyModelsAreBlank() {
        JTree empty = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode()));
        empty.setRootVisible(false);
        empty.setSize(300, 400);
        JList<String> none = new JList<>(new DefaultListModel<>());
        none.setSize(300, 400);
        assertEquals(Hit.BLANK, BackgroundPopupDispatcher.classify(empty, new Point(5, 5)));
        assertEquals(Hit.BLANK, BackgroundPopupDispatcher.classify(none, new Point(5, 5)));
    }

    public void testInboxHeadersAndMoreTogglesAreOccupied() {
        InboxList inbox = new InboxList(new InboxList.Actions() {
            @Override
            public void activate(InboxModel.Item item) {
            }

            @Override
            public void openPullRequest(InboxModel.Item item) {
            }

            @Override
            public void pickProject(InboxModel.ProjectRef project) {
            }

            @Override
            public void togglesChanged() {
            }
        });
        inbox.setSize(600, 2_000);
        SharedProject alpha = new SharedProject(RepositoryId.newId(), Path.of("/repos/alpha").toAbsolutePath(),
                "alpha", NOW, Optional.empty());
        List<TaskNode> sessions = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ManagedAgentSession session = new ManagedAgentSession(ManagedSessionId.newId(), RepositoryId.newId(),
                    "Run " + i, AgentBinding.unlaunched(AgentKind.CLAUDE), new SessionWorkspace(
                            Path.of("/tmp", "popup-run-" + i).toAbsolutePath(), Optional.empty(), false),
                    SessionStatus.RUNNING, NOW, NOW, Optional.empty(), PrLink.none(), false, Optional.empty(), false);
            sessions.add(new TaskRow(session, Optional.of(SessionActivity.BUSY), true, Optional.empty(),
                    TaskPlacement.of(true, Optional.empty(), Optional.empty(), session.workingDirectory(),
                            Optional.empty()), false, false));
        }
        inbox.show(InboxModel.build(new InboxModel.Inputs(List.of(new ProjectRow(alpha, false, false, sessions,
                List.of(), 5)), List.of(alpha), List.of(), Map.of(), Map.of(), NOW, ZoneOffset.UTC),
                InboxModel.Query.NONE), false);
        List<InboxList.Entry> entries = inbox.entries();
        int last = entries.size() - 1;
        assertTrue(entries.getFirst() instanceof Header);
        assertTrue(entries.get(last) instanceof More);
        for (int index : new int[]{0, last}) {
            Rectangle cell = inbox.getCellBounds(index, index);
            assertEquals(Hit.OCCUPIED, BackgroundPopupDispatcher.classify(inbox,
                    new Point(cell.x + cell.width - 2, cell.y + cell.height / 2)));
        }
        Rectangle end = inbox.getCellBounds(last, last);
        assertEquals(Hit.BLANK, BackgroundPopupDispatcher.classify(inbox, new Point(10, end.y + end.height + 5)));
    }

    public void testBlankTriggerShowsOnlyNewTaskAndKeepsAConflictingSelection() {
        BackgroundPopupDispatcher dispatcher = dispatcher(owning);
        list.setSelectedIndex(0);
        tree.setSelectionRow(2);
        list.dispatchEvent(rightPress(list, 10, 200));
        tree.dispatchEvent(rightPress(tree, 10, 200));
        assertEquals(2, shown.size());
        for (Shown popup : shown) {
            assertSame(dispatcher.backgroundGroup(), popup.group());
        }
        assertEquals("New Task…", newTask(dispatcher).getTemplatePresentation().getText());
        assertEquals(0, list.getSelectedIndex());
        assertEquals(2, tree.getMinSelectionRow());
    }

    public void testOccupiedTriggerShowsOnlyTheRowGroupOnItsComponent() {
        BackgroundPopupDispatcher dispatcher = dispatcher(owning);
        list.dispatchEvent(rightPress(list, 10, 30));
        tree.dispatchEvent(rightPress(tree, 10, 30));
        assertEquals(List.of(new Shown(rowGroup, list, list, 10, 30), new Shown(rowGroup, tree, tree, 10, 30)), shown);
        assertFalse(List.of(rowGroup.getChildren((AnActionEvent) null)).contains(newTask(dispatcher)));
    }

    public void testViewportWhitespaceAndScrolledCellsRouteInContentCoordinates() {
        BackgroundPopupDispatcher dispatcher = new BackgroundPopupDispatcher(owning, rowGroup, PLACE,
                (group, place, target, invoker, x, y) -> shown.add(new Shown(group, target, invoker, x, y)),
                launched::add, BackgroundPopupDispatcher::keyboardInvoked);
        JViewport viewport = new JViewport();
        viewport.setSize(300, 100);
        DefaultListModel<String> rows = new DefaultListModel<>();
        for (int i = 0; i < 50; i++) {
            rows.addElement("row " + i);
        }
        JList<String> tall = new JList<>(rows);
        tall.setFixedCellHeight(20);
        viewport.setView(tall);
        tall.setSize(300, 1_000);
        viewport.setViewPosition(new Point(0, 400));
        dispatcher.register(tall, viewport);
        viewport.dispatchEvent(rightPress(viewport, 10, 10));
        assertSame(rowGroup, shown.getLast().group());
        assertSame(tall, shown.getLast().target());
        assertSame(viewport, shown.getLast().invoker());
        rows.removeRange(2, 49);
        tall.setSize(300, 40);
        viewport.setViewPosition(new Point(0, 0));
        viewport.dispatchEvent(rightPress(viewport, 10, 80));
        assertSame(dispatcher.backgroundGroup(), shown.getLast().group());
        assertEquals(2, shown.size());
    }

    public void testExactlyOnePopupPerGestureAcrossPhasesAndSurfaces() {
        BackgroundPopupDispatcher dispatcher = dispatcher(owning);
        JViewport viewport = new JViewport();
        viewport.setView(new JList<>());
        dispatcher.register((JComponent) viewport.getView(), viewport);
        // Press-trigger platforms: release and click repeat the trigger.
        list.dispatchEvent(rightPress(list, 10, 200));
        list.dispatchEvent(mouse(list, MouseEvent.MOUSE_RELEASED, 10, 200, true, MouseEvent.BUTTON3, 0));
        list.dispatchEvent(mouse(list, MouseEvent.MOUSE_CLICKED, 10, 200, true, MouseEvent.BUTTON3, 0));
        assertEquals(1, shown.size());
        // Release-trigger platforms.
        list.dispatchEvent(mouse(list, MouseEvent.MOUSE_PRESSED, 10, 200, false, MouseEvent.BUTTON3,
                MouseEvent.BUTTON3_DOWN_MASK));
        list.dispatchEvent(mouse(list, MouseEvent.MOUSE_RELEASED, 10, 200, true, MouseEvent.BUTTON3, 0));
        assertEquals(2, shown.size());
        // One trigger reaching content and then its viewport is shown once.
        MouseEvent once = rightPress(viewport.getView(), 10, 10);
        viewport.getView().dispatchEvent(once);
        viewport.dispatchEvent(once);
        assertEquals(3, shown.size());
    }

    public void testLeftAndMiddleClicksShowNothing() {
        dispatcher(owning);
        for (int button : new int[]{MouseEvent.BUTTON1, MouseEvent.BUTTON2}) {
            int down = button == MouseEvent.BUTTON1 ? MouseEvent.BUTTON1_DOWN_MASK : MouseEvent.BUTTON2_DOWN_MASK;
            for (int id : new int[]{MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
                list.dispatchEvent(mouse(list, id, 10, 200, false, button, id == MouseEvent.MOUSE_PRESSED ? down : 0));
                tree.dispatchEvent(mouse(tree, id, 10, 200, false, button, id == MouseEvent.MOUSE_PRESSED ? down : 0));
            }
        }
        assertEquals(List.of(), shown);
    }

    public void testKeyboardPopupStaysTheRowPopupEvenOverBlankSpace() {
        BackgroundPopupDispatcher dispatcher = dispatcher(owning);
        // As the platform's Show Context Menu action synthesizes it: no button held.
        list.dispatchEvent(mouse(list, MouseEvent.MOUSE_PRESSED, 10, 200, true, MouseEvent.BUTTON3, 0));
        tree.dispatchEvent(mouse(tree, MouseEvent.MOUSE_PRESSED, 10, 200, true, MouseEvent.BUTTON3, 0));
        list.dispatchEvent(rightPress(list, 10, 200));
        list.dispatchEvent(mouse(list, MouseEvent.MOUSE_PRESSED, 10, 200, true, MouseEvent.BUTTON3, 0));
        assertEquals(List.of(rowGroup, rowGroup, dispatcher.backgroundGroup(), rowGroup),
                shown.stream().map(Shown::group).toList());
    }

    public void testNewTaskLaunchesTheOwningWindowProjectOnly() {
        BackgroundPopupDispatcher dispatcher = dispatcher(owning);
        list.setSelectedIndex(1);
        assertTrue(invoke(newTask(dispatcher)));
        assertEquals(1, launched.size());
        assertSame(owning, launched.getFirst());
    }

    public void testMissingDisposedAndBaselessProjectsDoNotLaunch() {
        for (Project project : new Project[]{null, project(true, "/repos/alpha"), project(false, null)}) {
            assertFalse(invoke(newTask(dispatcher(project))));
        }
        assertEquals(List.of(), launched);
    }

    public void testDisposeRemovesEveryListenerAndStopsDispatch() {
        JViewport viewport = new JViewport();
        JList<String> content = new JList<>();
        int before = viewport.getMouseListeners().length + content.getMouseListeners().length
                + tree.getMouseListeners().length + list.getMouseListeners().length;
        BackgroundPopupDispatcher dispatcher = dispatcher(owning);
        dispatcher.register(content, viewport);
        MouseListener retained = list.getMouseListeners()[list.getMouseListeners().length - 1];
        AnAction action = newTask(dispatcher);
        assertEquals(before + 4, viewport.getMouseListeners().length + content.getMouseListeners().length
                + tree.getMouseListeners().length + list.getMouseListeners().length);
        dispatcher.dispose();
        assertEquals(before, viewport.getMouseListeners().length + content.getMouseListeners().length
                + tree.getMouseListeners().length + list.getMouseListeners().length);
        retained.mousePressed(rightPress(list, 10, 200));
        list.dispatchEvent(rightPress(list, 10, 200));
        assertFalse(invoke(action));
        assertEquals(List.of(), shown);
        assertEquals(List.of(), launched);
        assertThrows(IllegalStateException.class, () -> dispatcher.register(list, null));
    }

    public void testOnlyTreesAndListsCanBeRegistered() {
        BackgroundPopupDispatcher dispatcher = new BackgroundPopupDispatcher(owning, rowGroup, PLACE);
        assertThrows(IllegalArgumentException.class, () -> dispatcher.register(new JViewport(), null));
        dispatcher.dispose();
    }
}
