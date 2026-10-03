package app.drydock.review.tour;

import java.util.Locale;
import java.util.Optional;

/** The grading rules for tour checks, as pure transitions of {@link CheckProgress}. */
public final class StepGrading {

    public enum RiskVerdict {
        HOLDS("holds"),
        PARTLY("partly"),
        DOES_NOT_HOLD("doesNotHold");

        private final String wireName;

        RiskVerdict(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Optional<RiskVerdict> fromWire(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String normalized = raw.strip().toLowerCase(Locale.ROOT);
            for (RiskVerdict verdict : values()) {
                if (verdict.wireName.toLowerCase(Locale.ROOT).equals(normalized)) {
                    return Optional.of(verdict);
                }
            }
            return Optional.empty();
        }
    }

    private StepGrading() {
    }

    /** Grades a choice against the answer key of the version currently offered. */
    public static CheckProgress answerChoice(TourCheck check, CheckProgress progress, int choiceIndex) {
        if (progress.status() != CheckProgress.Status.OPEN || !check.gradedLocally()) {
            return progress;
        }
        TourCheck offered = check.version(progress.attempt());
        if (offered.answer().isPresent() && offered.answer().getAsInt() == choiceIndex) {
            return progress.with(progress.attempt(), CheckProgress.Status.PASSED, Optional.empty(),
                    Optional.empty(), progress.lastExplanation());
        }
        return wrong(check, progress, offered);
    }

    public static CheckProgress submitRisk(CheckProgress progress, String answer) {
        if (progress.status() != CheckProgress.Status.OPEN) {
            return progress;
        }
        return progress.with(progress.attempt(), CheckProgress.Status.AWAITING_AGENT, Optional.of(answer),
                Optional.empty(), progress.lastExplanation());
    }

    public static CheckProgress applyRiskVerdict(TourCheck check, CheckProgress progress, RiskVerdict verdict,
                                                 String reason) {
        if (progress.status() != CheckProgress.Status.AWAITING_AGENT) {
            return progress;
        }
        if (verdict == RiskVerdict.DOES_NOT_HOLD) {
            CheckProgress next = wrong(check, progress, check.version(progress.attempt()));
            return next.with(next.attempt(), next.status(), Optional.empty(), Optional.of(reason),
                    next.lastExplanation());
        }
        return progress.with(progress.attempt(), CheckProgress.Status.PASSED, progress.riskAnswer(),
                Optional.of(reason), progress.lastExplanation());
    }

    public static CheckProgress markAgentUnavailable(CheckProgress progress) {
        if (progress.status() != CheckProgress.Status.AWAITING_AGENT) {
            return progress;
        }
        return progress.with(progress.attempt(), CheckProgress.Status.AGENT_UNAVAILABLE, progress.riskAnswer(),
                Optional.empty(), progress.lastExplanation());
    }

    public static CheckProgress retryRisk(CheckProgress progress) {
        if (progress.status() != CheckProgress.Status.AGENT_UNAVAILABLE) {
            return progress;
        }
        return progress.with(progress.attempt(), CheckProgress.Status.AWAITING_AGENT, progress.riskAnswer(),
                Optional.empty(), progress.lastExplanation());
    }

    public static CheckProgress voided(CheckProgress progress) {
        return progress.with(progress.attempt(), CheckProgress.Status.VOIDED, progress.riskAnswer(),
                progress.agentReason(), progress.lastExplanation());
    }

    private static CheckProgress wrong(TourCheck check, CheckProgress progress, TourCheck offered) {
        int next = progress.attempt() + 1;
        Optional<String> explanation = Optional.of(offered.explanation());
        if (next < check.versions()) {
            return progress.with(next, CheckProgress.Status.OPEN, Optional.empty(), Optional.empty(), explanation);
        }
        return progress.with(progress.attempt(), CheckProgress.Status.EXHAUSTED, Optional.empty(),
                Optional.empty(), explanation);
    }
}
