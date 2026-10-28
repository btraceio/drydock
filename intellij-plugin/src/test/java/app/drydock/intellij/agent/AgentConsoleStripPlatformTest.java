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
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;

import java.nio.file.Path;

import java.time.Duration;
import java.time.Instant;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The console ribbon in a booted IDE (A4, A5, A7): a real
 * {@link AgentConsoleStrip} built the way {@code AgentConsoleEditor} builds
 * it, repainted through the real {@code update()} with
 * {@link SessionCommands.State} snapshots — every enablement, label and
 * visibility flag asserted on the strip's own Swing components.
 *
 * <p>Only the icon-binding case touches the catalog (A7): one stopped
 * session seeded through {@link IsolatedCatalog}, resolved by the real
 * {@link AgentTabActions.Lifecycle} — the recording {@code Perform} seam,
 * never the default constructor — through a real {@code EDITOR_TAB} event
 * carrying that session's console file and the project. The presentation
 * must prove itself <em>resolved and visible</em> before any icon is
 * compared: a hidden or unresolved action fails the test instead of
 * passing silently on two null icons.</p>
 *
 * <p>Headless only: every check reads un-rendered Swing components. Paint,
 * hover and themes belong to the visual stage, so nothing here is visual
 * evidence.</p>
 */
public class AgentConsoleStripPlatformTest extends BasePlatformTestCase {

    /** The recording perform seam: captures the target an action resolved, so no command ever runs. */
    private static final class Recorder implements AgentTabActions.Perform {

        private AgentTabCommands.Target target;

        @Override
        public void run(AgentTabCommands.Target target) {
            this.target = target;
        }
    }

    /** Sinks the clicked surface's own file and the project, as the editor tab hierarchy does. */
    private static final class SinkPanel extends JPanel implements UiDataProvider {

        private final Project project;
        private final AgentConsoleFile file;

        SinkPanel(Project project, AgentConsoleFile file) {
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

    private AgentConsoleStrip strip;
    /** The seeded session of the icon-binding case, removed again in tearDown. */
    private ManagedSessionId seeded;

    @Override
    protected void setUp() throws Exception {
        // The guard runs BEFORE super.setUp(): before anything in this test
        // can initialize the plugin, and before any catalog write below
        // (T14's constraint; the write paths re-check it themselves).
        IsolatedCatalog.assertSandboxed();
        super.setUp();

        // The unit-test sandbox skips the component-hierarchy data walk a
        // real IDE performs; the icon-binding case resolves its target
        // through that walk, so switch it back on (the SDK's own hook, as
        // the earlier tab tests do).
        HeadlessDataManager.fallbackToProductionDataManager(getTestRootDisposable());

        // The strip exactly as the editor builds it: the click handler is
        // never exercised here, so a no-op keeps every assertion on what
        // update() shows rather than what a click would do.
        strip = new AgentConsoleStrip(action -> { });
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (seeded != null) {
                IsolatedCatalog.remove(seeded);
                seeded = null;
            }
        } finally {
            super.tearDown();
        }
    }

    // ---- the ribbon's command states (A5, A11) --------------------------

    public void testARunningSessionOffersEnabledDeactivateAndDone() {
        ManagedAgentSession session = session("Running one", Optional.empty(), SessionStatus.RUNNING);
        strip.update(session, Optional.empty(), true, false, "Pi", Duration.ZERO,
                Optional.empty(), Optional.empty(), false, Optional.empty(), false, false,
                commands(true, false, false));
        assertEquals("the running session's primary command is Deactivate",
                "Deactivate", strip.primaryButton().getText());
        assertTrue("a live session can always be deactivated",
                strip.primaryButton().isEnabled());
        assertTrue("Done stays clickable while the agent runs",
                button(SessionCommands.Command.DONE).isEnabled());
    }

