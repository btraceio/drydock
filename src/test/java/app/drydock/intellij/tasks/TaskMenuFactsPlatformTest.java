package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.delayedtasks.TaskStoreWatcher.WatchedSnapshot;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.GhCliService.OpenPullRequest;
import app.drydock.git.GhCliService.PrInfo;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.IsolatedCatalog;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.PullRequestRow;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.SectionKind;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.projects.SharedProject;
import app.drydock.tasks.StackBranches;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.TaskNode.TasksSection;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskState;
import app.drydock.tasks.TaskStatus;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The menu facts' cross-row resolution ({@code TaskMenu.factsFor}): which
 * target rows a {@code TaskRow} and a {@code PullRequestRow} lead resolve,
 * positive and miss per target, so a swapped, dropped or silently-defaulting
 * switch arm fails here. The booted IDE is required because the resolution
 * reads {@code SessionTransfer} and the mirror registry through plugin
 * services; everything the switch itself consumes is hand-built in memory.
 * Plain {@code TaskMenuTest} stays untouched -- by its own contract it never
 * calls {@code factsFor}.
 */
public class TaskMenuFactsPlatformTest extends BasePlatformTestCase {

    private static final Path ROOT = Path.of("/work/profiling-backend");
    private static final Instant NOW = Instant.parse("2026-10-20T17:00:00Z");
    private static final TaskId TASK_ID = TaskId.of("fix-alloc-k3f9");
    private static final RepositoryId REPO = RepositoryId.newId();

    @Override
    protected void setUp() throws Exception {
        IsolatedCatalog.assertSandboxed(); // before anything can touch the plugin (T14 discipline)
        super.setUp();
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        try {
            plugin.initialization().get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            fail("Drydock initialization did not complete within 60 s: " + e);
        }
    }

    /** A TaskRow lead: its own PR and its attached task resolve; nothing defaults. */
    public void testATaskRowResolvesItsPullRequestAndTaskJumps() {
        TaskRow lead = session("pr-40", Optional.of(branchPr(40, "pr-40")));
        String sessionId = lead.session().id().toString();
        DelayedTaskRow assigned = task(List.of(sessionId));
        PullRequestRow shown = pullRequest(40, "pr-40");
        TaskNode project = project(pullRequests(shown), lead);
        List<TaskNode> displayed = List.of(project);

        TaskMenu.MenuFacts facts = TaskMenu.factsFor(lead, false, displayed,
                layer(List.of(), Map.of(sessionId, List.of(assigned))), null);
        assertSame("the branch PR resolves within the lead's own project", shown,
                facts.showPullRequest());
        assertSame("the attached task naming the session resolves", assigned, facts.showTask());
        assertNull("a task row leads to no session", facts.showSession());

        // Nothing displayed: nothing resolves -- no silently-defaulted arm.
        TaskMenu.MenuFacts empty = TaskMenu.factsFor(lead, false, List.of(),
                TaskTreePanel.TaskLayer.EMPTY, null);
        assertNull(empty.showPullRequest());
        assertNull(empty.showTask());
        assertNull(empty.showSession());
    }

    /** A PullRequestRow lead: its stacked-branch task and its session resolve; misses stay null. */
    public void testAPullRequestRowResolvesItsTaskAndSessionJumps() {
        PullRequestRow lead = pullRequest(40, StackBranches.name(TASK_ID));
        TaskRow working = session(StackBranches.name(TASK_ID));
        TaskNode project = project(pullRequests(lead), working);
        List<TaskNode> displayed = List.of(project);
        // The stacked-branch task must sit where the layer surfaces it: in
        // the Tasks section, which the tree mounts before every session row.
        TaskTreePanel.TaskLayer tasks = layer(List.of(task(List.of())), Map.of());
        WatchedSnapshot watched = watched(document(TASK_ID));

        TaskMenu.MenuFacts facts = TaskMenu.factsFor(lead, false, displayed, tasks, watched);
        DelayedTaskRow shownTask = facts.showTask();
        assertNotNull("the stacked-branch task resolves from the snapshot and the layer", shownTask);
        assertEquals(TASK_ID, shownTask.id());
        assertSame("the session working the PR's head branch resolves", working, facts.showSession());
        assertNull("a PR row leads to no PR", facts.showPullRequest());

        // Misses: a head that is not a stacked branch names no task, and no
        // shown session works a foreign branch -- a whole-arm swap between
        // the two fields would fail the pair above or this one.
        PullRequestRow plain = pullRequest(41, "pr-41");
        TaskMenu.MenuFacts misses = TaskMenu.factsFor(plain, false, displayed, tasks, watched);
        assertNull(misses.showTask());
        assertNull(misses.showSession());
    }

