package app.drydock.ui.review;

import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourStep;

import java.util.ArrayList;
import java.util.List;

/**
 * Which keys the tour's hint strip offers: only the ones that do something
 * now. A reader who is predicting needs the digits and the way out, not the
 * approve key; one who has settled a step needs undo and next. Showing every
 * binding at all times is what the full overlay is for -- and the reason a
 * reader still got lost on a screen that already had one.
 *
 * <p>Pure, so what the strip says is tested without a scene. Every key named
 * here is bound by {@link TourController#handleShortcut} (or the step panel's
 * own text area, for {@code ⌘⏎}), and {@code TourKeyHintsTest} holds the
 * overlay to the same set.</p>
 */
final class TourKeyHints {

    /** What the reader is doing on the current step. */
    enum Context {
        /** A choice check (PREDICT or TRACE) is open. */
        ANSWERING_CHOICE,
        /** A RISK check is open: a free-text answer to send. */
        ANSWERING_RISK,
        /** No check is waiting on an answer: reading, then deciding. */
        READING,
        /** The step has a decision (approved or changes requested). */
        DECIDED
    }

    /** One key and what it does; {@code key} is the keycap text. */
    record Hint(String key, String label) { }

    /** The two the strip always ends with: how to hide itself, and the full list. */
    static final Hint HIDE = new Hint("h", "hide");
    static final Hint ALL = new Hint("?", "all shortcuts");

    /** The most digits that jump to a claim: the board binds 1-4, which also answer a check. */
    static final int MAX_DIGIT_CLAIMS = 4;

    private TourKeyHints() {
    }

    /** What {@code step} asks of the reader right now, given its {@code progress}. */
    static Context contextOf(TourStep step, StepProgress progress) {
        if (progress.decision() != StepProgress.Decision.NONE) {
            return Context.DECIDED;
        }
        for (TourCheck check : step.checks()) {
            CheckProgress p = progress.check(check.id());
            if (p.settled()) {
                continue;
            }
            if (p.status() != CheckProgress.Status.OPEN) {
                return Context.READING;
            }
            return check.version(p.attempt()).kind() == TourCheck.Kind.RISK
                    ? Context.ANSWERING_RISK
                    : Context.ANSWERING_CHOICE;
        }
        return Context.READING;
    }

    /**
     * The hints for {@code context}, most useful first -- a narrow window clips
     * from the end. {@code claims} is how many ranges of the step carry a claim
     * (0 for a step without notes, whose ranges are just ranges).
     */
    static List<Hint> hintsFor(Context context, int claims) {
        List<Hint> hints = new ArrayList<>();
        switch (context) {
            case ANSWERING_CHOICE -> hints.add(new Hint("1–4", "answer the check"));
            case ANSWERING_RISK -> hints.add(new Hint("⌘⏎", "send your answer"));
            case READING -> {
                hints.add(claims > 0
                        ? new Hint(". ,", "next / previous claim")
                        : new Hint(". ,", "next / previous range"));
                if (claims > 0) {
                    hints.add(new Hint(claims == 1 ? "1" : "1–" + Math.min(claims, MAX_DIGIT_CLAIMS),
                            "jump to a claim"));
                }
                hints.add(new Hint("a", "approve"));
                hints.add(new Hint("r", "request changes"));
            }
            case DECIDED -> hints.add(new Hint("u", "undo"));
        }
        hints.add(new Hint("n", "next unsettled step"));
        hints.add(new Hint("[ ]", "previous / next step"));
        return List.copyOf(hints);
    }
}
