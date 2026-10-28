package app.drydock.intellij.agent;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.IsolatedCatalog;

import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.ide.impl.HeadlessDataManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.InplaceButton;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.ui.tabs.impl.JBEditorTabs;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JRootPane;

import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;

import java.nio.file.Path;

import java.time.Instant;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertThrows;

/**
 * The tab-header shortcuts over a real catalog, in a booted IDE (A7, A9,
 * A12): two stopped sessions seeded into the sandboxed catalog through
 * {@link IsolatedCatalog}, the same action composition the registered
 * header group carries ({@code OwnProject, Lifecycle, Done} with a
 * recording perform seam, so no command ever runs and no agent is ever
 * launched), placed on real editor-tab labels the way the platform does.
 *
 * <p>Everything here is a <em>headless platform check</em> — Swing
 * {@code isVisible()} on components the platform's own
 * {@code ActionButton.update()} drives, and the real {@code update()} fed
 * by the clicked tab's own data context. Nothing is rendered on screen, so
 * nothing here is visual evidence: paint, hover and themes belong to the
 * visual stage.</p>
 */
public class AgentTabActionsPlatformTest extends BasePlatformTestCase {

    /** The recording perform seam: captures the target a click acted on. */
    private static final class Recorder implements AgentTabActions.Perform {

        private AgentTabCommands.Target target;

        @Override
        public void run(AgentTabCommands.Target target) {
            this.target = target;
        }
    }

    /** A real lifecycle action whose default perform is captured, not run: the click's painted state, with no dispatch. */
    private static final class RecordingLifecycle extends AgentTabActions.Lifecycle {

        private AgentTabCommands.Target target;
        private SessionCommands.State presented;

        RecordingLifecycle() {
            super(null);
        }

        @Override
        void perform(AgentTabCommands.Target target, SessionCommands.State presented) {
            this.target = target;
            this.presented = presented;
        }
    }

    /** A panel that sinks a file and a project, like the tab components and the project frame it stands in for. */
    private static final class SinkPanel extends JPanel implements UiDataProvider {

        private final Project project;
        private final VirtualFile file;

        SinkPanel(Project project, VirtualFile file) {
            super(new BorderLayout());
            this.project = project;
            this.file = file;
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

    private ManagedAgentSession sessionA;
    private ManagedAgentSession sessionB;
    private AgentConsoleFile fileA;
    private AgentConsoleFile fileB;
    private Recorder recorder;
    private AgentTabActions.Lifecycle lifecycle;
    private JBEditorTabs tabs;
    private TabInfo tabA;
    private TabInfo tabB;
    private TabInfo ordinary;
    private JFrame frame;

    @Override
    protected void setUp() throws Exception {
        // The guard runs BEFORE super.setUp(): before anything in this test
        // can initialize the plugin, and before any catalog write below.
        IsolatedCatalog.assertSandboxed();
        System.out.println("T14 sandbox: config=" + PathManager.getConfigPath()
                + ", projectsFile=" + System.getProperty("drydock.projectsFile"));
        super.setUp();

        // The unit-test sandbox skips the component-hierarchy data walk a
        // real IDE performs; the update()/click probes resolve their target
        // through that walk, so switch it back on (the SDK's own sanctioned
        // hook, as EditorTabLabelActionsRuntimeTest does).
        HeadlessDataManager.fallbackToProductionDataManager(getTestRootDisposable());

        // Two stopped sessions in the sandboxed catalog: A carries an agent
        // session id (a conversation to resume into), B does not.
        sessionA = stopped("A", Optional.of("pi-conversation-a"));
        sessionB = stopped("B", Optional.empty());
        IsolatedCatalog.seed(sessionA);
        IsolatedCatalog.seed(sessionB);
        fileA = AgentConsoleFileSystem.getInstance().fileFor(sessionA.id());
        fileB = AgentConsoleFileSystem.getInstance().fileFor(sessionB.id());

        // The registered header group's composition, with a recording seam.
        recorder = new Recorder();
        lifecycle = new AgentTabActions.Lifecycle(recorder);
        DefaultActionGroup shortcuts = new DefaultActionGroup(
                new AgentTabActions.OwnProject(recorder), lifecycle, new AgentTabActions.Done(recorder));

        // The stock editor tabs: autoHide off, exactly as EditorTabbedContainer
        // sets it, so label actions are not hover-gated by the platform.
        tabs = new JBEditorTabs(getProject(), getTestRootDisposable());
        tabs.getPresentation().setTabLabelActionsAutoHide(false);
        tabA = tabInfo(fileA, "A", shortcuts);
        tabB = tabInfo(fileB, "B", shortcuts);
        ordinary = tabInfo(new LightVirtualFile("Ordinary.java"), "Ordinary.java", shortcuts);
        tabs.addTab(tabA);
        tabs.addTab(tabB);
        tabs.addTab(ordinary);

        // A parent sink for PROJECT, mirroring how the tab content sits inside
        // the project frame; the label actions' data contexts resolve
        // through it. ActionPanel.update() needs a reachable JRootPane.
        JPanel projectSink = new SinkPanel(getProject(), null);
        projectSink.add(tabs, BorderLayout.CENTER);
        if (GraphicsEnvironment.isHeadless()) {
            JRootPane rootPane = new JRootPane();
            rootPane.getContentPane().add(projectSink, BorderLayout.CENTER);
        } else {
            frame = new JFrame();
            frame.setContentPane(projectSink);
            frame.pack();
        }

        // The clicked-tab precondition: the ordinary file tab is selected,
        // both agent tabs are not.
        tabs.select(ordinary, true);
        tabs.updateTabActions(true);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (frame != null) {
                frame.dispose();
                frame = null;
            }
            fileA.putUserData(HostedTab.KEY, null);
            AgentExecutionRegistry.getInstance().markStarting(sessionA.id(), false);
            AgentExecutionRegistry.getInstance().markStarting(sessionB.id(), false);
            IsolatedCatalog.remove(sessionA.id(), sessionB.id());
        } finally {
            super.tearDown();
        }
    }

