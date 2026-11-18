package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.IsolatedCatalog;
import app.drydock.intellij.agent.AgentConsoleEditorProvider;
import app.drydock.intellij.agent.AgentConsoleFileSystem;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.util.ui.UIUtil;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * All Sessions… over a twenty-session catalog: every session listed with no query (stopped,
 * collapsed-Idle, row five and beyond, duplicate names), reachable from the opened host's toolbar
 * and the tool window title, and the inbox's filter and multi-selection untouched.
 */
public class AllSessionsActionPlatformTest extends HeavyPlatformTestCase {

    private static final String ID = "Drydock.AllSessions";
    private static final int COUNT = 20;

    private final List<ManagedSessionId> seeded = new ArrayList<>();
    private Supplier<List<ManagedAgentSession>> productionCatalog;
    private Supplier<SessionActivation> productionActivation;
    private BiConsumer<Project, List<AllSessionsAction.Row>> productionPresenter;
    private final List<List<AllSessionsAction.Row>> shown = new ArrayList<>();
    private final List<ManagedSessionId> activated = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        IsolatedCatalog.assertSandboxed();
        super.setUp();
        productionCatalog = AllSessionsAction.catalog;
        productionActivation = AllSessionsAction.activation;
        productionPresenter = AllSessionsAction.presenter;
        Instant base = Instant.parse("2026-11-13T15:00:00Z");
        for (int i = 0; i < COUNT; i++) {
            // Sessions 0 and 1 share a name; session 2 differs only by case.
            String name = i < 2 ? "Same name" : i == 2 ? "same NAME" : "Session " + i;
            ManagedSessionId id = ManagedSessionId.newId();
            seeded.add(id);
            IsolatedCatalog.seed(new ManagedAgentSession(id, RepositoryId.newId(), name,
                    AgentBinding.unlaunched(AgentKind.CLAUDE),
                    new SessionWorkspace(Path.of("/tmp/all-sessions-" + i).toAbsolutePath(),
                            Optional.of(Path.of("/tmp/wt-" + i).toAbsolutePath()), false),
                    SessionStatus.IDLE, base, base.minusSeconds(i), Optional.empty(), PrLink.none(), false,
                    Optional.empty(), false));
        }
        SessionActivation fake = new SessionActivation(new SessionActivation.Seams(Runnable::run, Runnable::run,
                () -> AllSessionsAction.catalog.get(), id -> false, s -> false, s -> { },
                (project, session) -> activated.add(session.id()), (project, message) -> { }));
        AllSessionsAction.activation = () -> fake;
        AllSessionsAction.presenter = (project, rows) -> shown.add(rows);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            AllSessionsAction.catalog = productionCatalog;
            AllSessionsAction.activation = productionActivation;
            AllSessionsAction.presenter = productionPresenter;
            IsolatedCatalog.remove(seeded.toArray(ManagedSessionId[]::new));
        } finally {
            super.tearDown();
        }
    }

    private AnActionEvent event(AnAction action) {
        return TestActionEvent.createTestEvent(action, SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject()).build());
    }

    public void testEveryCatalogSessionIsListedWithNoQueryAndDuplicatesCarryTheirFullId() {
        AnAction action = ActionManager.getInstance().getAction(ID);
        action.actionPerformed(event(action));
        assertEquals(1, shown.size());
        List<AllSessionsAction.Row> rows = shown.get(0);
        assertEquals("all twenty, none cut at four, none hidden as Idle", COUNT, rows.size());
        assertEquals(Set.copyOf(seeded), rows.stream().map(AllSessionsAction.Row::id).collect(Collectors.toSet()));
        assertEquals("most recently opened first", seeded.get(0), rows.get(0).id());
        for (AllSessionsAction.Row row : rows) {
            assertEquals("IDLE", row.status());
            assertEquals("Start or Resume", row.intent());
            assertTrue(row.worktree().startsWith("wt-"));
            boolean duplicate = row.title().equalsIgnoreCase("Same name");
            assertEquals(row.title(), duplicate, row.disambiguate());
            assertEquals(duplicate, row.text().contains(row.id().toString()));
            assertTrue("search matches a pasted id", row.searchText().contains(row.id().toString()));
        }
    }

    public void testChoosingARowActivatesItsIdAndShowingActivatesNothing() {
        AnAction action = ActionManager.getInstance().getAction(ID);
        action.actionPerformed(event(action));
        assertTrue("a highlight is not a commit: nothing activated by showing", activated.isEmpty());
        AllSessionsAction.Row fifth = shown.get(0).get(4);
        AllSessionsAction.activate(getProject(), fifth);
        assertEquals(List.of(fifth.id()), activated);
    }

    public void testNoDefaultShortcutAndActionIsRegistered() {
        assertNotNull(ActionManager.getInstance().getAction(ID));
        assertEquals(0, KeymapManager.getInstance().getActiveKeymap().getShortcuts(ID).length);
    }

    public void testReachableFromTheOpenedHostToolbar() {
        var file = AgentConsoleFileSystem.getInstance().sessionHostFile();
        file.putUserData(FileEditorProvider.KEY, new AgentConsoleEditorProvider());
        FileEditor[] opened = FileEditorManager.getInstance(getProject()).openFile(file, true);
        try {
            assertEquals(1, opened.length);
            ActionToolbar toolbar = UIUtil.findComponentOfType(opened[0].getComponent(), ActionToolbarImpl.class);
            assertNotNull(toolbar);
            toolbar.updateActionsImmediately();
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertTrue(toolbar.getActions().contains(ActionManager.getInstance().getAction(ID)));
        } finally {
            FileEditorManager.getInstance(getProject()).closeFile(file);
            Disposer.dispose(opened[0]);
            file.putUserData(FileEditorProvider.KEY, null);
        }
    }

    public void testToolWindowTitleCarriesTheAction() {
        ContentManager contents = ContentFactory.getInstance().createContentManager(false, getProject());
        Disposer.register(getTestRootDisposable(), contents);
        List<AnAction> titleActions = new ArrayList<>();
        ToolWindow window = (ToolWindow) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ToolWindow.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getContentManager" -> contents;
                    case "setTitleActions" -> {
                        titleActions.addAll((List<AnAction>) args[0]);
                        yield null;
                    }
                    default -> method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                });
        new DrydockToolWindowFactory().createToolWindowContent(getProject(), window);
        assertEquals(List.of(ActionManager.getInstance().getAction(ID)), titleActions);
        assertEquals(1, contents.getContentCount());
    }

    public void testInboxFilterAndMultiSelectionAreUntouched() {
        TaskTreePanel panel = new TaskTreePanel(getProject());
        Disposer.register(getTestRootDisposable(), panel);
        panel.search().setText("Session 5");
        InboxList inbox = panel.inbox();
        List<InboxModel.Item> selectionBefore = inbox.selectedItems();
        String textBefore = panel.search().getText();
        Optional<InboxModel.ProjectRef> chipBefore = panel.search().project();

        AnAction action = ActionManager.getInstance().getAction(ID);
        action.actionPerformed(event(action));

        assertEquals("the picker lists everything while the inbox is filtered", COUNT, shown.get(0).size());
        assertEquals(textBefore, panel.search().getText());
        assertEquals(chipBefore, panel.search().project());
        assertEquals(selectionBefore, inbox.selectedItems());
    }
}
