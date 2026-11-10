package app.drydock.intellij.review;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.Splitter;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One splitter pane that collapses to a thin vertical strip and expands
 * back. The wrapper is the splitter's component: expanded it shows the
 * pane's content under a one-button header (chevron, tooltip "Hide …"),
 * collapsed only the strip — a chevron and the pane's label read sideways —
 * so the divider snaps to the strip's width.
 *
 * <p>The snap works through the splitter's own minimum-size rule
 * ({@link Splitter} honors {@link JComponent#getMinimumSize} when dividing):
 * collapsed, the wrapper's minimum width is the strip's, and the collapse
 * clamps the proportion to it. The proportions a splitter persists stay the
 * expanded ones — the collapse keeps them ({@link #expandedProportion}) and
 * the expand restores them. Dragging the divider past the strip while
 * collapsed is the other way to expand: a proportion change counts as an
 * expand request only while the divider is actively pressed — a
 * programmatic change (a restored collapse, a foreign clamp) never
 * expands the pane. EDT.</p>
 */
final class CollapsiblePane extends JPanel {

    static final float DRAG_EPSILON = 0.01f;

    private final Splitter splitter;
    /** Whether this pane is the splitter's first component (left/top). */
    private final boolean first;
    private final JComponent content;
    private final int minWidth;
    private final JButton headerToggle;
    private final JPanel header;
    private final Strip strip;
    private final Consumer<Boolean> onUserToggle;
    /** The splitter's proportion while expanded, restored on expand; -1 before the first collapse. */
    private float expandedProportion = -1f;
    /** True while this pane itself sets the proportion, so the drag listener ignores it. */
    private boolean applying;
    /** True while the divider is pressed: only a pressed divider makes a proportion change user intent. */
    private boolean dividerPressed;
    private boolean collapsed;

    /**
     * Wraps {@code content} in {@code splitter}'s first or second slot
     * ({@code first}); {@code onUserToggle} receives every collapse state a
     * user action produced, for persistence.
     */
    CollapsiblePane(Splitter splitter, boolean first, JComponent content, String label,
                    int minWidth, Consumer<Boolean> onUserToggle) {
        super(new BorderLayout());
        this.splitter = Objects.requireNonNull(splitter, "splitter");
        this.first = first;
        this.content = Objects.requireNonNull(content, "content");
        this.minWidth = minWidth;
        this.onUserToggle = Objects.requireNonNull(onUserToggle, "onUserToggle");
        headerToggle = new JButton(AllIcons.General.CollapseComponent);
        headerToggle.setToolTipText("Hide " + label);
        headerToggle.setBorder(JBUI.Borders.empty(2));
        headerToggle.setFocusable(false);
        headerToggle.addActionListener(e -> setCollapsed(true, true));
        strip = new Strip(label);
        strip.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                setCollapsed(false, true);
            }
        });
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        header.add(headerToggle);
        this.header = header;
        add(header, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        add(strip, BorderLayout.WEST);
        // A splitter child's minimum size is the divider's floor: the pane's
        // width never goes under minWidth while expanded, the strip's when
        // collapsed (Splitter honors component minimum sizes by default).
        setMinimumSize(JBUI.size(minWidth, 0));
        strip.setVisible(false);
        // The proportion changes by itself only when the user drags the
        // divider: dragging past the strip expands the pane.
        splitter.addPropertyChangeListener(Splitter.PROP_PROPORTION, this::dividerDragged);
        // Only a pressed divider makes a proportion change user intent: a
        // programmatic load (a restored collapse, a foreign splitter clamp)
        // never presses anything.
        splitter.getDivider().addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                dividerPressed = true;
            }

            @Override public void mouseReleased(MouseEvent e) {
                dividerPressed = false;
            }
        });
        // The clamp re-applies on every layout: a collapse applied before the
        // splitter had a width becomes the strip proportion at the first (or
        // any later) layout, with no dependence on resize events -- a resize
        // event is not delivered to a component that is not yet displayable.
    }

    @Override
    public void doLayout() {
        super.doLayout();
        reapplyCollapse();
    }

    boolean isCollapsed() {
        return collapsed;
    }

    /** The pane's content, for tests. */
    JComponent content() {
        return content;
    }

    /** The collapsed strip, for tests. */
    JComponent strip() {
        return strip;
    }

    /** Collapses or expands the pane; {@code byUser} routes to {@link #onUserToggle}. EDT. */
    void setCollapsed(boolean next, boolean byUser) {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("CollapsiblePane toggles on the EDT");
        }
        if (collapsed == next) {
            return;
        }
        collapsed = next;
        content.setVisible(!next);
        strip.setVisible(next);
        header.setVisible(!next);
        setMinimumSize(JBUI.size(next ? strip.getPreferredSize().width : minWidth, 0));
        if (next) {
            float proportion = splitter.getProportion();
            if (proportion > 0f && proportion < 1f) {
                expandedProportion = proportion;
            } else {
                // A degenerate reading (the splitter has no real width, or a
                // garbage persisted proportion): drop any stale record instead of
                // keeping one from an earlier cycle; expand falls back to the
                // pane's minimum width rather than restoring it.
                expandedProportion = -1f;
            }
        }
        reapplyCollapse();
        if (!next && expandedProportion >= 0f) {
            applying = true;
            try {
                splitter.setProportion(expandedProportion);
            } finally {
                applying = false;
            }
        }
        if (byUser) {
            onUserToggle.accept(next);
        }
        revalidate();
        repaint();
    }

    /** Clamps the splitter to the strip's proportion while collapsed. */
    private void reapplyCollapse() {
        if (!collapsed || splitter.getWidth() <= splitter.getDividerWidth()) {
            return;
        }
        float stripProportion = stripProportion();
        if (Math.abs(splitter.getProportion() - stripProportion) <= DRAG_EPSILON) {
            return;
        }
        applying = true;
        try {
            splitter.setProportion(stripProportion);
        } finally {
            applying = false;
        }
    }

    /** The proportion that leaves this pane exactly the strip's width. */
    private float stripProportion() {
        int dividable = splitter.getWidth() - splitter.getDividerWidth();
        float proportion = strip.getPreferredSize().width / (float) dividable;
        return first ? proportion : 1f - proportion;
    }

    /** A drag past the strip is an expand request; this pane's own clamps are flagged off. */
    private void dividerDragged(PropertyChangeEvent event) {
        if (applying || !collapsed || !dividerPressed
                || splitter.getWidth() <= splitter.getDividerWidth()
                || !(event.getNewValue() instanceof Float proportion)) {
            return;
        }
        if (Math.abs(proportion - stripProportion()) > DRAG_EPSILON) {
            setCollapsed(false, true);
        }
    }

    /**
     * The collapsed pane: a chevron over the pane's label, painted sideways
     * (bottom to top). A click expands.
     */
    private static final class Strip extends JPanel {
        private final String label;

        Strip(String label) {
            this.label = Objects.requireNonNull(label, "label");
            setOpaque(false);
            setToolTipText("Show " + label);
            setPreferredSize(JBUI.size(24, 0));
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D graphics = (Graphics2D) g.create();
            try {
                FontMetrics metrics = graphics.getFontMetrics();
                graphics.setColor(UIUtil.getContextHelpForeground());
                AffineTransform rotated = new AffineTransform();
                rotated.quadrantRotate(-1);
                graphics.transform(rotated);
                // Rotated, the component's height is the text line's width and
                // its width the text line's height.
                int x = (getHeight() - metrics.stringWidth(label)) / 2;
                int y = (getWidth() - metrics.getHeight()) / 2 + metrics.getAscent();
                graphics.drawString(label, x, y);
            } finally {
                graphics.dispose();
            }
        }

        @Override public Dimension getPreferredSize() {
            return JBUI.size(24, 24);
        }
    }
}
