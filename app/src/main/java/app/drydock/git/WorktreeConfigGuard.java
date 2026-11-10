package app.drydock.git;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Detects and removes {@code extensions.worktreeConfig=true} from a
 * repository's SHARED config -- the flag Claude Code's worktree feature
 * (worktree isolation, sparse worktrees) flips silently, without asking.
 *
 * <p>Why the flip is worth guarding against: once the extension is on, git
 * reads a per-worktree {@code config.worktree} file for EVERY worktree
 * sharing that common git dir, and {@code git config --worktree} in every
 * other worktree resolves against files that do not exist yet instead of
 * the shared config values the user actually set. Enabling it the proper
 * way ({@code git worktree ...} with sparse checkout) migrates the values
 * git forbids sharing ({@code core.bare}, {@code core.worktree}); Claude
 * Code sets the bare flag via plain {@code git config}, which performs no
 * such migration. See anthropics/claude-code#45201 for the reported
 * breakage of bare-repo worktree layouts.</p>
 *
 * <p>The fix is deliberately conservative ("safe full check"): the flag is
 * only unset when nothing legitimately depends on it -- a worktree with
 * sparse checkout enabled owns the extension and is refused, so is a
 * worktree carrying its own {@code config.worktree} values (a user's own
 * {@code git config --worktree} settings), and so is a Claude Code
 * worktree under {@code .claude/worktrees/} that looks live (locked,
 * dirty, or active within the last hour). Stale Claude worktree leftovers
 * never block. {@code core.repositoryformatversion} is restored to 0 only
 * when no other {@code extensions.*} entry remains, because version 1 is
 * what makes git read the {@code extensions} section at all.</p>
 *
 * <p>Mirrors {@link WorktreeService}'s process/executor style: argument
 * lists (never a shell string), all work on a background virtual-thread
 * executor, {@link CompletableFuture} results, package-private blocking
 * forms for tests.</p>
 */
public final class WorktreeConfigGuard implements AutoCloseable {

    private static final Logger LOG = System.getLogger(WorktreeConfigGuard.class.getName());

    /** A config read; a hung git must not park futures forever. */
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

    /** Marker of worktrees Claude Code's worktree isolation creates. */
    private static final String CLAUDE_WORKTREE_MARKER = ".claude/worktrees";

    /** Git-dir activity newer than this means a Claude Code worktree's session may still be running. */
    private static final Duration LIVE_ACTIVITY_WINDOW = Duration.ofHours(1);

    /**
     * The detection result for one repository. {@code originPath} is the
     * config file the entry was read from (from {@code git config
     * --show-origin}; e.g. {@code <repo>/.git/config}), empty when the flag
     * is absent or git did not report a file origin.
     */
    public record Flag(boolean set, Optional<String> originPath) {

        public static Flag clean() {
            return new Flag(false, Optional.empty());
        }
    }

    /** The outcome of the one-click fix; a hard git failure surfaces as a failed future instead. */
    public sealed interface FixResult {
        /** The flag was absent when the fix ran (e.g. removed in between). */
        record AlreadyClean() implements FixResult { }

        /** The flag was unset; {@code versionRestored} says whether repositoryformatversion went back to 0. */
        record Fixed(boolean versionRestored) implements FixResult { }

        /** The flag is still there because something legitimately needs it; {@code reason} says what. */
        record Refused(String reason) implements FixResult { }
    }

    private final GitExecutableLocator locator;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    public WorktreeConfigGuard() {
        this(new GitExecutableLocator());
    }

