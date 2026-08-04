package app.drydock.git;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Produces the parsed {@link UnifiedDiff} the Review tab renders for each
 * {@link DiffScope} (design handoff section C): {@code git diff HEAD} for
 * the working tree (staged + unstaged), {@code git diff @{upstream}...HEAD}
 * for the upstream scope, and {@code git diff <base>...HEAD} for the
 * base-branch scope. Read-only; mirrors {@link GitStatusService}'s
 * process/executor style (argument lists, background virtual-thread
 * executor, {@link CompletableFuture} results).
 */
public final class DiffService implements AutoCloseable {

    private static final Logger LOG = System.getLogger(DiffService.class.getName());

    /** git's own default: three unchanged lines either side of a change. */
    public static final int DEFAULT_CONTEXT_LINES = 3;

    /**
     * Above this many untracked files, the intent-to-add pass is skipped
     * rather than run: a repository with this many untracked entries is
     * pathological (a build output directory that slipped past
     * {@code .gitignore}, say), and diffing all of them would hang the
     * Review diff column, which re-diffs constantly.
     */
    private static final int MAX_UNTRACKED = 2000;

    /**
     * What the Review diff column asks for. Wide enough that a fold is worth
     * making -- see {@link #diff(Path, DiffScope, String, int)}.
     */
    public static final int REVIEW_CONTEXT_LINES = 12;

    /** Every command here is a quick read-only query; a hung git must not park futures forever. */
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

    private final GitExecutableLocator locator;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    public DiffService() {
        this(new GitExecutableLocator());
    }