    /** A session row's link lists: its pull requests, then its tasks' mirrors split by kind. */
    public void testATaskRowCarriesItsPullRequestsAndItsTaskMirrorsAsLinkLists() throws Exception {
        TaskRow lead = session("pr-40", Optional.of(branchPr(40, "pr-40")));
        String sessionId = lead.session().id().toString();
        // The target PR differs from the branch PR: both pages go into the
        // list, target first.
        TaskRow targeted = new TaskRow(lead.session().toBuilder()
                .targetPullRequest(Optional.of(app.drydock.domain.PullRequestTarget.fromUrl(
                        "https://github.com/x/y/pull/7"))).build(),
                lead.activity(), lead.running(), lead.branch(), lead.placement(),
                lead.readOnly(), lead.folderMissing(), lead.resumable(), lead.branchPullRequest());
        TaskDocument mirrored = mirroredDocument(TASK_ID, sessionId,
                List.of("https://github.com/x/y/issues/12", "https://acme.atlassian.net/browse/PROJ-7",
                        "https://example.org/unclassifiable"));
        // mirrorsOfSession reads the task service's published composition,
        // not the factsFor argument -- the tree paints from the same source --
        // so the test publishes the hand-built one through the same field
        // the watcher writes, and restores what it parked.
        Field watched = app.drydock.delayedtasks.DelayedTaskService.class.getDeclaredField("watchedSnapshot");
        watched.setAccessible(true);
        Object service = DrydockPluginService.getInstance().taskService();
        app.drydock.delayedtasks.TaskStoreWatcher.WatchedSnapshot parked =
                (app.drydock.delayedtasks.TaskStoreWatcher.WatchedSnapshot) watched.get(service);
        watched.set(service, watched(sessionId, mirrored));
        try {
            TaskMenu.MenuFacts facts = TaskMenu.factsFor(targeted, false, List.of(),
                    TaskTreePanel.TaskLayer.EMPTY, null);
            assertEquals(List.of("https://github.com/x/y/pull/7", "https://github.com/x/y/pull/40"),
                    facts.prUrls());
            assertEquals(List.of("https://github.com/x/y/issues/12"), facts.githubUrls());
            assertEquals(List.of("https://acme.atlassian.net/browse/PROJ-7"), facts.jiraUrls());
        } finally {
            watched.set(service, parked);
        }

        // No target, no branch PR, a task with no mirrors: every list empty,
        // so the session menu offers only the project window.
        TaskMenu.MenuFacts plain = TaskMenu.factsFor(session("pr-41"), false, List.of(),
                TaskTreePanel.TaskLayer.EMPTY, null);
        assertEquals(List.of(), plain.prUrls());
        assertEquals(List.of(), plain.githubUrls());
        assertEquals(List.of(), plain.jiraUrls());
    }

    // Fixtures: the shapes the real tree builder produces, hand-built.

    private static TaskRow session(String branch) {
        return session(branch, Optional.empty());
    }

    private static TaskRow session(String branch, Optional<BranchPullRequest> pullRequest) {
        ManagedAgentSession agent = new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), "Fix allocation profile",
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(ROOT), NOW)
                .build();
        return new TaskRow(agent, Optional.empty(), false, Optional.ofNullable(branch),
                new TaskPlacement.Here(), false, false, false, pullRequest);
    }

    private static BranchPullRequest branchPr(int number, String head) {
        return new BranchPullRequest(number, PrInfo.PrLifecycle.OPEN, false, head, "Speed up",
                Optional.of("https://github.com/x/y/pull/" + number));
    }

    private static PullRequestRow pullRequest(int number, String head) {
        return new PullRequestRow(ROOT, new OpenPullRequest(number, "Speed up the flaky suite",
                head, "main", false, Optional.of("reviewer"),
                Optional.of("https://github.com/x/y/pull/" + number)));
    }

    private static DelayedTaskRow task(List<String> storedSessions) {
        return new DelayedTaskRow(new TaskState.TaskView(TASK_ID, TaskState.Presentation.READY,
                List.of(), List.of()), "Fix allocation profile", REPO, Optional.of(NOW), false,
                storedSessions);
    }

    private static TaskNode project(TaskNode... children) {
        return new ProjectRow(SharedProject.local(REPO, ROOT, "profiling-backend", NOW),
                false, false, List.of(children), List.of(), 0);
    }

    private static TaskNode pullRequests(TaskNode... rows) {
        return new Section(SectionKind.PULL_REQUESTS, List.of(rows));
    }

    private static TaskTreePanel.TaskLayer layer(List<DelayedTaskRow> section,
                                                 Map<String, List<DelayedTaskRow>> attached) {
        List<TasksSection> sections = section.isEmpty() ? List.of()
                : List.of(new TasksSection("Tasks", List.copyOf(section)));
        return new TaskTreePanel.TaskLayer(sections, attached);
    }

    private static TaskDocument document(TaskId id) {
        return mirroredDocument(id, List.of(), List.of());
    }

    private static TaskDocument mirroredDocument(TaskId id, List<String> sessions, List<String> mirrors) {
        return new TaskDocument(id, RepositoryId.newId(), "Fix allocation profile",
                TaskStatus.STARTED, app.drydock.tasks.DoneBy.AGENT, Optional.of(NOW),
                List.of(), sessions, mirrors, "", "body");
    }

    private static TaskDocument mirroredDocument(TaskId id, String sessionId, List<String> mirrors) {
        return mirroredDocument(id, List.of(sessionId), mirrors);
    }

    private static WatchedSnapshot watched(TaskDocument... documents) {
        Map<TaskId, TaskDocument> byId = new LinkedHashMap<>();
        for (TaskDocument document : documents) {
            byId.put(document.id(), document);
        }
        return watched(Map.of(), documents);
    }

    /** The watched snapshot with the session-reference index stated, as TaskFiles.scan computes it. */
    private static WatchedSnapshot watched(String sessionId, TaskDocument... documents) {
        Map<TaskId, TaskDocument> byId = new LinkedHashMap<>();
        for (TaskDocument document : documents) {
            byId.put(document.id(), document);
        }
        return watched(Map.of(sessionId, List.of(documents)), documents);
    }

    private static WatchedSnapshot watched(Map<String, List<TaskDocument>> bySessionReference,
                                           TaskDocument... documents) {
        Map<TaskId, TaskDocument> byId = new LinkedHashMap<>();
        for (TaskDocument document : documents) {
            byId.put(document.id(), document);
        }
        return new WatchedSnapshot(new TaskSnapshot(List.of(documents),
                List.of(documents[0].repository()), byId, bySessionReference, List.of(), List.of()),
                List.of(), null);
    }
}