    public void testAStoppedResumableSessionLabelsItsPrimaryResume() {
        ManagedAgentSession session = session("Stopped", Optional.of("pi-conversation"), SessionStatus.INACTIVE);
        SessionCommands.State stopped = commands(false, true, false);
        strip.update(session, Optional.empty(), false, false, "Pi", Duration.ZERO,
                Optional.empty(), Optional.of(12_345), false, Optional.empty(), false, false, stopped);
        String label = strip.primaryButton().getText();
        assertTrue("the resumable label must start with Resume (A4): " + label,
                label.startsWith("Resume"));
        assertEquals("the label must be AgentStripText's own, cost and all",
                AgentStripText.primaryLabel(true, Optional.of(12_345)), label);

        // An unknown resume cost is a bare Resume, never a fabricated ~0k.
        strip.update(session, Optional.empty(), false, false, "Pi", Duration.ZERO,
                Optional.empty(), Optional.empty(), false, Optional.empty(), false, false, stopped);
        assertEquals(AgentStripText.primaryLabel(true, Optional.empty()),
                strip.primaryButton().getText());
        assertTrue(strip.primaryButton().isEnabled());
    }

    public void testAStoppedSessionWithoutAConversationOffersStart() {
        ManagedAgentSession session = session("No conversation", Optional.empty(), SessionStatus.INACTIVE);
        strip.update(session, Optional.empty(), false, false, "Pi", Duration.ZERO,
                Optional.empty(), Optional.empty(), false, Optional.empty(), false, false,
                commands(false, false, false));
        assertEquals("a session with nothing recorded can only start fresh",
                "Start", strip.primaryButton().getText());
        assertEquals(AgentStripText.primaryLabel(false, Optional.empty()),
                strip.primaryButton().getText());
        assertTrue(strip.primaryButton().isEnabled());
    }

    public void testAStartingSessionDisablesLifecycleAndDoneUnderTheBusyLabel() {
        ManagedAgentSession session = session("Starting", Optional.empty(), SessionStatus.STARTING);
        strip.update(session, Optional.empty(), false, false, "Pi", Duration.ZERO,
                Optional.empty(), Optional.empty(), false, Optional.empty(), false, false,
                commands(false, true, true));
        assertEquals("the in-flight operation names itself in the label",
                "Starting…", strip.primaryButton().getText());
        assertFalse("a second lifecycle click could only race the first (A11)",
                strip.primaryButton().isEnabled());
        assertFalse("Done waits with it", button(SessionCommands.Command.DONE).isEnabled());
    }

    // ---- hosting (A8) ------------------------------------------------------

    public void testTheProjectWindowJumpFollowsHosting() {
        ManagedAgentSession session = session("Hosted", Optional.empty(), SessionStatus.RUNNING);
        SessionCommands.State running = commands(true, false, false);
        strip.update(session, Optional.empty(), true, false, "Pi", Duration.ZERO,
                Optional.empty(), Optional.empty(), false,
                Optional.of(new SessionPlace("hosted-repo", Optional.of("feature/x"))),
                false, false, running);
        JButton ownProject = button(SessionCommands.Command.OWN_PROJECT);
        assertNotNull(ownProject);
        assertTrue("a tab hosting another worktree's agent offers the jump to its "
                        + "own project window (A8)",
                ownProject.isVisible());

        strip.update(session, Optional.empty(), true, false, "Pi", Duration.ZERO,
                Optional.empty(), Optional.empty(), false, Optional.empty(), false, false, running);
        assertFalse("this window's own session has nothing to jump to (A8)",
                ownProject.isVisible());
    }

    // ---- keyboard access (AGENTS.md) ---------------------------------------

    public void testThePrimaryButtonIsAFocusableButton() {
        strip.update(session("Focusable", Optional.empty(), SessionStatus.INACTIVE),
                Optional.empty(), false, false, "Pi", Duration.ZERO,
                Optional.empty(), Optional.empty(), false, Optional.empty(), false, false,
                commands(false, false, false));
        assertTrue("the lifecycle must be a real focusable JButton so Enter and "
                        + "Space trigger it, in every state",
                strip.primaryButton().isFocusable());
    }

    // ---- the wrapping ribbon (A4, A5) --------------------------------------

