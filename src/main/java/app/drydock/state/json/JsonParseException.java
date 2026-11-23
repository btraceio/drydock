package app.drydock.state.json;

/**
 * Thrown when {@link JsonParser} encounters malformed or truncated JSON
 * text. Callers (in particular {@link app.drydock.state.JsonApplicationStateRepository})
 * are expected to catch this and recover gracefully rather than let it
 * propagate as an application crash (plan section 17: "recovery from
 * truncated or malformed state").
 */
public final class JsonParseException extends RuntimeException {

    public JsonParseException(String message) {
        super(message);
    }
}
