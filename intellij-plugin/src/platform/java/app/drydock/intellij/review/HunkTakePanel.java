package app.drydock.intellij.review;

import app.drydock.review.tour.HunkTake;
import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.panels.HorizontalLayout;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionListener;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The agent's take on one hunk (spec §6), on a section card and at the
 * hunk's start in the file view: a header link that folds it, over what the
 * hunk does, the alignment note and the red flags; a red flag that names a
 * finding links to it. Hidden while there is no take; open until the user
 * folds it, for the panel's lifetime. EDT.
 *
 * <p>The take reads as a quiet callout: a 2 px left rule in the alignment's
 * accent colour, the fold link and the alignment word in the header, the
 * take's prose in {@link ReviewTypography}'s roles, and a warning icon
 * before each red flag instead of a text glyph.</p>
 */
final class HunkTakePanel extends JPanel {

    static final String SHOW_FINDING = "Show finding";

    final ActionLink toggle;
    private final Consumer<String> showFinding;
    private final JBLabel alignment = new JBLabel();
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
        // The fold affordance is the chevron beside the text, not a glyph in it.
        toggle.setIcon(AllIcons.General.ChevronDown);
        body.setLayout(new VerticalLayout(ReviewTypography.S));
        // The permanent chrome: the header row holds the fold link beside the
        // alignment word. Built once — render only rebuilds take-dependent parts.
        // Header first, body second: a hierarchy walk (accessibility, tests)
        // always sees the header before the body.
        JPanel headerRow = new JPanel(new HorizontalLayout(JBUI.scale(6)));
        headerRow.setOpaque(false);
        headerRow.add(toggle);
        headerRow.add(alignment);
        removeAll();
        add(headerRow, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        toggle.setText("Agent take");
        setVisible(false);
    }

    /** The expansion-dependent trio: chevron, accessible name, body visibility. */
    private void applyExpandedState(HunkTake take) {
        toggle.setIcon(expanded ? AllIcons.General.ChevronDown : AllIcons.General.ChevronRight);
        toggle.getAccessibleContext().setAccessibleName(header(take, expanded));
        body.setVisible(expanded);
        // Deliberately also on the unchanged-take path: render runs on every
        // workbench state render, and a fold toggle that takes the
        // short-circuit path must still update the chevron and body.
        revalidate();
        repaint();
    }

    /** The header (pure): "Agent take · Diverges", with the open/closed marker. */
    static String header(HunkTake take, boolean expanded) {
        return (expanded ? "▾ " : "▸ ") + "Agent take · " + take.alignment().label();
    }

    void render(Optional<HunkTake> next) {
        setVisible(next.isPresent());
        if (next.isPresent() && next.equals(current)) {
            // An unchanged take: no rebuild — apply the expansion state only.
            applyExpandedState(next.get());
            return;
        }
        current = next;
        body.removeAll();
        if (next.isEmpty()) {
            return;
        }
        HunkTake take = next.get();
        // The alignment word sits beside the link, coloured only when the take
        // diverges: that is the one state a reviewer must not scroll past.
        alignment.setText(take.alignment().label());
        ReviewTypography.apply(alignment, ReviewTypography.Role.META);
        alignment.setForeground(take.alignment() == HunkTake.Alignment.DIVERGES
                ? JBUI.CurrentTheme.Label.warningForeground()
                : UIUtil.getContextHelpForeground());
        // The accent rule is the callout's whole decoration: one colour per
        // alignment, no bubbles or fills.
        setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(accent(take.alignment()), 0, 2, 0, 0),
                JBUI.Borders.empty(ReviewTypography.S, 10, ReviewTypography.S, 0)));
        body.add(text(take.does(), ReviewTypography.Role.BODY));
        if (!take.alignmentNote().isEmpty()) {
            body.add(text(take.alignmentNote(), ReviewTypography.Role.BODY_MUTED));
        }
        for (HunkTake.RedFlag flag : take.redFlags()) {
            JBLabel warning = new JBLabel(AllIcons.General.Warning);
            warning.setVerticalAlignment(SwingConstants.TOP);
            JPanel column = new JPanel(new VerticalLayout(ReviewTypography.XS));
            column.setOpaque(false);
            column.add(text(flag.text(), ReviewTypography.Role.BODY));
            flag.findingId().ifPresent(id -> column.add(new ActionLink(SHOW_FINDING,
                    (ActionListener) e -> showFinding.accept(id))));
            JPanel row = new JPanel(new BorderLayout(JBUI.scale(ReviewTypography.S), 0));
            row.setOpaque(false);
            row.add(warning, BorderLayout.WEST);
            row.add(column, BorderLayout.CENTER);
            body.add(row);
        }
        applyExpandedState(take);
    }

    /** The alignment's accent: the platform's banner border colours, success through warning. */
    private static Color accent(HunkTake.Alignment alignment) {
        return switch (alignment) {
            case ALIGNED -> JBUI.CurrentTheme.Banner.SUCCESS_BORDER_COLOR;
            case PARTIAL -> JBUI.CurrentTheme.Banner.INFO_BORDER_COLOR;
            case DIVERGES -> JBUI.CurrentTheme.Banner.WARNING_BORDER_COLOR;
            case CONTEXT -> JBColor.border();
        };
    }

    private static JTextArea text(String value, ReviewTypography.Role role) {
        JTextArea area = ReviewTypography.prose(role);
        area.setText(value);
        return area;
    }

    /** The texts shown, in order (header first): for the tests. */
    java.util.List<String> shownTexts() {
        java.util.List<String> texts = new java.util.ArrayList<>();
        texts.add(toggle.getText() + (alignment.getText().isEmpty() ? "" : " · " + alignment.getText()));
        if (body.isVisible()) {
            UIUtil.uiTraverser(body).filter(JTextArea.class).forEach(area -> texts.add(area.getText()));
        }
        return texts;
    }
}
