package app.drydock.intellij.agent;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.WorkspaceBoxBinding;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.ui.SessionDots;
import app.drydock.intellij.workspace.RemoteSessionLiveness;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.UIUtil;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The session surfaces' remote (workspace-box) presentation: the honest box
 * name + liveness labeling T21 pins, and the OQ10 rule that no remote row
 * ever shows an activity state — the local hook-based monitor cannot see a
 * box-side agent, so a working/idle claim would always be a guess.
 *
 * <p>Every remote word here is derived through the real {@code AgentStripText}
 * / {@code SessionCommands} / {@code AgentConsoleTabPresentation} functions the
 * surfaces call, never status constants compared against themselves; and the
 * local half of each rule is re-pinned beside it, so the change can be seen
 * to touch only remote rows.</p>
 *
 * <p>Headless: the derivations run as pure functions, and the strip's
 * wiring is pinned on a real {@code AgentConsoleStrip} whose components are
 * read un-rendered — no paint, hover or theme is visual evidence. The tests
 * that need the IDE's service registry ({@code stateOf}'s liveness read,
 * the strip's construction) run under the platform fixture; the rest are
 * pure functions.</p>
 */
public class RemoteSessionSurfacesTest extends BasePlatformTestCase {

    private static final String BOX = "drydock-demo";

    private static Optional<RemoteSessionLiveness.State> liveness(RemoteSessionLiveness.State state) {
        return Optional.of(state);
    }

