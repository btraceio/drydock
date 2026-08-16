package app.drydock.app;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.HandoffBrief;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.git.GitCommandFailedException;
import app.drydock.git.GitCommandInterruptedException;
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.GitException;
import app.drydock.git.GitExecutableNotFoundException;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeNaming;
import app.drydock.git.WorktreeTransplant;
import app.drydock.handoff.ForkFacts;
import app.drydock.handoff.HandoffSeed;
import app.drydock.handoff.HandoffStaleness;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.stream.Stream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Forks a session onto a sibling worktree running a chosen agent.
 *
 * <p>Every switch is a fork. Nothing is switched in place, no live tab is
 * operated on, and the outgoing session -- its branch, its worktree, its
 * metadata -- is never written to. The whole operation is additive, so a fork
 * that fails cannot cost work, and the rollback below exists only for the one
 * thing a failure can leave behind: a half-populated destination.</p>
 */
public final class SessionForkService {

    private static final Logger LOG = System.getLogger(SessionForkService.class.getName());
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(60);

    /** How many suffixed branch names to try before giving up. */
    private static final int MAX_BRANCH_SUFFIX = 100;

    /** How long an unread seed file survives before {@link #sweepStaleSeeds} removes it. */
    private static final Duration SEED_RETENTION = Duration.ofDays(7);

    private static final FileAttribute<?> OWNER_ONLY_FILE =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    private static final FileAttribute<?> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

    /**
     * Longest commit and changed-file lists the seed carries. The commit list
     * was already bounded by {@code -n 20}; the file list was not, and {@code
     * git status --porcelain} in a tree with thousands of untracked files
     * would otherwise become thousands of bullet lines in the successor's very
     * first prompt.
     */
    private static final int MAX_COMMIT_SUBJECTS = 20;
    private static final int MAX_CHANGED_FILES = 50;

    /**
     * Starts the forked session.
     *
     * <p>A seam rather than a direct call into the workspace, for the same
     * reason {@code McpSessionContext} is one: the build has no mocking
     * library, and a service that reached into JavaFX could only be exercised
     * on the FX thread.</p>
     */
    public interface Launcher {
        /**
         * Opens the forked session and completes with its id.
         *
         * <p>Asynchronous because opening a session is FX-thread work while
         * {@link #forkBlocking} runs on a background thread. Returning a
         * future rather than hopping and joining inside the implementation
         * keeps the deadline in one place: this service already unwraps and
         * joins, and a second join inside the launcher would be a second
         * timeout to reason about.</p>
         */
        CompletableFuture<ManagedSessionId> start(Path worktree, AgentKind kind, String seedPrompt,
                                                  ManagedSessionId forkedFrom);
    }

    /**
     * Carries the outgoing worktree's dirty state onto the fork. A seam for
     * the same reason {@link Launcher} is: {@link WorktreeTransplant} is final
     * and does real git work, so a test that needs it to fail cannot subclass
     * it and should not have to break a real repository to get there.
     */
    public interface Transplanter {
        int transplant(Path source, Path destination);
    }

    private final GitStatusService gitStatusService;
    private final Transplanter transplant;
    private final GitExecutableLocator locator;
    private final Launcher launcher;
    private final Function<ManagedSessionId, Optional<HandoffBrief>> briefLookup;
    private final Function<ManagedAgentSession, Path> repositoryRootLookup;
    private final Function<String, Path> worktreeDirectory;
    private final Function<ManagedSessionId, List<String>> openIntentLookup;
    private final Path seedDirectory;
    private final Executor backgroundExecutor;

    public SessionForkService(GitStatusService gitStatusService,
                              Transplanter transplant,
                              GitExecutableLocator locator,
                              Launcher launcher,
                              Function<ManagedSessionId, Optional<HandoffBrief>> briefLookup,
                              Function<ManagedAgentSession, Path> repositoryRootLookup,
                              Function<String, Path> worktreeDirectory,
                              Function<ManagedSessionId, List<String>> openIntentLookup,
                              Path seedDirectory,
                              Executor backgroundExecutor) {
        this.gitStatusService = gitStatusService;
        this.transplant = transplant;
        this.locator = locator;
        this.launcher = launcher;
        this.briefLookup = briefLookup;
        this.repositoryRootLookup = repositoryRootLookup;
        this.worktreeDirectory = worktreeDirectory;
        this.openIntentLookup = openIntentLookup;
        this.seedDirectory = seedDirectory;
        this.backgroundExecutor = backgroundExecutor;
    }