    public void testANarrowStripWrapsOntoRowsAndKeepsDoneInside() {
        ManagedAgentSession session = session(
                "A session whose name is long enough to need the whole first row",
                Optional.empty(), SessionStatus.RUNNING);
        strip.update(session, Optional.empty(), true, false, "Pi agent", Duration.ZERO,
                Optional.of(3), Optional.empty(), true,
                Optional.of(new SessionPlace("hosted-repo", Optional.empty())),
                false, false, commands(true, false, false));

        // WrapLayout reads the container's current width, so set the width
        // before asking for the wrapped preferred size.
        strip.setSize(200, 10);
        strip.doLayout();
        Dimension wrapped = strip.getPreferredSize();
        strip.setSize(100_000, 10);
        strip.doLayout();
        int oneRow = strip.getPreferredSize().height;
        assertTrue("at width 200 the ribbon must prefer more than one row (A4): "
                        + wrapped.height + " vs " + oneRow,
                wrapped.height > oneRow);

        strip.setSize(200, wrapped.height);
        strip.doLayout();
        JButton done = button(SessionCommands.Command.DONE);
        assertNotNull(done);
        assertTrue("Done must sit fully inside the wrapped strip (A5): " + done.getBounds(),
                new Rectangle(0, 0, strip.getWidth(), strip.getHeight())
                        .contains(done.getBounds()));
    }

    // ---- live resize through the real parent (F7) -----------------------

    public void testALiveResizeThroughTheRealParentLayoutKeepsEveryButtonInsideBothWays() {
        // The strip is painted ONCE — the way the editor paints it after a
        // state change — and then only the parent is resized, wide to narrow
        // to wide again, with no status tick, no timer and no further
        // update() in between: the hypothesis under test was that WrapLayout
        // computes its preferred height from a stale width so a narrowed
        // parent clips the wrapped rows (Done first) until some other event
        // repaints the world.
        ManagedAgentSession session = session(
                "A session whose name is long enough to need the whole first row",
                Optional.empty(), SessionStatus.RUNNING);
        strip.update(session, Optional.empty(), true, false, "Pi agent", Duration.ZERO,
                Optional.of(3), Optional.empty(), true,
                Optional.of(new SessionPlace("hosted-repo", Optional.empty())),
                false, false, commands(true, false, false));

        // The strip's real home, as AgentConsoleEditor builds it: a
        // BorderLayout root holding it at NORTH. Only the sizes are driven by
        // hand: a headless hierarchy has no peer, so validate() is inert, and
        // the layoutTree call below is the validateTree recursion (each
        // container lays out, then its children) a shown editor runs per
        // resize — the strip itself never sets its own height.
        JPanel root = new JPanel(new BorderLayout());
        root.add(strip, BorderLayout.NORTH);
        root.add(new JPanel(), BorderLayout.CENTER);

        int wide = 1000;
        // 200 is proven by testANarrowStripWrapsOntoRowsAndKeepsDoneInside
        // to force wrapping for exactly this strip content.
        int narrow = 200;

        int wideHeight = 0;
        int narrowHeight = 0;
        for (int pass = 1; pass <= 3; pass++) {
            boolean narrowPass = pass == 2;
            root.setSize(narrowPass ? narrow : wide, 600);
            layoutTree(root);
            if (pass == 1) {
                wideHeight = strip.getHeight();
            } else if (pass == 2) {
                narrowHeight = strip.getHeight();
            }
            // The wrapped units — the strip's direct children, the command
            // buttons among them — must stay inside; the identity block is
            // one unit by design (A5) and its own internals are its business.
            for (int i = 0; i < strip.getComponentCount(); i++) {
                if (!(strip.getComponent(i) instanceof JButton candidate)
                        || !candidate.isVisible()) {
                    continue;
                }
                assertTrue("every visible command button must stay fully inside the "
                                + "strip after the resize to " + (narrowPass ? narrow : wide)
                                + " wide (pass " + pass + "): strip " + strip.getSize()
                                + ", button \u201c" + candidate.getText() + "\u201d "
                                + candidate.getBounds(),
                        new Rectangle(strip.getSize()).contains(candidate.getBounds()));
            }
            JButton done = button(SessionCommands.Command.DONE);
            assertNotNull(done);
            assertTrue("Done especially must survive the resize (A5): " + done.getBounds(),
                    new Rectangle(strip.getSize()).contains(done.getBounds()));
        }
        assertTrue("the narrow pass must actually wrap — more rows than the wide "
                        + "passes: " + wideHeight + " vs " + narrowHeight,
                narrowHeight > wideHeight);
    }

    // ---- icon sameness through the real tab action (A7) -------------------

