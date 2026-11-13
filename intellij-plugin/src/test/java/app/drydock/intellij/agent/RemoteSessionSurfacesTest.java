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

import java.nio.file.Path;
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
 * <p>Headless: these are the derivations, not rendered Swing. The one test
 * that needs the IDE's service registry ({@code stateOf}'s liveness read)
 * runs under the platform fixture; the rest are pure functions.</p>
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
}
