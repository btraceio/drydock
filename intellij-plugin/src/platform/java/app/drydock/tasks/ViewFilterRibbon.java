package app.drydock.tasks;

import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SearchTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.WrapLayout;

import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.event.DocumentEvent;

import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.Objects;
import java.util.function.Consumer;

import app.drydock.tasks.DelayedTaskTreeModel.View;

/**
 * The delayed-task tree's quick-view switcher (D9/D10): one flat, tab-like
 * chip per {@link View} of the task layer's own view model, in a
 * {@link ButtonGroup} so the views stay mutually exclusive, laid out in a
 * wrapping ribbon - the same layout as the session tree's ribbon - so a
 * narrow tool window grows rows instead of hiding a view behind a clipped
 * edge. The text search (D9's "hidden behind the filter ribbon" narrowing)
 * rides the same ribbon as its first control, the search editor the session
 * tree uses, so every added filter - Tasks, Completed, Needs you, the text
 * search - is one row of controls with one shape.
 *
 * <p>Like the session tree's ribbon: the chips paint their own selection
 * state rather than leaving it to the LaF (Darcula fills the plain button
 * gradient whatever {@code isSelected()} says), so the visible "on" state is
 * an underline in the tab pane's selected colour - present whenever the
 * chip is selected, never tied to focus, which moves to the editor and
 * back. Hover shows the tab pane's hover colour, and focus (keyboard
 * access - every chip and the search editor are real, focusable controls)
 * is an outline in the focus colour. No chip or search state is derived
 * here: the chosen view and the typed text live in the owner, and this
 * ribbon only reports real changes to it. The search text is reported
 * verbatim, every change; normalization to the model's filter happens once,
 * in {@link DelayedTaskTreeModel.Inputs}, never here.</p>
 */
public final class ViewFilterRibbon extends JPanel {

    private final ButtonGroup group = new ButtonGroup();
    private final SearchTextField search = new SearchTextField(false);
    private View current;

    public ViewFilterRibbon(View initial, String initialSearch, Consumer<View> onView,
            Consumer<String> onSearch) {
        super(new WrapLayout(FlowLayout.LEFT, 0, 0));
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(onView, "onView");
        Objects.requireNonNull(onSearch, "onSearch");
        current = initial;
        // The initial text is set BEFORE the listener attaches, so
        // construction reports nothing - a ribbon starts life quiet, like
        // the session tree's one; typing is what reports.
        search.setText(initialSearch == null ? "" : initialSearch);
        search.getTextEditor().setToolTipText("Search delayed tasks: matches a task's "
                + "title or id within the selected view");
        search.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(DocumentEvent event) {
                onSearch.accept(search.getText());
            }
        });
        add(search);
        for (View candidate : View.values()) {
            JToggleButton button = new FilterButton(candidate);
            button.setSelected(candidate == initial);
            // Re-clicking the selected chip still fires Swing's action (a
            // ButtonGroup keeps the chip selected), but changes nothing; the
            // consumer only sees real changes.
            button.addActionListener(event -> {
                if (current != candidate) {
                    current = candidate;
                    onView.accept(candidate);
                }
            });
            group.add(button);
            add(button);
        }
        // WrapLayout derives the rows from this ribbon's own current width,
        // but a nesting parent (a BorderLayout block, like the panel's query
        // strip) reads its preferred height BEFORE it is given a new width,
        // so it sizes itself for the rows at the OLD width and a later
        // validation pass leaves the overlap standing. A resize that changed
        // the width AND changed the preferred height from the one the parent
        // allotted (the previous width's) asks for one more layout;
        // revalidate() is coalesced, and the follow-up resize keeps the
        // width, so it cannot loop.
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

    /** The chip's hover text: what the view shows. UI copy lives here; the model stays presentation-free. */
    private static String tooltip(View view) {
        return switch (view) {
            case ALL -> "Show sessions and their tasks; done and dropped tasks stay hidden";
            case TASKS -> "Show the open tasks: ready first, then blocked, review, interrupted";
            case COMPLETED -> "Show the done and dropped tasks";
            case NEEDS_YOU -> "Show what awaits you: sessions with an answer due and tasks in review";
        };
    }

    /**
     * A flat chip: no LaF fill, border or focus paint - the only paints are
     * this class's own hover, focus and selected-underline, so the chip reads
     * like an editor tab in every theme.
     */
    private static final class FilterButton extends JToggleButton {

        FilterButton(View view) {
            super(view.label());
            setToolTipText(tooltip(view));
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