    public CompletableFuture<ManagedSessionId> fork(ManagedAgentSession outgoing, AgentKind target) {
        return CompletableFuture.supplyAsync(() -> forkBlocking(outgoing, target), backgroundExecutor);
    }

    /**
     * Blocking; never call on the FX thread.
     *
     * <p>{@code target} may be the agent {@code outgoing} is already running:
     * a wedged process is a legitimate reason to fork even when the harness
     * itself is fine, and so is wanting a second opinion from the same
     * model.</p>
     */
    public ManagedSessionId forkBlocking(ManagedAgentSession outgoing, AgentKind target) {
        Path repositoryRoot = repositoryRootLookup.apply(outgoing);
        Path sourceWorktree = outgoing.workingDirectory();
        String baseBranch = branchOf(sourceWorktree).orElse("HEAD");
        String branch = availableBranchName(repositoryRoot, baseBranch, target);

        // An unborn branch has no HEAD to fork from, so the worktree is cut
        // from the repository's current HEAD instead and the seed says so.
        Optional<String> head = gitStatusService.headCommitBlocking(sourceWorktree);
        Path created = join(gitStatusService.createWorktree(
                repositoryRoot, worktreeDirectory.apply(branch), branch, head));

        try {
            initSubmodules(created);
            transplant.transplant(sourceWorktree, created);
            String seed = HandoffSeed.compose(briefLookup.apply(outgoing.id()),
                    factsFor(outgoing, sourceWorktree, branch, baseBranch, head));
            return join(launcher.start(created, target, seedPointer(seed, branch), outgoing.id()));
        } catch (RuntimeException e) {
            rollback(repositoryRoot, created, branch);
            throw e;
        }
    }

    /**
     * How far {@code session}'s work has moved since its brief was written.
     * Blocking; never call on the FX thread.
     */
    public HandoffStaleness stalenessBlocking(ManagedAgentSession session) {
        Optional<HandoffBrief> brief = briefLookup.apply(session.id());
        if (brief.isEmpty()) {
            return HandoffStaleness.of(Optional.empty(), 0, 0);
        }
        Path worktree = session.workingDirectory();
        Optional<String> since = brief.get().writtenAtCommit();
        if (since.isEmpty()) {
            // Written when the branch had no commits: there is no range to
            // count from, so every uncommitted file is the whole story.
            return HandoffStaleness.of(brief, 0, countLines(gitOut(worktree, "status", "--porcelain")));
        }
        int commits = parseCountOrZero(gitOut(worktree, "rev-list", "--count",
                "--end-of-options", since.get() + "..HEAD"));
        // Tracked changes since the brief, PLUS untracked non-ignored files.
        // git diff lists neither, and a session that spent an hour writing
        // twenty brand-new files has moved exactly as far as one that edited
        // twenty existing ones -- the unstamped branch above already counts
        // both, so leaving them out here would make the two measurements
        // disagree about what "work done" means.
        int files = countLines(gitOut(worktree, "diff", "--name-only", "--end-of-options", since.get()))
                + countLines(gitOut(worktree, "ls-files", "--others", "--exclude-standard"));
        return HandoffStaleness.of(brief, commits, files);
    }

    /**
     * Unwraps {@link CompletionException} so callers see the git failure
     * itself rather than a wrapper -- the message is what the human reads.
     */
    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    private ForkFacts factsFor(ManagedAgentSession outgoing, Path sourceWorktree, String branch,
                               String baseBranch, Optional<String> head) {
        List<String> subjects = head.isEmpty()
                ? List.of()
                : lines(gitOut(sourceWorktree, "log", "--format=%s",
                        "-n", String.valueOf(MAX_COMMIT_SUBJECTS), "HEAD"));
        List<String> changed = capped(lines(gitOut(sourceWorktree, "status", "--porcelain")),
                MAX_CHANGED_FILES);
        // The intents the human has not settled are the most concrete
        // statement of what is still open; a successor that re-litigates a
        // resolved one is doing the work twice.
        return new ForkFacts(branch, baseBranch, subjects, changed,
                capped(openIntentLookup.apply(outgoing.id()), MAX_CHANGED_FILES));
    }

