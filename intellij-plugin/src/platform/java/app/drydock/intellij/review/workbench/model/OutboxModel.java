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
 * to fix, the dismissed ones as a no-action list. In a pull request review
 * the confirmed findings meant for the PR wait for the PR lane instead, and
 * are only counted here. Membership is
 * {@link ReviewTriageActions#forSession}, the send's own rule, so the lane
 * never lists what the send would leave out.
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
     * findings go along as no-action, whether a session is bound to receive
     * them, and how many confirmed findings wait for the PR instead.
     */
    public record Snapshot(List<Item> toFix, int dismissed, boolean sessionBound, int waitingForPr) {
        public static final Snapshot EMPTY = new Snapshot(List.of(), 0, false, 0);

        public Snapshot {
            toFix = List.copyOf(toFix);
        }

        /** Whether there is anything to send. */
        public boolean pending() {
            return !toFix.isEmpty() || dismissed > 0;
        }

        /** The lane's counts: "2 to fix · 1 dismissed", or "Nothing to send"; then "· 3 wait for the PR". */
        public String countsText() {
            String lane;
            if (!pending()) {
                lane = "Nothing to send";
            } else {
                String fix = toFix.isEmpty() ? "" : toFix.size() + " to fix";
                String dismissedText = dismissed == 0 ? "" : dismissed + " dismissed";
                lane = fix.isEmpty() ? dismissedText : dismissedText.isEmpty() ? fix : fix + " · " + dismissedText;
            }
            return waitingForPr == 0 ? lane
                    : lane + " · " + waitingForPr + (waitingForPr == 1 ? " waits" : " wait") + " for the PR";
        }
    }

    /** The session lane of a local review's {@code findings}. */
    public static Snapshot snapshot(List<ReviewAnnotation> findings, boolean sessionBound) {
        return snapshot(findings, sessionBound, false);
    }

    /**
     * The session lane of {@code findings} (one scope's), with
     * {@code sessionBound} as resolved for that scope; {@code prMode} when the
     * scope is a pull request review.
     */
    public static Snapshot snapshot(List<ReviewAnnotation> findings, boolean sessionBound, boolean prMode) {
        Objects.requireNonNull(findings, "findings");
        List<Item> toFix = findings.stream()
                .filter(finding -> ReviewTriageActions.forSession(finding, prMode))
                .filter(finding -> finding.triage() == Triage.CONFIRMED)
                .sorted(InboxModel.REVIEW_ORDER)
                .map(finding -> new Item(finding, commented(finding)))
                .toList();
        int dismissed = (int) findings.stream()
                .filter(finding -> ReviewTriageActions.forSession(finding, prMode))
                .filter(finding -> finding.triage() == Triage.DISMISSED)
                .count();
        int waitingForPr = (int) findings.stream()
                .filter(ReviewTriageActions::isPendingDecision)
                .filter(finding -> !ReviewTriageActions.forSession(finding, prMode))
                .count();
        return new Snapshot(toFix, dismissed, sessionBound, waitingForPr);
    }

    /** Whether the reviewer wrote to the agent on this finding: a non-blank message of theirs in the thread. */
    private static boolean commented(ReviewAnnotation finding) {
        return finding.thread().stream()
                .anyMatch(message -> HUMAN.equals(message.author()) && !message.text().isBlank());
    }
}
