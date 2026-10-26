package app.drydock.review;

import java.util.List;
import java.util.Objects;

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
