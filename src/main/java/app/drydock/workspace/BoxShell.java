package app.drydock.workspace;

import app.drydock.domain.ManagedSessionId;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import app.drydock.process.SshCommandBuilder;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The ssh/scp seam to a workspace box: every box-side batch command —
 * reachability probe, {@code tmux ls}, {@code tmux has-session}, {@code
 * tmux kill-session} — and every scp copy runs through here, through
 * {@link CommandRunner}, as an argument list with an explicit timeout
 * (INV-5), never a shell string and never on the EDT. The command lines
 * themselves are built in {@link SshCommandBuilder} (INV-7), which owns the
 * {@code --} before the host and the POSIX quoting.
 *
 * <p>Every failure mode is typed, never conflated (INV-9, AC-20): a failed
 * command is {@link BoxResult.RemoteFailed} or {@link BoxResult.Unreachable},
 * a hung one is {@link BoxResult.Timeout}, a missing tool is {@link
 * BoxResult.SshNotInstalled} — and each class logs its own WARNING with a
 * distinct {@link BoxResult#describe()} line carrying the stderr excerpt.
 * The only outcomes that are <em>not</em> failures are the two the
 * workspace-persistent-sessions investigation established as valid liveness
 * answers: a {@code has-session} exit 1 carrying a session-gone marker
 * (DEAD) and a {@code tmux ls} no-server response (the empty set) — those
 * are classified, not warned about.</p>
 */
public final class BoxShell {

    private static final Logger LOG = System.getLogger(BoxShell.class.getName());

    /** ssh's own failure exit; every other exit code is the remote command's, forwarded by ssh. */
    private static final int SSH_FAILURE_EXIT = 255;

    /**
     * The budget for the probe, has-session and kill-session: each is a
     * one-word round trip, so anything slower is a box that will not serve
     * the caller anyway.
     */
    public static final Duration SHORT_COMMAND_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Every box call's output cap (INV-20): has-session and kill-session
     * stderr and the {@code tmux ls} listing are all refused beyond this;
     * {@link TmuxListing} adds the line bound on top.
     */
    public static final int MAX_OUTPUT_BYTES = 256 * 1024;

    /**
     * What every {@link BoxResult.Unreachable} text must surface (ASM-9):
     * an unreachable box alias is fixed by the user's {@code workspaces
     * ssh-config}, not by anything this plugin may do — it never edits the
     * user's ssh config.
     */
    public static final String SSH_CONFIG_HINT = "run `workspaces ssh-config`";

    /**
     * The stderr text that means "the session is not there", not "the probe
     * failed": tmux's message for a missing session plus the two no-server
     * forms. With {@code remain-on-exit off} (forced by the persistent
     * session command), has-session is the exact liveness signal — a session
     * whose command exited is gone, so these markers are the whole DEAD story.
     */
    private static final List<String> SESSION_GONE_MARKERS = List.of(
            "can't find session", "no server running", "error connecting to");

    private final CommandRunner runner;

    public BoxShell(CommandRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /**
     * The typed outcome of one box-side command — five classes, each with a
     * distinct {@link #describe()} WARNING line (INV-9). A failed command is
     * never an empty result: callers read the failure class, never a
     * zero-length stdout as "nothing happened".
     */
    public sealed interface BoxResult {

        /** The one line this class logs as a WARNING when it occurs; distinct per class (AC-20). */
        String describe();

        /** Exit 0, with the drained stdout. */
        record Ok(String stdout) implements BoxResult {
            @Override
            public String describe() {
                return "box command succeeded";
            }
        }

        /** A non-zero remote exit (not ssh's own 255): the remote command refused or failed. */
        record RemoteFailed(int exitCode, String excerpt) implements BoxResult {
            @Override
            public String describe() {
                return "box command failed (exit " + exitCode + "): " + excerpt;
            }
        }

        /**
         * ssh exit 255 — the box itself could not be reached. The error text
         * surfaces the {@link #SSH_CONFIG_HINT} (ASM-9).
         */
        record Unreachable(String excerpt) implements BoxResult {
            @Override
            public String describe() {
                return "box unreachable (ssh exit 255): " + excerpt + " — " + SSH_CONFIG_HINT;
            }
        }

        /** The run hung past its timeout; the child was forcibly destroyed. */
        record Timeout(Duration timeout) implements BoxResult {
            @Override
            public String describe() {
                return "box command timed out after " + timeout.toSeconds() + " s";
            }
        }

        /** The tool could not even be spawned — ssh or scp missing from the PATH. */
        record SshNotInstalled(String tool) implements BoxResult {
            @Override
            public String describe() {
                return tool + " is not installed";
            }
        }
    }

    /**
     * The verdict of one {@code has-session} probe: ALIVE, DEAD, or UNKNOWN
     * with the reason. DEAD is a valid answer from a successful probe — the
     * exact liveness signal under {@code remain-on-exit off} — while
     * UNKNOWN means the probe itself could not conclude (unreachable box,
     * timeout, unrecognized exit), and its reason is what callers log.
     */
    public sealed interface Liveness {

        Liveness ALIVE = new Alive();
        Liveness DEAD = new Dead();

        record Alive() implements Liveness {
        }

        record Dead() implements Liveness {
        }

        /** The probe could not conclude; carries the distinct failure text (excerpt, timeout, missing tool). */
        record Unknown(String reason) implements Liveness {
        }
    }

    /**
     * One reachability probe: a batch ssh running the remote no-op
     * {@code true} — the cheapest command that still proves the alias
     * resolves, the connection authenticates, and a remote shell executes.
     * {@link BoxResult.Ok} is the only good answer; every failure class is
     * logged with its own WARNING (AC-20).
     */
    public BoxResult probe(String host) throws InterruptedException {
        BoxResult result = run(SshCommandBuilder.remoteCommand(host, List.of("true")),
                SHORT_COMMAND_TIMEOUT);
        warnUnlessOk(host, result);
        return result;
    }

    /**
     * The exact liveness signal for one session: {@code tmux has-session -t
     * drydock-<id>} over the batch seam, 10 s budget. Exit 0 → ALIVE; exit 1
     * carrying one of the session-gone markers → DEAD; every other outcome →
     * UNKNOWN, logged as a WARNING with the failure text.
     */
    public Liveness hasSession(String host, ManagedSessionId id) throws InterruptedException {
        List<String> argv = SshCommandBuilder.remoteCommand(host,
                List.of("tmux", "has-session", "-t", SshCommandBuilder.tmuxSessionName(id)));
        BoxResult result = run(argv, SHORT_COMMAND_TIMEOUT);
        Liveness verdict = switch (result) {
            case BoxResult.Ok(String ignored) -> Liveness.ALIVE;
            case BoxResult.RemoteFailed(int exitCode, String excerpt)
                    when exitCode == 1 && sessionGone(excerpt) -> Liveness.DEAD;
            case BoxResult other -> new Liveness.Unknown(other.describe());
        };
        if (verdict instanceof Liveness.Unknown unknown) {
            LOG.log(Level.WARNING, host + ": has-session: " + unknown.reason());
        }
        return verdict;
    }

    /**
     * Issues {@code tmux kill-session -t drydock-<id>} (10 s). The raw
     * {@link BoxResult} is returned so the explicit Stop/Forget callers
     * (the only paths allowed to issue this, INV-10) can read the "can't
     * find session" exit-1 form themselves — for them it means the session
     * was already gone, not that the kill failed (D1).
     */
    public BoxResult killSession(String host, ManagedSessionId id) throws InterruptedException {
        BoxResult result = run(SshCommandBuilder.remoteCommand(host,
                        List.of("tmux", "kill-session", "-t", SshCommandBuilder.tmuxSessionName(id))),
                SHORT_COMMAND_TIMEOUT);
        warnUnlessOk(host, result);
        return result;
    }

    /**
     * One {@code tmux ls -F '#{session_name}'} for the whole box — the
     * revalidation pass runs exactly one of these per box (not a
     * has-session per session) and classifies every row against the
     * returned set. The timeout is the caller's: it must be sized inside
     * the revalidation pass's 30 s bound, not default to this class's own.
     */
    public TmuxListing.Result tmuxLs(String host, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        BoxResult result = run(SshCommandBuilder.remoteCommand(host,
                List.of("tmux", "ls", "-F", "#{session_name}")), timeout);
        TmuxListing.Result listing = switch (result) {
            case BoxResult.Ok(String stdout) -> TmuxListing.parse(0, stdout, "");
            // The excerpt carries the remote stderr (the no-server forms
            // included) so TmuxListing can recognize its valid empty answer.
            case BoxResult.RemoteFailed(int exitCode, String excerpt) -> TmuxListing.parse(exitCode, "", excerpt);
            case BoxResult other -> new TmuxListing.Unknown(other.describe());
        };
        if (listing instanceof TmuxListing.Unknown unknown) {
            LOG.log(Level.WARNING, host + ": tmux ls: " + unknown.reason());
        }
        return listing;
    }

    /**
     * Copies one artifact to the box over scp ({@code
     * SshCommandBuilder.copyCommand}, the only scp shape). The timeout is
     * the caller's — a proxy binary and a config file are different
     * payloads; both are run-to-completion budgets.
     */
    public BoxResult copy(Path local, String host, String remotePath, Duration timeout)
            throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        BoxResult result = run(SshCommandBuilder.copyCommand(local, host, remotePath), timeout);
        warnUnlessOk(host, result);
        return result;
    }

    private void warnUnlessOk(String host, BoxResult result) {
        if (!(result instanceof BoxResult.Ok)) {
            LOG.log(Level.WARNING, host + ": " + result.describe());
        }
    }

    private static boolean sessionGone(String excerpt) {
        for (String marker : SESSION_GONE_MARKERS) {
            if (excerpt.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs one argv to completion and classifies its outcome into the five
     * {@link BoxResult} classes. The output cap bounds every stream the
     * classification reads (INV-20); a spawn {@link IOException} is "not
     * installed", except the cap's own refusal, which is a failed run.
     * {@link InterruptedException} propagates — the caller cancelled, the
     * child is already destroyed, and the caller's own cancellation path
     * owns what happens next.
     */
    private BoxResult run(List<String> argv, Duration timeout) throws InterruptedException {
        try {
            ProcessResult result = runner.run(argv,
                    new ProcessRunner.Options(null, timeout, true, Map.of()), MAX_OUTPUT_BYTES);
            if (result.exitCode() == 0) {
                return new BoxResult.Ok(result.stdout());
            }
            String excerpt = ProcessRunner.excerpt(
                    result.stderr().isBlank() ? result.stdout() : result.stderr());
            if (result.exitCode() == SSH_FAILURE_EXIT) {
                return new BoxResult.Unreachable(excerpt);
            }
            return new BoxResult.RemoteFailed(result.exitCode(), excerpt);
        } catch (ProcessTimeoutException e) {
            return new BoxResult.Timeout(timeout);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("exceeded")) {
                // ProcessRunner's output-cap refusal: a failed run, typed and
                // logged like any other remote failure, never a missing tool.
                return new BoxResult.RemoteFailed(-1, ProcessRunner.excerpt(e.getMessage()));
            }
            return new BoxResult.SshNotInstalled(argv.get(0));
        }
    }
}
