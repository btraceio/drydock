package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * What an approval is pinned to (spec §9.2). A digest that ignores context
 * lets an approval stand over code whose surroundings moved; a digest that
 * covers the whole file re-reviews hunks nobody touched. These tests pin
 * both edges of that window.
 */
class HunkDigestTest {

    private static UnifiedDiff.Line ctx(int line, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT,
                OptionalInt.of(line), OptionalInt.of(line), text);
    }

    private static UnifiedDiff.Line add(int line, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                OptionalInt.empty(), OptionalInt.of(line), text);
    }

    private static UnifiedDiff.Hunk hunk(List<UnifiedDiff.Line> lines) {
        return new UnifiedDiff.Hunk("@@ -1,3 +1,4 @@", lines);
    }

    @Test
    void theSameContentInTheSamePathDigestsIdentically() {
        UnifiedDiff.Hunk left = hunk(List.of(ctx(1, "int a;"), add(2, "int b;")));
        UnifiedDiff.Hunk right = hunk(List.of(ctx(1, "int a;"), add(2, "int b;")));

        assertEquals(HunkDigest.of("src/a.c", left), HunkDigest.of("src/a.c", right));
    }

    /** A hunk that only moved is the same code, and stays approved. */
    @Test
    void movingAHunkWithoutChangingItKeepsTheDigest() {
        UnifiedDiff.Hunk before = hunk(List.of(ctx(1, "int a;"), add(2, "int b;")));
        UnifiedDiff.Hunk after = hunk(List.of(ctx(41, "int a;"), add(42, "int b;")));

        assertEquals(HunkDigest.of("src/a.c", before), HunkDigest.of("src/a.c", after));
    }

    /**
     * The reason context is in the digest: a hunk means what it means in
     * place, so an edit to the line above it must unsettle the approval even
     * though the changed lines are byte-identical.
     */
    @Test
    void changingOnlyAContextLineChangesTheDigest() {
        UnifiedDiff.Hunk before = hunk(List.of(ctx(1, "int a;"), add(2, "int b;")));
        UnifiedDiff.Hunk after = hunk(List.of(ctx(1, "long a;"), add(2, "int b;")));

        assertNotEquals(HunkDigest.of("src/a.c", before), HunkDigest.of("src/a.c", after));
    }

    @Test
    void changingAChangedLineChangesTheDigest() {
        UnifiedDiff.Hunk before = hunk(List.of(ctx(1, "int a;"), add(2, "int b;")));
        UnifiedDiff.Hunk after = hunk(List.of(ctx(1, "int a;"), add(2, "int c;")));

        assertNotEquals(HunkDigest.of("src/a.c", before), HunkDigest.of("src/a.c", after));
    }

    /** Identical hunks in two files are two different things to approve. */
    @Test
    void thePathIsPartOfTheIdentity() {
        UnifiedDiff.Hunk both = hunk(List.of(ctx(1, "int a;"), add(2, "int b;")));

        assertNotEquals(HunkDigest.of("src/a.c", both), HunkDigest.of("src/b.c", both));
    }

    /** The line's KIND matters: an added line and a deleted one are not the same review. */
    @Test
    void addAndDeleteOfTheSameTextDigestDifferently() {
        UnifiedDiff.Hunk added = hunk(List.of(add(1, "int b;")));
        UnifiedDiff.Hunk deleted = hunk(List.of(new UnifiedDiff.Line(
                UnifiedDiff.Line.Kind.DEL, OptionalInt.of(1), OptionalInt.empty(), "int b;")));

        assertNotEquals(HunkDigest.of("src/a.c", added), HunkDigest.of("src/a.c", deleted));
    }

    @Test
    void theDigestIsLowercaseHexOfFixedWidth() {
        String digest = HunkDigest.of("src/a.c", hunk(List.of(add(1, "x"))));

        assertEquals(64, digest.length());
        assertEquals(digest.toLowerCase(java.util.Locale.ROOT), digest);
    }
}
