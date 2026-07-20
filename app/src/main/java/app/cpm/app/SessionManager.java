package app.cpm.app;

import app.cpm.claude.ClaudeCapabilities;
import app.cpm.claude.ClaudeCapabilityService;
import app.cpm.claude.ConversationCatalog;
import app.cpm.domain.ApplicationState;
import app.cpm.domain.ManagedClaudeSession;
import app.cpm.domain.ManagedSessionId;
import app.cpm.domain.PrState;
import app.cpm.domain.Repository;
import app.cpm.domain.SessionStatus;
import app.cpm.state.ApplicationStateRepository;
import app.cpm.terminal.ghostty.GhosttyApp;
import app.cpm.terminal.ghostty.GhosttySurface;
import app.cpm.terminal.host.CpmTerminalHost;
import javafx.application.Platform;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Orchestrates creating and resuming {@link ManagedClaudeSession}s (plan
 * section 11): generates/persists session metadata via {@link
 * ApplicationStateRepository}, launches the real {@code claude} CLI inside a
 * {@link GhosttySurface}, enforces duplicate-open protection (plan section
 * 11.3), and closes sessions using {@link
 * GhosttySurface#closeGracefully(long, long, Runnable)}.
 *
 * <p><b>Threading (plan section 18):</b> {@link #createSession} and {@link
 * #resumeSession} do their slow work -- {@link ClaudeCapabilityService}
 * detection and persistence I/O -- on a background executor, and only touch
 * {@link GhosttySurface}/{@link GhosttyApp}/{@link CpmTerminalHost} via
 * {@link Platform#runLater}, per {@link CpmTerminalHost}'s own documented
 * "JavaFX Application Thread only" constraint. Callers get back a {@link
 * CompletableFuture}; if the caller needs to touch UI with the result, it is
 * the caller's responsibility to hop back onto the FX thread (this class
 * does not assume the completion thread is the FX thread for anything
 * except the {@link GhosttySurface} calls it makes itself).</p>
 *
 * <p><b>Deviation from a literal reading of plan section 21</b> ("argument
 * list, never a shell string"): {@link GhosttySurface#create} (Phase 0's
 * already-fixed, narrow terminal API -- not modified here) only accepts a
 * single shell command string, which libghostty always runs via {@code
 * /bin/sh -c "<command>"}. There is no argument-list overload to call
 * instead. This class therefore builds the command as a single
 * single-quoted-argument string ({@link #shellQuote}) rather than an actual
 * {@code String[]}/{@code List<String>} argument vector; every dynamic value
 * placed into it (display name, Claude session id/name) is quoted so it
 * cannot be interpreted as additional shell syntax. Likewise, plan section
 * 11.1's "add only application-specific environment variables that are
 * strictly necessary" is not implemented: {@code GhosttySurface.create} has
 * no environment-map parameter at all, so the spawned {@code claude}
 * process's environment is simply whatever the embedded shell inherits from
 * this application's own process (which does satisfy "inherit the
 * application environment").</p>
 */
public final class SessionManager implements AutoCloseable {

    private static final Logger LOG = System.getLogger(SessionManager.class.getName());

    /** Default grace period for {@link #closeSession}, matching Gate 0D's verified Ctrl+D-exit timing headroom. */
    private static final long DEFAULT_GRACE_PERIOD_MILLIS = 3000;
    private static final long DEFAULT_POLL_INTERVAL_MILLIS = 100;

    /** How long {@link #close} waits for queued background work (state saves) before giving up. */
    private static final long CLOSE_AWAIT_TERMINATION_SECONDS = 2;

    private final ApplicationStateStore stateStore;
    private final ClaudeCapabilityService capabilityService;
    private final ExecutorService backgroundExecutor;
    private final boolean ownsExecutor;

    private final ActiveSessionRegistry activeRegistry = new ActiveSessionRegistry();
    private final Map<ManagedSessionId, GhosttySurface> activeSurfaces = new ConcurrentHashMap<>();

    public SessionManager(ApplicationStateRepository stateRepository, ClaudeCapabilityService capabilityService) {
        this(stateRepository, capabilityService, Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    /** For callers/tests that want to supply (and own the shutdown of) their own executor. */
    public SessionManager(ApplicationStateRepository stateRepository, ClaudeCapabilityService capabilityService,
                           ExecutorService backgroundExecutor) {
        this(stateRepository, capabilityService, backgroundExecutor, false);
    }

    private SessionManager(ApplicationStateRepository stateRepository, ClaudeCapabilityService capabilityService,
                            ExecutorService backgroundExecutor, boolean ownsExecutor) {
        // The store is shared with every other manager built against the
        // same repository instance (see ApplicationStateStore.forRepository),
        // so cross-manager read-modify-write cycles serialize on ONE lock.
        this.stateStore = ApplicationStateStore.forRepository(stateRepository);
        this.capabilityService = capabilityService;
        this.backgroundExecutor = backgroundExecutor;
        this.ownsExecutor = ownsExecutor;
        stateStore.update(SessionManager::normalizeLoadedState);
    }

    /**
     * A freshly loaded state can contain sessions persisted as {@link
     * SessionStatus#RUNNING}/{@link SessionStatus#STARTING} by a previous
     * app run (e.g. the app quit, crashed, or was killed while sessions were
     * open). No terminal process survives an app restart, so presenting
     * those statuses would show "running" indicators for processes that do
     * not exist; normalize them to {@link SessionStatus#INACTIVE} before
     * anything reads them.
     */
    private static ApplicationState normalizeLoadedState(ApplicationState loaded) {
        List<ManagedClaudeSession> normalized = loaded.sessions().stream()
                .map(session -> switch (session.status()) {
                    case RUNNING, STARTING -> session.withStatus(SessionStatus.INACTIVE);
                    default -> session;
                })
                .toList();
        return loaded.withSessions(normalized);
    }

    /** Read-only view into claude's transcript store, for {@code checkResumeBlocked}'s missing-conversation probe. */
    private final ConversationCatalog conversationCatalog = new ConversationCatalog();

    public ApplicationState state() {
        return stateStore.state();
    }

    public List<ManagedClaudeSession> sessions() {
        return stateStore.state().sessions();
    }

    // ---- 11.1 Create a new session ----------------------------------------

    /** Creates a new session with a generated default display name (plan section 11.1). */
    public CompletableFuture<SessionOpenResult> createSession(Repository repository, GhosttyApp app,
                                                               CpmTerminalHost host, double scaleFactor) {
        return createSession(repository, defaultDisplayName(repository), app, host, scaleFactor);
    }

    /**
     * Mints the metadata for a brand-new session (generated display name)
     * WITHOUT launching anything. Callers that key UI bookkeeping by
     * session id should prepare first and then {@link #launchSession}: the
     * launch persists the session almost immediately (making it visible to
     * e.g. the sidebar), and a placeholder registered under a provisional
     * id would not be found by a concurrent open of the freshly persisted
     * real id -- yielding a duplicate surface and a leaked native pair.
     */
    public ManagedClaudeSession prepareSession(Repository repository) {
        return newSessionMetadata(repository, defaultDisplayName(repository));
    }

    /** As {@link #prepareSession}, for a session living inside an already-created worktree checkout. */
    public ManagedClaudeSession prepareWorktreeSession(Repository repository, String displayName, Path worktreeRoot) {
        return newSessionMetadata(repository, displayName, Optional.of(worktreeRoot));
    }

    /** Launches a session minted by {@link #prepareSession}/{@link #prepareWorktreeSession}. */
    public CompletableFuture<SessionOpenResult> launchSession(ManagedClaudeSession prepared, GhosttyApp app,
                                                              CpmTerminalHost host, double scaleFactor) {
        return launchNewSession(prepared, prepared.displayName(), app, host, scaleFactor);
    }

    /**
     * Creates a new session with an explicit display name (plan section
     * 11.1's "allow immediate renaming" -- a caller can generate its own
     * name, or rename after the fact via {@link #renameSession}, which this
     * step does not itself provide UI for).
     */
    public CompletableFuture<SessionOpenResult> createSession(Repository repository, String displayName,
                                                               GhosttyApp app, CpmTerminalHost host,
                                                               double scaleFactor) {
        return launchNewSession(newSessionMetadata(repository, displayName), displayName, app, host, scaleFactor);
    }

    /**
     * Creates a session living inside an (already-created) git worktree
     * checkout: claude launches from {@code worktreeRoot}, and the session
     * is tagged with it so the UI can render branch/dirty/PR metadata.
     * Creating the worktree itself ({@code git worktree add}) is the
     * caller's job, via {@link app.cpm.git.GitStatusService#createWorktree}.
     */
    public CompletableFuture<SessionOpenResult> createWorktreeSession(Repository repository, String displayName,
                                                                       Path worktreeRoot, GhosttyApp app,
                                                                       CpmTerminalHost host, double scaleFactor) {
        ManagedClaudeSession initial = newSessionMetadata(repository, displayName, Optional.of(worktreeRoot));
        return launchNewSession(initial, displayName, app, host, scaleFactor);
    }

    private CompletableFuture<SessionOpenResult> launchNewSession(ManagedClaudeSession initial, String displayName,
                                                                  GhosttyApp app, CpmTerminalHost host,
                                                                  double scaleFactor) {
        // Generated up front so this app -- not claude -- decides the Claude
        // session id: launching with `claude --session-id '<uuid>'` (when the
        // installed claude supports it) makes the session id known without
        // having to scrape it from claude's output or state files, so a
        // later resume can target this EXACT conversation via
        // `claude --resume '<uuid>'` (see buildResumeCommand's fallback
        // chain) instead of dropping the user into the interactive picker.
        String claudeSessionId = UUID.randomUUID().toString();

        // Metadata persistence is disk I/O; keep it off the (FX) caller thread.
        return CompletableFuture.runAsync(() -> persistNewSession(initial), backgroundExecutor)
                .thenCompose(ignored -> capabilityService.detectCapabilities())
                .thenApplyAsync(caps -> {
                    String command = System.getProperty("app.cpm.diag.command",
                            buildCreateCommand(caps, displayName, claudeSessionId));
                    // contains() rather than caps.supportsSessionId(): a
                    // diag command override never carries the id even when
                    // the flag is supported.
                    return new CreatePlan(command, command.contains(claudeSessionId));
                }, backgroundExecutor)
                .thenCompose(plan -> createSurfaceOnFxThread(app, host, scaleFactor, plan.command(),
                        initial.workingDirectory().toString())
                        .thenApply(surface -> new CreateLaunch(plan, surface)))
                .handleAsync((launch, ex) -> finalizeCreate(initial, claudeSessionId, launch, ex), backgroundExecutor);
    }

    /** The launch command plus whether it actually carries the pre-generated {@code --session-id}. */
    private record CreatePlan(String command, boolean sessionIdUsed) { }

    private record CreateLaunch(CreatePlan plan, GhosttySurface surface) { }

    private SessionOpenResult finalizeCreate(ManagedClaudeSession initial, String claudeSessionId,
                                              CreateLaunch launch, Throwable ex) {
        if (ex != null) {
            Throwable cause = unwrap(ex);
            LOG.log(Level.WARNING, () -> "Failed to start session " + initial.id() + ": " + cause.getMessage());
            try {
                persistUpdatedSession(initial.withStatus(SessionStatus.FAILED));
            } catch (RuntimeException persistFailure) {
                // Never mask the original launch failure with a secondary
                // persistence failure; the FAILED status is best-effort.
                LOG.log(Level.WARNING, () -> "Could not persist FAILED status for session " + initial.id()
                        + ": " + persistFailure.getMessage());
            }
            throw wrap(cause);
        }
        ManagedClaudeSession running = initial.withStatus(SessionStatus.RUNNING).withLastOpenedAt(Instant.now());
        // Only persist the Claude session id if the launch command actually
        // used it -- persisting an id claude never saw would make a later
        // resume target a nonexistent conversation.
        if (launch.plan().sessionIdUsed()) {
            running = running.withClaudeSessionId(Optional.of(claudeSessionId));
            activeRegistry.tryMarkActive(claudeSessionId, running.id());
        }
        persistUpdatedSession(running);
        activeSurfaces.put(running.id(), launch.surface());
        return new SessionOpenResult.Opened(running, launch.surface());
    }

    // ---- 11.2 Resume a session ---------------------------------------------

    /**
     * Resumes an existing session (plan section 11.2): {@code claude
     * --resume '<id>'} if a trusted Claude session id is known, else {@code
     * claude --resume '<name>'} if an assigned name is known, else plain
     * {@code claude --resume} (the official picker). Always launches from
     * the session's stored working directory; never silently substitutes a
     * different one (see {@link #reassignWorkingDirectory}).
     */
    public CompletableFuture<SessionOpenResult> resumeSession(ManagedSessionId sessionId, GhosttyApp app,
                                                               CpmTerminalHost host, double scaleFactor) {
        // checkResumeBlocked touches the filesystem (working-directory and
        // transcript existence probes, potentially persistence) -- run it on
        // the background executor, never the calling (FX) thread.
        return CompletableFuture.supplyAsync(() -> checkResumeBlocked(sessionId), backgroundExecutor)
                .thenCompose(blocked -> {
                    if (blocked.isPresent()) {
                        return CompletableFuture.completedFuture(blocked.get());
                    }
                    ManagedClaudeSession session = requireSession(sessionId);
                    String command = buildResumeCommand(session);
                    return createSurfaceOnFxThread(app, host, scaleFactor, command,
                                    session.workingDirectory().toString())
                            .handleAsync((surface, ex) -> finalizeResume(session, surface, ex), backgroundExecutor);
                });
    }

    private SessionOpenResult finalizeResume(ManagedClaudeSession session, GhosttySurface surface, Throwable ex) {
        if (ex != null) {
            Throwable cause = unwrap(ex);
            LOG.log(Level.WARNING, () -> "Failed to resume session " + session.id() + ": " + cause.getMessage());
            persistUpdatedSession(session.withStatus(SessionStatus.FAILED));
            throw wrap(cause);
        }
        ManagedClaudeSession running = session.withStatus(SessionStatus.RUNNING).withLastOpenedAt(Instant.now());
        persistUpdatedSession(running);
        activeSurfaces.put(running.id(), surface);
        session.claudeSessionId().ifPresent(claudeId -> activeRegistry.tryMarkActive(claudeId, running.id()));
        return new SessionOpenResult.Opened(running, surface);
    }

    /**
     * Checks the two "do not launch a surface" preconditions from plan
     * section 11.2/11.3 without touching any terminal object -- pure
     * metadata/bookkeeping, so it is directly unit-testable without a real
     * window (see class Javadoc and the accompanying test).
     *
     * @return a present {@link SessionOpenResult.AlreadyOpen} or {@link
     *         SessionOpenResult.MissingWorkingDirectory} if launching should
     *         not proceed, or {@link Optional#empty()} if the session is
     *         clear to launch (working directory exists, and either it has
     *         no Claude session id yet or that id is not already active
     *         elsewhere).
     */
    Optional<SessionOpenResult> checkResumeBlocked(ManagedSessionId sessionId) {
        // Deliberately holds no lock across the filesystem probes below --
        // they can stall (network volumes, cold disk), and a monitor held
        // here used to block FX-thread callers of this manager's other
        // (then-synchronized) methods. State reads/writes take the store's
        // own short-lived lock only.
        ManagedClaudeSession session = requireSession(sessionId);

        Optional<String> claudeSessionId = session.claudeSessionId();
        if (claudeSessionId.isPresent()) {
            Optional<ManagedSessionId> active = activeRegistry.activeSessionId(claudeSessionId.get());
            if (active.isPresent() && !active.get().equals(sessionId)) {
                GhosttySurface activeSurface = activeSurfaces.get(active.get());
                if (activeSurface != null) {
                    return Optional.of(new SessionOpenResult.AlreadyOpen(session, active.get(), activeSurface));
                }
            }
        }

        if (Files.notExists(session.workingDirectory())) {
            ManagedClaudeSession missing = session.withStatus(SessionStatus.MISSING_WORKING_DIRECTORY);
            persistUpdatedSession(missing);
            return Optional.of(new SessionOpenResult.MissingWorkingDirectory(missing));
        }

        // A pinned conversation id whose transcript claude no longer has on
        // disk would make `claude --resume '<id>'` exit immediately with "No
        // conversation found" -- detect it up front (same transcript layout
        // ConversationCatalog reads) so the UI can offer a fresh start or
        // deletion instead of presenting a dead terminal.
        if (claudeSessionId.isPresent()) {
            Path transcript = conversationCatalog.projectDirFor(session.workingDirectory())
                    .resolve(claudeSessionId.get() + ".jsonl");
            if (Files.notExists(transcript)) {
                return Optional.of(new SessionOpenResult.MissingConversation(session));
            }
        }

        return Optional.empty();
    }

    /**
     * Relaunches a session whose pinned conversation vanished (see {@link
     * SessionOpenResult.MissingConversation}) as a BRAND-NEW claude
     * conversation under the same display name and working directory: the
     * managed session row is kept, its stale Claude session id replaced by
     * a freshly pinned one.
     */
    public CompletableFuture<SessionOpenResult> startFreshConversation(ManagedSessionId sessionId, GhosttyApp app,
                                                                        CpmTerminalHost host, double scaleFactor) {
        String claudeSessionId = UUID.randomUUID().toString();
        // The stale-id clear persists to disk; keep it off the (FX) caller thread.
        return CompletableFuture.supplyAsync(() -> {
                    ManagedClaudeSession cleared = requireSession(sessionId).withClaudeSessionId(Optional.empty());
                    persistUpdatedSession(cleared);
                    return cleared;
                }, backgroundExecutor)
                .thenCompose(cleared -> capabilityService.detectCapabilities()
                        .thenApplyAsync(caps -> {
                            String command = buildCreateCommand(caps, cleared.displayName(), claudeSessionId);
                            return new CreatePlan(command, command.contains(claudeSessionId));
                        }, backgroundExecutor)
                        .thenCompose(plan -> createSurfaceOnFxThread(app, host, scaleFactor, plan.command(),
                                cleared.workingDirectory().toString())
                                .thenApply(surface -> new CreateLaunch(plan, surface)))
                        .handleAsync((launch, ex) -> finalizeCreate(cleared, claudeSessionId, launch, ex),
                                backgroundExecutor));
    }

    /** Explicitly reassigns a session's working directory (plan section 11.2), e.g. after the user picks a replacement. */
    public ManagedClaudeSession reassignWorkingDirectory(ManagedSessionId sessionId, Path newWorkingDirectory) {
        Path normalized = newWorkingDirectory.toAbsolutePath().normalize();
        return updateSession(sessionId,
                session -> session.withWorkingDirectory(normalized).withStatus(SessionStatus.INACTIVE));
    }

    public ManagedClaudeSession renameSession(ManagedSessionId sessionId, String newDisplayName) {
        return updateSession(sessionId, session -> session.withDisplayName(newDisplayName));
    }

    /** Records the observed PR lifecycle state of a worktree session's branch (Finish-panel reconciliation). */
    public ManagedClaudeSession updatePrState(ManagedSessionId sessionId, PrState prState,
                                              Optional<Integer> prNumber) {
        return updateSession(sessionId, session -> session.withPr(prState, prNumber));
    }

    /**
     * Registers an existing on-disk Claude conversation (discovered by the
     * resume picker in {@code ~/.claude/projects}) as a managed session, so
     * the normal {@link #resumeSession} path can reopen that exact
     * conversation via {@code claude --resume '<id>'}. Idempotent per
     * Claude session id: if a managed session already tracks {@code
     * claudeSessionId}, that session is returned unchanged instead of
     * creating a duplicate row.
     */
    public ManagedClaudeSession adoptConversation(Repository repository, String claudeSessionId,
                                                  String displayName) {
        ManagedClaudeSession[] result = new ManagedClaudeSession[1];
        stateStore.update(state -> {
            Optional<ManagedClaudeSession> existing = state.sessions().stream()
                    .filter(session -> session.claudeSessionId().map(claudeSessionId::equals).orElse(false))
                    .findFirst();
            if (existing.isPresent()) {
                result[0] = existing.get();
                return state;
            }
            ManagedClaudeSession adopted = newSessionMetadata(repository, displayName)
                    .withClaudeSessionId(Optional.of(claudeSessionId));
            result[0] = adopted;
            List<ManagedClaudeSession> updated = new ArrayList<>(state.sessions());
            updated.add(adopted);
            return state.withSessions(updated);
        });
        return result[0];
    }

    /**
     * Deletes a session's metadata entirely (sidebar quick-action "Delete"),
     * first closing its surface gracefully if one is active. Only this
     * manager's metadata is removed; nothing of claude's own on-disk
     * transcript is touched (plan section 21: never destroy user data).
     */
    public CompletableFuture<Void> deleteSession(ManagedSessionId sessionId) {
        // thenRunAsync: closeSession's future completes on the FX thread
        // (closeGracefully's callback); the metadata removal must not run
        // there.
        return closeSession(sessionId).thenRunAsync(
                () -> stateStore.update(state -> state.withSessions(state.sessions().stream()
                        .filter(session -> !session.id().equals(sessionId))
                        .toList())),
                backgroundExecutor);
    }

    // ---- Close --------------------------------------------------------------

    /** Closes a session's surface (if any is active) using the grace-period defaults. */
    public CompletableFuture<Void> closeSession(ManagedSessionId sessionId) {
        return closeSession(sessionId, DEFAULT_GRACE_PERIOD_MILLIS, DEFAULT_POLL_INTERVAL_MILLIS);
    }

    /**
     * Closes a session's active surface via {@link
     * GhosttySurface#closeGracefully(long, long, Runnable)} -- never {@link
     * GhosttySurface#close()} directly, per the documented live-child-process
     * crash risk -- and updates the persisted session's status/lastOpenedAt
     * afterward. A no-op (completes immediately) if the session has no
     * active surface.
     *
     * <p>{@code lastExitCode} is deliberately left unchanged: {@link
     * GhosttySurface} exposes only {@link GhosttySurface#processExited()}
     * (a boolean), not an actual exit code, so there is nothing more precise
     * to persist here.</p>
     */
    public CompletableFuture<Void> closeSession(ManagedSessionId sessionId, long gracePeriodMillis, long pollIntervalMillis) {
        GhosttySurface surface = activeSurfaces.get(sessionId);
        if (surface == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        Platform.runLater(() -> surface.closeGracefully(gracePeriodMillis, pollIntervalMillis, () -> {
            onSurfaceClosed(sessionId, surface);
            future.complete(null);
        }));
        return future;
    }

    /**
     * Records that a session's child process exited on its own (detected by
     * the UI polling {@link GhosttySurface#processExited()}), without
     * closing the surface -- the terminal stays open so the user can read
     * the final output. Only a {@link SessionStatus#RUNNING} session is
     * updated (idempotent; racing with {@link #closeSession}'s own EXITED
     * update is harmless).
     *
     * @return the updated session, or empty if the session no longer exists
     *         or was not RUNNING
     */
    public Optional<ManagedClaudeSession> markSessionExited(ManagedSessionId sessionId) {
        ManagedClaudeSession[] result = new ManagedClaudeSession[1];
        stateStore.update(state -> {
            Optional<ManagedClaudeSession> running = state.sessions().stream()
                    .filter(session -> session.id().equals(sessionId))
                    .filter(session -> session.status() == SessionStatus.RUNNING)
                    .findFirst();
            if (running.isEmpty()) {
                return state;
            }
            ManagedClaudeSession updated = running.get().withStatus(SessionStatus.EXITED);
            result[0] = updated;
            return withReplacedSession(state, updated);
        });
        return Optional.ofNullable(result[0]);
    }

    private void onSurfaceClosed(ManagedSessionId sessionId, GhosttySurface surface) {
        activeSurfaces.remove(sessionId, surface);
        findSession(sessionId).ifPresent(session -> {
            session.claudeSessionId().ifPresent(activeRegistry::release);
            persistUpdatedSession(session.withStatus(SessionStatus.EXITED));
        });
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            // shutdown() alone would let queued background work (including
            // state-transform submissions) die at JVM exit; give it a
            // bounded drain first.
            backgroundExecutor.shutdown();
            try {
                if (!backgroundExecutor.awaitTermination(CLOSE_AWAIT_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
                    LOG.log(Level.WARNING, "Background executor did not drain within "
                            + CLOSE_AWAIT_TERMINATION_SECONDS + "s; forcing shutdown");
                    backgroundExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                backgroundExecutor.shutdownNow();
            }
        }
        // The store's writer is asynchronous; make every queued state save
        // durable before shutdown proceeds (AGENTS.md: services writing
        // files from background threads must expose a flush).
        stateStore.flush();
    }

    // ---- Command construction (pure; unit-testable without a window) ------

    /**
     * Strips the nested-Claude-Code environment markers before launching
     * {@code claude}. When these variables are present (i.e. this app was
     * itself launched from inside a Claude Code session -- a terminal
     * driven by claude, a dev workflow, etc.), the spawned interactive
     * claude detects the nesting and does NOT persist its transcript: the
     * session then cannot be resumed later ("No conversation found with
     * session ID ..."), silently defeating this manager's whole
     * resume-exact-session feature. Verified empirically: an interactive
     * {@code claude --session-id <uuid>} run with these variables present
     * saves nothing under any id; the identical run with them stripped
     * saves and resumes normally. Managed sessions must always behave like
     * real standalone sessions, so they are unconditionally stripped.
     */
    static final String ENV_CLEANUP_PREFIX = "env -u CLAUDECODE -u CLAUDE_CODE_ENTRYPOINT"
            + " -u CLAUDE_CODE_EXECPATH -u CLAUDE_CODE_SESSION_ID -u CLAUDE_CODE_CHILD_SESSION"
            + " -u CLAUDE_EFFORT ";

    /**
     * Plan section 11.1: {@code claude -n '<name>'} if supported, else plain
     * {@code claude}; additionally {@code --session-id '<uuid>'} when
     * supported, so the exact Claude conversation is known up front and a
     * later resume can target it directly (see {@link #buildResumeCommand}).
     */
    static String buildCreateCommand(ClaudeCapabilities capabilities, String displayName, String claudeSessionId) {
        StringBuilder command = new StringBuilder(ENV_CLEANUP_PREFIX).append("claude");
        if (capabilities.supportsName()) {
            command.append(" -n ").append(shellQuote(displayName));
        }
        if (capabilities.supportsSessionId()) {
            command.append(" --session-id ").append(shellQuote(claudeSessionId));
        }
        return command.toString();
    }

    /** Plan section 11.2's exact fallback chain: id, then name, then bare {@code --resume}. */
    static String buildResumeCommand(ManagedClaudeSession session) {
        if (session.claudeSessionId().isPresent()) {
            return ENV_CLEANUP_PREFIX + "claude --resume " + shellQuote(session.claudeSessionId().get());
        }
        if (session.claudeSessionName().isPresent()) {
            return ENV_CLEANUP_PREFIX + "claude --resume " + shellQuote(session.claudeSessionName().get());
        }
        return ENV_CLEANUP_PREFIX + "claude --resume";
    }

    /** Wraps {@code value} as a single POSIX shell single-quoted argument, safe against embedded shell metacharacters. */
    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    // ---- Helpers ------------------------------------------------------------

    private CompletableFuture<GhosttySurface> createSurfaceOnFxThread(GhosttyApp app, CpmTerminalHost host,
                                                                       double scaleFactor, String command,
                                                                       String workingDirectory) {
        CompletableFuture<GhosttySurface> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                future.complete(GhosttySurface.create(app, host, scaleFactor, command, workingDirectory));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private String defaultDisplayName(Repository repository) {
        long existing = stateStore.state().sessions().stream()
                .filter(session -> session.repositoryId().equals(repository.id()))
                .count();
        return "Session " + (existing + 1);
    }

    private ManagedClaudeSession newSessionMetadata(Repository repository, String displayName) {
        return newSessionMetadata(repository, displayName, Optional.empty());
    }

    /**
     * When {@code worktreeRoot} is present the session lives (and launches
     * claude) inside that worktree checkout rather than the repository's
     * main checkout -- {@code workingDirectory} IS the worktree directory.
     */
    private ManagedClaudeSession newSessionMetadata(Repository repository, String displayName,
                                                    Optional<Path> worktreeRoot) {
        Instant now = Instant.now();
        return new ManagedClaudeSession(
                ManagedSessionId.newId(),
                repository.id(),
                displayName,
                Optional.empty(),
                Optional.empty(),
                worktreeRoot.orElse(repository.root()),
                worktreeRoot,
                SessionStatus.INACTIVE,
                now,
                now,
                Optional.empty(),
                PrState.NONE,
                Optional.empty());
    }

    private void persistNewSession(ManagedClaudeSession session) {
        stateStore.update(state -> {
            List<ManagedClaudeSession> updated = new ArrayList<>(state.sessions());
            updated.add(session);
            return state.withSessions(updated);
        });
    }

    /**
     * Replaces the persisted session with the same id as {@code
     * updatedSession} (a no-op if it was deleted concurrently). The
     * cross-manager lost-update protection this class used to hand-roll
     * (re-reading the freshest disk state and re-applying only the {@code
     * sessions} delta -- see docs/milestone5-report.md for the original
     * data-loss bug) now lives in {@link ApplicationStateStore}: every
     * manager's read-modify-write runs under the store's single lock, and
     * disk writes happen on the store's background writer, never here.
     */
    private void persistUpdatedSession(ManagedClaudeSession updatedSession) {
        stateStore.update(state -> withReplacedSession(state, updatedSession));
    }

    /** Applies the atomic find-and-change-one-session pattern shared by the metadata mutators. */
    private ManagedClaudeSession updateSession(ManagedSessionId sessionId,
                                               UnaryOperator<ManagedClaudeSession> change) {
        ManagedClaudeSession[] result = new ManagedClaudeSession[1];
        stateStore.update(state -> {
            ManagedClaudeSession session = state.sessions().stream()
                    .filter(existing -> existing.id().equals(sessionId))
                    .findFirst()
                    .orElseThrow(() -> new UnknownSessionException(sessionId));
            result[0] = change.apply(session);
            return withReplacedSession(state, result[0]);
        });
        return result[0];
    }

    private static ApplicationState withReplacedSession(ApplicationState state, ManagedClaudeSession updatedSession) {
        return state.withSessions(state.sessions().stream()
                .map(existing -> existing.id().equals(updatedSession.id()) ? updatedSession : existing)
                .toList());
    }

    private ManagedClaudeSession requireSession(ManagedSessionId sessionId) {
        return findSession(sessionId).orElseThrow(() -> new UnknownSessionException(sessionId));
    }

    private Optional<ManagedClaudeSession> findSession(ManagedSessionId sessionId) {
        return stateStore.state().sessions().stream()
                .filter(session -> session.id().equals(sessionId))
                .findFirst();
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
    }

    private static CompletionException wrap(Throwable cause) {
        return (cause instanceof CompletionException completionException) ? completionException : new CompletionException(cause);
    }
}
