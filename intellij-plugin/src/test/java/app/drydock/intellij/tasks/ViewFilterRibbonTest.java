package app.drydock.intellij.tasks;

import app.drydock.intellij.tasks.TaskTreeModel.ViewFilter;

import com.intellij.ui.SearchTextField;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JToggleButton;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.EventQueue;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quick-view ribbon as a pure Swing component: exactly one chip is
 * selected, a click selects a view and reports it once, re-clicking the
 * selected chip reports nothing (a ButtonGroup keeps the chip selected), and
 * a narrow ribbon prefers more than one row instead of hiding a view. The
 * ribbon holds no store and no process, so everything runs headless on the
 * EDT; how the flat paints look is a platform concern, not verified here.
 */
class ViewFilterRibbonTest {

    // ---- fixtures -----------------------------------------------------------

    private static ViewFilterRibbon ribbon(ViewFilter initial, List<ViewFilter> fired) {
        ViewFilterRibbon[] out = new ViewFilterRibbon[1];
        onEdt(() -> out[0] = new ViewFilterRibbon(initial, fired::add));
        return out[0];
    }

    private static List<JToggleButton> chips(ViewFilterRibbon ribbon) {
        List<JToggleButton> out = new ArrayList<>();
        // onEdt returns only once the EDT work is done, so the list is
        // complete when it is read.
        onEdt(() -> {
            for (Component child : ribbon.getComponents()) {
                if (child instanceof JToggleButton toggle) {
                    out.add(toggle);
                }
            }
        });
        return out;
    }

