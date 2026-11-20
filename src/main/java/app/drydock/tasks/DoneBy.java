package app.drydock.tasks;

import java.util.Objects;

/**
 * Who may set a task done (D16): {@code done_by: agent} or
 * {@code done_by: human}, with human as the default policy. With
 * {@link #HUMAN}, an agent's done lands in {@link TaskStatus#REVIEW} until a
 * human confirms; the policy is stored front matter, so it only changes
 * through manual edits or the plan dialog — never through MCP.
 */
public enum DoneBy {
    HUMAN("human"),
    AGENT("agent");

    private final String storedName;

    DoneBy(String storedName) {
        this.storedName = storedName;
    }

    /** The wire/stored value written into the front matter; never rename. */
    public String storedName() {
        return storedName;
    }

    /** Parses a stored front-matter value; unknown words fail visibly. */
    public static DoneBy fromStored(String text) {
        Objects.requireNonNull(text, "text");
        for (DoneBy doneBy : values()) {
            if (doneBy.storedName.equals(text)) {
                return doneBy;
            }
        }
        throw new IllegalArgumentException("Unknown done_by value: '" + text + "'");
    }
}