    /** A stopped session the way the existing test factories build them, with the given conversation id. */
    private static ManagedAgentSession stopped(String displayName, Optional<String> agentSessionId) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(ManagedSessionId.newId(),
                RepositoryId.newId(), displayName, AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/wt/" + displayName)), Instant.EPOCH)
                .status(SessionStatus.INACTIVE).build();
        return session.withAgentSessionId(agentSessionId);
    }

    /** createTabInfo's shape: component sinking the file, label actions, EDITOR_TAB place. */
    private static TabInfo tabInfo(VirtualFile file, String text, DefaultActionGroup shortcuts) {
        TabInfo info = new TabInfo(new SinkPanel(null, file)).setText(text).setObject(file);
        info.setTabLabelActions(shortcuts, ActionPlaces.EDITOR_TAB);
        return info;
    }

    /** The label's buttons rendered for the action whose tooltip starts with {@code actionText}. */
    private static InplaceButton buttonFor(TabInfo info, JBEditorTabs tabs, String actionText) {
        for (InplaceButton button : UIUtil.findComponentsOfType(tabs.getTabLabel(info), InplaceButton.class)) {
            String tooltip = button.getToolTipText();
            if (tooltip != null && tooltip.startsWith(actionText)) {
                return button;
            }
        }
        return null;
    }

    private List<InplaceButton> visibleButtons(TabInfo info) {
        List<InplaceButton> visible = new ArrayList<>();
        for (InplaceButton button : UIUtil.findComponentsOfType(tabs.getTabLabel(info), InplaceButton.class)) {
            if (button.isVisible()) {
                visible.add(button);
            }
        }
        return visible;
    }

    /** A stand-in live process: prints one line, then idles — never a Drydock agent. */
    private static final List<String> LIVE_AGENT = List.of("/bin/sh", "-c", "printf 'up\\n'; sleep 60");

    /** The no-op end listener a start's owner needs. */
    private static final AgentExecutionListener OWNER = new AgentExecutionListener() {
    };

