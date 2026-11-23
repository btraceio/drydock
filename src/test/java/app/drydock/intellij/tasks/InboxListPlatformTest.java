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
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
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
import java.util.UUID;
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
    private final List<InboxModel.Graph> startedGraphs = new ArrayList<>();
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
            public void startGraph(InboxModel.Graph graph) {
                startedGraphs.add(graph);
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
                "Ready one", TASKS, Optional.empty(), false, List.of(), List.of(), "alpha", Optional.empty(), "")
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
            case InboxList.GraphHeader header -> (header.collapsed() ? "> " : "v ") + "graph " + header.graph().key();
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
        String tooltip = "<html>Task ready-one (ready) · project alpha · Pull request #943 from dd/ready-one: PR</html>";
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
                " ", Optional.empty(), "");
        TaskItem item = new TaskItem(unnamed, Optional.empty());
        list.show(new Inbox(List.of(new Group(GroupKind.READY, "Ready to start", List.of(item))), List.of(), 0),
                false);
        assertEquals(List.of("Unknown project"), renderedLabels(indexOf("Unknown project")));
        assertEquals("<html>Task unknown-project (ready)</html>", InboxList.tooltip(item, NOW));
    }

    public void testATaskRowsHoverCarriesItsFullDescriptionAfterTheSummary() throws Exception {
        DrydockPluginService.getInstance().initialization().get(60, TimeUnit.SECONDS);
        DelayedTaskRow described = new DelayedTaskRow(
                new TaskView(TaskId.of("described-one"), Presentation.READY, List.of(), List.of()),
                "Described one", TASKS, Optional.empty(), false, List.of(), List.of(), "alpha", Optional.empty(),
                "\nFix the parser.\n\n## Session notes\nThe first pass cached too eagerly.");
        TaskItem item = new TaskItem(described, Optional.empty());
        String tooltip = InboxList.tooltip(item, NOW);
        assertTrue("tooltip: " + tooltip, tooltip.startsWith("<html>Task described-one (ready)"));
        assertTrue("tooltip: " + tooltip, tooltip.endsWith(
                "<br><br>Fix the parser.<br><br>## Session notes<br>The first pass cached too eagerly.</html>"));
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
                    // The HTML wrapper sits at the ends; the base details compose inside it.
                    String baseInner = baseDetail.startsWith("<html>") && baseDetail.endsWith("</html>")
                            ? baseDetail.substring("<html>".length(), baseDetail.length() - "</html>".length())
                            : baseDetail;
                    assertTrue(combo + " keeps the base details: " + tooltip,
                            tooltip.startsWith("<html>" + baseInner) && tooltip.endsWith("</html>"));

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

    /** {@code row} with its part in a babysitting engagement. */
    private static TaskRow withBabysit(TaskRow row, SessionBabysit part) {
        return new TaskRow(row.session(), row.activity(), row.running(), row.branch(), row.placement(),
                row.readOnly(), row.folderMissing(), row.resumable(), row.branchPullRequest(), Optional.of(part));
    }

    public void testABabysitterAndItsEscalatedWatchedSessionWearTheirChipsBadgeAndSection() throws Exception {
        DrydockPluginService.getInstance().initialization().get(60, TimeUnit.SECONDS);
        TaskRow watchedRow = running("Watched", NOW);
        TaskRow babysitterRow = running("babysit PR #12", NOW.minusSeconds(60));
        BabysitPairing pairing = new BabysitPairing(babysitterRow.session().id(), watchedRow.session().id(),
                watchedRow.session().repositoryId(), 12, BabysitState.ESCALATED);
        Map<ManagedSessionId, SessionBabysit> parts = SessionBabysit.join(List.of(pairing), List.of(
                new Escalation(UUID.randomUUID(), pairing.watchedId(), pairing.babysitterId(), 12,
                        "Needs a design call\nsecond line", NOW, false)));
        TaskRow watched = withBabysit(watchedRow, parts.get(watchedRow.session().id()));
        TaskRow babysitter = withBabysit(babysitterRow, parts.get(babysitterRow.session().id()));
        TaskRow plain = running("Plain", NOW.minusSeconds(120));
        list.show(InboxModel.build(new InboxModel.Inputs(
                List.of(new ProjectRow(ALPHA_PROJECT, false, false, List.<TaskNode>of(watched, babysitter, plain),
                        List.of(), 3)),
                List.of(ALPHA_PROJECT), List.of(), Map.of(), Map.of(TASKS, ALPHA), NOW, ZoneOffset.UTC), Query.NONE),
                false);

        assertEquals(List.of("v Needs you", "  Watched", "v Running", "  Plain", "v Babysitting", "  babysit PR #12"),
                describe(list.entries()));
        assertEquals("the engagement chip comes last, after the project tag",
                List.of("babysit PR #12", "alpha", "escalated #12"), renderedLabels(indexOf("babysit PR #12")));
        assertEquals(List.of("Watched", "alpha", "babysat · escalated"), renderedLabels(indexOf("Watched")));
        assertEquals("a plain row has no chip", List.of("Plain", "alpha"), renderedLabels(indexOf("Plain")));

        Icon dot = SessionDots.dotIcon(SessionStatus.RUNNING, Optional.of(SessionActivity.BUSY));
        assertEquals("a babysitter: its dot, then the watch mark; the badge is the watched row's",
                List.of(dot, InboxList.BABYSITTER_ICON), iconParts(InboxList.icon(itemOf("babysit PR #12"))));
        assertEquals("the watched row wears the escalation badge after its dot",
                List.of(dot, InboxList.ESCALATION_ICON), ((RowIcon) InboxList.icon(itemOf("Watched"))).getAllIcons());
        assertSame(dot, InboxList.icon(itemOf("Plain")));

        assertEquals(InboxList.NEEDS_BACKGROUND, renderedBackground(indexOf("Watched")));
        assertEquals("the human acts on the watched session, so only its row is tinted",
                list.getBackground(), renderedBackground(indexOf("babysit PR #12")));
        assertEquals(list.getBackground(), renderedBackground(indexOf("Plain")));
        assertEquals(InboxList.BABYSIT_TEXT, InboxList.headerColor(GroupKind.BABYSITTING));

        String tooltip = InboxList.tooltip(itemOf("Watched"), NOW);
        assertTrue(tooltip, tooltip
                .endsWith(" · PR #12 is babysat (escalated) · escalated: Needs a design call</html>"));
        assertTrue(InboxList.tooltip(itemOf("babysit PR #12"), NOW)
                .endsWith(" · Babysitter of PR #12 (escalated) · escalated: Needs a design call</html>"));
    }

    public void testARunningFlaggedSessionWearsTheFlaggedChipItsStoppedSiblingDoesNot() throws Exception {
        DrydockPluginService.getInstance().initialization().get(60, TimeUnit.SECONDS);
        TaskRow flaggedRunning = withFlaggedSession(running("Flagged run", NOW), true);
        TaskRow flaggedStopped = withFlaggedSession(stopped("Flagged stopped", NOW.minusSeconds(60)), true);
        TaskRow plain = running("Plain", NOW.minusSeconds(120));
        list.show(InboxModel.build(new InboxModel.Inputs(
                List.of(new ProjectRow(ALPHA_PROJECT, false, false,
                        List.<TaskNode>of(flaggedRunning, flaggedStopped, plain), List.of(), 3)),
                List.of(ALPHA_PROJECT), List.of(), Map.of(), Map.of(TASKS, ALPHA), NOW, ZoneOffset.UTC), Query.NONE),
                false);

        assertEquals(List.of("v Flagged", "  Flagged stopped", "v Running", "  Flagged run", "  Plain"),
                describe(list.entries()));
        assertEquals("the flagged chip follows the project tag on the Running row",
                List.of("Flagged run", "alpha", "flagged"), renderedLabels(indexOf("Flagged run")));
        assertEquals("the Flagged section's header already says it", List.of("Flagged stopped", "alpha"),
                renderedLabels(indexOf("Flagged stopped")));
        assertEquals("a plain running row has no chip", List.of("Plain", "alpha"),
                renderedLabels(indexOf("Plain")));
    }

    /** A running session's row with its flag set: the session carries it, the row is otherwise plain. */
    private static TaskRow withFlaggedSession(TaskRow row, boolean flagged) {
        return new TaskRow(row.session().withFlagged(flagged), row.activity(), row.running(), row.branch(),
                row.placement(), row.readOnly(), row.folderMissing(), row.resumable());
    }

    /** A stopped, idle session's row: the running helper's counterpart. */
    private static TaskRow stopped(String name, Instant opened) {
        ManagedAgentSession session = new ManagedAgentSession(ManagedSessionId.newId(), RepositoryId.newId(), name,
                AgentBinding.unlaunched(AgentKind.CLAUDE), new SessionWorkspace(
                        Path.of("/tmp", "inbox-list-" + name.replace(' ', '-')).toAbsolutePath(), Optional.empty(),
                        false),
                SessionStatus.INACTIVE, opened, opened, Optional.empty(), PrLink.none(), false, Optional.empty(),
                false);
        return new TaskRow(session, Optional.empty(), false, Optional.empty(),
                TaskPlacement.of(false, Optional.empty(), Optional.empty(), session.workingDirectory(),
                        Optional.empty()), false, false, false);
    }

    /** A row icon's parts, one nesting level deep: a nested row icon becomes its own part list. */
    private static List<Object> iconParts(Icon icon) {
        List<Object> parts = new ArrayList<>();
        for (Icon part : ((RowIcon) icon).getAllIcons()) {
            parts.add(part instanceof RowIcon nested ? nested.getAllIcons() : part);
        }
        return parts;
    }

    private Color renderedBackground(int index) {
        return list.getCellRenderer().getListCellRendererComponent(list, list.entries().get(index), index, false,
                false).getBackground();
    }

    private Inbox graphInbox() {
        return graphInbox(false);
    }

    /**
     * One graph (first, then second which waits on it) and one standalone ready task, no sessions;
     * {@code earlyRoot} adds a ready task the second also waits on, which sorts before the first.
     */
    private Inbox graphInbox(boolean earlyRoot) {
        TaskId firstId = TaskId.of("first-task");
        TaskId secondId = TaskId.of("second-task");
        TaskId aloneId = TaskId.of("alone-task");
        DelayedTaskRow first = new DelayedTaskRow(new TaskView(firstId, Presentation.READY, List.of(), List.of()),
                "First task", TASKS, List.of());
        DelayedTaskRow second = new DelayedTaskRow(new TaskView(secondId, Presentation.BLOCKED,
                List.of("blocked by 'First task' (open)"), List.of()), "Second task", TASKS, List.of());
        DelayedTaskRow alone = new DelayedTaskRow(new TaskView(aloneId, Presentation.READY, List.of(), List.of()),
                "Alone task", TASKS, List.of());
        TaskId earlyId = TaskId.of("early-task");
        DelayedTaskRow early = new DelayedTaskRow(new TaskView(earlyId, Presentation.READY, List.of(), List.of()),
                "Early task", TASKS, List.of());
        List<DelayedTaskRow> rows = earlyRoot ? List.of(first, second, alone, early) : List.of(first, second, alone);
        Map<TaskId, TaskDocument> documents = new HashMap<>();
        for (DelayedTaskRow row : rows) {
            documents.put(row.id(), new TaskDocument(row.id(), TASKS, row.title(), TaskStatus.OPEN, DoneBy.HUMAN,
                    Optional.empty(), List.of(), List.of(), "", ""));
        }
        return InboxModel.build(new InboxModel.Inputs(List.of(), List.of(ALPHA_PROJECT), rows, documents,
                Map.of(TASKS, ALPHA), NOW, ZoneOffset.UTC,
                Map.of(secondId, earlyRoot ? List.of(firstId, earlyId) : List.of(firstId))), Query.NONE);
    }

    public void testGraphsSitUnderReadyToStartAheadOfItsStandaloneTasks() {
        list.show(graphInbox(), false);

        assertEquals(List.of("v Ready to start", "v graph first-task", "  First task", "  Second task",
                "  Alone task"), describe(list.entries()));
        assertEquals("the header counts what is ready: the graph's first task and the standalone one", 2,
                ((InboxList.Header) list.entries().get(0)).count());
        assertEquals(Optional.of("unblocks 1"), InboxList.graphChip(
                ((InboxList.Row) list.entries().get(2)).node()));
        assertEquals(Optional.of("waits on 1"), InboxList.graphChip(
                ((InboxList.Row) list.entries().get(3)).node()));
        assertEquals("a graph with one outcome is labelled by it", "Second task",
                InboxList.graphLabel(((InboxList.GraphHeader) list.entries().get(1)).graph()));
    }

    public void testStartGraphIsAHitOnTheGraphHeaderAndReportsItsGraph() {
        list.show(graphInbox(), false);

        assertTrue(hitsAcross(1).contains(Hit.START_GRAPH));
        click(1, Hit.START_GRAPH, 1);

        assertEquals(1, startedGraphs.size());
        assertEquals("first-task", startedGraphs.get(0).key());
        assertEquals("a Start graph click does not collapse the graph", 5, list.entries().size());
    }

    /** A graph of two tasks that wait on each other: stuck, so with no outcome to start. */
    private Inbox stuckGraphInbox() {
        TaskId oneId = TaskId.of("one-task");
        TaskId twoId = TaskId.of("two-task");
        DelayedTaskRow one = new DelayedTaskRow(new TaskView(oneId, Presentation.BLOCKED, List.of(), List.of()),
                "One task", TASKS, List.of());
        DelayedTaskRow two = new DelayedTaskRow(new TaskView(twoId, Presentation.BLOCKED, List.of(), List.of()),
                "Two <b> task", TASKS, List.of());
        Map<TaskId, TaskDocument> documents = new HashMap<>();
        for (DelayedTaskRow row : List.of(one, two)) {
            documents.put(row.id(), new TaskDocument(row.id(), TASKS, row.title(), TaskStatus.OPEN, DoneBy.HUMAN,
                    Optional.empty(), List.of(), List.of(), "", ""));
        }
        return InboxModel.build(new InboxModel.Inputs(List.of(), List.of(ALPHA_PROJECT), List.of(one, two),
                documents, Map.of(TASKS, ALPHA), NOW, ZoneOffset.UTC,
                Map.of(oneId, List.of(twoId), twoId, List.of(oneId))), Query.NONE);
    }

    public void testAStuckGraphHasNoStartGraphLinkAndStillGetsAReadyGroup() {
        list.show(stuckGraphInbox(), false);

        assertEquals(List.of("v Ready to start", "v graph one-task", "  One task", "  Two <b> task"),
                describe(list.entries()));
        assertFalse(hitsAcross(1).contains(Hit.START_GRAPH));
        assertEquals("nothing in it is ready", 0, ((InboxList.Header) list.entries().get(0)).count());
    }

    public void testTheTooltipOfAGraphTaskEscapesTheTitlesItWaitsOn() {
        list.show(stuckGraphInbox(), false);

        String tooltip = list.getToolTipText(new MouseEvent(list, MouseEvent.MOUSE_MOVED, 0, 0,
                list.getCellBounds(2, 2).x + 2, list.getCellBounds(2, 2).y + 2, 0, false));

        assertTrue(tooltip, tooltip.contains("Waits on: Two &lt;b&gt; task"));
        assertFalse(tooltip, tooltip.contains("<b>"));
    }

    public void testACollapsedGraphStaysCollapsedWhenItsFirstTaskChanges() {
        list.show(graphInbox(), false);
        list.toggleGraph(((InboxList.GraphHeader) list.entries().get(1)).graph());

        list.show(graphInbox(true), false);

        InboxList.GraphHeader header = (InboxList.GraphHeader) list.entries().get(1);
        assertEquals("the first task changed", "early-task", header.graph().key());
        assertTrue(header.collapsed());
    }

    /** Delivers Ctrl+Enter straight to the list's key listeners: a headless list has no focus to route it. */
    private void pressCtrlEnter() {
        KeyEvent event = new KeyEvent(list, KeyEvent.KEY_PRESSED, 0, InputEvent.CTRL_DOWN_MASK,
                KeyEvent.VK_ENTER, '\n');
        for (KeyListener listener : list.getKeyListeners()) {
            listener.keyPressed(event);
        }
    }

    public void testCtrlEnterStartsTheSelectedGraphFromItsHeaderOrOneOfItsTasks() {
        list.show(graphInbox(), false);

        list.setSelectedIndex(1);
        pressCtrlEnter();
        list.setSelectedIndex(3);
        pressCtrlEnter();
        list.setSelectedIndex(4);
        pressCtrlEnter();

        assertEquals("the standalone task is no graph", 2, startedGraphs.size());
        assertEquals("on the standalone task it is a plain Enter: it opens the task", 1, activated.size());
    }

    public void testTheGraphHeaderNamesItsProjectAndAClickOnItPicksTheProject() {
        list.show(graphInbox(), false);

        click(1, Hit.TAG, 1);

        assertEquals(1, picked.size());
        assertEquals("alpha", picked.get(0).name());
        assertEquals("the click did not collapse the graph", 5, list.entries().size());
    }

    public void testASelectedGraphHeaderStaysSelectedAcrossARebuildAndAnotherInbox() {
        list.show(graphInbox(), false);
        list.setSelectedIndex(1);

        list.toggleGraph(((InboxList.GraphHeader) list.entries().get(1)).graph());
        assertTrue(list.getSelectedValue() instanceof InboxList.GraphHeader);

        list.show(graphInbox(), false);
        assertTrue(list.getSelectedValue() instanceof InboxList.GraphHeader);
    }

    public void testClickingAGraphHeaderElsewhereCollapsesItAndTheViewToggleRestoresStateGroups() {
        list.show(graphInbox(), false);

        Rectangle cell = list.getCellBounds(1, 1);
        list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_CLICKED, 0, InputEvent.BUTTON1_DOWN_MASK,
                cell.x + 2, cell.y + cell.height / 2, 1, false, MouseEvent.BUTTON1));
        assertEquals(List.of("v Ready to start", "> graph first-task", "  Alone task"),
                describe(list.entries()));

        list.setGraphView(false);
        assertEquals(List.of("v Blocked", "  Second task", "v Ready to start", "  Alone task", "  First task"),
                describe(list.entries()));
        assertFalse(list.graphView());
    }
}
