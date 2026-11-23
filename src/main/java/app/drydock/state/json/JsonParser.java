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
    private final boolean strict;
    private final int maxDepth;

    private JsonParser(String text) {
        this(text, false, MAX_DEPTH);
    }

    private JsonParser(String text, boolean strict, int maxDepth) {
        this.text = text;
        this.pos = 0;
        this.strict = strict;
        this.maxDepth = maxDepth;
    }

    /**
     * RFC 8259 strict parse for security-relevant documents: rejects
     * duplicate object members, whitespace other than space/tab/LF/CR,
     * leading-zero or non-ASCII-digit numbers, non-hex Unicode escapes,
     * lone or mis-paired UTF-16 surrogates (raw or escaped), and nesting
     * deeper than {@code maxDepth}. {@link #parse(String)} is unchanged.
     */
    public static JsonValue parseStrict(String text, int maxDepth) {
        if (text == null) {
            throw new JsonParseException("Null input");
        }
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be positive: " + maxDepth);
        }
        JsonParser parser = new JsonParser(text, true, maxDepth);
        parser.skipWhitespace();
        JsonValue value = parser.parseValue();
        parser.skipWhitespace();
        if (parser.pos != parser.text.length()) {
            throw new JsonParseException("Unexpected trailing content at offset " + parser.pos);
        }
        return value;
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
        if (depth >= maxDepth) {
            throw new JsonParseException("Nesting deeper than " + maxDepth + " levels at offset " + pos);
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
            if (members.put(key, value) != null && strict) {
                throw new JsonParseException("Duplicate member '" + key + "' at offset " + pos);
            }
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
                        char unit;
                        try {
                            if (strict && !hex.chars().allMatch(JsonParser::isHexDigit)) {
                                throw new NumberFormatException(hex);
                            }
                            unit = (char) Integer.parseInt(hex, 16);
                        } catch (NumberFormatException e) {
                            throw new JsonParseException("Invalid unicode escape '\\u" + hex + "' at offset " + pos);
                        }
                        pos += 4;
                        sb.append(unit);
                        if (strict) {
                            checkEscapedSurrogate(unit, sb);
                        }
                    }
                    default -> throw new JsonParseException("Invalid escape '\\" + escape + "' at offset " + (pos - 1));
                }
            } else if (c < 0x20) {
                throw new JsonParseException("Unescaped control character at offset " + (pos - 1));
            } else {
                if (strict && Character.isSurrogate(c)) {
                    if (!Character.isHighSurrogate(c) || pos >= text.length()
                            || !Character.isLowSurrogate(text.charAt(pos))) {
                        throw new JsonParseException("Invalid UTF-16 surrogate at offset " + (pos - 1));
                    }
                    sb.append(c);
                    c = text.charAt(pos++);
                }
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

    private static boolean isHexDigit(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** Strict: a high surrogate escape must be followed by a low one, and a low must follow a high. */
    private void checkEscapedSurrogate(char unit, StringBuilder sb) {
        int at = pos - 6;
        if (Character.isLowSurrogate(unit)) {
            boolean paired = sb.length() >= 2 && Character.isHighSurrogate(sb.charAt(sb.length() - 2));
            if (!paired) {
                throw new JsonParseException("Lone low surrogate escape at offset " + at);
            }
        } else if (Character.isHighSurrogate(unit)) {
            boolean next = pos + 6 <= text.length() && text.startsWith("\\u", pos)
                    && text.substring(pos + 2, pos + 6).matches("[dD][c-fC-F][0-9a-fA-F]{2}");
            if (!next) {
                throw new JsonParseException("Lone high surrogate escape at offset " + at);
            }
        }
    }

    private JsonValue.JsonNumber parseNumber() {
        int start = pos;
        if (peekOrNul() == '-') {
            pos++;
        }
        if (pos >= text.length() || !Character.isDigit(text.charAt(pos))) {
            throw new JsonParseException("Invalid number at offset " + start);
        }
        if (strict) {
            parseStrictNumberTail(start);
            return new JsonValue.JsonNumber(text.substring(start, pos));
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

    /** Strict RFC 8259 number grammar from the first integer digit; ASCII digits only, no leading zeros. */
    private void parseStrictNumberTail(int start) {
        if (!asciiDigit()) {
            throw new JsonParseException("Invalid number at offset " + start);
        }
        if (text.charAt(pos) == '0') {
            pos++;
            if (asciiDigit()) {
                throw new JsonParseException("Leading zero in number at offset " + start);
            }
        } else {
            skipAsciiDigits();
        }
        if (peekOrNul() == '.') {
            pos++;
            if (!asciiDigit()) {
                throw new JsonParseException("Invalid number at offset " + start);
            }
            skipAsciiDigits();
        }
        if (peekOrNul() == 'e' || peekOrNul() == 'E') {
            pos++;
            if (peekOrNul() == '+' || peekOrNul() == '-') {
                pos++;
            }
            if (!asciiDigit()) {
                throw new JsonParseException("Invalid number at offset " + start);
            }
            skipAsciiDigits();
        }
    }

    private boolean asciiDigit() {
        return pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9';
    }

    private void skipAsciiDigits() {
        while (asciiDigit()) {
            pos++;
        }
    }

    private void skipWhitespace() {
        while (pos < text.length() && isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private boolean isWhitespace(char c) {
        return strict ? (c == ' ' || c == '\t' || c == '\n' || c == '\r') : Character.isWhitespace(c);
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
