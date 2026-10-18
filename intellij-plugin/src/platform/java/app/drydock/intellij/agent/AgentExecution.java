package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.terminal.TerminalExecutionConsole;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One live (or just-ended) agent process and the console showing it.
 *
 * <p>Deliberately not a record: {@link #requestStop()} has to be a
 * compare-and-set, so that a second Stop — a double click, or a project close
 * landing on top of an explicit Stop — neither schedules a second force-kill
 * nor turns a drydock-initiated stop into something that looks like a natural
 * exit.</p>
 *
 * <p>The console outlives the process on purpose: a stopped session still
 * shows its last transcript, dimmed, and that transcript lives in the console
 * the process wrote to. It is disposed when the window goes, or when the
 * session is forgotten.</p>
 */
public final class AgentExecution {

    private final ManagedSessionId sessionId;
    private final KillableProcessHandler handler;
    private final TerminalExecutionConsole console;
    private final Instant startedAt;
    private final AtomicBoolean stopRequested = new AtomicBoolean();

    AgentExecution(ManagedSessionId sessionId, KillableProcessHandler handler,
                   TerminalExecutionConsole console, Instant startedAt) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.console = Objects.requireNonNull(console, "console");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }

    public ManagedSessionId sessionId() {
        return sessionId;
    }

    public KillableProcessHandler handler() {
        return handler;
    }

    public TerminalExecutionConsole console() {
        return console;
    }

    public Instant startedAt() {
        return startedAt;
    }

    /** Whether drydock asked for this process to end (as opposed to it exiting on its own). */
    public boolean stopRequested() {
        return stopRequested.get();
    }

    /** Claims the stop; {@code false} when another caller already did. */
    boolean requestStop() {
        return stopRequested.compareAndSet(false, true);
    }
}
