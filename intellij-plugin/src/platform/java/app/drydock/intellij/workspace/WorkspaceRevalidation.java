package app.drydock.intellij.workspace;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.process.ProcessRunner;
import app.drydock.process.SshCommandBuilder;
import app.drydock.workspace.BoxShell;
import app.drydock.workspace.CommandRunner;
import app.drydock.workspace.TmuxListing;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The startup revalidation pass for detached remote sessions: after a
 * restart, mark each remote row's box-side liveness without attaching,
 * resuming, or writing anything.
 *
 * <p>Shape (the workspace-persistent-sessions investigation, spec AC-12):
 * remote rows are grouped by box and each box gets <em>exactly one</em>
 * {@code tmux ls -F '#{session_name}'} over the batch ssh seam — never a
 * has-session per session — all boxes probed in parallel on the plugin
 * executor, the whole pass bounded to {@link #PASS_BOUND}. A listed
 * {@code drydock-<id>} is ALIVE; an unlisted one, or a box whose tmux server
 * is not running (a <em>valid</em> empty answer, never a failure), is DEAD;
 * anything else is UNKNOWN plus a WARNING carrying the stderr excerpt. The
 * verdict goes to {@link RemoteSessionLiveness} only: the row itself is
 * never touched — it stays IDLE as decoded and resumable by conversation id —
 * so {@code ApplicationStateStore.update} is never called here (INV-21 is
 * vacuously honored; there is no store reference to abuse).</p>
 *
 * <p>Startup discipline (INV-12): this class never creates, bootstraps, or
 * re-creates a box, never spawns a console, never opens a dialog or prompts.
 * Its one capability is reading the session catalog and probing tmux —
 * everything a box needs beyond that belongs to the explicit user-triggered
 * launch and resume paths.</p>
 *
 * <p>Once per IDE run: {@link #runOnce()} coalesces every trigger — the
 * first one (with the flag on) starts the pass and every later trigger,
 * concurrent or not, receives the same future. The application-scoped owner
 * is {@code DrydockPluginService}, so "one instance per IDE run" falls out
 * of the service's lifetime; the plugin's startup activity is the one
 * trigger, firing from its existing background task after the warm-up (the
 * pass then runs on that task's thread pool, not the EDT).</p>
 */
public final class WorkspaceRevalidation {

    private static final Logger LOG = System.getLogger(WorkspaceRevalidation.class.getName());

    /**
     * The whole pass's bound: the returned future completes within this,
     * whatever the boxes do. Boxes that have not answered by then are
     * marked UNKNOWN and their probes are cancelled, so no ssh child
     * outlives the pass.
     */
    public static final Duration PASS_BOUND = Duration.ofSeconds(30);

    /**
     * The per-box {@code tmux ls} budget, sized inside {@link #PASS_BOUND}
     * so the parallel listings plus the bounded join fit the whole-pass
     * bound with scheduling slack.
     */
    static final Duration TMUX_LS_TIMEOUT = Duration.ofSeconds(25);

    private final Supplier<List<ManagedAgentSession>> sessions;
    private final Executor executor;
    private final RemoteSessionLiveness liveness;
    private final CommandRunner runner;
    private final BooleanSupplier enabled;
    private final Object startLock = new Object();
    private CompletableFuture<Void> run;

    /**
     * @param sessions the read-only session catalog — the read seam only;
     *                 this class holds no store reference, so it cannot write
     *                 a row even by mistake
     * @param executor the plugin's shared executor — no executor of its own
     *                 (INV-27); every probe and the pass itself run on it
     * @param liveness the application-level liveness map this pass feeds
     * @param runner   the command seam the box probes run through; the
     *                 production instance is {@link CommandRunner#PRODUCTION}
     * @param enabled  the workspace-sessions opt-in flag; with the flag off
     *                 this pass never runs
     */
    public WorkspaceRevalidation(Supplier<List<ManagedAgentSession>> sessions, Executor executor,
                                 RemoteSessionLiveness liveness, CommandRunner runner, BooleanSupplier enabled) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.liveness = Objects.requireNonNull(liveness, "liveness");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
    }

    /**
     * Runs the revalidation pass once per IDE run, only while the flag is
     * on. A trigger while the flag is off runs nothing and leaves the
     * once-guard unconsumed, so enabling the feature mid-run still allows
     * one pass at the next project open; a trigger after the pass has run,
     * or while it is running, coalesces into the first pass's future.
     * The returned future never completes exceptionally — the pass logs its
     * own failures — so fire-and-forget triggers are safe.
     */
    public CompletableFuture<Void> runOnce() {
        if (!enabled.getAsBoolean()) {
            return CompletableFuture.completedFuture(null);
        }
        synchronized (startLock) {
            if (run == null) {
                run = CompletableFuture.runAsync(this::pass, executor);
            }
            return run;
        }
    }

    private void pass() {
        try {
            Map<String, List<ManagedAgentSession>> rowsByBox = groupRemoteRowsByBox();
            if (rowsByBox.isEmpty()) {
                return;
            }
            Map<String, CompletableFuture<BoxListing>> listings = startListings(rowsByBox);
            awaitWithinBound(listings);
            applyVerdicts(rowsByBox, listings);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, "Workspace session revalidation was interrupted before every box answered;"
                    + " the unanswered rows are UNKNOWN", e);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Workspace session revalidation failed", e);
        }
    }

    /** Remote rows grouped by box ssh host: one group, one tmux ls. */
    private Map<String, List<ManagedAgentSession>> groupRemoteRowsByBox() {
        Map<String, List<ManagedAgentSession>> rowsByBox = new LinkedHashMap<>();
        for (ManagedAgentSession session : sessions.get()) {
            session.workspaceBox().ifPresent(binding -> rowsByBox
                    .computeIfAbsent(binding.sshRemote().host(), host -> new ArrayList<>())
                    .add(session));
        }
        return rowsByBox;
    }

    /** Exactly one tmux ls per box, all started at once on the shared executor. */
    private Map<String, CompletableFuture<BoxListing>> startListings(
            Map<String, List<ManagedAgentSession>> rowsByBox) {
        BoxShell shell = new BoxShell(runner);
        Map<String, CompletableFuture<BoxListing>> listings = new LinkedHashMap<>();
        for (String host : rowsByBox.keySet()) {
            listings.put(host, CompletableFuture.supplyAsync(() -> listing(shell, host), executor));
        }
        return listings;
    }

    private BoxListing listing(BoxShell shell, String host) {
        try {
            return new BoxListing(host, shell.tmuxLs(host, TMUX_LS_TIMEOUT));
        } catch (InterruptedException e) {
            // The pass gave up on this box (cancellation) or the IDE is
            // shutting down: the answer is UNKNOWN, never a silent DEAD.
            Thread.currentThread().interrupt();
            return new BoxListing(host, new TmuxListing.Unknown("the tmux ls probe was interrupted"));
        }
    }

    /**
     * Waits for every listing within {@link #PASS_BOUND}. On the bound, or
     * an interrupt, the outstanding probes are cancelled — cancelling
     * interrupts their virtual threads, which {@code ProcessRunner} answers
     * by destroying the ssh child — so nothing the pass started outlives it;
     * the rows those probes owned are classified UNKNOWN below.
     */
    private void awaitWithinBound(Map<String, CompletableFuture<BoxListing>> listings)
            throws InterruptedException {
        CompletableFuture<Void> all =
                CompletableFuture.allOf(listings.values().toArray(CompletableFuture[]::new));
        try {
            all.get(PASS_BOUND.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            cancelOutstanding(listings);
            LOG.log(Level.WARNING, "Workspace session revalidation did not complete within "
                    + PASS_BOUND.toSeconds() + " s; the boxes that did not answer are UNKNOWN");
        } catch (ExecutionException e) {
            // listing() never throws, so this is unexpected; classify below.
            LOG.log(Level.WARNING, "A box listing failed during workspace session revalidation", e);
        }
    }

    private static void cancelOutstanding(Map<String, CompletableFuture<BoxListing>> listings) {
        for (CompletableFuture<BoxListing> listing : listings.values()) {
            listing.cancel(true);
        }
    }

    private void applyVerdicts(Map<String, List<ManagedAgentSession>> rowsByBox,
                               Map<String, CompletableFuture<BoxListing>> listings) {
        for (Map.Entry<String, List<ManagedAgentSession>> box : rowsByBox.entrySet()) {
            TmuxListing.Result result = resultOf(listings.get(box.getKey()));
            if (result instanceof TmuxListing.Unknown unknown) {
                LOG.log(Level.WARNING, box.getKey() + ": revalidation: " + unknown.reason());
            }
            for (ManagedAgentSession row : box.getValue()) {
                liveness.set(row.id(), verdict(row.id(), result));
            }
        }
    }

    private static TmuxListing.Result resultOf(CompletableFuture<BoxListing> task) {
        if (task.isCancelled()) {
            return new TmuxListing.Unknown("tmux ls did not finish within the "
                    + PASS_BOUND.toSeconds() + " s revalidation bound");
        }
        try {
            return task.join().result();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new TmuxListing.Unknown(ProcessRunner.excerpt(cause.toString()));
        }
    }

    private static RemoteSessionLiveness.State verdict(ManagedSessionId id, TmuxListing.Result result) {
        if (result instanceof TmuxListing.Sessions(Set<String> names)) {
            // An unlisted id, and a no-server box's valid empty listing, are
            // the same honest answer: nothing with this id is running there.
            return names.contains(SshCommandBuilder.tmuxSessionName(id))
                    ? RemoteSessionLiveness.State.ALIVE
                    : RemoteSessionLiveness.State.DEAD;
        }
        return RemoteSessionLiveness.State.UNKNOWN;
    }

    /** One box's tmux ls verdict, kept with its host so a cancelled or failed probe stays attributable. */
    private record BoxListing(String host, TmuxListing.Result result) {
    }
}
