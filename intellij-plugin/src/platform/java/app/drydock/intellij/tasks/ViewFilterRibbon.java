package app.drydock.intellij.tasks;

import app.drydock.intellij.tasks.TaskTreeModel.ViewFilter;

import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.WrapLayout;

import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.function.Consumer;

/**
 * The tree's quick-view switcher: one flat, tab-like chip per
 * {@link ViewFilter}, in a {@link ButtonGroup} so the views stay mutually
 * exclusive, laid out in a wrapping ribbon so a narrow tool window grows
 * rows instead of hiding a view behind a clipped edge.
 *
 * <p>The chips paint their own selection state rather than leaving it to the
 * LaF: Darcula's toggle-button UI fills the plain button gradient whatever
 * {@code isSelected()} says, so the visible "on" state is an underline in the
 * tab pane's selected colour — present whenever the chip is selected, never
 * tied to focus, which moves to the editor and back. Hover shows the tab
 * pane's hover colour, and focus (keyboard access — the chips are real,
 * focusable buttons) is an outline in the focus colour. No chip state is
 * derived here: the chosen view lives in the panel, and this ribbon only
 * reports real changes to it.</p>
 */
public final class ViewFilterRibbon extends JPanel {

    private final ButtonGroup group = new ButtonGroup();
    private ViewFilter current;

    public ViewFilterRibbon(ViewFilter initial, Consumer<ViewFilter> onChange) {
        super(new WrapLayout(FlowLayout.LEFT, 0, 0));
        current = initial;
        for (ViewFilter candidate : ViewFilter.values()) {
            JToggleButton button = new FilterButton(candidate);
            button.setSelected(candidate == initial);
            // Re-clicking the selected chip still fires Swing's action (a
            // ButtonGroup keeps the chip selected), but changes nothing; the
            // consumer only sees real changes.
            button.addActionListener(event -> {
                if (current != candidate) {
                    current = candidate;
                    onChange.accept(candidate);
                }
            });
            group.add(button);
            add(button);
        }
        // WrapLayout derives the rows from this ribbon's own current width,
        // but the nesting parents (TaskTreePanel's query and toolbar blocks,
        // both BorderLayout) read its preferred height BEFORE it is given a
        // new width, so they size themselves for the rows at the OLD width.
        // The ribbon itself still gets its new preferred height, so on a
        // live narrowing BorderLayout pushes it up over the search field and
        // the resize's own validation pass leaves it there. So a resize that
        // changed the width AND changed the preferred height from the one the
        // parents allotted (the previous width's) asks for one more layout.
        // revalidate() is coalesced by the RepaintManager; the follow-up
        // resize keeps the width, so it cannot loop.
        addComponentListener(new ComponentAdapter() {
            private int allottedWidth = -1;
            private int allottedHeight = -1;

            @Override
            public void componentResized(ComponentEvent event) {
                int width = getWidth();
                if (width == allottedWidth) {
                    return;
                }
                allottedWidth = width;
                int preferred = getPreferredSize().height;
                if (preferred != allottedHeight) {
                    allottedHeight = preferred;
                    revalidate();
                }
            }
        });
    }

    /**
     * A flat chip: no LaF fill, border or focus paint — the only paints are
     * this class's own hover, focus and selected-underline, so the chip reads
     * like an editor tab in every theme.
     */
    private static final class FilterButton extends JToggleButton {

        FilterButton(ViewFilter view) {
            super(view.label());
            setToolTipText(view.tooltip());
            setFocusable(true);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setMargin(JBUI.insets(2, 7));
            // The flat paints below depend on model state (rollover, selected)
            // and focus, none of which repaints a chip with no LaF fill.
            addChangeListener(event -> repaint());
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent event) {
                    repaint();
                }

                @Override
                public void focusLost(FocusEvent event) {
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (getModel().isRollover()) {
                g.setColor(JBUI.CurrentTheme.TabbedPane.HOVER_COLOR);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
            if (isFocusOwner()) {
                g.setColor(JBUI.CurrentTheme.Focus.focusColor());
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            }
            if (isSelected()) {
                int underline = JBUI.CurrentTheme.TabbedPane.SELECTION_HEIGHT.get();
                g.setColor(JBUI.CurrentTheme.TabbedPane.ENABLED_SELECTED_COLOR);
                g.fillRect(0, getHeight() - underline, getWidth(), underline);
            }
            // The label paints after the flat fills, on top of them.
            super.paintComponent(g);
        }
    }
}
