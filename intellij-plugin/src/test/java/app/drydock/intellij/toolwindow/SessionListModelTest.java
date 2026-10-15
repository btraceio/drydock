package app.drydock.intellij.toolwindow;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the session list model's pure seams — the static {@code join} and
 * the event-driven rebuilds — with plain data, no IDE (the model itself has
 * no IntelliJ import; everything here is deterministic because the refresh
 * executor only runs what the test runs).
 *
 * <p>Fixture vocabulary follows the watcher's semantics: a "state file" is
 * one entry of the poll map, keyed by the agent session id its hook named
 * the file after.</p>
 */
class SessionListModelTest {

    private static final Instant T0 = Instant.parse("2026-10-05T10:00:00Z");

    private static final RepositoryId MAIN = RepositoryId.newId();
    private static final RepositoryId WORKTREE = RepositoryId.newId();

    // ---- AC17: lifecycle-only when no activity can be known -----------------

    @Test
    void piSessionsStayLifecycleOnlyWhenBusyStateFilesExist() {
        // A busy state file exists — for a Claude session id. The Pi rows
        // have no id that matches any state file (one unlaunched, one with a
        // discovered id nobody wrote a state file for), so both must render
        // lifecycle-only: providers without an ActivityReporter never show a
        // fabricated BUSY/IDLE.
        ManagedAgentSession unlaunchedPi = session(MAIN, AgentKind.PI, "pi-unlaunched", SessionStatus.RUNNING,
                Optional.empty(), T0);
        ManagedAgentSession discoveredPi = session(MAIN, AgentKind.PI, "pi-discovered", SessionStatus.RUNNING,
                Optional.of("pi-1"), T0);
        ManagedAgentSession claude = session(MAIN, AgentKind.CLAUDE, "claude", SessionStatus.RUNNING,
                Optional.of("claude-1"), T0);

        List<SessionListModel.Row> rows = SessionListModel.join(
                List.of(unlaunchedPi, discoveredPi, claude), MAIN, Map.of("claude-1", SessionActivity.BUSY));

        assertEquals(Optional.empty(), activityOf(rows, "pi-unlaunched"),
                "a Pi session with no agent session id is lifecycle-only even with a busy state file present");
        assertEquals(Optional.empty(), activityOf(rows, "pi-discovered"),
                "a Pi session whose id has no state file is lifecycle-only");
        assertEquals(Optional.of(SessionActivity.BUSY), activityOf(rows, "claude"),
                "the busy state file joins onto the Claude session it belongs to");
        assertEquals("Running", SessionDots.statusText(SessionStatus.RUNNING, Optional.empty()),
                "the Pi rows show the lifecycle word only");
    }

    // ---- AC16 (automated half): the lifecycle+activity join -----------------

    @Test
    void claudeSessionJoinsActivityByAgentSessionId() {
        ManagedAgentSession claude = session(MAIN, AgentKind.CLAUDE, "claude", SessionStatus.RUNNING,
                Optional.of("claude-1"), T0);

        List<SessionListModel.Row> rows = SessionListModel.join(
                List.of(claude), MAIN, Map.of("claude-1", SessionActivity.BUSY));

        assertEquals(Optional.of(SessionActivity.BUSY), activityOf(rows, "claude"));
        assertEquals("Running · busy", SessionDots.statusText(claude.status(), activityOf(rows, "claude")));
        assertEquals("Running · needs attention", SessionDots.statusText(
                SessionStatus.RUNNING, Optional.of(SessionActivity.NEEDS_ATTENTION)));
        // A leftover state file must not label a dead session busy: activity
        // is a property of a live process.
        ManagedAgentSession exited = session(MAIN, AgentKind.CLAUDE, "exited", SessionStatus.EXITED,
                Optional.of("claude-1"), T0);
        assertEquals(Optional.empty(), activityOf(
                SessionListModel.join(List.of(exited), MAIN, Map.of("claude-1", SessionActivity.BUSY)), "exited"),
                "an EXITED session renders lifecycle-only even with a busy state file left behind");
    }

    @Test
    void claudeSessionJoinsActivityThroughSinkEvents() {
        ManagedAgentSession claude = session(MAIN, AgentKind.CLAUDE, "claude", SessionStatus.RUNNING,
                Optional.of("claude-1"), T0);
        ManualExecutor refreshes = new ManualExecutor();
        SessionListModel model = new SessionListModel(MAIN, () -> List.of(claude), refreshes);

        // What the lifecycle monitor delivers for the tick's poll result.
        model.sessionActivityChanged(claude.id(), Optional.of(SessionActivity.BUSY));
        assertEquals(1, refreshes.size(), "one queued coalesced rebuild");
        refreshes.runOne();

        assertEquals(Optional.of(SessionActivity.BUSY), activityOf(model.rows(), "claude"));
        // The session reported nothing on a later poll (empty optional is
        // the no-state-file / lifecycle-only signal, never a fabricated one).
        model.sessionActivityChanged(claude.id(), Optional.empty());
        refreshes.runOne();
        assertEquals(Optional.empty(), activityOf(model.rows(), "claude"));
    }

