package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.delayedtasks.TaskStoreWatcher.WatchedSnapshot;
import app.drydock.domain.*;
import app.drydock.git.GhCliService.OpenPullRequest;
import app.drydock.tasks.*;
import app.drydock.tasks.DelayedTaskTreeModel.*;
import app.drydock.tasks.TaskNode.*;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

import static app.drydock.tasks.TaskState.Presentation.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * T15b acceptance matrix for {@link DelayedTaskRows} and
 * {@link DelayedTaskTreeModel} (the T15a smoke, committed): D9-D11
 * placement and hints, idle/stopped membership, removed/foreign sessions
 * from the snapshot alone, broken/cyclic rows, stable ready-first
 * ordering, the views with text search, the fixed-clock age flag, and no
 * filesystem reads in derivation. Fixtures use the public constructors
 * with a fixed clock: no store, no files, no mocking.
 */
class DelayedTaskRowsTest {

    /** The injected clock; no row code reads the real one. */
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Instant YOUNG = NOW.minus(Duration.ofDays(1));
    private static final Instant OLD = NOW.minus(Duration.ofDays(15));
    private static final String FOREIGN = "11111111-1111-1111-1111-111111111111";
    private static final RepositoryId ALPHA = RepositoryId.newId();
    private static final RepositoryId BETA = RepositoryId.newId();
    private static final ManagedSessionId S1 = ManagedSessionId.newId();
    private static final ManagedSessionId S2 = ManagedSessionId.newId();
    private static final ManagedSessionId S3 = ManagedSessionId.newId();
    private static final ManagedSessionId S4 = ManagedSessionId.newId();
    private static final List<TaskDocument> DOCS = List.of(
            doc("ready-zebra", "Free the harbour", TaskStatus.OPEN, YOUNG, List.of(), List.of()),
            doc("ready-early", "Second ready task", TaskStatus.OPEN, YOUNG, List.of(), List.of()),
            doc("after-done", "After the finished one", TaskStatus.OPEN, YOUNG, List.of("done-solo"), List.of()),
            doc("done-solo", "Finished long ago", TaskStatus.DONE, OLD, List.of(), List.of()),
            doc("dropped-solo", "Abandoned plan", TaskStatus.DROPPED, YOUNG, List.of(), List.of()),
            doc("blocked-harbour", "Blocked behind harbour", TaskStatus.OPEN, YOUNG, List.of("ready-zebra"), List.of()),
            doc("broken-dream", "Broken dream", TaskStatus.OPEN, YOUNG, List.of("ghost-task"), List.of()),
            doc("cycle-one", "Cycle one", TaskStatus.OPEN, YOUNG, List.of("cycle-two"), List.of()),
            doc("cycle-two", "Cycle two", TaskStatus.OPEN, YOUNG, List.of("cycle-one"), List.of()),
            doc("cross-dependent", "Cross project dependant", TaskStatus.OPEN, YOUNG, List.of("beta:beta-blocker"), List.of()),
            doc(BETA, "beta-blocker", "Beta blocker", TaskStatus.OPEN, YOUNG, List.of(), List.of()),
            doc("underway", "Underway work", TaskStatus.STARTED, YOUNG, List.of(), List.of(S4.toString())),
            doc("review-attached", "Awaiting confirmation", TaskStatus.REVIEW, YOUNG, List.of(), List.of(S3.toString())),
            doc("done-attached", "Session finished chore", TaskStatus.DONE, YOUNG, List.of(), List.of(S2.toString())),
            doc("review-solo", "Lone review", TaskStatus.REVIEW, YOUNG, List.of(), List.of()),
            doc("foreign-task", "Lost session", TaskStatus.STARTED, YOUNG, List.of(), List.of(FOREIGN)),
            doc("age-13", "Thirteen days old", TaskStatus.OPEN, NOW.minus(Duration.ofDays(13)), List.of(), List.of()),
            doc("age-14", "Exactly fourteen days old", TaskStatus.OPEN, NOW.minus(Duration.ofDays(14)), List.of(), List.of()),
            doc("age-15", "Fifteen days old", TaskStatus.OPEN, OLD, List.of(), List.of()));
    private static final WatchedSnapshot WATCHED = new WatchedSnapshot(snapshot(DOCS), List.of(), null);
    private static final Set<String> LIVE = Set.of(S1.toString(), S2.toString(), S3.toString(), S4.toString());
    private static final Function<RepositoryId, String> SLUGS = repository ->
            repository.equals(ALPHA) ? "alpha" : repository.equals(BETA) ? "beta" : null;
    private static final List<TaskRow> SESSION_ROWS = List.of(
            row(S1, "session one", SessionStatus.RUNNING, Optional.of(SessionActivity.NEEDS_ATTENTION)),
            row(S2, "session two", SessionStatus.INACTIVE, Optional.empty()),
            row(S3, "session three", SessionStatus.INACTIVE, Optional.of(SessionActivity.IDLE)),
            row(S4, "session four", SessionStatus.INACTIVE, Optional.empty()));
    private static final List<DelayedTaskRow> ROWS = DelayedTaskRows.rows(WATCHED, LIVE, SLUGS, NOW);

