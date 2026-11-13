package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.tasks.InboxModel.GroupKind;
import app.drydock.intellij.tasks.InboxModel.Inbox;
import app.drydock.intellij.tasks.InboxModel.Inputs;
import app.drydock.intellij.tasks.InboxModel.Item;
import app.drydock.intellij.tasks.InboxModel.ProjectRef;
import app.drydock.intellij.tasks.InboxModel.Query;
import app.drydock.intellij.tasks.InboxModel.SessionItem;
import app.drydock.intellij.tasks.TaskNode.AddCurrentProject;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.projects.SharedProject;
import app.drydock.tasks.DoneBy;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.TaskState.Presentation;
import app.drydock.tasks.TaskState.TaskView;
import app.drydock.tasks.TaskStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link InboxModel}: grouping, ordering, truncation, the query and the project counts. */
class InboxModelTest {

    private static final Instant NOW = Instant.parse("2026-11-13T15:00:00Z");
    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final Path ALPHA = Path.of("/repos/alpha").toAbsolutePath();
    private static final Path BETA = Path.of("/repos/beta").toAbsolutePath();
    private static final Path QUIET = Path.of("/repos/quiet").toAbsolutePath();
    private static final Path LOOSE = Path.of("/repos/loose").toAbsolutePath();
    private static final SharedProject ALPHA_PROJECT = project(ALPHA, "alpha");
    private static final SharedProject BETA_PROJECT = project(BETA, "beta");
    private static final SharedProject QUIET_PROJECT = project(QUIET, "quiet");
    private static final RepositoryId ALPHA_TASKS = RepositoryId.newId();

    /** The nodes, tasks and documents one test builds its inbox from. */
    private final List<TaskNode> alphaRows = new ArrayList<>();
    private final List<TaskNode> betaRows = new ArrayList<>();
    private final List<TaskNode> topRows = new ArrayList<>();
    private final List<DelayedTaskRow> tasks = new ArrayList<>();
    private final Map<TaskId, TaskDocument> documents = new HashMap<>();

    private static SharedProject project(Path root, String name) {
        return new SharedProject(RepositoryId.newId(), root, name, NOW, Optional.empty());
    }

    private static TaskRow session(String name, boolean running, Optional<SessionActivity> activity,
                                   boolean flagged, Instant opened) {
        ManagedAgentSession session = new ManagedAgentSession(ManagedSessionId.newId(), RepositoryId.newId(), name,
                AgentBinding.unlaunched(AgentKind.CLAUDE), new SessionWorkspace(
                        Path.of("/tmp", "inbox-" + name.replace(' ', '-')).toAbsolutePath(), Optional.empty(),
                        false),
                running ? SessionStatus.RUNNING : SessionStatus.INACTIVE, opened, opened, Optional.empty(),
                PrLink.none(), false, Optional.empty(), false).withFlagged(flagged);
        return new TaskRow(session, activity, running, Optional.empty(), TaskPlacement.of(running,
                Optional.empty(), Optional.empty(), session.workingDirectory(), Optional.empty()),
                session.readOnly(), false);
    }

    private static TaskRow running(String name, Instant opened) {
        return session(name, true, Optional.of(SessionActivity.BUSY), false, opened);
    }

    private DelayedTaskRow task(String title, Presentation presentation, TaskStatus status,
                                Optional<Instant> doneAt, List<String> liveSessions) {
        TaskId id = TaskId.of(title.toLowerCase().replace(' ', '-'));
        DelayedTaskRow row = new DelayedTaskRow(new TaskView(id, presentation, List.of(), liveSessions), title,
                ALPHA_TASKS, liveSessions);
        documents.put(id, new TaskDocument(id, ALPHA_TASKS, title, status, DoneBy.HUMAN, Optional.empty(),
                List.of(), liveSessions, List.of(), doneAt, "", ""));
        tasks.add(row);
        return row;
    }

    private DelayedTaskRow task(String title, Presentation presentation) {
        return task(title, presentation, TaskStatus.OPEN, Optional.empty(), List.of());
    }

    private Inbox inbox(Query query) {
        List<TaskNode> nodes = new ArrayList<>(topRows);
        nodes.add(new ProjectRow(ALPHA_PROJECT, false, false, alphaRows, List.of(), 0));
        nodes.add(new ProjectRow(BETA_PROJECT, false, false, betaRows, List.of(), 0));
        nodes.add(new ProjectRow(QUIET_PROJECT, false, false, List.of(), List.of(), 0));
        return InboxModel.build(new Inputs(nodes, List.of(ALPHA_PROJECT, BETA_PROJECT, QUIET_PROJECT), tasks,
                documents, Map.of(ALPHA_TASKS, ALPHA), NOW, ZONE), query);
    }

