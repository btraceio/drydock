package app.drydock.ui;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Pure branch-name → worktree-directory derivation for the create-worktree
 * modal (design handoff section B: directory auto-derived from the branch
 * slug as {@code ~/dev/wt/<repo>-<slug>}, editable).
 */
final class WorktreeNaming {

    private WorktreeNaming() {
    }

    /**
     * Slugs a branch name for use in a directory name: the prefix up to the
     * last {@code /} is dropped ({@code feat/sidebar-resize} → {@code
     * sidebar-resize}), everything non-alphanumeric collapses to single
     * dashes, lowercased. Returns {@code "worktree"} when nothing usable
     * remains (e.g. a bare {@code feat/}).
     */
    static String slug(String branch) {
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
    static Path defaultDirectory(Path home, String repositoryName, String branch) {
        return defaultDirectory(home, Optional.empty(), repositoryName, branch);
    }

    /**
     * As {@link #defaultDirectory(Path, String, String)}, but forms the
     * directory under {@code worktreesDirectory} (the user's configured
     * base, see {@code UserConfig}) instead of {@code <home>/dev/wt} when
     * present: {@code <worktreesDirectory>/<repo>-<slug>}.
     */
    static Path defaultDirectory(Path home, Optional<Path> worktreesDirectory, String repositoryName,
                                 String branch) {
        String repoSlug = repositoryName == null ? "" : repositoryName.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (repoSlug.isEmpty()) {
            repoSlug = "repo";
        }
        Path base = worktreesDirectory.orElseGet(() -> home.resolve("dev").resolve("wt"));
        return base.resolve(repoSlug + "-" + slug(branch));
    }
}
