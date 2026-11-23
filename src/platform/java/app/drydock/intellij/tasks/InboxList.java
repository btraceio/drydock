package app.drydock.intellij.tasks;

import app.drydock.domain.SessionStatus;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.tasks.InboxModel.Graph;
import app.drydock.intellij.tasks.InboxModel.GraphNode;
import app.drydock.intellij.tasks.InboxModel.Group;
import app.drydock.intellij.tasks.InboxModel.GroupKind;
import app.drydock.intellij.tasks.InboxModel.Inbox;
import app.drydock.intellij.tasks.InboxModel.Item;
import app.drydock.intellij.tasks.InboxModel.ProjectRef;
import app.drydock.intellij.tasks.InboxModel.SessionItem;
import app.drydock.intellij.tasks.InboxModel.TaskItem;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.ui.SessionDots;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskMirror;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.TaskState.Presentation;
import app.drydock.ui.AgentLabels;
import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.ui.ListSpeedSearch;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.UIUtil;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.function.Supplier;

/**
 * The Attention Inbox's list: one header per non-empty {@link Group}, its
 * rows, and a "+N more" / "Show less" toggle where the group truncates.
 * Clicking a header (or Enter on it) collapses or expands the group; the
 * collapsed and show-all sets live here, seeded from each group's default.
 * A row is {@code [icon] title … [#PR] [project] [window] [babysit]}: the PR number
 * opens the pull request, the project tag reports the project to the owner,
 * the window tag names a running session's other window, and a double-click
 * or Enter activates the row. EDT only.
 */
final class InboxList extends JBList<InboxList.Entry> {

    /** One list entry. */
    sealed interface Entry {
    }

    /** A group's header line. */
    record Header(Group group, boolean collapsed, int count) implements Entry {
        Header(Group group, boolean collapsed) {
            this(group, collapsed, group.items().size());
        }
    }

    /**
     * One item of a group, or (with {@code group} null and {@code node} set) one task of a dependency graph.
     */
    record Row(Group group, Item item, GraphNode node) implements Entry {
        Row(Group group, Item item) {
            this(group, item, null);
        }
    }

    /** A dependency graph's header line. */
    record GraphHeader(Graph graph, boolean collapsed) implements Entry {
    }

    /** The toggle under a truncating group: {@code hidden} rows behind "+N more", or "Show less". */
    record More(Group group, int hidden, boolean showingAll) implements Entry {
    }

    /** What the owner does with a row's gestures. */
    interface Actions {
        /** Double-click or Enter on a row. */
        void activate(Item item);

        /** A click on a row's PR number. */
        void openPullRequest(Item item);

        /** A click on a row's project tag. */
        void pickProject(ProjectRef project);

        /** A click on a graph header's "Start graph". */
        default void startGraph(Graph graph) {
        }

        /** A group was collapsed, expanded, or switched between "+N more" and all rows. */
        default void togglesChanged() {
        }
    }

    /** Where a click on a row landed. */
    enum Hit { PULL_REQUEST, TAG, START_GRAPH, ELSEWHERE }

    static final JBColor NEEDS_TEXT = new JBColor(0xA64B0A, 0xE5925B);
    static final JBColor NEEDS_BACKGROUND = new JBColor(0xFFF4EB, 0x3B2F25);
    static final JBColor NEEDS_TAG_BORDER = new JBColor(0xE4D3C4, 0x5C4A3B);
    static final JBColor BLOCKED_TEXT = new JBColor(0xB4282E, 0xE5777C);
    static final JBColor TAG_BORDER = new JBColor(0xEBECF0, 0x43454A);
    /** The babysitting chip's colours: distinct from every other chip's. */
    static final JBColor BABYSIT_TAG_BORDER = new JBColor(0xBFDCD6, 0x355A53);
    /** A babysitter row's mark after its dot: the debugger's watch eye. */
    static final Icon BABYSITTER_ICON = AllIcons.Debugger.Watch;
    /** The attention mark a row wears while its engagement needs the human. */
    static final Icon ESCALATION_ICON = AllIcons.General.BalloonWarning;

    private final DefaultListModel<Entry> entries = new DefaultListModel<>();
    private final Set<GroupKind> collapsed = EnumSet.noneOf(GroupKind.class);
    private final Set<GroupKind> expanded = EnumSet.noneOf(GroupKind.class);
    private final Set<GroupKind> showingAll = EnumSet.noneOf(GroupKind.class);
    /**
     * The tasks of the graphs the user collapsed; not persisted. A graph is collapsed when any of its tasks
     * is here, so it stays collapsed while its first task changes, and new tasks joining it are collapsed too.
     */
    private final Set<TaskId> collapsedGraphTasks = new HashSet<>();
    private final Renderer renderer = new Renderer();
    private final Actions actions;
    private Inbox inbox;
    private boolean tagsHidden;
    /** Whether dependency graphs sit under "Ready to start" and leave the state groups the tasks that belong to one. */
    private boolean graphView = true;

