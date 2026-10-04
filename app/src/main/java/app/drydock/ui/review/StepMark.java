package app.drydock.ui.review;

/**
 * How the diff column paints one row while a tour is shown. {@code tagged}
 * marks the first row of a step's contiguous run (where the "step N" tag
 * goes); {@code bandStart} marks the first hidden row of a contiguous run of
 * hidden rows (where the {@link #bandLabel} goes). Rows are hidden for the
 * current step and for any other step whose first unsettled check is an
 * open PREDICT: reading them anywhere would answer it.
 */
record StepMark(Strength strength, int stepNumber, boolean tagged, boolean hidden, boolean bandStart) {

    enum Strength { CURRENT, OTHER }

    /** The hatched band's words; another step's band says whose answer it waits for. */
    String bandLabel() {
        return strength == Strength.CURRENT
                ? "hidden until you answer"
                : "step " + stepNumber + " — hidden until you answer";
    }
}
