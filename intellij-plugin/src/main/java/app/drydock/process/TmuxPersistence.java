package app.drydock.process;

import app.drydock.domain.ManagedSessionId;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * tmux-backed session persistence (SPIKE — guarded by the
 * {@code app.drydock.tmux.persistence} system property, OFF by default).
 *
 * <p>When enabled, an agent command is wrapped in
 * {@code tmux new-session -A -s <name> -c <wd> '<cmd>'} so the agent process
 * is a child of a <em>daemonized tmux server</em> (reparented to PID 1), not
 * of Drydock's in-process libghostty. Closing the Drydock surface only kills
 * the tmux <em>client</em> (detach); the agent keeps running. On a Drydock
 * restart the same {@code -A} command reattaches to the surviving session,
 * and a startup liveness probe flips persisted sessions back to RUNNING when
 * their tmux session still exists. See
 * {@code ~/.cairn/.../unattended-sessions/} for the investigation this spike
 * springs from; the mechanism was proven out-of-band (a detached tmux session
 * outlives the spawning shell; the server's PPID is 1).</p>
 *
 * <p><b>Spike scope.</b> Local sessions only. Remote (SSH) persistence is the
 * same mechanism with the tmux server on the remote host ({@code ssh -t
 * <host> 'tmux new-session -A -s <name> -c <path> "<cmd>"'}); it is deferred
 * because the stable session name must reach the provider's remote command
 * builder, which today does not receive the {@link ManagedSessionId}. MCP
 * tools go stale on reattach (Drydock's MCP server is in-process on an
 * ephemeral port) — handled as a follow-up, not by this spike.</p>
 *
 * <p>All tmux calls go through {@link ProcessRunner} (never a hand-rolled
 * {@code ProcessBuilder}), with a short timeout — these are liveness probes,
 * not long work. {@code sessionAlive} and {@code detachClient} may block and
 * MUST run off the JavaFX Application Thread.</p>
 */
public final class TmuxPersistence {

    private static final Logger LOG = System.getLogger(TmuxPersistence.class.getName());

    /** Gate. Read once; flipping the property mid-session is not supported. */
    private static final boolean ENABLED = Boolean.getBoolean("app.drydock.tmux.persistence");

    /** Liveness probe budget — a reachable local tmux answers in well under 1s. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    private static volatile Optional<String> binary = null;

    private TmuxPersistence() {
    }

    /** Whether tmux persistence is switched on AND a {@code tmux} binary is reachable. */
    public static boolean enabled() {
        return ENABLED && tmuxBinary().isPresent();
    }

    /** The {@code tmux} executable path, probed once (cached). Empty if not installed. */
    public static Optional<String> tmuxBinary() {
        Optional<String> cached = binary;
        if (cached != null) {
            return cached;
        }
        synchronized (TmuxPersistence.class) {
            if (binary != null) {
                return binary;
            }
            Optional<String> found = probeTmux();
            binary = found;
            return found;
        }
    }

    private static Optional<String> probeTmux() {
        try {
            ProcessResult r = ProcessRunner.run(List.of("tmux", "-V"), null, PROBE_TIMEOUT);
            if (r.exitCode() == 0) {
                return Optional.of("tmux");
            }
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "tmux probe failed; persistence disabled: " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * The stable tmux session name for a managed session. A UUID is safe for
     * tmux session names (no {@code .} or {@code :}, which tmux treats specially).
     */
    public static String sessionName(ManagedSessionId id) {
        return "drydock-" + id.toString();
    }

    /**
     * Wraps a local agent command so it runs inside a (re)attachable tmux
     * session: {@code tmux new-session -A -s <name> -c <wd> '<cmd>'}.
     *
     * <p>{@code -A} attaches if the session already exists (reattach on
     * restart) and ignores the shell-command in that case; otherwise it
     * creates the session running {@code cmd}. The inner command is
     * POSIX-single-quoted (embedded quotes escaped) so it reaches tmux as one
     * shell-command word; the whole string is itself run by libghostty via
     * {@code bash -c "exec -l <this>"}, so the outer shell splits it into tmux
     * argv with the quoted command intact.</p>
     */
    public static String wrapLocalCommand(String command, String workingDirectory, ManagedSessionId id) {
        String tmux = tmuxBinary().orElseThrow();
        String name = sessionName(id);
        String wd = SshCommandBuilder.posixQuote(workingDirectory);
        String cmd = "'" + command.replace("'", "'\\''") + "'";
        return tmux + " new-session -A -s " + name + " -c " + wd + " " + cmd;
    }

    /**
     * Whether the tmux session for {@code id} is still alive (the agent is
     * running). Blocks (spawns {@code tmux has-session}); off-FX-thread only.
     */
    public static boolean sessionAlive(ManagedSessionId id) {
        Optional<String> tmux = tmuxBinary();
        if (tmux.isEmpty()) {
            return false;
        }
        try {
            ProcessResult r = ProcessRunner.run(
                    List.of(tmux.get(), "has-session", "-t", sessionName(id)), null, PROBE_TIMEOUT);
            return r.exitCode() == 0;
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "tmux has-session probe failed for " + id + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Detaches any client attached to the session, so the ghostty-spawned tmux
     * client exits cleanly (and can be freed without the documented
     * live-child {@code ghostty_surface_free} crash). The agent keeps running
     * in the tmux server. Best-effort; blocks — off-FX-thread only.
     */
    public static void detachClient(ManagedSessionId id) {
        Optional<String> tmux = tmuxBinary();
        if (tmux.isEmpty()) {
            return;
        }
        try {
            ProcessRunner.run(List.of(tmux.get(), "detach-client", "-s", sessionName(id)), null, PROBE_TIMEOUT);
        } catch (Exception e) {
            // Non-fatal: if detach fails, closeWithoutSignal's timeout will
            // force the surface free as a last resort.
            LOG.log(Level.DEBUG, "tmux detach-client failed for " + id + ": " + e.getMessage());
        }
    }
}