    public void testTheStripLifecycleIconIsTheIconTheRealTabActionShows() {
        // One stopped session with a conversation to resume into — the only
        // case here that touches the catalog, seeded the only sanctioned way.
        ManagedAgentSession session = session("Seeded", Optional.of("pi-conversation-seeded"),
                SessionStatus.INACTIVE);
        IsolatedCatalog.seed(session);
        seeded = session.id();
        AgentConsoleFile file = AgentConsoleFileSystem.getInstance().fileFor(session.id());

        // The real EDITOR_TAB event for this session's own console tab: the
        // file the tab carries and the project it lives in (A7).
        Presentation presentation = new Presentation();
        AnActionEvent event = AnActionEvent.createEvent(
                DataManager.getInstance().getDataContext(new SinkPanel(getProject(), file)),
                presentation, ActionPlaces.EDITOR_TAB, ActionUiKind.NONE, null);

        // The real Lifecycle with the recording seam — never the default
        // constructor, so no command can ever run.
        Recorder recorder = new Recorder();
        AgentTabActions.Lifecycle lifecycle = new AgentTabActions.Lifecycle(recorder);
        lifecycle.update(event);
        // Visible BEFORE icons: a hidden or unresolved action fails the test
        // here instead of passing silently on two missing icons.
        assertTrue("the tab lifecycle must resolve a visible presentation for the "
                        + "seeded catalog session (A7)",
                presentation.isVisible());
        Icon tabIcon = presentation.getIcon();
        assertNotNull("a resolved, visible lifecycle must carry an icon", tabIcon);
        // The header surface carries the session-naming tooltip as its
        // text (F3: the header's ActionButton derives its hover tooltip
        // from Presentation.text), so the stopped, resumable session shows
        // "Resume <name>" — the naming sentence, not the bare command label.
        assertEquals("the stopped, resumable session is offered Resume naming "
                        + "this tab's own session",
                "Resume Seeded", presentation.getText());

        // And the real catalog target through the same event, captured by
        // the seam: the strip below is painted for that very target.
        lifecycle.actionPerformed(event);
        assertNotNull("the resolved target must reach the recording seam", recorder.target);
        assertEquals("the resolved target must be the seeded session itself",
                session.id(), recorder.target.session().id());

        // The strip for the same session state: the same State derivation
        // the editor makes (cold facts — no editor is open on the file).
        SessionCommands.State state = SessionCommands.stateOf(recorder.target.session(),
                Optional.empty());
        assertFalse(state.running());
        assertTrue(state.resumable());
        strip.update(recorder.target.session(), Optional.empty(), state.running(), false, "Pi",
                Duration.ZERO, Optional.empty(), Optional.empty(), false, Optional.empty(),
                false, false, state);
        assertSame("the strip's lifecycle button must carry the very icon the real "
                        + "tab action shows for the same session (A7)",
                tabIcon, strip.primaryButton().getIcon());
        assertEquals("and the shared Resume choice, as a stable anchor",
                AllIcons.Actions.Resume, tabIcon);
    }

    // ---- the detached tab (A6, reconnect first) --------------------------

    public void testADetachedTabShowsAgentWithAnHonestRunningState() {
        // The session runs — just not in this window — so the strip is a
        // remote control for a live agent, never a tombstone for one.
        ManagedAgentSession session = session("Elsewhere", Optional.of("pi-conversation"),
                SessionStatus.RUNNING);
        strip.update(session, Optional.empty(), false, false, "Pi", Duration.ZERO,
                Optional.empty(), Optional.empty(), false, Optional.empty(), false, false,
                detached(true));

        assertEquals("a tab whose session runs elsewhere offers Show agent (A6), "
                        + "never Deactivate",
                "Show agent", strip.primaryButton().getText());
        assertSame("the strip's primary carries the shared model's own icon, "
                        + "the one the tab header and popup show too",
                SessionCommands.Lifecycle.SHOW_AGENT.icon(), strip.primaryButton().getIcon());
        assertEquals("and that icon is the model's Show agent", AllIcons.Actions.Show,
                strip.primaryButton().getIcon());
        assertTrue("showing is a question about the present, so it stays clickable",
                strip.primaryButton().isEnabled());

        assertNotNull("the state word must stay live and honest about where it is "
                        + "not, instead of \"Not running\" implying the live agent "
                        + "stopped (A6)",
                label("Running · not shown here"));
        JButton done = button(SessionCommands.Command.DONE);
        assertNotNull(done);
        assertTrue("a detached tab is still a live session: Done stays visible",
                done.isVisible());
        assertTrue("and clickable — nothing is in flight", done.isEnabled());
    }

