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
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.GhCliService.CiFailure;
import app.drydock.git.GhCliService.OpenPullRequest;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.git.GhCliService.PrInfo.PrLifecycle;
import app.drydock.intellij.IsolatedCatalog;
import app.drydock.intellij.tasks.InboxList.Entry;
import app.drydock.intellij.tasks.InboxList.Header;
import app.drydock.intellij.tasks.InboxList.Hit;
import app.drydock.intellij.tasks.InboxList.More;
import app.drydock.intellij.tasks.InboxList.Row;
import app.drydock.intellij.tasks.InboxModel.Group;
import app.drydock.intellij.tasks.InboxModel.GroupKind;
import app.drydock.intellij.tasks.InboxModel.Inbox;
import app.drydock.intellij.tasks.InboxModel.Item;
import app.drydock.intellij.tasks.InboxModel.ProjectRef;
import app.drydock.intellij.tasks.InboxModel.Query;
import app.drydock.intellij.tasks.InboxModel.SessionItem;
import app.drydock.intellij.tasks.InboxModel.TaskItem;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.ui.SessionDots;
import app.drydock.projects.SharedProject;
import app.drydock.tasks.DoneBy;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.TaskState.Presentation;
import app.drydock.tasks.TaskState.TaskView;
import app.drydock.tasks.TaskStatus;
import com.intellij.icons.AllIcons;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.icons.RowIcon;

