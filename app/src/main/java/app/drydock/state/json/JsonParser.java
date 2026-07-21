package app.drydock.state.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A minimal recursive-descent JSON parser. See {@link JsonValue}'s Javadoc
 * for why this is hand-rolled rather than a dependency.
 *
 * <p>Deliberately strict: any malformed or truncated input throws {@link
 * JsonParseException} rather than guessing at a partial result, so callers
 * can reliably detect "this state file is corrupt" (plan section 17).</p>
 */
public final class JsonParser {

    /**
     * Maximum object/array nesting. This parser now also sees untrusted
     * input (GitHub API responses, gh output, claude transcripts); without
     * a bound, deep nesting turns the recursive descent into a
     * {@link StackOverflowError} that escapes {@code catch
     * (JsonParseException)} recovery paths.
     */
    private static final int MAX_DEPTH = 256;

    private final String text;
    private int pos;
    private int depth;

    private JsonParser(String text) {
        this.text = text;
        this.pos = 0;
    }

    public static JsonValue parse(String text) {
        JsonParser parser = new JsonParser(text);
        parser.skipWhitespace();
        JsonValue value = parser.parseValue();
        parser.skipWhitespace();
        if (parser.pos != parser.text.length()) {
            throw new JsonParseException("Unexpected trailing content at offset " + parser.pos);
        }
        return value;
    }

    private JsonValue parseValue() {
        if (pos >= text.length()) {
            throw new JsonParseException("Unexpected end of input at offset " + pos);
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> parseNested(this::parseObject);
            case '[' -> parseNested(this::parseArray);
            case '"' -> new JsonValue.JsonString(parseStringLiteral());
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private JsonValue parseNested(Supplier<JsonValue> container) {
        if (depth >= MAX_DEPTH) {
            throw new JsonParseException("Nesting deeper than " + MAX_DEPTH + " levels at offset " + pos);
        }
        depth++;
        JsonValue value = container.get();
        depth--;
        return value;
    }

    private JsonValue.JsonObject parseObject() {
        expect('{');
        Map<String, JsonValue> members = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return new JsonValue.JsonObject(members);
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new JsonParseException("Expected string key at offset " + pos);
            }
            String key = parseStringLiteral();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            JsonValue value = parseValue();
            members.put(key, value);
            skipWhitespace();
            char next = requireNext();
            if (next == ',') {
                continue;
            }
            if (next == '}') {
                break;
            }
            throw new JsonParseException("Expected ',' or '}' at offset " + (pos - 1));
        }
        return new JsonValue.JsonObject(members);
    }

    private JsonValue.JsonArray parseArray() {
        expect('[');
        List<JsonValue> elements = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return new JsonValue.JsonArray(elements);
        }
        while (true) {
            skipWhitespace();
            elements.add(parseValue());
            skipWhitespace();
            char next = requireNext();
            if (next == ',') {
                continue;
            }
            if (next == ']') {
                break;
            }
            throw new JsonParseException("Expected ',' or ']' at offset " + (pos - 1));
        }
        return new JsonValue.JsonArray(elements);
    }

    private String parseStringLiteral() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw new JsonParseException("Unterminated string at offset " + pos);
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c == '\\') {
                if (pos >= text.length()) {
                    throw new JsonParseException("Unterminated escape sequence at offset " + pos);
                }
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw new JsonParseException("Truncated unicode escape at offset " + pos);
                        }
                        String hex = text.substring(pos, pos + 4);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw new JsonParseException("Invalid unicode escape '\\u" + hex + "' at offset " + pos);
                        }
                        pos += 4;
                    }
                    default -> throw new JsonParseException("Invalid escape '\\" + escape + "' at offset " + (pos - 1));
                }
            } else if (c < 0x20) {
                throw new JsonParseException("Unescaped control character at offset " + (pos - 1));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private JsonValue parseBoolean() {
        if (text.startsWith("true", pos)) {
            pos += 4;
            return new JsonValue.JsonBoolean(true);
        }
        if (text.startsWith("false", pos)) {
            pos += 5;
            return new JsonValue.JsonBoolean(false);
        }
        throw new JsonParseException("Invalid literal at offset " + pos);
    }

    private JsonValue parseNull() {
        if (text.startsWith("null", pos)) {
            pos += 4;
            return JsonValue.JsonNull.INSTANCE;
        }
        throw new JsonParseException("Invalid literal at offset " + pos);
    }

    private JsonValue.JsonNumber parseNumber() {
        int start = pos;
        if (peekOrNul() == '-') {
            pos++;
        }
        if (pos >= text.length() || !Character.isDigit(text.charAt(pos))) {
            throw new JsonParseException("Invalid number at offset " + start);
        }
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
            pos++;
        }
        if (peekOrNul() == '.') {
            pos++;
            if (pos >= text.length() || !Character.isDigit(text.charAt(pos))) {
                throw new JsonParseException("Invalid number at offset " + start);
            }
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
        }
        if (peekOrNul() == 'e' || peekOrNul() == 'E') {
            pos++;
            if (peekOrNul() == '+' || peekOrNul() == '-') {
                pos++;
            }
            if (pos >= text.length() || !Character.isDigit(text.charAt(pos))) {
                throw new JsonParseException("Invalid number at offset " + start);
            }
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
        }
        return new JsonValue.JsonNumber(text.substring(start, pos));
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= text.length()) {
            throw new JsonParseException("Unexpected end of input at offset " + pos);
        }
        return text.charAt(pos);
    }

    private char peekOrNul() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private char requireNext() {
        if (pos >= text.length()) {
            throw new JsonParseException("Unexpected end of input at offset " + pos);
        }
        return text.charAt(pos++);
    }

    private void expect(char c) {
        if (pos >= text.length() || text.charAt(pos) != c) {
            throw new JsonParseException("Expected '" + c + "' at offset " + pos);
        }
        pos++;
    }
}
