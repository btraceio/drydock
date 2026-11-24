package app.drydock.tasks;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A task's priority, exactly the seven values the registered Jira site
 * exposes (verified against datadoghq.atlassian.net via the priorities API):
 * {@code Top}, {@code Highest}, {@code High}, {@code Medium}, {@code Low},
 * {@code Lowest}, {@code Unprioritized}. The stored names are the Jira
 * names verbatim, so a mirrored ticket can carry the task's priority by
 * name and a mirrored ticket's priority names a task's back without
 * translation.
 *
 * <p>Ordering is urgency order, Jira's own priority-id order: {@code Top}
 * first, then {@code Highest} through {@code Lowest}, and
 * {@code Unprioritized} last -- it is Jira's explicit "No priority", and it
 * sorts below every named priority. {@link #rank} is that order as a sort
 * key; the list sorts by it inside every presentation bucket.</p>
 */
public enum TaskPriority {
    TOP("Top"),
    HIGHEST("Highest"),
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low"),
    LOWEST("Lowest"),
    UNPRIORITIZED("Unprioritized");

    private final String storedName;

    TaskPriority(String storedName) {
        this.storedName = storedName;
    }

    /** The wire/stored value written into front matter and sent to Jira; never rename. */
    public String storedName() {
        return storedName;
    }

    /** The sort key: urgency order, {@code Unprioritized} last. */
    public int rank() {
        return ordinal();
    }

    /**
     * Parses a stored front-matter value; exact match on the stored names,
     * like {@link TaskStatus#fromStored}. Unknown words fail visibly -- the
     * reader's job for known fields -- except where the reader chooses to
     * treat a priority leniently (see {@link #fromKnown}).
     */
    public static TaskPriority fromStored(String text) {
        Objects.requireNonNull(text, "text");
        for (TaskPriority priority : values()) {
            if (priority.storedName.equals(text)) {
                return priority;
            }
        }
        throw new IllegalArgumentException("Unknown task priority: '" + text + "'");
    }

    /**
     * Lenient parse for values already in the wild: an exact stored name is
     * itself, and otherwise the match is case-insensitive (hand-edited
     * files and other Jira sites do not agree on case); a word no priority
     * names answers empty, and the caller treats that as unset -- a foreign
     * priority name never fails a task file's parse and never sorts above
     * an unprioritized one.
     */
    public static Optional<TaskPriority> fromKnown(String text) {
        Objects.requireNonNull(text, "text");
        for (TaskPriority priority : values()) {
            if (priority.storedName.equals(text)) {
                return Optional.of(priority);
            }
        }
        String folded = text.strip().toLowerCase(Locale.ROOT);
        for (TaskPriority priority : values()) {
            if (priority.storedName.toLowerCase(Locale.ROOT).equals(folded)) {
                return Optional.of(priority);
            }
        }
        return Optional.empty();
    }
}
