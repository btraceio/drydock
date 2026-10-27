package app.drydock.intellij.review;

import app.drydock.review.tour.ImpactNote;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourDiagram;
import app.drydock.review.tour.TourStep;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Tour tab's text and link contract, headless. */
class TourViewTest {

    private static final TourAnchor FIRST = new TourAnchor("src/A.java", "n10", "n14", "Caches the <key>.");
    private static final TourAnchor SECOND = new TourAnchor("src/B.java", "o3", "o3");
    private static final TourCheck PREDICT = new TourCheck("c1", TourCheck.Kind.PREDICT,
            "What does get return on a miss?", List.of(choice("null"), choice("an empty Optional")),
            OptionalInt.of(1), "The cache never returns null.", List.of());
    private static final TourCheck RISK = new TourCheck("c2", TourCheck.Kind.RISK,
            "What breaks if two threads miss at once?", List.of(), OptionalInt.empty(), "", List.of());
    private static final TourStep STEP = new TourStep("s1", "The cache & its key",
            "Lead point.\n\nSecond paragraph\nwith a break.", List.of(FIRST, SECOND),
            List.of(new ImpactNote("src/C.java", 42, "Calls get.")), List.of(PREDICT, RISK),
            Optional.of(new TourDiagram("Flow", List.of("a -> b", "b -> c"))));

    private static TourCheck.Choice choice(String text) {
        return new TourCheck.Choice(text, Optional.empty());
    }

    @Test
    void anAnchorLinkResolvesToThatAnchorOfTheStep() {
        assertEquals(Optional.of(new TourView.AnchorLink(SECOND)), TourView.resolve(STEP, "anchor:1"));
    }

    @Test
    void aCheckLinkResolvesToThatChecksId() {
        assertEquals(Optional.of(new TourView.RevealLink("c2")), TourView.resolve(STEP, "check:1"));
    }

    @Test
    void anythingTheViewDidNotWriteResolvesToNothing() {
        for (String href : new String[] {null, "anchor:2", "anchor:-1", "anchor:x", "check:9",
                "https://example.com", "anchor:"}) {
            assertEquals(Optional.empty(), TourView.resolve(STEP, href), String.valueOf(href));
        }
    }

    @Test
    void theStepTextLinksEveryAnchorAndEscapesAgentText() {
        String html = TourView.stepHtml(2, 5, STEP, false, Set.of());
        assertTrue(html.contains("Step 2 of 5: The cache &amp; its key"), html);
        assertTrue(html.contains("<a href=\"anchor:0\">src/A.java L10–L14</a>"), html);
        assertTrue(html.contains("<a href=\"anchor:1\">src/B.java old L3</a>"), html);
        assertTrue(html.contains("Caches the &lt;key&gt;."), html);
        assertTrue(html.contains("<p>Second paragraph<br>with a break.</p>"), html);
        assertTrue(html.contains("src/C.java:42"), html);
        assertTrue(html.contains("a -&gt; b\nb -&gt; c"), "every diagram stage is shown: " + html);
        assertFalse(html.contains(TourView.escape(TourView.STALE_NOTICE)), html);
    }

    @Test
    void aCheckAnswerStaysHiddenUntilRevealed() {
        String hidden = TourView.stepHtml(1, 1, STEP, false, Set.of());
        assertTrue(hidden.contains("<a href=\"check:0\">Show answer</a>"), hidden);
        assertFalse(hidden.contains("an empty Optional</p>"), hidden);
        assertFalse(hidden.contains("The cache never returns null."), hidden);

        String shown = TourView.stepHtml(1, 1, STEP, false, Set.of("c1"));
        assertTrue(shown.contains("<b>Answer:</b> 2. an empty Optional"), shown);
        assertTrue(shown.contains("The cache never returns null."), shown);
        assertFalse(shown.contains("check:0"), shown);
    }

    @Test
    void aRiskCheckOffersNoAnswerToReveal() {
        String html = TourView.stepHtml(1, 1, STEP, false, Set.of("c2"));
        assertFalse(html.contains("check:1"), html);
        assertTrue(html.contains("No model answer"), html);
    }

    @Test
    void aStaleTourSaysSo() {
        assertTrue(TourView.stepHtml(1, 1, STEP, true, Set.of()).contains(TourView.escape(TourView.STALE_NOTICE)));
    }

    @Test
    void anUnrecognizedLineKeyIsShownAsWritten() {
        assertEquals("x9–L2", TourView.range(new TourAnchor("f", "x9", "n2")));
    }
}
