package app.drydock.git;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A parsed boolean filter over GitHub pull-request labels, compiled to a
 * {@code gh pr list --search} query.
 *
 * <p>Syntax (standard precedence: {@code NOT} &gt; {@code AND} &gt; {@code OR},
 * with parentheses):</p>
 * <ul>
 *   <li>AND: {@code &}, {@code &&}, or uppercase {@code AND}</li>
 *   <li>OR: {@code |}, {@code ||}, or uppercase {@code OR}</li>
 *   <li>NOT: {@code !}, prefix {@code -}, or uppercase {@code NOT}</li>
 *   <li>Labels: unquoted text (spaces, colons, slashes and internal hyphens
 *       such as {@code comp: profiling} or {@code do-not-merge} are allowed)
 *       or single/double-quoted text when a label contains an operator
 *       character, starts with {@code -}, or contains uppercase
 *       {@code AND}/{@code OR}/{@code NOT}.</li>
 * </ul>
 *
 * <p>Before emitting GitHub's search syntax, negations are pushed down to the
 * label leaves via De Morgan's laws so every negation renders as
 * {@code -label:"..."} — GitHub's advanced search handles {@code AND},
 * {@code OR}, parentheses, and negated {@code -label:"..."} qualifiers
 * uniformly without relying on a {@code NOT (...)} construct.</p>
 */
public final class PullRequestLabelFilter {

    /** Hard cap on filter text length so malformed input cannot consume unbounded work. */
    static final int MAX_INPUT_LENGTH = 2048;

    /** Hard cap on expression nesting depth so deeply nested input raises a refusal, never StackOverflowError. */
    static final int MAX_DEPTH = 64;

    private final String raw;
    private final Expr expr;

    private PullRequestLabelFilter(String raw, Expr expr) {
        this.raw = raw;
        this.expr = expr;
    }