    public DiffService(GitExecutableLocator locator) {
        this(locator, Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    /** For tests/callers that want to supply their own executor (and own its shutdown). */
    public DiffService(GitExecutableLocator locator, ExecutorService executor) {
        this(locator, executor, false);
    }

    private DiffService(GitExecutableLocator locator, ExecutorService executor, boolean ownsExecutor) {
        this.locator = locator;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * Diffs {@code checkoutRoot} for {@code scope} on this service's
     * background executor. {@code baseBranch} is only consulted for
     * {@link DiffScope#BASE}. The returned future completes exceptionally
     * with a {@link GitException} (wrapped in a
     * {@link java.util.concurrent.CompletionException}) on any failure.
     */
    public CompletableFuture<UnifiedDiff> diff(Path checkoutRoot, DiffScope scope, String baseBranch) {
        return diff(checkoutRoot, scope, baseBranch, DEFAULT_CONTEXT_LINES);
    }

    /**
     * As {@link #diff(Path, DiffScope, String)}, with an explicit number of
     * unchanged lines around each change ({@code git diff -U}).
     *
     * <p>Review asks for {@link #REVIEW_CONTEXT_LINES} rather than git's
     * default three: its diff column folds long unchanged runs into a single
     * {@code ⋯ N unchanged} row, and with a three-line window there is never
     * a run long enough to be worth folding -- the feature would render, and
     * simply never appear. Showing more and folding it is the point.</p>
     */
    public CompletableFuture<UnifiedDiff> diff(Path checkoutRoot, DiffScope scope, String baseBranch,
                                               int contextLines) {
        return CompletableFuture.supplyAsync(
                () -> diffBlocking(checkoutRoot, scope, baseBranch, contextLines), executor);
    }

    /**
     * Synchronous form, exposed package-private so tests can assert on the
     * thrown exception type directly instead of unwrapping a
     * {@code CompletionException}. Must never be called from the JavaFX
     * application thread.
     */
    UnifiedDiff diffBlocking(Path checkoutRoot, DiffScope scope, String baseBranch) {
        return diffBlocking(checkoutRoot, scope, baseBranch, DEFAULT_CONTEXT_LINES);
    }

    UnifiedDiff diffBlocking(Path checkoutRoot, DiffScope scope, String baseBranch, int contextLines) {
        if (contextLines < 0) {
            throw new IllegalArgumentException("contextLines must be non-negative: " + contextLines);
        }
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        String range = switch (scope) {
            case WORKING_TREE -> "HEAD";
            case UPSTREAM -> "@{upstream}...HEAD";
            case BASE -> baseBranch + "...HEAD";
        };
        // --end-of-options: a branch name that looks like an option must
        // reach git as a revision, never be parsed as a flag.
        List<String> command = List.of(
                git.toString(), "-C", checkoutRoot.toString(),
                "diff", "--no-color", "--no-ext-diff", "-U" + contextLines,
                "--end-of-options", range);

        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            if (result.stderr().toLowerCase(Locale.ROOT).contains("not a git repository")) {
                throw new NotAGitRepositoryException(checkoutRoot);
            }
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }

        Set<String> untrackedPaths = Set.of();
        if (scope == DiffScope.WORKING_TREE) {
            untrackedPaths = untrackedPaths(git, checkoutRoot);
            result = withUntrackedFiles(git, checkoutRoot, command, result, untrackedPaths);
        }

        // Working-tree scope tags each file with whether (part of) its
        // change is staged, for the staged/unstaged chip.
        Set<String> stagedPaths = scope == DiffScope.WORKING_TREE
                ? stagedPaths(git, checkoutRoot)
                : Set.of();

        return parse(result.stdout(), stagedPaths, untrackedPaths);
    }

    /**
     * Extends a {@code git diff HEAD} result with untracked, non-ignored
     * files as new-file additions.
     *
     * <p>{@code git diff HEAD} never shows untracked files -- it diffs
     * against the index/HEAD, and an untracked path is in neither -- so a
     * repository whose only "dirty" content is brand-new files rendered
     * Review's working-tree diff as "No changes in this scope." with no way
     * to see the file at all (task 14). git's own intent-to-add marker
     * ({@code git add -N}) is what makes {@code git diff} treat a path as a
     * new file; running that against the real index would be staging the
     * user's files as a side effect of opening a diff, which no review tool
     * should ever do. So this copies the real index to a throwaway one
     * (selected via {@code GIT_INDEX_FILE}), marks the untracked paths
     * intent-to-add there, re-runs the diff against that copy, and discards
     * it -- the user's actual index and working tree are untouched, and
     * their next real {@code git add} or {@code git commit} sees exactly
     * what it would have before this ran. {@code git add -N} does still
     * write one empty blob into the repository's object database (git
     * always writes blob content before recording a tree/index entry that
     * points at it) -- but it is unreachable from any ref, touches no
     * index, status, or diff, and is identical every time regardless of how
     * many untracked files there are, so it is gc-able noise rather than a
     * correctness concern. The diff itself is still produced by git, not
     * synthesised here, so binary detection and line handling for the new
     * files match every other file in the diff exactly.</p>
     */
    /**
     * The {@code ls-files --others} set: paths git considers untracked and
     * non-ignored. Computed once per working-tree diff and reused both to
     * drive the intent-to-add pass below and to tag each resulting
     * {@link UnifiedDiff.FileDiff#untracked()} -- the same set, compared the
     * same way {@code stagedPaths} compares its own, so the flag cannot
     * drift from what {@link #withUntrackedFiles} actually diffed.
     */
    private Set<String> untrackedPaths(Path git, Path checkoutRoot) {
        // -z: NUL-separated raw paths, and also what lets an empty result be
        // told apart from a single empty-named entry unambiguously.
        List<String> lsFilesCommand = List.of(
                git.toString(), "-C", checkoutRoot.toString(),
                "ls-files", "--others", "--exclude-standard", "-z");
        ProcessResult lsFiles = run(lsFilesCommand);
        if (lsFiles.exitCode() != 0) {
            throw new GitCommandFailedException(lsFilesCommand, lsFiles.exitCode(),
                    ProcessRunner.excerpt(lsFiles.stderr()));
        }
        Set<String> untracked = new HashSet<>();
        for (String entry : lsFiles.stdout().split("\\u0000")) {
            if (!entry.isEmpty()) {
                untracked.add(entry);
            }
        }
        return untracked;
    }

    private ProcessResult withUntrackedFiles(Path git, Path checkoutRoot, List<String> diffCommand,
                                             ProcessResult trackedResult, Set<String> untracked) {
        // The common case -- nothing untracked -- must not pay for a single
        // extra process spawn beyond the ls-files probe above; Review
        // re-diffs constantly.
        if (untracked.isEmpty()) {
            return trackedResult;
        }
        if (untracked.size() > MAX_UNTRACKED) {
            LOG.log(Level.WARNING, "Skipping untracked-file diff pass in " + checkoutRoot + ": "
                    + untracked.size() + " untracked files exceeds the limit of " + MAX_UNTRACKED);
            return trackedResult;
        }

        Path tempIndex;
        try {
            // Files.createTempFile is the only portable way to reserve a
            // unique path; it creates an empty regular file to do so. That
            // file is then deleted immediately, leaving the path reserved
            // but absent, because a zero-length file is not a valid git
            // index -- git would fail to read it -- whereas a copy is about
            // to be written to this same path below.
            tempIndex = Files.createTempFile("drydock-diff-index", ".tmp");
            Files.delete(tempIndex);
        } catch (IOException e) {
            throw new GitCommandFailedException(List.of("Files.createTempFile"), -1,
                    e.getMessage() == null ? "could not create a temporary index file" : e.getMessage());
        }
        try {
            // --absolute-git-dir, never a hand-built ".git/index": inside a
            // linked worktree the real index lives at
            // ".git/worktrees/<name>/index", and this runs in worktrees
            // constantly.
            List<String> gitDirCommand = List.of(
                    git.toString(), "-C", checkoutRoot.toString(), "rev-parse", "--absolute-git-dir");
            ProcessResult gitDirResult = run(gitDirCommand);
            if (gitDirResult.exitCode() != 0) {
                throw new GitCommandFailedException(gitDirCommand, gitDirResult.exitCode(),
                        ProcessRunner.excerpt(gitDirResult.stderr()));
            }
            Path realIndex = Path.of(gitDirResult.stdout().strip()).resolve("index");
            if (Files.exists(realIndex)) {
                Files.copy(realIndex, tempIndex, StandardCopyOption.REPLACE_EXISTING);
            }

            Map<String, String> tempIndexEnv = Map.of("GIT_INDEX_FILE", tempIndex.toString());

            // Pathspec "." rather than the explicit untracked-file list: an
            // explicit list of every untracked path can blow past ARG_MAX on
            // a large repository, and "." already respects .gitignore.
            List<String> addCommand = List.of(
                    git.toString(), "-C", checkoutRoot.toString(), "add", "-N", "--", ".");
            ProcessResult addResult = run(addCommand,
                    new ProcessRunner.Options(null, PROCESS_TIMEOUT, false, tempIndexEnv));
            if (addResult.exitCode() != 0) {
                throw new GitCommandFailedException(addCommand, addResult.exitCode(),
                        ProcessRunner.excerpt(addResult.stderr()));
            }

            ProcessResult withUntracked = run(diffCommand,
                    new ProcessRunner.Options(null, PROCESS_TIMEOUT, false, tempIndexEnv));
            if (withUntracked.exitCode() != 0) {
                throw new GitCommandFailedException(diffCommand, withUntracked.exitCode(),
                        ProcessRunner.excerpt(withUntracked.stderr()));
            }
            return withUntracked;
        } catch (IOException e) {
            // The only IOException source in this block is the Files.copy
            // above (no git process is spawned between it and here that
            // could throw one) -- label the failure as what it actually is,
            // not as a git invocation that never ran.
            throw new GitCommandFailedException(List.of("Files.copy", "->", tempIndex.toString()), -1,
                    e.getMessage() == null ? "could not copy the index to a temporary file" : e.getMessage());
        } finally {
            // A leaked temp index is a leaked file per diff -- and Review
            // re-diffs constantly.
            try {
                Files.deleteIfExists(tempIndex);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not delete temporary index " + tempIndex, e);
            }
        }
    }

    private Set<String> stagedPaths(Path git, Path checkoutRoot) {
        // -z: NUL-separated raw paths. Without it git C-quotes any
        // non-ASCII path and the contains() check against the diff's plain
        // path text would silently miss it.
        List<String> command = List.of(
                git.toString(), "-C", checkoutRoot.toString(),
                "diff", "--cached", "--name-only", "-z");
        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            return Set.of();
        }
        Set<String> paths = new HashSet<>();
        for (String entry : result.stdout().split("\\u0000")) {
            if (!entry.isEmpty()) {
                paths.add(entry);
            }
        }
        return paths;
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }

    // ---- parsing: unified diff text ----

    /**
     * Parses {@code git diff} unified output into {@link UnifiedDiff}.
     * Tracks the running old/new line numbers per hunk so every row
     * carries the gutter values (and stable annotation keys). Binary
     * files produce a file entry with no hunks.
     */
    /**
     * Parses a unified diff that did not come from this service's own
     * {@code git diff} -- {@code gh pr diff} for the "Read the patch only"
     * path, which has no local checkout to run git in. Same parser, so a PR
     * read without a worktree renders exactly like one with.
     */
    public static UnifiedDiff parseUnified(String unifiedDiff) {
        return parse(unifiedDiff, Set.of(), Set.of());
    }

    static UnifiedDiff parse(String stdout, Set<String> stagedPaths, Set<String> untrackedPaths) {
        List<UnifiedDiff.FileDiff> files = new ArrayList<>();

        String path = null;
        String kind = null;
        List<UnifiedDiff.Hunk> hunks = null;
        List<UnifiedDiff.Line> lines = null;
        String hunkHeader = null;
        int oldLine = 0;
        int newLine = 0;
        int insertions = 0;
        int deletions = 0;

        List<String> raw = stdout.lines().toList();
        for (String line : raw) {
            if (line.startsWith("diff --git ")) {
                if (path != null) {
                    if (hunkHeader != null) {
                        hunks.add(new UnifiedDiff.Hunk(hunkHeader, List.copyOf(lines)));
                    }
                    files.add(new UnifiedDiff.FileDiff(path, kind == null ? "M" : kind, insertions, deletions,
                            stagedPaths.contains(path), untrackedPaths.contains(path), List.copyOf(hunks)));
                }
                path = parseDiffGitPath(line);
                kind = null;
                hunks = new ArrayList<>();
                lines = new ArrayList<>();
                hunkHeader = null;
                insertions = 0;
                deletions = 0;
            } else if (path == null) {
                continue;
            } else if (line.startsWith("new file mode")) {
                kind = "A";
            } else if (line.startsWith("deleted file mode")) {
                kind = "D";
            } else if (line.startsWith("rename from") || line.startsWith("rename to")) {
                kind = "R";
            } else if (line.startsWith("+++ b/")) {
                // The post-image path is authoritative (handles renames).
                path = line.substring("+++ b/".length());
            } else if (line.startsWith("@@")) {
                if (hunkHeader != null) {
                    hunks.add(new UnifiedDiff.Hunk(hunkHeader, List.copyOf(lines)));
                    lines = new ArrayList<>();
                }
                hunkHeader = line;
                int[] starts = parseHunkStarts(line);
                oldLine = starts[0];
                newLine = starts[1];
            } else if (hunkHeader != null && !line.startsWith("\\")) {
                if (line.startsWith("+")) {
                    lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                            OptionalInt.empty(), OptionalInt.of(newLine++), line.substring(1)));
                    insertions++;
                } else if (line.startsWith("-")) {
                    lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.DEL,
                            OptionalInt.of(oldLine++), OptionalInt.empty(), line.substring(1)));
                    deletions++;
                } else {
                    String text = line.startsWith(" ") ? line.substring(1) : line;
                    lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT,
                            OptionalInt.of(oldLine++), OptionalInt.of(newLine++), text));
                }
            }
        }
        if (path != null) {
            if (hunkHeader != null) {
                hunks.add(new UnifiedDiff.Hunk(hunkHeader, List.copyOf(lines)));
            }
            files.add(new UnifiedDiff.FileDiff(path, kind == null ? "M" : kind, insertions, deletions,
                    stagedPaths.contains(path), untrackedPaths.contains(path), List.copyOf(hunks)));
        }
        return new UnifiedDiff(List.copyOf(files));
    }

    /** Extracts the b-side path from a {@code diff --git a/x b/x} line (quoted paths kept verbatim). */
    private static String parseDiffGitPath(String line) {
        String rest = line.substring("diff --git ".length());
        int bIndex = rest.lastIndexOf(" b/");
        if (bIndex >= 0) {
            return rest.substring(bIndex + " b/".length());
        }
        return rest;
    }

    /** Parses the old/new start line numbers out of {@code @@ -a,b +c,d @@ ...}. */
    private static int[] parseHunkStarts(String header) {
        // Only look inside the @@ ... @@ span; the trailing context snippet
        // may itself contain "+"/"-" tokens.
        int close = header.indexOf("@@", 2);
        String span = close >= 0 ? header.substring(2, close) : header.substring(2);
        int oldStart = 1;
        int newStart = 1;
        for (String part : span.strip().split("\\s+")) {
            if (part.startsWith("-")) {
                oldStart = parseStart(part.substring(1), oldStart);
            } else if (part.startsWith("+")) {
                newStart = parseStart(part.substring(1), newStart);
            }
        }
        return new int[] {oldStart, newStart};
    }

    private static int parseStart(String range, int fallback) {
        int comma = range.indexOf(',');
        String digits = comma >= 0 ? range.substring(0, comma) : range;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---- process execution (shared ProcessRunner, git-flavored failure translation) ----

    private static ProcessResult run(List<String> command) {
        try {
            return ProcessRunner.run(command, null, PROCESS_TIMEOUT);
        } catch (IOException e) {
            throw new GitCommandFailedException(command, -1, e.getMessage() == null ? "" : e.getMessage());
        } catch (ProcessTimeoutException e) {
            throw new GitCommandFailedException(command, -1,
                    "timed out after " + PROCESS_TIMEOUT.toSeconds() + "s (killed)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitCommandFailedException(command, -1, "interrupted while waiting for git");
        }
    }

    /** As {@link #run(List)}, with explicit {@link ProcessRunner.Options} -- e.g. a {@code GIT_INDEX_FILE} override. */
    private static ProcessResult run(List<String> command, ProcessRunner.Options options) {
        try {
            return ProcessRunner.run(command, options);
        } catch (IOException e) {
            throw new GitCommandFailedException(command, -1, e.getMessage() == null ? "" : e.getMessage());
        } catch (ProcessTimeoutException e) {
            throw new GitCommandFailedException(command, -1,
                    "timed out after " + options.timeout().toSeconds() + "s (killed)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitCommandFailedException(command, -1, "interrupted while waiting for git");
        }
    }
}
