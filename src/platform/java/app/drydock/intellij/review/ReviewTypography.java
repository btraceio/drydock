package app.drydock.intellij.review;

import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import com.intellij.ui.SimpleTextAttributes;
import java.util.Objects;

/**
 * The review workbench's typography: one table of type roles, one spacing
 * scale, and the prose/measure helpers every review surface reads — the
 * section view, the agent take, the Sections navigator, the finding card
 * and the inbox — so their text renders with one hierarchy and one rhythm
 * instead of per-view copies (AGENTS.md: shared presentation logic lives in
 * one utility).
 *
 * <p>Colours are the platform's own named colours, resolved at paint time,
 * so a theme switch needs no handling here. Fonts are set explicitly and
 * therefore are not {@code UIResource}s: an IDE font-size change would
 * leave them stale, which is why {@link #apply} stores the role on the
 * component and {@link #restyle} re-applies every stored role — the
 * surfaces call it from their {@code updateUI()}.</p>
 */
final class ReviewTypography {

    /** The spacing scale, in UI-scaling units (JBUI scales it). */
    static final int XS = 2, S = 4, M = 8, L = 12, XL = 16, XXL = 24;

    /**
     * The measure, in em: the line length body text wraps at in the wide
     * centre (the platform's own review text width is 42 em too —
     * {@code CodeReviewChatItemUIUtil.TEXT_CONTENT_WIDTH}).
     */
    static final int MEASURE_EM = 42;

    /** The client property {@link #apply} stores the role under, for {@link #restyle}. */
    private static final String ROLE_KEY = "drydock.review.typography.role";

    /**
     * Marks a subtree that restyles itself in its own {@code updateUI()}:
     * {@link #restyle} applies roles to it but does not descend into it.
     */
    private static final String SELF_RESTYLING_KEY = "drydock.review.typography.selfRestyling";

    /** What a piece of review text is: its font and its colour. */
    enum Role {
        /** The section's title in the centre. */
        PAGE_TITLE,
        /** The "§n" before a page title. */
        PAGE_NUMBER,
        /** A card's title line (finding title, composer title). */
        CARD_TITLE,
        /** Body prose: narratives, takes, intents. */
        BODY,
        /** Body prose quoted or secondary: alignment notes, PR description. */
        BODY_MUTED,
        /** Metadata: progress, notices, ages, severities. */
        META,
        /** The small bold label above a block ("Intent", "Agent take"). */
        EYEBROW
    }

    private ReviewTypography() {
    }

    /** The role's font. */
    static JBFont font(@NotNull Role role) {
        return switch (role) {
            case PAGE_TITLE -> JBFont.h3().asBold();
            case PAGE_NUMBER -> JBFont.h3();
            case CARD_TITLE -> JBFont.label().asBold();
            case BODY, BODY_MUTED -> JBFont.label();
            case META -> JBFont.medium();
            case EYEBROW -> JBFont.medium().asBold();
        };
    }

    /** The role's colour — always a platform named colour, never a copy. */
    static Color foreground(@NotNull Role role) {
        return switch (role) {
            case PAGE_TITLE, CARD_TITLE, BODY -> JBUI.CurrentTheme.Label.foreground();
            case PAGE_NUMBER, BODY_MUTED, META, EYEBROW -> UIUtil.getContextHelpForeground();
        };
    }

    /** Sets {@code component}'s font and colour to the role's, and stores the role for {@link #restyle}. */
    static void apply(@NotNull JComponent component, @NotNull Role role) {
        component.setFont(font(role));
        component.setForeground(foreground(role));
        component.putClientProperty(ROLE_KEY, role);
    }

    /**
     * The role's font only, storing the role: for editable fields, whose
     * colour the LaF owns and only the typography is ours.
     */
    static void applyFont(@NotNull JComponent component, @NotNull Role role) {
        component.setFont(font(role));
        component.putClientProperty(ROLE_KEY, role);
    }

    /** A one-line label in the role's type. */
    static JLabel label(String text, @NotNull Role role) {
        JLabel label = new JLabel(text);
        apply(label, role);
        return label;
    }

