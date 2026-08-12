package app.drydock.git;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Copies one worktree's uncommitted state onto another, leaving it
 * uncommitted so the review rail still sees a real diff.
 *
 * <p>Two moves, because git has no single command for this: tracked changes
 * (including deletions and binary content) cross as a {@code git diff HEAD
 * --binary} patch applied in the destination, and untracked non-ignored files
 * are copied byte for byte.</p>
 *
 * <p><strong>All-or-nothing is the caller's job, not this class's.</strong>
 * Any failure throws, and {@code SessionForkService} responds by removing the
 * destination worktree and its branch. A half-populated worktree that looked
 * like a successful fork would be worse than a visible failure, because the
 * human would start working in it.</p>
 */
public final class WorktreeTransplant {

    /** Matches GitStatusService's own per-command bound. */
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

    private final GitExecutableLocator locator;

    public WorktreeTransplant() {
        this(new GitExecutableLocator());
    }

    public WorktreeTransplant(GitExecutableLocator locator) {
        this.locator = locator;
    }

    /**
     * Carries {@code source}'s uncommitted state onto {@code destination}.
     *
     * <p>Blocking; never call on the FX thread.</p>
     *
     * @return how many files were carried over
     * @throws GitCommandFailedException if any step fails, leaving {@code
     *         destination} in an undefined state the caller must discard
     */
    public int transplantBlocking(Path source, Path destination) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        return applyTrackedChanges(git, source, destination) + copyUntracked(git, source, destination);
    }

    /**
     * A repository with no commits has no {@code HEAD} to diff against, so
     * there are no tracked changes by definition -- every file in it is
     * untracked, and {@link #copyUntracked} carries the whole tree.
     */
    private int applyTrackedChanges(Path git, Path source, Path destination) {
        ProcessResult head = run(List.of(git.toString(), "-C", source.toString(),
                "rev-parse", "--verify", "HEAD"));
        if (head.exitCode() != 0) {
            return 0;
        }

        ProcessResult diff = run(List.of(git.toString(), "-C", source.toString(), "diff", "HEAD", "--binary"));
        if (diff.exitCode() != 0) {
            throw new GitCommandFailedException(List.of("git", "diff", "HEAD", "--binary"),
                    diff.exitCode(), ProcessRunner.excerpt(diff.stderr()));
        }
        if (diff.stdout().isBlank()) {
            return 0;
        }

        // Via a temp file rather than stdin: ProcessRunner has no stdin
        // channel, and it is the single place this codebase spawns processes.
        // --binary output is base85, so it survives the String round trip.
        Path patch = null;
        try {
            patch = Files.createTempFile("drydock-transplant-", ".patch");
            Files.writeString(patch, diff.stdout(), StandardCharsets.UTF_8);
            ProcessResult apply = run(List.of(git.toString(), "-C", destination.toString(),
                    "apply", "--binary", patch.toString()));
            if (apply.exitCode() != 0) {
                throw new GitCommandFailedException(List.of("git", "apply", "--binary"),
                        apply.exitCode(), ProcessRunner.excerpt(apply.stderr()));
            }
        } catch (IOException e) {
            throw new GitCommandFailedException(List.of("git", "apply", "--binary"), -1,
                    e.getMessage() == null ? "could not stage the patch" : e.getMessage());
        } finally {
            deleteQuietly(patch);
        }
        return countPatchedFiles(diff.stdout());
    }

    private int copyUntracked(Path git, Path source, Path destination) {
        ProcessResult listed = run(List.of(git.toString(), "-C", source.toString(),
                "ls-files", "--others", "--exclude-standard", "-z"));
        if (listed.exitCode() != 0) {
            throw new GitCommandFailedException(List.of("git", "ls-files", "--others"),
                    listed.exitCode(), ProcessRunner.excerpt(listed.stderr()));
        }

        int copied = 0;
        for (String relative : listed.stdout().split("\0")) {
            if (relative.isEmpty()) {
                continue;
            }
            Path from = source.resolve(relative);
            Path to = destination.resolve(relative);
            try {
                Path parent = to.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                copied++;
            } catch (IOException e) {
                throw new GitCommandFailedException(List.of("copy", relative), -1,
                        e.getMessage() == null ? "could not copy an untracked file" : e.getMessage());
            }
        }
        return copied;
    }

    /** One {@code diff --git} header per file the patch touches. */
    private static int countPatchedFiles(String patch) {
        int count = 0;
        for (String line : patch.split("\n")) {
            if (line.startsWith("diff --git ")) {
                count++;
            }
        }
        return count;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover temp patch is not worth masking the real outcome.
        }
    }

    private static ProcessResult run(List<String> command) {
        try {
            return ProcessRunner.run(command, new ProcessRunner.Options(null, PROCESS_TIMEOUT, true, Map.of()));
        } catch (IOException e) {
            throw new GitCommandFailedException(command, -1, e.getMessage() == null ? "" : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitCommandInterruptedException(command);
        }
    }
}