    /** One task document; the scan's defaults would be a null status/done_by. */
    private static TaskDocument doc(RepositoryId repo, String id, String title, TaskStatus status,
            Instant created, List<String> after, List<String> sessions) {
        return new TaskDocument(TaskId.of(id), repo, title, status, null, Optional.of(created),
                after, sessions, "", "");
    }

    private static TaskDocument doc(String id, String title, TaskStatus status, Instant created,
            List<String> after, List<String> sessions) {
        return doc(ALPHA, id, title, status, created, after, sessions);
    }

    /** The snapshot as the scan would publish it: both indexes and both repositories. */
    private static TaskSnapshot snapshot(List<TaskDocument> docs) {
        Map<TaskId, TaskDocument> byId = new LinkedHashMap<>();
        Map<String, List<TaskDocument>> bySession = new LinkedHashMap<>();
        for (TaskDocument document : docs) {
            byId.put(document.id(), document);
            document.sessions().forEach(session -> bySession
                    .computeIfAbsent(session, any -> new ArrayList<>()).add(document));
        }
        return new TaskSnapshot(docs, List.of(ALPHA, BETA), byId, bySession, List.of(), List.of());
    }

    /** One existing session row: running with attention, or idle/stopped. */
    private static TaskRow row(ManagedSessionId id, String name, SessionStatus status,
            Optional<SessionActivity> activity) {
        ManagedAgentSession session = new ManagedAgentSession(id, RepositoryId.newId(), name,
                AgentBinding.unlaunched(AgentKind.CLAUDE), new SessionWorkspace(
                        Path.of("/tmp", "t15b-" + name.replace(' ', '-')).toAbsolutePath(),
                        Optional.empty(), false),
                status, YOUNG, YOUNG, Optional.empty(), PrLink.none(), false, Optional.empty(), false);
        boolean running = status == SessionStatus.RUNNING;
        return new TaskRow(session, activity, running, Optional.empty(), TaskPlacement.of(running,
                Optional.empty(), Optional.empty(), session.workingDirectory(), Optional.empty()),
                session.readOnly(), false);
    }

    private static Derivation derive(View view, String filter) {
        return DelayedTaskTreeModel.derive(new Inputs(WATCHED, SESSION_ROWS, LIVE, SLUGS, view, filter, NOW));
    }

    /**
     * A row carries its repository's display slug from the same mapping the
     * graph resolves references with, so its tooltip can name the project
     * across the inbox's state groups. A mapping that names no repository
     * yields no hint, never an invented name.
     */
    @Test
    void rowsCarryTheirProjectSlugFromTheMapping() {
        assertEquals("alpha", byTitle(ROWS, "Free the harbour").project());
        assertEquals("beta", byTitle(ROWS, "Beta blocker").project());
        List<DelayedTaskRow> unnamed = DelayedTaskRows.rows(WATCHED, LIVE, repository -> "  ", NOW);
        assertNull(byTitle(unnamed, "Free the harbour").project());
        List<DelayedTaskRow> unknown = DelayedTaskRows.rows(WATCHED, LIVE, repository -> null, NOW);
        assertNull(byTitle(unknown, "Free the harbour").project());
    }