    // ---- AC18 (display half): acknowledged attention reads idle -------------

    @Test
    void acknowledgedAttentionReadsIdle() {
        ManagedAgentSession claude = session(MAIN, AgentKind.CLAUDE, "claude", SessionStatus.RUNNING,
                Optional.of("claude-1"), T0);
        List<ManagedAgentSession> sessions = new ArrayList<>(List.of(claude));
        ManualExecutor refreshes = new ManualExecutor();
        // The supplier reads the "persisted state" fixture live, so a status
        // change (as the session service would persist it) is picked up by
        // the next coalesced rebuild.
        SessionListModel model = new SessionListModel(MAIN, () -> sessions, refreshes);

        // Before the user looks: the hook wrote "attention".
        model.sessionActivityChanged(claude.id(), Optional.of(SessionActivity.NEEDS_ATTENTION));
        refreshes.runOne();
        assertEquals("Running · needs attention",
                SessionDots.statusText(claude.status(), activityOf(model.rows(), "claude")));

        // The watcher's acknowledge (driven by the monitor's tabFocused)
        // makes the next poll report IDLE — its own applyAcknowledgement
        // semantics; this pins the display half: what the watcher reports
        // after the acknowledgement is shown verbatim, attention is not
        // sticky in the list.
        model.sessionActivityChanged(claude.id(), Optional.of(SessionActivity.IDLE));
        refreshes.runOne();
        assertEquals("Running · idle",
                SessionDots.statusText(claude.status(), activityOf(model.rows(), "claude")));
    }

    // ---- AC25 (view half): repository filtering ------------------------------

    @Test
    void worktreeSessionsStayOutOfTheMainProjectList() {
        ManagedAgentSession mainSession = session(MAIN, AgentKind.CLAUDE, "main", SessionStatus.RUNNING,
                Optional.of("claude-1"), T0);
        ManagedAgentSession worktreeSession = session(WORKTREE, AgentKind.PI, "worktree", SessionStatus.RUNNING,
                Optional.empty(), T0);

        List<SessionListModel.Row> mainRows = SessionListModel.join(
                List.of(mainSession, worktreeSession), MAIN, Map.of());
        assertEquals(List.of("main"), names(mainRows),
                "a session registered under the worktree's repository does not appear in the main project's list");

        List<SessionListModel.Row> worktreeRows = SessionListModel.join(
                List.of(mainSession, worktreeSession), WORKTREE, Map.of());
        assertEquals(List.of("worktree"), names(worktreeRows),
                "the worktree project's own list shows its session");
    }

    // ---- ordering -------------------------------------------------------------

    @Test
    void newestOpenedSessionSortsFirst() {
        ManagedAgentSession oldest = session(MAIN, AgentKind.PI, "oldest", SessionStatus.INACTIVE,
                Optional.empty(), T0);
        ManagedAgentSession newest = session(MAIN, AgentKind.PI, "newest", SessionStatus.INACTIVE,
                Optional.empty(), T0.plusSeconds(600));
        ManagedAgentSession middle = session(MAIN, AgentKind.PI, "middle", SessionStatus.INACTIVE,
                Optional.empty(), T0.plusSeconds(300));

        assertEquals(List.of("newest", "middle", "oldest"),
                names(SessionListModel.join(List.of(oldest, newest, middle), MAIN, Map.of())));

        // Equal lastOpenedAt falls through to creation time: the order is
        // total, so identical data always rebuilds identically.
        ManagedAgentSession createdLater = session(MAIN, AgentKind.PI, "created-later", SessionStatus.INACTIVE,
                Optional.empty(), T0.plusSeconds(60))
                .toBuilder().createdAt(T0.plusSeconds(30)).build();
        ManagedAgentSession createdEarlier = session(MAIN, AgentKind.PI, "created-earlier", SessionStatus.INACTIVE,
                Optional.empty(), T0.plusSeconds(60));
        List<SessionListModel.Row> tie = SessionListModel.join(
                List.of(createdEarlier, createdLater), MAIN, Map.of());
        assertEquals(2, tie.size());
        assertEquals("created-later", tie.get(0).session().displayName(),
                "the session created later sorts first when lastOpenedAt ties");
    }

    // ---- coalescing: N events, one rebuild ------------------------------------

