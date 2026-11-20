package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.ide.DataManager;
import com.intellij.ide.impl.HeadlessDataManager;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.InplaceButton;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.ui.tabs.impl.JBEditorTabs;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;

/**
 * A headless probe of the editor-tab label action machinery on this SDK
 * (263.7031.23), mirroring {@code EditorTabbedContainerKt.createTabInfo}:
 * per-{@link TabInfo} label actions flattened into one icon button per EDT
 * action, and the button's event resolving {@code VIRTUAL_FILE} from the tab
 * the button lives in — never from whichever editor is selected.
 *
 * <p>Everything here is <em>headless SDK mechanics</em>, not visual evidence:
 * component visibility is asserted with Swing's {@code isVisible()}, which is
 * what {@code ActionButton.update()} drives. Nothing is rendered on screen,
 * so this test says nothing about paint, hover or themes; that verification
 * belongs to the visual stage.</p>
 *
 * <p>This is the only source that touches {@code com.intellij.ui.tabs.impl}.</p>
 */
public class EditorTabLabelActionsRuntimeTest extends BasePlatformTestCase {

    /** One distinct icon per test action, so a label's buttons can be told apart. */
    private static final class TestIcon implements Icon {
        private final int width;

        TestIcon(int width) {
            this.width = width;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            // never painted: headless probe
        }

        @Override
        public int getIconWidth() {
            return width;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    }

    private static final Icon RECORDER_ICON = new TestIcon(16);
    private static final Icon DISABLED_ICON = new TestIcon(17);
    private static final Icon INVISIBLE_ICON = new TestIcon(18);

    /** Records exactly what a clicked tab-label action is told it acts on. */
    private static final class Recorder extends AnAction {
        VirtualFile file;
        String place;
        Project project;

        Recorder() {
            getTemplatePresentation().setIcon(RECORDER_ICON);
            getTemplatePresentation().setText("Recorder");
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            // The production shape: visible only where the clicked tab's own
            // file is an agent console.
            VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
            e.getPresentation().setVisible(file instanceof AgentConsoleFile);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            file = e.getData(CommonDataKeys.VIRTUAL_FILE);
            place = e.getPlace();
            project = e.getProject();
        }
    }

    private static final class DisabledAction extends AnAction {
        DisabledAction() {
            getTemplatePresentation().setIcon(DISABLED_ICON);
            getTemplatePresentation().setText("Disabled");
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setVisible(true);
            e.getPresentation().setEnabled(false);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            fail("a disabled action must never be performed");
        }
    }

    private static final class InvisibleAction extends AnAction {
        InvisibleAction() {
            getTemplatePresentation().setIcon(INVISIBLE_ICON);
            getTemplatePresentation().setText("Invisible");
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setVisible(false);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            fail("an invisible action must never be performed");
        }
    }

    /** A panel that sinks exactly one data value, like the tab components it stands in for. */
    private static final class DataSinkPanel extends JPanel implements UiDataProvider {
        private final VirtualFile file;
        private final Project project;

        DataSinkPanel(VirtualFile file, Project project) {
            super(new BorderLayout());
            this.file = file;
            this.project = project;
        }

        @Override
        public void uiDataSnapshot(@NotNull DataSink sink) {
            if (file != null) {
                sink.set(CommonDataKeys.VIRTUAL_FILE, file);
            }
            if (project != null) {
                sink.set(CommonDataKeys.PROJECT, project);
            }
        }
    }

    private JBEditorTabs tabs;
    private AgentConsoleFile agentFile;
    private TabInfo agentInfo;
    private TabInfo ordinaryInfo;
    private Recorder recorder;
    private JFrame frame;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Project project = getProject();

        // The unit-test sandbox runs with HeadlessDataManager, whose contexts
        // skip the component-hierarchy provider walk that a real IDE performs;
        // this probe is about the real IDE path, so switch it back on for the
        // lifetime of the test root disposable (the SDK's own sanctioned hook).
        HeadlessDataManager.fallbackToProductionDataManager(getTestRootDisposable());

        // The stock editor tabs: autoHide off, exactly as EditorTabbedContainer
        // sets it, so label actions are not hover-gated by the platform.
        tabs = new JBEditorTabs(project, getTestRootDisposable());
        tabs.getPresentation().setTabLabelActionsAutoHide(false);

        recorder = new Recorder();
        DefaultActionGroup testGroup =
                new DefaultActionGroup(recorder, new DisabledAction(), new InvisibleAction());

        agentFile = AgentConsoleFileSystem.getInstance().fileFor(ManagedSessionId.newId());
        VirtualFile ordinaryFile = new LightVirtualFile("A.java");
        agentInfo = tabInfo(agentFile, "Agent", testGroup);
        ordinaryInfo = tabInfo(ordinaryFile, "A.java", testGroup);
        tabs.addTab(agentInfo);
        tabs.addTab(ordinaryInfo);

        // A parent sink for PROJECT, mirroring how the tab content sits inside
        // the project frame; the button data context resolves through it.
        JPanel projectSink = new DataSinkPanel(null, project);
        projectSink.add(tabs, BorderLayout.CENTER);

        // ActionPanel.update() bails out unless a JRootPane is reachable, so
        // the hierarchy needs one. A real frame when a display exists, a
        // detached root pane when the JVM is headless.
        if (GraphicsEnvironment.isHeadless()) {
            JRootPane rootPane = new JRootPane();
            rootPane.getContentPane().add(projectSink, BorderLayout.CENTER);
        } else {
            frame = new JFrame();
            frame.setContentPane(projectSink);
            frame.pack();
        }

