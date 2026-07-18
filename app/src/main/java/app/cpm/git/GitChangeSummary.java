package app.cpm.git;

import java.util.List;
import java.util.Objects;

/**
 * What a worktree branch changes relative to its base branch (Finish-panel
 * change summary): how many commits it is ahead, and the per-file diff
 * stat of {@code git diff base...HEAD}.
 */
public record GitChangeSummary(int commitsAhead, List<ChangedFile> files) {

    /** One changed file: kind is git's status letter (M/A/D/R/...). */
    public record ChangedFile(String kind, String path, int insertions, int deletions) {
        public ChangedFile {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(path, "path");
        }
    }

    public GitChangeSummary {
        if (commitsAhead < 0) {
            throw new IllegalArgumentException("commitsAhead must be non-negative: " + commitsAhead);
        }
        files = List.copyOf(Objects.requireNonNull(files, "files"));
    }

    public int totalInsertions() {
        return files.stream().mapToInt(ChangedFile::insertions).sum();
    }

    public int totalDeletions() {
        return files.stream().mapToInt(ChangedFile::deletions).sum();
    }
}
