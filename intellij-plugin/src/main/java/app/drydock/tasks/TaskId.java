package app.drydock.tasks;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identity of a stored task: the title slug plus short random suffix that is
 * also the task file's name stem (D4: {@code fix-login-redirect-k3f9}, stored
 * as {@code <id>.md}). The id is minted at creation and never changes, so a
 * renamed title never renames the file (D4) and links by id stay stable.
 *
 * <p>The accepted text is exactly a safe file-name stem: ASCII lowercase
 * letters, digits and inner hyphens, 1–200 characters, starting and ending
 * with a letter or digit. Path separators, dots, colons (the
 * {@code <project>:<id>} qualified reference syntax of P2 is a reference
 * form, not an id), whitespace and case are rejected, so a {@link TaskId}
 * cannot represent a path escape from {@code ~/.drydock/tasks/<repo-id>/}
 * (D2/D4).</p>
 */
public record TaskId(String text) {

    /** Longest accepted id, keeping {@code <id>.md} well inside common file-name limits. */
    public static final int MAX_LENGTH = 200;

    private static final Pattern SAFE_STEM = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,198}[a-z0-9])?");

    public TaskId {
        Objects.requireNonNull(text, "text");
        if (text.length() > MAX_LENGTH || !SAFE_STEM.matcher(text).matches()) {
            throw new IllegalArgumentException(
                    "Task id must be a file-name stem of lowercase letters, digits and inner hyphens (1-"
                            + MAX_LENGTH + " chars), got: '" + text + "'");
        }
    }

    public static TaskId of(String text) {
        return new TaskId(text);
    }

    @Override
    public String toString() {
        return text;
    }
}
