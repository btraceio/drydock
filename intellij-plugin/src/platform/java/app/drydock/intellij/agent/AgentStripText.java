package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionStatusFacet;
import app.drydock.intellij.workspace.RemoteSessionLiveness;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Every string the agent strip shows, as pure functions.
 *
 * <p>Swing-free and IDE-free on purpose: the strip's copy is the part of the
 * UX handoff that is easiest to get subtly wrong (a chip that promises an
 * enforcement the agent does not have, a "0 files changed" where the count is
 * simply unknown), and these are the assertions a test can actually pin. The
 * strip itself only places the results.</p>
 */
public final class AgentStripText {

    private AgentStripText() {
    }

    /**
     * The status to <em>display</em> for a session, given whether this window
     * actually has a process for it.
     *
     * <p>A row persisted in the running band with nothing running here is
     * stale. The window is the authority on what is alive in it — the catalog
     * only records what was last written — so the display follows the window
     * and reports {@code INACTIVE}. Without this, a session whose idle
     * transition was lost shows a filled blue dot, the word "Running", and a
     * Stop button that stops nothing.</p>
     *
     * <p>This corrects presentation only; the stale row itself is repaired by
     * whoever owns the write (window close now marks idle before stopping,
     * and the startup sweep catches the rest).</p>
     */
    public static SessionStatus displayStatus(SessionStatus persisted, boolean runningHere) {
        if (runningHere) {
            return persisted;
        }
        return SessionStatusFacet.of(persisted) == SessionStatusFacet.RUNNING
                ? SessionStatus.INACTIVE
                : persisted;
    }

    /**
     * The status a remote (workspace-box) row's dot paints: the same
     * palette the local strip uses, fed by the two facts a remote row
     * actually has. This IDE's own ssh execution is the live authority (the
     * local rule unchanged); a detached remote row is decided by the
     * liveness map — the box's {@code tmux ls} answer — and never by the
     * local activity monitor, which cannot see a box-side agent (OQ10).
     *
     * <p>ALIVE keeps the live mark: the box says the tmux session is there,
     * which is a probe fact, not a working/idle claim. Everything else the
     * box can answer — DEAD, UNKNOWN, a stopped or gone box — paints the
     * idle band, because from this Mac nothing runs; the liveness word that
     * distinguishes them is {@link #remoteStatusText}, not the dot. An error
     * facet (a failed or unsupported remote launch) survives the mapping:
     * a refused row must not paint blue just because some probe once said
     * the box had sessions.</p>
     */
    public static SessionStatus remoteDisplayStatus(SessionStatus persisted, boolean ownedHere,
                                                    Optional<RemoteSessionLiveness.State> liveness) {
        if (ownedHere) {
            return persisted;
        }
        if (SessionStatusFacet.of(persisted) == SessionStatusFacet.ERROR) {
            return persisted;
        }
        return liveness.orElse(RemoteSessionLiveness.State.UNKNOWN) == RemoteSessionLiveness.State.ALIVE
                ? SessionStatus.RUNNING
                : SessionStatus.IDLE;
    }

    /**
     * The status line a remote row's strip shows: the liveness word plus the
     * box name — {@code "Running · on box <name>"} — and never a
     * working/idle word (OQ10). The local strip folds activity into its one
     * status word; a remote row has no activity to fold, so the word comes
     * from the liveness facts alone and the box name is the second half of
     * the line, naming where the agent runs.
     *
     * <p>An unprobed row (empty liveness) reads {@code Unknown}: it has
     * never been asked, and "Not running" would claim an answer this IDE
     * does not have. {@code ownedHere} — this IDE owns the live ssh
     * execution — outranks the map: the console is attached in this IDE, so
     * the agent runs, whatever a stale probe once said.</p>
     */
    public static String remoteStatusText(boolean ownedHere,
                                           Optional<RemoteSessionLiveness.State> liveness, String boxName) {
        String where = "on box " + boxName;
        if (ownedHere) {
            return "Running · " + where;
        }
        return switch (liveness.orElse(RemoteSessionLiveness.State.UNKNOWN)) {
            case ALIVE -> "Running · " + where;
            case DEAD -> "Not running · " + where;
            case UNKNOWN -> "Unknown · " + where;
            case BOX_STOPPED -> "Box stopped · " + where;
            case BOX_GONE -> "Box gone · " + where;
        };
    }

    /**
     * The strip/tab tooltip under a remote row's status: one sentence
     * saying what the box knows and what the user can do about it. The
     * stopped and gone box sentences are the resume refusal's own words —
     * the surfaces and the refusal must not tell two different stories about
     * the same box.
     */
    public static String remoteStatusTooltip(boolean ownedHere,
                                              Optional<RemoteSessionLiveness.State> liveness, String boxName) {
        if (ownedHere) {
            return "The agent runs on workspace box " + boxName + "; this window shows its live ssh console";
        }
        return switch (liveness.orElse(RemoteSessionLiveness.State.UNKNOWN)) {
            case ALIVE -> "The agent is running on workspace box " + boxName
                    + "; Resume attaches to it";
            case DEAD -> "The agent is not running on workspace box " + boxName;
            case UNKNOWN -> "Workspace box " + boxName
                    + " could not be reached, so whether the agent runs there is unknown";
            case BOX_STOPPED -> "Box " + boxName + " is stopped; its conversations are unreachable";
            case BOX_GONE -> "Box " + boxName + " no longer exists";
        };
    }

