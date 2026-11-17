package app.drydock.process;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SshRemote;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * The single place ssh command lines are constructed (spec: SSH remote
 * repositories). Two forms: an argv list for {@link ProcessRunner}
 * (non-interactive git commands, BatchMode so background work can never
 * hang on a prompt), and a single shell command string for interactive
 * terminal sessions (libghostty takes a shell string; prompts must render).
 * The workspace-box arm adds three more shapes on the same invariants:
 * {@link #remoteCommand} (batch ssh for box-side commands such as tmux
 * probes), {@link #copyCommand} (scp to a box), and {@link
 * #persistentSessionCommand} (an interactive ssh that starts or reattaches
 * a box-side tmux session).
 *
 * <p>Invariants, both forms: {@code --} is placed immediately <em>before</em>
 * the destination so a hostile host can never be parsed as an ssh option
 * (and a {@code --} after the host would be handed to the remote shell,
 * which rejects it). Everything sent to the remote shell is POSIX
 * single-quoted; the documented v1 requirement is a POSIX-compatible login
 * shell on the host (sshd runs remote commands through it).</p>
 *
 * <p>No ControlMaster/multiplexing: a background mux master inherits the
 * client's pipes and would park {@link ProcessRunner}'s post-exit stream
 * joins; each command is a full connection. {@code ServerAliveInterval}
 * bounds post-connect stalls that {@code ConnectTimeout} (TCP connect
 * only) does not cover.</p>
 *
 * <p>Both forms send keep-alive probes, on deliberately different budgets.
 * The batch form fails fast (~6s) so the status poller surfaces an
 * unreachable host instead of stalling; the interactive form tolerates a
 * ~3min blackout so a terminal the user is sitting in survives a wifi
 * hiccup or a suspended laptop. Keep the two apart.</p>
 */
public final class SshCommandBuilder {

    /** Remote git commands get a tighter budget than local ones (network in the loop, poller behind it). */
    public static final Duration REMOTE_GIT_TIMEOUT = Duration.ofSeconds(10);

    private static final List<String> BATCH_OPTIONS = List.of(
            "-o", "BatchMode=yes",
            "-o", "ConnectTimeout=5",
            "-o", "ServerAliveInterval=3",
            "-o", "ServerAliveCountMax=2");

    /**
     * Keep-alive for interactive sessions, which are idle for long stretches
     * while the user reads. The 30s channel-level probe is what resets the
     * idle timers on NATs, firewalls and bastions in the path; six missed
     * probes before ssh gives up = a ~3min tolerance. Deliberately no
     * {@code TCPKeepAlive}: {@code yes} is already the OpenSSH default and
     * these probes keep the socket too busy for TCP's own (~2h idle) timer
     * to ever fire, so passing it would only override the user who set
     * {@code no} on purpose to survive transient route outages. Passed on
     * the command line, so it beats per-host ssh config values.
     */
    private static final String KEEPALIVE_OPTIONS =
            "-o ServerAliveInterval=30 -o ServerAliveCountMax=6";

    /**
     * Session names are interpolated bare into the remote shell string (the
     * only practical shape for a name both a shell and tmux must read), so
     * only shell-neutral characters are allowed. {@link #tmuxSessionName}'s
     * output ({@code drydock-<uuid>}) trivially passes.
     */
    private static final Pattern SAFE_SESSION_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

    private SshCommandBuilder() {
    }