    public void testEveryPrimaryClickCarriesTheLifecycleItWasPaintedWith() {
        List<AgentConsoleStrip.StripAction> clicks = new ArrayList<>();
        AgentConsoleStrip clicked = new AgentConsoleStrip(clicks::add);

        // Live here stays Deactivate; a stopped session keeps Resume and
        // Start; only the detached case changes — and all four hand the
        // editor the shared lifecycle's own action, which the editor routes
        // through the one shared handler (A6).
        clicked.update(session("Live here", Optional.empty(), SessionStatus.RUNNING),
                Optional.empty(), true, false, "Pi", Duration.ZERO, Optional.empty(),
                Optional.empty(), false, Optional.empty(), false, false, commands(true, false, false));
        clicked.primaryButton().doClick();
        assertEquals("a genuinely live tab still deactivates its agent",
                List.of(AgentConsoleStrip.StripAction.DEACTIVATE), clicks);

        clicks.clear();
        clicked.update(session("Elsewhere", Optional.of("pi-conversation"), SessionStatus.RUNNING),
                Optional.empty(), false, false, "Pi", Duration.ZERO, Optional.empty(),
                Optional.empty(), false, Optional.empty(), false, false, detached(false));
        clicked.primaryButton().doClick();
        assertEquals("a click on the detached tab's Show agent carries the shared "
                        + "lifecycle's own action, the one the editor hands the shared "
                        + "handler — never a start-shaped stand-in",
                List.of(AgentConsoleStrip.StripAction.SHOW_AGENT), clicks);

        clicks.clear();
        clicked.update(session("Stopped", Optional.of("pi-conversation"), SessionStatus.INACTIVE),
                Optional.empty(), false, false, "Pi", Duration.ZERO, Optional.empty(),
                Optional.empty(), false, Optional.empty(), false, false, commands(false, true, false));
        clicked.primaryButton().doClick();
        assertEquals("a stopped session with a conversation keeps Resume",
                List.of(AgentConsoleStrip.StripAction.RESUME), clicks);

        clicks.clear();
        clicked.update(session("Fresh", Optional.empty(), SessionStatus.INACTIVE),
                Optional.empty(), false, false, "Pi", Duration.ZERO, Optional.empty(),
                Optional.empty(), false, Optional.empty(), false, false, commands(false, false, false));
        clicked.primaryButton().doClick();
        assertEquals("a stopped session with nothing recorded keeps Start",
                List.of(AgentConsoleStrip.StripAction.START), clicks);
    }

    /**
     * The validateTree recursion a shown hierarchy runs per resize: each
     * container lays out, then its children. A headless hierarchy has no
     * peer, so {@code validate()} alone is inert.
     */
    private static void layoutTree(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) {
                layoutTree(child);
            }
        }
    }

    // ---- helpers -----------------------------------------------------------

    /** The command state of a session that is neither stopping, moving nor transferring. */
    private static SessionCommands.State commands(boolean running, boolean resumable, boolean starting) {
        return new SessionCommands.State(running, resumable, starting, false, false, false, false);
    }

    /** A window-scoped detached state: globally live, not shown in this window. */
    private static SessionCommands.State detached(boolean resumable) {
        return new SessionCommands.State(true, resumable, false, false, false, false, false, true);
    }

    /** The strip's label with exactly {@code text}, or null when none shows it. */
    private JBLabel label(String text) {
        for (JBLabel candidate : UIUtil.findComponentsOfType(strip, JBLabel.class)) {
            if (text.equals(candidate.getText())) {
                return candidate;
            }
        }
        return null;
    }

    /** A session the way the existing test factories build them. */
    private static ManagedAgentSession session(String displayName, Optional<String> agentSessionId,
                                               SessionStatus status) {
        ManagedAgentSession built = new ManagedAgentSession.Builder(ManagedSessionId.newId(),
                RepositoryId.newId(), displayName,
                AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/wt/" + displayName)), Instant.EPOCH)
                .status(status).build();
        return built.withAgentSessionId(agentSessionId);
    }

    /** The strip's command button for {@code command} (invisible ones included). */
    private JButton button(SessionCommands.Command command) {
        List<JButton> buttons = UIUtil.findComponentsOfType(strip, JButton.class);
        for (JButton candidate : buttons) {
            if (command.label().equals(candidate.getText())) {
                return candidate;
            }
        }
        return null;
    }
}
