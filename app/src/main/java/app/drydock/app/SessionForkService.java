package app.drydock.app;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.HandoffBrief;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.git.GitCommandFailedException;
import app.drydock.git.GitCommandInterruptedException;
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.GitExecutableNotFoundException;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeTransplant;
import app.drydock.handoff.ForkFacts;
import app.drydock.handoff.HandoffSeed;
import app.drydock.handoff.HandoffStaleness;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;

import java.io.IOException;
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
import java.util.concurrent.ExecutorService;
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
        ManagedSessionId start(Path worktree, AgentKind kind, String seedPrompt, ManagedSessionId forkedFrom);
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
    private final ExecutorService backgroundExecutor;

    public SessionForkService(GitStatusService gitStatusService,
                              Transplanter transplant,
                              GitExecutableLocator locator,
                              Launcher launcher,
                              Function<ManagedSessionId, Optional<HandoffBrief>> briefLookup,
                              Function<ManagedAgentSession, Path> repositoryRootLookup,
                              Function<String, Path> worktreeDirectory,
                              ExecutorService backgroundExecutor) {
        this.gitStatusService = gitStatusService;
        this.transplant = transplant;
        this.locator = locator;
        this.launcher = launcher;
        this.briefLookup = briefLookup;
        this.repositoryRootLookup = repositoryRootLookup;
        this.worktreeDirectory = worktreeDirectory;
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
                    factsFor(sourceWorktree, branch, baseBranch, head));
            return launcher.start(created, target, seed, outgoing.id());
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
        int files = countLines(gitOut(worktree, "diff", "--name-only", "--end-of-options", since.get()));
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

    private ForkFacts factsFor(Path sourceWorktree, String branch, String baseBranch, Optional<String> head) {
        List<String> subjects = head.isEmpty()
                ? List.of()
                : lines(gitOut(sourceWorktree, "log", "--format=%s",
                        "-n", String.valueOf(MAX_COMMIT_SUBJECTS), "HEAD"));
        List<String> changed = capped(lines(gitOut(sourceWorktree, "status", "--porcelain")),
                MAX_CHANGED_FILES);
        return new ForkFacts(branch, baseBranch, subjects, changed, List.of());
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
     * Runs git in {@code worktree} and returns stdout, or {@code ""} on a
     * non-zero exit. Degrading rather than throwing is deliberate: a brief
     * whose commit no longer exists (history rewritten under it) must read as
     * "not stale" rather than break the tab it is drawn in.
     */
    private String gitOut(Path worktree, String... args) {
        List<String> command = new ArrayList<>(List.of(git().toString(), "-C", worktree.toString()));
        command.addAll(List.of(args));
        ProcessResult result = run(command);
        return result.exitCode() == 0 ? result.stdout() : "";
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitCommandInterruptedException(command);
        }
    }
}
