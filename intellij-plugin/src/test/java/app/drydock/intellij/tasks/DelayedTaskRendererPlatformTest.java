package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.delayedtasks.TaskStoreWatcher.WatchedSnapshot;
import app.drydock.domain.*;
import app.drydock.git.GhCliService;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.IsolatedCatalog;
import app.drydock.intellij.ui.SessionDots;
import app.drydock.tasks.*;
import app.drydock.tasks.DelayedTaskTreeModel.Derivation;
import app.drydock.tasks.DelayedTaskTreeModel.Inputs;
import app.drydock.tasks.DelayedTaskTreeModel.View;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.TaskNode.SessionTasks;
import app.drydock.tasks.TaskNode.TasksSection;
import app.drydock.tasks.TaskState.Presentation;
import app.drydock.tasks.TaskState.TaskView;

import com.intellij.icons.AllIcons;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleColoredComponent.ColoredIterator;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.TimeoutUtil;

import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The T16b acceptance matrix for the accepted delayed-task renderer delta
 * and quick-view ribbon, in a booted IDE: the label, icon, hint style and
 * accessible name of every status from cached row data; the
 * stale/review/error indicators; the ribbon driving the T15a model into
 * the right row set; and session rows rendering byte-identically to the
 * pre-delayed-layer path. Headless - no window shown, every paint goes
 * through the real renderer override, every wait on the model's
 * coalescing thread pumps the event queue with a deadline, no bare sleep.
 */
public class DelayedTaskRendererPlatformTest extends BasePlatformTestCase {

    /** The renderer's separator between title, hints, age and flags (U+00B7, escaped for ASCII source). */
    private static final String DOT = " \u00b7 ";
    private static final String TITLE = "Fix allocation accounting";
    private static final List<String> BLOCKERS = List.of("blocked by 'Fix memory growth' (started)",
            "blocked by 'Wire the panel' (in session 's-42'");
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Instant YOUNG = NOW.minus(Duration.ofDays(1));
    private static final RepositoryId REPO = RepositoryId.newId();
    private static final ManagedSessionId S1 = ManagedSessionId.newId();
    private static final ManagedSessionId S2 = ManagedSessionId.newId();
    private static final WatchedSnapshot WATCHED = new WatchedSnapshot(snapshot(List.of(
            doc("alpha", "Free the harbour", TaskStatus.OPEN, List.of(), List.of()),
            doc("beta-blocker", "Behind the harbour", TaskStatus.OPEN, List.of("alpha"), List.of()),
            doc("review-solo", "Lone review", TaskStatus.REVIEW, List.of(), List.of()),
            doc("underway", "Underway work", TaskStatus.STARTED, List.of(), List.of(S1.toString())),
            doc("done-solo", "Finished long ago", TaskStatus.DONE, List.of(), List.of()),
            doc("done-attached", "Session finished chore", TaskStatus.DONE, List.of(), List.of(S1.toString())))),
            List.of(), null);
    private static final Set<String> LIVE = Set.of(S1.toString(), S2.toString());
    private static final List<TaskNode.TaskRow> SESSION_ROWS = List.of(
            sessionRow(S1, "session one", SessionStatus.RUNNING, Optional.of(SessionActivity.NEEDS_ATTENTION)),
            sessionRow(S2, "session two", SessionStatus.INACTIVE, Optional.empty()));