    /**
     * Parses {@code input} into a label filter.
     *
     * @throws IllegalArgumentException if {@code input} is blank, exceeds the
     *         size/depth bounds, or is not a valid boolean label expression
     */
    public static PullRequestLabelFilter parse(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Enter a label filter");
        }
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c != '\t' && Character.isISOControl(c)) {
                throw new IllegalArgumentException("Label filter must not contain line breaks or control characters");
            }
        }
        String stripped = input.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("Enter a label filter");
        }
        if (stripped.length() > MAX_INPUT_LENGTH) {
            throw new IllegalArgumentException("Label filter is too long (maximum " + MAX_INPUT_LENGTH + " characters)");
        }
        List<Token> tokens = tokenize(stripped);
        Parser parser = new Parser(tokens);
        Expr expr = parser.parse();
        return new PullRequestLabelFilter(stripped, expr);
    }

    /** The validation refusal for {@code input}, or empty when it parses cleanly. */
    public static Optional<String> refusal(String input) {
        try {
            parse(input);
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
    }

    /** The stripped filter text as written by the user. */
    public String raw() {
        return raw;
    }

    /**
     * Compiles this filter to a GitHub {@code gh pr list --search} query string.
     * Negations are pushed to leaves via De Morgan's laws; AND/OR combinations
     * are parenthesized explicitly so server-side precedence is unambiguous.
     */
    public String toGitHubSearchQuery() {
        return formatNnf(toNnf(expr, false));
    }

    /**
     * Evaluates this filter against one pull request's label names,
     * case-insensitively (matching GitHub's case-insensitive label matching).
     */
    public boolean matches(Collection<String> labels) {
        Objects.requireNonNull(labels, "labels");
        Set<String> present = new HashSet<>(labels.size());
        for (String label : labels) {
            if (label != null) {
                present.add(label.toLowerCase(Locale.ROOT));
            }
        }
        return eval(expr, present);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof PullRequestLabelFilter other && raw.equals(other.raw);
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    @Override
    public String toString() {
        return raw;
    }

    // ---- AST ----

    private sealed interface Expr {
        record Label(String name) implements Expr { }
        record Not(Expr inner) implements Expr { }
        record And(Expr left, Expr right) implements Expr { }
        record Or(Expr left, Expr right) implements Expr { }
    }

    private sealed interface Nnf {
        record Positive(String label) implements Nnf { }
        record Negative(String label) implements Nnf { }
        record And(Nnf left, Nnf right) implements Nnf { }
        record Or(Nnf left, Nnf right) implements Nnf { }
    }

    private static Nnf toNnf(Expr expr, boolean negated) {
        return switch (expr) {
            case Expr.Label label -> negated ? new Nnf.Negative(label.name()) : new Nnf.Positive(label.name());
            case Expr.Not not -> toNnf(not.inner(), !negated);
            case Expr.And and -> negated
                    ? new Nnf.Or(toNnf(and.left(), true), toNnf(and.right(), true))
                    : new Nnf.And(toNnf(and.left(), false), toNnf(and.right(), false));
            case Expr.Or or -> negated
                    ? new Nnf.And(toNnf(or.left(), true), toNnf(or.right(), true))
                    : new Nnf.Or(toNnf(or.left(), false), toNnf(or.right(), false));
        };
    }

    private static String formatNnf(Nnf node) {
        return switch (node) {
            case Nnf.Positive positive -> "label:" + quoteGitHubLabel(positive.label());
            case Nnf.Negative negative -> "-label:" + quoteGitHubLabel(negative.label());
            case Nnf.And and -> formatAndChild(and.left()) + " AND " + formatAndChild(and.right());
            case Nnf.Or or -> formatOrChild(or.left()) + " OR " + formatOrChild(or.right());
        };
    }

    private static String formatAndChild(Nnf child) {
        String text = formatNnf(child);
        return child instanceof Nnf.Or ? "(" + text + ")" : text;
    }

    private static String formatOrChild(Nnf child) {
        String text = formatNnf(child);
        return child instanceof Nnf.And ? "(" + text + ")" : text;
    }

    private static String quoteGitHubLabel(String label) {
        StringBuilder out = new StringBuilder(label.length() + 2);
        out.append('"');
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c == '\\' || c == '"') {
                out.append('\\');
            }
            out.append(c);
        }
        out.append('"');
        return out.toString();
    }

    private static boolean eval(Expr expr, Set<String> presentLower) {
        return switch (expr) {
            case Expr.Label label -> presentLower.contains(label.name().toLowerCase(Locale.ROOT));
            case Expr.Not not -> !eval(not.inner(), presentLower);
            case Expr.And and -> eval(and.left(), presentLower) && eval(and.right(), presentLower);
            case Expr.Or or -> eval(or.left(), presentLower) || eval(or.right(), presentLower);
        };
    }

    // ---- Lexer ----

    private enum TokenKind { LPAREN, RPAREN, AND, OR, NOT, LABEL }

    private record Token(TokenKind kind, String text) { }

    private static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        int length = input.length();
        int i = 0;
        boolean expectingOperand = true;
        while (i < length) {
            char c = input.charAt(i);
            if (isSpace(c)) {
                i++;
                continue;
            }
            if (c == '(') {
                tokens.add(new Token(TokenKind.LPAREN, "("));
                expectingOperand = true;
                i++;
            } else if (c == ')') {
                tokens.add(new Token(TokenKind.RPAREN, ")"));
                expectingOperand = false;
                i++;
            } else if (c == '&') {
                if (i + 1 < length && input.charAt(i + 1) == '&') {
                    tokens.add(new Token(TokenKind.AND, "&&"));
                    i += 2;
                } else {
                    tokens.add(new Token(TokenKind.AND, "&"));
                    i++;
                }
                expectingOperand = true;
            } else if (c == '|') {
                if (i + 1 < length && input.charAt(i + 1) == '|') {
                    tokens.add(new Token(TokenKind.OR, "||"));
                    i += 2;
                } else {
                    tokens.add(new Token(TokenKind.OR, "|"));
                    i++;
                }
                expectingOperand = true;
            } else if (c == '!') {
                tokens.add(new Token(TokenKind.NOT, "!"));
                expectingOperand = true;
                i++;
            } else if (c == '-' && expectingOperand) {
                tokens.add(new Token(TokenKind.NOT, "-"));
                expectingOperand = true;
                i++;
            } else if (c == '"' || c == '\'') {
                int[] next = new int[1];
                String label = readQuoted(input, i, next);
                tokens.add(new Token(TokenKind.LABEL, label));
                expectingOperand = false;
                i = next[0];
            } else if (matchesKeyword(input, i, "AND")) {
                tokens.add(new Token(TokenKind.AND, "AND"));
                expectingOperand = true;
                i += 3;
            } else if (matchesKeyword(input, i, "OR")) {
                tokens.add(new Token(TokenKind.OR, "OR"));
                expectingOperand = true;
                i += 2;
            } else if (matchesKeyword(input, i, "NOT")) {
                tokens.add(new Token(TokenKind.NOT, "NOT"));
                expectingOperand = true;
                i += 3;
            } else {
                int start = i;
                while (i < length) {
                    char ch = input.charAt(i);
                    if (isHardDelimiter(ch)) {
                        break;
                    }
                    if (isSpace(ch)) {
                        int afterSpace = skipSpaces(input, i);
                        if (afterSpace >= length || isHardDelimiter(input.charAt(afterSpace))
                                || matchesKeyword(input, afterSpace, "AND")
                                || matchesKeyword(input, afterSpace, "OR")
                                || matchesKeyword(input, afterSpace, "NOT")) {
                            break;
                        }
                        i = afterSpace;
                        continue;
                    }
                    i++;
                }
                String label = input.substring(start, i).strip();
                if (label.isEmpty()) {
                    throw new IllegalArgumentException("Unexpected character '" + c + "'");
                }
                tokens.add(new Token(TokenKind.LABEL, label));
                expectingOperand = false;
            }
        }
        return tokens;
    }

    private static String readQuoted(String input, int start, int[] nextPos) {
        char quote = input.charAt(start);
        StringBuilder value = new StringBuilder();
        int i = start + 1;
        int length = input.length();
        while (i < length) {
            char c = input.charAt(i);
            if (c == '\\') {
                if (i + 1 >= length) {
                    throw new IllegalArgumentException("Unterminated escape in quoted label");
                }
                char escaped = input.charAt(i + 1);
                if (escaped == quote || escaped == '\\') {
                    value.append(escaped);
                    i += 2;
                } else {
                    throw new IllegalArgumentException("Unsupported escape '\\" + escaped + "' in quoted label");
                }
            } else if (c == quote) {
                String label = value.toString().strip();
                if (label.isEmpty()) {
                    throw new IllegalArgumentException("Label name must not be empty");
                }
                nextPos[0] = i + 1;
                return label;
            } else {
                value.append(c);
                i++;
            }
        }
        throw new IllegalArgumentException("Unterminated quoted label");
    }

    private static boolean matchesKeyword(String input, int offset, String keyword) {
        int end = offset + keyword.length();
        if (!input.regionMatches(offset, keyword, 0, keyword.length())) {
            return false;
        }
        if (offset > 0 && !isKeywordBoundary(input.charAt(offset - 1))) {
            return false;
        }
        return end == input.length() || isKeywordBoundary(input.charAt(end));
    }

    private static boolean isKeywordBoundary(char c) {
        return isSpace(c) || isHardDelimiter(c);
    }

    private static boolean isHardDelimiter(char c) {
        return c == '(' || c == ')' || c == '&' || c == '|' || c == '!' || c == '"' || c == '\'';
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    }

    private static int skipSpaces(String input, int from) {
        int i = from;
        while (i < input.length() && isSpace(input.charAt(i))) {
            i++;
        }
        return i;
    }

    // ---- Recursive-descent parser ----

    private static final class Parser {
        private final List<Token> tokens;
        private int pos;
        private int depth;

        private Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        Expr parse() {
            Expr result = parseOr();
            if (pos < tokens.size()) {
                Token extra = tokens.get(pos);
                if (extra.kind() == TokenKind.RPAREN) {
                    throw new IllegalArgumentException("Unexpected ')'");
                }
                throw new IllegalArgumentException("Expected '&', '|', 'AND', or 'OR' before '" + extra.text() + "'");
            }
            return result;
        }

        private Expr parseOr() {
            Expr left = parseAnd();
            while (match(TokenKind.OR)) {
                Expr right = parseAnd();
                left = new Expr.Or(left, right);
            }
            return left;
        }

        private Expr parseAnd() {
            Expr left = parseUnary();
            while (match(TokenKind.AND)) {
                Expr right = parseUnary();
                left = new Expr.And(left, right);
            }
            return left;
        }

        private Expr parseUnary() {
            if (match(TokenKind.NOT)) {
                enterDepth();
                try {
                    return new Expr.Not(parseUnary());
                } finally {
                    depth--;
                }
            }
            return parsePrimary();
        }

        private Expr parsePrimary() {
            if (pos >= tokens.size()) {
                throw new IllegalArgumentException("Expected a label or '(' at end of filter");
            }
            Token token = tokens.get(pos);
            if (token.kind() == TokenKind.LABEL) {
                pos++;
                return new Expr.Label(token.text());
            }
            if (token.kind() == TokenKind.LPAREN) {
                pos++;
                enterDepth();
                try {
                    Expr inner = parseOr();
                    if (!match(TokenKind.RPAREN)) {
                        throw new IllegalArgumentException("Missing closing ')'");
                    }
                    return inner;
                } finally {
                    depth--;
                }
            }
            throw new IllegalArgumentException("Expected a label or '(' before '" + token.text() + "'");
        }

        private boolean match(TokenKind kind) {
            if (pos < tokens.size() && tokens.get(pos).kind() == kind) {
                pos++;
                return true;
            }
            return false;
        }

        private void enterDepth() {
            depth++;
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("Label filter nesting is too deep");
            }
        }
    }
}
