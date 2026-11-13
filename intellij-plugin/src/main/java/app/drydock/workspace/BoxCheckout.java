package app.drydock.workspace;

import app.drydock.git.BranchNameRules;
import app.drydock.github.GitHubLinkService;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import app.drydock.process.SshCommandBuilder;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Prepares the box-side checkout an agent session runs in. Three steps,
 * in order: discover the workspace box's auto-clone of the project (never
 * a hardcoded path — where {@code workspaces create} lands the clone was
 * never established, so it is found by scanning the box's {@code $HOME}
 * for the one checkout whose origin matches the project's), keep
 * Drydock's worktrees out of the clone's dirty state by listing
 * {@code .drydock-worktrees/} in {@code <clone>/.git/info/exclude}, and
 * for a new-session launch create the session's own box-side worktree
 * under {@code <clone>/.drydock-worktrees/<branch>}. A main-checkout
 * launch uses the clone itself.
 *
 * <p>Every command runs on the box over the batch ssh seam — the argv is
 * built by {@link SshCommandBuilder#remoteCommand} (INV-7: one builder,
 * {@code --} before the host, POSIX single-quoting) and executed through
 * {@link CommandRunner}, an argument list with an explicit timeout (INV-5),
 * never on the EDT. Two commands need the remote shell to do more than
 * quote-echo words: the discovery {@code find} must expand {@code $HOME}
 * on the box, and appending to {@code .git/info/exclude} needs the {@code
 * >>} operator. Both run as {@code sh -c '<fixed script>' '<arg0>'
 * '<arg1>'…} — the script is a fixed literal and every variable value
 * arrives as a shell positional parameter, so nothing user-derived is
 * ever interpolated into shell syntax. The git commands themselves stay
 * plain quoted word lists, with {@code --end-of-options} before the
 * worktree positionals and {@code --} before the {@code cat} path (INV-6).
 *
 * <p>All output is parsed bounded (INV-20): the shared output cap below,
 * and the {@code find} listing additionally at {@value #MAX_FIND_RESULTS}
 * lines — beyond the bound the discovery refuses rather than deciding
 * from a partial scan. Every failure reuses {@link BoxShell.BoxResult}'s
 * five typed classes and is logged as its own WARNING with the excerpt
 * (INV-9); the discovery scan is the one place where a per-candidate
 * {@code git remote get-url} exit is a <em>classification</em>, not a
 * failure — a checkout with no matching origin is a non-match, exactly
 * like a {@code has-session} exit 1 means DEAD.
 *
 * <p>The discovered clone is cached per repository in the {@code .clone}
 * settings key (ASM-17's manual escape hatch edits the same key), so the
 * scan runs once per box. The cache is injected as {@link CloneCache}
 * because the settings store lives in the platform layer; the launcher
 * binds it, and this class never touches IDE state (INV-26).
 */
public final class BoxCheckout {

    private static final Logger LOG = System.getLogger(BoxCheckout.class.getName());

    /** ssh's own failure exit; every other exit code is the remote command's, forwarded by ssh. */
    private static final int SSH_FAILURE_EXIT = 255;

    /** Every command's output cap (INV-20), the same bound the rest of the box seam uses. */
    public static final int MAX_OUTPUT_BYTES = 256 * 1024;

    /** The discovery {@code find} bound (INV-20): more checkouts than this and the scan refuses. */
    public static final int MAX_FIND_RESULTS = 200;

    /** One bounded walk of the box's {@code $HOME} at depth 4. */
    public static final Duration FIND_TIMEOUT = Duration.ofSeconds(60);

    /** One git config lookup per candidate checkout. */
    public static final Duration GIT_QUERY_TIMEOUT = Duration.ofSeconds(10);

    /** Reading or appending the clone's {@code .git/info/exclude}. */
    public static final Duration EXCLUDE_TIMEOUT = Duration.ofSeconds(10);

    /** {@code git fetch --quiet origin} in the box clone — network in the loop, clone-scale budget. */
    public static final Duration FETCH_TIMEOUT = Duration.ofMinutes(3);

    /** {@code git worktree add} in the box clone — object checkout, clone-scale budget. */
    public static final Duration WORKTREE_TIMEOUT = Duration.ofMinutes(3);

    /** Drydock's worktree directory inside the box clone. */
    public static final String WORKTREES_DIR = ".drydock-worktrees";

    /** The line {@link #prepare} keeps in {@code <clone>/.git/info/exclude}. */
    public static final String EXCLUDE_LINE = WORKTREES_DIR + "/";

    /**
     * The discovery scan: every {@code .git} directory under the box's
     * {@code $HOME} at depth 4. A shell script fixed literal — {@code $HOME}
     * must expand on the box, and a quoted word list could not do that;
     * every later value rides a positional parameter instead.
     */
    private static final String FIND_SCRIPT = "find \"$HOME\" -maxdepth 4 -name .git -type d";

    /**
     * Appends {@code $1} as one line to {@code $2}: the {@code >>} operator
     * needs a shell, so the fixed script runs under {@code sh -c} with both
     * values as positional parameters.
     */
    private static final String APPEND_EXCLUDE_SCRIPT = "echo \"$1\" >> \"$2\"";

    /** {@code sh}'s {@code $0} for the positional-parameter scripts; never read. */
    private static final String SCRIPT_ARG_ZERO = "drydock";

    private final CommandRunner runner;

    /** @param runner the seam every box-side command runs through; tests inject fakes. */
    public BoxCheckout(CommandRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /** What {@link #prepare} must set up on the box clone. */
    public sealed interface Work permits Work.MainCheckout, Work.NewWorktree {

        /** Use the clone itself as the session's working path. */
        record MainCheckout() implements Work {
        }

        /** Create a fresh box-side worktree under {@code <clone>/.drydock-worktrees/<branch>}. */
        record NewWorktree(String branch, Optional<String> startRef) implements Work {

            public NewWorktree {
                Objects.requireNonNull(branch, "branch");
                Objects.requireNonNull(startRef, "startRef");
            }
        }
    }

    /** {@link #prepare}'s answer: the working path, or the reason it refused. */
    public sealed interface Outcome permits Outcome.Ready, Outcome.Refused {

        /**
         * The checkout is ready: {@code workingPath} is where the session
         * runs — the clone itself, or its {@code .drydock-worktrees}
         * worktree.
         */
        record Ready(String clonePath, String workingPath) implements Outcome {
        }

        /**
         * Human-readable refusal. When several checkouts matched, the
         * candidates are surfaced in the reason (ASM-17) so the user can
         * pick one in the Workspace box dialog's {@code .clone} override.
         */
        record Refused(String reason) implements Outcome {
        }
    }

    /**
     * The per-repository cache of the discovered clone path — the
     * {@code .clone} settings key. Read before any scan (a cached or
     * user-overridden path is trusted as-is) and written exactly once,
     * when discovery accepts a single clone. The settings store lives in
     * the platform layer, so the launcher binds this interface to it;
     * core stays free of IDE state.
     */
    public interface CloneCache {

        /** The cached clone path, empty when discovery must run. */
        Optional<String> cached();

        /** Records a freshly discovered clone path. */
        void store(String clonePath);
    }

    /**
     * Runs the box-side checkout recipe. Blocking — never call on the EDT;
     * {@link InterruptedException} propagates so the caller's cancellation
     * path (which already destroyed the child) owns what happens next.
     *
     * @param host   the box's ssh alias ({@code workspace-<name>})
     * @param origin the project's GitHub identity, already parsed from the
     *               project's own origin by the caller
     * @param work   what to set up: the clone itself, or a fresh worktree
     * @param cache  the per-repo {@code .clone} key, read first and stored
     *               on a first successful discovery
     */
    public Outcome prepare(String host, GitHubLinkService.Remote origin, Work work, CloneCache cache)
            throws InterruptedException {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(cache, "cache");
        String clone = cache.cached().filter(path -> !path.isBlank()).orElse(null);
        if (clone == null) {
            Discovery discovered = discoverClone(host, origin);
            if (discovered.refusal() != null) {
                return discovered.refusal();
            }
            clone = discovered.clonePath();
            cache.store(clone);
        }
        Outcome.Refused excludeFailure = ensureExcludeLine(host, clone);
        if (excludeFailure != null) {
            return excludeFailure;
        }
        return switch (work) {
            case Work.MainCheckout() -> new Outcome.Ready(clone, clone);
            case Work.NewWorktree newWorktree -> newWorktree(host, clone, newWorktree);
        };
    }

    /**
     * Exactly one field is non-null: the accepted clone root, or the
     * refusal that ended the scan.
     */
    private record Discovery(String clonePath, Outcome.Refused refusal) {

        static Discovery found(String clone) {
            return new Discovery(clone, null);
        }

        static Discovery refused(Outcome.Refused refusal) {
            return new Discovery(null, refusal);
        }

        static Discovery refusedString(String reason) {
            return refused(new Outcome.Refused(reason));
        }
    }

    /**
     * Finds the box's auto-clone of the project: every {@code .git}
     * directory under the box's {@code $HOME} (bounded), then
     * {@code git -C <dir> remote get-url origin} for each, parsed by the
     * one GitHub remote parser. Exactly one matching checkout is accepted;
     * zero or several refuse, the several-case with the candidates
     * surfaced (ASM-17). Owner and repo compare case-insensitively because
     * GitHub names are — a clone that spells the project differently must
     * not be missed over letter case.
     */
    private Discovery discoverClone(String host, GitHubLinkService.Remote origin) throws InterruptedException {
        BoxShell.BoxResult listing = run(host, List.of("sh", "-c", FIND_SCRIPT), FIND_TIMEOUT);
        List<String> gitDirs = new ArrayList<>();
        if (listing instanceof BoxShell.BoxResult.Ok(String stdout)) {
            for (String line : stdout.split("\n", -1)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (!trimmed.endsWith("/.git")) {
                    return Discovery.refusedString(
                            "the box's checkout listing returned an unexpected line: " + trimmed);
                }
                gitDirs.add(trimmed);
            }
            if (gitDirs.size() > MAX_FIND_RESULTS) {
                return Discovery.refusedString("the box has more than " + MAX_FIND_RESULTS
                        + " checkouts under its $HOME — the project's clone cannot be identified safely");
            }
        } else {
            return Discovery.refused(stepFailed(host, "discovering the box's checkouts", listing));
        }

        List<String> matches = new ArrayList<>();
        for (String gitDir : gitDirs) {
            BoxShell.BoxResult url = run(host,
                    List.of("git", "-C", gitDir, "remote", "get-url", "origin"), GIT_QUERY_TIMEOUT);
            if (url instanceof BoxShell.BoxResult.Ok(String value)) {
                if (GitHubLinkService.parseRemote(value)
                        .filter(remote -> sameRepository(remote, origin)).isPresent()) {
                    // find yields the .git directory; the clone the session
                    // works in is its parent, the work tree.
                    matches.add(gitDir.substring(0, gitDir.length() - "/.git".length()));
                }
            } else if (url instanceof BoxShell.BoxResult.RemoteFailed(int exit, String ignored)) {
                if (exit < 0) {
                    // The output-cap refusal (-1): a get-url answer too large
                    // to bound is a failed scan, not a non-match (INV-20).
                    return Discovery.refused(stepFailed(host, "reading the origin of " + gitDir, url));
                }
                // Any other non-zero get-url is this candidate's answer —
                // "no such remote" (exit 2), "not a git repository" (128) —
                // and a checkout without an origin to compare cannot match.
                LOG.log(Level.DEBUG, host + ": " + gitDir + " has no origin to compare: " + url.describe());
            } else {
                // Unreachable, timeout, or ssh missing: the scan cannot
                // continue on this box at all.
                return Discovery.refused(stepFailed(host, "reading the origin of " + gitDir, url));
            }
        }

        String project = origin.owner() + "/" + origin.repo();
        if (matches.isEmpty()) {
            return Discovery.refusedString("no checkout of " + project
                    + " exists under the box's $HOME — set the clone path in the Workspace box dialog");
        }
        if (matches.size() > 1) {
            return Discovery.refusedString("more than one checkout of " + project
                    + " exists under the box's $HOME — set one of them in the Workspace box dialog: "
                    + String.join(", ", matches));
        }
        return Discovery.found(matches.get(0));
    }

    /**
     * Keeps {@link #EXCLUDE_LINE} in {@code <clone>/.git/info/exclude} so
     * Drydock's worktrees never show up in the clone's status. Idempotent:
     * the file is read first and the append runs only when the line is
     * absent. Returns the refusal when a step failed, {@code null} when
     * the line is (now) listed.
     */
    private Outcome.Refused ensureExcludeLine(String host, String clone) throws InterruptedException {
        String excludePath = clone + "/.git/info/exclude";
        BoxShell.BoxResult read = run(host, List.of("cat", "--", excludePath), EXCLUDE_TIMEOUT);
        String content;
        if (read instanceof BoxShell.BoxResult.Ok(String stdout)) {
            content = stdout;
        } else if (read instanceof BoxShell.BoxResult.RemoteFailed(int exit, String excerpt)
                && exit == 1 && excerpt.contains("No such file")) {
            // info/exclude comes from git's clone template; a missing file
            // means "nothing excluded yet", and the append below recreates
            // it — a classified answer, never a silent empty read.
            content = "";
        } else {
            return stepFailed(host, "reading " + excludePath + " on the box", read);
        }
        for (String line : content.split("\n", -1)) {
            if (line.strip().equals(EXCLUDE_LINE)) {
                return null;
            }
        }
        BoxShell.BoxResult append = run(host, List.of("sh", "-c", APPEND_EXCLUDE_SCRIPT,
                SCRIPT_ARG_ZERO, EXCLUDE_LINE, excludePath), EXCLUDE_TIMEOUT);
        if (!(append instanceof BoxShell.BoxResult.Ok)) {
            return stepFailed(host, "listing " + EXCLUDE_LINE + " in " + excludePath, append);
        }
        return null;
    }

    /**
     * Creates the session's box-side worktree: the branch is checked with
     * {@link BranchNameRules#check} before any network runs ({@code origin}
     * is the only remote the check can know — discovery proved it exists,
     * and it is the one every session branch name risks shadowing), then
     * the clone fetches, then one {@code git worktree add} with
     * {@code --end-of-options} before the two positionals (INV-6).
     */
    private Outcome newWorktree(String host, String clone, Work.NewWorktree work) throws InterruptedException {
        Optional<BranchNameRules.Refusal> refusal = BranchNameRules.check(work.branch(), List.of("origin"));
        if (refusal.isPresent()) {
            return new Outcome.Refused(BranchNameRules.humanSentence(refusal.get()));
        }
        BoxShell.BoxResult fetch = run(host,
                List.of("git", "-C", clone, "fetch", "--quiet", "origin"), FETCH_TIMEOUT);
        if (!(fetch instanceof BoxShell.BoxResult.Ok)) {
            return stepFailed(host, "fetching origin in the box clone", fetch);
        }
        String worktreePath = clone + "/" + WORKTREES_DIR + "/" + work.branch();
        List<String> add = new ArrayList<>(List.of("git", "-C", clone,
                "worktree", "add", "-b", work.branch(), "--end-of-options", worktreePath));
        work.startRef().filter(start -> !start.isBlank()).ifPresent(add::add);
        BoxShell.BoxResult created = run(host, add, WORKTREE_TIMEOUT);
        if (!(created instanceof BoxShell.BoxResult.Ok)) {
            return stepFailed(host, "creating the box-side worktree " + worktreePath, created);
        }
        return new Outcome.Ready(clone, worktreePath);
    }

    private static boolean sameRepository(GitHubLinkService.Remote found, GitHubLinkService.Remote expected) {
        return found.owner().equalsIgnoreCase(expected.owner())
                && found.repo().equalsIgnoreCase(expected.repo());
    }

    /**
     * Logs the failure as its own WARNING and shapes the refusal that ends
     * the step: the typed class's own {@code describe()} text (INV-9),
     * excerpt included.
     */
    private Outcome.Refused stepFailed(String host, String step, BoxShell.BoxResult failure) {
        LOG.log(Level.WARNING, host + ": " + step + ": " + failure.describe());
        return new Outcome.Refused(step + " failed: " + failure.describe());
    }

    /**
     * Runs one remote command on the box and classifies its outcome into
     * the box seam's five typed classes ({@link BoxShell.BoxResult});
     * same classification contract as the rest of the seam, applied to the
     * checkout recipe's commands. The output cap bounds everything the
     * recipe parses (INV-20); a spawn {@link IOException} is "not
     * installed", except the cap's own refusal, which is a failed run.
     */
    private BoxShell.BoxResult run(String host, List<String> remoteArgs, Duration timeout)
            throws InterruptedException {
        List<String> argv = SshCommandBuilder.remoteCommand(host, remoteArgs);
        try {
            ProcessResult result = runner.run(argv,
                    new ProcessRunner.Options(null, timeout, true, Map.of()), MAX_OUTPUT_BYTES);
            if (result.exitCode() == 0) {
                return new BoxShell.BoxResult.Ok(result.stdout());
            }
            String excerpt = ProcessRunner.excerpt(
                    result.stderr().isBlank() ? result.stdout() : result.stderr());
            if (result.exitCode() == SSH_FAILURE_EXIT) {
                return new BoxShell.BoxResult.Unreachable(excerpt);
            }
            return new BoxShell.BoxResult.RemoteFailed(result.exitCode(), excerpt);
        } catch (ProcessTimeoutException e) {
            return new BoxShell.BoxResult.Timeout(timeout);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("exceeded")) {
                // ProcessRunner's output-cap refusal: a failed run, typed and
                // logged like any other remote failure, never a missing tool.
                return new BoxShell.BoxResult.RemoteFailed(-1, ProcessRunner.excerpt(e.getMessage()));
            }
            return new BoxShell.BoxResult.SshNotInstalled(argv.get(0));
        }
    }
}
