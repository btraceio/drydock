package app.drydock.intellij.review.spike;

import app.drydock.git.DiffScope;
import app.drydock.git.DiffService;
import app.drydock.git.GitExecutableNotFoundException;
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.UnifiedDiff;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.diff.fragments.DiffFragment;
import com.intellij.diff.fragments.LineFragment;
import com.intellij.diff.fragments.LineFragmentImpl;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * SPIKE -- throwaway harness for P2 live spikes 1 and 2
 * (docs/superpowers/specs/2026-10-22-intellij-integrated-review-design.md,
 * "P2 -- Review tab and diff"). Never wired to the review stores; the whole
 * package is deleted once its findings are recorded in the investigation.
 *
 * <p>Answers, live in the sandbox IDE, what the bytecode probe behind
 * {@code find-review-tab-public-api} / {@code hyp-git-diff-stays-authoritative}
 * could not:
 * <ul>
 *   <li>does a {@code changesViewContent} tab land, and in which tool window
 *       (spike 1);</li>
 *   <li>does {@code CUSTOM_DIFF_COMPUTER} fed with Drydock's own
 *       {@link UnifiedDiff} hunks render exactly the git hunks, with
 *       {@code SCROLL_TO_LINE} and {@code FORCE_READ_ONLY} honored, and do
 *       range highlighters drawn through a {@code DiffExtension} survive the
 *       line transfer in both viewer modes (spike 2).</li>
 * </ul>
 */
final class SpikeReviewSupport {

    private static final Logger LOG = Logger.getInstance(SpikeReviewSupport.class);

    static final String BASE_BRANCH = "main";
    /** Status/query scale: the spike runs plain, short git commands. */
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(10);

    private SpikeReviewSupport() {
    }

    /**
     * The sandbox IDE drops .idea/ and *.iml into the worktree when the
     * project opens; DiffService's working-tree pass reports them as
     * untracked changes. They are IDE noise, not review material.
     */
    static boolean isIdeNoise(String path) {
        return path.startsWith(".idea/") || path.endsWith(".iml");
    }

    /**
     * The project's base path as the worktree root. The sandbox project is
     * opened on the checkout directory, and a Drydock task window opens the
     * worktree itself (find-review-lives-in-owner-window).
     */
    static Path checkoutRoot(Project project) {
        String base = project.getBasePath();
        return base == null ? null : Path.of(base);
    }

    /**
     * Branch-scope diff with uncommitted work ({@code main...working tree}),
     * the scope the Review tab will show. Runs git on the calling thread --
     * the async changes tree builder invokes the tree-model factory off the
     * EDT, which is where the spike calls this.
     */
    static UnifiedDiff branchDiff(Project project) {
        Path root = checkoutRoot(project);
        if (root == null) {
            LOG.warn("SpikeReview: no content root");
            return null;
        }
        try {
            return new DiffService().diff(root, DiffScope.BRANCH_WORKING_TREE, BASE_BRANCH).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            LOG.warn("SpikeReview: git diff failed", e.getCause());
            return null;
        }
    }

    /**
     * Content of {@code path} at {@link #BASE_BRANCH}, via {@code git show}.
     * Added files have no base content: the caller passes {@code kind} and an
     * added file yields the empty string instead of a failing git call.
     */
    static String baseContent(Project project, String path, String kind) {
        if ("A".equals(kind)) {
            return "";
        }
        Path root = checkoutRoot(project);
        if (root == null) {
            return "";
        }
        try {
            Path git = new GitExecutableLocator().locate().orElse(Path.of("git"));
            // --end-of-options: a path that looks like an option must reach
            // git as a revision spec, never as a flag.
            ProcessResult result = ProcessRunner.run(
                    List.of(git.toString(), "-C", root.toString(), "show",
                            "--end-of-options", BASE_BRANCH + ":" + path),
                    root, GIT_TIMEOUT);
            if (result.exitCode() != 0) {
                String err = result.stderr().strip();
                LOG.warn("SpikeReview: git show " + path + " failed: "
                        + err.substring(0, Math.min(200, err.length())));
                return "";
            }
            return result.stdout();
        } catch (GitExecutableNotFoundException | IOException | InterruptedException e) {
            LOG.warn("SpikeReview: git show failed for " + path, e);
            return "";
        }
    }

