package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.babysit.BabysitPairing;
import app.drydock.babysit.BabysitState;
import app.drydock.babysit.Escalation;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.GhCliService;
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
import java.util.UUID;

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
                GroupKind.IN_REVIEW, GroupKind.READY, GroupKind.IDLE, GroupKind.DONE), kinds(inbox),
                "no babysitter, no Babysitting group: an empty group is never shown");
        assertEquals(List.of("Waiting"), titles(inbox, GroupKind.NEEDS_YOU));
        assertEquals(List.of("Flagged stopped"), titles(inbox, GroupKind.FLAGGED));
        assertEquals(List.of("Working"), titles(inbox, GroupKind.RUNNING));
        assertEquals(List.of("Ready one", "Interrupted one"), titles(inbox, GroupKind.READY));
        assertEquals(List.of("Stopped"), titles(inbox, GroupKind.IDLE),
                "a stopped session's leftover attention is not Needs you");
        assertEquals(List.of("Done now"), titles(inbox, GroupKind.DONE), "only done with a done_at today");
        assertEquals("Done today", inbox.groups().getLast().label());
    }

    /** A review task is its own In-review row and leaves its session's group to the dot. */
    @Test
    void aStartedTaskRidesOnItsSessionAndAReviewTaskDoesNotPinIt() {
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

        assertTrue(inbox.groups().stream().noneMatch(group -> group.kind() == GroupKind.NEEDS_YOU),
                "a blue-dot session is not Needs you, review child or not");
        assertEquals(List.of("Host", "Reviewed", "Started elsewhere"), titles(inbox, GroupKind.RUNNING),
                "the reviewed session runs on by its dot; the review task is a row of its own");
        assertEquals(List.of("Awaiting confirmation"), titles(inbox, GroupKind.IN_REVIEW));
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
        assertEquals("dark mode fix", InboxModel.titleText("\"dark mode\" fix"));
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

    // -- babysitting ------------------------------------------------------

    /** The session's row rebuilt with its part in a babysitting engagement. */
    private static TaskRow withBabysit(TaskRow row, SessionBabysit part) {
        return new TaskRow(row.session(), row.activity(), row.running(), row.branch(), row.placement(),
                row.readOnly(), row.folderMissing(), row.resumable(), row.branchPullRequest(), Optional.of(part));
    }

    /** {@code watched} and {@code babysitter} paired in {@code state}, with {@code escalations}. */
    private static Map<ManagedSessionId, SessionBabysit> paired(TaskRow watched, TaskRow babysitter,
                                                                BabysitState state, boolean escalated) {
        BabysitPairing pairing = new BabysitPairing(babysitter.session().id(), watched.session().id(),
                watched.session().repositoryId(), 12, state);
        List<Escalation> escalations = escalated
                ? List.of(new Escalation(UUID.randomUUID(), pairing.watchedId(), pairing.babysitterId(), 12,
                        "Needs a design call", NOW, false))
                : List.of();
        return SessionBabysit.join(List.of(pairing), escalations);
    }

    @Test
    void anActiveBabysitterGoesToBabysittingBeforeEveryOtherCheck() {
        // Running, waiting for the user AND flagged: every other group would
        // take it, Babysitting takes it first.
        TaskRow babysitter = session("babysit PR #12", true, Optional.of(SessionActivity.NEEDS_ATTENTION), true, NOW);
        TaskRow watched = running("Watched", NOW);
        Map<ManagedSessionId, SessionBabysit> parts = paired(watched, babysitter, BabysitState.WATCHING, false);
        alphaRows.add(withBabysit(babysitter, parts.get(babysitter.session().id())));
        alphaRows.add(withBabysit(watched, parts.get(watched.session().id())));
        task("Ready one", Presentation.READY);
        betaRows.add(session("Stopped", false, Optional.empty(), false, NOW));

        Inbox inbox = inbox(Query.NONE);

        assertEquals(List.of(GroupKind.RUNNING, GroupKind.READY, GroupKind.BABYSITTING, GroupKind.IDLE),
                kinds(inbox), "Babysitting sits between Ready to start and Idle");
        assertEquals(List.of("babysit PR #12"), titles(inbox, GroupKind.BABYSITTING));
        assertEquals(List.of("Watched"), titles(inbox, GroupKind.RUNNING),
                "a watched session with nothing to say stays where its dot puts it");
        assertEquals("Babysitting", inbox.groups().get(2).label());
        assertEquals(List.of(GroupKind.BABYSITTING), kinds(inbox(new Query(Optional.empty(), "is:babysit"))));
    }

    @Test
    void aStoppedBabysitterIsAnOrdinarySessionAgain() {
        TaskRow babysitter = session("babysit PR #12", false, Optional.empty(), false, NOW);
        TaskRow watched = session("Watched", false, Optional.empty(), false, NOW);
        Map<ManagedSessionId, SessionBabysit> parts = paired(watched, babysitter, BabysitState.STOPPED, false);
        alphaRows.add(withBabysit(babysitter, parts.get(babysitter.session().id())));
        alphaRows.add(watched);

        Inbox inbox = inbox(Query.NONE);

        assertEquals(List.of(GroupKind.IDLE), kinds(inbox));
        assertEquals(List.of("babysit PR #12", "Watched"), titles(inbox, GroupKind.IDLE));
    }

    @Test
    void anEscalatedWatchedSessionNeedsYouAndTheBabysitterIsNoActivity() {
        TaskRow babysitter = session("babysit PR #12", true, Optional.of(SessionActivity.IDLE), false, NOW);
        TaskRow watched = session("Watched", false, Optional.empty(), false, NOW);
        Map<ManagedSessionId, SessionBabysit> parts = paired(watched, babysitter, BabysitState.ESCALATED, true);
        betaRows.add(withBabysit(babysitter, parts.get(babysitter.session().id())));
        alphaRows.add(withBabysit(watched, parts.get(watched.session().id())));

        Inbox inbox = inbox(Query.NONE);

        assertEquals(List.of(GroupKind.NEEDS_YOU, GroupKind.BABYSITTING), kinds(inbox));
        assertEquals(List.of("Watched"), titles(inbox, GroupKind.NEEDS_YOU),
                "a stopped watched session its babysitter escalated needs the human");
        assertEquals(2, inbox.quietProjects(), "a project with only a babysitter is quiet");
        assertEquals(List.of("alpha"), inbox.projects().stream().filter(suggestion -> suggestion.active() > 0)
                .map(suggestion -> suggestion.project().name()).toList());
    }

    // -- the pr: and issue: terms -----------------------------------------

    /** The session's row rebuilt with a branch pull request attached. */
    private static TaskRow withBranchPr(TaskRow row, GhCliService.BranchPullRequest pr) {
        return new TaskRow(row.session(), row.activity(), row.running(), row.branch(), row.placement(),
                row.readOnly(), row.folderMissing(), row.resumable(), Optional.of(pr));
    }

    /** The task row rebuilt with a mirror list attached. */
    private static DelayedTaskRow withMirrors(DelayedTaskRow row, List<String> mirrors) {
        return new DelayedTaskRow(row.view(), row.title(), row.repository(), Optional.empty(), false,
                row.storedSessions(), mirrors);
    }

    @Test
    void aPrTermKeepsOnlyItemsWhosePullRequestMatches() {
        TaskRow prNine = withBranchPr(running("Working", NOW),
                new GhCliService.BranchPullRequest(9, GhCliService.PrInfo.PrLifecycle.OPEN, false, "feature/x",
                        "Fix the parser", Optional.empty()));
        alphaRows.add(prNine);
        alphaRows.add(running("No pull request", NOW));
        task("Task on pr", Presentation.READY);
        tasks.set(tasks.size() - 1, tasks.getLast().withPullRequest(
                new GhCliService.OpenPullRequest(9, "Fix the parser", "feature/x", "main", false,
                        Optional.empty(), Optional.empty())));

        assertEquals(List.of("Working"), titles(inbox(new Query(Optional.empty(), "pr:9")), GroupKind.RUNNING));
        assertEquals(List.of("Task on pr"), titles(inbox(new Query(Optional.empty(), "pr:9")), GroupKind.READY));
        assertEquals(List.of("Working"), titles(inbox(new Query(Optional.empty(), "pr:parser")), GroupKind.RUNNING),
                "free text matches the pull request's title");
        // Two terms AND: the session's pull request matches both, a miss keeps nothing.
        assertEquals(List.of("Working"), titles(inbox(new Query(Optional.empty(), "pr:9 pr:parser")),
                GroupKind.RUNNING));
        assertTrue(inbox(new Query(Optional.empty(), "pr:9 pr:40")).isEmpty());
        assertTrue(inbox(new Query(Optional.empty(), "pr:40")).isEmpty(),
                "no item's pull request is 40");
    }

    @Test
    void combinedTermsMustMatchTheSameSessionItem() {
        TaskRow pullRequestSession = withBranchPr(running("PR only", NOW),
                new GhCliService.BranchPullRequest(12, GhCliService.PrInfo.PrLifecycle.OPEN, false,
                        "feature/one", "First", Optional.empty()));
        TaskRow issueSession = running("Issue only", NOW.minusSeconds(10));
        alphaRows.add(pullRequestSession);
        alphaRows.add(issueSession);
        task("Issue mirror", Presentation.STARTED, TaskStatus.STARTED, Optional.empty(),
                List.of(issueSession.session().id().toString()));
        tasks.set(tasks.size() - 1, withMirrors(tasks.getLast(),
                List.of("https://acme.atlassian.net/browse/PROJ-5")));

        assertTrue(inbox(new Query(Optional.empty(), "pr:12 issue:5")).isEmpty(),
                "the PR on one session cannot combine with the issue on another");
    }

    @Test
    void oneSessionItemCanMatchPrAndIssueTermsTogether() {
        TaskRow host = withBranchPr(running("Both references", NOW),
                new GhCliService.BranchPullRequest(12, GhCliService.PrInfo.PrLifecycle.OPEN, false,
                        "feature/one", "First", Optional.empty()));
        alphaRows.add(host);
        task("Issue mirror", Presentation.STARTED, TaskStatus.STARTED, Optional.empty(),
                List.of(host.session().id().toString()));
        tasks.set(tasks.size() - 1, withMirrors(tasks.getLast(),
                List.of("https://acme.atlassian.net/browse/PROJ-5")));

        assertEquals(List.of("Both references"),
                titles(inbox(new Query(Optional.empty(), "pr:12 issue:5")), GroupKind.RUNNING));
    }

    @Test
    void anIssueTermKeepsOnlyItemsWhoseMirroredIssueMatches() {
        TaskRow host = running("Host", NOW);
        alphaRows.add(host);
        task("Mirrors jira", Presentation.STARTED, TaskStatus.STARTED, Optional.empty(),
                List.of(host.session().id().toString()));
        tasks.set(tasks.size() - 1, withMirrors(tasks.getLast(),
                List.of("https://acme.atlassian.net/browse/PROJ-7")));

        Inbox hit = inbox(new Query(Optional.empty(), "issue:PROJ-7"));
        assertEquals(List.of("Host"), titles(hit, GroupKind.RUNNING),
                "the started task rides its session, and the session matches by the mirror");
        assertTrue(inbox(new Query(Optional.empty(), "issue:PROJ-8")).isEmpty());
    }
}
