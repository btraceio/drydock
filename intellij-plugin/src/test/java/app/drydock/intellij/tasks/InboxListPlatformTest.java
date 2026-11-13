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
import app.drydock.git.GhCliService.OpenPullRequest;
import app.drydock.intellij.IsolatedCatalog;
import app.drydock.intellij.tasks.InboxList.Entry;
import app.drydock.intellij.tasks.InboxList.Header;
import app.drydock.intellij.tasks.InboxList.Hit;
import app.drydock.intellij.tasks.InboxList.More;
import app.drydock.intellij.tasks.InboxList.Row;
import app.drydock.intellij.tasks.InboxModel.GroupKind;
import app.drydock.intellij.tasks.InboxModel.Inbox;
import app.drydock.intellij.tasks.InboxModel.Item;
import app.drydock.intellij.tasks.InboxModel.ProjectRef;
import app.drydock.intellij.tasks.InboxModel.Query;
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
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.awt.Point;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link InboxList} in a booted IDE: the entries it lays out for an inbox,
 * the collapse and "+N more" toggles, selection across a re-show, and where
 * a click on a row lands (PR number, project tag, elsewhere). Headless.
 */
public class InboxListPlatformTest extends BasePlatformTestCase {

    private static final Instant NOW = Instant.parse("2026-11-13T15:00:00Z");
    private static final Path ALPHA = Path.of("/repos/alpha").toAbsolutePath();
    private static final SharedProject ALPHA_PROJECT =
            new SharedProject(RepositoryId.newId(), ALPHA, "alpha", NOW, Optional.empty());
    private static final RepositoryId TASKS = RepositoryId.newId();

