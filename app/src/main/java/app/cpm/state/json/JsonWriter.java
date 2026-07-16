package app.cpm.state.json;

import java.util.Map;

/**
 * Writes a {@link JsonValue} tree to pretty-printed JSON text. See {@link
 * JsonValue}'s Javadoc for why this is hand-rolled rather than a
 * dependency.
 */
public final class JsonWriter {

    private JsonWriter() {
    }

    public static String write(JsonValue value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void writeValue(JsonValue value, StringBuilder sb, int indent) {
        switch (value) {
            case JsonValue.JsonObject obj -> writeObject(obj, sb, indent);
            case JsonValue.JsonArray arr -> writeArray(arr, sb, indent);
            case JsonValue.JsonString str -> writeString(str.value(), sb);
            case JsonValue.JsonNumber num -> sb.append(num.literal());
            case JsonValue.JsonBoolean bool -> sb.append(bool.value());
            case JsonValue.JsonNull ignored -> sb.append("null");
        }
    }

    private static void writeObject(JsonValue.JsonObject obj, StringBuilder sb, int indent) {
        if (obj.members().isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int i = 0;
        int size = obj.members().size();
        for (Map.Entry<String, JsonValue> entry : obj.members().entrySet()) {
            indent(sb, indent + 1);
            writeString(entry.getKey(), sb);
            sb.append(": ");
            writeValue(entry.getValue(), sb, indent + 1);
            if (++i < size) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append('}');
    }

    private static void writeArray(JsonValue.JsonArray arr, StringBuilder sb, int indent) {
        if (arr.elements().isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < arr.elements().size(); i++) {
            indent(sb, indent + 1);
            writeValue(arr.elements().get(i), sb, indent + 1);
            if (i + 1 < arr.elements().size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append(']');
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void indent(StringBuilder sb, int level) {
        sb.append("  ".repeat(level));
    }
}