    /**
     * A read-only, word-wrapped, transparent prose area in the role's type —
     * the workbench's body text: selectable, wrappable, no HTML, and its
     * exact text stays readable for tests. A text area defaults to the
     * editor's monospace font, which is why the font is set here.
     */
    static JTextArea prose(@NotNull Role role) {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        apply(area, role);
        return area;
    }

    /** The measure in px: 42 em of the label font. */
    static int measure() {
        return Math.round(JBFont.label().getSize2D() * MEASURE_EM);
    }

    /**
     * Caps {@code child} (a wrapped prose component) at {@link #measure()}
     * px wide, anchored left, in the wide centre: 45–80 characters per line
     * is what reads, and a full-width narrative at 1000+ px is a line length
     * no book uses. The child's height is measured at the width it gets, so
     * the wrapped height is right from the first pass.
     */
    static JComponent measured(@NotNull JComponent child) {
        JPanel panel = new JPanel(new MeasureLayout(child));
        panel.setOpaque(false);
        panel.add(child);
        return panel;
    }

    /**
     * Marks {@code component} as restyling itself in its own
     * {@code updateUI()}: {@link #restyle} stops descending into it. The
     * component must re-apply the role of every typed component that
     * survives its own renders.
     */
    static void selfRestyling(@NotNull JComponent component) {
        component.putClientProperty(SELF_RESTYLING_KEY, Boolean.TRUE);
    }

    /** Re-applies every stored role under {@code root}, not descending into marked self-restyling subtrees: the surfaces' {@code updateUI()} calls this. */
    static void restyle(@NotNull Component root) {
        UIUtil.uiTraverser(root)
                .expand(component -> !(component instanceof JComponent marked
                        && Boolean.TRUE.equals(marked.getClientProperty(SELF_RESTYLING_KEY))))
                .filter(JComponent.class::isInstance)
                .forEach(component -> {
                    Object role = ((JComponent) component).getClientProperty(ROLE_KEY);
                    if (role instanceof Role typed) {
                        component.setFont(font(typed));
                        component.setForeground(foreground(typed));
                    }
                });
    }

    /** The renderer attributes matching the labels' muted metadata, so list rows and labels share one grey. */
    static final SimpleTextAttributes MUTED =
            new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, UIUtil.getContextHelpForeground());

    /** The muted metadata, smaller — the trailing counts and chips of a list row. */
    static final SimpleTextAttributes MUTED_SMALL =
            new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, UIUtil.getContextHelpForeground());

    /** One child, at most the measure wide; its height measured at that width. */
    private static final class MeasureLayout implements LayoutManager {
        private final JComponent child;

        MeasureLayout(JComponent child) {
            this.child = Objects.requireNonNull(child, "child");
        }

        @Override public void addLayoutComponent(String name, Component comp) {
        }

        @Override public void removeLayoutComponent(Component comp) {
        }

        @Override public Dimension preferredLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            return new Dimension(measure() + insets.left + insets.right,
                    measuredHeight(measure()) + insets.top + insets.bottom);
        }

        @Override public Dimension minimumLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            int available = parent.getWidth() - insets.left - insets.right;
            // Measure at the width the container actually has; before the first
            // layout pass there is none, and the preferred height is the safe
            // stand-in (a 1-px floor must not imply a one-word-per-line height).
            int measured = available > 0 ? available : measure();
            return new Dimension(insets.left + insets.right + 1,
                    measuredHeight(measured) + insets.top + insets.bottom);
        }

        @Override public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            int available = parent.getWidth() - insets.left - insets.right;
            int width = Math.min(measure(), Math.max(1, available));
            child.setBounds(insets.left, insets.top, width, measuredHeight(width));
        }

        /** The child's preferred height at {@code width}: sized there, measured, left sized (idempotent). */
        private int measuredHeight(int width) {
            child.setSize(width, Short.MAX_VALUE);
            return child.getPreferredSize().height;
        }
    }
}
