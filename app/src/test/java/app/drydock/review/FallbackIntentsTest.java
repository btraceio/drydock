package app.drydock.review;

import app.drydock.git.UnifiedDiff;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grouping Review falls back to when no reviewer has proposed intents.
 *
 * <p>One intent per file was the old fallback, and on a 45-file diff it
 * produced 45 cards whose titles all clipped to the same
 * {@code app/src/main/java/app/dry…} prefix, all tagged {@code change}, all
 * carrying an identical rationale and an identical flat heat bar. A rail that
 * cannot tell its own entries apart is not a rail, so these tests are about
 * the three things that make a card distinguishable: where it is, what kind
 * of change it is, and how much of it there is.</p>
 */
class FallbackIntentsTest {

    @Test
    void filesInTheSameDirectoryBecomeOneIntent() {
        UnifiedDiff diff = diff(
                file("app/src/main/java/app/drydock/git/DiffService.java", 40, 10),
                file("app/src/main/java/app/drydock/git/GitService.java", 20, 5));

        List<ReviewIntent> intents = FallbackIntents.group(diff);

        assertEquals(1, intents.size(), "two files in one directory are one intent");
        assertEquals(2, intents.get(0).fileCount());
    }

    @Test
    void separateDirectoriesStaySeparateIntents() {
        UnifiedDiff diff = diff(
                file("app/src/main/java/app/drydock/git/DiffService.java", 40, 10),
                file("app/src/main/java/app/drydock/ui/review/ReviewDiffColumn.java", 30, 8));

        assertEquals(2, FallbackIntents.group(diff).size());
    }

    /**
     * The whole point of the rail: two cards must not read the same. The old
     * fallback failed this on every pair of files in one package.
     */
    @Test
    void everyIntentTitleIsDistinct() {
        UnifiedDiff diff = diff(
                file("app/src/main/java/app/drydock/git/DiffService.java", 40, 10),
                file("app/src/main/java/app/drydock/ui/review/ReviewDiffColumn.java", 30, 8),
                file("app/src/test/java/app/drydock/git/DiffServiceTest.java", 60, 0),
                file("build.gradle.kts", 4, 1));

        List<String> titles = FallbackIntents.group(diff).stream().map(ReviewIntent::title).toList();

        assertEquals(titles.size(), titles.stream().distinct().count(), "titles: " + titles);
        assertTrue(titles.stream().noneMatch(String::isBlank));
    }

    @Test
    void testsConfigAndGeneratedFilesAreTaggedAsSuch() {
        UnifiedDiff diff = diff(
                file("app/src/test/java/app/drydock/git/DiffServiceTest.java", 60, 0),
                file("app/src/main/resources/config/app.yaml", 3, 1),
                file("web/package-lock.json", 900, 400),
                file("app/src/main/java/app/drydock/git/DiffService.java", 40, 10));

        List<ReviewIntent> intents = FallbackIntents.group(diff);

        assertEquals(ReviewIntent.Kind.TESTS, kindOf(intents, "DiffServiceTest.java"));
        assertEquals(ReviewIntent.Kind.CONFIG, kindOf(intents, "app.yaml"));
        assertEquals(ReviewIntent.Kind.GENERATED, kindOf(intents, "package-lock.json"));
        assertEquals(ReviewIntent.Kind.CHANGE, kindOf(intents, "DiffService.java"));
    }

    /** A test file and a source file in sibling trees must never share a card. */
    @Test
    void aKindNeverSharesAnIntentWithAnotherKind() {
        UnifiedDiff diff = diff(
                file("src/app/Thing.java", 10, 0),
                file("src/app/ThingTest.java", 10, 0));

        List<ReviewIntent> intents = FallbackIntents.group(diff);

        assertEquals(2, intents.size());
        assertNotEquals(intents.get(0).kind(), intents.get(1).kind());
    }

    /** The heat bar has to vary, or it is a decoration on every card. */
    @Test
    void riskFollowsChurn() {
        UnifiedDiff big = diff(file("src/app/Huge.java", 500, 200));
        UnifiedDiff small = diff(file("src/app/Tiny.java", 2, 1));

        assertEquals(ReviewIntent.Risk.HIGH, FallbackIntents.group(big).get(0).risk());
        assertEquals(ReviewIntent.Risk.LOW, FallbackIntents.group(small).get(0).risk());
    }