    /**
     * The first {@code limit} entries, with a final line saying how many were
     * left out -- a truncated list that does not say it is truncated would
     * read to the successor as the whole of the tree's dirty state.
     */
    private static List<String> capped(List<String> items, int limit) {
        if (items.size() <= limit) {
            return items;
        }
        List<String> capped = new ArrayList<>(items.subList(0, limit));
        capped.add("… and " + (items.size() - limit) + " more");
        return List.copyOf(capped);
    }

    /**
     * Writes the seed to a file and returns the ONE LINE to type at the
     * successor instead.
     *
     * <p>The seed cannot be delivered as the prompt itself. A prompt reaches a
     * session as real keystrokes, and an embedded newline submits the line
     * before it -- which is why {@code MainWorkspace.sendTaskWhenReady}
     * collapses whitespace before typing. Run the seed through that and its
     * headings, blank lines and bullets become one run-on line, destroying the
     * separation of the previous session's testimony from the facts drydock
     * derived, which is a mitigation rather than formatting.</p>
     *
     * <p>So the structure lives in a file and the prompt is a pointer. The
     * file is written OUTSIDE the worktree, in drydock's own state directory:
     * putting it in the tree would show up in the fork's very first diff and
     * in the review rail, and the spec rejected a worktree file as the brief's
     * home for that reason. This is a delivery artifact for one launch, not
     * the store.</p>
     *
     * <p>Owner-only, like the MCP config: a brief can quote anything the
     * previous session was working on.</p>
     *
     * <p>The successor is asked to delete it once read. That is a request to
     * an agent, so it is not a guarantee -- {@link #sweepStaleSeeds} is the
     * half that does not depend on cooperation.</p>
     */
    private String seedPointer(String seed, String branch) {
        try {
            Files.createDirectories(seedDirectory, OWNER_ONLY_DIRECTORY);
        } catch (FileAlreadyExistsException existing) {
            // Tighten a directory left by an earlier run (or by a version that
            // inherited the umask) rather than trusting that it exists.
            try {
                Files.setPosixFilePermissions(seedDirectory, PosixFilePermissions.fromString("rwx------"));
            } catch (IOException e) {
                return undeliverableSeed(e);
            }
        } catch (IOException e) {
            return undeliverableSeed(e);
        }

        try {
            Path file = Files.createTempFile(seedDirectory, "handoff-" + WorktreeNaming.slug(branch) + "-",
                    ".md", OWNER_ONLY_FILE);
            Files.writeString(file, seed, StandardCharsets.UTF_8);
            // Deliberately one line, with no tab or newline anywhere in it: it
            // has to survive being whitespace-collapsed and typed.
            return "Read your handoff brief at " + file + " -- it explains what this session inherited "
                    + "and what to do next. Delete that file once you have read it, then take the next step.";
        } catch (IOException e) {
            return undeliverableSeed(e);
        }
    }

    /**
     * What to say when the brief could not be handed over at all. The fork
     * itself is still worth having -- the tree, branch and commits are real --
     * so this degrades to a session that knows it is missing context, rather
     * than failing the whole operation.
     */
    private static String undeliverableSeed(IOException cause) {
        LOG.log(Level.WARNING, () -> "Could not write the handoff seed: " + cause.getMessage());
        return "You are continuing work another agent started, but drydock could not write its handoff "
                + "brief to disk, so none is available. Read the diff before changing direction.";
    }

