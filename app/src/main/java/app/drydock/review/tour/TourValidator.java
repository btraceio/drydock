package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Triage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Checks an agent's tour against the review diff before anything is stored.
 *
 * <p>Reports every problem, not the first, as full sentences naming the
 * step, check or hunk: the messages go back to the agent verbatim so it can
 * fix them in one resubmission.</p>
 *
 * <p>Coverage is checked per changed row, not per hunk: a step covering
 * three changed lines of a fifty-line hunk must not make the other
 * forty-seven count as reviewed.</p>
 */
public final class TourValidator {

    public static final int MAX_STEPS = 40;
    public static final int MAX_CHECKS_PER_STEP = 6;
    public static final int MAX_NARRATIVE = 1000;
    public static final int MAX_TITLE = 120;
    /** One claim about one anchored range; short enough to read beside the code it is about. */
    public static final int MAX_ANCHOR_NOTE = 400;
    public static final int MAX_PROMPT = 1000;
    public static final int MAX_CHOICE = 300;
    public static final int MIN_CHOICES = 2;
    public static final int MAX_CHOICES = 4;

    private TourValidator() {
    }

    public static List<String> validate(ReviewTour tour, UnifiedDiff reviewDiff) {
        return validate(tour, reviewDiff, List.of());
    }

    /** As {@link #validate(ReviewTour, UnifiedDiff)}, plus the rules for findings withheld behind its checks. */
    public static List<String> validate(ReviewTour tour, UnifiedDiff reviewDiff, List<ReviewAnnotation> findings) {
        List<String> errors = new ArrayList<>();
        AnchorIndex index = AnchorIndex.of(reviewDiff);
        if (tour.steps().isEmpty()) {
            errors.add("a tour needs at least one step");
        }
        if (tour.steps().size() > MAX_STEPS) {
            errors.add("a tour has at most " + MAX_STEPS + " steps; this one has " + tour.steps().size());
        }
        Set<String> stepIds = new HashSet<>();
        Set<String> checkIds = new HashSet<>();
        for (TourStep step : tour.steps()) {
            if (!stepIds.add(step.id())) {
                errors.add("step id " + step.id() + " is used twice");
            }
            validateStep(step, index, checkIds, errors);
        }
        validateCoverage(tour, index, errors);
        errors.addAll(withheldFindingErrors(tour, index, findings));
        return List.copyOf(errors);
    }

    /**
     * Every finding withheld behind a check (and not dismissed) names a check
     * of {@code tour} on a step whose anchors contain the finding's line:
     * the check is the moment the finding is revealed, so it must be asked
     * where the finding is.
     */
    public static List<String> withheldFindingErrors(ReviewTour tour, AnchorIndex index,
                                                     List<ReviewAnnotation> findings) {
        List<String> errors = new ArrayList<>();
        for (ReviewAnnotation finding : findings) {
            if (finding.withheldBy().isEmpty() || finding.triage() == Triage.DISMISSED) {
                continue;
            }
            String checkId = finding.withheldBy().get();
            String where = "finding " + finding.id() + " is withheld by check " + checkId;
            Optional<TourStep> step = tour.stepOfCheck(checkId);
            if (step.isEmpty()) {
                errors.add(where + ", which is not in the tour");
                continue;
            }
            TourCheck owner = step.get().check(checkId).orElseThrow();
            TourCheck named = owner.id().equals(checkId)
                    ? owner
                    : owner.alternates().stream().filter(alt -> alt.id().equals(checkId)).findFirst().orElse(owner);
            if (named.kind() == TourCheck.Kind.TRACE) {
                errors.add(where + ", which is a trace check; only predict or risk checks withhold findings");
            }
            boolean covered = step.get().anchors().stream()
                    .anyMatch(anchor -> index.contains(anchor, finding.file(), finding.startKey()));
            if (!covered) {
                errors.add(where + ", which is not on a step covering " + finding.file() + " "
                        + finding.startKey());
            }
        }
        return errors;
    }

