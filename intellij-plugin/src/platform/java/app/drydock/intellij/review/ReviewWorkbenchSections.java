package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.SectionsModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import com.intellij.icons.AllIcons;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The navigator's Sections tab (spec §5): the agent's intent and, in a pull
 * request review, the first paragraph of the PR's description — each under
 * its own eyebrow label, over a hairline — then one row per section with
 * its number, title, progress, open findings and the "builds on / needed
 * by" chips, and a check on every fully reviewed row. Selecting a row
 * selects the section, which the centre then shows. EDT.
 */
final class ReviewWorkbenchSections extends JPanel {

    static final String COMPUTED_NOTE = "Computed sections: the agent has posted no tour for this review.";
    static final String EMPTY_TEXT = "No sections: the agent has posted no tour, and the computed sections are "
            + "still being built or could not be built.";

    private final Consumer<String> select;
    private final JPanel intro = new JPanel(new VerticalLayout(ReviewTypography.M));
    private final JPanel intentBlock = introBlock("Intent");
    private final JTextArea intentText;
    private final JPanel prBlock = introBlock("PR description");
    private final JTextArea prText;
    private final JTextArea note;
    private final DefaultListModel<SectionsModel.Section> rows = new DefaultListModel<>();
    final JBList<SectionsModel.Section> list = new JBList<>(rows);
    /** Set while a render replaces the rows, so the list's own selection events are not the user's. */
    private boolean rendering;

    /** An intro block: the eyebrow label over its prose. */
    private static JPanel introBlock(String eyebrow) {
        JPanel block = new JPanel(new VerticalLayout(ReviewTypography.XS));
        block.setOpaque(false);
        block.add(ReviewTypography.label(eyebrow, ReviewTypography.Role.EYEBROW));
        return block;
    }

    /** {@code select} receives the key of the section the user picked. */
    ReviewWorkbenchSections(Consumer<String> select) {
        super(new BorderLayout());
        this.select = Objects.requireNonNull(select, "select");
        intentText = ReviewTypography.prose(ReviewTypography.Role.BODY);
        prText = ReviewTypography.prose(ReviewTypography.Role.BODY_MUTED);
        note = ReviewTypography.prose(ReviewTypography.Role.META);
        intentBlock.add(intentText);
        prBlock.add(prText);
        JPanel noteBlock = new JPanel(new VerticalLayout(ReviewTypography.XS));
        noteBlock.setOpaque(false);
        noteBlock.add(note);
        intro.setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.borderColor()),
                JBUI.Borders.empty(ReviewTypography.M, ReviewTypography.L)));
        intro.add(intentBlock);
        intro.add(prBlock);
        intro.add(noteBlock);
        list.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new SectionRenderer());
        list.getEmptyText().setText(EMPTY_TEXT);
        list.addListSelectionListener(e -> {
            SectionsModel.Section picked = list.getSelectedValue();
            if (!rendering && !e.getValueIsAdjusting() && picked != null) {
                select.accept(picked.key());
            }
        });
        add(intro, BorderLayout.NORTH);
        add(new JBScrollPane(list), BorderLayout.CENTER);
    }

    /** Shows {@code state}'s sections and selection. */
    void render(WorkbenchState state) {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("ReviewWorkbenchSections renders on the EDT");
        }
        SectionsModel.Snapshot sections = state.sections();
        intentText.setText(sections.intent().orElse(""));
        intentBlock.setVisible(sections.intent().isPresent());
        Optional<String> description = state.prDescription().map(text -> firstParagraph(text, DESCRIPTION_CHARS));
        prText.setText(description.orElse(""));
        prBlock.setVisible(description.isPresent());
        note.setText(COMPUTED_NOTE);
        note.setVisible(sections.source() == SectionsModel.Source.COMPUTED);
        // No intro, no hairline: an empty band above the rows reads as a bug.
        intro.setVisible(sections.intent().isPresent() || description.isPresent()
                || sections.source() == SectionsModel.Source.COMPUTED);
        rendering = true;
        try {
            rows.clear();
            for (SectionsModel.Section section : sections.sections()) {
                rows.addElement(section);
            }
            Optional<String> selected = state.section();
            int index = selected.map(key -> indexOf(sections.sections(), key)).orElse(-1);
            if (index >= 0) {
                list.setSelectedIndex(index);
            } else {
                list.clearSelection();
            }
        } finally {
            rendering = false;
        }
    }

    private static int indexOf(List<SectionsModel.Section> sections, String key) {
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** The most characters of the PR description the tab shows. */
    static final int DESCRIPTION_CHARS = 400;

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

    /** The row's chips (pure): "builds on §1 · needed by §3, §4", empty without dependencies. */
    static String chips(SectionsModel.Section section) {
        StringBuilder text = new StringBuilder();
        if (!section.buildsOn().isEmpty()) {
            text.append("builds on ").append(numbers(section.buildsOn()));
        }
        if (!section.neededBy().isEmpty()) {
            if (!text.isEmpty()) {
                text.append(" · ");
            }
            text.append("needed by ").append(numbers(section.neededBy()));
        }
        return text.toString();
    }

    private static String numbers(List<Integer> numbers) {
        return numbers.stream().map(number -> "§" + number).collect(Collectors.joining(", "));
    }

    /** The row's trailing counts (pure): "1/3", plus "· 2 findings" when any are open. */
    static String counts(SectionsModel.Section section) {
        String findings = section.openFindings() == 0 ? ""
                : "  ·  " + section.openFindings() + (section.openFindings() == 1 ? " finding" : " findings");
        return section.progressText() + findings;
    }

    private static final class SectionRenderer extends ColoredListCellRenderer<SectionsModel.Section> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends SectionsModel.Section> list,
                                             SectionsModel.Section section, int index, boolean selected,
                                             boolean hasFocus) {
            setIpad(JBUI.insets(3, 12, 3, 8));
            boolean reviewed = section.reviewed() == section.total();
            if (reviewed) {
                setIcon(AllIcons.Actions.Checked);
            } else {
                setIcon(null);
                appendTextPadding(JBUI.scale(20)); // the check's column, so unreviewed titles line up
            }
            append("§" + section.number() + "  ", ReviewTypography.MUTED);
            append(section.title(), reviewed ? ReviewTypography.MUTED : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            append("   " + counts(section), ReviewTypography.MUTED_SMALL);
            String chips = chips(section);
            if (!chips.isEmpty()) {
                append("   " + chips, ReviewTypography.MUTED_SMALL);
            }
            setToolTipText(section.narrative().isEmpty() ? chips
                    : section.narrative() + (chips.isEmpty() ? "" : "\n" + chips));
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (intentText != null) {
            ReviewTypography.restyle(this);
        }
    }
}