    private static JToggleButton chip(ViewFilterRibbon ribbon, ViewFilter filter) {
        return chips(ribbon).stream()
                .filter(toggle -> toggle.getText().equals(filter.label()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no chip for " + filter.label()));
    }

    private static long selectedCount(ViewFilterRibbon ribbon) {
        return chips(ribbon).stream().filter(JToggleButton::isSelected).count();
    }

    // ---- the switcher's own contract ---------------------------------------

    @Test
    void exactlyOneChipIsSelectedInitiallyAndConstructionFiresNothing() {
        List<ViewFilter> fired = new ArrayList<>();
        ViewFilterRibbon ribbon = ribbon(ViewFilter.ACTIVE, fired);

        List<JToggleButton> selected = chips(ribbon).stream().filter(JToggleButton::isSelected).toList();

        assertEquals(1, selected.size(), "the initial view must be the only selected chip");
        assertEquals(ViewFilter.ACTIVE.label(), selected.get(0).getText());
        List<JToggleButton> chips = chips(ribbon);
        assertEquals(ViewFilter.values().length, chips.size(), "one chip per view");
        assertTrue(chips.stream().allMatch(JToggleButton::isFocusable),
                "every chip is a real focusable button (keyboard access)");
        assertEquals(List.of(), fired, "constructing the ribbon must not report a change");
    }

    @Test
    void clickingAChipSelectsItAndReportsOnce() {
        List<ViewFilter> fired = new ArrayList<>();
        ViewFilterRibbon ribbon = ribbon(ViewFilter.ALL, fired);
        JToggleButton active = chip(ribbon, ViewFilter.ACTIVE);

        onEdt(() -> active.doClick());

        assertEquals(List.of(ViewFilter.ACTIVE), fired, "the click is reported exactly once");
        assertTrue(active.isSelected(), "the clicked chip is the selected one");
        assertEquals(1, selectedCount(ribbon), "selecting a chip deselects the previous one");
    }

    @Test
    void reclickingTheSelectedChipReportsNothing() {
        List<ViewFilter> fired = new ArrayList<>();
        ViewFilterRibbon ribbon = ribbon(ViewFilter.ALL, fired);
        JToggleButton active = chip(ribbon, ViewFilter.ACTIVE);

        onEdt(() -> {
            active.doClick();
            active.doClick();
        });

        assertEquals(List.of(ViewFilter.ACTIVE), fired,
                "the second click keeps the same view and must not fire the consumer again");
        assertTrue(active.isSelected(), "a ButtonGroup keeps the re-clicked chip selected");
        assertEquals(1, selectedCount(ribbon));
    }

    @Test
    void aNarrowRibbonPrefersMoreThanOneRow() {
        List<ViewFilter> fired = new ArrayList<>();
        ViewFilterRibbon ribbon = ribbon(ViewFilter.ALL, fired);

        onEdt(() -> {
            int widest = chips(ribbon).stream()
                    .map(JToggleButton::getPreferredSize)
                    .mapToInt(dimension -> dimension.width)
                    .max()
                    .orElseThrow();
            // WrapLayout reads the container's current width, so set a width
            // that fits no more than one chip before asking for the wrapped
            // preferred size.
            ribbon.setSize(widest, 10);
            ribbon.doLayout();
            int wrapped = ribbon.getPreferredSize().height;
            ribbon.setSize(100_000, 10);
            ribbon.doLayout();
            int oneRow = ribbon.getPreferredSize().height;
            assertTrue(wrapped > oneRow,
                    "a width that fits one chip must wrap the others onto further rows: "
                            + wrapped + " vs " + oneRow);
        });
    }

    // ---- live resize through the real nested parents (G2) ------------------

    /**
     * A live wide→narrow→wide resize must keep the wrapped ribbon inside its
     * query panel, below the search field, with the query panel inside the
     * toolbar block — the way TaskTreePanel nests it: the ribbon in the
     * SOUTH of a BorderLayout query panel (search field NORTH), that query
     * panel in the SOUTH of the BorderLayout {@code top} block, and
     * {@code top} handed to SimpleToolWindowPanel.setToolbar, which (2026.3
     * bytecode) lays the panel out with a BorderLayout and adds the toolbar
     * block at NORTH — so {@code top} gets exactly its OWN preferred height
     * at every width, never an arbitrary one.
     *
     * <p>The hazard this pins: BorderLayout reads {@code top}'s (and then
     * the query panel's) preferred height before the ribbon itself has been
     * given the new width, and WrapLayout derives the ribbon's rows from the
     * ribbon's own current width — so a narrowed ribbon wraps into more rows
     * than its parents allotted, and BorderLayout pushes it up over the
     * search field (or above the query panel's top edge).</p>
     *
     * <p>The hierarchy gets real lightweight peers ({@code addNotify} on the
     * root, inside this test JVM only), so {@code validate()} runs AWT's own
     * validateTree with its preferred-size caching, exactly as a resized
     * window runs it. After each resize the test makes one more EDT hop, so
     * the COMPONENT_RESIZED events the resize posted are delivered, then
     * calls {@code validate()} on the root once more: that stands in for
     * RepaintManager's validation of invalidated roots, which headless AWT
     * cannot schedule (it needs a Window ancestor). It lays anything out
     * only if something invalidated the tree; a hierarchy nobody
     * invalidated stays exactly as the first pass left it. Component
     * evidence, not visual: nothing here is rendered on screen.</p>
     */
    @Test
    void aLiveResizeKeepsTheRibbonInsideItsQueryPanelAndBelowTheSearchBothWays() {
        List<ViewFilter> fired = new ArrayList<>();
        ViewFilterRibbon ribbon = ribbon(ViewFilter.ALL, fired);

        JPanel root = new JPanel(new BorderLayout());
        JPanel top = new JPanel(new BorderLayout());
        JPanel query = new JPanel(new BorderLayout());
        SearchTextField search = onEdtSearch();
        onEdt(() -> {
            // The action toolbar's place: a fixed preferred height, as an
            // ActionToolbar has; its contents are not under test.
            JPanel toolbar = new JPanel();
            toolbar.setPreferredSize(new Dimension(10, 28));
            top.add(toolbar, BorderLayout.NORTH);
            query.add(search, BorderLayout.NORTH);
            query.add(ribbon, BorderLayout.SOUTH);
            top.add(query, BorderLayout.SOUTH);
            // SimpleToolWindowPanel: BorderLayout, toolbar block at NORTH,
            // the tree in the rest.
            root.add(top, BorderLayout.NORTH);
            root.add(new JPanel(), BorderLayout.CENTER);
            root.addNotify();
        });
        try {
            int widestChip = onEdtInt(() -> chips(ribbon).stream()
                    .map(chip -> chip.getPreferredSize().width)
                    .max(Integer::compare)
                    .orElseThrow());
            int[] widths = {1000, widestChip, 1000};
            int[] heights = new int[widths.length];
            for (int pass = 0; pass < widths.length; pass++) {
                final int width = widths[pass];
                final int passNumber = pass + 1;
                onEdt(() -> {
                    root.setSize(width, 700);
                    root.validate();
                });
                // Deliver the posted COMPONENT_RESIZED events, then validate
                // whatever they invalidated (nothing, unless something did).
                onEdt(() -> { });
                onEdt(root::validate);
                heights[pass] = onEdtInt(ribbon::getHeight);
                onEdt(() -> {
                    String where = " after the resize to " + width + " wide (pass " + passNumber
                            + "): top " + top.getBounds() + ", query " + query.getBounds()
                            + ", search " + search.getBounds() + ", ribbon " + ribbon.getBounds()
                            + ", ribbon preferred " + ribbon.getPreferredSize();
                    assertTrue(ribbon.getY() >= 0
                                    && ribbon.getY() + ribbon.getHeight() <= query.getHeight(),
                            "the ribbon must lie inside its query panel" + where);
                    assertFalse(ribbon.getBounds().intersects(search.getBounds()),
                            "the ribbon must not overlap the search field" + where);
                    assertTrue(query.getY() >= 0 && query.getY() + query.getHeight() <= top.getHeight(),
                            "the query panel must lie inside the toolbar block" + where);
                    assertTrue(chips(ribbon).stream()
                                    .allMatch(chip -> new Rectangle(ribbon.getSize()).contains(chip.getBounds())),
                            "every chip must stay inside the ribbon" + where + ", chips " + chipBounds(ribbon));
                });
            }
            assertTrue(heights[1] > heights[0] && heights[1] > heights[2],
                    "the narrow pass must actually wrap — more rows than the wide "
                            + "passes: " + heights[0] + ", " + heights[1] + ", " + heights[2]);
            assertEquals(List.of(), fired, "no resize must report a view change");
        } finally {
            onEdt(root::removeNotify);
        }
    }

    /** The chips' bounds, for failure messages. */
    private static List<Rectangle> chipBounds(ViewFilterRibbon ribbon) {
        List<Rectangle> out = new ArrayList<>();
        for (JToggleButton chip : chips(ribbon)) {
            out.add(chip.getBounds());
        }
        return out;
    }

    /** The platform search field TaskTreePanel puts above the ribbon, made on the EDT. */
    private static SearchTextField onEdtSearch() {
        SearchTextField[] out = new SearchTextField[1];
        onEdt(() -> out[0] = new SearchTextField(false));
        return out[0];
    }

    /** An int read on the EDT, where the ribbon lives. */
    private static int onEdtInt(IntSupplier op) {
        int[] out = new int[1];
        onEdt(() -> out[0] = op.getAsInt());
        return out[0];
    }

    // ---- Swing plumbing ----------------------------------------------------------

    /** Runs on the EDT: the ribbon is a Swing component, so it is read where it is mutated. */
    private static void onEdt(Runnable op) {
        if (EventQueue.isDispatchThread()) {
            op.run();
            return;
        }
        try {
            EventQueue.invokeAndWait(op);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted running on the EDT", e);
        } catch (InvocationTargetException e) {
            throw new AssertionError("EDT operation failed", e.getCause());
        }
    }
}
