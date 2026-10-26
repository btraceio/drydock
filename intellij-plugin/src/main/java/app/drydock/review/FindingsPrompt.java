package app.drydock.review;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The one-line prompt drydock hands a session when a human asks the agent to
 * address review findings.
 *
 * <p>One builder serves every host: the app's hand-off and the plugin's
 * ask-to-fix render the same bytes, so the prompt a session receives never
 * forks by which tree sent it. It is a pure builder -- it renders text and
 * nothing else. Whether an empty hand-off is worth delivering, how the prompt
 * reaches the session, and what a delivered prompt marks are the caller's
 * decisions; this class has no opinion on them.</p>
 */
public final class FindingsPrompt {

    private FindingsPrompt() {
    }

    /**
     * The prompt for a human's ask-to-fix: the exact heading the app has
     * always sent, followed by the numbered findings.
     */
    public static String forFix(String subject, List<ReviewAnnotation> findings) {
        Objects.requireNonNull(subject, "subject");
        return render("Address these review findings on \"" + subject
                + "\", then summarize what you changed: ", findings);
    }

    /**
     * The prompt for the batched decisions hand-off: the ask-to-fix heading,
     * followed by the findings the reviewer agreed with -- each carrying the
     * reviewer's own last comment when there is one -- and the dismissed
     * findings as an explicit no-action list. One prompt carries the whole
     * decision set, so the agent is interrupted once per review round, not
     * once per click. Pure text, like every builder here: what a delivered
     * prompt marks is the caller's decision.
     */
    public static String forDecisions(String subject, List<ReviewAnnotation> toFix,
                                      List<ReviewAnnotation> dismissed) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(toFix, "toFix");
        Objects.requireNonNull(dismissed, "dismissed");
        StringBuilder prompt = new StringBuilder("Address these review findings on \""
                + subject + "\", then summarize what you changed: ");
        appendDecisions(prompt, toFix, true);
        if (!dismissed.isEmpty()) {
            // appendDecisions ends each entry with ". ", so no leading space here.
            prompt.append("Dismissed by the reviewer, no action needed: ");
            appendDecisions(prompt, dismissed, false);
        }
        return prompt.toString().strip();
    }

    /** The numbered entries, each optionally carrying the reviewer's last comment. */
    private static void appendDecisions(StringBuilder prompt, List<ReviewAnnotation> findings,
                                        boolean withComments) {
        int n = 1;
        for (ReviewAnnotation finding : findings) {
            prompt.append('[').append(n++).append("] ").append(finding.file()).append(' ')
                    .append(finding.startKey()).append(": ")
                    .append(finding.displayTitle().replaceAll("\\s+", " ")).append(". ");
            if (withComments) {
                lastCommentOf(finding).ifPresent(comment ->
                        prompt.append("You said: \"").append(comment.replaceAll("\\s+", " "))
                                .append("\". "));
            }
        }
    }

    /** The reviewer's own latest comment on the finding, when one exists and is not blank. */
    private static Optional<String> lastCommentOf(ReviewAnnotation finding) {
        return finding.thread().stream()
                .filter(message -> "You".equals(message.author()))
                .reduce((first, second) -> second)
                .map(ReviewAnnotation.Message::text)
                .map(String::strip)
                .filter(text -> !text.isEmpty());
    }

    /**
     * {@code heading} followed by every finding in order, each as
     * {@code [n] file startKey: title. } -- one line an agent can read back
     * from a prompt box. A title's runs of whitespace collapse to single
     * spaces so a multi-line or indented title cannot break the line, and
     * surrounding whitespace is stripped from the finished prompt.
     *
     * <p>An empty {@code findings} renders the stripped heading alone. The
     * caller decides whether that is worth delivering: the app's hand-off
     * refuses an empty list before it builds anything.</p>
     */
    public static String render(String heading, List<ReviewAnnotation> findings) {
        Objects.requireNonNull(heading, "heading");
        Objects.requireNonNull(findings, "findings");
        StringBuilder prompt = new StringBuilder(heading);
        int n = 1;
        for (ReviewAnnotation finding : findings) {
            prompt.append('[').append(n++).append("] ").append(finding.file()).append(' ')
                    .append(finding.startKey()).append(": ")
                    .append(finding.displayTitle().replaceAll("\\s+", " ")).append(". ");
        }
        return prompt.toString().strip();
    }
}