    /**
     * The read-only chip, or empty for a writing session. A provider that
     * cannot hold the agent to reading says so in the chip rather than
     * claiming an enforcement that does not exist — the mode is still a real
     * statement of intent, which is why the chip appears at all.
     */
    public static Optional<String> readOnlyChip(ManagedAgentSession session, boolean enforced) {
        if (!session.readOnly()) {
            return Optional.empty();
        }
        return Optional.of(enforced ? "Read-only · won't edit files" : "Read-only · not enforced");
    }

    /**
     * The secondary line: {@code <Agent> · <age> · <n> files changed}, with
     * each part dropped when it is not known rather than guessed. An unknown
     * change count is omitted entirely — "0 files changed" is a claim, and
     * before the first git status there is nothing to claim.
     *
     * <p>{@code IDE still indexing} appears only while an agent is actually
     * running. The note exists to explain one thing — that the agent started
     * without waiting for indexing, so the IDE around it is not ready yet —
     * and on a stopped session it explains nothing: nothing started, so
     * indexing has no bearing on it, and sitting under the words "Not running"
     * it reads as the reason the agent is not running.</p>
     */
    public static String secondary(String agentName, Duration age, Optional<Integer> changedFiles,
                                   boolean running, boolean indexing) {
        StringBuilder text = new StringBuilder(agentName);
        text.append(" · ").append(relativeAge(age));
        changedFiles.ifPresent(count ->
                text.append(" · ").append(count).append(count == 1 ? " file changed" : " files changed"));
        if (running && indexing) {
            text.append(" · IDE still indexing");
        }
        return text.toString();
    }

    /**
     * The footer under a stopped session's dimmed transcript. The second
     * clause is the load-bearing one: after a window close or an IDE restart
     * every session is idle, and the user has to be able to tell that from
     * "it crashed and will come back".
     *
     * <p>This is only ever rendered when nothing is running in this window, so
     * a {@code RUNNING} status reaching it is <em>stale</em> — a row whose idle
     * transition was lost, most plausibly because the window that owned it
     * closed while its message bus was going away. Saying "it is running"
     * there contradicts the very panel it sits in, so the copy reports what is
     * actually true of this window instead and leaves the cause unstated.</p>
     */
    public static String stoppedFooter(SessionStatus status, Optional<Instant> stoppedAt, Instant now) {
        String when = stoppedAt
                .map(at -> relativeAge(Duration.between(at, now)) + " ago")
                .orElse("earlier");
        String why = switch (status) {
            case EXITED -> "the agent exited";
            case FAILED -> "the last launch failed";
            case MISSING_WORKING_DIRECTORY -> "its directory is gone";
            case UNSUPPORTED_AGENT -> "this build cannot run its agent";
            // INACTIVE is drydock's own stop: Deactivate, window close, or IDE quit.
            // The three are not distinguished because the user already knows
            // which one they did, and the persisted row does not record it.
            case INACTIVE -> "by Drydock";
            // IDLE has deliberately forgotten how the prior execution ended;
            // STARTING/RUNNING/STOPPING here are stale projections because
            // this footer is only rendered after the execution is gone.
            case IDLE, STARTING, RUNNING, STOPPING -> "no agent is running here";
        };
        String event = status == SessionStatus.INACTIVE ? "deactivated" : "stopped";
        return "— " + event + " " + when + " · " + why + " · nothing restarts on its own —";
    }

    /**
     * The stopped strip's primary button.
     *
     * <p>{@code Resume · ~12k tokens} when there is a conversation to resume
     * and its size is known, a bare {@code Resume} when the size is not (a
     * fabricated {@code ~0k} would read as "this costs nothing", the opposite
     * of unknown), and {@code Start} when the session has no recorded
     * conversation at all.</p>
     *
     * <p>That last case is not cosmetic. A session the agent never wrote a
     * record for — stopped before it completed a turn, which is easy to do —
     * has nothing to resume into, and "Resume" could only land the user in the
     * provider's own session picker. Offering to start is both honest and what
     * they wanted.</p>
     */
    public static String primaryLabel(boolean resumable, Optional<Integer> estimatedTokens) {
        return primaryLabelFor(resumable, estimatedTokens);
    }

    /**
     * Whether a stopped session has a conversation to resume: an agent
     * session id, and — when the provider can say — a transcript on disk.
     * Claude is given its id at launch, so a session stopped before its
     * first turn has an id and nothing recorded; resuming it answered "No
     * conversation found" and exited. A provider that cannot tell keeps
     * Resume.
     */
    public static boolean resumable(boolean hasAgentSessionId, Optional<Boolean> transcriptExists) {
        return hasAgentSessionId && transcriptExists.orElse(true);
    }

    private static String primaryLabelFor(boolean resumable, Optional<Integer> estimatedTokens) {
        if (!resumable) {
            return "Start";
        }
        return estimatedTokens
                .map(tokens -> "Resume · " + approximateThousands(tokens) + " tokens")
                .orElse("Resume");
    }

    /**
     * {@code ~12k}, rounded to the nearest thousand; {@code <1k} below a
     * thousand. Truncating division rendered a short conversation as
     * {@code ~0k} — "this costs nothing" — which is the one reading a cost
     * estimate must never give.
     */
    static String approximateThousands(int tokens) {
        return tokens < 1000 ? "<1k" : "~" + Math.round(tokens / 1000.0) + "k";
    }

    /**
     * A compact age: seconds under a minute, then minutes, hours, days. Keeps
     * the strip's secondary line one line wide at any session age.
     */
    public static String relativeAge(Duration age) {
        long seconds = Math.max(0, age.getSeconds());
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m";
        }
        if (seconds < 86_400) {
            return (seconds / 3600) + "h";
        }
        return (seconds / 86_400) + "d";
    }
}
