package app.drydock.ui.review;

import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourStep;
import app.drydock.ui.ShortcutsOverlay;
import app.drydock.ui.review.TourKeyHints.Context;
import app.drydock.ui.review.TourKeyHints.Hint;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TourKeyHintsTest {

    private static TourCheck choice(String id, TourCheck.Kind kind) {
        TourCheck alternate = new TourCheck(id + "_alt", kind, "again?",
                List.of(new TourCheck.Choice("x", Optional.empty()), new TourCheck.Choice("y", Optional.empty())),
                OptionalInt.of(0), "e", List.of());
        return new TourCheck(id, kind, "p",
                List.of(new TourCheck.Choice("x", Optional.empty()), new TourCheck.Choice("y", Optional.empty())),
                OptionalInt.of(0), "e", List.of(alternate));
    }

    private static TourCheck risk(String id) {
        return new TourCheck(id, TourCheck.Kind.RISK, "what could break?", List.of(), OptionalInt.empty(), "e",
                List.of(new TourCheck("alt", TourCheck.Kind.RISK, "and?", List.of(), OptionalInt.empty(), "e",
                        List.of())));
    }

    private static TourStep step(TourCheck... checks) {
        return new TourStep("s1", "One", "n", List.of(new TourAnchor("A.java", "n1", "n2")), List.of(),
                List.of(checks));
    }

    private static StepProgress progress(StepProgress.Decision decision, CheckProgress... checks) {
        java.util.Map<String, CheckProgress> byId = new java.util.LinkedHashMap<>();
        for (CheckProgress check : checks) {
            byId.put(check.checkId(), check);
        }
        return new StepProgress("s1", List.of(), byId, decision, Optional.empty(), false);
    }

    private static CheckProgress status(String id, int attempt, CheckProgress.Status status) {
        return new CheckProgress(id, attempt, status, Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    void anOpenChoiceCheckIsAnsweredWithTheDigits() {
        TourStep step = step(choice("c1", TourCheck.Kind.PREDICT));

        assertEquals(Context.ANSWERING_CHOICE,
                TourKeyHints.contextOf(step, progress(StepProgress.Decision.NONE, CheckProgress.fresh("c1"))));
        assertEquals(Context.ANSWERING_CHOICE, TourKeyHints.contextOf(
                step(choice("t1", TourCheck.Kind.TRACE)),
                progress(StepProgress.Decision.NONE, CheckProgress.fresh("t1"))));
    }

    @Test
    void anOpenRiskCheckIsSentWithCommandEnter() {
        assertEquals(Context.ANSWERING_RISK, TourKeyHints.contextOf(step(risk("r1")),
                progress(StepProgress.Decision.NONE, CheckProgress.fresh("r1"))));
        assertEquals("⌘⏎", TourKeyHints.hintsFor(Context.ANSWERING_RISK, 0).getFirst().key());
    }

    @Test
    void aSettledOrWaitingCheckLeavesTheReaderReading() {
        TourStep step = step(choice("c1", TourCheck.Kind.PREDICT));

        assertEquals(Context.READING, TourKeyHints.contextOf(step,
                progress(StepProgress.Decision.NONE, status("c1", 0, CheckProgress.Status.PASSED))));
        assertEquals(Context.READING, TourKeyHints.contextOf(step,
                progress(StepProgress.Decision.NONE, status("c1", 1, CheckProgress.Status.EXHAUSTED))),
                "an exhausted check has nothing left to answer by digit");
        assertEquals(Context.READING, TourKeyHints.contextOf(step(risk("r1")),
                progress(StepProgress.Decision.NONE, status("r1", 0, CheckProgress.Status.AWAITING_AGENT))));
    }

    @Test
    void aDecisionWinsOverEverythingElse() {
        assertEquals(Context.DECIDED, TourKeyHints.contextOf(step(choice("c1", TourCheck.Kind.PREDICT)),
                progress(StepProgress.Decision.PASSED, CheckProgress.fresh("c1"))));
        assertEquals("u", TourKeyHints.hintsFor(Context.DECIDED, 3).getFirst().key());
    }

    @Test
    void readingOffersClaimKeysOnlyWhenTheStepMakesClaims() {
        List<String> without = TourKeyHints.hintsFor(Context.READING, 0).stream().map(Hint::label).toList();
        List<String> with = TourKeyHints.hintsFor(Context.READING, 3).stream().map(Hint::label).toList();

        assertTrue(without.contains("next / previous range"));
        assertFalse(without.contains("jump to a claim"));
        assertTrue(with.contains("next / previous claim"));
        assertTrue(with.contains("jump to a claim"));
        assertEquals("1–3", TourKeyHints.hintsFor(Context.READING, 3).get(1).key());
        assertEquals("1–4", TourKeyHints.hintsFor(Context.READING, 9).get(1).key(),
                "only the digits the board binds");
        assertEquals("1", TourKeyHints.hintsFor(Context.READING, 1).get(1).key());
    }

    @Test
    void approveAndRequestChangesAreOfferedOnlyWhileReading() {
        for (Context context : Context.values()) {
            Set<String> keys = new HashSet<>();
            TourKeyHints.hintsFor(context, 2).forEach(hint -> keys.add(hint.key()));
            assertEquals(context == Context.READING, keys.contains("a"), context.name());
            assertEquals(context == Context.READING, keys.contains("r"), context.name());
        }
    }

    /**
     * What the strip advertises must be real. Every single-character key it
     * can show, and the digit range, is one the overlay lists for Review --
     * and the overlay is already pinned to the bindings by
     * ShortcutsOverlayParityTest. {@code ⌘⏎} is bound by the step panel's own
     * text area and listed by the overlay too.
     */
    @Test
    void everyKeyTheStripCanShowIsListedByTheOverlay() {
        Set<String> overlay = new HashSet<>();
        for (String keycap : ShortcutsOverlay.reviewShortcutKeys()) {
            overlay.addAll(Arrays.asList(keycap.split(" / ")));
        }
        Set<String> shown = new HashSet<>();
        for (Context context : Context.values()) {
            for (int claims : new int[] {0, 1, 4}) {
                for (Hint hint : TourKeyHints.hintsFor(context, claims)) {
                    shown.addAll(expand(hint.key()));
                }
            }
        }
        shown.add(TourKeyHints.HIDE.key());
        shown.add(TourKeyHints.ALL.key());
        // ? is the app-wide overlay key, bound by DrydockApplication's scene filter, not the board.
        shown.remove("?");

        Set<String> unlisted = new HashSet<>(shown);
        unlisted.removeAll(overlay);
        assertEquals(Set.of(), unlisted, "the strip advertises keys the overlay does not list");
    }

    /** "1–3" is the digits 1, 2, 3; ". ," and "[ ]" are two keys each. */
    private static Set<String> expand(String key) {
        Set<String> keys = new HashSet<>();
        if (key.matches("\\d–\\d")) {
            for (char c = key.charAt(0); c <= key.charAt(2); c++) {
                keys.add(String.valueOf(c));
            }
        } else if (key.contains(" ")) {
            keys.addAll(Arrays.asList(key.split(" ")));
        } else {
            keys.add(key);
        }
        return keys;
    }
}
