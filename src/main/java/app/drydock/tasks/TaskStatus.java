package app.drydock.tasks;

import java.util.Objects;

/**
 * Status of a task, with exactly the five values that are stored in front
 * matter (P1): {@code open}, {@code started}, {@code review}, {@code done},
 * {@code dropped}.
 *
 * <p>"Interrupted" is deliberately not a stored value: it is derived — a
 * {@code started} task whose sessions no longer exist in the catalog (P1,
 * D10). {@link #fromStored} therefore rejects it along with every other
 * unknown word, so a stored interrupted status cannot be represented.</p>
 */
public enum TaskStatus {
    OPEN("open"),
    STARTED("started"),
    REVIEW("review"),
    DONE("done"),
    DROPPED("dropped");

    private final String storedName;

    TaskStatus(String storedName) {
        this.storedName = storedName;
    }

    /** The wire/stored value written into the front matter; never rename. */
    public String storedName() {
        return storedName;
    }

    /** Parses a stored front-matter value; unknown words (including
     * "interrupted") fail visibly instead of being coerced. */
    public static TaskStatus fromStored(String text) {
        Objects.requireNonNull(text, "text");
        for (TaskStatus status : values()) {
            if (status.storedName.equals(text)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown task status: '" + text + "'");
    }
}
