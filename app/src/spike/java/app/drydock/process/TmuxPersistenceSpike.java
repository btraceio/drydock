package app.drydock.process;

import app.drydock.domain.ManagedSessionId;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * SPIKE harness for {@link TmuxPersistence} (the unattended-sessions
 * investigation). Proves the three things the restart-reattach path relies
 * on, against a real tmux server on this machine:
 *
 * <ol>
 *   <li>{@link TmuxPersistence#wrapLocalCommand} builds the expected
 *       {@code tmux new-session -A -s <name> -c <wd> '<cmd>'} string.</li>
 *   <li>{@link TmuxPersistence#sessionAlive} returns true for a live tmux
 *       session and false after it is killed — the probe
 *       {@code SessionManager.revalidateTmuxSessions} runs on startup.</li>
 *   <li>{@link TmuxPersistence#detachClient} is safe to call on a session
 *       with no attached client (the close-tab path) and leaves the session
 *       alive.</li>
 * </ol>
 *
 * <p>Run with {@code ./gradlew tmuxPersistenceSpike}. Requires {@code tmux}
 * on PATH; exits 0 with a SKIP line when it is absent (or the flag is off),
 * so it is safe on CI runners without tmux. Creates and tears down its own
 * tmux session named {@code drydock-<uuid>}; never touches any other
 * session. Not a JavaFX spike — no native build needed.</p>
 */
public final class TmuxPersistenceSpike {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private TmuxPersistenceSpike() {
    }

    public static void main(String[] args) {
        if (!TmuxPersistence.enabled()) {
            System.out.println("SKIP: tmux persistence disabled (set -Dapp.drydock.tmux.persistence=true "
                    + "and install tmux).");
            return;
        }
        ManagedSessionId id = ManagedSessionId.of(UUID.randomUUID().toString());
        String name = TmuxPersistence.sessionName(id);
        int failures = 0;

        // (1) wrapLocalCommand is a pure string build. The inner command's
        // single quotes are POSIX-escaped, so assert the fixed tmux prefix
        // and that the agent command survived intact, not the exact escape.
        String wrapped = TmuxPersistence.wrapLocalCommand("claude --resume 'abc 123'", "/tmp/work", id);
        if (wrapped.startsWith("tmux new-session -A -s " + name + " -c '/tmp/work' ")
                && wrapped.contains("claude --resume ") && wrapped.contains("abc 123")) {
            System.out.println("PASS wrapLocalCommand -> " + wrapped);
        } else {
            System.out.println("FAIL wrapLocalCommand: " + wrapped);
            failures++;
        }

        // (2) sessionAlive against a real session. Create detached, probe,
        // kill, probe again.
        try {
            ProcessRunner.run(List.of("tmux", "new-session", "-d", "-s", name, "-x", "80", "-y", "24",
                    "sleep 300"), null, TIMEOUT);
        } catch (Exception e) {
            System.out.println("FAIL: could not create test tmux session: " + e.getMessage());
            System.exit(1);
        }
        if (!TmuxPersistence.sessionAlive(id)) {
            System.out.println("FAIL sessionAlive: expected true for a live session");
            failures++;
        } else {
            System.out.println("PASS sessionAlive (live) = true");
        }

        // (3) detachClient on a session with no client is a no-op and leaves
        // the session alive.
        TmuxPersistence.detachClient(id);
        if (!TmuxPersistence.sessionAlive(id)) {
            System.out.println("FAIL: session died after detachClient with no client attached");
            failures++;
        } else {
            System.out.println("PASS detachClient (no client) left session alive");
        }

        // (4) sessionAlive false after kill.
        try {
            ProcessRunner.run(List.of("tmux", "kill-session", "-t", name), null, TIMEOUT);
        } catch (Exception e) {
            System.out.println("FAIL: could not kill test tmux session: " + e.getMessage());
            failures++;
        }
        if (TmuxPersistence.sessionAlive(id)) {
            System.out.println("FAIL sessionAlive: expected false after kill-session");
            failures++;
        } else {
            System.out.println("PASS sessionAlive (killed) = false");
        }

        if (failures > 0) {
            System.out.println("SPIKE FAILED: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("SPIKE PASSED: TmuxPersistence wraps + probes + detaches correctly.");
    }
}