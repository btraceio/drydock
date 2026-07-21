package app.drydock.git;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
        return CompletableFuture.supplyAsync(() -> diffBlocking(checkoutRoot, scope, baseBranch), executor);
    }

    /**
     * Synchronous form, exposed package-private so tests can assert on the
     * thrown exception type directly instead of unwrapping a
     * {@code CompletionException}. Must never be called from the JavaFX
     * application thread.
     */
    UnifiedDiff diffBlocking(Path checkoutRoot, DiffScope scope, String baseBranch) {
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
                "diff", "--no-color", "--no-ext-diff", "--end-of-options", range);

        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            if (result.stderr().toLowerCase(Locale.ROOT).contains("not a git repository")) {
                throw new NotAGitRepositoryException(checkoutRoot);
            }
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }

        // Working-tree scope tags each file with whether (part of) its
        // change is staged, for the staged/unstaged chip.
        Set<String> stagedPaths = scope == DiffScope.WORKING_TREE
                ? stagedPaths(git, checkoutRoot)
                : Set.of();

        return parse(result.stdout(), stagedPaths);
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
    static UnifiedDiff parse(String stdout, Set<String> stagedPaths) {
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
                            stagedPaths.contains(path), List.copyOf(hunks)));
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
                    stagedPaths.contains(path), List.copyOf(hunks)));
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
}
