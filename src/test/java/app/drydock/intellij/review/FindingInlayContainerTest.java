package app.drydock.intellij.review;

import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.EventQueue;
import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inlay container's geometry over suppliers (no editor): the card is
 * forced to the live text width — re-applied on every editor resize — and
 * the reserved height is capped, so a long card scrolls instead of
 * stretching the inlay past the viewport.
 */
class FindingInlayContainerTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    @Test
    void theContainerForcesTheCardToTheLiveTextWidthAndCapsTheHeight() throws Exception {
        AtomicInteger width = new AtomicInteger(500);
        AtomicInteger cap = new AtomicInteger(120);
        AtomicReference<FindingInlayCard> card = new AtomicReference<>();

        onEdt(() -> card.set(new FindingInlayCard(finding(), Optional.empty(),
                Clock.fixed(NOW, java.time.ZoneOffset.UTC), noopCallbacks())));

        onEdt(() -> {
            FindingInlayContainer container = new FindingInlayContainer(card.get(),
                    width::get, cap::get);

            Dimension preferred = container.getPreferredSize();
            assertEquals(500, preferred.width, "the inlay is the editor text width, live");
            assertTrue(preferred.height <= 120,
                    "the card's height is capped at the supplier's maximum: " + preferred.height);

            width.set(700);
            container.editorResized();
            assertEquals(700, container.getPreferredSize().width, "a resize re-applies the new width");
        });
        // The card was told the width minus the container's margin, so its
        // text blocks wrap at the width they will actually be laid out at —
        // a long title never reports the unwrapped single-line width.
        onEdt(() -> assertTrue(card.get().getPreferredSize().width <= 700,
                "the card wraps at the assigned width, never the unwrapped width"));
    }

    // ---- fixtures -------------------------------------------------------------

    private static ReviewAnnotation finding() {
        return new ReviewAnnotation(
                "rs_a", "f1", "src/A.java", "n3", "n3",
                Severity.BLOCKING, Confidence.HIGH,
                Optional.of("This title is long enough that wrapping it at a few hundred pixels "
                        + "must take more than one line, otherwise the card truncates"), "reviewer",
                NOW.minus(Duration.ofHours(2)), List.of(), Optional.empty(), Optional.empty(),
                List.of(), List.of(new ReviewAnnotation.Message("reviewer", NOW, "This leaks.")),
                Optional.empty(), AnnotationStatus.OPEN,
                Optional.empty(), false, Triage.PROPOSED, Optional.empty());
    }

    private static FindingInlayCard.Callbacks noopCallbacks() {
        return new FindingInlayCard.Callbacks() {
            @Override public void confirm() { }
            @Override public void dismiss() { }
            @Override public void undo() { }
            @Override public void comment(String text) { }
        };
    }

    /** Runs on the EDT: the card and the container are Swing components. */
    private static void onEdt(Runnable op) throws Exception {
        if (EventQueue.isDispatchThread()) {
            op.run();
            return;
        }
        try {
            EventQueue.invokeAndWait(op);
        } catch (InvocationTargetException e) {
            throw new AssertionError("the EDT runnable failed", e.getCause());
        }
    }
}
