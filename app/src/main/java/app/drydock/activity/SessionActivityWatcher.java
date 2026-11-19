package app.drydock.activity;

import app.drydock.domain.SessionActivity;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Reads the state words the Claude hooks write (see {@link
 * ClaudeHookInstaller}) and turns them into {@link SessionActivity} values
 * keyed by claude session id.
 *
 * <p>Polled rather than watched. A {@code WatchService} would be the more
 * obvious tool, but this app has no watch infrastructure at all, and the
 * workspace already runs a one-second {@code Timeline} to notice exited
 * processes. Reading a handful of files under 10 bytes each on that
 * existing tick costs less than a watch thread plus its event plumbing,
 * and one second of latency is imperceptible on a status badge.</p>
 *
 * <p>All I/O runs on a background executor (AGENTS.md: never block the
 * JavaFX application thread on filesystem work), so {@link #poll()} returns
 * a future rather than a map.</p>
 */
public final class SessionActivityWatcher implements AutoCloseable {

    private static final Logger LOG = System.getLogger(SessionActivityWatcher.class.getName());

    /** A state word is one of "busy"/"bg"/"idle"/"attention"; anything longer is not ours. */
    private static final long MAX_STATE_FILE_BYTES = 64;

    private final Path activityDirectory;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    /**
     * Sessions whose current NEEDS_ATTENTION has already been seen by the
     * user (they switched to it). Cleared as soon as the underlying state
     * changes, so the next time Claude blocks on a human the badge returns.
     * The value is the state the user actually saw -- never a constant -- so
     * an acknowledgement taken while a session was busy can never collide
     * with a NEEDS_ATTENTION that arrives later and suppress its badge.
     */
    private final Map<String, SessionActivity> acknowledged = new ConcurrentHashMap<>();

    /**
     * What each session last reported, so {@link #acknowledge} can record the
     * state that was on screen when the user switched to a tab. Entries for
     * sessions that stopped reporting are dropped with each poll; a session
     * with no entry here has nothing to acknowledge yet.
     */
    private final Map<String, SessionActivity> observed = new ConcurrentHashMap<>();

    public SessionActivityWatcher(Path activityDirectory) {
        this(activityDirectory, Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    /** For tests/callers supplying (and owning the shutdown of) their own executor. */
    public SessionActivityWatcher(Path activityDirectory, ExecutorService executor) {
        this(activityDirectory, executor, false);
    }

    private SessionActivityWatcher(Path activityDirectory, ExecutorService executor, boolean ownsExecutor) {
        this.activityDirectory = Objects.requireNonNull(activityDirectory, "activityDirectory");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * Reads every state file, applying acknowledgement so an already-seen
     * NEEDS_ATTENTION reports as IDLE. Sessions with no state file are
     * absent from the result rather than mapped to UNKNOWN; the caller knows
     * which sessions exist, this class only knows which ones reported.
     */
    public CompletableFuture<Map<String, SessionActivity>> poll() {
        return CompletableFuture.supplyAsync(this::readBlocking, executor);
    }

    Map<String, SessionActivity> readBlocking() {
        if (!Files.isDirectory(activityDirectory)) {
            return Map.of();
        }
        Map<String, SessionActivity> activities = new HashMap<>();
        try (Stream<Path> files = Files.list(activityDirectory)) {
            files.forEach(file -> readState(file).ifPresent(
                    activity -> activities.put(file.getFileName().toString(), activity)));
        } catch (IOException e) {
            // A status badge is strictly cosmetic: a transient read failure
            // must degrade to "no activity known", never propagate.
            LOG.log(Level.WARNING, "Could not list activity directory " + activityDirectory + ": " + e.getMessage());
            return Map.of();
        }
        observed.putAll(activities);
        observed.keySet().retainAll(activities.keySet());
        activities.replaceAll(this::applyAcknowledgement);
        acknowledged.keySet().retainAll(activities.keySet());
        return Map.copyOf(activities);
    }

    private Optional<SessionActivity> readState(Path file) {
        String name = file.getFileName().toString();
        if (name.startsWith(".")) {
            return Optional.empty(); // in-flight temp file from the hook script
        }
        try {
            if (Files.size(file) > MAX_STATE_FILE_BYTES) {
                return Optional.empty();
            }
            SessionActivity activity = SessionActivity.fromStateWord(
                    Files.readString(file, StandardCharsets.UTF_8));
            return activity == SessionActivity.UNKNOWN ? Optional.empty() : Optional.of(activity);
        } catch (IOException e) {
            // Raced with the hook's rename, or the file vanished on SessionEnd.
            return Optional.empty();
        }
    }

    private SessionActivity applyAcknowledgement(String sessionId, SessionActivity current) {
        SessionActivity seen = acknowledged.get(sessionId);
        if (seen == null) {
            return current;
        }
        if (seen != current) {
            acknowledged.remove(sessionId); // state moved on; a future badge is legitimate again
            return current;
        }
        return current == SessionActivity.NEEDS_ATTENTION ? SessionActivity.IDLE : current;
    }

    /**
     * Marks the session's last observed activity as seen, clearing its badge.
     * Called when the user switches to a session -- the badge exists to say
     * "this one needs you", and looking at it answers that.
     *
     * <p>Only a state that was actually observed is recorded: switching to a
     * tab whose session is busy must not suppress a NEEDS_ATTENTION that
     * arrives moments later. If nothing has been observed yet (no poll has
     * run), nothing is stored, so a pending badge survives until the user
     * answers it or the state moves on.</p>
     */
    public void acknowledge(String agentSessionId) {
        if (agentSessionId == null) {
            return;
        }
        SessionActivity seen = observed.get(agentSessionId);
        if (seen != null) {
            acknowledged.put(agentSessionId, seen);
        }
    }

    /**
     * Drops any state left behind for a session that is gone for good.
     *
     * <p>The delete runs on this watcher's executor rather than the caller's
     * thread: callers are on the JavaFX thread (session close), where AGENTS.md
     * bars filesystem I/O. The returned future is for tests; callers that do not
     * care may ignore it, since failure only means the file waits for the next
     * startup purge.</p>
     */
    public CompletableFuture<Void> forget(String agentSessionId) {
        if (agentSessionId == null) {
            return CompletableFuture.completedFuture(null);
        }
        acknowledged.remove(agentSessionId);
        observed.remove(agentSessionId);
        return CompletableFuture.runAsync(() -> {
            try {
                Files.deleteIfExists(activityDirectory.resolve(agentSessionId));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.DEBUG, "Could not clear activity state for " + agentSessionId + ": " + e.getMessage());
            }
        }, executor);
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }
}
