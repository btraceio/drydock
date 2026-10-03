package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import app.drydock.git.UnifiedDiff.Line;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Hand-built diffs and tours for the tour package's tests. */
final class TourFixtures {

    static final String SCOPE = "rs_tour";

    private TourFixtures() {
    }

    static Line ctx(int oldLine, int newLine, String text) {
        return new Line(Line.Kind.CONTEXT, OptionalInt.of(oldLine), OptionalInt.of(newLine), text);
    }

    static Line add(int newLine, String text) {
        return new Line(Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(newLine), text);
    }

    static Line del(int oldLine, String text) {
        return new Line(Line.Kind.DEL, OptionalInt.of(oldLine), OptionalInt.empty(), text);
    }

    static UnifiedDiff.Hunk hunk(Line... lines) {
        return new UnifiedDiff.Hunk("@@ -1 +1 @@", List.of(lines));
    }

    static UnifiedDiff.FileDiff file(String path, UnifiedDiff.Hunk... hunks) {
        int insertions = 0;
        int deletions = 0;
        for (UnifiedDiff.Hunk hunk : hunks) {
            for (Line line : hunk.lines()) {
                if (line.kind() == Line.Kind.ADD) {
                    insertions++;
                } else if (line.kind() == Line.Kind.DEL) {
                    deletions++;
                }
            }
        }
        return new UnifiedDiff.FileDiff(path, "M", insertions, deletions, false, false, List.of(hunks));
    }

    static UnifiedDiff.FileDiff deletedFile(String path, String... removed) {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < removed.length; i++) {
            lines.add(del(i + 1, removed[i]));
        }
        return new UnifiedDiff.FileDiff(path, "D", 0, removed.length, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -1 +0,0 @@", lines)));
    }

    /**
     * Two files: {@code src/A.java} with two hunks (hunk 0 adds n3, hunk 1
     * replaces o20 with n21) and {@code src/B.java} with one deletion-only
     * hunk (removes o5..o6).
     */
    static UnifiedDiff twoFileDiff() {
        UnifiedDiff.FileDiff a = file("src/A.java",
                hunk(ctx(1, 1, "class A {"), ctx(2, 2, "  int x;"), add(3, "  int y;"), ctx(3, 4, "}")),
                hunk(ctx(19, 20, "void f() {"), del(20, "  old();"), add(21, "  next();"), ctx(21, 22, "}")));
        UnifiedDiff.FileDiff b = file("src/B.java",
                hunk(ctx(4, 4, "a"), del(5, "b"), del(6, "c"), ctx(7, 5, "d")));
        return new UnifiedDiff(List.of(a, b));
    }

    static TourCheck predict(String id) {
        TourCheck alternate = new TourCheck(id + "_alt", TourCheck.Kind.PREDICT, "Alternate?",
                List.of(choice("yes"), choice("no")), OptionalInt.of(1), "Because.", List.of());
        return new TourCheck(id, TourCheck.Kind.PREDICT, "What happens?",
                List.of(choice("it throws"), choice("it returns")), OptionalInt.of(1), "It returns early.",
                List.of(alternate));
    }

    static TourCheck risk(String id) {
        TourCheck alternate = new TourCheck(id + "_alt", TourCheck.Kind.RISK, "Name another risk.",
                List.of(), OptionalInt.empty(), "Concurrency.", List.of());
        return new TourCheck(id, TourCheck.Kind.RISK, "What input breaks this?",
                List.of(), OptionalInt.empty(), "An empty list.", List.of(alternate));
    }

    static TourCheck.Choice choice(String text) {
        return new TourCheck.Choice(text, Optional.empty());
    }

    static TourStep step(String id, List<TourAnchor> anchors, TourCheck... checks) {
        return new TourStep(id, "Step " + id, "Why " + id + " exists.", anchors, List.of(), List.of(checks));
    }

    /** A tour covering every changed row of {@link #twoFileDiff()} in two steps. */
    static ReviewTour coveringTour(UnifiedDiff diff) {
        return new ReviewTour(SCOPE, TourFingerprint.of(diff), List.of(
                step("s1", List.of(new TourAnchor("src/A.java", "n1", "n22")), predict("c1")),
                step("s2", List.of(new TourAnchor("src/B.java", "o5", "o6")), risk("c2"))));
    }
}
