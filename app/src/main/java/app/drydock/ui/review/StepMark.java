package app.drydock.ui.review;

/**
 * How the diff column paints one row while a tour is shown. {@code tagged}
 * marks the first row of a step's contiguous run (where the "step N" tag
 * goes); {@code bandStart} marks the first hidden row of a contiguous run of
 * hidden rows (where the {@link #bandLabel} goes). Added rows are hidden
 * while any step whose anchors take them in -- the current one or another --
 * has an open PREDICT as its first unsettled check: reading them anywhere
 * would answer it. {@code hiddenBy} is that step's number (0 when the row
 * shows); it can differ from {@code stepNumber} where anchors overlap.
 */
record StepMark(Strength strength, int stepNumber, boolean tagged, boolean hidden, boolean bandStart,
                int hiddenBy) {

    enum Strength { CURRENT, OTHER }

    /** The hatched band's words; a band waiting on another step's answer names that step. */
    String bandLabel() {
        return strength == Strength.CURRENT && hiddenBy == stepNumber
                ? "hidden until you answer"
                : "step " + hiddenBy + " — hidden until you answer";
    }
}
