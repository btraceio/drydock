package app.drydock.ui.review;

import java.util.List;
import java.util.Optional;

/**
 * How the diff column paints one row while a tour is shown. {@code tagged}
 * marks the first row of a step's contiguous run (where the "step N" tag
 * goes); {@code bandStart} marks the first hidden row of a contiguous run of
 * hidden rows (where the {@link #bandLabel} goes). Added rows are hidden
 * while any step whose anchors take them in -- the current one or another --
 * has an open PREDICT as its first unsettled check: reading them anywhere
 * would answer it. {@code hiddenBy} is that step's number (0 when the row
 * shows); it can differ from {@code stepNumber} where anchors overlap.
 *
 * <p>{@code claim} is the current step's anchored range this row belongs to,
 * when the step makes claims (see {@link Claim}); {@code callouts} are the
 * claims whose range ENDS on this row, drawn directly under it. Both are
 * empty for any other step's rows and for a step that makes no claims.</p>
 */
record StepMark(Strength strength, int stepNumber, boolean tagged, boolean hidden, boolean bandStart,
                int hiddenBy, Optional<Claim> claim, List<Callout> callouts) {

    enum Strength { CURRENT, OTHER }

    StepMark {
        callouts = List.copyOf(callouts);
    }

    /** A mark that belongs to no claim. */
    StepMark(Strength strength, int stepNumber, boolean tagged, boolean hidden, boolean bandStart,
             int hiddenBy) {
        this(strength, stepNumber, tagged, hidden, bandStart, hiddenBy, Optional.empty(), List.of());
    }

    /**
     * The claim a row's range supports. {@code index} is the anchor's position
     * in the step ({@code .} / {@code ,} walk the same index), {@code number}
     * is {@code index + 1} as the reader sees it. {@code first} marks the
     * range's first row, where the numbered badge goes; {@code active} is the
     * claim the reader is on, which the rest dim around.
     */
    record Claim(int index, int number, boolean active, boolean first) { }

    /** One claim's words, shown under the last row of its range. */
    record Callout(int index, int number, boolean active, String text) { }

    /** The hatched band's words; a band waiting on another step's answer names that step. */
    String bandLabel() {
        return strength == Strength.CURRENT && hiddenBy == stepNumber
                ? "hidden until you answer"
                : "step " + hiddenBy + " — hidden until you answer";
    }
}
