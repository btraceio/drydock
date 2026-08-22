package app.drydock.review;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Whether a base move can have changed what an approval was given for
 * (spec §9.2).
 *
 * <p>Marking every verdict stale on any base move spends the reviewer's
 * attention on commits that provably could not matter, and a
 * "confirm still good" button clicked reflexively is worth less than no
 * button. So the base delta is intersected first.</p>
 *
 * <p>The intersection is file-level and lexical. A base change that alters
 * behaviour without touching a file the scope names or references will not
 * mark anything -- drydock does not index the repository, so it cannot see
 * that far. Closing that gap is the agent recheck's job, not this class's.</p>
 */
public final class BaseMove {

    private static final Logger LOG = Logger.getLogger(BaseMove.class.getName());
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private BaseMove() {
    }

    /**
     * What a base move touched. {@code unresolvable} means the old base could
     * not be diffed -- a force-push, or a collected commit -- and is NOT the
     * same as an empty delta.
     */
    public record Delta(boolean unresolvable, SortedSet<String> changedFiles) {
        public Delta {
            Objects.requireNonNull(changedFiles, "changedFiles");
            changedFiles = new TreeSet<>(changedFiles);
        }
    }

    /** The files {@code oldBase..newBase} touched. Blocking; never call on the FX thread. */
    public static Delta between(Path worktree, String oldBase, String newBase) {
        List<String> command = List.of("git", "diff", "--name-only", "-z", "--end-of-options",
                oldBase + ".." + newBase);
        try {
            ProcessResult result = ProcessRunner.run(command, worktree, TIMEOUT);
            if (result.exitCode() != 0) {
                LOG.log(Level.WARNING, "git diff for base move failed: "
                        + ProcessRunner.excerpt(result.stderr()));
                return new Delta(true, new TreeSet<>());
            }
            return new Delta(false, parseNames(result.stdout()));
        } catch (ProcessTimeoutException e) {
            LOG.log(Level.WARNING, "git diff for base move timed out", e);
            return new Delta(true, new TreeSet<>());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.log(Level.WARNING, "git diff for base move could not run", e);
            return new Delta(true, new TreeSet<>());
        }
    }

    /**
     * Parses NUL-separated filenames from git diff output (with {@code -z} flag).
     * Each path is a raw UTF-8 string with no C-style quoting.
     */
    static SortedSet<String> parseNames(String stdout) {
        SortedSet<String> files = new TreeSet<>();
        for (String path : stdout.split("\0", -1)) {
            if (!path.isEmpty()) {
                files.add(path);
            }
        }
        return files;
    }

    /**
     * Whether {@code delta} could have changed the meaning of code in
     * {@code scopeFiles}.
     *
     * <p>{@code scopeFiles} is a {@link Collection} rather than the scope's
     * own file list so that the set can widen -- Phase 2 adds the files
     * declaring symbols the scope's hunks reference -- without moving any
     * caller.</p>
     */
    public static boolean couldMatter(Delta delta, Collection<String> scopeFiles) {
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(scopeFiles, "scopeFiles");
        if (delta.unresolvable()) {
            return true;
        }
        for (String file : scopeFiles) {
            if (delta.changedFiles().contains(file)) {
                return true;
            }
        }
        return false;
    }
}
