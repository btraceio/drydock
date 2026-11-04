package app.drydock.intellij.review;

import app.drydock.review.tour.HunkTake;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The agent's take on one hunk (spec §6), on a section card and at the
 * hunk's start in the file view: a header link that folds it, over what the
 * hunk does, the alignment note and the red flags; a red flag that names a
 * finding links to it. Hidden while there is no take; open until the user
 * folds it, for the panel's lifetime. EDT.
 */
final class HunkTakePanel extends JPanel {

    static final String SHOW_FINDING = "Show finding";

    final ActionLink toggle;
    private final Consumer<String> showFinding;
    private final JPanel body = new JPanel();
    private Optional<HunkTake> current = Optional.empty();
    private boolean expanded = true;

    /** {@code showFinding} receives the finding id of a red flag's link. */
    HunkTakePanel(Consumer<String> showFinding) {
        super(new BorderLayout());
        this.showFinding = Objects.requireNonNull(showFinding, "showFinding");
        toggle = new ActionLink("", (ActionListener) e -> {
            expanded = !expanded;
            render(current);
        });
        setBorder(JBUI.Borders.empty(2, 0, 4, 0));
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(JBUI.Borders.emptyLeft(12));
        add(toggle, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        setVisible(false);
    }

    /** The header (pure): "Agent take · Diverges", with the open/closed marker. */
    static String header(HunkTake take, boolean expanded) {
        return (expanded ? "▾ " : "▸ ") + "Agent take · " + take.alignment().label();
    }

    void render(Optional<HunkTake> next) {
        current = next;
        setVisible(next.isPresent());
        body.removeAll();
        if (next.isEmpty()) {
            return;
        }
        HunkTake take = next.get();
        toggle.setText(header(take, expanded));
        body.setVisible(expanded);
        body.add(text(take.does(), false));
        if (!take.alignmentNote().isEmpty()) {
            body.add(text(take.alignmentNote(), true));
        }
        for (HunkTake.RedFlag flag : take.redFlags()) {
            JPanel row = new JPanel(new BorderLayout(JBUI.scale(6), 0));
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.add(text("⚑ " + flag.text(), false), BorderLayout.CENTER);
            flag.findingId().ifPresent(id ->
                    row.add(new ActionLink(SHOW_FINDING, (ActionListener) e -> showFinding.accept(id)),
                            BorderLayout.EAST));
            body.add(row);
        }
        revalidate();
        repaint();
    }

    private static JBTextArea text(String value, boolean muted) {
        JBTextArea area = new JBTextArea(value);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFont(JBUI.Fonts.label()); // a text area defaults to the editor's monospace font
        area.setAlignmentX(LEFT_ALIGNMENT);
        if (muted) {
            area.setForeground(UIUtil.getContextHelpForeground());
        }
        return area;
    }

    /** The texts shown, in order (header first): for the tests. */
    List<String> shownTexts() {
        List<String> texts = new ArrayList<>();
        texts.add(toggle.getText());
        if (body.isVisible()) {
            UIUtil.uiTraverser(body).filter(JBTextArea.class).forEach(area -> texts.add(area.getText()));
        }
        return texts;
    }
}
