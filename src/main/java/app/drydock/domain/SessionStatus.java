package app.drydock.domain;

/**
 * Lifecycle status of a {@link ManagedAgentSession} (plan section 10.2).
 *
 * <p>{@link #IDLE} is the durable no-process state: no execution is bound
 * to the session, and it is where every session starts after a cold load —
 * nothing that was live survives a restart. {@link #STARTING}, {@link
 * #RUNNING} and {@link #STOPPING} are transient execution projections: they
 * are only true while this process owns the session's live execution, and a
 * persisted one (written mid-run, then read after a crash or restart) is
 * normalized back to IDLE at decode time by {@code ApplicationStateCodec}
 * — never trusted, and never a reason to drop the row. {@link #FAILED}
 * retains the last launch failure without costing the row its identity.
 * {@link #MISSING_WORKING_DIRECTORY} and {@link #UNSUPPORTED_AGENT} are
 * durable availability errors: the row stays visible and actionable rather
 * than disappearing.</p>
 *
 * <p>{@link #INACTIVE} and {@link #EXITED} are legacy wire values from the
 * experiment-era model (the pre-execution-sessions terminal stack still
 * writes them). They remain enum constants so that callers outside this
 * change keep compiling through the migration, and they remain readable
 * wire values — {@code ApplicationStateCodec} decodes both as IDLE — but no
 * new code should produce them.</p>
 */
public enum SessionStatus {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED,
    MISSING_WORKING_DIRECTORY,
    UNSUPPORTED_AGENT,
    INACTIVE,
    EXITED
}
