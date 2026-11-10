package app.drydock.intellij.review;

import com.intellij.openapi.ui.Splitter;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JTextArea;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.event.MouseEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The collapsible splitter pane as a pure Swing component: the expanded
 * pane's minimum width, the collapse to a strip whose width the splitter's
 * proportion is clamped to, the expand restoring the proportion, the strip
 * click and the header chevron as user toggles, and a divider drag past the
 * strip expanding the pane. Runs headless on the EDT; the splitter is the
 * platform's own, without a proportion key, so nothing persists.
 */
class CollapsiblePaneTest {

    private final List<Boolean> userToggles = new ArrayList<>();

    // ---- fixtures -----------------------------------------------------------

    private record Pane(Splitter splitter, CollapsiblePane pane, JComponent content) {
    }

    /** A first-component pane over a laid-out splitter of {@code width} px. */
    private Pane pane(int width, int minWidth) throws InvocationTargetException, InterruptedException {
        return pane(width, minWidth, true);
    }

    private Pane pane(int width, int minWidth, boolean first)
            throws InvocationTargetException, InterruptedException {
        List<Pane> made = new ArrayList<>();
        EventQueue.invokeAndWait(() -> {
            Splitter splitter = new Splitter(false, 0.3f);
            JTextArea content = new JTextArea("content");
            CollapsiblePane pane = new CollapsiblePane(splitter, first, content, "Navigator", minWidth,
                    userToggles::add);
            JComponent other = new JTextArea("other");
            if (first) {
                splitter.setFirstComponent(pane);
                splitter.setSecondComponent(other);
            } else {
                splitter.setFirstComponent(other);
                splitter.setSecondComponent(pane);
            }
            splitter.setSize(new Dimension(width, 400));
            splitter.doLayout();
            made.add(new Pane(splitter, pane, content));
        });
        return made.get(0);
    }

    private static void onEdt(Runnable action) throws InvocationTargetException, InterruptedException {
        EventQueue.invokeAndWait(action);
    }

    // ---- expanded -----------------------------------------------------------

    @Test void expandedPaneShowsContentAndKeepsItsMinimumWidth()
            throws InvocationTargetException, InterruptedException {
        Pane pane = pane(1000, 200);
        onEdt(() -> {
            assertTrue(pane.content().isVisible());
            assertFalse(pane.pane().isCollapsed());
            assertTrue(pane.pane().getMinimumSize().width >= 200,
                    "the splitter's divider cannot pull the pane under its minimum width");
            assertTrue(userToggles.isEmpty(), "no toggle happened");
        });
    }

    // ---- collapsed ----------------------------------------------------------

    @Test void collapsingHidesTheContentAndClampsTheProportionToTheStrip()
            throws InvocationTargetException, InterruptedException {
        Pane pane = pane(1000, 200);
        onEdt(() -> pane.pane().setCollapsed(true, true));
        onEdt(() -> {
            assertFalse(pane.content().isVisible());
            assertTrue(pane.pane().isCollapsed());
            assertTrue(pane.pane().getMinimumSize().width < 40, "only the strip is left");
            float expected = pane.pane().strip().getPreferredSize().width / (1000f - pane.splitter()
                    .getDividerWidth());
            assertEquals(expected, pane.splitter().getProportion(), 0.01f,
                    "the divider sits at the strip");
            assertEquals(List.of(true), userToggles, "the collapse was a user toggle");
        });
    }

    @Test void collapsingTheSecondPaneClampsItFromTheRight()
            throws InvocationTargetException, InterruptedException {
        Pane pane = pane(1000, 200, false);
        onEdt(() -> pane.pane().setCollapsed(true, true));
        onEdt(() -> {
            float strip = pane.pane().strip().getPreferredSize().width;
            float expected = 1f - strip / (1000f - pane.splitter().getDividerWidth());
            assertEquals(expected, pane.splitter().getProportion(), 0.01f);
        });
    }

    @Test void expandingRestoresTheProportionThePaneHadBefore()
            throws InvocationTargetException, InterruptedException {
        Pane pane = pane(1000, 200);
        onEdt(() -> {
            float before = pane.splitter().getProportion();
            pane.pane().setCollapsed(true, true);
            pane.pane().setCollapsed(false, true);
            assertEquals(before, pane.splitter().getProportion(), 0.01f);
            assertTrue(pane.content().isVisible());
            assertEquals(List.of(true, false), userToggles);
        });
    }

    @Test void theHeaderChevronCollapsesAndTheStripClickExpands()
            throws InvocationTargetException, InterruptedException {
        Pane pane = pane(1000, 200);
        onEdt(() -> {
            JButton chevron = findButton(pane.pane());
            chevron.doClick();
            assertTrue(pane.pane().isCollapsed(), "the header chevron collapses");
            pane.pane().strip().dispatchEvent(new java.awt.event.MouseEvent(pane.pane().strip(),
                    java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false));
            assertFalse(pane.pane().isCollapsed(), "the strip click expands");
            assertEquals(List.of(true, false), userToggles);
        });
    }

