package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.util.ui.UIUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Hold, Close held and Previous through the REAL opened host editor (the provider's own
 * {@link SessionHostEditor} over the project controller): toolbar membership, enablement and its
 * explanations, no default shortcut, and that Previous of a stopped session launches nothing.
 */
public class SessionHostActionsPlatformTest extends HeavyPlatformTestCase {

    private static final String HOLD = "Drydock.SessionHost.Hold";
    private static final String CLOSE = "Drydock.SessionHost.CloseHeld";
    private static final String PREVIOUS = "Drydock.SessionHost.Previous";

    private final ManagedSessionId a = ManagedSessionId.newId();
    private final ManagedSessionId b = ManagedSessionId.newId();
    private SessionHostController controller;
    private SessionHostFile hostFile;
    private SessionHostEditor editor;
    private Predicate<ManagedSessionId> productionCatalogMissing;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        productionCatalogMissing = SessionHostActions.catalogMissing;
        controller = SessionHostController.getInstance(getProject());
        hostFile = AgentConsoleFileSystem.getInstance().sessionHostFile();
        hostFile.putUserData(FileEditorProvider.KEY, new AgentConsoleEditorProvider());
        FileEditor[] opened = FileEditorManager.getInstance(getProject()).openFile(hostFile, true);
        assertEquals(1, opened.length);
        editor = (SessionHostEditor) opened[0];
        assertTrue(editor.leased());
        SessionHostActions.catalogMissing = id -> false;
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            SessionHostActions.catalogMissing = productionCatalogMissing;
            FileEditorManager.getInstance(getProject()).closeFile(hostFile);
            Disposer.dispose(editor);
            hostFile.putUserData(FileEditorProvider.KEY, null);
        } finally {
            super.tearDown();
        }
    }

    private Presentation update(String id) {
        AnAction action = ActionManager.getInstance().getAction(id);
        AnActionEvent event = TestActionEvent.createTestEvent(action, SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject()).build());
        action.update(event);
        return event.getPresentation();
    }

    private void click(String id) {
        AnAction action = ActionManager.getInstance().getAction(id);
        action.actionPerformed(TestActionEvent.createTestEvent(action, SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject()).build()));
    }

    public void testToolbarOfTheOpenedHostContainsTheThreeActionsAndTheyAreFindActionEntries() {
        ActionToolbar toolbar = UIUtil.findComponentOfType(editor.getComponent(), ActionToolbarImpl.class);
        assertNotNull("the opened editor hosts the group's toolbar", toolbar);
        toolbar.updateActionsImmediately();
        // The toolbar fills asynchronously; getActions() is empty until the update lands.
        PlatformTestUtil.waitWithEventsDispatching("the host toolbar is populated",
                () -> !toolbar.getActions().isEmpty(), 30);
        List<AnAction> children = toolbar.getActions();
        for (String id : List.of(HOLD, CLOSE, PREVIOUS)) {
            AnAction action = ActionManager.getInstance().getAction(id);
            assertTrue(id + " is in the host toolbar group", children.contains(action));
            assertNotNull(action.getTemplatePresentation().getText());
        }
    }

    public void testNoActionHasADefaultShortcut() {
        for (String id : List.of(HOLD, CLOSE, PREVIOUS)) {
            assertEquals(id + " has no shortcut in the active keymap", 0,
                    KeymapManager.getInstance().getActiveKeymap().getShortcuts(id).length);
        }
    }

    public void testEnablementAndExplanationsFollowTheHostLayout() {
        assertFalse(update(HOLD).isEnabled());
        assertEquals("No session to hold", update(HOLD).getDescription());
        assertFalse(update(CLOSE).isEnabled());
        assertEquals("No session is held", update(CLOSE).getDescription());
        assertFalse(update(PREVIOUS).isEnabled());
        assertEquals("No previous session", update(PREVIOUS).getDescription());

        controller.show(a);
        editor.refreshLayout();
        assertTrue(update(HOLD).isEnabled());
        click(HOLD);
        assertEquals(Optional.of(a), controller.layout().held());
        assertSame("editor re-mounted", editor.heldSlot(), controller.paneFor(a).root().getParent());

        Presentation hold = update(HOLD);
        assertFalse(hold.isEnabled());
        assertEquals("Another session is held; close it first", hold.getDescription());
        assertTrue(update(CLOSE).isEnabled());
        controller.show(b);
        editor.refreshLayout();
        assertFalse("hold stays disabled while occupied", update(HOLD).isEnabled());
        click(CLOSE);
        assertTrue(controller.layout().held().isEmpty());
        assertNull("the held root is detached", controller.paneFor(a).root().getParent());
        assertTrue(update(HOLD).isEnabled() && !update(CLOSE).isEnabled());
    }

    public void testPreviousShowsTheStoppedSessionAndLaunchesNothing() {
        List<String> launches = new ArrayList<>();
        var connection = ApplicationManager.getApplication().getMessageBus().connect(getTestRootDisposable());
        connection.subscribe(AgentExecutionListener.TOPIC, new AgentExecutionListener() {
            @Override
            public void executionStarting(ManagedSessionId id, boolean starting) {
                launches.add("starting " + id);
            }
            @Override
            public void executionStarted(ManagedSessionId id) {
                launches.add("started " + id);
            }
        });
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        controller.show(a);
        controller.show(b);
        editor.refreshLayout();
        assertTrue("a and b are stopped", registry.execution(a).isEmpty() && registry.execution(b).isEmpty());
        assertTrue(update(PREVIOUS).isEnabled());
        click(PREVIOUS);
        assertEquals(Optional.of(a), controller.layout().primary());
        assertSame(editor.primarySlot(), controller.paneFor(a).root().getParent());
        assertTrue("no launch, resume or start: " + launches, launches.isEmpty());
        assertTrue(registry.execution(a).isEmpty() && !registry.isStarting(a) && !registry.isRunning(a));
    }

    public void testUnavailablePreviousIsExplainedNotOffered() {
        controller.show(a);
        controller.show(b);
        SessionHostActions.catalogMissing = id -> id.equals(a);
        Presentation missing = update(PREVIOUS);
        assertFalse(missing.isEnabled());
        assertEquals("The previous session no longer exists", missing.getDescription());
        click(PREVIOUS);
        assertEquals("a disabled action changes nothing", Optional.of(b), controller.layout().primary());

        SessionHostActions.catalogMissing = id -> false;
        AgentExecutionRegistry.getInstance().markMoving(a, true);
        try {
            assertFalse(update(PREVIOUS).isEnabled());
            assertEquals("The previous session is being moved to another window",
                    update(PREVIOUS).getDescription());
        } finally {
            AgentExecutionRegistry.getInstance().markMoving(a, false);
        }
        assertTrue(update(PREVIOUS).isEnabled());
    }
}