    @Test
    void eventBatchesCollapseIntoOneRebuild() {
        ManagedAgentSession claude = session(MAIN, AgentKind.CLAUDE, "claude", SessionStatus.RUNNING,
                Optional.of("claude-1"), T0);
        ManagedAgentSession pi = session(MAIN, AgentKind.PI, "pi", SessionStatus.INACTIVE,
                Optional.empty(), T0);
        List<ManagedAgentSession> sessions = new ArrayList<>(List.of(claude, pi));
        ManualExecutor refreshes = new ManualExecutor();
        SessionListModel model = new SessionListModel(MAIN, () -> sessions, refreshes);
        AtomicInteger rebuilds = new AtomicInteger();
        model.addListener(rebuilds::incrementAndGet);

        model.refreshNow();
        assertEquals(1, rebuilds.get(), "the initial fill fires once");

        // A burst: activity for the Claude session, a status change for the
        // Pi session (as the session service's sink persists it), and an
        // activity flip back — three events, one queued rebuild.
        model.sessionActivityChanged(claude.id(), Optional.of(SessionActivity.BUSY));
        sessions.set(1, pi.withStatus(SessionStatus.RUNNING));
        model.sessionExited(pi.id(), false);
        model.sessionActivityChanged(claude.id(), Optional.of(SessionActivity.IDLE));
        assertEquals(1, refreshes.size(), "the whole batch queued exactly one rebuild");
        refreshes.runOne();

        assertEquals(2, rebuilds.get(), "the batch produced one listener notification");
        assertEquals(Optional.of(SessionActivity.IDLE), activityOf(model.rows(), "claude"));
        assertEquals(SessionStatus.RUNNING, statusOf(model.rows(), "pi"),
                "the exit event's status change (persisted by the service sink) is in the rebuilt rows");

        // And the next single event queues exactly one again.
        model.tabFocused(pi.id());
        model.sessionActivityChanged(pi.id(), Optional.empty());
        assertEquals(1, refreshes.size());
    }

    @Test
    void unchangedRowsDoNotRefire() {
        ManagedAgentSession claude = session(MAIN, AgentKind.CLAUDE, "claude", SessionStatus.RUNNING,
                Optional.of("claude-1"), T0);
        SessionListModel model = new SessionListModel(MAIN, () -> List.of(claude), Runnable::run);
        AtomicInteger rebuilds = new AtomicInteger();
        model.addListener(rebuilds::incrementAndGet);

        model.refreshNow();
        model.refreshNow();
        model.refreshNow();
        assertEquals(1, rebuilds.get(),
                "a refresh that changes nothing fires no listener (repaint traffic never scales with events)");
    }

    // ---- C15 vocabulary spot-checks -------------------------------------------

    @Test
    void errorStatusesAreNeverReadAsIdle() {
        // kb/repo-menu-bulk-session-actions: "inactive" deliberately excludes
        // ERROR — those rows may be worth inspecting before deleting.
        for (SessionStatus error : List.of(
                SessionStatus.FAILED, SessionStatus.MISSING_WORKING_DIRECTORY, SessionStatus.UNSUPPORTED_AGENT)) {
            String text = SessionDots.statusText(error, Optional.empty());
            assertTrue(!text.contains("nactive") && !text.contains("xited"),
                    error + " must not read as inactive: " + text);
            assertEquals(SessionDots.dotColor(SessionStatus.FAILED, Optional.empty()),
                    SessionDots.dotColor(error, Optional.empty()),
                    error + " shares the error color, distinct from idle");
        }
        assertTrue(!SessionDots.dotColor(SessionStatus.INACTIVE, Optional.empty())
                        .equals(SessionDots.dotColor(SessionStatus.FAILED, Optional.empty())),
                "the idle dot is visually distinct from the error dot");
    }

    // ---- fixtures ---------------------------------------------------------------

    private static ManagedAgentSession session(RepositoryId repository, AgentKind kind, String name,
            SessionStatus status, Optional<String> agentSessionId, Instant openedAt) {
        return new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), repository, name,
                new AgentBinding(kind, agentSessionId, Optional.empty()),
                SessionWorkspace.inRepository(Path.of("/repo/" + name)),
                openedAt.minusSeconds(60))
                .status(status)
                .lastOpenedAt(openedAt)
                .build();
    }

    private static Optional<SessionActivity> activityOf(List<SessionListModel.Row> rows, String name) {
        for (SessionListModel.Row row : rows) {
            if (row.session().displayName().equals(name)) {
                return row.activity();
            }
        }
        throw new AssertionError("no row named " + name);
    }

    private static SessionStatus statusOf(List<SessionListModel.Row> rows, String name) {
        for (SessionListModel.Row row : rows) {
            if (row.session().displayName().equals(name)) {
                return row.session().status();
            }
        }
        throw new AssertionError("no row named " + name);
    }

    private static List<String> names(List<SessionListModel.Row> rows) {
        return rows.stream().map(row -> row.session().displayName()).toList();
    }

    /** Runs only what the test runs, so coalescing is a queue-shape assertion, never a sleep. */
    private static final class ManualExecutor implements Executor {

        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            Objects.requireNonNull(command);
            queue.addLast(command);
        }

        int size() {
            return queue.size();
        }

        void runOne() {
            Runnable task = queue.pollFirst();
            assertTrue(task != null, "expected a queued refresh");
            task.run();
        }
    }
}
