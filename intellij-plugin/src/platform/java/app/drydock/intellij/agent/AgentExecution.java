package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.openapi.project.Project;
import com.intellij.terminal.TerminalExecutionConsole;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One live (or just-ended) agent process, owned by
 * {@link AgentExecutionRegistry}, and the console currently showing it, if
 * any.
 *
 * <p>The process belongs to the IDE; the console belongs to one window and
 * is replaced whenever the agent is shown somewhere else. Its output lives in
 * {@link #router()}, so a console built later — another window's, or this
 * window's again after it closed and reopened — is filled with what the
 * agent already printed.</p>
 *
 * <p>Deliberately not a record: {@link #requestStop()} has to be a
 * compare-and-set, so that a second Stop — a double click, or a window close
 * landing on top of an explicit Stop — neither schedules a second force-kill
 * nor turns a drydock-initiated stop into something that looks like a natural
 * exit.</p>
 *
 * <p>A console outlives its process on purpose: a stopped session still
 * shows its last transcript, dimmed.</p>
 */
public final class AgentExecution {

    private final ManagedSessionId sessionId;
    private final KillableProcessHandler handler;
    private final Path workingDirectory;
    private final Instant startedAt;
    private final SessionPlace place;
    private final OutputRouter router;
    private final AgentExecutionListener owner;
    private final AtomicBoolean stopRequested = new AtomicBoolean();

    /** EDT-written; read anywhere. */
    private volatile TerminalExecutionConsole console;
    private volatile Project host;

    AgentExecution(ManagedSessionId sessionId, KillableProcessHandler handler, Path workingDirectory,
                   Instant startedAt, SessionPlace place, OutputRouter router, AgentExecutionListener owner) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.place = Objects.requireNonNull(place, "place");
        this.router = Objects.requireNonNull(router, "router");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    public ManagedSessionId sessionId() {
        return sessionId;
    }

    public KillableProcessHandler handler() {
        return handler;
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public Instant startedAt() {
        return startedAt;
    }

    /** The agent's own repository and branch, resolved when it started. */
    public SessionPlace place() {
        return place;
    }

    /** The console showing the agent now; empty when no window shows it. */
    public Optional<TerminalExecutionConsole> console() {
        return Optional.ofNullable(console);
    }

    /** The window showing the agent now; empty when none does. */
    public Optional<Project> host() {
        return Optional.ofNullable(host);
    }

    public boolean isRunning() {
        return !handler.isProcessTerminated();
    }

    /** Whether drydock asked for this process to end (as opposed to it exiting on its own). */
    public boolean stopRequested() {
        return stopRequested.get();
    }

    /** Claims the stop; {@code false} when another caller already did. */
    boolean requestStop() {
        return stopRequested.compareAndSet(false, true);
    }

    OutputRouter router() {
        return router;
    }

    /** The session service of the window that started the agent: it persists the end. */
    AgentExecutionListener owner() {
        return owner;
    }

    void shownIn(Project project, TerminalExecutionConsole shownBy) {
        this.host = project;
        this.console = shownBy;
    }

    void notShown() {
        this.host = null;
        this.console = null;
    }
}
