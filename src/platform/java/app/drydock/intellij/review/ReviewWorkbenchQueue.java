package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.FlowModel;
import app.drydock.intellij.review.workbench.model.SectionsModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScrollingUtil;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The flow review's queue (design A), the workbench's left pane: a
 * Sections | Files switch, the count still to see and a Hide Seen toggle,
 * the change's intent (Sections only), then the rows of {@link FlowModel.Queue#rows}:
 * each section's or file's header and its hunks, marked seen, flagged or
 * not seen. Picking a hunk shows it in the centre, which marks the hunk
 * left as seen. EDT.
 */
final class ReviewWorkbenchQueue extends JPanel {

    static final String SECTIONS = "Sections";
    static final String FILES = "Files";
    static final String HIDE_SEEN = "Hide Seen";
    static final String INTENT = "Intent";
    static final String PR_DESCRIPTION = "PR description";
    static final String COMPUTED_NOTE = "Computed sections: the agent has posted no tour for this review.";
    /** The most characters of the PR description the intro shows. */
    static final int DESCRIPTION_CHARS = 400;
    static final String FOOTER = "Hunks mark themselves seen when you move on. Nothing to click.";
    private static final String TOOLBAR_PLACE = "DrydockReviewQueue";

    private final ReviewWorkbenchFlow flow;
    final DefaultActionGroup tabActions;
    private final ActionToolbar toolbar;
    final JBLabel toGo = new JBLabel();
    /** The Sections tab's intro: the agent's intent, the PR description's first paragraph, the computed note. */
    private final JPanel intentBox = new JPanel(new BorderLayout());
    private final JPanel intentBlock;
    private final JTextArea intentText;
    private final JPanel prBlock;
    private final JTextArea prText;
    private final JTextArea note;
    private final DefaultListModel<FlowModel.Row> model = new DefaultListModel<>();
    /** The rows {@link #model} holds, as last rendered. */
    private List<FlowModel.Row> shownRows = List.of();
    final JBList<FlowModel.Row> list = new JBList<>(model);
    /** True while the list is filled or its selection follows the flow, so the selection is not a pick. */
    private boolean syncing;