    @Override
    protected void setUp() throws Exception {
        IsolatedCatalog.assertSandboxed(); // before anything can touch the plugin (T14 discipline)
        super.setUp();
        try { // matrix 5 paints session rows, whose paint path reads the agent registry
            DrydockPluginService.getInstance().initialization().get(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for Drydock initialization", e);
        } catch (ExecutionException e) {
            throw new AssertionError("Drydock initialization failed", e.getCause());
        }
    }

    // ---- matrix 1: task row labels, icons and hint styles per status ----

    public void testEveryStatusRendersItsOwnLabelIconAndHintStyle() {
        TaskTreeRenderer ready = paint(row(Presentation.READY, ago(Duration.ofHours(2).plusMinutes(5))));
        assertEquals(TITLE + "  ready" + DOT + "2h old", text(ready));
        assertSame(AllIcons.Actions.Execute, ready.getIcon());
        assertEquals(SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES, attributes(ready, "  ready"));
        TaskTreeRenderer blocked = paint(row(Presentation.BLOCKED, ago(Duration.ofDays(1).plusMinutes(5))));
        assertEquals(TITLE + "  blocked" + DOT + "1d old" + DOT + BLOCKERS.get(0) + DOT + BLOCKERS.get(1), text(blocked));
        assertSame(AllIcons.General.Information, blocked.getIcon());
        TaskTreeRenderer review = paint(row(Presentation.REVIEW, Optional.empty()));
        assertEquals(TITLE + "  in review", text(review));
        assertSame(AllIcons.Actions.Preview, review.getIcon());
        assertTrue("review's hint is bold, so it reads as waiting on you (D16)",
                (attributes(review, "  in review").getStyle() & SimpleTextAttributes.STYLE_BOLD) != 0);
        TaskTreeRenderer started = paint(row(Presentation.STARTED, Optional.empty()));
        assertEquals(TITLE + "  in session", text(started));
        assertSame(AllIcons.Actions.Forward, started.getIcon());
        TaskTreeRenderer interrupted = paint(row(Presentation.INTERRUPTED, ago(Duration.ofDays(20).plusMinutes(5))));
        assertEquals(TITLE + "  interrupted" + DOT + "20d old" + DOT + "stale", text(interrupted));
        assertSame(AllIcons.General.Warning, interrupted.getIcon());
        assertTrue("interrupted's hint is italic, so a lost session reads different from a fresh one (D10)",
                (attributes(interrupted, "  interrupted").getStyle() & SimpleTextAttributes.STYLE_ITALIC) != 0);
        TaskTreeRenderer terminal = paint(row(Presentation.TERMINAL, Optional.empty()));
        assertEquals(TITLE + "  done or dropped", text(terminal));
        assertSame(AllIcons.Actions.Checked, terminal.getIcon());
    }

    /**
     * A row under the Tasks section names its project (the section spans
     * every project, so the row must say which one it applies to); the same
     * row under a session row paints no project hint, its project's subtree
     * already says it. The tooltip names the project in both places.
     */
    public void testARowUnderTheTasksSectionNamesItsProjectAndASessionChildDoesNot() {
        DelayedTaskRow projected = new DelayedTaskRow(new TaskView(TaskId.of("fix-alloc-k3f9"),
                Presentation.READY, List.of(), List.of()), TITLE, REPO, Optional.empty(), false, List.of(),
                List.of(), "alpha");
        TaskTreeRenderer inSection = paintUnder(new TasksSection("Tasks", List.of()), projected);
        assertEquals(TITLE + "  ready" + DOT + "alpha", text(inSection));
        assertEquals(SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES, attributes(inSection, DOT + "alpha"));
        assertEquals("Task fix-alloc-k3f9 (ready)" + DOT + "project alpha", inSection.getToolTipText());
        TaskTreeRenderer detached = paint(projected);
        assertEquals("the same row under a session row paints no project hint", TITLE + "  ready",
                text(detached));
        assertEquals("Task fix-alloc-k3f9 (ready)" + DOT + "project alpha", detached.getToolTipText());
        assertEquals("a row whose mapping names no repository paints no made-up name",
                TITLE + "  ready", text(paintUnder(new TasksSection("Tasks", List.of()),
                        new DelayedTaskRow(new TaskView(TaskId.of("fix-alloc-k3f9"), Presentation.READY,
                                List.of(), List.of()), TITLE, REPO, Optional.empty(), false, List.of(),
                                List.of(), " "))));
    }

    // ---- matrix 2: the accessible label carries status, age, blocker and stale text ----

    public void testTheAccessibleLabelCarriesStatusAgeBlockerAndStaleText() {
        TaskTreeRenderer blocked = paint(row(Presentation.BLOCKED, ago(Duration.ofDays(1).plusMinutes(5))));
        String name = blocked.getAccessibleContext().getAccessibleName();
        assertEquals("the accessible name is exactly the painted row", text(blocked), name);
        assertTrue(name, name.contains(TITLE) && name.contains("blocked") && name.contains("1d old")
                && name.contains(BLOCKERS.get(0)) && name.contains(BLOCKERS.get(1)));
        assertEquals("Task fix-alloc-k3f9 (blocked)" + DOT + "created 1d ago" + DOT + BLOCKERS.get(0) + DOT
                + BLOCKERS.get(1), blocked.getToolTipText());
        TaskTreeRenderer interrupted = paint(row(Presentation.INTERRUPTED, ago(Duration.ofDays(20).plusMinutes(5))));
        String staleName = interrupted.getAccessibleContext().getAccessibleName();
        assertTrue(staleName, staleName.contains("interrupted") && staleName.contains("20d old")
                && staleName.contains("stale"));
        assertEquals("Task fix-alloc-k3f9 (interrupted)" + DOT + "created 20d ago" + DOT
                + "stale (older than 14 days)" + DOT + "last session s-42", interrupted.getToolTipText());
    }

    // ---- matrix 3: stale, review and error indicators render distinctly ----

    public void testStaleReviewAndErrorRowsRenderDistinctly() {
        SimpleTextAttributes staleStyle = attributes(
                paint(row(Presentation.INTERRUPTED, ago(Duration.ofDays(20).plusMinutes(5)))), DOT + "stale");
        assertEquals("the stale flag keeps the plain style", SimpleTextAttributes.STYLE_PLAIN, staleStyle.getStyle());
        int rgb = staleStyle.getFgColor().getRGB() & 0xFFFFFF;
        assertTrue("the stale flag paints the review tree's open-findings orange in both themes: "
                + Integer.toHexString(rgb), rgb == 0xB54A0E || rgb == 0xD98A5A);
        assertFalse("a fresh row carries no stale fragment",
                text(paint(row(Presentation.READY, ago(Duration.ofHours(2).plusMinutes(5))))).contains("stale"));
        assertTrue("review is visibly not ready: its icon differs (matrix 1: Preview vs Execute) and its hint is"
                + " bold where ready's is greyed small", paint(row(Presentation.REVIEW,
                Optional.empty())).getIcon() != AllIcons.Actions.Execute);
        TaskTreeRenderer error = paint(new TaskNode.ListProblem("Task store: scan failed - boom"));
        assertEquals("Task store: scan failed - boom", text(error));
        assertEquals(SimpleTextAttributes.ERROR_ATTRIBUTES, attributes(error, "Task store: scan failed - boom"));
        assertSame(AllIcons.General.Warning, error.getIcon());
    }

    // ---- matrix 4: the ribbon's views and search, through the T15a model ----

    public void testTheRibbonDrivesTheModelIntoTheRightRowSet() {
        AtomicReference<Derivation> published = new AtomicReference<>();
        AtomicReference<View> view = new AtomicReference<>(View.ALL);
        AtomicReference<String> search = new AtomicReference<>("");
        String all = "[Tasks: Free the harbour Behind the harbour Lone review][session one: Underway work][session two:]";
        String tasks = "[Tasks: Free the harbour Behind the harbour Lone review][session one: Underway work]";
        DelayedTaskTreeModel model = new DelayedTaskTreeModel(published::set, 1);
        app.drydock.tasks.ViewFilterRibbon ribbon = new app.drydock.tasks.ViewFilterRibbon(View.ALL, "",
                v -> { view.set(v); model.request(inputs(view.get(), search.get())); },
                typed -> { search.set(typed); model.request(inputs(view.get(), search.get())); });
        try {
            model.request(inputs(View.ALL, ""));
            park(published, all, "the initial All derivation");
            control(ribbon, JToggleButton.class, View.COMPLETED.label()).doClick();
            park(published, "[Completed: Finished long ago][session one: Session finished chore]",
                    "the Completed derivation");
            control(ribbon, JToggleButton.class, View.TASKS.label()).doClick();
            park(published, tasks, "the Tasks derivation");
            control(ribbon, JToggleButton.class, View.NEEDS_YOU.label()).doClick();
            park(published, "[Tasks: Lone review][session one: Underway work]", "the Needs-you derivation");
            control(ribbon, JToggleButton.class, View.ALL.label()).doClick();
            control(ribbon, SearchTextField.class, null).setText("Underway");
            park(published, "[session one: Underway work]", "the search-narrowed All derivation");
        } finally {
            model.dispose();
        }
    }

    // ---- matrix 5: session rows render byte-identically to the pre-change path ----

    public void testSessionRowsRenderExactlyAsTheyDidBeforeTheDelayedLayer() {
        TaskNode.TaskRow plain = SESSION_ROWS.get(0);
        TaskTreeRenderer painted = paint(plain);
        List<String> expected = fragments(painted);
        TaskTreeRenderer wrapped = paint(new SessionTasks(plain));
        assertEquals("a wrapped session row paints the same fragments", expected, fragments(wrapped));
        assertSame(painted.getIcon(), wrapped.getIcon());
        assertEquals(painted.getToolTipText(), wrapped.getToolTipText());
        assertEquals("a wrapped session row with task children paints the same session fragments", expected,
                fragments(paint(new SessionTasks(plain, List.of(row(Presentation.READY, Optional.empty()))))));
        Derivation through = DelayedTaskTreeModel.derive(new Inputs(
                new WatchedSnapshot(snapshot(List.of()), List.of(), null), List.of(plain), Set.of(),
                repo -> "alpha", View.ALL, "", NOW));
        assertSame("the model carries the very session row it was given", plain,
                ((SessionTasks) through.nodes().get(0)).session());
        assertEquals("the model's pass-through paints the same fragments", expected,
                fragments(paint(through.nodes().get(0))));
    }

    // ---- a session whose branch has a pull request carries the badge ----

    public void testASessionRowOnAPullRequestBranchShowsTheColouredBadgeAndTooltip() {
        TaskNode.TaskRow plain = SESSION_ROWS.get(1);
        TaskNode.TaskRow badged = new TaskNode.TaskRow(plain.session(), plain.activity(), plain.running(),
                plain.branch(), plain.placement(), plain.readOnly(), plain.folderMissing(), plain.resumable(),
                Optional.of(new GhCliService.BranchPullRequest(12, GhCliService.PrInfo.PrLifecycle.MERGED, false,
                        "feature/x", "Fix it", Optional.empty())));
        TaskTreeRenderer painted = paint(badged);
        assertSame("the merged state is the row's leading icon, beside its dot",
                TaskTreeRenderer.sessionIcon(SessionDots.dotIcon(
                        badged.running() ? SessionStatus.RUNNING : badged.session().status(), badged.activity()),
                        badged.branchPullRequest()), painted.getIcon());
        assertFalse("the state is an icon, not text", text(painted).contains("Merged"));
        assertEquals("Pull request #12 (merged) from feature/x: Fix it", painted.getToolTipText());
        assertTrue("a merged PR offers Done", text(painted).contains(TaskTreeRenderer.DONE_LINK_TEXT));
        assertFalse("a row without a branch PR carries no badge", text(paint(plain)).contains("#12"));
        assertFalse("nor a Done offer", text(paint(plain)).contains(TaskTreeRenderer.DONE_LINK_TEXT));
    }

    // ---- fixtures: documents, rows and the model inputs ----

    private static TaskDocument doc(String id, String title, TaskStatus status, List<String> after, List<String> sessions) {
        return new TaskDocument(TaskId.of(id), REPO, title, status, null, Optional.of(YOUNG), after, sessions, "", "");
    }

    private static TaskSnapshot snapshot(List<TaskDocument> docs) {
        Map<TaskId, TaskDocument> byId = new LinkedHashMap<>();
        Map<String, List<TaskDocument>> bySession = new LinkedHashMap<>();
        for (TaskDocument document : docs) {
            byId.put(document.id(), document);
            document.sessions().forEach(session -> bySession
                    .computeIfAbsent(session, any -> new ArrayList<>()).add(document));
        }
        return new TaskSnapshot(docs, List.of(REPO), byId, bySession, List.of(), List.of());
    }

    private static TaskNode.TaskRow sessionRow(ManagedSessionId id, String name, SessionStatus status,
            Optional<SessionActivity> activity) {
        boolean running = status == SessionStatus.RUNNING;
        ManagedAgentSession session = new ManagedAgentSession(id, RepositoryId.newId(), name,
                AgentBinding.unlaunched(AgentKind.CLAUDE), new SessionWorkspace(
                        Path.of("/tmp", "t16b-" + name.replace(' ', '-')).toAbsolutePath(), Optional.empty(), false),
                status, YOUNG, YOUNG, Optional.empty(), PrLink.none(), false, Optional.empty(), false);
        return new TaskNode.TaskRow(session, activity, running, Optional.empty(), TaskPlacement.of(running,
                Optional.empty(), Optional.empty(), session.workingDirectory(), Optional.empty()),
                session.readOnly(), false, true);
    }

    private static Inputs inputs(View view, String filter) {
        return new Inputs(WATCHED, SESSION_ROWS, LIVE, repo -> "alpha", view, filter, NOW);
    }

    /** A delayed row for PAINT tests: blockers/age per presentation, the carried stale flag and last session. */
    private static DelayedTaskRow row(Presentation presentation, Optional<Instant> created) {
        boolean interrupted = presentation == Presentation.INTERRUPTED;
        boolean started = presentation == Presentation.STARTED;
        return new DelayedTaskRow(new TaskView(TaskId.of("fix-alloc-k3f9"), presentation,
                presentation == Presentation.BLOCKED ? BLOCKERS : List.of(),
                started ? List.of("s-1", "s-2") : List.of()), TITLE, RepositoryId.newId(), created, interrupted,
                interrupted ? List.of("s-42") : started ? List.of("s-1", "s-2") : List.of());
    }

    /** A creation age for a PAINTED row: painted against the wall clock a moment later, with a margin. */
    private static Optional<Instant> ago(Duration age) {
        return Optional.of(Instant.now().minus(age));
    }

    // ---- the paint harness: the real renderer override, headless ----

    private static TaskTreeRenderer paint(Object userObject) {
        TaskTreeRenderer renderer = new TaskTreeRenderer();
        renderer.customizeCellRenderer(new JTree(), new DefaultMutableTreeNode(userObject), false, true, false, 1,
                false);
        return renderer;
    }

    /** Paints one row as the child of the given node, the way the tree paints a section's child. */
    private static TaskTreeRenderer paintUnder(Object parentUserObject, Object userObject) {
        DefaultMutableTreeNode parent = new DefaultMutableTreeNode(parentUserObject);
        DefaultMutableTreeNode child = new DefaultMutableTreeNode(userObject);
        parent.add(child);
        TaskTreeRenderer renderer = new TaskTreeRenderer();
        renderer.customizeCellRenderer(new JTree(), child, false, true, false, 1, false);
        return renderer;
    }

    private static String text(TaskTreeRenderer renderer) {
        StringBuilder out = new StringBuilder();
        renderer.iterator().forEachRemaining(out::append);
        return out.toString();
    }

    /** Every painted fragment with its style and tag - the byte-identity of a rendered row. */
    private static List<String> fragments(TaskTreeRenderer renderer) {
        List<String> out = new ArrayList<>();
        ColoredIterator fragments = renderer.iterator();
        while (fragments.hasNext()) {
            out.add(fragments.next() + " @ " + fragments.getTextAttributes() + " # " + fragments.getTag());
        }
        return out;
    }

    private static SimpleTextAttributes attributes(TaskTreeRenderer renderer, String fragment) {
        ColoredIterator fragments = renderer.iterator();
        while (fragments.hasNext()) {
            if (fragments.next().equals(fragment)) {
                return fragments.getTextAttributes();
            }
        }
        throw new AssertionError("no painted fragment '" + fragment + "' in " + text(renderer));
    }

    // ---- the ribbon, model and pump-park harness ----

    /** The ribbon's control of one type and, for chips, one label: every control is a real, focusable component. */
    private static <T extends Component> T control(Container ribbon, Class<T> type, String label) {
        for (Component child : ribbon.getComponents()) {
            if (type.isInstance(child) && (label == null || label.equals(((JToggleButton) child).getText()))) {
                return type.cast(child);
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + (label == null ? "" : " for " + label));
    }

    /** One derivation's shape as one comparable string: each node's label or session name with its child titles. */
    private static String shape(Derivation derivation) {
        StringBuilder out = new StringBuilder();
        for (app.drydock.tasks.TaskNode node : derivation.nodes()) {
            out.append('[').append(node instanceof TasksSection section ? section.label()
                    : ((SessionTasks) node).session().session().displayName()).append(':');
            (node instanceof TasksSection section ? section.children() : ((SessionTasks) node).children())
                    .forEach(child -> out.append(' ').append(((DelayedTaskRow) child).title()));
            out.append(']');
        }
        return out.toString();
    }

    /** Drains rounds of the IDE event queue, the repo's pump for tests that run on the EDT. */
    private static void pump() {
        for (int i = 0; i < 3; i++) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            TimeoutUtil.sleep(20);
        }
    }

    /** Spins (never sleeps) until the model's coalescing thread published the expected row shape. */
    private static Derivation park(AtomicReference<Derivation> published, String expected, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Derivation current = published.get();
        while (current == null || !expected.equals(shape(current))) {
            assertTrue("the model did not publish " + what + " within 10 s; last shape: "
                    + (current == null ? null : shape(current)), System.nanoTime() < deadline);
            pump();
            current = published.get();
        }
        return current;
    }
}
