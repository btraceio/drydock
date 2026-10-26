package app.drydock.intellij.review;

import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;

import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.swing.JButton;
import javax.swing.JTextField;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The inlay card as a pure Swing component (AC1's card half and AC8's
 * UI-side progress contract): which severity, title, author/age, drift hint,
 * thread messages and state-specific actions each finding state shows, that
 * clicks and Enter invoke the injected callbacks only — with the controls
 * disabled and progress showing before the callback runs — and that every
 * completion and disposal path clears the progress. The card never touches a
 * store, so everything here runs headless on the EDT with a fixed clock.
 */
class FindingInlayCardTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final String AGO_2H = "2h ago";
    private static final String FIRST_MESSAGE = "reviewer · " + AGO_2H + ": This leaks.";

    // ---- fixtures -----------------------------------------------------------

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static ReviewAnnotation finding(Triage triage, AnnotationStatus status) {
        return new ReviewAnnotation(
                "s1", "f1", "src/A.java", "n3", "n3",
                Severity.BLOCKING, Confidence.HIGH, Optional.of("Leak"), "reviewer",
                NOW.minus(Duration.ofHours(2)), List.of(), Optional.empty(), Optional.empty(),
                List.of(), thread("reviewer", "This leaks."), Optional.empty(), status,
                Optional.empty(), false, triage, Optional.empty());
    }

    private static List<ReviewAnnotation.Message> thread(String... authorThenText) {
        List<ReviewAnnotation.Message> messages = new ArrayList<>();
        for (int i = 0; i < authorThenText.length; i += 2) {
            messages.add(new ReviewAnnotation.Message(authorThenText[i],
                    NOW.minus(Duration.ofHours(2)), authorThenText[i + 1]));
        }
        return messages;
    }

    private static FindingInlayCard card(ReviewAnnotation finding, Recording owner, Optional<String> drift) {
        FindingInlayCard[] out = new FindingInlayCard[1];
        onEdt(() -> out[0] = new FindingInlayCard(finding, drift, clock(), owner));
        owner.card = out[0];
        return out[0];
    }

    private static FindingInlayCard card(ReviewAnnotation finding, Recording owner) {
        return card(finding, owner, Optional.empty());
    }

    /** Records every callback plus what the card looked like at the moment the callback ran. */
    private static class Recording implements FindingInlayCard.Callbacks {
        final List<String> calls = new ArrayList<>();
        final List<Boolean> busyAtCall = new ArrayList<>();
        final List<Boolean> progressAtCall = new ArrayList<>();
        FindingInlayCard card;

        @Override public void confirm() { record("confirm"); }
        @Override public void dismiss() { record("dismiss"); }
        @Override public void undo() { record("undo"); }
        @Override public void comment(String text) { record("comment:" + text); }

        void record(String what) {
            calls.add(what);
            busyAtCall.add(card.isBusy());
            progressAtCall.add(card.progressVisible());
        }
    }

    // ---- component state matrix ----------------------------------------------

    @Test
    void proposedShowsAgreeAndWontFixByDefault() {
        FindingInlayCard card = card(finding(Triage.PROPOSED, AnnotationStatus.OPEN), new Recording());

        onEdt(() -> {
            assertEquals(List.of("Agree", "Won't fix"), buttonTexts(card));
            assertTrue(button(card, "Agree").isEnabled());
            assertFalse(card.stateVisible());
        });
    }

    /** A review with a pull request to post to confirms findings into it: the labels name that meaning. */
    @Test
    void aPullRequestReviewConfirmsInsteadOfAgrees() {
        FindingInlayCard card = card(finding(Triage.PROPOSED, AnnotationStatus.OPEN), new Recording());

        onEdt(() -> card.setTriageLabels("Confirm", "Dismiss"));

        onEdt(() -> assertEquals(List.of("Confirm", "Dismiss"), buttonTexts(card)));
    }

    @Test
    void confirmedAndDismissedKeepTheCardAndExposeUndo() {
        FindingInlayCard confirmed = card(finding(Triage.CONFIRMED, AnnotationStatus.OPEN), new Recording());
        FindingInlayCard dismissed = card(finding(Triage.DISMISSED, AnnotationStatus.OPEN), new Recording());

        onEdt(() -> {
            assertEquals(List.of("Undo"), buttonTexts(confirmed));
            assertTrue(confirmed.stateVisible());
            assertEquals("confirmed", confirmed.stateText());
            assertEquals(List.of("Undo"), buttonTexts(dismissed));
            assertEquals("dismissed", dismissed.stateText());
        });
    }

    @Test
    void sentIsShownAsStateWithoutPretendingAReply() {
        FindingInlayCard card = card(finding(Triage.PROPOSED, AnnotationStatus.SENT), new Recording());

        onEdt(() -> {
            assertTrue(card.stateVisible());
            assertEquals("sent", card.stateText());
            // no invented reply: the thread shows exactly the finding's own messages
            assertEquals(List.of(FIRST_MESSAGE), card.threadLines());
            assertEquals(List.of("Agree", "Won't fix"), buttonTexts(card));
        });
    }

    @Test
    void driftHintIsShownOnlyWhenGiven() {
        FindingInlayCard plain = card(finding(Triage.PROPOSED, AnnotationStatus.OPEN), new Recording());
        FindingInlayCard drifted = card(finding(Triage.PROPOSED, AnnotationStatus.OPEN), new Recording(),
                Optional.of(ReviewTabModel.DRIFT_HINT));

        onEdt(() -> {
            assertFalse(plain.driftShowing());
            assertTrue(drifted.driftShowing());
            assertEquals(ReviewTabModel.DRIFT_HINT, drifted.driftText());
        });
    }

    @Test
    void headerAuthorAndAgeComeFromTheFindingAndTheInjectedClock() {
        ReviewAnnotation withTwoMessages = finding(Triage.PROPOSED, AnnotationStatus.OPEN)
                .withReply(new ReviewAnnotation.Message("You", NOW.minus(Duration.ofMinutes(5)), "Why here?"));
        FindingInlayCard card = card(withTwoMessages, new Recording());

        onEdt(() -> {
            assertEquals("blocking · Leak", headerText(card));
            assertEquals("reviewer · " + AGO_2H, metaText(card));
            assertEquals(List.of(FIRST_MESSAGE, "You · 5m ago: Why here?"), card.threadLines());
        });
    }

    // ---- age formatting -------------------------------------------------------

    @Test
    void relativeAgeIsDeterministicAndClamped() {
        assertEquals("now", FindingInlayCard.age(NOW, NOW));
        assertEquals("now", FindingInlayCard.age(NOW.plusSeconds(30), NOW)); // clock skew never shows a negative age
        assertEquals("5m ago", FindingInlayCard.age(NOW.minus(Duration.ofMinutes(5)), NOW));
        assertEquals("2h ago", FindingInlayCard.age(NOW.minus(Duration.ofHours(2)), NOW));
        assertEquals("3d ago", FindingInlayCard.age(NOW.minus(Duration.ofDays(3)), NOW));
    }

    // ---- actions, Enter and the progress contract -------------------------------

    @Test
    void aClickDisablesControlsAndShowsProgressBeforeTheCallbackRuns() {
        Recording owner = new Recording();
        FindingInlayCard card = card(finding(Triage.PROPOSED, AnnotationStatus.OPEN), owner);

        onEdt(() -> button(card, "Agree").doClick());

        assertEquals(List.of("confirm"), owner.calls);
        assertEquals(List.of(true), owner.busyAtCall, "the callback must see the card already busy");
        assertEquals(List.of(true), owner.progressAtCall, "progress must show before the callback runs");
        onEdt(() -> {
            assertTrue(card.isBusy());
            assertTrue(card.progressVisible());
            assertFalse(button(card, "Agree").isEnabled());
            assertFalse(commentField(card).isEnabled());
        });

        onEdt(card::complete);
        onEdt(() -> {
            assertFalse(card.isBusy());
            assertFalse(card.progressVisible());
            assertTrue(button(card, "Agree").isEnabled());
            assertTrue(commentField(card).isEnabled());
        });

        onEdt(card::complete); // idempotent: a second completion changes nothing
        onEdt(() -> assertTrue(button(card, "Agree").isEnabled()));
    }

    @Test
    void aRefusedOrFailedCompletionClearsProgressToo() {
        Recording owner = new Recording();
        FindingInlayCard card = card(finding(Triage.PROPOSED, AnnotationStatus.OPEN), owner);
        onEdt(() -> button(card, "Won't fix").doClick());

        onEdt(card::complete); // the owner reports a refusal or failure the same way as success

        onEdt(() -> {
            assertEquals(List.of("dismiss"), owner.calls);
            assertFalse(card.isBusy());
            assertFalse(card.progressVisible());
            assertTrue(button(card, "Won't fix").isEnabled());
        });
    }

    @Test
    void enterSubmitsTheCommentThroughTheCallbackOnly() {
        Recording owner = new Recording();
        FindingInlayCard card = card(finding(Triage.CONFIRMED, AnnotationStatus.OPEN), owner);

        onEdt(() -> {
            commentField(card).setText("please fix");
            commentField(card).postActionEvent();
        });

        assertEquals(List.of("comment:please fix"), owner.calls);
        assertEquals(List.of(true), owner.busyAtCall);
        onEdt(card::complete);
        onEdt(() -> assertTrue(commentField(card).isEnabled()));
    }

    @Test
    void aSyncThrowingCallbackRestoresTheCardAndPropagates() {
        Recording owner = new Recording() {
            @Override public void confirm() {
                record("confirm");
                throw new IllegalStateException("sync boom");
            }
        };
        FindingInlayCard card = card(finding(Triage.PROPOSED, AnnotationStatus.OPEN), owner);

        IllegalStateException[] thrown = new IllegalStateException[1];
        onEdt(() -> {
            try {
                button(card, "Agree").doClick();
            } catch (IllegalStateException e) {
                thrown[0] = e;
            }
        });

        assertNotNull(thrown[0], "the synchronous throw must propagate, never be swallowed");
        assertEquals("sync boom", thrown[0].getMessage());
        assertEquals(List.of("confirm"), owner.calls);
        assertEquals(List.of(true), owner.busyAtCall, "the callback still sees the card already busy");
        onEdt(() -> {
            assertFalse(card.isBusy(), "no async owner exists to call complete(), so the card must restore itself");
            assertFalse(card.progressVisible());
            assertTrue(button(card, "Agree").isEnabled());
            assertTrue(commentField(card).isEnabled());
        });
    }

    @Test
    void aBlankCommentSubmitsNothing() {
        Recording owner = new Recording();
        FindingInlayCard card = card(finding(Triage.PROPOSED, AnnotationStatus.OPEN), owner);

        onEdt(() -> {
            commentField(card).setText("   ");
            commentField(card).postActionEvent();
        });

        assertTrue(owner.calls.isEmpty());
        onEdt(() -> assertFalse(card.isBusy()));
    }

    @Test
    void disposalRemovesProgressAndALateCompletionChangesNothing() {
        Recording owner = new Recording();
        FindingInlayCard card = card(finding(Triage.PROPOSED, AnnotationStatus.OPEN), owner);
        onEdt(() -> button(card, "Agree").doClick());
        onEdt(() -> assertTrue(card.progressVisible()));

        onEdt(card::dispose);

        onEdt(() -> {
            assertEquals(List.of("confirm"), owner.calls);
            assertFalse(card.isBusy());
            assertFalse(card.progressVisible());
            assertFalse(button(card, "Agree").isEnabled());
            assertFalse(commentField(card).isEnabled());
        });

        onEdt(card::complete); // the async work finished after the inlay went away
        onEdt(() -> {
            assertFalse(card.progressVisible());
            assertFalse(button(card, "Agree").isEnabled());
            assertFalse(commentField(card).isEnabled());
        });
    }

    @Test
    void updateRebuildsStateThreadAndActionsWithoutLosingTheBusyContract() {
        Recording owner = new Recording();
        FindingInlayCard card = card(finding(Triage.PROPOSED, AnnotationStatus.OPEN), owner);
        onEdt(() -> button(card, "Agree").doClick());

        ReviewAnnotation next = finding(Triage.CONFIRMED, AnnotationStatus.SENT)
                .withReply(new ReviewAnnotation.Message("You", NOW, "on it"));

        onEdt(() -> card.update(next));

        onEdt(() -> {
            assertEquals("confirmed · sent", card.stateText());
            assertEquals(List.of("Undo"), buttonTexts(card));
            assertEquals(List.of(FIRST_MESSAGE, "You · now: on it"), card.threadLines());
            assertTrue(card.isBusy(), "a rebuild during an in-flight action keeps the card busy");
            assertFalse(button(card, "Undo").isEnabled());
        });

        onEdt(card::complete);
        onEdt(() -> assertTrue(button(card, "Undo").isEnabled()));
    }

    // ---- threading -------------------------------------------------------------

    @Test
    void constructionAndMutationAssertTheEdt() {
        assumeFalse(EventQueue.isDispatchThread(), "this test needs a non-EDT thread");

        ReviewAnnotation finding = finding(Triage.PROPOSED, AnnotationStatus.OPEN);
        assertThrows(IllegalStateException.class,
                () -> new FindingInlayCard(finding, Optional.empty(), clock(), new Recording()));

        FindingInlayCard card = card(finding, new Recording());
        assertThrows(IllegalStateException.class, () -> card.update(finding));
        assertThrows(IllegalStateException.class, card::complete);
        assertThrows(IllegalStateException.class, card::dispose);
    }

    // ---- Swing plumbing ----------------------------------------------------------

    /** Runs on the EDT: the card is a Swing component, so its state is read where it is mutated. */
    private static void onEdt(Runnable op) {
        if (EventQueue.isDispatchThread()) {
            op.run();
            return;
        }
        try {
            EventQueue.invokeAndWait(op);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the EDT", e);
        } catch (InvocationTargetException e) {
            throw new AssertionError("the EDT runnable failed", e.getCause());
        }
    }

    private static List<Component> all(Component root) {
        List<Component> out = new ArrayList<>();
        out.add(root);
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                out.addAll(all(child));
            }
        }
        return out;
    }

    private static JButton button(FindingInlayCard card, String text) {
        return all(card).stream()
                .filter(c -> c instanceof JButton && text.equals(((JButton) c).getText()))
                .map(c -> (JButton) c)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button " + text));
    }

    private static List<String> buttonTexts(FindingInlayCard card) {
        return all(card).stream()
                .filter(c -> c instanceof JButton)
                .map(c -> ((JButton) c).getText())
                .toList();
    }

    private static JTextField commentField(FindingInlayCard card) {
        return (JTextField) all(card).stream()
                .filter(c -> c instanceof JTextField)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no comment field"));
    }

    private static String headerText(FindingInlayCard card) {
        return card.headerText();
    }

    private static String metaText(FindingInlayCard card) {
        return card.metaText();
    }

    // ---- geometry: wrapping, selection, alignment ------------------------------

    /**
     * A wrap width makes the card's text blocks wrap at it: the preferred
     * width stays at the assigned width and a long text's preferred height
     * grows beyond the single line a fresh text area reports — the wrapped
     * height is what the inlay must reserve, and the truncation was its
     * absence.
     */
    @Test
    void aWrapWidthWrapsTheTextBlocksAtIt() {
        ReviewAnnotation longFinding = new ReviewAnnotation(
                "s1", "f1", "src/A.java", "n3", "n3",
                Severity.BLOCKING, Confidence.HIGH,
                Optional.of("This title is long enough that wrapping it at two hundred pixels "
                        + "must take more than one line, otherwise the card truncates"), "reviewer",
                NOW.minus(Duration.ofHours(2)), List.of(), Optional.empty(), Optional.empty(),
                List.of(), thread("reviewer", "A message long enough that wrapping it at two "
                + "hundred pixels must take more than one line, otherwise the card truncates."),
                Optional.empty(), AnnotationStatus.OPEN,
                Optional.empty(), false, Triage.PROPOSED, Optional.empty());
        FindingInlayCard card = card(longFinding, new Recording());

        onEdt(() -> {
            int unwrappedHeight = card.blockHeights().stream().mapToInt(Integer::intValue).sum();
            card.setWrapWidth(200);
            int wrappedHeight = card.blockHeights().stream().mapToInt(Integer::intValue).sum();
            assertTrue(wrappedHeight > unwrappedHeight,
                    "a long text wraps to a taller block at 200px");
            for (java.awt.Dimension preferred : blockPreferredSizes(card)) {
                assertTrue(preferred.width <= 200, "blocks wrap at, never exceed, the assigned width");
            }
        });
    }

    /** The text blocks are selectable for copy-paste but never editable. */
    @Test
    void textBlocksAreSelectableAndNotEditable() {
        FindingInlayCard card = card(finding(Triage.PROPOSED, AnnotationStatus.OPEN), new Recording());

        onEdt(() -> {
            List<javax.swing.JTextArea> areas = all(card).stream()
                    .filter(c -> c instanceof javax.swing.JTextArea)
                    .map(c -> (javax.swing.JTextArea) c)
                    .toList();
            assertTrue(areas.size() >= 3, "header, drift and thread are text blocks");
            for (javax.swing.JTextArea area : areas) {
                assertFalse(area.isEditable(), "the reviewer copies, never edits, the card's words");
                assertTrue(area.getWrapStyleWord(), "wrapping breaks at word ends");
            }
        });
    }

    /** The action buttons sit at the card's right edge, not under the text. */
    @Test
    void actionsAlignToTheRightEdge() {
        FindingInlayCard card = card(finding(Triage.PROPOSED, AnnotationStatus.OPEN), new Recording());

        onEdt(() -> {
            javax.swing.JPanel actionsPanel = all(card).stream()
                    .filter(c -> c instanceof javax.swing.JPanel panel
                            && panel.getLayout() instanceof java.awt.BorderLayout
                            && panel.getComponentCount() > 0
                            && ((java.awt.BorderLayout) panel.getLayout())
                                    .getLayoutComponent(java.awt.BorderLayout.EAST) != null)
                    .map(c -> (javax.swing.JPanel) c)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no right-aligned actions row"));
            java.awt.FlowLayout layout = (java.awt.FlowLayout) all(actionsPanel).stream()
                    .filter(c -> c instanceof javax.swing.JPanel panel
                            && panel.getLayout() instanceof java.awt.FlowLayout)
                    .map(c -> (javax.swing.JPanel) c)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no buttons row"))
                    .getLayout();
            assertEquals(java.awt.FlowLayout.RIGHT, layout.getAlignment());
        });
    }

    private static List<java.awt.Dimension> blockPreferredSizes(FindingInlayCard card) {
        return all(card).stream()
                .filter(c -> c instanceof javax.swing.JTextArea)
                .map(c -> ((javax.swing.JTextArea) c).getPreferredSize())
                .toList();
    }
}
