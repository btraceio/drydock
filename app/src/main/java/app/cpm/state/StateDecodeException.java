package app.cpm.state;

/**
 * Thrown when parsed JSON is syntactically valid but does not match the
 * expected application-state schema (missing/wrong-typed fields, an
 * unrecognized {@code schemaVersion}, an invalid UUID/Instant literal,
 * etc). Treated identically to a {@link app.cpm.state.json.JsonParseException}
 * by {@link JsonApplicationStateRepository#load()}: the file is backed up
 * and an empty state is returned (plan section 17).
 */
public final class StateDecodeException extends RuntimeException {

    public StateDecodeException(String message) {
        super(message);
    }
}