    @Test
    void linkingAPullRequestPreservesTheProjectSlug() {
        DelayedTaskRow row = byTitle(ROWS, "Free the harbour");
        OpenPullRequest pr = new OpenPullRequest(102, "Free the harbour", "dd/ready-zebra", "main", false,
                Optional.empty(), Optional.of("https://github.com/o/r/pull/102"));
        DelayedTaskRow linked = row.withPullRequest(pr);
        assertEquals("alpha", linked.project());
        assertEquals(Optional.of(pr), linked.pullRequest());
        assertTrue(row.pullRequest().isEmpty(), "linking leaves the source row unchanged");
    }

    /** Matrix 1 and 4: D9-D11 placement, hints, broken and cyclic rows. */
    @Test
    void placementCarriesPresentationsAndHints() {
        assertEquals(19, ROWS.size());
        assertEquals(READY, byTitle(ROWS, "Free the harbour").presentation());
        assertEquals(READY, byTitle(ROWS, "After the finished one").presentation());
        DelayedTaskRow blocked = byTitle(ROWS, "Blocked behind harbour");
        assertEquals(BLOCKED, blocked.presentation());
        assertEquals("blocked by 'Free the harbour' (open)", blocked.blockers().get(0));
        DelayedTaskRow broken = byTitle(ROWS, "Broken dream");
        assertEquals(BLOCKED, broken.presentation());
        assertTrue(broken.blockers().get(0).contains("broken prerequisite reference 'ghost-task'"),
                broken.blockers().get(0));
        assertEquals(BLOCKED, byTitle(ROWS, "Cycle one").presentation());
        assertEquals(BLOCKED, byTitle(ROWS, "Cycle two").presentation());
        assertTrue(byTitle(ROWS, "Cycle one").blockers().get(0).contains("in a dependency cycle"));
        DelayedTaskRow cross = byTitle(ROWS, "Cross project dependant");
        assertEquals(BLOCKED, cross.presentation());
        assertTrue(cross.blockers().get(0).contains("blocked by 'Beta blocker'"), cross.blockers().get(0));
        assertEquals(ALPHA, cross.repository());
        assertEquals(BETA, byTitle(ROWS, "Beta blocker").repository());
        DelayedTaskRow underway = byTitle(ROWS, "Underway work");
        assertEquals(STARTED, underway.presentation());
        assertEquals(List.of(S4.toString()), underway.liveSessions());
        assertEquals(REVIEW, byTitle(ROWS, "Awaiting confirmation").presentation());
        assertEquals(TERMINAL, byTitle(ROWS, "Finished long ago").presentation());
        assertEquals(TERMINAL, byTitle(ROWS, "Abandoned plan").presentation());
        DelayedTaskRow foreign = byTitle(ROWS, "Lost session");
        assertEquals(INTERRUPTED, foreign.presentation());
        assertEquals(Optional.of(FOREIGN), foreign.lastSession());
    }

    /** Matrix 2: membership survives idle and stopped sessions (no live-catalog change). */
    @Test
    void membershipSurvivesIdleAndStoppedSessions() {
        Derivation all = derive(View.ALL, "");
        SessionTasks s4 = wrapper(all, S4).orElseThrow();
        assertEquals(SessionStatus.INACTIVE, s4.session().session().status());
        assertFalse(s4.session().running());
        assertEquals(List.of("Underway work"), titles(s4));
        SessionTasks s3 = wrapper(all, S3).orElseThrow();
        assertEquals(Optional.of(SessionActivity.IDLE), s3.session().activity());
        assertEquals(List.of("Awaiting confirmation"), titles(s3));
        assertTrue(wrapper(all, S1).orElseThrow().children().isEmpty());
        assertTrue(titles(wrapper(all, S2).orElseThrow()).isEmpty());
        assertFalse(sectionTitles(all).contains("Session finished chore"));
        assertTrue(all.nodes().stream().filter(SessionTasks.class::isInstance).map(SessionTasks.class::cast)
                .flatMap(node -> node.children().stream()).filter(DelayedTaskRow.class::isInstance)
                .map(DelayedTaskRow.class::cast).noneMatch(DelayedTaskRow::terminal));
    }

