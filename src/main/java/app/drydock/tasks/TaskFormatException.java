package app.drydock.tasks;

/**
 * A task file could not be parsed: malformed front-matter fences, YAML the
 * bounded reader refuses (duplicates, aliases, excessive nesting or size),
 * or a known field with an invalid type or value. Checked so a bad file is a
 * visible, reportable error for the caller (T04 keeps it per file), never a
 * silent empty task or an unhandled {@code StackOverflowError}.
 */
public final class TaskFormatException extends Exception {

    public TaskFormatException(String message) {
        super(message);
    }

    public TaskFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