    private static List<String> titles(Inbox inbox, GroupKind kind) {
        return inbox.groups().stream().filter(group -> group.kind() == kind)
                .flatMap(group -> group.items().stream()).map(Item::title).toList();
    }

    private static List<GroupKind> kinds(Inbox inbox) {
        return inbox.groups().stream().map(InboxModel.Group::kind).toList();
    }

    @Test
    void sessionsAndTasksLandInTheirGroupsInDisplayOrder() {
        alphaRows.add(session("Waiting", true, Optional.of(SessionActivity.NEEDS_ATTENTION), false, NOW));
        alphaRows.add(session("Flagged stopped", false, Optional.empty(), true, NOW));
        alphaRows.add(running("Working", NOW));
        betaRows.add(session("Stopped", false, Optional.of(SessionActivity.NEEDS_ATTENTION), false, NOW));
        task("Blocked one", Presentation.BLOCKED);
        task("Review one", Presentation.REVIEW);
        task("Ready one", Presentation.READY);
        task("Interrupted one", Presentation.INTERRUPTED);
        task("Done now", Presentation.TERMINAL, TaskStatus.DONE, Optional.of(NOW.minusSeconds(3600)), List.of());
        task("Done yesterday", Presentation.TERMINAL, TaskStatus.DONE,
                Optional.of(Instant.parse("2026-11-12T23:59:00Z")), List.of());
        task("Done undated", Presentation.TERMINAL, TaskStatus.DONE, Optional.empty(), List.of());
        task("Dropped", Presentation.TERMINAL, TaskStatus.DROPPED, Optional.empty(), List.of());

        Inbox inbox = inbox(Query.NONE);

        assertEquals(List.of(GroupKind.NEEDS_YOU, GroupKind.FLAGGED, GroupKind.RUNNING, GroupKind.BLOCKED,
                GroupKind.IN_REVIEW, GroupKind.READY, GroupKind.IDLE, GroupKind.DONE), kinds(inbox));
        assertEquals(List.of("Waiting"), titles(inbox, GroupKind.NEEDS_YOU));
        assertEquals(List.of("Flagged stopped"), titles(inbox, GroupKind.FLAGGED));
        assertEquals(List.of("Working"), titles(inbox, GroupKind.RUNNING));
        assertEquals(List.of("Ready one", "Interrupted one"), titles(inbox, GroupKind.READY));
        assertEquals(List.of("Stopped"), titles(inbox, GroupKind.IDLE),
                "a stopped session's leftover attention is not Needs you");
        assertEquals(List.of("Done now"), titles(inbox, GroupKind.DONE), "only done with a done_at today");
        assertEquals("Done today", inbox.groups().getLast().label());
    }

    @Test
    void aStartedTaskRidesOnItsSessionAndAReviewTaskMakesItNeedYou() {
        TaskRow host = running("Host", NOW);
        TaskRow reviewed = running("Reviewed", NOW);
        alphaRows.add(host);
        alphaRows.add(reviewed);
        task("Started under host", Presentation.STARTED, TaskStatus.STARTED, Optional.empty(),
                List.of(host.session().id().toString()));
        task("Awaiting confirmation", Presentation.REVIEW, TaskStatus.REVIEW, Optional.empty(),
                List.of(reviewed.session().id().toString()));
        task("Started elsewhere", Presentation.STARTED, TaskStatus.STARTED, Optional.empty(),
                List.of(ManagedSessionId.newId().toString()));

        Inbox inbox = inbox(Query.NONE);

        assertEquals(List.of("Reviewed"), titles(inbox, GroupKind.NEEDS_YOU));
        assertEquals(List.of("Awaiting confirmation"), titles(inbox, GroupKind.IN_REVIEW));
        assertEquals(List.of("Host", "Started elsewhere"), titles(inbox, GroupKind.RUNNING),
                "an attached started task is no row of its own; one with no session row is");
        SessionItem hostItem = (SessionItem) inbox.groups().stream()
                .filter(group -> group.kind() == GroupKind.RUNNING).findFirst().orElseThrow().items().get(0);
        assertEquals(List.of("Started under host"), hostItem.taskTitles());
    }

    @Test
    void sessionsAreNewestFirstAndGroupsTruncateExceptNeedsYou() {
        for (int i = 0; i < 6; i++) {
            alphaRows.add(running("Run " + i, NOW.minusSeconds(60L * (6 - i))));
            alphaRows.add(session("Wait " + i, true, Optional.of(SessionActivity.NEEDS_ATTENTION), false, NOW));
        }
        Inbox inbox = inbox(Query.NONE);
        InboxModel.Group runningGroup = inbox.groups().get(1);
        InboxModel.Group needsYou = inbox.groups().get(0);

        assertEquals(List.of("Run 5", "Run 4", "Run 3", "Run 2", "Run 1", "Run 0"),
                runningGroup.items().stream().map(Item::title).toList());
        assertEquals(4, runningGroup.shown(false).size());
        assertEquals(2, runningGroup.hidden(false));
        assertEquals(6, runningGroup.shown(true).size());
        assertEquals(6, needsYou.shown(false).size(), "Needs you is never truncated");
        assertEquals(0, needsYou.hidden(false));
    }

