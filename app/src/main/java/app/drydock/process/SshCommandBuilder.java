package app.drydock.process;

import app.drydock.domain.SshRemote;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * The single place ssh command lines are constructed (spec: SSH remote
 * repositories). Two forms: an argv list for {@link ProcessRunner}
 * (non-interactive git commands, BatchMode so background work can never
 * hang on a prompt), and a single shell command string for interactive
 * terminal sessions (libghostty takes a shell string; prompts must render).
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
 */
public final class SshCommandBuilder {

    /** Remote git commands get a tighter budget than local ones (network in the loop, poller behind it). */
    public static final Duration REMOTE_GIT_TIMEOUT = Duration.ofSeconds(10);

    private static final List<String> BATCH_OPTIONS = List.of(
            "-o", "BatchMode=yes",
            "-o", "ConnectTimeout=5",
            "-o", "ServerAliveInterval=3",
            "-o", "ServerAliveCountMax=2");

    private SshCommandBuilder() {
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
     * embedded terminal: {@code ssh -t -- <host> '<remote>'}. No local
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
        return "ssh -t -- " + posixQuote(remote.host()) + " " + posixQuote(remoteCommand);
    }

    /** Wraps {@code value} as one POSIX single-quoted word, safe against embedded metacharacters. */
    public static String posixQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