    /** Generated output is not risk, however large it is -- nobody reads it line by line. */
    @Test
    void generatedOutputIsNeverHighRisk() {
        UnifiedDiff diff = diff(file("web/package-lock.json", 5000, 4000));

        assertEquals(ReviewIntent.Risk.NONE, FallbackIntents.group(diff).get(0).risk());
    }

    /**
     * Selecting an intent has to be able to find its code. Reviewer groupings
     * address hunks by id, and the fallback now does too -- one mechanism, so
     * the column's filter and the scroll-into-view cannot disagree about what
     * an intent contains.
     */
    @Test
    void everyIntentNamesTheHunksItContains() {
        UnifiedDiff diff = diff(
                file("src/app/A.java", 10, 0, 2),
                file("src/app/B.java", 10, 0, 1));

        ReviewIntent intent = FallbackIntents.group(diff).get(0);

        assertEquals(3, intent.hunkIds().size());
        assertTrue(intent.hunkIds().contains(ReviewIntent.hunkId("src/app/A.java", 0)));
        assertTrue(intent.hunkIds().contains(ReviewIntent.hunkId("src/app/A.java", 1)));
        assertTrue(intent.hunkIds().contains(ReviewIntent.hunkId("src/app/B.java", 0)));
        assertTrue(intent.anchor().isPresent());
    }

    @Test
    void intentsAreNumberedDenselyFromOne() {
        UnifiedDiff diff = diff(
                file("src/app/A.java", 10, 0),
                file("src/lib/B.java", 10, 0),
                file("src/web/C.java", 10, 0));

        List<Integer> numbers = FallbackIntents.group(diff).stream().map(ReviewIntent::number).toList();

        assertEquals(List.of(1, 2, 3), numbers);
    }

    /** Source before tests before config before generated: reading order, not diff order. */
    @Test
    void productionCodeIsReadBeforeItsSupportingFiles() {
        UnifiedDiff diff = diff(
                file("web/package-lock.json", 900, 400),
                file("app/src/test/java/ThingTest.java", 60, 0),
                file("app/src/main/java/Thing.java", 40, 10));

        List<ReviewIntent.Kind> kinds = FallbackIntents.group(diff).stream()
                .map(ReviewIntent::kind).toList();

        assertEquals(List.of(ReviewIntent.Kind.CHANGE, ReviewIntent.Kind.TESTS,
                ReviewIntent.Kind.GENERATED), kinds);
    }

    @Test
    void anEmptyDiffHasNoIntents() {
        assertTrue(FallbackIntents.group(new UnifiedDiff(List.of())).isEmpty());
    }

    /** A file at the repository root has no parent directory to group under. */
    @Test
    void rootLevelFilesGroupWithoutCrashing() {
        UnifiedDiff diff = diff(file("README.md", 3, 1), file("build.gradle.kts", 2, 0));

        List<ReviewIntent> intents = FallbackIntents.group(diff);

        assertFalse(intents.isEmpty());
        assertTrue(intents.stream().noneMatch(intent -> intent.title().isBlank()));
    }

    private static ReviewIntent.Kind kindOf(List<ReviewIntent> intents, String fileSuffix) {
        return intents.stream()
                .filter(intent -> intent.hunkIds().stream()
                        .anyMatch(id -> id.contains(fileSuffix)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no intent contains " + fileSuffix
                        + "; intents were " + intents.stream().map(ReviewIntent::title).toList()))
                .kind();
    }

    private static UnifiedDiff diff(UnifiedDiff.FileDiff... files) {
        return new UnifiedDiff(List.of(files));
    }

    private static UnifiedDiff.FileDiff file(String path, int insertions, int deletions) {
        return file(path, insertions, deletions, 1);
    }

    private static UnifiedDiff.FileDiff file(String path, int insertions, int deletions, int hunks) {
        List<UnifiedDiff.Hunk> hunkList = new java.util.ArrayList<>();
        for (int i = 0; i < hunks; i++) {
            hunkList.add(new UnifiedDiff.Hunk("@@ -1 +1 @@", List.of(
                    new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                            OptionalInt.of(i + 1), "line"))));
        }
        return new UnifiedDiff.FileDiff(path, "M", insertions, deletions, false, false,
                List.copyOf(hunkList));
    }
}