    /** The guards {@link SshRemote} applies at its type; raw-host forms need them too. */
    private static void requireSshHost(String host) {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("SSH host must not be blank");
        }
        if (host.startsWith("-")) {
            throw new IllegalArgumentException("SSH host must not start with '-': " + host);
        }
    }

    /**
     * The stable box-side tmux session name for a managed session:
     * {@code drydock-<ManagedSessionId>}. The one place this name is formed
     * — every probe, kill, and attach derives it from here so a rename is
     * a one-line change. A UUID is safe for tmux session names (no
     * {@code .} or {@code :}, which tmux treats specially).
     */
    public static String tmuxSessionName(ManagedSessionId id) {
        Objects.requireNonNull(id, "id");
        return "drydock-" + id;
    }

    /**
     * The interactive form that gives an agent session box-side persistence:
     * {@code ssh -t <keepalive opts> -- '<host>' '<remote>'} where the remote
     * string exports {@code TERM}, applies the two global tmux options, then
     * runs {@code new-session -A -s <name> -c '<box path>' '<agent exec>'}.
     *
     * <p>{@code new-session -A} attaches when the session exists (reattach
     * after an IDE restart) and creates it otherwise, so create and resume
     * run the identical command. {@code history-limit} and
     * {@code remain-on-exit} are set <em>before</em> {@code new-session}
     * because tmux applies {@code history-limit} at pane creation — the
     * option set after the fact would not bound this pane's scrollback.
     * {@code remain-on-exit off} (the default, forced explicitly) keeps
     * liveness semantics exact: a session whose command exits is removed,
     * so {@code tmux has-session} is a precise agent-alive signal and no
     * probe needs a {@code pane_dead} check. The quoting crosses three
     * layers (local login shell {@code -c} → ssh remote shell → tmux's
     * shell-command): the box path and agent exec are POSIX single-quoted
     * for the remote shell, and the whole remote string is single-quoted
     * once more for the local shell. The session name is interpolated
     * bare, so it is validated to be free of shell metacharacters.
     * Same keep-alive budget and {@code TERM} forcing as {@link
     * #interactiveSessionCommand}; no BatchMode — the user may need to
     * type a passphrase into the terminal this command runs in.
     */
    public static String persistentSessionCommand(SshRemote box, String tmuxSessionName, String agentExec) {
        Objects.requireNonNull(box, "box");
        Objects.requireNonNull(agentExec, "agentExec");
        if (!SAFE_SESSION_NAME.matcher(tmuxSessionName).matches()) {
            throw new IllegalArgumentException(
                    "tmux session name must be alphanumerics, '-' and '_': " + tmuxSessionName);
        }
        String remote = "export TERM=xterm-256color; tmux"
                + " set-option -g history-limit 20000 \\;"
                + " set-option -g remain-on-exit off \\;"
                + " new-session -A -s " + tmuxSessionName
                + " -c " + posixQuote(box.remotePath())
                + " " + posixQuote(agentExec);
        return "ssh -t " + KEEPALIVE_OPTIONS + " -- "
                + posixQuote(box.host()) + " " + posixQuote(remote);
    }

    /**
     * Argv for {@link ProcessRunner}: a generic batch ssh to a box host,
     * {@code ssh <batch opts> -- <host> '<arg>' '<arg>'…}, for box-side
     * commands (tmux listing/probes, bootstrap checks). Every argument is
     * POSIX single-quoted because the box's login shell parses the joined
     * command string; {@code --} immediately before the host keeps a
     * hostile alias from being read as an ssh option. BatchMode and the
     * tight ConnectTimeout — these are background probes that must fail
     * fast, never hang on a prompt.
     */
    public static List<String> remoteCommand(String host, List<String> args) {
        requireSshHost(host);
        Objects.requireNonNull(args, "args");
        StringJoiner joined = new StringJoiner(" ");
        for (String arg : args) {
            joined.add(posixQuote(arg));
        }
        List<String> command = new ArrayList<>();
        command.add("ssh");
        command.addAll(BATCH_OPTIONS);
        command.add("--");
        command.add(host);
        command.add(joined.toString());
        return List.copyOf(command);
    }

    /**
     * Argv for {@link ProcessRunner}: {@code scp -- <local> <host>:<remote>},
     * copying one artifact to a box. {@code --} guards <em>both</em>
     * positionals — the destination carries the host, and a leading
     * {@code -} in either path must never parse as a scp option. There is
     * deliberately no shell layer to quote for here: this runs as an
     * argument list (never a shell string), and scp takes both paths
     * literally (sftp mode is the OpenSSH default; the legacy protocol
     * quotes the remote path itself), so pre-quoting would corrupt the
     * filenames. POSIX single-quoting in this class protects strings a
     * downstream shell parses — the ssh forms, not scp's argv.
     */
    public static List<String> copyCommand(Path local, String host, String remotePath) {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(remotePath, "remotePath");
        if (remotePath.isBlank()) {
            throw new IllegalArgumentException("Remote path must not be blank");
        }
        requireSshHost(host);
        List<String> command = new ArrayList<>();
        command.add("scp");
        command.add("--");
        command.add(local.toString());
        command.add(host + ":" + remotePath);
        return List.copyOf(command);
    }

    /** Argv for {@link ProcessRunner}: {@code ssh <batch opts> -- <host> "git -C '<path>' '<arg>'…"}. */
    public static List<String> remoteGitCommand(SshRemote remote, List<String> gitArgs) {
        StringJoiner remoteCommand = new StringJoiner(" ");
        remoteCommand.add("git").add("-C").add(posixQuote(remote.remotePath()));
        for (String arg : gitArgs) {
            remoteCommand.add(posixQuote(arg));
        }
        List<String> command = new ArrayList<>();
        command.add("ssh");
        command.addAll(BATCH_OPTIONS);
        command.add("--");
        command.add(remote.host());
        command.add(remoteCommand.toString());
        return List.copyOf(command);
    }

    /**
     * A shell command string launching an interactive remote command in the
     * embedded terminal: {@code ssh -t <keepalive opts> -- <host>
     * '<remote>'}. No local
     * {@code exec} prefix -- on macOS (the only platform this app ships)
     * libghostty already wraps the command it is given in one, see {@link
     * app.drydock.terminal.api.TerminalSpec}; a second one would try to run a
     * program literally named {@code exec}.
     * No BatchMode — passphrase/password prompts belong in the terminal.
     * {@code TERM} is forced to {@code xterm-256color} because Ghostty's
     * own {@code xterm-ghostty} terminfo won't exist on remote hosts and
     * would break full-screen TUIs (claude). {@code remoteExec} is executed
     * by the remote shell after {@code cd}-ing into the repo; the caller
     * quotes any arguments inside it with {@link #posixQuote} (that is the
     * inner of the two quoting layers this method assembles).
     */
    public static String interactiveSessionCommand(SshRemote remote, String remoteExec) {
        String remoteCommand = "export TERM=xterm-256color; cd " + posixQuote(remote.remotePath())
                + " && " + remoteExec;
        return "ssh -t " + KEEPALIVE_OPTIONS + " -- "
                + posixQuote(remote.host()) + " " + posixQuote(remoteCommand);
    }

    /** Wraps {@code value} as one POSIX single-quoted word, safe against embedded metacharacters. */
    public static String posixQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