        // The clicked-tab precondition: an ordinary file tab is selected, the
        // agent console tab is not.
        tabs.select(ordinaryInfo, true);
        tabs.updateTabActions(true);
    }

    @Override
    protected void tearDown() throws Exception {
        if (frame != null) {
            frame.dispose();
            frame = null;
        }
        super.tearDown();
    }

    /** createTabInfo's shape: component sinking VIRTUAL_FILE, object = file, label actions. */
    private static TabInfo tabInfo(VirtualFile file, String text, DefaultActionGroup testGroup) {
        JPanel component = new DataSinkPanel(file, null);
        TabInfo info = new TabInfo(component).setText(text).setObject(file);
        info.setTabLabelActions(new DefaultActionGroup(testGroup), ActionPlaces.EDITOR_TAB);
        return info;
    }

    private List<InplaceButton> labelButtons(TabInfo info) {
        return new ArrayList<>(UIUtil.findComponentsOfType(tabs.getTabLabel(info), InplaceButton.class));
    }

    /** The button rendered for the action with this icon, or null if there is none. */
    private static InplaceButton buttonFor(List<InplaceButton> buttons, Icon icon) {
        for (InplaceButton button : buttons) {
            Icon actual = button.getIcon();
            if (actual == icon || (actual != null && actual.getIconWidth() == icon.getIconWidth()
                    && actual.getIconHeight() == icon.getIconHeight())) {
                return button;
            }
        }
        return null;
    }

    private void assertOrdinaryTabSelected() {
        assertSame("precondition: the ordinary tab must be the selected one",
                ordinaryInfo, tabs.getSelectedInfo());
    }

    public void testA_RecorderButtonIsVisibleOnTheUnselectedAgentTab() {
        assertOrdinaryTabSelected();
        InplaceButton recorderButton = buttonFor(labelButtons(agentInfo), RECORDER_ICON);
        assertNotNull("the recorder action must render a button on the agent tab label", recorderButton);
        assertTrue("the recorder button must be visible on the UNSELECTED agent tab "
                + "(autoHide off means label actions are not hover-gated)",
                recorderButton.isVisible());
    }

    public void testB_NoVisibleRecorderOnTheOrdinaryTab() {
        assertOrdinaryTabSelected();
        InplaceButton recorderButton = buttonFor(labelButtons(ordinaryInfo), RECORDER_ICON);
        assertTrue("the recorder must not be visible on an ordinary file tab "
                        + "(a button may exist but must be hidden)",
                recorderButton == null || !recorderButton.isVisible());
    }

    public void testC_ClickingTheUnselectedAgentTabButtonTargetsThatTab() {
        assertOrdinaryTabSelected();
        InplaceButton recorderButton = buttonFor(labelButtons(agentInfo), RECORDER_ICON);
        assertNotNull("the recorder action must render a button on the agent tab label", recorderButton);
        recorderButton.doClick();
        assertSame("the click must deliver the AGENT tab's own file, not the selected editor's",
                agentFile, recorder.file);
        assertEquals("the click must arrive with the editor-tab action place",
                ActionPlaces.EDITOR_TAB, recorder.place);
    }

    public void testD_DisabledActionIsHiddenOnTheAgentTab() {
        assertOrdinaryTabSelected();
        InplaceButton disabledButton = buttonFor(labelButtons(agentInfo), DISABLED_ICON);
        // Observed on this SDK: ActionButton.update() sets the button visible
        // only when the presentation is enabled AND visible, so a disabled
        // action is HIDDEN — not greyed out.
        assertTrue("a disabled action must not render as a visible button (greyed icons are "
                        + "not what this SDK does)",
                disabledButton == null || !disabledButton.isVisible());
    }

    public void testE_ProjectResolvesFromTheTabLabelActionEvent() {
        assertOrdinaryTabSelected();
        InplaceButton recorderButton = buttonFor(labelButtons(agentInfo), RECORDER_ICON);
        assertNotNull(recorderButton);
        recorderButton.doClick();
        assertSame("AnActionEvent.getProject() must resolve through the button's data "
                        + "context to the tab hierarchy's project",
                getProject(), recorder.project);
    }

    public void testF_UnselectedAgentLabelContextCarriesTheAgentFile() {
        assertOrdinaryTabSelected();
        VirtualFile resolved = DataManager.getInstance()
                .getDataContext(tabs.getTabLabel(agentInfo))
                .getData(CommonDataKeys.VIRTUAL_FILE);
        assertSame("the unselected agent tab label's data context must carry THAT tab's file",
                agentFile, resolved);
    }

    public void testG_RegisteredTabActionGroupsExist() {
        ActionManager actions = ActionManager.getInstance();
        Object labelGroup = actions.getAction("EditorTabActionGroup");
        assertNotNull("EditorTabActionGroup must be registered (the supported seam for "
                + "per-editor-tab label actions)", labelGroup);
        assertTrue("EditorTabActionGroup must be a DefaultActionGroup",
                labelGroup instanceof DefaultActionGroup);
        Object popupGroup = actions.getAction("EditorTabPopupMenu");
        assertNotNull("EditorTabPopupMenu must be registered (the tab right-click menu)", popupGroup);
        assertTrue("EditorTabPopupMenu must be an ActionGroup",
                popupGroup instanceof ActionGroup);
    }
}
