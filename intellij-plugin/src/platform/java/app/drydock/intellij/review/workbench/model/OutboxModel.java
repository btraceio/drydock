package app.drydock.intellij.review.workbench.model;

import app.drydock.intellij.review.ReviewTriageActions;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Triage;

import java.util.List;
import java.util.Objects;

/**
 * The outbox's session lane (spec §8), pure: what Send to Session would
 * hand the bound session. In local mode that is every pending decision --
 * unresolved, CONFIRMED or DISMISSED, not yet SENT -- the confirmed ones
 * to fix, the dismissed ones as a no-action list. Membership is
 * {@link ReviewTriageActions#isPendingDecision}, the send's own test, so the
 * lane never lists what the send would leave out.
 */
public final class OutboxModel {

    /** The author a human's messages carry. */
    static final String HUMAN = "You";

    private OutboxModel() {
    }

    /** One finding to fix; {@code commented} when it carries the reviewer's own comment for the agent. */
    public record Item(ReviewAnnotation finding, boolean commented) {
        public Item {
            Objects.requireNonNull(finding, "finding");
        }
    }

    /**
     * The lane: the findings to fix in review order, how many dismissed
     * findings go along as no-action, and whether a session is bound to
     * receive them.
     */
    public record Snapshot(List<Item> toFix, int dismissed, boolean sessionBound) {
        public static final Snapshot EMPTY = new Snapshot(List.of(), 0, false);

        public Snapshot {
            toFix = List.copyOf(toFix);
        }

        /** Whether there is anything to send. */
        public boolean pending() {
            return !toFix.isEmpty() || dismissed > 0;
        }

        /** The lane's counts: "2 to fix · 1 dismissed", or "Nothing to send". */
        public String countsText() {
            if (!pending()) {
                return "Nothing to send";
            }
            String fix = toFix.isEmpty() ? "" : toFix.size() + " to fix";
            String dismissedText = dismissed == 0 ? "" : dismissed + " dismissed";
            return fix.isEmpty() ? dismissedText : dismissedText.isEmpty() ? fix : fix + " · " + dismissedText;
        }
    }

    /** The session lane of {@code findings} (one scope's), with {@code sessionBound} as resolved for that scope. */
    public static Snapshot snapshot(List<ReviewAnnotation> findings, boolean sessionBound) {
        Objects.requireNonNull(findings, "findings");
        List<Item> toFix = findings.stream()
                .filter(ReviewTriageActions::isPendingDecision)
                .filter(finding -> finding.triage() == Triage.CONFIRMED)
                .sorted(InboxModel.REVIEW_ORDER)
                .map(finding -> new Item(finding, commented(finding)))
                .toList();
        int dismissed = (int) findings.stream()
                .filter(ReviewTriageActions::isPendingDecision)
                .filter(finding -> finding.triage() == Triage.DISMISSED)
                .count();
        return new Snapshot(toFix, dismissed, sessionBound);
    }

    /** Whether the reviewer wrote to the agent on this finding: a non-blank message of theirs in the thread. */
    private static boolean commented(ReviewAnnotation finding) {
        return finding.thread().stream()
                .anyMatch(message -> HUMAN.equals(message.author()) && !message.text().isBlank());
    }
}