    private final List<Item> activated = new ArrayList<>();
    private final List<Item> pullRequests = new ArrayList<>();
    private final List<ProjectRef> picked = new ArrayList<>();
    private int toggleReports;
    private InboxList list;
    /** The six running sessions, the same rows (and ids) in every inbox a test builds. */
    private final List<TaskNode> sessions = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        IsolatedCatalog.assertSandboxed();
        super.setUp();
        list = new InboxList(new InboxList.Actions() {
            @Override
            public void activate(Item item) {
                activated.add(item);
            }

            @Override
            public void openPullRequest(Item item) {
                pullRequests.add(item);
            }

            @Override
            public void pickProject(ProjectRef project) {
                picked.add(project);
            }

            @Override
            public void togglesChanged() {
                toggleReports++;
            }
        });
        list.setSize(600, 2_000);
        for (int i = 0; i < 6; i++) {
            sessions.add(running("Run " + i, NOW.minusSeconds(60L * (6 - i))));
        }
    }

    private static TaskRow running(String name, Instant opened) {
        ManagedAgentSession session = new ManagedAgentSession(ManagedSessionId.newId(), RepositoryId.newId(), name,
                AgentBinding.unlaunched(AgentKind.CLAUDE), new SessionWorkspace(
                        Path.of("/tmp", "inbox-list-" + name.replace(' ', '-')).toAbsolutePath(), Optional.empty(),
                        false),
                SessionStatus.RUNNING, opened, opened, Optional.empty(), PrLink.none(), false, Optional.empty(),
                false);
        return new TaskRow(session, Optional.of(SessionActivity.BUSY), true, Optional.empty(),
                TaskPlacement.of(true, Optional.empty(), Optional.empty(), session.workingDirectory(),
                        Optional.empty()), false, false);
    }

    /** Six running sessions, one ready task with a linked PR, one task done today. */
    private Inbox inbox(Query query) {
        TaskId readyId = TaskId.of("ready-one");
        TaskId doneId = TaskId.of("done-one");
        OpenPullRequest pr = new OpenPullRequest(943, "PR", "dd/ready-one", "main", false, Optional.empty(),
                Optional.of("https://github.com/o/r/pull/943"), false);
        DelayedTaskRow ready = new DelayedTaskRow(new TaskView(readyId, Presentation.READY, List.of(), List.of()),
                "Ready one", TASKS, Optional.empty(), false, List.of(), List.of(), Optional.of(pr));
        DelayedTaskRow done = new DelayedTaskRow(new TaskView(doneId, Presentation.TERMINAL, List.of(), List.of()),
                "Done one", TASKS, List.of());
        Map<TaskId, TaskDocument> documents = new HashMap<>();
        documents.put(readyId, new TaskDocument(readyId, TASKS, "Ready one", TaskStatus.OPEN, DoneBy.HUMAN,
                Optional.empty(), List.of(), List.of(), "", ""));
        documents.put(doneId, new TaskDocument(doneId, TASKS, "Done one", TaskStatus.DONE, DoneBy.HUMAN,
                Optional.empty(), List.of(), List.of(), List.of(), Optional.of(NOW), "", ""));
        return InboxModel.build(new InboxModel.Inputs(
                List.of(new ProjectRow(ALPHA_PROJECT, false, false, sessions, List.of(), 6)), List.of(ALPHA_PROJECT),
                List.of(ready, done), documents, Map.of(TASKS, ALPHA), NOW, ZoneOffset.UTC), query);
    }

    private static List<String> describe(List<Entry> entries) {
        return entries.stream().map(entry -> switch (entry) {
            case Header header -> (header.collapsed() ? "> " : "v ") + header.group().label();
            case Row row -> "  " + row.item().title();
            case More more -> more.showingAll() ? "  Show less" : "  + " + more.hidden() + " more";
        }).toList();
    }

    private int indexOf(String title) {
        List<Entry> entries = list.entries();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) instanceof Row row && row.item().title().equals(title)) {
                return i;
            }
        }
        throw new AssertionError("no row " + title);
    }

    /** Every hit kind met walking the row at {@code index} left to right, in order, without repeats. */
    private List<Hit> hitsAcross(int index) {
        Rectangle cell = list.getCellBounds(index, index);
        List<Hit> hits = new ArrayList<>();
        for (int x = cell.x; x < cell.x + cell.width; x++) {
            Hit hit = list.hit(index, new Point(x, cell.y + cell.height / 2));
            if (hits.isEmpty() || hits.getLast() != hit) {
                hits.add(hit);
            }
        }
        return hits;
    }

    public void testGroupsTruncateAndDoneStartsCollapsed() {
        list.show(inbox(Query.NONE), false);
        assertEquals(List.of("v Running", "  Run 5", "  Run 4", "  Run 3", "  Run 2", "  + 2 more",
                "v Ready to start", "  Ready one", "> Done today"), describe(list.entries()));
    }

    public void testTheTogglesExpandAndCollapse() {
        list.show(inbox(Query.NONE), false);
        list.toggleShowAll(GroupKind.RUNNING);
        list.toggleCollapsed(GroupKind.DONE);
        list.toggleCollapsed(GroupKind.READY);
        assertEquals(List.of("v Running", "  Run 5", "  Run 4", "  Run 3", "  Run 2", "  Run 1", "  Run 0",
                "  Show less", "> Ready to start", "v Done today", "  Done one"), describe(list.entries()));
        list.show(inbox(Query.NONE), false);
        assertEquals("the toggles survive a re-show", 11, list.entries().size());
    }

    public void testTogglesAreReportedAndRestored() {
        list.show(inbox(Query.NONE), false);
        list.toggleCollapsed(GroupKind.READY);
        list.toggleCollapsed(GroupKind.DONE);
        list.toggleShowAll(GroupKind.RUNNING);
        assertEquals(3, toggleReports);
        InboxViewState.Toggles toggles = list.toggles();
        assertEquals(Set.of(GroupKind.READY), toggles.collapsed());
        assertEquals(Set.of(GroupKind.DONE), toggles.expanded());
        assertEquals(Set.of(GroupKind.RUNNING), toggles.showingAll());

        InboxList fresh = new InboxList(new InboxList.Actions() {
            @Override
            public void activate(Item item) {
            }

            @Override
            public void openPullRequest(Item item) {
            }

            @Override
            public void pickProject(ProjectRef project) {
            }
        });
        fresh.show(inbox(Query.NONE), false);
        fresh.restore(toggles);
        assertEquals(describe(list.entries()), describe(fresh.entries()));
    }

    public void testSelectionSurvivesARebuild() {
        list.show(inbox(Query.NONE), false);
        list.setSelectedIndex(indexOf("Run 3"));
        list.show(inbox(Query.NONE), false);
        assertEquals(List.of("Run 3"), list.selectedItems().stream().map(Item::title).toList());
    }

    public void testAClickLandsOnThePrNumberOrTheProjectTag() {
        list.show(inbox(Query.NONE), false);
        assertEquals(List.of(Hit.ELSEWHERE, Hit.PULL_REQUEST, Hit.ELSEWHERE, Hit.TAG, Hit.ELSEWHERE),
                hitsAcross(indexOf("Ready one")));
        assertEquals("a session without a PR has only its tag", List.of(Hit.ELSEWHERE, Hit.TAG, Hit.ELSEWHERE),
                hitsAcross(indexOf("Run 5")));
        list.show(inbox(new Query(Optional.of(ALPHA), "")), true);
        assertEquals("a project filter hides the tag", List.of(Hit.ELSEWHERE, Hit.PULL_REQUEST, Hit.ELSEWHERE),
                hitsAcross(indexOf("Ready one")));
    }
}
