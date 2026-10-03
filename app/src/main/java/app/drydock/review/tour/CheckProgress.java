package app.drydock.review.tour;

import java.util.Objects;
import java.util.Optional;

/** Where the reviewer stands on one top-level check (its alternates share this record). */
public record CheckProgress(String checkId, int attempt, Status status, Optional<String> riskAnswer,
                            Optional<String> agentReason, Optional<String> lastExplanation) {

    public enum Status { OPEN, PASSED, EXHAUSTED, AWAITING_AGENT, AGENT_UNAVAILABLE, VOIDED }

    public CheckProgress {
        Objects.requireNonNull(checkId, "checkId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(riskAnswer, "riskAnswer");
        Objects.requireNonNull(agentReason, "agentReason");
        Objects.requireNonNull(lastExplanation, "lastExplanation");
        attempt = Math.max(0, attempt);
    }

    public static CheckProgress fresh(String checkId) {
        return new CheckProgress(checkId, 0, Status.OPEN, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Passed, or voided because the finding it was built on was dismissed. */
    public boolean settled() {
        return status == Status.PASSED || status == Status.VOIDED;
    }

    CheckProgress with(int newAttempt, Status newStatus, Optional<String> newRiskAnswer,
                       Optional<String> newAgentReason, Optional<String> newExplanation) {
        return new CheckProgress(checkId, newAttempt, newStatus, newRiskAnswer, newAgentReason, newExplanation);
    }
}