    /**
     * Converts one file's git hunks into the fragment list
     * {@code CUSTOM_DIFF_COMPUTER} must return. Runs of +/- lines become a
     * single replace fragment bounded by the surrounding context lines, the
     * grouping git's unified format implies. Fragments are line-level;
     * inner-word highlighting is exercised separately by the DiffExtension's
     * range highlighters, which is the decoration route P3 would use anyway.
     */
    static List<LineFragment> fragmentsFor(UnifiedDiff.FileDiff file) {
        List<LineFragment> fragments = new ArrayList<>();
        int lastOld = -1; // 0-based last consumed old-side line
        int lastNew = -1;
        Integer delStart = null;
        Integer addStart = null;
        int delEnd = -1;
        int addEnd = -1;
        for (UnifiedDiff.Hunk hunk : file.hunks()) {
            for (UnifiedDiff.Line line : hunk.lines()) {
                switch (line.kind()) {
                    case CONTEXT -> {
                        flushRun(fragments, delStart, delEnd, addStart, addEnd, lastOld, lastNew);
                        delStart = null;
                        addStart = null;
                        lastOld = line.oldLine().orElse(lastOld + 2) - 1;
                        lastNew = line.newLine().orElse(lastNew + 2) - 1;
                    }
                    case DEL -> {
                        if (delStart == null) {
                            delStart = lastOld + 1;
                        }
                        delEnd = line.oldLine().orElse(delEnd + 2) - 1;
                        lastOld = delEnd;
                    }
                    case ADD -> {
                        if (addStart == null) {
                            addStart = lastNew + 1;
                        }
                        addEnd = line.newLine().orElse(addEnd + 2) - 1;
                        lastNew = addEnd;
                    }
                }
            }
        }
        flushRun(fragments, delStart, delEnd, addStart, addEnd, lastOld, lastNew);
        return fragments;
    }

    /**
     * Appends one replace fragment for the pending +/- run and returns the
     * (lastOld, lastNew) pair advanced past it: a deletion consumed old-side
     * lines only, an addition new-side ones, and a pure run leaves the other
     * side collapsed to the insertion point.
     */
    private static int[] flushRun(List<LineFragment> fragments, Integer delStart, int delEnd,
                                  Integer addStart, int addEnd, int lastOld, int lastNew) {
        if (delStart == null && addStart == null) {
            return new int[]{lastOld, lastNew};
        }
        int start1 = delStart != null ? delStart : lastOld + 1;
        int end1 = delStart != null ? delEnd + 1 : lastOld + 1;
        int start2 = addStart != null ? addStart : lastNew + 1;
        int end2 = addStart != null ? addEnd + 1 : lastNew + 1;
        if (delStart != null) {
            lastOld = delEnd;
        }
        if (addStart != null) {
            lastNew = addEnd;
        }
        // Inner bounds mirror the outer ranges, as the platform's own
        // convenience constructor does for line-level fragments.
        fragments.add(new LineFragmentImpl(start1, end1, start2, end2,
                start1, end1, start2, end2, List.<DiffFragment>of()));
        return new int[]{lastOld, lastNew};
    }

    /** First changed line of the file's first hunk, new side, 0-based. */
    static int firstChangedLine(UnifiedDiff.FileDiff file) {
        for (UnifiedDiff.Hunk hunk : file.hunks()) {
            for (UnifiedDiff.Line line : hunk.lines()) {
                if (line.kind() == UnifiedDiff.Line.Kind.ADD) {
                    return line.newLine().orElse(1) - 1;
                }
            }
        }
        return 0;
    }

    /** Current worktree text of {@code path}, read from disk. Off the EDT. */
    static String currentContent(Project project, String path) {
        Path root = checkoutRoot(project);
        if (root == null) {
            return null;
        }
        try {
            return java.nio.file.Files.readString(root.resolve(path));
        } catch (IOException e) {
            LOG.warn("SpikeReview: cannot read " + path, e);
            return null;
        }
    }
}
