package app.drydock.intellij.agent;

import app.drydock.agent.api.LaunchPlan;
import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.project.ProjectIdentity;
import app.drydock.intellij.terminal.ShellCommandAdapter;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.PtyCommandLine;
import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.execution.process.NopProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.terminal.ProcessHandlerTtyConnector;
import com.intellij.terminal.TerminalExecutionConsole;
import com.jediterm.core.util.TermSize;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Every live agent process in the IDE, and which window — if any — shows
 * each one.
 *
 * <p>The process belongs to the IDE, not to a window: a window is only a view
 * of it. That is what lets one window host agents from other projects and
 * worktrees, move an agent's console into its own window, and close without
 * stopping the agents it showed (cross-project tabs, v1.1). Before, each
 * window's {@link AgentExecutionService} owned its processes, so an agent died
 * with the window that started it and could be shown nowhere else.</p>
 *
 * <h2>Showing an agent somewhere</h2>
 *
 * <p>{@link #show} builds a fresh {@code TerminalExecutionConsole} in the
 * target window, fills it from the agent's {@link ReplayBuffer}, wires it to
 * the process for input only, and disposes the console it replaces — the
 * spike's variant (b). Output reaches a console only through the one
 * {@link ProcessListener} each process has, via its {@link OutputRouter}, so a
 * move loses and doubles nothing. A new console rather than the old one moved:
 * a console is built for one project, and moving it would leave it holding a
 * window that may be disposed. A session is shown in at most one window.</p>
 *
 * <h2>Stopping</h2>
 *
 * <p>Stop is <strong>non-soft</strong>, then a force-kill after two seconds.
 * The default soft stop sends SIGINT, which pi treats as "cancel the current
 * turn" rather than "exit". The force-kill timer runs on the plugin executor;
 * on IDE quit the executor is shut down and the JVM exit reaps the children.</p>
 *
 * <h2>Who hears an end</h2>
 *
 * <p>The session service of the window that started the agent persists its
 * end, called directly — it touches only application state, so this works
 * after that window has closed. The {@link AgentExecutionListener#TOPIC} on
 * the application bus is for presentation only; were every window's session
 * service subscribed to it, each exit would be persisted once per window.</p>
 *
 * <p>Nothing here exposes a platform rerun: a native Rerun would start a
 * process outside this registry, a second live agent for one session.</p>
 */
public final class AgentExecutionRegistry implements Disposable {

    private static final Logger LOG = System.getLogger(AgentExecutionRegistry.class.getName());

    /** How long a destroyed process has to go away before it is killed outright. */
    private static final long FORCE_KILL_DELAY_MILLIS = 2_000;

    /** The PTY's size until a console lays itself out. */
    private static final int INITIAL_COLUMNS = 120;
    private static final int INITIAL_ROWS = 30;

    private final Map<ManagedSessionId, AgentExecution> executions = new ConcurrentHashMap<>();
    /** Sessions whose console is on its way to another window (a jump, while that window opens). */
    private final Set<ManagedSessionId> moving = ConcurrentHashMap.newKeySet();
    /** Sessions whose agent a click has just started, until the process is live or the start failed. */
    private final Set<ManagedSessionId> starting = ConcurrentHashMap.newKeySet();
    private final LaunchClaims launchClaims = new LaunchClaims(this::isRunning);

    /** The launch claims every session launch in this IDE goes through; see {@link LaunchClaims}. */
    public LaunchClaims launchClaims() {
        return launchClaims;
    }

    public static AgentExecutionRegistry getInstance() {
        return ApplicationManager.getApplication().getService(AgentExecutionRegistry.class);
    }

    // ---- queries (memory only; any thread) ---------------------------------------

    /** The live-or-just-ended execution for {@code sessionId}, wherever it is shown. */
    public Optional<AgentExecution> execution(ManagedSessionId sessionId) {
        return Optional.ofNullable(executions.get(sessionId));
    }

    /** The window showing {@code sessionId}; empty when none does or nothing runs. */
    public Optional<Project> host(ManagedSessionId sessionId) {
        return execution(sessionId).flatMap(AgentExecution::host);
    }

    /** Whether {@code sessionId} has a live process anywhere in the IDE. */
    public boolean isRunning(ManagedSessionId sessionId) {
        return execution(sessionId).map(AgentExecution::isRunning).orElse(false);
    }

    /** Every session with a live process, shown or not. */
    public List<ManagedSessionId> runningSessions() {
        return executions.values().stream().filter(AgentExecution::isRunning)
                .map(AgentExecution::sessionId).toList();
    }

    /** Every session {@code project} shows, running or stopped. */
    public List<ManagedSessionId> shownIn(Project project) {
        return executions.values().stream()
                .filter(execution -> execution.host().map(project::equals).orElse(false))
                .map(AgentExecution::sessionId).toList();
    }

    /** Every session {@code project} shows that is running. */
    public List<ManagedSessionId> runningShownIn(Project project) {
        return executions.values().stream()
                .filter(AgentExecution::isRunning)
                .filter(execution -> execution.host().map(project::equals).orElse(false))
                .map(AgentExecution::sessionId).toList();
    }

    /** Sessions on their way to another window; see {@link #markMoving}. */
    public Set<ManagedSessionId> moving() {
        return Set.copyOf(moving);
    }

    /** Sessions whose agent start is in flight; see {@link #markStarting}. */
    public Set<ManagedSessionId> starting() {
        return Set.copyOf(starting);
    }

    /** Whether {@code sessionId}'s console is on its way to another window. O(1), for per-update callers. */
    public boolean isMoving(ManagedSessionId sessionId) {
        return moving.contains(sessionId);
    }

    /** Whether {@code sessionId}'s agent start is in flight. O(1), for per-update callers. */
    public boolean isStarting(ManagedSessionId sessionId) {
        return starting.contains(sessionId);
    }

    /**
     * Marks {@code sessionId} as moving to another window, or no longer. A
     * window closing meanwhile must neither stop nor release it: it is about
     * to be shown elsewhere. Publishes {@code executionMoving}.
     */
    public void markMoving(ManagedSessionId sessionId, boolean isMoving) {
        boolean changed = isMoving ? moving.add(sessionId) : moving.remove(sessionId);
        if (changed) {
            publish().executionMoving(sessionId, isMoving);
        }
    }

    /**
     * Marks {@code sessionId} as having an agent start in flight, or no longer
     * — the span from a Start/Resume click to the process being live or the
     * start failing, during which the guard {@code busy} must hold the
     * lifecycle commands off (A11). Like {@link #markMoving}, publishes
     * {@code executionStarting} only on change.
     */
    public void markStarting(ManagedSessionId sessionId, boolean isStarting) {
        boolean changed = isStarting ? starting.add(sessionId) : starting.remove(sessionId);
        if (changed) {
            publish().executionStarting(sessionId, isStarting);
        }
    }

    // ---- start -----------------------------------------------------------------

    /**
     * Starts exactly one agent for {@code sessionId} from {@code plan}, shown
     * in {@code host}; {@code owner} is told when it ends.
     *
     * <p>Spawning and showing are split across threads deliberately:
     * {@code KillableProcessHandler}'s constructor forks on a PTY (blocking),
     * so it runs on the caller's background thread, while the console is
     * Swing and is built on the EDT. {@code startNotify()} comes last, after
     * the console is attached, so no first output is dropped.</p>
     *
     * <p>Fails, spawning nothing, when the session already has a live process
     * anywhere — one agent per session is the invariant the model rests on.</p>
     *
     * <p>MUST be called off the EDT.</p>
     */
    public CompletableFuture<AgentExecution> start(ManagedSessionId sessionId, Path workingDirectory,
                                                   LaunchPlan plan, Project host, AgentExecutionListener owner) {
        Objects.requireNonNull(plan, "plan");
        // The plan's command is a POSIX shell string (env assignment
        // prefixes, quoted arguments), so it reaches the shell whole as one
        // -c argument -- ShellCommandAdapter owns that expansion.
        return startArgv(sessionId, workingDirectory, ShellCommandAdapter.shellArgv(plan), host, owner);
    }

    /** {@link #start} for a ready argv: the same path minus the shell expansion. Test seam. */
    CompletableFuture<AgentExecution> startArgv(ManagedSessionId sessionId, Path workingDirectory, List<String> argv,
                                                Project host, AgentExecutionListener owner) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(argv, "argv");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(owner, "owner");
        if (isRunning(sessionId)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Session " + sessionId + " already has a running agent"));
        }
        KillableProcessHandler handler;
        try {
            handler = spawn(workingDirectory, argv);
        } catch (ExecutionException | RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        ProjectIdentity identity = ProjectIdentity.forDirectory(workingDirectory,
                Optional.ofNullable(workingDirectory.getFileName()).map(Path::toString)
                        .orElse(workingDirectory.toString()));
        AgentExecution execution = new AgentExecution(sessionId, handler, workingDirectory, Instant.now(),
                new SessionPlace(identity.repository(), identity.branch()),
                new OutputRouter(new ReplayBuffer()), owner);
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                execution.router().onText(event.getText());
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                terminated(execution, event.getExitCode());
            }
        });
        // Claimed atomically: two starts racing past the isRunning check
        // above must not both end up with a live agent.
        AgentExecution[] replaced = new AgentExecution[1];
        boolean claimed = executions.compute(sessionId, (id, existing) -> {
            if (existing != null && existing.isRunning()) {
                return existing;
            }
            replaced[0] = existing;
            return execution;
        }) == execution;
        if (!claimed) {
            forceStop(handler);
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Session " + sessionId + " already has a running agent"));
        }
        CompletableFuture<AgentExecution> started = new CompletableFuture<>();
        // ModalityState.any(): a start from a modal dialog must still land
        // while that dialog is up. Not expiring on the host's disposal: an
        // expired runnable is dropped silently, which would leave the caller's
        // row stuck at STARTING. The disposal check is inside instead.
        ApplicationManager.getApplication().invokeLater(() -> {
            if (host.isDisposed()) {
                executions.remove(sessionId, execution);
                forceStop(handler);
                started.completeExceptionally(new IllegalStateException(
                        "The window closed before " + sessionId + "'s console could be created"));
                return;
            }
            try {
                // The stopped transcript of the session's previous run, if a
                // window still shows it, gives way to the new run's console.
                Optional<TerminalExecutionConsole> previous = Optional.ofNullable(replaced[0])
                        .flatMap(AgentExecution::console);
                moveTo(sessionId, host);
                previous.ifPresent(Disposer::dispose);
                handler.startNotify();
                publish().executionStarted(sessionId);
                started.complete(execution);
            } catch (RuntimeException e) {
                // The process is already alive here; a console that could not
                // be built must not leave it orphaned and invisible.
                executions.remove(sessionId, execution);
                forceStop(handler);
                started.completeExceptionally(e);
            }
        }, ModalityState.any());
        return started;
    }

    private static KillableProcessHandler spawn(Path workingDirectory, List<String> argv) throws ExecutionException {
        PtyCommandLine commandLine = new PtyCommandLine(argv)
                .withConsoleMode(false)
                .withInitialColumns(INITIAL_COLUMNS)
                .withInitialRows(INITIAL_ROWS);
        commandLine.withWorkDirectory(workingDirectory.toString());
        commandLine.withCharset(StandardCharsets.UTF_8);
        KillableProcessHandler handler = new KillableProcessHandler(commandLine) {
            /**
             * Null, so {@code startNotify()} does not print the command line
             * into the console: five lines of {@code /bin/zsh -l -i -c "env
             * -u …"} above the agent's own screen on every start.
             */
            @Override
            public String getCommandLineForLog() {
                return null;
            }
        };
        // Non-soft from birth, not only at Stop: a soft destroy anywhere in
        // the lifecycle would send the SIGINT that pi survives.
        handler.setShouldKillProcessSoftly(false);
        return handler;
    }

    private void terminated(AgentExecution execution, Integer exitCode) {
        ManagedSessionId sessionId = execution.sessionId();
        try {
            execution.owner().executionTerminated(sessionId, execution.stopRequested(), exitCode);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Could not record the end of " + sessionId + ": " + e);
        }
        if (execution.host().isEmpty()) {
            // Nothing shows it, so there is no transcript to keep around.
            executions.remove(sessionId, execution);
        }
        publish().executionTerminated(sessionId, execution.stopRequested(), exitCode);
    }

    // ---- show / release ---------------------------------------------------------

    /**
     * Shows {@code sessionId} in {@code target}: a no-op when it already
     * does, otherwise a new console there, filled with the agent's history,
     * replacing the one that showed it before. Empty when the registry no
     * longer knows the session (it ended while no window showed it). EDT.
     */
    public Optional<AgentExecution> show(ManagedSessionId sessionId, Project target) {
        AgentExecution execution = executions.get(sessionId);
        if (execution == null) {
            return Optional.empty();
        }
        if (execution.host().map(target::equals).orElse(false) && execution.console().isPresent()) {
            return Optional.of(execution);
        }
        return Optional.of(moveTo(sessionId, target));
    }

    /**
     * The move itself, without {@link #show}'s already-there shortcut. Package
     * scope so tests with one project can rebuild the console the way showing
     * the agent in another window does. EDT.
     */
    AgentExecution moveTo(ManagedSessionId sessionId, Project target) {
        Objects.requireNonNull(target, "target");
        ApplicationManager.getApplication().assertIsDispatchThread();
        AgentExecution execution = executions.get(sessionId);
        if (execution == null) {
            throw new IllegalStateException("No agent for " + sessionId);
        }
        Optional<TerminalExecutionConsole> previous = execution.console();
        TerminalExecutionConsole console = new TerminalExecutionConsole(target, INITIAL_COLUMNS, INITIAL_ROWS, null);
        // The window's service is the parent: a console is built for one
        // project and must not outlive it, even if a close path missed it.
        Disposer.register(AgentExecutionService.getInstance(target), console);
        // Paths the agent prints open in its own worktree's window.
        console.addMessageFilter(new WorktreeFileLinks(execution.workingDirectory()));
        // Feed output through TerminalExecutionConsole's normal process path.
        // Calling console.print() is not equivalent for a PTY: it treats CR
        // and LF as document line separators and rewrites them to CRLF. A
        // fullscreen TUI uses bare CR and LF as independent cursor controls,
        // so that rewrite progressively tears its screen apart (most visibly
        // after scrolling Claude's command palette).
        //
        // A disposable proxy carries routed output while the real connector
        // still owns input and resize. Attaching the console directly to the
        // long-lived real handler would leave the platform's private listener
        // retaining every console ever moved away from.
        ProcessHandlerTtyConnector tty = new ProcessHandlerTtyConnector(execution.handler(), StandardCharsets.UTF_8);
        NopProcessHandler output = new NopProcessHandler();
        console.attachToProcess(output, tty, true);
        output.startNotify();
        execution.shownIn(target, console);
        ProcessHandlerTtyConnector input = tty;
        // Set by the console's own disposal (a window closing, or a move):
        // a chunk arriving after it must not reach a disposed console.
        AtomicBoolean disposed = new AtomicBoolean();
        Disposer.register(console, () -> {
            disposed.set(true);
            if (!output.isProcessTerminated()) {
                output.destroyProcess();
            }
        });
        execution.router().attach(text -> {
            if (!disposed.get()) {
                output.notifyTextAvailable(text, ProcessOutputTypes.STDOUT);
            }
        }, () -> {
            if (execution.isRunning()) {
                redraw(console, input);
            }
        });
        previous.ifPresent(Disposer::dispose);
        publish().executionShown(sessionId, target);
        return execution;
    }

    /**
     * Asks the agent to repaint: a resize one column wider and back, which
     * every supported TUI answers with a full redraw. A replayed history can
     * be trimmed past the sequences that set the agent's screen up; without
     * this the new console would show it wrong until the agent next drew.
     */
    private static void redraw(TerminalExecutionConsole console, ProcessHandlerTtyConnector tty) {
        try {
            TermSize size = console.getTerminalWidget().getTerminal().getSize();
            if (size == null || size.getColumns() <= 0 || size.getRows() <= 0) {
                return;
            }
            tty.resize(new TermSize(size.getColumns() + 1, size.getRows()));
            tty.resize(size);
        } catch (RuntimeException e) {
            // A repaint is cosmetic; a console that cannot be resized yet
            // will be when it is laid out.
            LOG.log(Level.DEBUG, () -> "Could not nudge the agent to repaint: " + e);
        }
    }

    /**
     * No window shows {@code sessionId} any more; the agent keeps running and
     * its output is still buffered. An agent that has already ended is
     * forgotten, since nothing will ever show its transcript. EDT.
     */
    public void release(ManagedSessionId sessionId) {
        AgentExecution execution = executions.get(sessionId);
        if (execution == null) {
            return;
        }
        execution.router().detach();
        Optional<TerminalExecutionConsole> console = execution.console();
        execution.notShown();
        console.ifPresent(Disposer::dispose);
        if (!execution.isRunning()) {
            executions.remove(sessionId, execution);
        }
        publish().executionReleased(sessionId);
    }

    /**
     * The window {@code project} is going away: whatever it still shows and
     * runs is stopped (the backstop for close paths that did not decide),
     * except a session moving to another window, and what it shows that has
     * ended is forgotten. Its consoles are its own
     * Disposer children, so the platform disposes them.
     */
    public void hostClosed(Project project) {
        for (AgentExecution execution : executions.values()) {
            if (!execution.host().map(project::equals).orElse(false)) {
                continue;
            }
            execution.router().detach();
            execution.notShown();
            if (moving.contains(execution.sessionId())) {
                continue;   // about to be shown in the window it is moving to
            }
            if (execution.isRunning()) {
                stop(execution.sessionId());
            } else {
                executions.remove(execution.sessionId(), execution);
            }
        }
    }

    // ---- prompt ----------------------------------------------------------------

    /** Opens a bracketed paste: the terminal sequence a TUI reads as the start of pasted text. */
    static final String PASTE_START = "\u001b[200~";
    /** Closes a bracketed paste. */
    static final String PASTE_END = "\u001b[201~";

    /**
     * Types {@code prompt} into {@code sessionId}'s agent and submits it with a
     * carriage return, written to the process input in one call as {@link
     * #framed}.
     *
     * <p>The prompt goes in as a bracketed paste. Written bare, a long prompt
     * arrives as one burst, which Claude Code's TUI takes for a paste: it kept
     * only the last part of the text, and the carriage return did not always
     * submit it. Claude, Codex and Pi turn bracketed paste on at startup.</p>
     *
     * <p>Refuses, writing nothing, a prompt containing {@code '\n'}, {@code
     * '\r'} or ESC (an ESC could end the paste early), and a session with no
     * live process or no process input.
     * There is no busy check: the line is written whatever the agent is doing.
     * The outcome is {@link PromptOutcome#FAILED} when the write throws or when
     * the process is found ended right after the write. On a PTY neither check
     * is complete: pty4j's stream ignores the result of the native write, so a
     * failed or short write does not throw, and {@code flush()} does nothing.
     * {@link PromptOutcome#DELIVERED} means the bytes were handed to the
     * process input of a process that was still running right after, not that
     * the agent read them.</p>
     *
     * <p>Blocking write to the PTY: call off the EDT (the plugin executor).</p>
     */
    public PromptOutcome submitPrompt(ManagedSessionId sessionId, String prompt) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(prompt, "prompt");
        if (prompt.indexOf('\n') >= 0 || prompt.indexOf('\r') >= 0) {
            LOG.log(Level.WARNING, "Refusing a multi-line prompt for {0}", sessionId);
            return PromptOutcome.REFUSED;
        }
        if (prompt.indexOf('\u001b') >= 0) {
            LOG.log(Level.WARNING, "Refusing a prompt with an escape character for {0}", sessionId);
            return PromptOutcome.REFUSED;
        }
        AgentExecution execution = executions.get(sessionId);
        if (execution == null || !execution.isRunning()) {
            return PromptOutcome.REFUSED;
        }
        OutputStream in = execution.handler().getProcessInput();
        if (in == null) {
            return PromptOutcome.REFUSED;
        }
        return writePrompt(sessionId, in, framed(prompt), execution::isRunning);
    }

    /** {@code prompt} as a bracketed paste followed by a carriage return, in UTF-8. */
    static byte[] framed(String prompt) {
        return (PASTE_START + prompt + PASTE_END + "\r").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Writes and flushes {@code line}; {@link PromptOutcome#FAILED} when the
     * write throws (logged without the prompt text) or when
     * {@code stillRunning} is false after the write returned.
     */
    static PromptOutcome writePrompt(ManagedSessionId sessionId, OutputStream in, byte[] line,
                                     BooleanSupplier stillRunning) {
        try {
            in.write(line);
            in.flush();
        } catch (IOException e) {
            LOG.log(Level.WARNING, () -> "Could not write a prompt to " + sessionId + ": " + e);
            return PromptOutcome.FAILED;
        }
        if (!stillRunning.getAsBoolean()) {
            LOG.log(Level.WARNING, "{0}''s agent ended while a prompt was written to it", sessionId);
            return PromptOutcome.FAILED;
        }
        return PromptOutcome.DELIVERED;
    }

    // ---- stop / forget ---------------------------------------------------------

    /**
     * Stops {@code sessionId}'s process: non-soft destroy now, force-kill in
     * two seconds if it is still there. A session with no live process is a
     * no-op, so a double click on Stop cannot produce two terminations.
     */
    public void stop(ManagedSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        AgentExecution execution = executions.get(sessionId);
        if (execution == null || !execution.isRunning() || !execution.requestStop()) {
            return;
        }
        KillableProcessHandler handler = execution.handler();
        handler.setShouldKillProcessSoftly(false);
        handler.destroyProcess();
        scheduleForceKill(sessionId, handler);
    }

    /**
     * Stops the complete PTY process tree immediately when a replacement
     * must reopen the same provider conversation.
     *
     * <p>The ordinary stop path first asks pty4j to destroy its wrapper and
     * force-kills the tree two seconds later. That is appropriate for a user
     * deactivation, but not for transfer: the wrapper can report terminated
     * while a Codex child still owns the thread-store writer. A subsequent
     * {@code codex exec resume} then races that child and fails with an
     * active-writer conflict. Transfer has already committed to replacing
     * the process, so it kills the tree synchronously before waiting.</p>
     */
    void stopProcessTree(ManagedSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        AgentExecution execution = executions.get(sessionId);
        if (execution == null || !execution.isRunning() || !execution.requestStop()) {
            return;
        }
        forceStop(execution.handler());
    }

    /** Stops and drops a session's execution entirely, e.g. when the session is forgotten. */
    public void forget(ManagedSessionId sessionId) {
        stop(sessionId);
        AgentExecution removed = executions.remove(sessionId);
        if (removed == null) {
            return;
        }
        removed.router().detach();
        // The console and its host are EDT state, and forget runs wherever
        // Done runs it (the session service's executor).
        ApplicationManager.getApplication().invokeLater(() -> {
            Optional<TerminalExecutionConsole> console = removed.console();
            removed.notShown();
            console.ifPresent(Disposer::dispose);
        }, ModalityState.any());
    }

    private void scheduleForceKill(ManagedSessionId sessionId, KillableProcessHandler handler) {
        ExecutorService executor = DrydockPluginService.getInstance().executor();
        try {
            CompletableFuture.delayedExecutor(FORCE_KILL_DELAY_MILLIS, TimeUnit.MILLISECONDS, executor)
                    .execute(() -> forceStop(handler));
        } catch (RejectedExecutionException e) {
            // Shutdown drained the executor; the JVM exit reaps the child.
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

    private static AgentExecutionListener publish() {
        return ApplicationManager.getApplication().getMessageBus().syncPublisher(AgentExecutionListener.TOPIC);
    }

    /** IDE quit: every agent stops. The durable rows stay, idle. */
    @Override
    public void dispose() {
        for (ManagedSessionId sessionId : runningSessions()) {
            stop(sessionId);
        }
        executions.clear();
    }
}