    /** The first icon button in {@code root}'s hierarchy. */
    private static JButton findButton(java.awt.Container root) {
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof JButton button && button.getIcon() != null) {
                return button;
            }
            if (component instanceof java.awt.Container container) {
                JButton found = findButton(container);
                if (found != null) {
                    return found;
                }
            }
        }
        throw new AssertionError("no icon button in " + root);
    }

    // ---- drag to expand -----------------------------------------------------

    @Test void draggingTheDividerPastTheStripExpandsACollapsedPane()
            throws InvocationTargetException, InterruptedException {
        Pane pane = pane(1000, 200);
        onEdt(() -> pane.pane().setCollapsed(true, true));
        // The user grabs the divider and drags it well into the pane: the press
        // makes the proportion change user intent, the drag moves it.
        onEdt(() -> pane.splitter().getDivider().dispatchEvent(new MouseEvent(
                pane.splitter().getDivider(), MouseEvent.MOUSE_PRESSED, 0, 0, 1, 1, 1,
                false, MouseEvent.BUTTON1)));
        onEdt(() -> pane.splitter().setProportion(0.3f));
        onEdt(() -> pane.splitter().getDivider().dispatchEvent(new MouseEvent(
                pane.splitter().getDivider(), MouseEvent.MOUSE_RELEASED, 0, 0, 1, 1, 1,
                false, MouseEvent.BUTTON1)));
        onEdt(() -> {
            assertFalse(pane.pane().isCollapsed(), "the drag expanded the pane");
            assertTrue(pane.content().isVisible());
            assertEquals(List.of(true, false), userToggles, "a drag-expand is a user toggle");
        });
    }

    @Test void aProportionChangeWithoutADividerPressDoesNotExpand()
            throws InvocationTargetException, InterruptedException {
        Pane pane = pane(1000, 200);
        onEdt(() -> pane.pane().setCollapsed(true, true));
        // A programmatic load: the splitter restores a persisted expanded
        // proportion while the pane is collapsed — nothing was pressed.
        onEdt(() -> pane.splitter().setProportion(0.3f));
        onEdt(() -> {
            assertTrue(pane.pane().isCollapsed(), "no press, no expand");
            assertFalse(pane.content().isVisible());
            assertEquals(List.of(true), userToggles,
                    "only the collapse is a user toggle; the spurious event is not");
        });
    }

    @Test void aDegenerateProportionAtCollapseDropsTheRecordedOne()
            throws InvocationTargetException, InterruptedException {
        Pane pane = pane(1000, 200);
        onEdt(() -> {
            pane.splitter().setProportion(0.6f);
            pane.pane().setCollapsed(true, true);   // records 0.6
            pane.pane().setCollapsed(false, true); // restores 0.6
            assertEquals(0.6f, pane.splitter().getProportion(), 0.01f);
            // A degenerate reading while expanded (an un-laid-out or garbage
            // splitter): the collapse must not keep the stale 0.6.
            pane.splitter().setProportion(1f);
            pane.pane().setCollapsed(true, true);
            pane.pane().setCollapsed(false, true);
            assertTrue(pane.content().isVisible());
            assertNotEquals(0.6f, pane.splitter().getProportion(), 0.05f,
                    "a degenerate record is dropped, not restored");
        });
    }

    @Test void aCollapseAppliedBeforeTheSplitterHasAWidthClampsOnTheFirstLayout()
            throws InvocationTargetException, InterruptedException {
        List<Pane> made = new ArrayList<>();
        EventQueue.invokeAndWait(() -> {
            Splitter splitter = new Splitter(false, 0.3f);
            CollapsiblePane pane = new CollapsiblePane(splitter, true, new JTextArea("content"),
                    "Navigator", 200, userToggles::add);
            splitter.setFirstComponent(pane);
            splitter.setSecondComponent(new JTextArea("other"));
            pane.setCollapsed(true, false); // the restored state: no width yet
            made.add(new Pane(splitter, pane, null));
        });
        onEdt(() -> {
            Pane pane = made.get(0);
            pane.splitter().setSize(new Dimension(1000, 400));
            // The pane's first real layout pass: the pane's doLayout re-clamps
            // (a non-displayable splitter lays nothing out, so there is no
            // earlier hook, and resize events reach displayable components only).
            pane.pane().doLayout();
            float strip = pane.pane().strip().getPreferredSize().width;
            float expected = strip / (1000f - pane.splitter().getDividerWidth());
            assertEquals(expected, pane.splitter().getProportion(), 0.01f,
                    "the first layout puts the divider at the strip");
            assertTrue(userToggles.isEmpty(), "a restored collapse is not a user toggle");
        });
    }
}
