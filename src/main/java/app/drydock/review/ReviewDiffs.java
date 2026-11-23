package app.drydock.review;

import app.drydock.git.UnifiedDiff;

import java.util.Objects;

/**
 * What a review leaves out of its diff: untracked IDE project files
 * ({@code .idea/…} and {@code .iml}), and only those -- a tracked, modified
 * {@code .idea/…} file is review material and stays. The workbench hides
 * them from its rows, and the agent's review tools read the same diff
 * without them, so the reviewer and the agent see the same files. Removing
 * a file changes no other file's hunk ids or digests: both are per file.
 */
public final class ReviewDiffs {

    private ReviewDiffs() {
    }

    /** Whether {@code file} is an untracked IDE project file (pure). */
    public static boolean ideNoise(UnifiedDiff.FileDiff file) {
        Objects.requireNonNull(file, "file");
        return file.untracked() && (file.path().startsWith(".idea/") || file.path().endsWith(".iml"));
    }

    /** {@code diff} without its untracked IDE project files (pure); the same instance when it has none. */
    public static UnifiedDiff withoutIdeNoise(UnifiedDiff diff) {
        Objects.requireNonNull(diff, "diff");
        if (diff.files().stream().noneMatch(ReviewDiffs::ideNoise)) {
            return diff;
        }
        return new UnifiedDiff(diff.files().stream().filter(file -> !ideNoise(file)).toList());
    }
}