    @Test
    void theQueryNarrowsByProjectTextAndGroupWords() {
        alphaRows.add(running("Fix the parser", NOW));
        betaRows.add(running("Fix the build", NOW));
        betaRows.add(session("Old parser work", false, Optional.empty(), false, NOW));
        task("Parser task", Presentation.READY);

        assertEquals(List.of("Fix the parser"), titles(inbox(new Query(Optional.of(ALPHA), "")), GroupKind.RUNNING));
        assertEquals(List.of("Parser task"), titles(inbox(new Query(Optional.of(ALPHA), "")), GroupKind.READY),
                "a task belongs to the project its repository resolves to");
        assertEquals(List.of(), titles(inbox(new Query(Optional.of(BETA), "")), GroupKind.READY));

        Inbox parser = inbox(new Query(Optional.empty(), "PARSER"));
        assertEquals(List.of("Fix the parser"), titles(parser, GroupKind.RUNNING));
        assertEquals(List.of("Old parser work"), titles(parser, GroupKind.IDLE));

        Inbox idleParser = inbox(new Query(Optional.empty(), "is:idle parser"));
        assertEquals(List.of(GroupKind.IDLE), kinds(idleParser));
        assertTrue(inbox(new Query(Optional.of(QUIET), "")).isEmpty());
    }

    @Test
    void isDoneShowsEveryDoneTaskUnderItsOwnLabel() {
        task("Done now", Presentation.TERMINAL, TaskStatus.DONE, Optional.of(NOW), List.of());
        task("Done undated", Presentation.TERMINAL, TaskStatus.DONE, Optional.empty(), List.of());
        task("Dropped", Presentation.TERMINAL, TaskStatus.DROPPED, Optional.empty(), List.of());
        task("Ready one", Presentation.READY);

        Inbox inbox = inbox(new Query(Optional.empty(), "is:done"));

        assertEquals(List.of(GroupKind.DONE), kinds(inbox));
        assertEquals("Done", inbox.groups().get(0).label());
        assertEquals(List.of("Done now", "Done undated"), titles(inbox, GroupKind.DONE));
    }

    @Test
    void projectCountsIgnoreTheQueryAndCountOnlyActiveGroups() {
        alphaRows.add(running("Working", NOW));
        betaRows.add(session("Stopped", false, Optional.empty(), false, NOW));
        task("Ready one", Presentation.READY);
        task("Done now", Presentation.TERMINAL, TaskStatus.DONE, Optional.of(NOW), List.of());

        Inbox inbox = inbox(new Query(Optional.of(BETA), "nothing matches this"));

        assertEquals(List.of(new InboxModel.Suggestion(new ProjectRef(ALPHA, "alpha"), 2),
                new InboxModel.Suggestion(new ProjectRef(BETA, "beta"), 0),
                new InboxModel.Suggestion(new ProjectRef(QUIET, "quiet"), 0)), inbox.projects());
        assertEquals(2, inbox.quietProjects(), "Idle and Done do not count as activity");
        assertEquals(List.of("alpha"), inbox.suggestions(" AL").stream().map(s -> s.project().name()).toList());
        assertEquals(3, inbox.suggestions("").size());
    }

    @Test
    void theUnregisteredCurrentProjectsRowsCarryItsName() {
        topRows.add(new AddCurrentProject(LOOSE, "loose"));
        topRows.add(running("Loose work", NOW));

        Inbox inbox = inbox(Query.NONE);

        Item item = inbox.groups().get(0).items().get(0);
        assertEquals(Optional.of(new ProjectRef(LOOSE, "loose")), item.project());
        assertEquals(3, inbox.projects().size(), "suggestions list registered projects only");
    }

    @Test
    void titleTextDropsOnlyTheGroupWords() {
        assertEquals("parser fix", InboxModel.titleText("  is:idle parser IS:Done fix "));
        assertEquals("is:nonsense", InboxModel.titleText("is:nonsense"), "an unknown group word is text");
        assertEquals("", InboxModel.titleText(null));
    }

    @Test
    void projectPrefixReadsTheTextAfterProjectColon() {
        assertEquals(Optional.of("dry"), InboxModel.projectPrefix("project:dry"));
        assertEquals(Optional.of(""), InboxModel.projectPrefix("  Project:"));
        assertEquals(Optional.empty(), InboxModel.projectPrefix("proj"));
        assertEquals(Optional.empty(), InboxModel.projectPrefix(null));
    }
}