    /** The accepted deviation: assigned terminal children stay under their session in Completed, hidden in All. */
    @Test
    void completedViewKeepsAssignedTerminalUnderItsSession() {
        Derivation all = derive(View.ALL, "");
        assertEquals("Tasks", sectionOf(all).orElseThrow().label());
        assertTrue(wrapper(all, S2).orElseThrow().children().isEmpty());
        Derivation completed = derive(View.COMPLETED, "");
        assertEquals("Completed", sectionOf(completed).orElseThrow().label());
        assertEquals(List.of("Abandoned plan", "Finished long ago"), sectionTitles(completed));
        assertEquals(List.of("Session finished chore"), titles(wrapper(completed, S2).orElseThrow()));
        assertTrue(wrapper(completed, S3).isEmpty());
        assertTrue(wrapper(completed, S1).isEmpty());
    }

    /** Matrix 3: removed, historical and foreign sessions derive from the snapshot alone. */
    @Test
    void removedAndForeignSessionsStayDerivableFromTheSnapshotAlone() {
        Set<String> liveWithoutS4 = Set.of(S1.toString(), S2.toString(), S3.toString());
        List<DelayedTaskRow> withoutS4 = DelayedTaskRows.rows(WATCHED, liveWithoutS4, SLUGS, NOW);
        assertEquals(19, withoutS4.size());
        DelayedTaskRow underway = byTitle(withoutS4, "Underway work");
        assertEquals(INTERRUPTED, underway.presentation());
        assertEquals(Optional.of(S4.toString()), underway.lastSession());
        List<TaskRow> rows = SESSION_ROWS.stream().filter(row -> !row.session().id().equals(S4)).toList();
        Derivation removed = DelayedTaskTreeModel.derive(
                new Inputs(WATCHED, rows, liveWithoutS4, SLUGS, View.ALL, "", NOW));
        assertTrue(wrapper(removed, S4).isEmpty());
        assertTrue(sectionTitles(removed).contains("Underway work"));
        assertTrue(sectionTitles(removed).contains("Lost session"));
    }

    /** Matrix 5: ready-first order, task-id tie-break, stable across derivations. */
    @Test
    void orderingIsReadyFirstWithTaskIdTieBreak() {
        assertEquals(List.of(
                "after-done", "age-13", "age-14", "age-15", "beta-blocker", "ready-early", "ready-zebra",
                "blocked-harbour", "broken-dream", "cross-dependent", "cycle-one", "cycle-two",
                "review-attached", "review-solo", "foreign-task", "underway",
                "done-attached", "done-solo", "dropped-solo"),
                DelayedTaskRows.sorted(ROWS).stream().map(row -> row.id().text()).toList());
        assertEquals(DelayedTaskRows.sorted(ROWS), DelayedTaskRows.sorted(ROWS));
        assertEquals(derive(View.ALL, ""), derive(View.ALL, ""));
    }

    /** Matrix 6: All vs Tasks view contents and the text search. */
    @Test
    void viewsAndTextSearchFilter() {
        Derivation all = derive(View.ALL, "");
        Derivation tasks = derive(View.TASKS, "");
        assertEquals(14, sectionTitles(all).size());
        assertEquals(sectionTitles(all), sectionTitles(tasks));
        assertTrue(wrapper(tasks, S1).isEmpty());
        assertTrue(wrapper(tasks, S2).isEmpty());
        assertEquals(List.of("Underway work"), titles(wrapper(tasks, S4).orElseThrow()));
        assertEquals(List.of("Awaiting confirmation"), titles(wrapper(tasks, S3).orElseThrow()));
        assertEquals(List.of("Blocked behind harbour", "Free the harbour"),
                sectionTitles(derive(View.ALL, "HaRbOuR")));
        assertEquals(List.of("Free the harbour"), sectionTitles(derive(View.ALL, "ready-zebra")));
        assertEquals(List.of("Underway work"),
                titles(wrapper(derive(View.ALL, "session four"), S4).orElseThrow()));
        assertEquals(all.nodes(), derive(View.ALL, "").nodes());
    }

