package app.drydock.intellij.review;

import app.drydock.review.tour.ReviewTour;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourStep;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultListModel;
import javax.swing.JEditorPane;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.HyperlinkEvent;

import java.awt.CardLayout;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The Review window's Tour tab: the scope's guided tour as a step list over
 * the selected step's text ({@link TourView}). Following a step's code link
 * hands its anchor to {@code openAnchor}, which opens the review diff at
 * that line, the way selecting a finding does.
 *
 * <p>A plain reading of the tour: nothing is hidden, checks are not graded,
 * and no progress is written back to the tour store (see {@link TourView}).
 * EDT only; the caller reads the store off the EDT and hands the result to
 * {@link #show}.</p>
 */
final class ReviewTourPanel extends JPanel {

    static final String EMPTY_TEXT = "No guided tour for this review. Ask for one with Review Changes with Tour "
            + "in the Drydock task window, or tick \"Also write a guided tour\" when starting a review.";

    private static final String EMPTY_CARD = "empty";
    private static final String TOUR_CARD = "tour";

    private final Consumer<TourAnchor> openAnchor;
    private final DefaultListModel<TourStep> steps = new DefaultListModel<>();
    private final JBList<TourStep> stepList = new JBList<>(steps);
    private final JEditorPane detail = new JEditorPane();
    private final CardLayout cards = new CardLayout();

    /** The shown tour; null when there is none. */
    private ReviewTour tour;
    private boolean stale;
    /** Check ids whose answer the reviewer asked to see; kept across refreshes of the same scope. */
    private final Set<String> revealed = new HashSet<>();

    ReviewTourPanel(Consumer<TourAnchor> openAnchor) {
        this.openAnchor = Objects.requireNonNull(openAnchor, "openAnchor");
        setLayout(cards);

        JBLabel empty = new JBLabel(EMPTY_TEXT, SwingConstants.CENTER);
        empty.setAllowAutoWrapping(true);
        empty.setBorder(JBUI.Borders.empty(12));
        add(empty, EMPTY_CARD);

        stepList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        stepList.setCellRenderer(new ColoredListCellRenderer<TourStep>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends TourStep> list, TourStep step,
                                                 int index, boolean selected, boolean hasFocus) {
                if (step != null) {
                    append(TourView.stepLabel(index + 1, step));
                }
            }
        });
        stepList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                renderSelected();
            }
        });

        detail.setEditorKit(HTMLEditorKitBuilder.simple());
        detail.setEditable(false);
        detail.setBorder(JBUI.Borders.empty(4, 8));
        detail.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                follow(e.getDescription());
            }
        });

        JBSplitter split = new JBSplitter(true, "drydock.review.tour.splitter", 0.3f);
        split.setFirstComponent(new JBScrollPane(stepList));
        split.setSecondComponent(new JBScrollPane(detail));
        add(split, TOUR_CARD);
        cards.show(this, EMPTY_CARD);
    }

    /**
     * Shows {@code shown} (empty: the empty state). {@code isStale}: the
     * tour was written for hunks the current review diff no longer has.
     * Keeps the selected step when the refreshed tour still has it.
     */
    void show(Optional<ReviewTour> shown, boolean isStale) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        Objects.requireNonNull(shown, "shown");
        String selectedId = Optional.ofNullable(stepList.getSelectedValue()).map(TourStep::id).orElse(null);
        if (tour != null && shown.map(t -> !t.scopeId().equals(tour.scopeId())).orElse(true)) {
            revealed.clear(); // another scope's checks, or no tour: nothing stays revealed
        }
        tour = shown.orElse(null);
        stale = isStale;
        steps.clear();
        if (tour == null || tour.steps().isEmpty()) {
            detail.setText("");
            cards.show(this, EMPTY_CARD);
            return;
        }
        List<TourStep> all = tour.steps();
        all.forEach(steps::addElement);
        int index = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(selectedId)) {
                index = i;
            }
        }
        cards.show(this, TOUR_CARD);
        stepList.setSelectedIndex(index); // renders through the selection listener
        renderSelected(); // the same index re-selected fires no event
    }

    /** The shown tour's step count; 0 when there is none. */
    int stepCount() {
        return steps.size();
    }

    private void renderSelected() {
        TourStep step = stepList.getSelectedValue();
        if (step == null || tour == null) {
            detail.setText("");
            return;
        }
        detail.setText(TourView.stepHtml(tour.number(step.id()), tour.steps().size(), step, stale, revealed));
        detail.setCaretPosition(0);
    }

    private void follow(String href) {
        TourStep step = stepList.getSelectedValue();
        if (step == null) {
            return;
        }
        TourView.resolve(step, href).ifPresent(link -> {
            switch (link) {
                case TourView.AnchorLink anchor -> openAnchor.accept(anchor.anchor());
                case TourView.RevealLink reveal -> {
                    revealed.add(reveal.checkId());
                    renderSelected();
                }
            }
        });
    }
}