    /**
     * Deletes seed files older than {@link #SEED_RETENTION}. The successor is
     * asked to remove its own, but an agent that ignores the request, crashes,
     * or never reads the file would otherwise leave a brief on disk forever.
     */
    public void sweepStaleSeeds(Instant now) {
        if (!Files.isDirectory(seedDirectory)) {
            return;
        }
        try (Stream<Path> files = Files.list(seedDirectory)) {
            for (Path file : files.toList()) {
                try {
                    if (Files.getLastModifiedTime(file).toInstant().isBefore(now.minus(SEED_RETENTION))) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException e) {
                    LOG.log(Level.DEBUG, () -> "Could not sweep " + file + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.log(Level.DEBUG, () -> "Could not list " + seedDirectory + ": " + e.getMessage());
        }
    }

    /**
     * A fresh worktree's submodules are empty, and a successor that inherits a
     * repository which will not build has been handed a different problem than
     * the one it was briefed on. Local only -- the objects already live in the
     * shared {@code .git/modules}, so this never touches the network.
     */
    private void initSubmodules(Path worktree) {
        ProcessResult result = run(List.of(git().toString(), "-C", worktree.toString(),
                "submodule", "update", "--init", "--recursive"));
        if (result.exitCode() != 0) {
            throw new GitCommandFailedException(List.of("git", "submodule", "update", "--init"),
                    result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
    }

    /**
     * Best-effort, and deliberately swallowing: a rollback that itself fails
     * must not mask the original failure, which is the one the human needs to
     * read.
     */
    private void rollback(Path repositoryRoot, Path worktree, String branch) {
        try {
            run(List.of(git().toString(), "-C", repositoryRoot.toString(),
                    "worktree", "remove", "--force", worktree.toString()));
            run(List.of(git().toString(), "-C", repositoryRoot.toString(), "branch", "-D", branch));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Could not roll back the failed fork at " + worktree + ": " + e);
        }
    }

    /** {@code <outgoing branch>-<agent>}, suffixed until free. */
    private String availableBranchName(Path repositoryRoot, String baseBranch, AgentKind target) {
        String base = baseBranch + "-" + target.persistedName();
        if (!branchExists(repositoryRoot, base)) {
            return base;
        }
        for (int suffix = 2; suffix < MAX_BRANCH_SUFFIX; suffix++) {
            String candidate = base + "-" + suffix;
            if (!branchExists(repositoryRoot, candidate)) {
                return candidate;
            }
        }
        throw new GitCommandFailedException(List.of("git", "branch"), -1,
                "no free branch name near " + base);
    }

    private boolean branchExists(Path repositoryRoot, String branch) {
        ProcessResult result = run(List.of(git().toString(), "-C", repositoryRoot.toString(),
                "rev-parse", "--verify", "--quiet", "refs/heads/" + branch));
        return result.exitCode() == 0;
    }

    private Optional<String> branchOf(Path worktree) {
        String name = gitOut(worktree, "rev-parse", "--abbrev-ref", "HEAD").strip();
        return name.isEmpty() || name.equals("HEAD") ? Optional.empty() : Optional.of(name);
    }

    /**
     * Runs git in {@code worktree} and returns stdout, or {@code ""} when the
     * command does not succeed. Degrading rather than throwing is deliberate:
     * a brief whose commit no longer exists (history rewritten under it) must
     * read as "not stale" rather than break the tab it is drawn in.
     *
     * <p>"Does not succeed" has to cover a git that never ran, not only one
     * that exited non-zero: a worktree on a stalled mount makes {@code run}
     * throw (timeout, or a launch failure), and that exception would come out
     * of {@link #stalenessBlocking} -- which the banner calls -- in exactly
     * the case this method exists to absorb. Locating the binary stays
     * outside: no git at all is a real failure, not a stale brief.</p>
     */
    private String gitOut(Path worktree, String... args) {
        List<String> command = new ArrayList<>(List.of(git().toString(), "-C", worktree.toString()));
        command.addAll(List.of(args));
        try {
            ProcessResult result = run(command);
            return result.exitCode() == 0 ? result.stdout() : "";
        } catch (GitException e) {
            LOG.log(Level.DEBUG, () -> "git did not run in " + worktree + ": " + e.getMessage());
            return "";
        }
    }

    private Path git() {
        return locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));
    }

    private static List<String> lines(String output) {
        List<String> lines = new ArrayList<>();
        for (String line : output.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return List.copyOf(lines);
    }

    private static int countLines(String output) {
        return lines(output).size();
    }

    private static int parseCountOrZero(String output) {
        try {
            return Integer.parseInt(output.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static ProcessResult run(List<String> command) {
        try {
            return ProcessRunner.run(command, new ProcessRunner.Options(null, PROCESS_TIMEOUT, true, Map.of()));
        } catch (IOException e) {
            throw new GitCommandFailedException(command, -1, e.getMessage() == null ? "" : e.getMessage());
        } catch (ProcessTimeoutException e) {
            // Translated like GitStatusService does: a hang that escaped as a
            // raw ProcessTimeoutException would slip past every `catch
            // (GitException)` in this package.
            throw new GitCommandFailedException(command, -1,
                    "timed out after " + PROCESS_TIMEOUT.toSeconds() + "s (killed)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitCommandInterruptedException(command);
        }
    }
}
