package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-session tokens, spawn grants, and creation budgets for the MCP server.
 *
 * <p>The token's job is <em>attribution</em>, not isolation: it tells the
 * server which session a call came from, so tools resolve to the right
 * repository and annotation set without the agent naming a path. It is not a
 * secret between sessions -- every session's config file is readable by any
 * process running as the user (spec, "Trust boundary"). Do not build security
 * on top of it.</p>
 *
 * <p>Tokens live only in memory: no terminal process survives an app restart,
 * so a persisted token could only ever be stale. Budget charges, by contrast,
 * outlive a revoke, so a reconnect cannot refill them.</p>
 */
public final class McpSessionRegistry {

    /** 32 bytes of CSPRNG output; base64url-encodes to 43 unpadded chars. */
    private static final int TOKEN_BYTES = 32;

    public static final int MAX_WORKTREES_PER_SESSION = 4;
    public static final int MAX_SESSIONS_PER_SESSION = 4;

    /**
     * Renames one session may apply. Not about disk: {@code
     * ApplicationStateStore} coalesces saves and the activity log is a ring.
     * It bounds FX-thread work -- every rename attempt costs a {@code
     * Platform.runLater} and a turn under the state lock, dispatched from an
     * unbounded virtual-thread executor, so a loop would freeze the UI.
     */
    public static final int MAX_RENAMES_PER_SESSION = 20;

    /**
     * Twice {@link #MAX_RENAMES_PER_SESSION}: a brief is rewritten at every
     * checkpoint by design, where a name is written once or twice. Still
     * bounded, because an agent that has written forty briefs is looping
     * rather than working.
     */
    public static final int MAX_HANDOFFS_PER_SESSION = 40;

    /** Whether a session may create worktrees and start further sessions. */
    public enum Spawn {
        /** A session the human started. */
        ALLOWED,
        /** A session an agent started via {@code session_start}: depth 1, so it may not spawn again. */
        FORBIDDEN
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, ManagedSessionId> byToken = new ConcurrentHashMap<>();
    private final Map<ManagedSessionId, String> bySession = new ConcurrentHashMap<>();
    private final Map<ManagedSessionId, Spawn> grants = new ConcurrentHashMap<>();
    private final Map<ManagedSessionId, AtomicInteger> worktreesCreated = new ConcurrentHashMap<>();
    private final Map<ManagedSessionId, AtomicInteger> sessionsStarted = new ConcurrentHashMap<>();
    private final Map<ManagedSessionId, AtomicInteger> renamesApplied = new ConcurrentHashMap<>();
    private final Map<ManagedSessionId, AtomicInteger> handoffsApplied = new ConcurrentHashMap<>();

    /** Returns this session's token, minting one on first call. Idempotent per session. */
    public String mint(ManagedSessionId sessionId, Spawn spawn) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(spawn, "spawn");
        grants.put(sessionId, spawn);
        return bySession.computeIfAbsent(sessionId, id -> {
            byte[] bytes = new byte[TOKEN_BYTES];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            byToken.put(token, id);
            return token;
        });
    }

    /**
     * Resolves a presented token. Comparison is uniform across every live
     * token rather than a map lookup; the real defense is 256 bits of entropy,
     * not timing, but a uniform compare costs nothing here.
     */
    public Optional<ManagedSessionId> resolve(String presented) {
        if (presented == null || presented.isEmpty()) {
            return Optional.empty();
        }
        ManagedSessionId match = null;
        for (Map.Entry<String, ManagedSessionId> entry : byToken.entrySet()) {
            if (constantTimeEquals(entry.getKey(), presented)) {
                match = entry.getValue();
            }
        }
        return Optional.ofNullable(match);
    }

    public Optional<String> tokenFor(ManagedSessionId sessionId) {
        return Optional.ofNullable(bySession.get(sessionId));
    }

    /** False for an agent-started session and for a session this registry never saw. */
    public boolean maySpawn(ManagedSessionId sessionId) {
        return grants.get(sessionId) == Spawn.ALLOWED;
    }

