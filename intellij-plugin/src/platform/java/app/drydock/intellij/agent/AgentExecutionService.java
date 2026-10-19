package app.drydock.intellij.agent;

import app.drydock.agent.api.LaunchPlan;
import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.terminal.ShellCommandAdapter;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.PtyCommandLine;
import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.terminal.TerminalExecutionConsole;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The project's live agent executions: one {@code ManagedSessionId ->
 * (ProcessHandler, console)} map per worktree window.
 *
 * <p>This is the middle tier of the ownership split. The durable catalog is
 * application-scoped and outlives every process; this service owns only what
 * is alive <em>now</em>, in <em>this</em> window. Nothing here persists
 * anything: it reports terminations to a {@link AgentExecutionListener} and
 * the session service does the writing.</p>
 *
 * <h2>What the platform's events mean, and what they do not</h2>
 *
 * <p>{@code ProcessHandler} events are authoritative for live state. Editor
 * tab events are presentation only — closing the console tab hides it and the
 * process keeps running, which is why this service holds the console rather
 * than the editor holding the process. The v2 runtime had this the other way
 * round and a closed tab silently became a deleted session.</p>
 *
 * <h2>Stopping</h2>
 *
 * <p>Stop is <strong>non-soft</strong>, then a force-kill after two seconds.
 * The default soft stop sends SIGINT, which pi treats as "cancel the current
 * turn" rather than "exit": the Phase 0B spike logged an explicit Stop at
 * 23:10:28 whose handler was still "terminating" at 23:10:54, with Start
 * seeing a stale live handle in between. {@code setShouldKillProcessSoftly
 * (false)} plus the timed {@code killProcess()} is what actually ends a
 * session exactly once.</p>
 *
 * <p>The force-kill timer runs on the application-scoped plugin executor, not
 * a project one, so a stop requested while the project is closing still has
 * somewhere to run. On IDE quit the executor is shut down and a pending
 * force-kill is dropped; the JVM exit reaps the remaining children, which is
 * the platform's own guarantee for {@code OSProcessHandler} and not something
 * worth re-implementing.</p>
 *
 * <h2>No rerun</h2>
 *
 * <p>Nothing here exposes a platform rerun. Phase 0B's first shape used
 * {@code RunProfile}/{@code RunContentBuilder}, whose native Rerun started a
 * process outside drydock's registry — a second live agent for a session this
 * map said had one.</p>
 */
public final class AgentExecutionService implements AgentConsoleHost, Disposable {

    private static final Logger LOG = System.getLogger(AgentExecutionService.class.getName());

    /** How long a destroyed process has to go away before it is killed outright. */
    private static final long FORCE_KILL_DELAY_MILLIS = 2_000;

    private final Project project;
    private final Map<ManagedSessionId, AgentExecution> executions = new ConcurrentHashMap<>();

    public AgentExecutionService(Project project) {
        this.project = Objects.requireNonNull(project, "project");
    }

    public static AgentExecutionService getInstance(Project project) {
        return project.getService(AgentExecutionService.class);
    }

    /** The live-or-just-terminated execution for {@code sessionId}, if this window has one. */
    public Optional<AgentExecution> execution(ManagedSessionId sessionId) {
        return Optional.ofNullable(executions.get(sessionId));
    }

    /** Whether {@code sessionId} has a process running in this window right now. */
    public boolean isRunning(ManagedSessionId sessionId) {
        AgentExecution execution = executions.get(sessionId);
        return execution != null && !execution.handler().isProcessTerminated();
    }

    /** Every session this window has started, running or stopped. */
    public List<ManagedSessionId> knownSessions() {
        return List.copyOf(executions.keySet());
    }

