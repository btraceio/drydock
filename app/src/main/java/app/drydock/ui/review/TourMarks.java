package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.tour.AnchorIndex;
import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourRecord;
import app.drydock.review.tour.TourStep;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The step marks for one rendered diff: which rows belong to the current
 * step, which changed rows belong to which other step, and which added rows
 * an unanswered PREDICT check hides -- the current step's and every other
 * step's alike, since the whole files show every step's rows and reading
 * one elsewhere would answer its check (spec §5). Computed once per render on the FX
 * thread; a tour is at most 40 steps over one diff, so this is cheap.
 */
final class TourMarks implements ReviewDiffColumn.StepMarkSource {

    private static final TourMarks NONE = new TourMarks(Map.of());

    private final Map<String, StepMark> byRow;

    private TourMarks(Map<String, StepMark> byRow) {
        this.byRow = byRow;
    }

    static TourMarks none() {
        return NONE;
    }

    static TourMarks of(TourRecord record, UnifiedDiff renderedDiff, String currentStepId) {
        AnchorIndex index = AnchorIndex.of(renderedDiff);
        Map<String, StepMark> marks = new HashMap<>();
        Optional<TourStep> current = record.tour().step(currentStepId);
        Set<String> pending = new HashSet<>();
        for (TourStep step : record.tour().steps()) {
            if (predictPending(step, record.progress(step.id()))) {
                pending.add(step.id());
            }
        }
        boolean hideAdded = current.map(step -> pending.contains(step.id())).orElse(false);
        for (UnifiedDiff.FileDiff file : renderedDiff.files()) {
            String previousOwner = null;
            boolean previousHidden = false;
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                for (UnifiedDiff.Line line : hunk.lines()) {
                    String key = line.lineKey();
                    boolean changed = line.kind() != UnifiedDiff.Line.Kind.CONTEXT;
                    StepMark mark = null;
                    String owner = null;
                    boolean hidden = false;
                    if (current.isPresent() && inStep(index, current.get(), file.path(), key)) {
                        owner = current.get().id();
                        hidden = hideAdded && line.kind() == UnifiedDiff.Line.Kind.ADD;
                        mark = new StepMark(StepMark.Strength.CURRENT, record.tour().number(owner),
                                !owner.equals(previousOwner), hidden,
                                hidden && (!previousHidden || !owner.equals(previousOwner)));
                    } else if (changed) {
                        for (TourStep step : record.tour().steps()) {
                            if (!step.id().equals(currentStepId) && inStep(index, step, file.path(), key)) {
                                owner = step.id();
                                hidden = pending.contains(owner) && line.kind() == UnifiedDiff.Line.Kind.ADD;
                                // A band starts afresh where another step's hidden
                                // rows begin, so each says whose answer it waits for.
                                mark = new StepMark(StepMark.Strength.OTHER, record.tour().number(owner),
                                        !owner.equals(previousOwner), hidden,
                                        hidden && (!previousHidden || !owner.equals(previousOwner)));
                                break;
                            }
                        }
                    }
                    if (mark != null) {
                        marks.put(file.path() + " " + key, mark);
                    }
                    previousOwner = owner;
                    previousHidden = hidden;
                }
            }
        }
        return new TourMarks(marks);
    }

    @Override
    public Optional<StepMark> markAt(String file, String lineKey) {
        return Optional.ofNullable(byRow.get(file + " " + lineKey));
    }

    private static boolean inStep(AnchorIndex index, TourStep step, String file, String key) {
        for (TourAnchor anchor : step.anchors()) {
            if (index.contains(anchor, file, key)) {
                return true;
            }
        }
        return false;
    }

    /** The first unsettled check of the step is a PREDICT still open. */
    private static boolean predictPending(TourStep step, StepProgress progress) {
        for (TourCheck check : step.checks()) {
            CheckProgress p = progress.check(check.id());
            if (p.settled()) {
                continue;
            }
            return check.version(p.attempt()).kind() == TourCheck.Kind.PREDICT
                    && p.status() == CheckProgress.Status.OPEN;
        }
        return false;
    }
}