    public void chargeWorktree(ManagedSessionId sessionId) throws McpBudgetExhaustedException {
        charge(worktreesCreated, sessionId, MAX_WORKTREES_PER_SESSION, "worktrees");
    }

    public void chargeSession(ManagedSessionId sessionId) throws McpBudgetExhaustedException {
        charge(sessionsStarted, sessionId, MAX_SESSIONS_PER_SESSION, "sessions");
    }

    /** Releases a charge whose operation then failed. Never drops below zero. */
    public void refundWorktree(ManagedSessionId sessionId) {
        refund(worktreesCreated, sessionId);
    }

    /** Releases a charge whose operation then failed. Never drops below zero. */
    public void refundSession(ManagedSessionId sessionId) {
        refund(sessionsStarted, sessionId);
    }

    /**
     * Charges one rename attempt. Refused attempts are charged too -- they
     * cost the same FX hop, and each one tells the agent what is wrong, so
     * twenty is far more than it needs to learn.
     *
     * <p>Not the shared {@link #charge} helper: its message is written for a
     * creation limit ("has already created its limit of N ... Ask the human
     * to continue in one of them"), which reads as nonsense for a rename.</p>
     */
    public void chargeRename(ManagedSessionId sessionId) throws McpBudgetExhaustedException {
        AtomicInteger counter = renamesApplied.computeIfAbsent(sessionId, id -> new AtomicInteger());
        if (counter.incrementAndGet() > MAX_RENAMES_PER_SESSION) {
            counter.decrementAndGet();
            throw new McpBudgetExhaustedException("This session has already renamed itself "
                    + MAX_RENAMES_PER_SESSION + " times.");
        }
    }

    /** Releases a charge whose call then failed outright. Never drops below zero. */
    public void refundRename(ManagedSessionId sessionId) {
        refund(renamesApplied, sessionId);
    }

    /**
     * Charges one handoff write. Like {@link #chargeRename}, a refused attempt
     * is charged: it costs the same FX hop and turn under the state lock, and
     * it explains itself, so the budget bounds looping rather than learning.
     */
    public void chargeHandoff(ManagedSessionId sessionId) throws McpBudgetExhaustedException {
        AtomicInteger counter = handoffsApplied.computeIfAbsent(sessionId, id -> new AtomicInteger());
        if (counter.incrementAndGet() > MAX_HANDOFFS_PER_SESSION) {
            counter.decrementAndGet();
            throw new McpBudgetExhaustedException("This session has already written its handoff brief "
                    + MAX_HANDOFFS_PER_SESSION + " times.");
        }
    }

    /** Releases a handoff charge whose call then failed outright. */
    public void refundHandoff(ManagedSessionId sessionId) {
        refund(handoffsApplied, sessionId);
    }

    private static void refund(Map<ManagedSessionId, AtomicInteger> counters, ManagedSessionId sessionId) {
        AtomicInteger counter = counters.get(sessionId);
        if (counter != null) {
            counter.updateAndGet(current -> current > 0 ? current - 1 : 0);
        }
    }

    private static void charge(Map<ManagedSessionId, AtomicInteger> counters, ManagedSessionId sessionId,
                               int limit, String what) throws McpBudgetExhaustedException {
        AtomicInteger counter = counters.computeIfAbsent(sessionId, id -> new AtomicInteger());
        if (counter.incrementAndGet() > limit) {
            counter.decrementAndGet();
            throw new McpBudgetExhaustedException("This session has already created its limit of "
                    + limit + " " + what + ". Ask the human to continue in one of them.");
        }
    }

    /** Drops the session's token and grant. Budget charges are kept, so a reconnect cannot refill them. */
    public void revoke(ManagedSessionId sessionId) {
        String token = bySession.remove(sessionId);
        if (token != null) {
            byToken.remove(token);
        }
        grants.remove(sessionId);
    }

    private static boolean constantTimeEquals(String expected, String presented) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
