package app.drydock.ui.review;

/**
 * How the diff column paints one row while a tour is shown. {@code tagged}
 * marks the first row of a step's contiguous run (where the "step N" tag
 * goes); {@code bandStart} marks the first hidden row of a contiguous run of
 * hidden rows (where the "hidden until you answer" label goes).
 */
record StepMark(Strength strength, int stepNumber, boolean tagged, boolean hidden, boolean bandStart) {

    enum Strength { CURRENT, OTHER }
}