    private static void validateStep(TourStep step, AnchorIndex index, Set<String> checkIds, List<String> errors) {
        String where = "step " + step.id() + ": ";
        if (step.title().isBlank()) {
            errors.add(where + "needs a title");
        } else if (step.title().length() > MAX_TITLE) {
            errors.add(where + "title is longer than " + MAX_TITLE + " characters");
        }
        if (step.narrative().isBlank()) {
            errors.add(where + "needs a narrative");
        } else if (step.narrative().length() > MAX_NARRATIVE) {
            errors.add(where + "narrative is longer than " + MAX_NARRATIVE + " characters");
        }
        if (step.anchors().isEmpty()) {
            errors.add(where + "needs at least one anchor");
        }
        for (TourAnchor anchor : step.anchors()) {
            if (!index.resolves(anchor)) {
                errors.add(where + "anchor " + anchor.file() + " " + anchor.startKey() + ".." + anchor.endKey()
                        + " is not a range of rows of the diff");
            }
        }
        if (step.checks().isEmpty()) {
            errors.add(where + "needs at least one check");
        }
        if (step.checks().size() > MAX_CHECKS_PER_STEP) {
            errors.add(where + "has more than " + MAX_CHECKS_PER_STEP + " checks");
        }
        for (TourCheck check : step.checks()) {
            validateCheck(check, true, checkIds, errors);
        }
        for (ImpactNote note : step.impactNotes()) {
            if (note.file().isBlank()) {
                errors.add(where + "an impact note needs a file");
            } else if (note.line() < 1) {
                errors.add(where + "impact note on " + note.file() + " needs a line of 1 or more");
            }
            if (note.text().isBlank()) {
                errors.add(where + "impact note on " + note.file() + " needs text");
            }
        }
    }

    private static void validateCheck(TourCheck check, boolean topLevel, Set<String> checkIds, List<String> errors) {
        String where = "check " + check.id() + ": ";
        if (!checkIds.add(check.id())) {
            errors.add("check id " + check.id() + " is used twice");
        }
        if (check.prompt().isBlank()) {
            errors.add(where + "needs a prompt");
        } else if (check.prompt().length() > MAX_PROMPT) {
            errors.add(where + "prompt is longer than " + MAX_PROMPT + " characters");
        }
        if (check.kind() == TourCheck.Kind.RISK) {
            if (!check.choices().isEmpty()) {
                errors.add(where + "a risk check takes no choices");
            }
            if (check.answer().isPresent()) {
                errors.add(where + "a risk check takes no answer");
            }
        } else {
            int count = check.choices().size();
            if (count < MIN_CHOICES || count > MAX_CHOICES) {
                errors.add(where + "needs " + MIN_CHOICES + " to " + MAX_CHOICES + " choices; it has " + count);
            }
            for (TourCheck.Choice choice : check.choices()) {
                if (choice.text().isBlank() || choice.text().length() > MAX_CHOICE) {
                    errors.add(where + "every choice needs text of at most " + MAX_CHOICE + " characters");
                    break;
                }
            }
            if (check.answer().isEmpty()) {
                errors.add(where + "needs an answer (0-based index into its choices)");
            } else if (check.answer().getAsInt() < 0 || check.answer().getAsInt() >= count) {
                errors.add(where + "answer " + check.answer().getAsInt() + " is not one of its " + count
                        + " choices (0-based)");
            }
        }
        if (topLevel && check.alternates().isEmpty()) {
            errors.add(where + "needs at least one alternate, offered after a wrong answer");
        }
        if (!topLevel && !check.alternates().isEmpty()) {
            errors.add(where + "an alternate cannot have alternates of its own");
        }
        for (TourCheck alternate : check.alternates()) {
            validateCheck(alternate, false, checkIds, errors);
        }
    }

    private static void validateCoverage(ReviewTour tour, AnchorIndex index, List<String> errors) {
        Map<String, List<String>> uncoveredByHunk = new LinkedHashMap<>();
        for (AnchorIndex.ChangedRow row : index.changedRows()) {
            boolean covered = tour.steps().stream()
                    .flatMap(step -> step.anchors().stream())
                    .anyMatch(anchor -> index.contains(anchor, row.file(), row.lineKey()));
            if (!covered) {
                String hunkId = new AnchorIndex.HunkRef(row.file(), row.hunkIndex(), "").hunkId();
                uncoveredByHunk.computeIfAbsent(hunkId, id -> new ArrayList<>()).add(row.lineKey());
            }
        }
        uncoveredByHunk.forEach((hunkId, keys) -> errors.add("hunk " + hunkId + ": rows " + keys.getFirst()
                + ".." + keys.getLast() + " are in no step"));
    }
}