    /** Polls, pumping the EDT (the test runs on it), for up to ten seconds. */
    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("condition not met within 10 s");
            }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            Thread.sleep(20);
        }
    }

    /** A real header event for {@code info}: the clicked tab's own label context. */
    private AnActionEvent headerEvent(TabInfo info, Presentation presentation) {
        return AnActionEvent.createEvent(DataManager.getInstance().getDataContext(tabs.getTabLabel(info)),
                presentation, ActionPlaces.EDITOR_TAB, ActionUiKind.NONE, null);
    }

    /** The popup's own event shape (the SDK proof's): the right-clicked tab's file and the project, at the popup place. */
    private AnActionEvent popupEvent(VirtualFile file, Presentation presentation) {
        return AnActionEvent.createEvent(SimpleDataContext.builder()
                        .add(CommonDataKeys.VIRTUAL_FILE, file)
                        .add(CommonDataKeys.PROJECT, getProject())
                        .build(),
                presentation, ActionPlaces.EDITOR_TAB_POPUP, ActionUiKind.POPUP, null);
    }

    public void testLifecycleAndDoneButtonsAppearOnBothUnselectedAgentTabsAndNotOnTheFileTab() {
        assertSame("precondition: the ordinary file tab must be the selected one",
                ordinary, tabs.getSelectedInfo());
        // A: stopped WITH a conversation -> Resume; B: stopped without one -> Start.
        InplaceButton resume = buttonFor(tabA, tabs, "Resume");
        InplaceButton start = buttonFor(tabB, tabs, "Start");
        assertNotNull("A's unselected tab must carry the lifecycle button", resume);
        assertNotNull("B's unselected tab must carry the lifecycle button", start);
        assertTrue("the lifecycle button must be visible on the UNSELECTED agent tab A", resume.isVisible());
        assertTrue("the lifecycle button must be visible on the UNSELECTED agent tab B", start.isVisible());
        assertTrue("Done must be visible on A's unselected tab",
                buttonFor(tabA, tabs, "Done").isVisible());
        assertTrue("Done must be visible on B's unselected tab",
                buttonFor(tabB, tabs, "Done").isVisible());
        assertTrue("an ordinary file tab must carry no visible Drydock shortcut (A7)",
                visibleButtons(ordinary).isEmpty());
    }

    public void testColdLifecycleTooltipsSayResumeAAndStartBThroughTheRealUpdate() {
        // The cold path: no editor exists for either file, so the per-editor
        // facts are empty and the labels come from the pure fallback rules.
        Presentation presentation = new Presentation();
        lifecycle.update(headerEvent(tabA, presentation));
        assertEquals("A's header text must name A's own conversation: the SDK "
                        + "header button derives its tooltip from Presentation.text, "
                        + "not the description (A7)",
                "Resume A", presentation.getText());
        assertEquals("Resume A", presentation.getDescription());
        assertEquals(AllIcons.Actions.Resume, presentation.getIcon());
        assertTrue(presentation.isVisible());
        assertTrue(presentation.isEnabled());

        Presentation coldStart = new Presentation();
        lifecycle.update(headerEvent(tabB, coldStart));
        assertEquals("B's header text names B, honest about there being nothing "
                        + "to resume into (A6)",
                "Start B", coldStart.getText());
        assertEquals("Start B", coldStart.getDescription());
        assertEquals(AllIcons.Actions.Execute, coldStart.getIcon());
        assertTrue(coldStart.isVisible());
        assertTrue(coldStart.isEnabled());

        // The popup keeps the short command label as its text; the naming
        // sentence rides the description.
        Presentation popup = new Presentation();
        lifecycle.update(popupEvent(fileA, popup));
        assertEquals("Resume", popup.getText());
        assertEquals("Resume A", popup.getDescription());
    }

    public void testTheHeaderTextNamesTheSessionForEveryHeaderShortcut() {
        // The header carries the session-naming sentence as its text (the SDK
        // button reads its tooltip from the text); the popup keeps the short
        // command label. OwnProject is paintable only with hosted facts (A8).
        fileA.putUserData(HostedTab.KEY, new HostedTab.Facts(getProject().getLocationHash(),
                new SessionPlace("hosted-repo", Optional.empty())));
        try {
            Presentation ownHeader = new Presentation();
            new AgentTabActions.OwnProject().update(headerEvent(tabA, ownHeader));
            assertTrue(ownHeader.isVisible());
            assertEquals("Open A in its own project window", ownHeader.getText());

            Presentation doneHeader = new Presentation();
            new AgentTabActions.Done().update(headerEvent(tabA, doneHeader));
            assertEquals("Done with A…", doneHeader.getText());

            // The popup labels stay the short command names, description named.
            Presentation ownPopup = new Presentation();
            new AgentTabActions.OwnProject().update(popupEvent(fileA, ownPopup));
            assertEquals("Open in Own Project Window", ownPopup.getText());
            assertEquals("Open A in its own project window", ownPopup.getDescription());
            Presentation donePopup = new Presentation();
            new AgentTabActions.Done().update(popupEvent(fileA, donePopup));
            assertEquals("Done…", donePopup.getText());
            assertEquals("Done with A…", donePopup.getDescription());
        } finally {
            fileA.putUserData(HostedTab.KEY, null);
        }
    }

    public void testClickingBsLifecycleRecordsBsTargetNotTheSelectedFileAndNotA() {
        InplaceButton start = buttonFor(tabB, tabs, "Start");
        assertNotNull("B's tab must carry the lifecycle button", start);
        start.doClick();
        assertNotNull("the click must reach the recording seam", recorder.target);
        assertEquals("the click must act on B's session, not A's and not the selected "
                        + "file's session (A7: the clicked tab, never the selection)",
                sessionB.id(), recorder.target.session().id());
        assertSame("the click must carry B's own console file", fileB, recorder.target.file());
        assertSame(getProject(), recorder.target.project());
    }

    public void testOwnProjectAppearsOnlyWithHostedTabFacts() {
        // No hosted facts: both consoles are this window's own, so there is
        // nothing to jump to (A8) and the command must be absent.
        assertNull("OwnProject must be absent on A without hosted facts",
                buttonFor(tabA, tabs, "Project window"));
        assertNull("OwnProject must be absent on B without hosted facts",
                buttonFor(tabB, tabs, "Project window"));
        try {
            fileA.putUserData(HostedTab.KEY, new HostedTab.Facts(getProject().getLocationHash(),
                    new SessionPlace("hosted-repo", Optional.empty())));
            tabs.updateTabActions(true);
            // The header button's tooltip is the session-naming text (A7: the
            // SDK reads it from Presentation.text), not the short label.
            InplaceButton ownProject = buttonFor(tabA, tabs, "Open A in its own project window");
            assertNotNull("OwnProject must appear once A is hosted from another worktree", ownProject);
            assertTrue("the hosted own-project shortcut must be visible on the unselected "
                            + "agent tab (A7)",
                    ownProject.isVisible());
            assertNull("B is still this window's own session, so it keeps no shortcut",
                    buttonFor(tabB, tabs, "Project window"));
        } finally {
            fileA.putUserData(HostedTab.KEY, null);
        }
    }

    public void testABusyLifecycleIsHiddenNotGreyed() {
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        registry.markStarting(sessionA.id(), true);
        try {
            Presentation presentation = new Presentation();
            lifecycle.update(headerEvent(tabA, presentation));
            assertTrue("the busy lifecycle stays visible in its presentation", presentation.isVisible());
            assertFalse("a Start in flight must disable the lifecycle (A11)", presentation.isEnabled());
            assertEquals("Starting…", presentation.getText());

            tabs.updateTabActions(true);
            InplaceButton busy = buttonFor(tabA, tabs, "Starting");
            // Observed on this SDK (consistent with T1(d)):
            // ActionButton.update() sets the button visible only when the
            // presentation is BOTH visible and enabled, so a disabled action
            // is HIDDEN — not greyed out. Headless observation, not visual.
            assertTrue("a busy lifecycle must not render as a visible (greyed) button",
                    busy == null || !busy.isVisible());
        } finally {
            registry.markStarting(sessionA.id(), false);
        }
    }

    public void testADetachedLiveExecutionShowsAgentInTheRealHeaderButton() throws Exception {
        // The window-detached rule (A6, reconnect first): session A's agent
        // runs globally — a real registry execution, a live /bin/sh process
        // through the registry's own start path — but no window shows it
        // (release), so the header and the popup offer Show agent: never
        // Deactivate from a detached view, never a start.
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        CompletableFuture<AgentExecution> started = registry.startArgv(sessionA.id(),
                Path.of(System.getProperty("java.io.tmpdir")), LIVE_AGENT, getProject(), OWNER);
        waitFor(started::isDone);
        try {
            started.get(1, TimeUnit.SECONDS);
            registry.release(sessionA.id());
            assertTrue("precondition: the execution still runs globally",
                    registry.isRunning(sessionA.id()));
            assertFalse("precondition: no window shows it — the detached case (A6)",
                    AgentExecutionService.getInstance(getProject()).isRunning(sessionA.id()));

            Presentation header = new Presentation();
            lifecycle.update(headerEvent(tabA, header));
            assertEquals("a detached tab's primary is Show agent, never Deactivate (A6)",
                    "Show agent A", header.getText());
            assertEquals("Show agent A", header.getDescription());
            assertEquals(AllIcons.Actions.Show, header.getIcon());
            assertTrue(header.isVisible());
            assertTrue(header.isEnabled());

            Presentation popup = new Presentation();
            lifecycle.update(popupEvent(fileA, popup));
            assertEquals("Show agent", popup.getText());
            assertEquals("Show agent A", popup.getDescription());

            // The real InplaceButton path: the rendered header button's own
            // tooltip, which the SDK reads from the presentation text.
            tabs.updateTabActions(true);
            InplaceButton show = buttonFor(tabA, tabs, "Show agent A");
            assertNotNull("the detached tab's header button must carry the "
                    + "Show-agent tooltip", show);
            assertTrue("the detached tab keeps its primary visible (A7)", show.isVisible());
        } finally {
            registry.forget(sessionA.id());
            waitFor(() -> !registry.isRunning(sessionA.id()));
        }
    }

    /**
     * A second stock editor-tab strip carrying {@code group} on agent tabs
     * A and B, mounted under the project sink the way {@link #setUp} mounts
     * its own: the real JBTabs label machinery builds a real SDK
     * {@code ActionButton}/{@code InplaceButton} per action per tab.
     */
    private JBEditorTabs mountTabs(DefaultActionGroup group, TabInfo... infos) {
        JBEditorTabs strip = new JBEditorTabs(getProject(), getTestRootDisposable());
        strip.getPresentation().setTabLabelActionsAutoHide(false);
        for (TabInfo info : infos) {
            strip.addTab(info);
        }
        JPanel projectSink = new SinkPanel(getProject(), null);
        projectSink.add(strip, BorderLayout.CENTER);
        JRootPane rootPane = new JRootPane();
        rootPane.getContentPane().add(projectSink, BorderLayout.CENTER);
        strip.updateTabActions(true);
        return strip;
    }

    public void testARealHeaderClickCarriesThePaintedShowAgentAcrossTheSdksTemplateClonedEvent()
            throws Exception {
        // The end race of Show agent (A6) through the REAL header path: the
        // SDK's ActionButton builds the click's event from a clone of the
        // action's TEMPLATE presentation — not the presentation update()
        // painted — so whatever the paint left on its own presentation is
        // gone at the click. One shared action instance on both tabs, the
        // way the registered header group shares it.
        RecordingLifecycle action = new RecordingLifecycle();
        DefaultActionGroup group = new DefaultActionGroup(action);
        TabInfo a = tabInfo(fileA, "A", group);
        TabInfo b = tabInfo(fileB, "B", group);
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        CompletableFuture<AgentExecution> started = registry.startArgv(sessionA.id(),
                Path.of(System.getProperty("java.io.tmpdir")), LIVE_AGENT, getProject(), OWNER);
        waitFor(started::isDone);
        try {
            started.get(1, TimeUnit.SECONDS);
            registry.release(sessionA.id());

            // The paint: A's tab is detached (Show agent), B's is stopped
            // with nothing to resume into (Start).
            JBEditorTabs strip = mountTabs(group, a, b);
            InplaceButton showA = buttonFor(a, strip, "Show agent A");
            InplaceButton startB = buttonFor(b, strip, "Start B");
            assertNotNull("A's header must be painted Show agent while detached", showA);
            assertNotNull("B's header must be painted Start", startB);
            assertNotSame("each tab owns its own SDK button", showA, startB);
            // Continuity: the real SDK update latched each tab's own paint on
            // that tab's own button — the CONTEXT_COMPONENT of the context
            // the SDK builds from the InplaceButton for update AND click.
            AgentTabActions.Painted paintedA = (AgentTabActions.Painted)
                    showA.getClientProperty(AgentTabActions.PAINTED_INTENT);
            AgentTabActions.Painted paintedB = (AgentTabActions.Painted)
                    startB.getClientProperty(AgentTabActions.PAINTED_INTENT);
            assertNotNull("A's own SDK button must carry A's paint", paintedA);
            assertNotNull("B's own SDK button must carry B's paint", paintedB);
            assertEquals(sessionA.id(), paintedA.session());
            assertEquals(sessionB.id(), paintedB.session());
            assertNull("the shared action's template carries no per-tab paint",
                    action.getTemplatePresentation().getClientProperty(AgentTabActions.PAINTED_INTENT));

            // The end race: A's execution ends before the click. A's
            // CURRENT state is now stopped with a conversation — Resume.
            registry.stop(sessionA.id());
            waitFor(() -> !registry.isRunning(sessionA.id()));
            assertFalse("precondition: the current state is no longer detached",
                    AgentTabCommands.stateOf(new AgentTabCommands.Target(getProject(), fileA, sessionA))
                            .detachedFromThisWindow());

            // B first: its own painted Start, never A's Show agent.
            startB.doClick();
            assertNotNull("B's real click must reach the default perform", action.target);
            assertEquals(sessionB.id(), action.target.session().id());
            assertNotNull("B's click must carry B's painted state", action.presented);
            assertEquals("B's click dispatches B's own painted Start, not A's intent",
                    SessionCommands.Lifecycle.START, SessionCommands.lifecycle(action.presented));

            // A: the real SDK click, through the template-cloned event.
            action.target = null;
            action.presented = null;
            showA.doClick();
            assertNotNull("A's real click must reach the default perform", action.target);
            assertEquals(sessionA.id(), action.target.session().id());
            assertNotNull("A's click must carry the state its header painted, not "
                    + "null from the template-cloned event", action.presented);
            assertEquals("A's click dispatches the Show agent it was painted with — never "
                            + "the Resume the stopped current state would run (A6)",
                    SessionCommands.Lifecycle.SHOW_AGENT, SessionCommands.lifecycle(action.presented));
            assertFalse("nothing was started for the painted Show-agent click",
                    registry.isStarting(sessionA.id()));

            // Fail closed: a paint for ANOTHER target on B's button is not
            // B's intent, and with no provable intent the real click must
            // dispatch nothing — never fall back to B's current state.
            startB.putClientProperty(AgentTabActions.PAINTED_INTENT, paintedA);
            action.target = null;
            action.presented = null;
            startB.doClick();
            assertNull("another tab's paint must not drive B's click", action.target);
            startB.putClientProperty(AgentTabActions.PAINTED_INTENT, null);
            startB.doClick();
            assertNull("a click with no painted intent dispatches nothing", action.target);
        } finally {
            registry.forget(sessionA.id());
            waitFor(() -> !registry.isRunning(sessionA.id()));
        }
    }

    // ---- the guard (its positive run is setUp's; these prove it fails closed) ----

    public void testTheGuardAcceptsTheRealTestSandbox() {
        IsolatedCatalog.assertSandboxed(Path.of(PathManager.getConfigPath()),
                System.getProperty("drydock.projectsFile"));
    }

    public void testTheGuardRejectsUnexpectedLayouts() {
        String projectsFile = System.getProperty("drydock.projectsFile");
        Path config = Path.of(PathManager.getConfigPath());
        // The user's real shared projects list must never be accepted.
        assertThrows(AssertionError.class, () ->
                IsolatedCatalog.assertSandboxed(config,
                        Path.of(System.getProperty("user.home"), ".drydock", "projects.json").toString()));
        // A missing override is not a sandbox at all.
        assertThrows(AssertionError.class, () -> IsolatedCatalog.assertSandboxed(config, null));
        // The runIde sandbox config (no -test suffix) is a different IDE run.
        assertThrows(AssertionError.class, () ->
                IsolatedCatalog.assertSandboxed(config.getParent().resolve("config"), projectsFile));
        // The user's real IDE config is not the test sandbox.
        assertThrows(AssertionError.class, () -> IsolatedCatalog.assertSandboxed(
                Path.of(System.getProperty("user.home"), "Library", "Application Support", "JetBrains",
                        "idea"), projectsFile));
    }
}