    private static ManagedAgentSession session(SessionMode mode) {
        return new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), "Fix the parser",
                AgentBinding.unlaunched(AgentKind.CLAUDE, mode),
                SessionWorkspace.inRepository(Path.of("/tmp/repo")),
                Instant.EPOCH)
                .status(SessionStatus.RUNNING)
                .build();
    }

    // ---- the strip's remote words (AgentStripText) ------------------------------

    public void testRemoteStatusTextPerLivenessState() {
        // The honest labeling: every liveness verdict the box can hand back
        // gets its own word, and the box name rides the line. "Unknown" for
        // a row never probed (empty liveness) — "Not running" there would
        // claim an answer this IDE does not have.
        assertEquals("Running · on box " + BOX,
                AgentStripText.remoteStatusText(false, liveness(RemoteSessionLiveness.State.ALIVE), BOX));
        assertEquals("Not running · on box " + BOX,
                AgentStripText.remoteStatusText(false, liveness(RemoteSessionLiveness.State.DEAD), BOX));
        assertEquals("Unknown · on box " + BOX,
                AgentStripText.remoteStatusText(false, liveness(RemoteSessionLiveness.State.UNKNOWN), BOX));
        assertEquals("Unknown · on box " + BOX,
                AgentStripText.remoteStatusText(false, Optional.empty(), BOX));
        assertEquals("Box stopped · on box " + BOX,
                AgentStripText.remoteStatusText(false, liveness(RemoteSessionLiveness.State.BOX_STOPPED), BOX));
        assertEquals("Box gone · on box " + BOX,
                AgentStripText.remoteStatusText(false, liveness(RemoteSessionLiveness.State.BOX_GONE), BOX));
        // This IDE owns the live ssh execution: the agent runs, whatever a
        // stale or missing verdict once said.
        for (RemoteSessionLiveness.State state : RemoteSessionLiveness.State.values()) {
            assertEquals(state.name(), "Running · on box " + BOX,
                    AgentStripText.remoteStatusText(true, liveness(state), BOX));
        }
        assertEquals("Running · on box " + BOX,
                AgentStripText.remoteStatusText(true, Optional.empty(), BOX));
        // Never an activity word: the local strip folds Working/At prompt
        // into its status; a remote row has no activity to fold (OQ10).
        for (RemoteSessionLiveness.State state : RemoteSessionLiveness.State.values()) {
            String text = AgentStripText.remoteStatusText(false, liveness(state), BOX);
            assertFalse(state.name(), text.contains("Working"));
            assertFalse(state.name(), text.contains("At prompt"));
            assertFalse(state.name(), text.contains("Needs you"));
        }
    }

    public void testRemoteStatusTooltipNamesTheBoxAndTheRefusalWords() {
        // The stopped/gone sentences are the resume refusal's own words: the
        // strip and the refusal must not tell two stories about one box.
        assertEquals("Box " + BOX + " is stopped; its conversations are unreachable",
                AgentStripText.remoteStatusTooltip(false, liveness(RemoteSessionLiveness.State.BOX_STOPPED), BOX));
        assertEquals("Box " + BOX + " no longer exists",
                AgentStripText.remoteStatusTooltip(false, liveness(RemoteSessionLiveness.State.BOX_GONE), BOX));
        assertEquals("The agent is running on workspace box " + BOX + "; Resume attaches to it",
                AgentStripText.remoteStatusTooltip(false, liveness(RemoteSessionLiveness.State.ALIVE), BOX));
        assertEquals("The agent is not running on workspace box " + BOX,
                AgentStripText.remoteStatusTooltip(false, liveness(RemoteSessionLiveness.State.DEAD), BOX));
        assertTrue(AgentStripText.remoteStatusTooltip(false, Optional.empty(), BOX).contains("unknown"));
        assertTrue(AgentStripText.remoteStatusTooltip(true, Optional.empty(), BOX)
                .contains("this window shows its live ssh console"));
    }

    public void testRemoteDisplayStatusPerLivenessState() {
        // The dot's palette: ALIVE keeps the live mark (a probe fact, not
        // a working claim); every other verdict the box can answer paints
        // the idle band, with the word — not the dot — carrying the
        // distinction. This IDE's own ssh execution keeps the persisted
        // status, and an error row never paints blue on a stale probe.
        assertEquals(SessionStatus.RUNNING, AgentStripText.remoteDisplayStatus(
                SessionStatus.IDLE, false, liveness(RemoteSessionLiveness.State.ALIVE)));
        for (RemoteSessionLiveness.State notLive : new RemoteSessionLiveness.State[]{
                RemoteSessionLiveness.State.DEAD, RemoteSessionLiveness.State.UNKNOWN,
                RemoteSessionLiveness.State.BOX_STOPPED, RemoteSessionLiveness.State.BOX_GONE}) {
            assertEquals(notLive.name(), SessionStatus.IDLE, AgentStripText.remoteDisplayStatus(
                    SessionStatus.RUNNING, false, liveness(notLive)));
            assertEquals(notLive.name(), SessionDots.IDLE_GRAY, SessionDots.dotColor(AgentStripText.remoteDisplayStatus(
                            SessionStatus.RUNNING, false, liveness(notLive)), Optional.empty()));
        }
        assertEquals(SessionStatus.RUNNING, AgentStripText.remoteDisplayStatus(
                SessionStatus.RUNNING, true, Optional.empty()));
        assertEquals(SessionStatus.FAILED, AgentStripText.remoteDisplayStatus(
                SessionStatus.FAILED, false, liveness(RemoteSessionLiveness.State.ALIVE)));
        assertEquals(SessionDots.ERROR_RED, SessionDots.dotColor(AgentStripText.remoteDisplayStatus(
                SessionStatus.FAILED, false, liveness(RemoteSessionLiveness.State.ALIVE)), Optional.empty()));
    }

    // ---- the tab mark (AgentConsoleTabPresentation) -----------------------------

    public void testRemoteTabIconPerLivenessState() {
        // The mark must agree with the strip beside it: an ALIVE-detached
        // row paints the live word and the live blue even from a stale idle
        // row, mirroring the local detached-live rule; the box-level
        // verdicts paint gray with their own words carried elsewhere.
        AgentConsoleTabPresentation.TabIcon alive = AgentConsoleTabPresentation.remoteIconState(
                SessionStatus.IDLE, false, false, liveness(RemoteSessionLiveness.State.ALIVE));
        assertEquals(SessionStatus.RUNNING, alive.status());
        assertEquals("Running", SessionDots.statusWord(alive.status(), alive.activity()));
        assertEquals(SessionDots.RUNNING_BLUE, SessionDots.dotColor(alive.status(), alive.activity()));

        for (RemoteSessionLiveness.State state : RemoteSessionLiveness.State.values()) {
            // attached here: the persisted status paints, as the local rule does
            AgentConsoleTabPresentation.TabIcon attached = AgentConsoleTabPresentation.remoteIconState(
                    SessionStatus.RUNNING, true, true, liveness(state));
            assertEquals(state.name(), SessionStatus.RUNNING, attached.status());
            // every remote case, without exception: no activity, ever
            assertEquals(state.name(), Optional.empty(), attached.activity());
            String word = SessionDots.statusWord(attached.status(), attached.activity());
            assertFalse(state.name(), word.equals("Working"));
            assertFalse(state.name(), word.equals("At prompt"));
            assertFalse(state.name(), word.equals("Needs you"));

            AgentConsoleTabPresentation.TabIcon detached = AgentConsoleTabPresentation.remoteIconState(
                    SessionStatus.IDLE, false, false, liveness(state));
            assertEquals(state.name(), Optional.empty(), detached.activity());
            String detachedWord = SessionDots.statusWord(detached.status(), detached.activity());
            assertFalse(state.name(), detachedWord.equals("Working"));
            assertFalse(state.name(), detachedWord.equals("At prompt"));
            assertFalse(state.name(), detachedWord.equals("Needs you"));
        }
    }

    public void testRemoteTabIconHasNoActivityParameterToSmuggle() {
        // OQ10 is structural: the remote derivation takes no activity at
        // all, so no caller can pass a local working/idle claim onto a box
        // row. The local derivation still folds real monitor activity —
        // pinned here so the contrast is deliberate, not accidental.
        AgentConsoleTabPresentation.TabIcon localBusy = AgentConsoleTabPresentation.iconState(
                SessionStatus.RUNNING, true, true, Optional.of(SessionActivity.BUSY));
        assertEquals(Optional.of(SessionActivity.BUSY), localBusy.activity());
        assertEquals("Working", SessionDots.statusWord(localBusy.status(), localBusy.activity()));

        // A detached-remote row seen from a window that shows none of it:
        // another window in this IDE owns the ssh console, so the mark is
        // live and generic — the same rule as a local detached session.
        AgentConsoleTabPresentation.TabIcon shownElsewhere = AgentConsoleTabPresentation.remoteIconState(
                SessionStatus.IDLE, false, true, liveness(RemoteSessionLiveness.State.DEAD));
        assertEquals(SessionStatus.RUNNING, shownElsewhere.status());
        assertEquals(Optional.empty(), shownElsewhere.activity());
    }

    // ---- the tab title (AgentConsoleTabPresentation) ----------------------------

    public void testTabTitleNamesTheBoxForRemoteRowsOnly() {
        // The tab is the one line the user scans to tell sessions apart: a
        // box row names its box so it cannot pass for a local one. A local
        // row's title is the pre-remote text, unchanged.
        ManagedAgentSession local = session(SessionMode.WRITES);
        assertEquals("Fix the parser", AgentConsoleTabPresentation.tabTitle(local));
        assertEquals("Fix the parser · RO",
                AgentConsoleTabPresentation.tabTitle(session(SessionMode.READ_ONLY)));

        ManagedAgentSession remote = new ManagedAgentSession.Builder(
                local.id(), local.repositoryId(), "Fix the parser",
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/tmp/repo")),
                Instant.EPOCH)
                .status(SessionStatus.IDLE)
                .workspaceBox(Optional.of(new WorkspaceBoxBinding(BOX, "/Users/dev/clone")))
                .build();
        assertEquals("Fix the parser · on " + BOX, AgentConsoleTabPresentation.tabTitle(remote));

        ManagedAgentSession remoteReadOnly = new ManagedAgentSession.Builder(
                local.id(), local.repositoryId(), "Fix the parser",
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.READ_ONLY),
                SessionWorkspace.inRepository(Path.of("/tmp/repo")),
                Instant.EPOCH)
                .status(SessionStatus.IDLE)
                .workspaceBox(Optional.of(new WorkspaceBoxBinding(BOX, "/Users/dev/clone")))
                .build();
        assertEquals("Fix the parser · RO · on " + BOX,
                AgentConsoleTabPresentation.tabTitle(remoteReadOnly));
    }

    // ---- the command state (SessionCommands) ------------------------------------

    public void testStateOfCarriesTheBoxAndReadsTheLivenessMap() {
        // stateOf is the ONE state every tab surface computes; for a remote
        // row it now carries the row's binding and the detached liveness
        // this IDE last learned — a memory read of the plugin's map, never
        // a probe of the box.
        ManagedSessionId id = ManagedSessionId.newId();
        ManagedAgentSession remote = new ManagedAgentSession.Builder(
                id, RepositoryId.newId(), "On the box",
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/tmp/repo")),
                Instant.EPOCH)
                .status(SessionStatus.IDLE)
                .workspaceBox(Optional.of(new WorkspaceBoxBinding(BOX, "/Users/dev/clone")))
                .build();
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        plugin.remoteSessionLiveness().set(id, RemoteSessionLiveness.State.BOX_STOPPED);

        SessionCommands.State state = SessionCommands.stateOf(remote, Optional.empty(), true);
        assertEquals(Optional.of(new WorkspaceBoxBinding(BOX, "/Users/dev/clone")), state.box());
        assertEquals(Optional.of(RemoteSessionLiveness.State.BOX_STOPPED), state.liveness());
        // The shared status line a remote row's surfaces show: box + the
        // liveness state, never an activity claim.
        assertEquals(Optional.of("Box stopped · on box " + BOX),
                SessionCommands.remoteStatusLine(state));

        plugin.remoteSessionLiveness().remove(id);
    }

    public void testLocalRowsStateAndLineAreUnchanged() {
        // A local row never reads the remote map: its state is exactly the
        // pre-remote shape (a State built the old way, remote facts empty),
        // its status line is empty, and its display/icon rules keep their
        // own corrections — the local presentation is byte-identical.
        ManagedAgentSession local = new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), "Local work",
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/tmp/repo")),
                Instant.EPOCH)
                .status(SessionStatus.RUNNING)
                .build();
        SessionCommands.State state = SessionCommands.stateOf(local, Optional.empty(), true);
        assertEquals(new SessionCommands.State(false, false, false, false, false, false, false, false),
                state);
        assertEquals(Optional.empty(), SessionCommands.remoteStatusLine(state));

        // The local strip's own corrections, pinned unchanged beside the
        // remote rules so the delta is visible.
        assertEquals(SessionStatus.INACTIVE, AgentStripText.displayStatus(SessionStatus.RUNNING, false));
        assertEquals(SessionStatus.RUNNING, AgentStripText.displayStatus(SessionStatus.RUNNING, true));
        AgentConsoleTabPresentation.TabIcon localDetached = AgentConsoleTabPresentation.iconState(
                SessionStatus.INACTIVE, false, true, Optional.empty());
        assertEquals(SessionStatus.RUNNING, localDetached.status());
    }

    public void testUnprobedRemoteRowReadsUnknownInTheLine() {
        // A remote row with no verdict yet is still a remote row: the box
        // name shows, and the liveness word is Unknown — not a guess about
        // the box, and not a local-style "Not running" claim.
        SessionCommands.State unprobed = new SessionCommands.State(
                false, true, false, false, false, false, false, false,
                Optional.of(new WorkspaceBoxBinding(BOX, "/Users/dev/clone")), Optional.empty());
        assertEquals(Optional.of("Unknown · on box " + BOX),
                SessionCommands.remoteStatusLine(unprobed));
        // A local State built the pre-remote way has no line at all.
        assertEquals(Optional.empty(), SessionCommands.remoteStatusLine(
                new SessionCommands.State(false, true, false, false, false, false, false)));
    }

    // ---- the detached remote Stop: the lifecycle choice ------------------------

    public void testAnAliveDetachedRemoteRowOffersDeactivateWithoutAttachingFirst() {
        // The restart scenario: the row is IDLE here, nothing runs in this
        // IDE, and the box says the tmux session is there. The primary is
        // the explicit Stop — the user must not have to attach first
        // (contract component 6) — whether or not there is a conversation
        // to resume into, because the agent that conversation belongs to
        // is live.
        for (boolean resumable : new boolean[]{false, true}) {
            assertEquals("resumable=" + resumable, SessionCommands.Lifecycle.DEACTIVATE,
                    SessionCommands.lifecycle(
                            remoteState(resumable, liveness(RemoteSessionLiveness.State.ALIVE))));
        }
    }

    public void testOnlyAnAliveVerdictOffersTheDetachedRemoteStop() {
        // Every other verdict — and a row nothing has probed yet — keeps
        // the honest attach (Resume) or fresh conversation (Start): the
        // box has not said an agent is there, so a Stop would claim one.
        for (RemoteSessionLiveness.State notAlive : new RemoteSessionLiveness.State[]{
                RemoteSessionLiveness.State.DEAD, RemoteSessionLiveness.State.UNKNOWN,
                RemoteSessionLiveness.State.BOX_STOPPED, RemoteSessionLiveness.State.BOX_GONE}) {
            assertEquals(notAlive.name(), SessionCommands.Lifecycle.RESUME,
                    SessionCommands.lifecycle(remoteState(true, liveness(notAlive))));
        }
        assertEquals(SessionCommands.Lifecycle.RESUME,
                SessionCommands.lifecycle(remoteState(true, Optional.empty())));
        assertEquals(SessionCommands.Lifecycle.START,
                SessionCommands.lifecycle(remoteState(false, Optional.empty())));
    }

    public void testTheAttachedAndLocalLifecycleRulesStayUnchanged() {
        // The new choice is scoped to the detached-ALIVE remote row: a
        // remote row this window shows still deactivates, one another
        // window of this IDE shows still offers Show agent, and a local
        // row keeps exactly its pre-remote Resume/Start pair.
        assertEquals(SessionCommands.Lifecycle.DEACTIVATE, SessionCommands.lifecycle(
                new SessionCommands.State(true, true, false, false, false, false, false, false,
                        Optional.of(box()), Optional.empty())));
        assertEquals(SessionCommands.Lifecycle.SHOW_AGENT, SessionCommands.lifecycle(
                new SessionCommands.State(true, true, false, false, false, false, false, true,
                        Optional.of(box()), Optional.empty())));
        assertEquals(SessionCommands.Lifecycle.RESUME, SessionCommands.lifecycle(
                new SessionCommands.State(false, true, false, false, false, false, false, false)));
        assertEquals(SessionCommands.Lifecycle.START, SessionCommands.lifecycle(
                new SessionCommands.State(false, false, false, false, false, false, false, false)));
    }

    // ---- the real strip's remote wiring (AgentConsoleStrip.update) ------------

    public void testTheStripPaintsTheBoxAndAnAliveVerdictNeverALocalActivity() {
        AgentConsoleStrip strip = new AgentConsoleStrip(action -> { });
        // A local-style BUSY activity is passed on purpose: the monitor
        // cannot see a box-side agent (OQ10), so the strip must not render
        // it — the line is the box's own verdict, or it would be a guess.
        strip.update(remoteSession(SessionStatus.IDLE, "claude-conversation"),
                Optional.of(SessionActivity.BUSY), false, false, "Claude",
                Duration.ofMinutes(5), Optional.empty(), Optional.empty(), false, Optional.empty(),
                false, false, remoteState(true, liveness(RemoteSessionLiveness.State.ALIVE)));

        JBLabel status = label(strip, "Running · on box " + BOX);
        assertNotNull("the ALIVE-detached row paints the box name and the liveness "
                + "verdict, never the local \"Not running\"", status);
        assertEquals(AgentStripText.remoteStatusTooltip(false, liveness(RemoteSessionLiveness.State.ALIVE), BOX),
                status.getToolTipText());
        assertEquals("the ALIVE-detached remote row's primary is the explicit Stop",
                "Deactivate", strip.primaryButton().getText());
        assertTrue(strip.primaryButton().isEnabled());
        for (JBLabel candidate : UIUtil.findComponentsOfType(strip, JBLabel.class)) {
            assertFalse("a remote row never renders a local activity word (OQ10): "
                    + candidate.getText(), "Working".equals(candidate.getText()));
        }
    }

    public void testTheStripPaintsEveryOtherRemoteVerdictAsItsOwnWord() {
        AgentConsoleStrip strip = new AgentConsoleStrip(action -> { });
        ManagedAgentSession remote = remoteSession(SessionStatus.IDLE, "claude-conversation");
        for (RemoteSessionLiveness.State state : RemoteSessionLiveness.State.values()) {
            strip.update(remote, Optional.empty(), false, false, "Claude", Duration.ZERO,
                    Optional.empty(), Optional.empty(), false, Optional.empty(), false, false,
                    remoteState(true, liveness(state)));
            String line = AgentStripText.remoteStatusText(false, liveness(state), BOX);
            JBLabel status = label(strip, line);
            assertNotNull(state.name() + " paints its own word, not the local idle wording",
                    status);
            assertEquals(state.name(), AgentStripText.remoteStatusTooltip(false, liveness(state), BOX),
                    status.getToolTipText());
        }
        // Only ALIVE changes the primary: a box that answered anything
        // else — or nothing yet — keeps the attach (Resume).
        strip.update(remote, Optional.empty(), false, false, "Claude", Duration.ZERO,
                Optional.empty(), Optional.empty(), false, Optional.empty(), false, false,
                remoteState(true, liveness(RemoteSessionLiveness.State.DEAD)));
        assertTrue("a DEAD remote row keeps Resume: " + strip.primaryButton().getText(),
                strip.primaryButton().getText().startsWith("Resume"));
    }

    public void testARemoteRowAttachedHerePaintsRunningOnTheBoxNotLocalActivity() {
        AgentConsoleStrip strip = new AgentConsoleStrip(action -> { });
        SessionCommands.State attached = new SessionCommands.State(true, true, false, false,
                false, false, false, false, Optional.of(box()), Optional.empty());
        strip.update(remoteSession(SessionStatus.RUNNING, "claude-conversation"),
                Optional.of(SessionActivity.BUSY), true, false, "Claude", Duration.ZERO,
                Optional.empty(), Optional.empty(), false, Optional.empty(), false, false, attached);

        assertNotNull("an attached remote row names its box instead of folding the "
                + "local monitor's activity into the status word",
                label(strip, "Running · on box " + BOX));
        for (JBLabel candidate : UIUtil.findComponentsOfType(strip, JBLabel.class)) {
            assertFalse("no local activity word on a remote row (OQ10): " + candidate.getText(),
                    "Working".equals(candidate.getText()));
        }
        assertEquals("an attached remote row's primary is unchanged: the Stop",
                "Deactivate", strip.primaryButton().getText());
    }

    public void testARemoteRowShownByAnotherWindowHereKeepsShowAgentAndNamesTheBox() {
        AgentConsoleStrip strip = new AgentConsoleStrip(action -> { });
        SessionCommands.State elsewhere = new SessionCommands.State(true, true, false, false,
                false, false, false, true, Optional.of(box()), Optional.empty());
        strip.update(remoteSession(SessionStatus.RUNNING, "claude-conversation"),
                Optional.empty(), false, false, "Claude", Duration.ZERO,
                Optional.empty(), Optional.empty(), false, Optional.empty(), false, false, elsewhere);

        assertNotNull("another window of this IDE owns the ssh console, so the row is "
                + "live and says where it is not — naming the box",
                label(strip, "Running · on box " + BOX + " · not shown here"));
        assertEquals("the reconnect-first rule is unchanged for a remote row",
                "Show agent", strip.primaryButton().getText());
    }

    public void testALocalRowKeepsTheLocalStripPresentation() {
        AgentConsoleStrip strip = new AgentConsoleStrip(action -> { });
        ManagedAgentSession local = new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), "Local work",
                AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/wt/local")), Instant.EPOCH)
                .status(SessionStatus.INACTIVE).build()
                .withAgentSessionId(Optional.of("pi-conversation"));
        strip.update(local, Optional.empty(), false, false, "Pi", Duration.ZERO,
                Optional.empty(), Optional.of(12_345), false, Optional.empty(), false, false,
                new SessionCommands.State(false, true, false, false, false, false, false, false));
        assertNotNull("a local stopped row keeps the local status word",
                label(strip, "Not running"));
        assertEquals(AgentStripText.primaryLabel(true, Optional.of(12_345)),
                strip.primaryButton().getText());
        assertTrue(strip.primaryButton().isEnabled());
    }

    public void testAnInFlightRemoteStopGreysThePrimaryUnderTheBusyLabel() {
        AgentConsoleStrip strip = new AgentConsoleStrip(action -> { });
        strip.update(remoteSession(SessionStatus.RUNNING, "claude-conversation"),
                Optional.empty(), true, false, "Claude", Duration.ZERO,
                Optional.empty(), Optional.empty(), false, Optional.empty(), false, false,
                new SessionCommands.State(true, true, false, true, false, false, false, false,
                        Optional.of(box()), Optional.empty()));
        assertEquals("the accepted Stop names itself while it runs (INV-8)",
                "Deactivating…", strip.primaryButton().getText());
        assertFalse("a second click could only race the first",
                strip.primaryButton().isEnabled());
    }

    // ---- the remote Stop's synchronous busy fact -------------------------------

    public void testAnAcceptedRemoteStopIsBusyUntilItConcludes() {
        // The claim is what the handler takes on the click, before the
        // blocking kill: the service's own STOPPING mark is written on its
        // executor after the click returns, and the execution's
        // stop-requested only after the kill — neither can answer a
        // second click inside that window.
        ManagedSessionId id = ManagedSessionId.newId();
        ManagedAgentSession remote = new ManagedAgentSession.Builder(
                id, RepositoryId.newId(), "Box work",
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/tmp/repo")), Instant.EPOCH)
                .status(SessionStatus.IDLE)
                .workspaceBox(Optional.of(box()))
                .build();
        try {
            assertTrue(SessionCommands.claimRemoteStop(id));
            SessionCommands.State claimed = SessionCommands.stateOf(remote, Optional.empty(), true);
            assertTrue("the claim is the busy fact every surface reads", claimed.stopping());
            assertTrue(SessionCommands.busy(claimed));
            assertFalse("so no surface offers the lifecycle while the kill runs",
                    SessionCommands.lifecycleEnabled(claimed));
            assertFalse("a second click's claim is refused", SessionCommands.claimRemoteStop(id));
        } finally {
            SessionCommands.releaseRemoteStop(id);
        }
        SessionCommands.State released = SessionCommands.stateOf(remote, Optional.empty(), true);
        assertFalse("completion clears the busy fact on every path", released.stopping());
        assertTrue("and a later Stop is a new operation", SessionCommands.claimRemoteStop(id));
        SessionCommands.releaseRemoteStop(id);
    }

    // ---- helpers ----------------------------------------------------------------

    private static WorkspaceBoxBinding box() {
        return new WorkspaceBoxBinding(BOX, "/Users/dev/clone");
    }

    /** A detached remote row's window-scoped state: nothing runs here. */
    private static SessionCommands.State remoteState(boolean resumable,
                                                     Optional<RemoteSessionLiveness.State> liveness) {
        return new SessionCommands.State(false, resumable, false, false, false, false, false, false,
                Optional.of(box()), liveness);
    }

    /** A remote row as a restart would have loaded it: box-bound, IDLE. */
    private static ManagedAgentSession remoteSession(SessionStatus status, String agentSessionId) {
        return new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), "Box work",
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/tmp/repo")), Instant.EPOCH)
                .status(status)
                .workspaceBox(Optional.of(box()))
                .build()
                .withAgentSessionId(Optional.of(agentSessionId));
    }

    /** The strip's label with exactly {@code text}, or null when none shows it. */
    private static JBLabel label(AgentConsoleStrip strip, String text) {
        for (JBLabel candidate : UIUtil.findComponentsOfType(strip, JBLabel.class)) {
            if (text.equals(candidate.getText())) {
                return candidate;
            }
        }
        return null;
    }
}
