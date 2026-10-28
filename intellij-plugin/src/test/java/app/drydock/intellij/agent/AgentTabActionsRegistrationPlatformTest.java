package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.ide.DataManager;
import com.intellij.ide.impl.HeadlessDataManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPanel;

/**
 * The registration half of the agent-tab actions (A7, A9): the XML ids
 * resolve to the shared action classes with an EDT update thread, the tab
 * header's group carries exactly the three header shortcuts in order, and
 * the real {@code update()} hides every command where it does not apply.
 *
 * <p>Everything here is a headless platform check of registration and the
 * real resolve path — no catalog is written, no action is performed, no
 * agent is launched. The rendering of the shortcuts on a real tab strip is
 * visual verification and is claimed nowhere in this class.</p>
 */
public class AgentTabActionsRegistrationPlatformTest extends BasePlatformTestCase {

    private static final String[] ACTION_IDS = {
            "Drydock.AgentTab.OwnProject", "Drydock.AgentTab.Changes", "Drydock.AgentTab.Lifecycle",
            "Drydock.AgentTab.Transfer", "Drydock.AgentTab.Rename", "Drydock.AgentTab.Done",
    };

    public void testA_TheSixIdsResolveToTheSharedClassesOnTheEdt() {
        ActionManager actions = ActionManager.getInstance();
        for (String id : ACTION_IDS) {
            AnAction action = actions.getAction(id);
            assertNotNull(id + " must be registered", action);
            assertEquals(id + " must resolve to the shared AgentTabActions class",
                    expectedClass(id), action.getClass());
            assertEquals(id + " must update on the EDT (it reads per-editor facts and the "
                    + "presentation on the tab's own thread)", ActionUpdateThread.EDT,
                    action.getActionUpdateThread());
        }
    }

    public void testB_TheHeaderGroupCarriesExactlyTheThreeShortcutsInOrder() {
        ActionManager actions = ActionManager.getInstance();
        assertTrue("EditorTabActionGroup must be registered as the header seam",
                actions.getAction("EditorTabActionGroup") instanceof DefaultActionGroup);
        DefaultActionGroup shortcuts = assertGroup(actions, "Drydock.AgentTabShortcuts");
        boolean inTabGroup = false;
        for (AnAction child : ((DefaultActionGroup) actions.getAction("EditorTabActionGroup"))
                .getChildren(groupEvent())) {
            inTabGroup |= child == shortcuts;
        }
        assertTrue("EditorTabActionGroup must contain Drydock.AgentTabShortcuts "
                + "(the header always-visible shortcuts, A7)", inTabGroup);

        AnAction[] children = shortcuts.getChildren(groupEvent());
        assertEquals("the header group carries exactly three shortcuts", 3, children.length);
        assertSame("first: own project window", AgentTabActions.OwnProject.class, children[0].getClass());
        assertSame("second: the lifecycle", AgentTabActions.Lifecycle.class, children[1].getClass());
        assertSame("third: Done", AgentTabActions.Done.class, children[2].getClass());
        // The references resolve to the registered singletons, so a header
        // click reaches the same instance the action id resolves to.
        assertSame(actions.getAction("Drydock.AgentTab.OwnProject"), children[0]);
        assertSame(actions.getAction("Drydock.AgentTab.Lifecycle"), children[1]);
        assertSame(actions.getAction("Drydock.AgentTab.Done"), children[2]);
    }

    public void testC_RealUpdateHidesEveryCommandOnAnOrdinaryFile() {
        assertInvisibleEverywhere(new LightVirtualFile("A.java"), ActionPlaces.EDITOR_TAB);
        assertInvisibleEverywhere(new LightVirtualFile("A.java"), ActionPlaces.EDITOR_TAB_POPUP);
    }

    public void testD_RealUpdateHidesEveryCommandOutsideTheCatalog() {
        // A real console file whose session id is not in the loaded catalog:
        // the tab has not been shown by any window, so there is no target.
        AgentConsoleFile unknown = AgentConsoleFileSystem.getInstance().fileFor(ManagedSessionId.newId());
        assertInvisibleEverywhere(unknown, ActionPlaces.EDITOR_TAB);
        assertInvisibleEverywhere(unknown, ActionPlaces.EDITOR_TAB_POPUP);
    }

    public void testE_RealUpdateHidesEveryCommandOutsideTheTwoTabPlaces() {
        // The shared actions must not leak into other surfaces: a console
        // file offered in the editor's own popup belongs to T15's narrower
        // contribution, and every other place is not a tab command at all.
        AgentConsoleFile file = AgentConsoleFileSystem.getInstance().fileFor(ManagedSessionId.newId());
        assertInvisibleEverywhere(file, ActionPlaces.EDITOR_POPUP);
        assertInvisibleEverywhere(file, ActionPlaces.ACTION_SEARCH);
    }

    /** Every real action's real update(), applied to this file and place. */
    private void assertInvisibleEverywhere(VirtualFile file, String place) {
        ActionManager actions = ActionManager.getInstance();
        for (String id : ACTION_IDS) {
            AnAction action = actions.getAction(id);
            assertNotNull(id + " must be registered", action);
            Presentation presentation = new Presentation();
            action.update(AnActionEvent.createEvent(
                    DataManager.getInstance().getDataContext(new TabPanel(getProject(), file)),
                    presentation, place, ActionUiKind.NONE, null));
            assertFalse(id + " must be invisible for " + file.getName() + " at " + place,
                    presentation.isVisible());
        }
    }

    private static Class<?> expectedClass(String id) {
        return switch (id.substring("Drydock.AgentTab.".length())) {
            case "OwnProject" -> AgentTabActions.OwnProject.class;
            case "Changes" -> AgentTabActions.Changes.class;
            case "Lifecycle" -> AgentTabActions.Lifecycle.class;
            case "Transfer" -> AgentTabActions.Transfer.class;
            case "Rename" -> AgentTabActions.Rename.class;
            case "Done" -> AgentTabActions.Done.class;
            default -> throw new IllegalArgumentException(id);
        };
    }

    private static DefaultActionGroup assertGroup(ActionManager actions, String id) {
        AnAction group = actions.getAction(id);
        assertNotNull(id + " must be registered", group);
        assertTrue(id + " must be a DefaultActionGroup", group instanceof DefaultActionGroup);
        return (DefaultActionGroup) group;
    }

    /** An event to enumerate children with: the platform's own group path. */
    private static AnActionEvent groupEvent() {
        return AnActionEvent.createEvent(DataContext.EMPTY_CONTEXT, new Presentation(),
                ActionPlaces.EDITOR_TAB, ActionUiKind.NONE, null);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // The unit-test sandbox runs with HeadlessDataManager, whose contexts
        // skip the component-hierarchy provider walk a real IDE performs;
        // the update() probes below resolve their target through that walk,
        // so switch it back on for the lifetime of this test (the SDK's own
        // sanctioned hook, as EditorTabLabelActionsRuntimeTest does).
        HeadlessDataManager.fallbackToProductionDataManager(getTestRootDisposable());
    }

    /**
     * The clicked tab's own context, like the tab components it stands in
     * for: a component sinking its file and the project it lives in, read
     * through the production data walk.
     */
    private static final class TabPanel extends JPanel implements UiDataProvider {

        private final Project project;
        private final VirtualFile file;

        TabPanel(Project project, VirtualFile file) {
            this.project = project;
            this.file = file;
        }

        @Override
        public void uiDataSnapshot(@NotNull DataSink sink) {
            sink.set(CommonDataKeys.VIRTUAL_FILE, file);
            sink.set(CommonDataKeys.PROJECT, project);
        }
    }
}