    /** Every session with a process alive in this window (what the close confirmation asks about). */
    public List<ManagedSessionId> runningSessions() {
        return executions.entrySet().stream()
                .filter(entry -> !entry.getValue().handler().isProcessTerminated())
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Starts exactly one execution for {@code sessionId} from {@code plan}.
     *
     * <p>Spawning and console creation are split across threads deliberately:
     * {@code KillableProcessHandler}'s constructor runs {@code createProcess}
     * (a blocking fork/exec, on a PTY), so it is built on the caller's
     * background thread, while {@code TerminalExecutionConsole} builds Swing
     * components and must be constructed on the EDT. {@code startNotify()}
     * comes last, after the console has attached, so no first output is
     * dropped — a TUI's opening screen is most of what the user sees.</p>
     *
     * <p>Rejects a second start for a session that already has a live process:
     * one agent per session is the invariant the whole model rests on. The
     * returned future then completes exceptionally and nothing is spawned.</p>
     *
     * <p>MUST be called off the EDT.</p>
     */
    public CompletableFuture<AgentExecution> start(ManagedSessionId sessionId, Path workingDirectory,
                                                   LaunchPlan plan) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(plan, "plan");
        if (isRunning(sessionId)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Session " + sessionId + " already has a running agent in this window"));
        }
        KillableProcessHandler handler;
        try {
            handler = spawn(workingDirectory, plan);
        } catch (ExecutionException | RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        CompletableFuture<AgentExecution> started = new CompletableFuture<>();
        // ModalityState.any(): a start triggered from a modal dialog (the new
        // session dialog is one) must still land while that dialog is up.
        //
        // Deliberately NOT passing project.getDisposed() as an expiry
        // condition: an expired runnable is dropped silently, which would
        // leave this future pending forever and the caller's session row
        // stuck at STARTING. The disposal check is inside instead, so the
        // caller always gets an answer.
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                forceStop(handler);
                started.completeExceptionally(new IllegalStateException(
                        "The window closed before " + sessionId + "'s console could be created"));
                return;
            }
            try {
                started.complete(attachConsole(sessionId, handler));
            } catch (RuntimeException e) {
                // The process is already alive at this point; a console that
                // could not be built must not leave it orphaned and invisible.
                forceStop(handler);
                started.completeExceptionally(e);
            }
        }, ModalityState.any());
        return started;
    }

    private KillableProcessHandler spawn(Path workingDirectory, LaunchPlan plan)
            throws ExecutionException {
        // The plan's command is a POSIX shell string (env assignment
        // prefixes, quoted arguments), so it reaches the shell whole as one
        // -c argument -- ShellCommandAdapter owns that expansion and is
        // shared with everything else that launches an agent.
        PtyCommandLine commandLine = new PtyCommandLine(ShellCommandAdapter.shellArgv(plan))
                .withConsoleMode(false)
                .withInitialColumns(120)
                .withInitialRows(30);
        commandLine.withWorkDirectory(workingDirectory.toString());
        commandLine.withCharset(StandardCharsets.UTF_8);
        KillableProcessHandler handler = new KillableProcessHandler(commandLine) {
            /**
             * Null, so {@code startNotify()} does not print the command line
             * into the console. For a Run configuration that echo is useful;
             * here it put five lines of {@code /bin/zsh -l -i -c "env -u …
             * --settings …/hooks/settings.json"} above the agent's own screen
             * on every start and resume — internals the user did not ask for,
             * in the one place they read the agent.
             */
            @Override
            public String getCommandLineForLog() {
                return null;
            }
        };
        // Non-soft from birth, not only at Stop: a soft destroy anywhere in
        // the lifecycle (project close, IDE shutdown hooks) would send the
        // SIGINT that pi survives.
        handler.setShouldKillProcessSoftly(false);
        return handler;
    }

    /** EDT: build the console around the (not yet started) handler, then start it. */
    private AgentExecution attachConsole(ManagedSessionId sessionId, KillableProcessHandler handler) {
        TerminalExecutionConsole console = new TerminalExecutionConsole(project, handler);
        // The console registers itself under ROOT_DISPOSABLE, so without a
        // parent it is reported as a memory leak at IDE shutdown even though
        // dispose() below would eventually have reached it. This service is
        // the right parent: the console's lifetime is the window's, not the
        // tab's — closing the tab must leave the agent running.
        Disposer.register(this, console);
        AgentExecution execution = new AgentExecution(sessionId, handler, console, Instant.now());
        executions.put(sessionId, execution);
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void processTerminated(ProcessEvent event) {
                // The row goes idle either way; which idle status it gets
                // depends on whether drydock asked for this, which only the
                // execution knows.
                publish().executionTerminated(sessionId, execution.stopRequested(), event.getExitCode());
            }
        });
        handler.startNotify();
        publish().executionStarted(sessionId);
        return execution;
    }

    private AgentExecutionListener publish() {
        return project.getMessageBus().syncPublisher(AgentExecutionListener.TOPIC);
    }

    @Override
    public CompletableFuture<Void> startConsole(ManagedSessionId sessionId, Path workingDirectory,
                                                LaunchPlan plan) {
        return start(sessionId, workingDirectory, plan).thenApply(execution -> null);
    }

    @Override
    public void stopConsole(ManagedSessionId sessionId) {
        stop(sessionId);
    }

    @Override
    public void forgetConsole(ManagedSessionId sessionId) {
        forget(sessionId);
    }

    /**
     * Stops {@code sessionId}'s process: non-soft destroy now, force-kill in
     * two seconds if it is still there. A session with no live process is a
     * no-op, so a double click on Stop cannot produce two terminations.
     */
    public void stop(ManagedSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        AgentExecution execution = executions.get(sessionId);
        if (execution == null || execution.handler().isProcessTerminated()) {
            return;
        }
        if (!execution.requestStop()) {
            return;   // already stopping; the force-kill is already scheduled
        }
        KillableProcessHandler handler = execution.handler();
        handler.setShouldKillProcessSoftly(false);
        handler.destroyProcess();
        scheduleForceKill(sessionId, handler);
    }

    /**
     * Stops every execution in this window. Used by project close and by the
     * quit path; both leave the durable rows idle, never deleted.
     */
    public void stopAll() {
        for (ManagedSessionId sessionId : runningSessions()) {
            stop(sessionId);
        }
    }

    private void scheduleForceKill(ManagedSessionId sessionId, KillableProcessHandler handler) {
        ExecutorService executor = DrydockPluginService.getInstance().executor();
        try {
            CompletableFuture.delayedExecutor(FORCE_KILL_DELAY_MILLIS, TimeUnit.MILLISECONDS, executor)
                    .execute(() -> forceStop(handler));
        } catch (RejectedExecutionException e) {
            // The IDE is shutting down and the executor is drained. The JVM
            // exit reaps the child; nothing is leaked, and logging it as a
            // failure would be misleading.
            LOG.log(Level.DEBUG, "Skipping force-kill of {0} during shutdown", sessionId);
        }
    }

    private static void forceStop(KillableProcessHandler handler) {
        if (handler.isProcessTerminated()) {
            return;
        }
        if (handler.canKillProcess()) {
            handler.killProcess();
        } else {
            handler.destroyProcess();
        }
    }

    /**
     * Project close (and the window's own disposal) stops every execution it
     * owns. The durable rows stay — they go idle, and only an explicit Forget
     * removes one.
     */
    @Override
    public void dispose() {
        stopAll();
        // The consoles are Disposer children of this service, so the platform
        // disposes them as part of disposing it; doing it here too would
        // double-dispose.
        executions.clear();
    }

    /** Drops a session's execution entirely, e.g. when the session is forgotten. */
    public void forget(ManagedSessionId sessionId) {
        stop(sessionId);
        AgentExecution removed = executions.remove(sessionId);
        if (removed != null) {
            // Disposer.dispose, not console.dispose: the console is a child of
            // this service, and disposing it directly would leave a dead node
            // in the tree until the window closes.
            ApplicationManager.getApplication().invokeLater(
                    () -> Disposer.dispose(removed.console()), ModalityState.any());
        }
    }
}