    /**
     * The shown Jira group listings' issue key → priority name, fed by the
     * panel from its gather inputs; the priority badge of a mirrored task's
     * row reads it at paint time. Map::of until the panel says — a lookup
     * that answers nothing badges no row.
     */
    private volatile Supplier<Map<String, Optional<String>>> jiraPriorities = Map::of;

    InboxList(Actions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
        setModel(entries);
        setCellRenderer(renderer);
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        ListSpeedSearch.installOn(this, entry -> entry instanceof Row row ? row.item().title() : null);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                // A right-click acts on the row under the pointer, as in the tree.
                int index = locationToIndex(event.getPoint());
                if (SwingUtilities.isRightMouseButton(event) && index >= 0 && !isSelectedIndex(index)
                        && getCellBounds(index, index).contains(event.getPoint())) {
                    setSelectedIndex(index);
                }
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                int index = locationToIndex(event.getPoint());
                if (index < 0 || !getCellBounds(index, index).contains(event.getPoint())) {
                    return;
                }
                Entry entry = entries.get(index);
                switch (entry) {
                    case Header header when event.getClickCount() == 1 -> toggleCollapsed(header.group().kind());
                    case GraphHeader header when event.getClickCount() == 1 -> {
                        Hit hit = hit(index, event.getPoint());
                        if (hit == Hit.START_GRAPH) {
                            actions.startGraph(header.graph());
                        } else if (hit == Hit.TAG && header.graph().projects().size() == 1) {
                            actions.pickProject(header.graph().projects().get(0));
                        } else {
                            toggleGraph(header.graph());
                        }
                    }
                    case More more when event.getClickCount() == 1 -> toggleShowAll(more.group().kind());
                    case Row row -> {
                        Hit hit = hit(index, event.getPoint());
                        if (event.getClickCount() == 1 && hit == Hit.PULL_REQUEST) {
                            actions.openPullRequest(row.item());
                        } else if (event.getClickCount() == 1 && hit == Hit.TAG) {
                            row.item().project().ifPresent(actions::pickProject);
                        } else if (event.getClickCount() == 2 && hit == Hit.ELSEWHERE) {
                            actions.activate(row.item());
                        }
                    }
                    default -> {
                    }
                }
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() != KeyEvent.VK_ENTER || getSelectedValue() == null) {
                    return;
                }
                // Ctrl/Cmd+Enter starts the selected graph (its header, or one of its tasks).
                if ((event.isControlDown() || event.isMetaDown()) && selectedGraph().isPresent()) {
                    actions.startGraph(selectedGraph().get());
                    event.consume();
                    return;
                }
                switch (getSelectedValue()) {
                    case Header header -> toggleCollapsed(header.group().kind());
                    case GraphHeader header -> toggleGraph(header.graph());
                    case More more -> toggleShowAll(more.group().kind());
                    case Row row -> actions.activate(row.item());
                }
                event.consume();
            }
        });
    }

    /** Shows {@code next}, keeping the selected items selected; {@code hideTags} while a project filter is on. */
    void show(Inbox next, boolean hideTags) {
        List<String> selectedKeys = selectedItems().stream().map(InboxList::key).toList();
        inbox = next;
        tagsHidden = hideTags;
        collapsedGraphTasks.retainAll(next.graphs().stream().flatMap(graph -> graph.nodes().stream())
                .map(node -> node.item().row().id()).collect(Collectors.toSet()));
        rebuild(selectedKeys);
    }

    /** Switches between the dependency-graph view and the plain state groups. */
    void setGraphView(boolean graphs) {
        if (graphView != graphs) {
            graphView = graphs;
            rebuild(selectedItems().stream().map(InboxList::key).toList());
        }
    }

    boolean graphView() {
        return graphView;
    }

    void toggleGraph(Graph graph) {
        List<TaskId> members = graph.nodes().stream().map(node -> node.item().row().id()).toList();
        if (isGraphCollapsed(graph)) {
            members.forEach(collapsedGraphTasks::remove);
        } else {
            collapsedGraphTasks.addAll(members);
        }
        rebuild(selectedItems().stream().map(InboxList::key).toList());
    }

    /**
     * Feeds the priority-badge lookup: the panel's supplier over its gather
     * inputs. Called once, at construction; the supplier is read at paint
     * time, so each paint sees the listings the last gather answered.
     */
    void jiraPriorities(Supplier<Map<String, Optional<String>>> jiraPriorities) {
        this.jiraPriorities = Objects.requireNonNull(jiraPriorities, "jiraPriorities");
    }

    /** The selected rows' items, in list order; headers and toggles select nothing. */
    List<Item> selectedItems() {
        List<Item> items = new ArrayList<>();
        for (Entry entry : getSelectedValuesList()) {
            if (entry instanceof Row row) {
                items.add(row.item());
            }
        }
        return items;
    }

    /** Selects and scrolls to the first visible row whose row value {@code match} accepts. */
    boolean select(Predicate<Object> match) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) instanceof Row row && match.test(rowValue(row.item()))) {
                setSelectedIndex(i);
                ensureIndexIsVisible(i);
                return true;
            }
        }
        return false;
    }

    /** The tree-model value an item stands for: its session row or its task row. */
    static Object rowValue(Item item) {
        return switch (item) {
            case SessionItem session -> session.row();
            case TaskItem task -> task.row();
        };
    }

    private static String key(Item item) {
        return switch (item) {
            case SessionItem session -> "s:" + session.row().session().id();
            case TaskItem task -> "t:" + task.row().id().text();
        };
    }

    /** The group toggles as they stand. */
    InboxViewState.Toggles toggles() {
        return new InboxViewState.Toggles(collapsed, expanded, showingAll);
    }

    /** Replaces the group toggles with {@code restored} (no change is reported). */
    void restore(InboxViewState.Toggles restored) {
        collapsed.clear();
        collapsed.addAll(restored.collapsed());
        expanded.clear();
        expanded.addAll(restored.expanded());
        showingAll.clear();
        showingAll.addAll(restored.showingAll());
        rebuild(selectedItems().stream().map(InboxList::key).toList());
    }

    void toggleCollapsed(GroupKind kind) {
        boolean isCollapsed = isCollapsed(kind);
        if (kind.collapsedByDefault()) {
            if (isCollapsed) {
                expanded.add(kind);
            } else {
                expanded.remove(kind);
            }
        } else if (isCollapsed) {
            collapsed.remove(kind);
        } else {
            collapsed.add(kind);
        }
        rebuild(selectedItems().stream().map(InboxList::key).toList());
        actions.togglesChanged();
    }

    void toggleShowAll(GroupKind kind) {
        if (!showingAll.remove(kind)) {
            showingAll.add(kind);
        }
        rebuild(selectedItems().stream().map(InboxList::key).toList());
        actions.togglesChanged();
    }

    private boolean isGraphCollapsed(Graph graph) {
        return graph.nodes().stream().anyMatch(node -> collapsedGraphTasks.contains(node.item().row().id()));
    }

    /** The graph the selected entry is, or a task of; empty for anything else. */
    Optional<Graph> selectedGraph() {
        Entry entry = getSelectedValue();
        if (entry instanceof GraphHeader header) {
            return Optional.of(header.graph());
        }
        if (entry instanceof Row row && row.node() != null && inbox != null) {
            return inbox.graphs().stream().filter(graph -> graph.contains(row.item() instanceof TaskItem task
                    ? task.row().id() : null)).findFirst();
        }
        return Optional.empty();
    }

    private boolean isCollapsed(GroupKind kind) {
        return kind.collapsedByDefault() ? !expanded.contains(kind) : collapsed.contains(kind);
    }

    private void rebuild(List<String> selectedKeys) {
        Entry lead = getSelectedValue();
        entries.clear();
        if (inbox == null) {
            return;
        }
        Inbox shown = graphView ? inbox.graphed() : inbox;
        List<Group> groups = new ArrayList<>(shown.groups());
        // The graphs live under "Ready to start", which therefore exists whenever there is a graph.
        boolean withGraphs = graphView && !shown.graphs().isEmpty();
        if (withGraphs && groups.stream().noneMatch(group -> group.kind() == GroupKind.READY)) {
            int at = 0;
            while (at < groups.size() && groups.get(at).kind().compareTo(GroupKind.READY) < 0) {
                at++;
            }
            groups.add(at, new Group(GroupKind.READY, GroupKind.READY.label(), List.of()));
        }
        for (Group group : groups) {
            boolean isCollapsed = isCollapsed(group.kind());
            boolean hasGraphs = withGraphs && group.kind() == GroupKind.READY;
            entries.addElement(new Header(group, isCollapsed, group.items().size()
                    + (hasGraphs ? readyInGraphs(shown) : 0)));
            if (isCollapsed) {
                continue;
            }
            if (hasGraphs) {
                addGraphs(shown);
            }
            boolean all = showingAll.contains(group.kind());
            for (Item item : group.shown(all)) {
                entries.addElement(new Row(group, item));
            }
            int hidden = group.hidden(all);
            if (hidden > 0 || all && group.hidden(false) > 0) {
                entries.addElement(new More(group, hidden, all));
            }
        }
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry instanceof Row row && selectedKeys.contains(key(row.item()))
                    || lead instanceof Header header && entry instanceof Header now
                            && now.group().kind() == header.group().kind()
                    || lead instanceof GraphHeader graph && entry instanceof GraphHeader now
                            && now.graph().key().equals(graph.graph().key())
                    || lead instanceof More more && entry instanceof More now
                            && now.group().kind() == more.group().kind()) {
                indices.add(i);
            }
        }
        setSelectedIndices(indices.stream().mapToInt(Integer::intValue).toArray());
    }

    /** The graph tasks that are ready to start (or interrupted, which the group lists as ready); blocked and in-review ones are not counted. */
    private static int readyInGraphs(Inbox shown) {
        return (int) shown.graphs().stream().flatMap(graph -> graph.nodes().stream())
                .filter(node -> node.item().row().presentation() == Presentation.READY
                        || node.item().row().presentation() == Presentation.INTERRUPTED).count();
    }

    private void addGraphs(Inbox shown) {
        for (Graph graph : shown.graphs()) {
            boolean isCollapsed = isGraphCollapsed(graph);
            entries.addElement(new GraphHeader(graph, isCollapsed));
            if (!isCollapsed) {
                for (GraphNode node : graph.nodes()) {
                    entries.addElement(new Row(null, node.item(), node));
                }
            }
        }
    }

    /** The header text of a graph: its one outcome's title, or its first task's title and how many more it has. */
    static String graphLabel(Graph graph) {
        return graph.sinks().size() == 1 ? graph.sinks().get(0).title()
                : graph.nodes().get(0).item().title() + " (+" + (graph.nodes().size() - 1) + " more)";
    }

    /** The chip a graph task wears: how many tasks it waits on (their titles are in its tooltip), else how many wait on it; empty for neither. */
    static Optional<String> graphChip(GraphNode node) {
        if (!node.waitsOn().isEmpty()) {
            return Optional.of(node.waitsOn().size() == 1 ? "waits on 1" : "waits on " + node.waitsOn().size());
        }
        return node.unblocks() > 0 ? Optional.of("unblocks " + node.unblocks()) : Optional.empty();
    }

    /** Where {@code point} (list coordinates) lands within the row at {@code index}. */
    Hit hit(int index, Point point) {
        Rectangle cell = getCellBounds(index, index);
        Component component = renderer.getListCellRendererComponent(this, entries.get(index), index, false, false);
        component.setBounds(0, 0, cell.width, cell.height);
        layoutAll(component);
        Component at = SwingUtilities.getDeepestComponentAt(component, point.x - cell.x, point.y - cell.y);
        if (at == renderer.pullRequest && renderer.pullRequest.isVisible()
                && renderer.pullRequestLink) {
            return Hit.PULL_REQUEST;
        }
        if (at == renderer.startGraph && renderer.startGraph.isVisible()) {
            return Hit.START_GRAPH;
        }
        if (at == renderer.tag && renderer.tag.isVisible()) {
            return Hit.TAG;
        }
        return Hit.ELSEWHERE;
    }

    private static void layoutAll(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) {
                layoutAll(child);
            }
        }
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        int index = locationToIndex(event.getPoint());
        if (index < 0 || !getCellBounds(index, index).contains(event.getPoint())
                || !(entries.get(index) instanceof Row row)) {
            return null;
        }
        String waits = row.node() == null || row.node().waitsOn().isEmpty() ? ""
                : "\nWaits on: " + String.join(", ", row.node().waitsOn());
        return htmlTooltip(tooltipText(row.item(), Instant.now()) + waits);
    }

    /**
     * The row's tooltip: what the row no longer shows inline. A session: where
     * it runs, its agent and age, read-only and missing-folder marks, its
     * branch's pull request, the issues its tasks are mirrored into, and the
     * started tasks it carries. A task: its id, state, age, staleness,
     * blockers and sessions, its plan's progress, its mirrors and its pull
     * request, then its full description (with session notes).
     */
    static String tooltip(Item item, Instant now) {
        return htmlTooltip(tooltipText(item, now));
    }

    /** {@link #tooltip} before it is escaped into HTML. */
    private static String tooltipText(Item item, Instant now) {
        StringBuilder text = new StringBuilder();
        switch (item) {
            case SessionItem session -> {
                TaskRow task = session.row();
                String agent = AgentLabels.displayName(DrydockPluginService.getInstance().registry(),
                        task.session().agentKind());
                text.append(TaskTreeRenderer.secondary(task, agent,
                        Duration.between(task.session().lastOpenedAt(), now)));
                if (task.readOnly()) {
                    text.append(" · read-only");
                }
                if (task.folderMissing()) {
                    text.append(" · folder missing");
                }
                task.branchPullRequest().ifPresent(pr -> {
                    text.append(" · ").append(TaskTreeRenderer.pullRequestTooltip(pr));
                    TaskTreeRenderer.healthTooltip(pr).ifPresent(health -> text.append(" · ").append(health));
                });
                List<String> mirrors = TaskMirroring.mirrorsOfSession(task.session().id().toString());
                if (!mirrors.isEmpty()) {
                    text.append(" · mirrored to ").append(String.join(", ",
                            mirrors.stream().map(TaskMirror::label).toList()));
                }
                session.taskTitles().forEach(title -> text.append(" · task: ").append(title));
                task.babysit().ifPresent(part -> text.append(" · ").append(part.tooltipText()));
            }
            case TaskItem task -> {
                DelayedTaskRow row = task.row();
                text.append(TaskTreeRenderer.delayedTooltip(row, now));
                TaskTreeRenderer.planProgressOf(row.id())
                        .ifPresent(progress -> text.append(" · plan ").append(progress.text()));
                if (!row.mirrors().isEmpty()) {
                    text.append(" · mirrored to ").append(String.join(", ",
                            row.mirrors().stream().map(TaskMirror::label).toList()));
                }
                row.pullRequest().ifPresent(pr -> {
                    text.append(" · ").append(TaskTreeRenderer.pullRequestLinkTooltip(pr));
                    TaskTreeRenderer.healthTooltip(pr.number(), Optional.of(pr.baseRefName()), pr.conflicting(),
                            pr.ciFailures()).ifPresent(health -> text.append(" · ").append(health));
                });
                text.append(TaskTreeRenderer.descriptionHover(row.body()));
            }
        }
        return text.toString();
    }

    /**
     * Converts tooltip text into Swing's HTML tooltip format, escaping content
     * before adding markup so user-authored task descriptions remain literal.
     */
    static String htmlTooltip(String text) {
        String escaped = text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
        String lines = escaped.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "<br>");
        return "<html>" + lines + "</html>";
    }

    /**
     * A running session's window chip: where its console is shown, when that
     * is not this window — "in its own window" or "in {@code <repo>}" +
     * " window" ({@link TaskPlacement} is the vocabulary; the words are its
     * own). Empty for a session shown here and for one no window shows —
     * neither runs in a different window — and for task rows.
     */
    static Optional<String> windowTag(Item item) {
        if (item instanceof SessionItem session) {
            TaskPlacement placement = session.row().placement();
            if (placement instanceof TaskPlacement.OwnWindow
                    || placement instanceof TaskPlacement.OtherWindow) {
                return Optional.of(placement.meta());
            }
        }
        return Optional.empty();
    }

    /**
     * A session row's babysitting chip ({@link SessionBabysit#chipText}):
     * {@code watching #12} on a babysitter, {@code babysat · escalated} on
     * a watched session. Empty for everything else. Reads the carried row
     * only.
     */
    static Optional<String> babysitChip(Item item) {
        return item instanceof SessionItem session
                ? session.row().babysit().map(SessionBabysit::chipText) : Optional.empty();
    }

    /**
     * A flagged session's chip when its group no longer says it: "flagged"
     * on a row shown in Running. Empty otherwise -- the Flagged section's
     * header already says it. Reads the carried row only.
     */
    static Optional<String> flaggedChip(Item item, GroupKind group) {
        return group == GroupKind.RUNNING && item instanceof SessionItem session
                && session.row().session().flagged() ? Optional.of("flagged") : Optional.empty();
    }

    /**
     * Whether the row is a watched session whose engagement needs the human:
     * it wears the Needs you background and the escalation badge. Only the
     * watched side -- that is where the human acts; the babysitter's row
     * says "escalated" in its chip.
     */
    static boolean escalated(Item item) {
        return item instanceof SessionItem session && session.row().babysit()
                .filter(part -> part.role() == SessionBabysit.Role.WATCHED && part.needsHuman()).isPresent();
    }

    private static Optional<TaskRow> session(Item item) {
        return item instanceof SessionItem session ? Optional.of(session.row()) : Optional.empty();
    }

    /** The PR number a row shows, and whether it is a link (its page is known). */
    record PullRequestLabel(int number, boolean link, TaskTreeRenderer.PullRequestBadgeKind kind) {
        PullRequestLabel(int number, boolean link) {
            this(number, link, TaskTreeRenderer.PullRequestBadgeKind.UNKNOWN);
        }
    }

    static Optional<PullRequestLabel> pullRequestLabel(Item item) {
        return switch (item) {
            case SessionItem session -> {
                TaskRow task = session.row();
                if (task.session().targetPullRequest().isPresent()) {
                    var target = task.session().targetPullRequest().get();
                    var kind = task.branchPullRequest()
                            .filter(pr -> pr.number() == target.number()
                                    && pr.url().filter(target.url()::equals).isPresent())
                            .map(TaskTreeRenderer::badgeKind)
                            .orElse(TaskTreeRenderer.PullRequestBadgeKind.UNKNOWN);
                    yield Optional.of(new PullRequestLabel(target.number(), true, kind));
                }
                yield task.branchPullRequest()
                        .map(pr -> new PullRequestLabel(pr.number(), pr.url().isPresent(), TaskTreeRenderer.badgeKind(pr)))
                        .or(() -> TaskTreeRenderer.pullRequestOf(task)
                                .map(number -> new PullRequestLabel(number, false)));
            }
            case TaskItem task -> task.row().pullRequest()
                    .map(pr -> new PullRequestLabel(pr.number(), pr.url().isPresent(), pr.draft()
                            ? TaskTreeRenderer.PullRequestBadgeKind.DRAFT : TaskTreeRenderer.PullRequestBadgeKind.OPEN));
        };
    }

    /**
     * The row's status icon: a session's dot (a babysitter's followed by
     * the watch mark), or the task state's icon, followed by the carried
     * PR's conflict and then CI-failure icons when those are positively
     * known (the session's branch PR, the task's already-linked PR), and
     * last, on a watched session, the escalation mark while its
     * babysitting engagement needs the human. No PR, or nothing known, leaves the base alone.
     */
    static Icon icon(Item item) {
        return switch (item) {
            case SessionItem session -> {
                TaskRow task = session.row();
                SessionStatus status = task.running() ? SessionStatus.RUNNING : task.session().status();
                Icon dot = SessionDots.dotIcon(status, task.activity());
                Icon base = task.babysit().filter(part -> part.role() == SessionBabysit.Role.BABYSITTER).isPresent()
                        ? TaskTreeRenderer.rowIcon(dot, BABYSITTER_ICON) : dot;
                Icon health = task.branchPullRequest()
                        .map(pr -> TaskTreeRenderer.healthIcon(base, pr.conflicting(), !pr.ciFailures().isEmpty()))
                        .orElse(base);
                yield escalated(item) ? TaskTreeRenderer.rowIcon(health, ESCALATION_ICON) : health;
            }
            case TaskItem task -> {
                Icon base = stateIcon(task.row());
                yield task.row().pullRequest()
                        .map(pr -> TaskTreeRenderer.healthIcon(base, pr.conflicting(), !pr.ciFailures().isEmpty()))
                        .orElse(base);
            }
        };
    }

    /** A delayed task state's icon. */
    private static Icon stateIcon(DelayedTaskRow row) {
        return switch (row.presentation()) {
            case READY, INTERRUPTED -> AllIcons.Actions.Execute;
            case BLOCKED -> AllIcons.General.Error;
            case REVIEW -> AllIcons.Vcs.History;
            case STARTED -> AllIcons.Actions.Forward;
            case TERMINAL -> AllIcons.Actions.Checked;
        };
    }

    /** A group header's label colour. */
    static Color headerColor(GroupKind kind) {
        return switch (kind) {
            case NEEDS_YOU -> NEEDS_TEXT;
            case BLOCKED -> BLOCKED_TEXT;
            case DONE, IDLE -> NamedColorUtil.getInactiveTextColor();
            default -> UIUtil.getLabelForeground();
        };
    }

    /** Paints headers, rows and toggles; one instance, reused per cell. */
    private final class Renderer implements ListCellRenderer<Entry> {

        private final JPanel panel = new JPanel(new BorderLayout());
        private final SimpleColoredComponent title = new SimpleColoredComponent();
        /** A row's title: a label, so a title wider than the cell ends in an ellipsis. */
        private final JBLabel rowTitle = new JBLabel();
        private final JPanel center = new JPanel(new BorderLayout());
        /** GridBag centres the PR number and the tags vertically on the title's line. */
        private final JPanel trailing = new JPanel(new GridBagLayout());
        private final JBLabel pullRequest = new JBLabel();
        private final JBLabel tag = new JBLabel();
        private final JBLabel windowTag = new JBLabel();
        /** The flagged session's chip, for a row the Running section shows. */
        private final JBLabel flagTag = new JBLabel();
        /** The babysitting engagement chip, in the editor's monospace so it reads as machine state. */
        private final JBLabel babysitTag = new JBLabel();
        /** A graph task's dependency chip. */
        private final JBLabel graphChip = new JBLabel();
        /** A graph header's action. */
        private final JBLabel startGraph = new JBLabel("Start graph");
        private boolean pullRequestLink;

        Renderer() {
            title.setOpaque(false);
            title.setIpad(JBUI.emptyInsets());
            rowTitle.setIconTextGap(JBUI.scale(6));
            center.setOpaque(false);
            trailing.setOpaque(false);
            GridBagConstraints spaced = new GridBagConstraints();
            spaced.insets = JBUI.insetsLeft(6);
            trailing.add(pullRequest, spaced);
            trailing.add(tag, spaced);
            trailing.add(windowTag, spaced);
            trailing.add(flagTag, spaced);
            trailing.add(babysitTag, spaced);
            trailing.add(graphChip, spaced);
            trailing.add(startGraph, spaced);
            graphChip.setFont(JBUI.Fonts.smallFont());
            startGraph.setFont(JBUI.Fonts.smallFont());
            tag.setFont(JBUI.Fonts.smallFont());
            windowTag.setFont(JBUI.Fonts.smallFont());
            flagTag.setFont(JBUI.Fonts.smallFont());
            // The small font's size is already scaled; only the family changes.
            babysitTag.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.smallFont().getSize()));
            panel.add(center, BorderLayout.CENTER);
            panel.add(trailing, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Entry> list, Entry entry, int index,
                                                      boolean selected, boolean focused) {
            title.clear();
            center.removeAll();
            center.add(entry instanceof Row ? rowTitle : title, BorderLayout.CENTER);
            trailing.setVisible(false);
            graphChip.setVisible(false);
            startGraph.setVisible(false);
            pullRequestLink = false;
            boolean focus = list.hasFocus();
            Color background = selected ? UIUtil.getListSelectionBackground(focus) : list.getBackground();
            Color foreground = selected ? UIUtil.getListSelectionForeground(focus) : UIUtil.getListForeground();
            int height = JBUI.scale(30);
            switch (entry) {
                case Header header -> {
                    GroupKind kind = header.group().kind();
                    title.setIcon(header.collapsed() ? AllIcons.General.ArrowRight : AllIcons.General.ArrowDown);
                    title.append(header.group().label(), new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD,
                            selected ? foreground : headerColor(kind)));
                    title.append(" · " + header.count(), new SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_PLAIN, selected ? foreground
                            : NamedColorUtil.getInactiveTextColor()));
                    boolean first = index == 0;
                    panel.setBorder(JBUI.Borders.empty(first ? 4 : 12, 12, 0, 12));
                    height = JBUI.scale(first ? 26 : 34);
                }
                case GraphHeader header -> {
                    Graph graph = header.graph();
                    title.setIcon(header.collapsed() ? AllIcons.General.ArrowRight : AllIcons.General.ArrowDown);
                    title.append(graphLabel(graph), new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD,
                            selected ? foreground : UIUtil.getLabelForeground()));
                    title.append(" · " + graph.done() + "/" + graph.total() + " done", new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN,
                            selected ? foreground : NamedColorUtil.getInactiveTextColor()));
                    startGraph.setVisible(!graph.startTargets().isEmpty());
                    startGraph.setForeground(selected ? foreground : JBUI.CurrentTheme.Link.Foreground.ENABLED);
                    // One project: the header names it. Several: each task row names its own.
                    Optional<ProjectRef> graphProject = tagsHidden || graph.projects().size() != 1
                            ? Optional.empty() : Optional.of(graph.projects().get(0));
                    tag.setVisible(graphProject.isPresent());
                    graphProject.ifPresent(ref -> {
                        tag.setText(ref.name());
                        tag.setForeground(selected ? foreground : NamedColorUtil.getInactiveTextColor());
                        tag.setBorder(BorderFactory.createCompoundBorder(
                                JBUI.Borders.customLine(TAG_BORDER, 1), JBUI.Borders.empty(0, 4)));
                    });
                    pullRequest.setVisible(false);
                    windowTag.setVisible(false);
                    babysitTag.setVisible(false);
                    trailing.setVisible(startGraph.isVisible() || tag.isVisible());
                    panel.setBorder(JBUI.Borders.empty(6, 30, 0, 12));
                    height = JBUI.scale(28);
                }
                case More more -> {
                    title.setIcon(null);
                    title.append(more.showingAll() ? "Show less" : "+ " + more.hidden() + " more",
                            selected ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, foreground)
                                    : SimpleTextAttributes.LINK_ATTRIBUTES);
                    panel.setBorder(JBUI.Borders.empty(0, 34, 0, 12));
                    height = JBUI.scale(24);
                }
                case Row row -> {
                    Item item = row.item();
                    boolean needs = row.group() != null && row.group().kind() == GroupKind.NEEDS_YOU
                            || escalated(item);
                    if (!selected && needs) {
                        background = NEEDS_BACKGROUND;
                    }
                    // The Jira priority badge rides a task row's own icon when one
                    // of its mirrors names a Jira ticket with a known priority.
                    Optional<TaskTreeRenderer.PriorityKind> jiraPriority =
                            item instanceof TaskItem task
                                    ? TaskTreeRenderer.jiraMirrorPriority(task.row().mirrors(),
                                            jiraPriorities.get())
                                    : Optional.empty();
                    rowTitle.setIcon(TaskTreeRenderer.withPriorityBadge(icon(item), jiraPriority));
                    rowTitle.setText(item.title());
                    rowTitle.setForeground(foreground);
                    Optional<PullRequestLabel> pr = pullRequestLabel(item);
                    pullRequest.setVisible(pr.isPresent());
                    pr.ifPresent(label -> {
                        pullRequestLink = label.link();
                        pullRequest.setText("#" + label.number());
                        pullRequest.setIcon(TaskTreeRenderer.pullRequestIcon(label.kind()).orElse(null));
                        pullRequest.setForeground(selected ? foreground : label.link()
                                ? JBUI.CurrentTheme.Link.Foreground.ENABLED : NamedColorUtil.getInactiveTextColor());
                    });
                    Optional<ProjectRef> project = tagsHidden || row.node() != null && !row.node().mixedProjects()
                            ? Optional.empty()
                            : item.project();
                    tag.setVisible(project.isPresent());
                    project.ifPresent(ref -> {
                        tag.setText(ref.name());
                        tag.setForeground(selected ? foreground : NamedColorUtil.getInactiveTextColor());
                        tag.setBorder(BorderFactory.createCompoundBorder(
                                JBUI.Borders.customLine(needs ? NEEDS_TAG_BORDER : TAG_BORDER, 1),
                                JBUI.Borders.empty(0, 4)));
                    });
                    Optional<String> window = windowTag(item);
                    windowTag.setVisible(window.isPresent());
                    window.ifPresent(text -> {
                        windowTag.setText(text);
                        windowTag.setForeground(selected ? foreground : NamedColorUtil.getInactiveTextColor());
                        windowTag.setBorder(BorderFactory.createCompoundBorder(
                                JBUI.Borders.customLine(needs ? NEEDS_TAG_BORDER : TAG_BORDER, 1),
                                JBUI.Borders.empty(0, 4)));
                    });
                    Optional<String> flag = row.group() == null ? Optional.empty() : flaggedChip(item, row.group().kind());
                    flagTag.setVisible(flag.isPresent());
                    flag.ifPresent(text -> {
                        flagTag.setText(text);
                        flagTag.setForeground(selected ? foreground : NamedColorUtil.getInactiveTextColor());
                        flagTag.setBorder(BorderFactory.createCompoundBorder(
                                JBUI.Borders.customLine(needs ? NEEDS_TAG_BORDER : TAG_BORDER, 1),
                                JBUI.Borders.empty(0, 4)));
                    });
                    Optional<String> babysit = babysitChip(item);
                    babysitTag.setVisible(babysit.isPresent());
                    babysit.ifPresent(text -> {
                        babysitTag.setText(text);
                        babysitTag.setForeground(selected ? foreground
                                : session(item).flatMap(TaskRow::babysit).filter(SessionBabysit::needsHuman)
                                        .isPresent() ? NEEDS_TEXT : NamedColorUtil.getInactiveTextColor());
                        babysitTag.setBorder(BorderFactory.createCompoundBorder(
                                JBUI.Borders.customLine(needs ? NEEDS_TAG_BORDER : BABYSIT_TAG_BORDER, 1),
                                JBUI.Borders.empty(0, 4)));
                    });
                    Optional<String> chip = row.node() == null ? Optional.empty() : graphChip(row.node());
                    graphChip.setVisible(chip.isPresent());
                    chip.ifPresent(text -> {
                        graphChip.setText(text);
                        graphChip.setForeground(selected ? foreground : row.node().waitsOn().isEmpty()
                                ? JBUI.CurrentTheme.Link.Foreground.ENABLED : NamedColorUtil.getInactiveTextColor());
                    });
                    trailing.setVisible(pr.isPresent() || project.isPresent() || window.isPresent()
                            || flag.isPresent() || babysit.isPresent() || chip.isPresent());
                    panel.setBorder(JBUI.Borders.empty(0, row.node() == null ? 30 : 48 + 14 * row.node().depth(),
                            0, 12));
                }
            }
            title.setIconTextGap(JBUI.scale(6));
            panel.setBackground(background);
            panel.setPreferredSize(new Dimension(0, height));
            return panel;
        }
    }

    /** The entries the list currently shows, in order. */
    List<Entry> entries() {
        List<Entry> out = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            out.add(entries.get(i));
        }
        return out;
    }
}
