package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ReviewDiffsTest {

    private static UnifiedDiff.FileDiff file(String path, boolean untracked) {
        return new UnifiedDiff.FileDiff(path, "M", 1, 0, false, untracked, List.of());
    }

    @Test
    void onlyUntrackedIdeProjectFilesAreLeftOut() {
        UnifiedDiff.FileDiff source = file("src/A.java", false);
        UnifiedDiff.FileDiff trackedIdea = file(".idea/vcs.xml", false);
        UnifiedDiff.FileDiff untrackedIdea = file(".idea/workspace.xml", true);
        UnifiedDiff.FileDiff untrackedIml = file("app/app.iml", true);
        UnifiedDiff.FileDiff untrackedSource = file("src/New.java", true);

        UnifiedDiff kept = ReviewDiffs.withoutIdeNoise(new UnifiedDiff(List.of(source, trackedIdea, untrackedIdea,
                untrackedIml, untrackedSource)));

        assertEquals(List.of(source, trackedIdea, untrackedSource), kept.files(),
                "a tracked .idea file is review material; an untracked source file too");
    }

    @Test
    void aDiffWithoutIdeNoiseIsReturnedAsItIs() {
        UnifiedDiff clean = new UnifiedDiff(List.of(file("src/A.java", false)));
        assertSame(clean, ReviewDiffs.withoutIdeNoise(clean));
    }
}