    public WorktreeConfigGuard(GitExecutableLocator locator) {
        this(locator, Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    /** For tests/callers that want to supply their own executor (and own its shutdown). */
    WorktreeConfigGuard(GitExecutableLocator locator, ExecutorService executor, boolean ownsExecutor) {
        this.locator = locator;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * Reads {@code extensions.worktreeConfig} from {@code repositoryRoot}'s
     * config. Clean exit 1 is "not set", not a failure; any other non-zero
     * exit fails the future.
     */
    public CompletableFuture<Flag> check(Path repositoryRoot) {
        return CompletableFuture.supplyAsync(() -> checkBlocking(repositoryRoot), executor);
    }

    Flag checkBlocking(Path repositoryRoot) {
        Path git = gitExecutable();
        // --local reads exactly the shared <repo>/.git/config -- where
        // Claude Code writes the flag -- and refuses (exit 128) outside a
        // repository, which a plain read cannot distinguish from "unset".
        List<String> command = List.of(
                git.toString(), "-C", repositoryRoot.toString(),
                "config", "--local", "--show-origin", "--get", "extensions.worktreeConfig");
        ProcessResult result = run(command);
        if (result.exitCode() == 0) {
            return new Flag(true, originFileOf(result.stdout()));
        }
        if (result.exitCode() == 1) {
            return Flag.clean();
        }
        throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
    }

    /**
     * Removes the flag when nothing needs it. The caller decides how to
     * surface {@link FixResult.Refused}; a git failure propagates as a
     * failed future.
     */
    public CompletableFuture<FixResult> fix(Path repositoryRoot) {
        return CompletableFuture.supplyAsync(() -> fixBlocking(repositoryRoot), executor);
    }

    FixResult fixBlocking(Path repositoryRoot) {
        Path git = gitExecutable();
        if (!checkBlocking(repositoryRoot).set()) {
            return new FixResult.AlreadyClean();
        }

        // The extension has legitimate owners. Sparse checkout is git's own
        // reason to enable it (per-worktree sparse config), a user's own
        // `git config --worktree` values live in <gitdir>/config.worktree,
        // and a LIVE Claude Code worktree may still be using the flag when
        // the user clicks Fix -- all three keep it. A stale Claude worktree
        // (unlocked, clean, quiet for over LIVE_ACTIVITY_WINDOW) does not:
        // sessions routinely leave dead worktrees behind, and refusing on
        // those would make the fix unusable exactly when it is needed
        // (live 2026-11-09: the drydock repo's own shared config was
        // flagged while only a detached, unlocked, clean Claude worktree
        // leftover was registered).
        List<String> sparse = new ArrayList<>();
        List<String> perWorktreeConfig = new ArrayList<>();
        List<String> claude = new ArrayList<>();
        for (WorktreeGitdir wt : worktreeGitdirs(git, repositoryRoot)) {
            if (Files.exists(wt.gitdir().resolve("info").resolve("sparse-checkout"))) {
                sparse.add(wt.path().toString());
            } else if (Files.exists(wt.gitdir().resolve("config.worktree"))) {
                perWorktreeConfig.add(wt.path().toString());
            }
            if (wt.path().normalize().toString().contains(CLAUDE_WORKTREE_MARKER)
                    && claudeWorktreeLive(git, wt)) {
                claude.add(wt.path().toString());
            }
        }
        if (!sparse.isEmpty()) {
            return new FixResult.Refused(
                    "a worktree with sparse checkout needs extensions.worktreeConfig ("
                            + String.join(", ", sparse) + "); removing the flag would break it");
        }
        if (!perWorktreeConfig.isEmpty()) {
            return new FixResult.Refused(
                    "a worktree carries its own config.worktree values (" + String.join(", ", perWorktreeConfig)
                            + "); removing the flag makes git stop reading them, silently orphaning those settings");
        }
        if (!claude.isEmpty()) {
            return new FixResult.Refused(
                    "a live Claude Code worktree may still be using the flag (" + String.join(", ", claude)
                            + "); end that session or remove the worktree, then fix again");
        }

        List<String> unset = List.of(
                git.toString(), "-C", repositoryRoot.toString(),
                "config", "--unset-all", "extensions.worktreeConfig");
        ProcessResult unsetResult = run(unset);
        if (unsetResult.exitCode() != 0) {
            throw new GitCommandFailedException(unset, unsetResult.exitCode(),
                    ProcessRunner.excerpt(unsetResult.stderr()));
        }

        // Version 1 exists so git reads the extensions section at all; with
        // no extension left it is dead weight and goes back to 0. Another
        // extension present (objectFormat, refStorage, ...) keeps it.
        List<String> remaining = List.of(
                git.toString(), "-C", repositoryRoot.toString(),
                "config", "--get-regexp", "^extensions\\.");
        ProcessResult remainingResult = run(remaining);
        boolean versionRestored = false;
        if (remainingResult.exitCode() == 1) {
            List<String> restore = List.of(
                    git.toString(), "-C", repositoryRoot.toString(),
                    "config", "core.repositoryformatversion", "0");
            ProcessResult restoreResult = run(restore);
            if (restoreResult.exitCode() != 0) {
                throw new GitCommandFailedException(restore, restoreResult.exitCode(),
                        ProcessRunner.excerpt(restoreResult.stderr()));
            }
            versionRestored = true;
        } else if (remainingResult.exitCode() != 0) {
            throw new GitCommandFailedException(remaining, remainingResult.exitCode(),
                    ProcessRunner.excerpt(remainingResult.stderr()));
        }
        LOG.log(Level.DEBUG, "removed extensions.worktreeConfig from " + repositoryRoot);
        return new FixResult.Fixed(versionRestored);
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }

    // ---- helpers ----

    private Path gitExecutable() {
        return locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));
    }

    /**
     * {@code git config --show-origin} prefixes each value with
     * {@code file:<path>\t} (or {@code command line:} et al.). Only the
     * file form yields a path.
     */
    private static Optional<String> originFileOf(String stdout) {
        String line = stdout.strip();
        if (!line.startsWith("file:")) {
            return Optional.empty();
        }
        String rest = line.substring("file:".length());
        int tab = rest.indexOf('\t');
        return Optional.of(tab >= 0 ? rest.substring(0, tab).strip() : rest.strip());
    }

    private record WorktreeGitdir(Path path, Path gitdir, boolean locked) { }

    /**
     * {@code git worktree list --porcelain}, with each entry's git dir
     * resolved. A sparse checkout keeps its marker file at
     * {@code <gitdir>/info/sparse-checkout}, so the git dir is what the
     * probes need. Modern git's porcelain output no longer prints a
     * {@code gitdir} line, so it is resolved the way git itself does it:
     * a linked worktree's {@code .git} is a FILE naming its git dir, the
     * main checkout's {@code .git} is a DIRECTORY, and a bare checkout has
     * none (the worktree path itself is the git dir). A porcelain
     * {@code gitdir} line, when git prints one, wins.
     */
    private List<WorktreeGitdir> worktreeGitdirs(Path git, Path repositoryRoot) {
        List<String> command = List.of(
                git.toString(), "-C", repositoryRoot.toString(),
                "worktree", "list", "--porcelain");
        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
        List<WorktreeGitdir> worktrees = new ArrayList<>();
        Optional<Path> path = Optional.empty();
        boolean locked = false;
        for (String line : result.stdout().split("\n", -1)) {
            if (line.startsWith("worktree ")) {
                if (path.isPresent()) {
                    Optional<Path> gitdir = resolveGitdir(path.get());
                    if (gitdir.isPresent()) {
                        worktrees.add(new WorktreeGitdir(path.get(), gitdir.get(), locked));
                    }
                }
                path = Optional.of(Path.of(line.substring("worktree ".length()).strip()));
                locked = false;
            } else if (line.startsWith("gitdir ") && path.isPresent()) {
                worktrees.add(new WorktreeGitdir(path.get(),
                        Path.of(line.substring("gitdir ".length()).strip()), locked));
                path = Optional.empty();
                locked = false;
            } else if (line.equals("locked") || line.startsWith("locked ")) {
                locked = true;
            }
        }
        if (path.isPresent()) {
            Optional<Path> gitdir = resolveGitdir(path.get());
            if (gitdir.isPresent()) {
                worktrees.add(new WorktreeGitdir(path.get(), gitdir.get(), locked));
            }
        }
        return worktrees;
    }

    /**
     * Whether a Claude Code worktree looks live: locked (a deliberate
     * in-flight marker), with git-dir activity inside {@link
     * #LIVE_ACTIVITY_WINDOW} (a running session touches the index), or
     * dirty. The mtime is read BEFORE the dirty probe -- {@code git status}
     * refreshes and rewrites the index, so the order decides whether a
     * dead-but-clean worktree can ever be recognized as stale.
     */
    private boolean claudeWorktreeLive(Path git, WorktreeGitdir wt) {
        if (wt.locked()) {
            return true;
        }
        Path marker = Files.isRegularFile(wt.gitdir().resolve("index"))
                ? wt.gitdir().resolve("index")
                : wt.gitdir();
        try {
            if (Duration.between(Files.getLastModifiedTime(marker).toInstant(), Instant.now())
                    .abs()
                    .compareTo(LIVE_ACTIVITY_WINDOW) < 0) {
                return true;
            }
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "unreadable mtime on " + marker, e);
            return false;
        }
        List<String> status = List.of(
                git.toString(), "-C", wt.path().toString(), "status", "--porcelain");
        try {
            ProcessResult result = run(status);
            return result.exitCode() == 0 && !result.stdout().isBlank();
        } catch (GitCommandFailedException broken) {
            // The checkout is gone or unreadable: nothing can be running in it.
            return false;
        }
    }

    /** The git dir behind a worktree checkout, when it exists on disk. */
    private static Optional<Path> resolveGitdir(Path worktree) {
        Path dotGit = worktree.resolve(".git");
        if (Files.isDirectory(dotGit)) {
            return Optional.of(dotGit);
        }
        if (Files.isRegularFile(dotGit)) {
            try {
                String line = Files.readString(dotGit).strip();
                if (line.startsWith("gitdir:")) {
                    Path gitdir = Path.of(line.substring("gitdir:".length()).strip());
                    return Optional.of(gitdir.isAbsolute() ? gitdir : worktree.resolve(gitdir));
                }
            } catch (IOException e) {
                LOG.log(Level.DEBUG, "unreadable .git pointer in " + worktree, e);
            }
            return Optional.empty();
        }
        // No .git entry at all: a bare checkout is its own git dir.
        return Optional.of(worktree);
    }

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
            throw new GitCommandInterruptedException(command);
        }
    }
}
