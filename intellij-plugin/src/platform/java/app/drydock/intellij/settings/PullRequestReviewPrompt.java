package app.drydock.intellij.settings;

import com.intellij.ide.util.PropertiesComponent;

import java.util.Objects;
import java.util.Optional;

/**
 * The application-wide, user-editable prompt a pull request's Review action
 * sends to the agent it starts, in place of Drydock's own review flow.
 *
 * <p>{@code $PR$} in the template is replaced by the pull request number, so
 * a harness command can be invoked directly — e.g. {@code /sphinx review pr
 * $PR$}. Whether the target harness actually knows the command is the
 * harness's business: drydock only types the text at the agent's prompt.
 * A blank template (the default) means "no override": the review runs
 * Drydock's own way.</p>
 */
public final class PullRequestReviewPrompt {

    /** The placeholder replaced by the pull request number. */
    public static final String PLACEHOLDER = "$PR$";

    private static final String KEY = "drydock.review.pullRequestPrompt";

    private PullRequestReviewPrompt() { }

    /** The saved template, stripped; empty when unset or blank (no override). */
    public static Optional<String> load() {
        return Optional.ofNullable(PropertiesComponent.getInstance().getValue(KEY))
                .map(String::strip)
                .filter(template -> !template.isEmpty());
    }

    public static void save(Optional<String> template) {
        Objects.requireNonNull(template, "template");
        PropertiesComponent.getInstance().setValue(KEY,
                template.map(String::strip).filter(value -> !value.isEmpty()).orElse(null));
    }

    /** The prompt for {@code prNumber}: the template with every {@code $PR$} replaced. Pure. */
    public static String render(String template, int prNumber) {
        Objects.requireNonNull(template, "template");
        return template.replace(PLACEHOLDER, String.valueOf(prNumber));
    }
}
