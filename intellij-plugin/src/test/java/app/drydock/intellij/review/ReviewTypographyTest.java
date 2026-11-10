package app.drydock.intellij.review;

import app.drydock.review.tour.HunkTake;
import com.intellij.util.ui.UIUtil;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.EventQueue;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review typography utility as pure Swing contracts: the measured
 * wrapper's floor is one pixel while its preferred width keeps the 42-em
 * cap and its layout clamps to the granted width; the restyle walk prunes
 * marked self-restyling subtrees while still covering everything else — and
 * never prunes a panel that did not opt in. Runs headless on the EDT.
 */
class ReviewTypographyTest {

    private static void onEdt(Runnable action) throws InvocationTargetException, InterruptedException {
        EventQueue.invokeAndWait(action);
    }

    // ---- measure contract ---------------------------------------------------

    @Test void theMeasuredPanelShrinksToItsFloorButPrefersTheMeasure()
            throws InvocationTargetException, InterruptedException {
        List<JComponent> made = new java.util.ArrayList<>();
        onEdt(() -> {
            JTextArea prose = ReviewTypography.prose(ReviewTypography.Role.BODY);
            prose.setText("word ".repeat(200));
            JComponent panel = ReviewTypography.measured(prose);
            made.add(panel);
        });
        onEdt(() -> {
            JComponent panel = made.get(0);
            Dimension minimum = panel.getMinimumSize();
            assertEquals(1, minimum.width, "the floor is one pixel, not the measure");
            assertEquals(ReviewTypography.measure(), panel.getPreferredSize().width,
                    "the preferred width keeps the 42-em cap");
            assertTrue(minimum.height >= panel.getPreferredSize().height,
                    "a narrower floor can only wrap to more lines, never fewer");
            panel.setSize(200, 1000);
            panel.doLayout();
            JTextArea prose = (JTextArea) panel.getComponent(0);
            assertEquals(Math.min(ReviewTypography.measure(), 200), prose.getWidth(),
                    "layout still clamps to the granted width");
        });
    }

    // ---- restyle coverage ---------------------------------------------------

    @Test void restyleSkipsSelfRestylingSubtreesButCoversEverythingElse()
            throws InvocationTargetException, InterruptedException {
        onEdt(() -> {
            JLabel covered = ReviewTypography.label("covered", ReviewTypography.Role.META);
            JLabel inside = ReviewTypography.label("inside", ReviewTypography.Role.META);
            Font corrupt = new Font(Font.DIALOG, Font.ITALIC, 7);
            covered.setFont(corrupt);
            inside.setFont(corrupt);
            JPanel marked = new JPanel();
            ReviewTypography.selfRestyling(marked);
            marked.add(inside);
            JPanel root = new JPanel();
            root.add(covered);
            root.add(marked);
            ReviewTypography.restyle(root);
            assertEquals(ReviewTypography.font(ReviewTypography.Role.META), covered.getFont(),
                    "an unmarked typed component is restyled");
            assertEquals(corrupt, inside.getFont(),
                    "a marked subtree restyles itself; the walk does not enter it");
        });
    }

    @Test void theTakePanelIsNotSelfRestylingSoRestyleStillCoversIt()
            throws InvocationTargetException, InterruptedException {
        onEdt(() -> {
            HunkTakePanel take = new HunkTakePanel(id -> { });
            take.render(Optional.of(new HunkTake("d", "does", HunkTake.Alignment.ALIGNED, "",
                    List.of(), Instant.EPOCH)));
            JTextArea body = (JTextArea) UIUtil.uiTraverser(take).filter(JTextArea.class).first();
            Font corrupt = new Font(Font.DIALOG, Font.ITALIC, 7);
            body.setFont(corrupt);
            ReviewTypography.restyle(take);
            assertEquals(ReviewTypography.font(ReviewTypography.Role.BODY), body.getFont(),
                    "the take panel is not self-restyling; restyle still covers it");
        });
    }
}