    ReviewWorkbenchQueue(ReviewWorkbenchFlow flow, JButton hide) {
        super(new BorderLayout());
        this.flow = Objects.requireNonNull(flow, "flow");
        tabActions = new DefaultActionGroup(new TabToggle(WorkbenchState.Navigator.SECTIONS, SECTIONS),
                new TabToggle(WorkbenchState.Navigator.FILES, FILES));
        DefaultActionGroup right = new DefaultActionGroup(new HideSeenToggle());
        toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, tabActions, true);
        toolbar.setTargetComponent(this);
        ActionToolbar rightBar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, right, true);
        rightBar.setTargetComponent(this);
        ReviewTypography.apply(toGo, ReviewTypography.Role.META);
        // Two rows: the pane is often narrower than the switch, the count, the
        // toggle and the hide button side by side.
        JPanel tabsRow = new JPanel(new BorderLayout());
        tabsRow.setOpaque(false);
        tabsRow.add(toolbar.getComponent(), BorderLayout.WEST);
        if (hide != null) {
            hide.setContentAreaFilled(false);
            hide.setBorderPainted(false);
            tabsRow.add(hide, BorderLayout.EAST);
        }
        JPanel countRow = new JPanel(new BorderLayout());
        countRow.setOpaque(false);
        countRow.setBorder(JBUI.Borders.emptyLeft(6));
        countRow.add(toGo, BorderLayout.WEST);
        countRow.add(rightBar.getComponent(), BorderLayout.EAST);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setBorder(JBUI.Borders.empty(4, 4, 4, 6));
        top.add(tabsRow, BorderLayout.NORTH);
        top.add(countRow, BorderLayout.SOUTH);

        intentText = ReviewTypography.prose(ReviewTypography.Role.BODY);
        prText = ReviewTypography.prose(ReviewTypography.Role.BODY);
        note = ReviewTypography.prose(ReviewTypography.Role.META);
        note.setText(COMPUTED_NOTE);
        intentBlock = introBlock(INTENT, intentText);
        prBlock = introBlock(PR_DESCRIPTION, prText);
        JPanel intro = new JPanel(new VerticalLayout(ReviewTypography.M));
        intro.setOpaque(false);
        intro.add(intentBlock);
        intro.add(prBlock);
        intro.add(ReviewTypography.measured(note));
        intentBox.setBorder(JBUI.Borders.compound(JBUI.Borders.empty(0, 10, 8, 10),
                JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(8, 10))));
        intentBox.setOpaque(false);
        intentBox.add(intro, BorderLayout.CENTER);

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(top, BorderLayout.NORTH);
        north.add(intentBox, BorderLayout.CENTER);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new RowRenderer());
        list.getEmptyText().setText("No hunks to review.");
        list.addListSelectionListener(e -> {
            if (syncing || e.getValueIsAdjusting()) {
                return;
            }
            if (list.getSelectedValue() instanceof FlowModel.Item item) {
                flow.go(item.hunk().key());
            } else {
                render(); // a header is not a pick: the selection goes back to the current hunk
            }
        });
        ScrollingUtil.installActions(list);

        JBLabel footer = new JBLabel(FOOTER);
        ReviewTypography.apply(footer, ReviewTypography.Role.META);
        footer.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()),
                JBUI.Borders.empty(8, 12)));

        add(north, BorderLayout.NORTH);
        add(new JBScrollPane(list), BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        flow.addListener(this::render);
        render();
    }

    /** Shows the flow's queue, with the current hunk selected. */
    void render() {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("ReviewWorkbenchQueue renders on the EDT");
        }
        FlowModel.Queue queue = flow.queue();
        Optional<String> current = flow.current().map(FlowModel.Hunk::key);
        List<FlowModel.Row> rows = queue.rows(flow.seen(), flow.flagged(), flow.hideSeen(),
                flow.finishing() ? Optional.empty() : current);
        int todo = queue.order().size() - queue.seenCount(flow.seen());
        toGo.setText(todo + " to go");
        WorkbenchState state = flow.state();
        Optional<String> description = state == null ? Optional.empty()
                : state.prDescription().map(text -> firstParagraph(text, DESCRIPTION_CHARS));
        boolean computed = state != null && state.sections().source() == SectionsModel.Source.COMPUTED;
        intentText.setText(queue.intent().orElse(""));
        intentBlock.setVisible(queue.intent().isPresent());
        prText.setText(description.orElse(""));
        prBlock.setVisible(description.isPresent());
        note.setVisible(computed);
        // No intro in the Files tab, and none with nothing to say: an empty box reads as a bug.
        intentBox.setVisible(queue.sections() && (queue.intent().isPresent() || description.isPresent() || computed));
        syncing = true;
        try {
            if (!rows.equals(shownRows)) {
                model.clear();
                model.addAll(rows);
                shownRows = rows;
            }
            int selected = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i) instanceof FlowModel.Item item && item.current()) {
                    selected = i;
                    break;
                }
            }
            if (selected < 0) {
                list.clearSelection();
            } else {
                list.setSelectedIndex(selected);
                list.ensureIndexIsVisible(selected);
            }
        } finally {
            syncing = false;
        }
        toolbar.updateActionsAsync();
    }

    /** An intro block: the eyebrow label over its prose. */
    private static JPanel introBlock(String eyebrow, JTextArea prose) {
        JPanel block = new JPanel(new VerticalLayout(ReviewTypography.XS));
        block.setOpaque(false);
        block.add(ReviewTypography.label(eyebrow, ReviewTypography.Role.EYEBROW));
        block.add(ReviewTypography.measured(prose));
        return block;
    }

    /**
     * The description's first paragraph (pure): markdown heading lines
     * dropped, then the text up to its first blank line, whitespace
     * collapsed, cut at {@code max} characters with an ellipsis.
     */
    static String firstParagraph(String text, int max) {
        String first = text.replaceAll("(?m)^\\s*#+\\s.*$", "").strip().split("\\R\\s*\\R", 2)[0]
                .replaceAll("\\s+", " ").strip();
        return first.length() <= max ? first : first.substring(0, max).strip() + "…";
    }

    /** The strip's label while the pane is collapsed (pure): "Sections · 3 to go". */
    static String stripLabel(FlowModel.Queue queue, Set<String> seen) {
        return (queue.sections() ? SECTIONS : FILES) + " · " + (queue.order().size() - queue.seenCount(seen))
                + " to go";
    }

    private final class TabToggle extends DumbAwareToggleAction {
        private final WorkbenchState.Navigator target;

        TabToggle(WorkbenchState.Navigator target, String text) {
            super(text);
            this.target = target;
            ReviewWorkbenchEditor.showTextInToolbar(this);
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return flow.queue().sections() == (target == WorkbenchState.Navigator.SECTIONS);
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean selected) {
            if (selected) {
                flow.showTab(target);
            }
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            super.update(e);
            WorkbenchState state = flow.state();
            // Sections need the agent's tour or the computed sections.
            e.getPresentation().setEnabled(target == WorkbenchState.Navigator.FILES
                    || (state != null && !state.sections().sections().isEmpty()));
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    private final class HideSeenToggle extends DumbAwareToggleAction {
        HideSeenToggle() {
            super(HIDE_SEEN, "Hide the hunks already seen", AllIcons.Actions.Show);
            ReviewWorkbenchEditor.showTextInToolbar(this);
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return flow.hideSeen();
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean selected) {
            flow.setHideSeen(selected);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    /** Renders a header (section: "§n title", meta line; file: icon, name, directory) or a hunk row. */
    private static final class RowRenderer extends ColoredListCellRenderer<FlowModel.Row> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends FlowModel.Row> list, FlowModel.Row row,
                                             int index, boolean selected, boolean hasFocus) {
            switch (row) {
                case FlowModel.Header header -> header(header);
                case FlowModel.Item item -> item(item);
            }
        }

        private void header(FlowModel.Header header) {
            FlowModel.Group group = header.group();
            setIpad(JBUI.insets(8, 4, 2, 4));
            if (group.section()) {
                append("§" + group.number().orElseThrow() + "  ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES);
                append(group.title(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                append("   " + header.meta(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                setToolTipText(group.title() + " — " + header.meta());
            } else {
                String path = group.title();
                int slash = path.lastIndexOf('/');
                String name = path.substring(slash + 1);
                setIcon(FileTypeManager.getInstance().getFileTypeByFileName(name).getIcon());
                append(name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                if (slash >= 0) {
                    append("  " + path.substring(0, slash), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
                setToolTipText(path);
            }
        }

        private void item(FlowModel.Item item) {
            FlowModel.Hunk hunk = item.hunk();
            setIpad(JBUI.insets(2, 20, 2, 4));
            setIcon(switch (item.mark()) {
                case SEEN -> AllIcons.Actions.Checked;
                case FLAGGED -> AllIcons.Nodes.Bookmark;
                case OPEN -> RingIcon.INSTANCE;
            });
            if (item.showFile()) {
                append(hunk.fileName() + "  ");
            }
            append(hunk.lines().isEmpty() ? "hunk " + (hunk.index() + 1) : hunk.lines(),
                    SimpleTextAttributes.GRAYED_ATTRIBUTES);
            if (!item.showFile()) {
                append("  +" + hunk.added(), new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER,
                        FileStatus.ADDED.getColor()));
                append(" −" + hunk.removed(), new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER,
                        FileStatus.DELETED.getColor()));
            }
            if (hunk.openFindings() > 0) {
                append("   ◆ " + hunk.openFindings(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            }
            setToolTipText(hunk.file() + (hunk.lines().isEmpty() ? "" : " · " + hunk.lines())
                    + switch (item.mark()) {
                        case SEEN -> " · seen";
                        case FLAGGED -> " · flagged";
                        case OPEN -> " · not seen";
                    }
                    + (hunk.openFindings() > 0 ? " · " + hunk.openFindings() + " open findings" : ""));
        }
    }

    /** The not-seen mark: a hollow ring in the muted foreground. */
    private static final class RingIcon implements Icon {
        static final RingIcon INSTANCE = new RingIcon();

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D graphics = (Graphics2D) g.create();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setColor(UIUtil.getContextHelpForeground());
                int size = JBUI.scale(9);
                int inset = (getIconWidth() - size) / 2;
                graphics.drawOval(x + inset, y + inset, size, size);
            } finally {
                graphics.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return JBUI.scale(16);
        }

        @Override
        public int getIconHeight() {
            return JBUI.scale(16);
        }
    }

    /** The queue's list, for the workbench's preferred focus. */
    JComponent focusTarget() {
        return list;
    }
}