    /** Needs-you: running-with-attention, plus review making an IDLE parent eligible (D11). */
    @Test
    void needsYouIncludesIdleParentOnReview() {
        Derivation needs = derive(View.NEEDS_YOU, "");
        assertTrue(wrapper(needs, S1).isPresent());
        SessionTasks s3 = wrapper(needs, S3).orElseThrow();
        assertEquals(Optional.of(SessionActivity.IDLE), s3.session().activity());
        assertEquals(List.of("Awaiting confirmation"), titles(s3));
        assertTrue(wrapper(needs, S4).isEmpty());
        assertEquals(List.of("Lone review"), sectionTitles(needs));
    }

    /** Matrix 7: the 14-day age flag follows the injected clock (13/14/15-day boundaries). */
    @Test
    void staleFlagFollowsTheInjectedClockAtFourteenDays() {
        assertFalse(byTitle(ROWS, "Thirteen days old").stale());
        assertFalse(byTitle(ROWS, "Exactly fourteen days old").stale());
        assertTrue(byTitle(ROWS, "Fifteen days old").stale());
        assertTrue(byTitle(ROWS, "Finished long ago").stale());
        List<DelayedTaskRow> tomorrow = DelayedTaskRows.rows(WATCHED, LIVE, SLUGS, NOW.plus(Duration.ofDays(1)));
        assertTrue(byTitle(tomorrow, "Exactly fourteen days old").stale());
        assertFalse(byTitle(tomorrow, "Thirteen days old").stale());
    }

    /** Matrix 8: derivation needs no filesystem; the store root is mode 000. */
    @Test
    void derivationReadsNoFilesystem(@TempDir Path root) throws Exception {
        Derivation expected = derive(View.ALL, "");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(root);
        Files.setPosixFilePermissions(root, Set.of());
        try {
            assertThrows(IOException.class, () -> Files.list(root).close(),
                    "the premise: the root is unreadable now");
            assertEquals(19, DelayedTaskRows.rows(WATCHED, LIVE, SLUGS, NOW).size());
            assertEquals(expected, derive(View.ALL, ""));
        } finally {
            Files.setPosixFilePermissions(root, original);
        }
    }

    private static DelayedTaskRow byTitle(List<DelayedTaskRow> rows, String title) {
        return rows.stream().filter(row -> row.title().equals(title)).findFirst().orElseThrow();
    }

    private static Optional<SessionTasks> wrapper(Derivation derivation, ManagedSessionId session) {
        return derivation.nodes().stream().filter(SessionTasks.class::isInstance).map(SessionTasks.class::cast)
                .filter(node -> node.session().session().id().equals(session)).findFirst();
    }

    private static List<String> titles(SessionTasks wrapper) {
        return wrapper.children().stream().filter(DelayedTaskRow.class::isInstance)
                .map(DelayedTaskRow.class::cast).map(DelayedTaskRow::title).toList();
    }

    private static Optional<TasksSection> sectionOf(Derivation derivation) {
        return derivation.nodes().stream().filter(TasksSection.class::isInstance)
                .map(TasksSection.class::cast).findFirst();
    }

    private static List<String> sectionTitles(Derivation derivation) {
        return sectionOf(derivation).map(section -> section.children().stream()
                        .filter(DelayedTaskRow.class::isInstance).map(DelayedTaskRow.class::cast)
                        .map(DelayedTaskRow::title).sorted().toList())
                .orElse(List.of());
    }
}
