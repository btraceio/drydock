package app.drydock.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The shared brief of a {@link Workflow}: the running narrative of the whole
 * piece of work, written by whichever member session is active and read by
 * the next one opened in any repository.
 *
 * <p>This is the workflow-scoped counterpart of {@link HandoffBrief}, minus
 * the fields that have no meaning above a single session/tree:</p>
 * <ul>
 *   <li>{@code sessionId} -- a workflow brief belongs to the workflow, not to
 *       any one session;</li>
 *   <li>{@code writtenAtCommit} -- a workflow spans multiple repos and
 *       branches, so a single {@code HEAD} is meaningless. Staleness is
 *       expressed by {@link Workflow#lastOpenedAt()} / {@link #writtenAt()}
 *       (elapsed time since anyone touched it), not by commits-since.</li>
 * </ul>
 *
 * <p>Like {@link HandoffBrief}, this is replaced wholesale on every write:
 * an omitted optional slot is cleared, not preserved. {@link #author()}
 * reuses {@link HandoffBrief.Author} so an agent-written brief is labelled
 * as testimony and a human-written one is not.</p>
 */
public record WorkflowBrief(
        String goal,
        String nextStep,
        Optional<String> approach,
        Optional<String> decisions,
        Optional<String> ruledOut,
        Optional<String> corrections,
        Instant writtenAt,
        HandoffBrief.Author author
) {

    public WorkflowBrief {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(nextStep, "nextStep");
        Objects.requireNonNull(approach, "approach");
        Objects.requireNonNull(decisions, "decisions");
        Objects.requireNonNull(ruledOut, "ruledOut");
        Objects.requireNonNull(corrections, "corrections");
        Objects.requireNonNull(writtenAt, "writtenAt");
        Objects.requireNonNull(author, "author");

        if (goal.isBlank()) {
            throw new IllegalArgumentException("WorkflowBrief goal must not be blank");
        }
        if (nextStep.isBlank()) {
            throw new IllegalArgumentException("WorkflowBrief nextStep must not be blank");
        }
    }
}
