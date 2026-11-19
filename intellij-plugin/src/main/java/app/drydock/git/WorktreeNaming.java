package app.drydock.git;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Pure branch-name → worktree-directory derivation for the create-worktree
 * modal (design handoff section B: directory auto-derived from the branch
 * slug as {@code ~/dev/wt/<repo>-<slug>}, editable).
 */
public final class WorktreeNaming {

    private WorktreeNaming() {
    }

    /**
     * Slugs a branch name for use in a directory name: the prefix up to the
     * last {@code /} is dropped ({@code feat/sidebar-resize} → {@code
     * sidebar-resize}), everything non-alphanumeric collapses to single
     * dashes, lowercased. Returns {@code "worktree"} when nothing usable
     * remains (e.g. a bare {@code feat/}).
     */
    public static String slug(String branch) {
        String tail = branch == null ? "" : branch.strip();
        int lastSlash = tail.lastIndexOf('/');
        if (lastSlash >= 0) {
            tail = tail.substring(lastSlash + 1);
        }
        String slug = tail.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "worktree" : slug;
    }

    /** The default worktree directory: {@code <home>/dev/wt/<repo>-<slug>}. */
    public static Path defaultDirectory(Path home, String repositoryName, String branch) {
        return defaultDirectory(home, Optional.empty(), repositoryName, branch);
    }

    /**
     * As {@link #defaultDirectory(Path, String, String)}, but forms the
     * directory under {@code worktreesDirectory} (the user's configured
     * base, see {@code UserConfig}) instead of {@code <home>/dev/wt} when
     * present: {@code <worktreesDirectory>/<repo>-<slug>}.
     */
    public static Path defaultDirectory(Path home, Optional<Path> worktreesDirectory, String repositoryName,
                                 String branch) {
        Path base = worktreesDirectory.orElseGet(() -> home.resolve("dev").resolve("wt"));
        return base.resolve(repositorySlug(repositoryName) + "-" + slug(branch));
    }

    /**
     * The directory a babysitter session's detached worktree for pull request
     * {@code prNumber} lives in: {@code <base>/<repo>--babysit-pr<n>}.
     *
     * <p>The double dash is what keeps it apart from every
     * {@link #defaultDirectory} name, including the watched session's own
     * worktree: both slugs collapse runs of non-alphanumerics to one dash and
     * trim them at the ends, so {@code <repo>-<slug>} never contains
     * {@code --}. A branch literally named {@code babysit-pr12} still gets
     * {@code <repo>-babysit-pr12}, never this directory. A hand-picked folder
     * can still be anything, so the creator probes for an existing
     * directory before {@code git worktree add}.</p>
     */
    public static Path babysitterDirectory(Path base, String repositoryName, int prNumber) {
        if (prNumber <= 0) {
            throw new IllegalArgumentException("prNumber must be positive: " + prNumber);
        }
        return base.resolve(repositorySlug(repositoryName) + "--babysit-pr" + prNumber);
    }

    private static String repositorySlug(String repositoryName) {
        String repoSlug = repositoryName == null ? "" : repositoryName.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return repoSlug.isEmpty() ? "repo" : repoSlug;
    }
}
