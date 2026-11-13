package app.drydock.intellij.tasks;

import app.drydock.domain.SessionStatus;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.tasks.InboxModel.Group;
import app.drydock.intellij.tasks.InboxModel.GroupKind;
import app.drydock.intellij.tasks.InboxModel.Inbox;
import app.drydock.intellij.tasks.InboxModel.Item;
import app.drydock.intellij.tasks.InboxModel.ProjectRef;
import app.drydock.intellij.tasks.InboxModel.SessionItem;
import app.drydock.intellij.tasks.InboxModel.TaskItem;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.ui.SessionDots;
import app.drydock.tasks.TaskMirror;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The Attention Inbox's list: one header per non-empty {@link Group}, its
 * rows, and a "+N more" / "Show less" toggle where the group truncates.
 * Clicking a header (or Enter on it) collapses or expands the group; the
 * collapsed and show-all sets live here, seeded from each group's default.
 * A row is {@code [icon] title … [#PR] [project]}: the PR number opens the
 * pull request, the project tag reports the project to the owner, and a
 * double-click or Enter activates the row. EDT only.
 */
final class InboxList extends JBList<InboxList.Entry> {

    /** One list entry. */
    sealed interface Entry {
    }

    /** A group's header line. */
    record Header(Group group, boolean collapsed) implements Entry {
    }

    /** One item of a group. */
    record Row(Group group, Item item) implements Entry {
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

        /** A group was collapsed, expanded, or switched between "+N more" and all rows. */
        default void togglesChanged() {
        }
    }

    /** Where a click on a row landed. */
    enum Hit { PULL_REQUEST, TAG, ELSEWHERE }

    static final JBColor NEEDS_TEXT = new JBColor(0xA64B0A, 0xE5925B);
    static final JBColor NEEDS_BACKGROUND = new JBColor(0xFFF4EB, 0x3B2F25);
    static final JBColor NEEDS_TAG_BORDER = new JBColor(0xE4D3C4, 0x5C4A3B);
    static final JBColor BLOCKED_TEXT = new JBColor(0xB4282E, 0xE5777C);
    static final JBColor TAG_BORDER = new JBColor(0xEBECF0, 0x43454A);

    private final DefaultListModel<Entry> entries = new DefaultListModel<>();
    private final Set<GroupKind> collapsed = EnumSet.noneOf(GroupKind.class);
    private final Set<GroupKind> expanded = EnumSet.noneOf(GroupKind.class);
    private final Set<GroupKind> showingAll = EnumSet.noneOf(GroupKind.class);
    private final Renderer renderer = new Renderer();
    private final Actions actions;
    private Inbox inbox;
    private boolean tagsHidden;

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
                switch (getSelectedValue()) {
                    case Header header -> toggleCollapsed(header.group().kind());
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
        rebuild(selectedKeys);
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

    private boolean isCollapsed(GroupKind kind) {
        return kind.collapsedByDefault() ? !expanded.contains(kind) : collapsed.contains(kind);
    }

    private void rebuild(List<String> selectedKeys) {
        Entry lead = getSelectedValue();
        entries.clear();
        if (inbox == null) {
            return;
        }
        for (Group group : inbox.groups()) {
            boolean isCollapsed = isCollapsed(group.kind());
            entries.addElement(new Header(group, isCollapsed));
            if (isCollapsed) {
                continue;
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
                    || lead instanceof More more && entry instanceof More now
                            && now.group().kind() == more.group().kind()) {
                indices.add(i);
            }
        }
        setSelectedIndices(indices.stream().mapToInt(Integer::intValue).toArray());
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
        return tooltip(row.item(), Instant.now());
    }

    /**
     * The row's tooltip: what the row no longer shows inline. A session: where
     * it runs, its agent and age, read-only and missing-folder marks, its
     * branch's pull request, the issues its tasks are mirrored into, and the
     * started tasks it carries. A task: its id, state, age, staleness,
     * blockers and sessions, its plan's progress, its mirrors and its pull
     * request.
     */
    static String tooltip(Item item, Instant now) {
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
                task.branchPullRequest().ifPresent(pr -> text.append(" · ")
                        .append(TaskTreeRenderer.pullRequestTooltip(pr)));
                List<String> mirrors = TaskMirroring.mirrorsOfSession(task.session().id().toString());
                if (!mirrors.isEmpty()) {
                    text.append(" · mirrored to ").append(String.join(", ",
                            mirrors.stream().map(TaskMirror::label).toList()));
                }
                session.taskTitles().forEach(title -> text.append(" · task: ").append(title));
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
                row.pullRequest().ifPresent(pr -> text.append(" · ")
                        .append(TaskTreeRenderer.pullRequestLinkTooltip(pr)));
            }
        }
        return text.toString();
    }

    /** The PR number a row shows, and whether it is a link (its page is known). */
    record PullRequestLabel(int number, boolean link) {
    }

    static Optional<PullRequestLabel> pullRequestLabel(Item item) {
        return switch (item) {
            case SessionItem session -> {
                TaskRow task = session.row();
                Optional<PullRequestLabel> linked = task.branchPullRequest()
                        .filter(pr -> TaskTreeRenderer.pullRequestUrl(task).isPresent())
                        .map(pr -> new PullRequestLabel(pr.number(), true));
                yield linked.or(() -> task.branchPullRequest().map(pr -> pr.number())
                        .or(() -> TaskTreeRenderer.pullRequestOf(task))
                        .map(number -> new PullRequestLabel(number, false)));
            }
            case TaskItem task -> task.row().pullRequest()
                    .map(pr -> new PullRequestLabel(pr.number(), pr.url().isPresent()));
        };
    }

    /** The row's status icon: a session's dot, or the task state's icon. */
    static Icon icon(Item item) {
        return switch (item) {
            case SessionItem session -> {
                TaskRow task = session.row();
                SessionStatus status = task.running() ? SessionStatus.RUNNING : task.session().status();
                yield SessionDots.dotIcon(status, task.activity());
            }
            case TaskItem task -> switch (task.row().presentation()) {
                case READY, INTERRUPTED -> AllIcons.Actions.Execute;
                case BLOCKED -> AllIcons.General.Error;
                case REVIEW -> AllIcons.Vcs.History;
                case STARTED -> AllIcons.Actions.Forward;
                case TERMINAL -> AllIcons.Actions.Checked;
            };
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
        /** GridBag centres the PR number and the tag vertically on the title's line. */
        private final JPanel trailing = new JPanel(new GridBagLayout());
        private final JBLabel pullRequest = new JBLabel();
        private final JBLabel tag = new JBLabel();
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
            tag.setFont(JBUI.Fonts.smallFont());
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
                    title.append(" · " + header.group().items().size(), new SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_PLAIN, selected ? foreground
                            : NamedColorUtil.getInactiveTextColor()));
                    boolean first = index == 0;
                    panel.setBorder(JBUI.Borders.empty(first ? 4 : 12, 12, 0, 12));
                    height = JBUI.scale(first ? 26 : 34);
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
                    boolean needs = row.group().kind() == GroupKind.NEEDS_YOU;
                    if (!selected && needs) {
                        background = NEEDS_BACKGROUND;
                    }
                    rowTitle.setIcon(icon(item));
                    rowTitle.setText(item.title());
                    rowTitle.setForeground(foreground);
                    Optional<PullRequestLabel> pr = pullRequestLabel(item);
                    pullRequest.setVisible(pr.isPresent());
                    pr.ifPresent(label -> {
                        pullRequestLink = label.link();
                        pullRequest.setText("#" + label.number());
                        pullRequest.setForeground(selected ? foreground : label.link()
                                ? JBUI.CurrentTheme.Link.Foreground.ENABLED : NamedColorUtil.getInactiveTextColor());
                    });
                    Optional<ProjectRef> project = tagsHidden ? Optional.empty() : item.project();
                    tag.setVisible(project.isPresent());
                    project.ifPresent(ref -> {
                        tag.setText(ref.name());
                        tag.setForeground(selected ? foreground : NamedColorUtil.getInactiveTextColor());
                        tag.setBorder(BorderFactory.createCompoundBorder(
                                JBUI.Borders.customLine(needs ? NEEDS_TAG_BORDER : TAG_BORDER, 1),
                                JBUI.Borders.empty(0, 4)));
                    });
                    trailing.setVisible(pr.isPresent() || project.isPresent());
                    panel.setBorder(JBUI.Borders.empty(0, 30, 0, 12));
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
