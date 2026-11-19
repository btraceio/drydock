package app.drydock.git;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * The review-query rules shared by every producer of review data: the local
 * {@link DiffService} / {@link GitStatusService} and any producer that runs
 * the same Git queries elsewhere. Pure functions only -- no process spawning,
 * no state -- so one rule cannot drift between producers.
 */
public final class GitReviewQueryRules {

    /** The well-known default-branch names, in preference order. */
    public static final List<String> DEFAULT_BRANCH_CANDIDATES = List.of("main", "master", "trunk", "develop");

    /**
     * The branches a review base is looked for among when no pull request
     * declares one, after the repository's own default. {@code develop}
     * before {@code trunk} because a repository that has both almost always
     * integrates on the former.
     */
    public static final List<String> INTEGRATION_BRANCHES = List.of("main", "master", "develop", "trunk");

    private GitReviewQueryRules() {
    }

    /** A branch-name-shaped string: no leading dash, no refspec tricks, no {@code ..}. */
    public static boolean isRefComponent(String name) {
        if (name.isEmpty() || name.length() > 200 || name.startsWith("-") || name.startsWith(".")
                || name.contains("..") || name.endsWith("/") || name.endsWith(".") || name.contains("//")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '/' || c == '-' || c == '_' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * The ordered base candidates: the default first (resolved through
     * {@code resolver}, or kept verbatim when it resolves to nothing), then
     * each integration branch that resolves and is not already listed. The
     * order doubles as the tie-break in {@link #chooseBase}.
     */
    public static List<String> reviewBaseCandidates(String defaultBranch,
                                                    Function<String, Optional<String>> resolver) {
        List<String> candidates = new ArrayList<>();
        String defaultName = defaultBranch.startsWith("origin/")
                ? defaultBranch.substring("origin/".length()) : defaultBranch;
        candidates.add(resolver.apply(defaultName).orElse(defaultBranch));
        for (String name : INTEGRATION_BRANCHES) {
            resolver.apply(name)
                    .filter(resolved -> !candidates.contains(resolved))
                    .ifPresent(candidates::add);
        }
        return candidates;
    }

    /**
     * The candidate with the fewest commits ahead ({@code commitsAhead}
     * negative = unmeasurable, skipped; strict {@code <} so a tie keeps the
     * earlier candidate), or the unmeasured default when none measures.
     */
    public static ReviewBase chooseBase(List<String> candidates, ToLongFunction<String> commitsAhead,
                                        String defaultBranch) {
        String best = defaultBranch;
        long fewest = Long.MAX_VALUE;
        for (String candidate : candidates) {
            long ahead = commitsAhead.applyAsLong(candidate);
            if (ahead >= 0 && ahead < fewest) {
                fewest = ahead;
                best = candidate;
            }
        }
        if (fewest == Long.MAX_VALUE) {
            return new ReviewBase(defaultBranch, ReviewBase.Origin.DEFAULT_UNMEASURED);
        }
        return new ReviewBase(best, ReviewBase.Origin.FORKED_FROM);
    }

    /** Whether the scope diffs the working tree, i.e. carries staged/untracked metadata. */
    public static boolean includesWorkingTree(DiffScope scope) {
        return scope == DiffScope.WORKING_TREE || scope == DiffScope.BRANCH_WORKING_TREE;
    }

    /**
     * The {@code git diff} command for {@code scope}. {@code mergeBase} is
     * consulted only for {@link DiffScope#BRANCH_WORKING_TREE}.
     */
    public static List<String> diffCommand(String git, Path checkoutRoot, DiffScope scope, String baseBranch,
                                           int contextLines, Supplier<String> mergeBase) {
        String range = switch (scope) {
            case WORKING_TREE -> "HEAD";
            case UPSTREAM -> "@{upstream}...HEAD";
            case BASE -> baseBranch + "...HEAD";
            // One revision, so git compares the WORKING TREE against it: the
            // branch's commits and its uncommitted edits in a single diff,
            // measured from the fork point as BASE's three dots are.
            case BRANCH_WORKING_TREE -> mergeBase.get();
        };
        // --end-of-options: a branch name that looks like an option must
        // reach git as a revision, never be parsed as a flag.
        return List.of(git, "-C", checkoutRoot.toString(),
                "diff", "--no-color", "--no-ext-diff", "-U" + contextLines,
                "--end-of-options", range);
    }

    /** The commit {@code HEAD} forked from {@code baseBranch} at. */
    public static List<String> mergeBaseCommand(String git, Path checkoutRoot, String baseBranch) {
        return List.of(git, "-C", checkoutRoot.toString(),
                "merge-base", "--end-of-options", baseBranch, "HEAD");
    }

    /** Untracked, non-ignored paths; NUL-separated output for {@link #parseNulPaths}. */
    public static List<String> untrackedCommand(String git, Path checkoutRoot) {
        return List.of(git, "-C", checkoutRoot.toString(),
                "ls-files", "--others", "--exclude-standard", "-z");
    }

    /** Paths with staged changes; NUL-separated output for {@link #parseNulPaths}. */
    public static List<String> stagedCommand(String git, Path checkoutRoot) {
        return List.of(git, "-C", checkoutRoot.toString(),
                "diff", "--cached", "--name-only", "-z");
    }

    /** Arguments (after {@code git -C <root>}) counting commits {@code HEAD} has that {@code base} lacks. */
    public static List<String> commitsAheadArguments(String base) {
        return List.of("rev-list", "--count", "--end-of-options", base + "..HEAD");
    }

    /** Splits NUL-separated {@code -z} output into its non-empty paths. */
    public static Set<String> parseNulPaths(String stdout) {
        Set<String> paths = new HashSet<>();
        for (String entry : stdout.split("\\u0000")) {
            if (!entry.isEmpty()) {
                paths.add(entry);
            }
        }
        return paths;
    }

    /**
     * Parses {@code git diff} output, tagging each file whose path is in
     * {@code stagedPaths} / {@code untrackedPaths}. The same parser the local
     * Review diff uses.
     */
    public static UnifiedDiff parse(String stdout, Set<String> stagedPaths, Set<String> untrackedPaths) {
        return DiffService.parse(stdout, stagedPaths, untrackedPaths);
    }
}