import javax.swing.JLabel;
import javax.swing.Icon;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
        return inbox(query, false, Set.of());
    }

    /** {@link #inbox(Query)}, the ready task's linked PR carrying {@code conflicting} and {@code ciFailures}. */
    private Inbox inbox(Query query, boolean conflicting, Set<CiFailure> ciFailures) {
        TaskId readyId = TaskId.of("ready-one");
        TaskId doneId = TaskId.of("done-one");
        OpenPullRequest pr = new OpenPullRequest(943, "PR", "dd/ready-one", "main", false, Optional.empty(),
                Optional.of("https://github.com/o/r/pull/943"), conflicting, ciFailures);
        DelayedTaskRow ready = new DelayedTaskRow(new TaskView(readyId, Presentation.READY, List.of(), List.of()),
                "Ready one", TASKS, Optional.empty(), false, List.of(), List.of(), "alpha", Optional.empty())
                .withPullRequest(pr);
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

    private List<String> renderedLabels(int index) {
        Component rendered = list.getCellRenderer().getListCellRendererComponent(list, list.entries().get(index),
                index, false, false);
        List<String> labels = new ArrayList<>();
        visibleLabels(rendered, labels);
        return labels;
    }

    private static void visibleLabels(Component component, List<String> labels) {
        if (!component.isVisible()) {
            return;
        }
        if (component instanceof JLabel label && label.getText() != null) {
            labels.add(label.getText());
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                visibleLabels(child, labels);
            }
        }
    }

    public void testTaskProjectTagAndTooltipSurvivePullRequestLinkingAndFiltering() throws Exception {
        DrydockPluginService.getInstance().initialization().get(60, TimeUnit.SECONDS);
        list.show(inbox(Query.NONE), false);
        int index = indexOf("Ready one");
        TaskItem item = (TaskItem) ((Row) list.entries().get(index)).item();
        assertEquals("alpha", item.row().project());
        assertEquals(List.of("Ready one", "#943", "alpha"), renderedLabels(index));
        String tooltip = "Task ready-one (ready) · project alpha · Pull request #943 from dd/ready-one: PR";
        assertEquals(tooltip, InboxList.tooltip(item, NOW));

        list.show(inbox(new Query(Optional.of(ALPHA), "")), true);
        index = indexOf("Ready one");
        assertEquals("a project filter hides the redundant tag, not the PR", List.of("Ready one", "#943"),
                renderedLabels(index));
        assertEquals("the tooltip still names the project", tooltip,
                InboxList.tooltip(((Row) list.entries().get(index)).item(), NOW));
    }

    public void testAnUnknownTaskProjectPaintsNoMadeUpNameOrPreviousRowsTag() throws Exception {
        DrydockPluginService.getInstance().initialization().get(60, TimeUnit.SECONDS);
        list.show(inbox(Query.NONE), false);
        assertEquals(List.of("Ready one", "#943", "alpha"), renderedLabels(indexOf("Ready one")));
        DelayedTaskRow unnamed = new DelayedTaskRow(new TaskView(TaskId.of("unknown-project"), Presentation.READY,
                List.of(), List.of()), "Unknown project", TASKS, Optional.empty(), false, List.of(), List.of(),
                " ", Optional.empty());
        TaskItem item = new TaskItem(unnamed, Optional.empty());
        list.show(new Inbox(List.of(new Group(GroupKind.READY, "Ready to start", List.of(item))), List.of(), 0),
                false);
        assertEquals(List.of("Unknown project"), renderedLabels(indexOf("Unknown project")));
        assertEquals("Task unknown-project (ready)", InboxList.tooltip(item, NOW));
    }

    public void testARunningSessionInAnotherWindowWearsTheWindowChip() throws Exception {
        DrydockPluginService.getInstance().initialization().get(60, TimeUnit.SECONDS);
        ManagedAgentSession session = new ManagedAgentSession(ManagedSessionId.newId(), RepositoryId.newId(),
                "Windowed", AgentBinding.unlaunched(AgentKind.CLAUDE),
                new SessionWorkspace(Path.of("/tmp/inbox-list-windowed").toAbsolutePath(), Optional.empty(), false),
                SessionStatus.RUNNING, NOW, NOW, Optional.empty(), PrLink.none(), false, Optional.empty(), false);
        TaskPlacement here = TaskPlacement.of(true, Optional.of(ALPHA), Optional.of("alpha"),
                session.workingDirectory(), Optional.of(ALPHA));
        TaskPlacement own = TaskPlacement.of(true, Optional.of(session.workingDirectory()),
                Optional.of("beta"), session.workingDirectory(), Optional.of(ALPHA));
        TaskPlacement other = TaskPlacement.of(true, Optional.of(Path.of("/repos/gamma").toAbsolutePath()),
                Optional.of("gamma"), session.workingDirectory(), Optional.of(ALPHA));
        assertEquals("the placements the chip is about", new TaskPlacement.OwnWindow(), own);
        assertEquals("in gamma window", new TaskPlacement.OtherWindow("gamma").meta(), other.meta());
        TaskRow placed = null;
        for (TaskPlacement placement : List.of(here, own, other)) {
            placed = new TaskRow(session, Optional.of(SessionActivity.BUSY), true, Optional.empty(), placement,
                    false, false);
            list.show(inboxOf(placed), false);
            List<String> labels = renderedLabels(indexOf("Windowed"));
            if (placement == here) {
                assertEquals("shown here is not running in a different window",
                        List.of("Windowed", "alpha"), labels);
            } else if (placement == own) {
                assertEquals(List.of("Windowed", "alpha", "in its own window"), labels);
            } else {
                assertEquals(List.of("Windowed", "alpha", "in gamma window"), labels);
            }
        }
        // A running session no window shows (NotShown) does not run in a window either.
        placed = new TaskRow(placed.session(), Optional.of(SessionActivity.BUSY), true, Optional.empty(),
                TaskPlacement.of(true, Optional.empty(), Optional.empty(), session.workingDirectory(),
                        Optional.of(ALPHA)), false, false);
        list.show(inboxOf(placed), false);
        assertEquals(List.of("Windowed", "alpha"), renderedLabels(indexOf("Windowed")));
    }

    private Inbox inboxOf(TaskRow session) {
        return InboxModel.build(new InboxModel.Inputs(
                List.of(new ProjectRow(ALPHA_PROJECT, false, false, List.<TaskNode>of(session), List.of(), 1)),
                List.of(ALPHA_PROJECT), List.of(), Map.of(), Map.of(TASKS, ALPHA), NOW, ZoneOffset.UTC), Query.NONE);
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

    /** {@code row} with its branch's pull request #{@code number} carrying the given health. */
    private static TaskRow withPullRequest(TaskRow row, int number, boolean conflicting, Set<CiFailure> ci) {
        BranchPullRequest pr = new BranchPullRequest(number, PrLifecycle.OPEN, false, "dd/branch-" + number,
                "Session PR", Optional.of("https://github.com/o/r/pull/" + number), conflicting, ci);
        return new TaskRow(row.session(), row.activity(), row.running(), row.branch(), row.placement(),
                row.readOnly(), row.folderMissing(), row.resumable(), Optional.of(pr));
    }

    private Item itemOf(String title) {
        return ((Row) list.entries().get(indexOf(title))).item();
    }

    /** The icon the renderer paints on the row at {@code index}, rendered now (the renderer is reused). */
    private Icon renderedIcon(int index, String title) {
        Component cell = list.getCellRenderer().getListCellRendererComponent(list, list.entries().get(index),
                index, false, false);
        JBLabel label = findLabel(cell, title);
        assertNotNull("no title label " + title, label);
        return label.getIcon();
    }

    private static JBLabel findLabel(Component component, String text) {
        if (component instanceof JBLabel label && text.equals(label.getText())) {
            return label;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JBLabel found = findLabel(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The expected composite: {@code base}, then the conflict icon, then the CI-failure icon. */
    private static void assertHealthIcon(Icon base, boolean conflicting, boolean ci, Icon actual) {
        if (!conflicting && !ci) {
            assertSame("no known health: the base icon itself", base, actual);
            return;
        }
        List<Icon> parts = ((RowIcon) actual).getAllIcons();
        assertSame("CI comes last", ci ? TaskTreeRenderer.PR_CI_FAILURE_ICON : TaskTreeRenderer.PR_CONFLICT_ICON,
                parts.getLast());
        Icon head = parts.getFirst();
        if (conflicting && ci) {
            assertEquals(List.of(base, TaskTreeRenderer.PR_CONFLICT_ICON), ((RowIcon) head).getAllIcons());
        } else {
            assertSame("the base status icon comes first", base, head);
        }
    }

    private void click(int index, Hit target, int clicks) {
        Rectangle cell = list.getCellBounds(index, index);
        int y = cell.y + cell.height / 2;
        for (int x = cell.x; x < cell.x + cell.width; x++) {
            if (list.hit(index, new Point(x, y)) == target) {
                list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_CLICKED, 0, InputEvent.BUTTON1_DOWN_MASK,
                        x, y, clicks, false, MouseEvent.BUTTON1));
                return;
            }
        }
        throw new AssertionError("no " + target + " on row " + index);
    }

    public void testSessionAndLinkedTaskRowsComposeCarriedPrHealth() {
        Icon sessionBase = SessionDots.dotIcon(SessionStatus.RUNNING, Optional.of(SessionActivity.BUSY));
        TaskRow plain = (TaskRow) sessions.get(5);
        for (boolean conflicting : List.of(false, true)) {
            for (boolean failing : List.of(false, true)) {
                Set<CiFailure> ci = failing ? Set.of(CiFailure.FAILURE) : Set.of();
                String combo = "conflicting=" + conflicting + " ci=" + failing;
                sessions.set(5, withPullRequest(plain, 77, conflicting, ci));
                activated.clear();
                pullRequests.clear();
                picked.clear();
                list.show(inbox(Query.NONE, conflicting, ci), false);

                for (String title : List.of("Run 5", "Ready one")) {
                    Item item = itemOf(title);
                    Icon base = item instanceof SessionItem ? sessionBase : AllIcons.Actions.Execute;
                    assertHealthIcon(base, conflicting, failing, InboxList.icon(item));
                    assertSame(combo + " " + title, InboxList.icon(item), renderedIcon(indexOf(title), title));
                    assertSame("the next healthy row keeps its bare dot", sessionBase,
                            renderedIcon(indexOf("Run 4"), "Run 4"));

                    String tooltip = InboxList.tooltip(item, NOW);
                    int number = item instanceof SessionItem ? 77 : 943;
                    assertTrue(combo + ": " + tooltip, tooltip.contains("Pull request #" + number));
                    String conflictText = item instanceof SessionItem ? "#77 has merge conflicts"
                            : "#943 cannot merge into main: it has conflicts";
                    assertEquals(combo + ": " + tooltip, conflicting, tooltip.contains(conflictText));
                    assertEquals(combo + ": " + tooltip, failing,
                            tooltip.contains("#" + number + " has failing CI checks"));
                    String baseDetail = item instanceof SessionItem
                            ? InboxList.tooltip(new SessionItem(plain, Optional.empty(), List.of()), NOW)
                            : "Task ready-one (ready)";
                    assertTrue(combo + " keeps the base details: " + tooltip, tooltip.startsWith(baseDetail));

                    int index = indexOf(title);
                    assertEquals(combo + " " + title, List.of(Hit.ELSEWHERE, Hit.PULL_REQUEST, Hit.ELSEWHERE,
                            Hit.TAG, Hit.ELSEWHERE), hitsAcross(index));
                    click(index, Hit.PULL_REQUEST, 1);
                    click(index, Hit.TAG, 1);
                    click(index, Hit.ELSEWHERE, 2);
                }
                assertEquals(List.of(itemOf("Run 5"), itemOf("Ready one")), pullRequests);
                assertEquals(List.of(itemOf("Run 5"), itemOf("Ready one")), activated);
                assertEquals(2, picked.size());
                assertFalse("a healthy session says nothing of PR health",
                        InboxList.tooltip(itemOf("Run 4"), NOW).contains("CI"));
            }
        }
    }

    public void testUnknownOrAbsentPrHealthAddsNoBadge() {
        list.show(inbox(Query.NONE), false);
        TaskItem ready = (TaskItem) itemOf("Ready one");
        assertSame(AllIcons.Actions.Execute, InboxList.icon(ready));
        SessionItem bare = (SessionItem) itemOf("Run 5");
        assertSame(SessionDots.dotIcon(SessionStatus.RUNNING, Optional.of(SessionActivity.BUSY)),
                InboxList.icon(bare));
        String tooltip = InboxList.tooltip(ready, NOW);
        assertFalse(tooltip, tooltip.contains("conflicts") || tooltip.contains("failing CI"));
    }
}
