package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.SectionsModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The navigator's Sections tab (spec §5): the agent's intent, then one row
 * per section with its number, title, progress, open findings and the
 * "builds on / needed by" chips. Selecting a row selects the section, which
 * the centre then shows. EDT.
 */
final class ReviewWorkbenchSections extends JPanel {

    static final String COMPUTED_NOTE = "Computed sections: the agent has posted no tour for this review.";
    static final String EMPTY_TEXT = "No sections: the agent has posted no tour, and the computed sections are "
            + "still being built or could not be built.";

    private final Consumer<String> select;
    private final JBTextArea intent = new JBTextArea();
    private final JBTextArea note = new JBTextArea();
    private final DefaultListModel<SectionsModel.Section> rows = new DefaultListModel<>();
    final JBList<SectionsModel.Section> list = new JBList<>(rows);
    /** Set while a render replaces the rows, so the list's own selection events are not the user's. */
    private boolean rendering;

    /** {@code select} receives the key of the section the user picked. */
    ReviewWorkbenchSections(Consumer<String> select) {
        super(new BorderLayout());
        this.select = Objects.requireNonNull(select, "select");
        for (JBTextArea text : List.of(intent, note)) {
            text.setEditable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setOpaque(false);
            text.setBorder(JBUI.Borders.empty(4, 8));
            text.setFont(JBUI.Fonts.label()); // a text area defaults to the editor's monospace font
        }
        note.setForeground(UIUtil.getContextHelpForeground());
        list.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new SectionRenderer());
        list.getEmptyText().setText(EMPTY_TEXT);
        list.addListSelectionListener(e -> {
            SectionsModel.Section picked = list.getSelectedValue();
            if (!rendering && !e.getValueIsAdjusting() && picked != null) {
                select.accept(picked.key());
            }
        });
        JPanel north = new JPanel(new BorderLayout());
        north.add(intent, BorderLayout.NORTH);
        north.add(note, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
        add(new JBScrollPane(list), BorderLayout.CENTER);
    }

    /** Shows {@code state}'s sections and selection. */
    void render(WorkbenchState state) {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("ReviewWorkbenchSections renders on the EDT");
        }
        SectionsModel.Snapshot sections = state.sections();
        intent.setText(sections.intent().map(text -> "Intent: " + text).orElse(""));
        intent.setVisible(sections.intent().isPresent());
        note.setText(COMPUTED_NOTE);
        note.setVisible(sections.source() == SectionsModel.Source.COMPUTED);
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
            append("§" + section.number() + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
            append(section.title());
            append("   " + counts(section), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            String chips = chips(section);
            if (!chips.isEmpty()) {
                append("   " + chips, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
            }
            setToolTipText(section.narrative().isEmpty() ? null : section.narrative());
        }
    }
}
