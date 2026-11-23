package app.drydock.review;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * What counts as a symbol, for every lens in the application.
 *
 * <p>The Review tab's symbol lens and the Explorer's peek underline make the
 * same promise -- "here is every place this text appears" -- and the delta
 * asks the Explorer to use the <em>same</em> index rule as Review. Two copies
 * of this vocabulary drifted immediately (the copy lowercased before the
 * keyword test, which silently made identifiers named {@code Record},
 * {@code Class} or {@code Val} unpeekable), so both now read it from here.</p>
 */
public final class SymbolWords {

    /** Identifiers shorter than this are noise: {@code i}, {@code id}, {@code of}. */
    public static final int MIN_LENGTH = 3;

    /** Java-ish identifiers; the shape both lenses treat as a word. */
    public static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    /**
     * Words never worth a lens. Keywords are not symbols, and offering a
     * popover on {@code return} teaches the reader that the underline means
     * nothing. Matched case-SENSITIVELY: {@code record} is a keyword,
     * {@code Record} is somebody's type.
     */
    public static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
            "int", "interface", "long", "native", "new", "package", "private", "protected",
            "public", "record", "return", "sealed", "short", "static", "super", "switch",
            "synchronized", "this", "throw", "throws", "transient", "try", "var", "void",
            "volatile", "while", "yield", "true", "false", "null",
            "fun", "val", "let", "function", "def", "self", "from", "with", "and", "not");

    /**
     * Names some grammars DO parse as ordinary identifiers although they
     * never name anything a reader could follow: a receiver (Python's
     * {@code self}, which tree-sitter-python emits as an {@code identifier})
     * or a predeclared literal (Go's {@code nil}, {@code true}). Linking
     * every hunk that mentions one would make the link meaningless.
     */
    private static final Set<String> NON_REFERRING_NAMES = Set.of(
            "self", "cls", "this", "super", "true", "false", "null", "nil", "None", "True", "False");

    private SymbolWords() {
    }

    /** Whether {@code word} is worth indexing, underlining or offering a popover on. */
    public static boolean isSymbol(String word) {
        return word != null && word.length() >= MIN_LENGTH && !KEYWORDS.contains(word);
    }

    /**
     * Whether a name a GRAMMAR classified as an identifier is worth a
     * symbol. {@link #KEYWORDS} is the wrong filter here: it is the union
     * of several languages' reserved words, kept for text no parser has
     * seen, and a parser has already refused this language's real keywords
     * by the time it calls something an identifier. Applied anyway, it
     * dropped legal names -- Java's contextual {@code record}/{@code var}/
     * {@code yield}, and Python's keyword {@code from} used as a Java
     * method -- leaving every call to them unlinked.
     */
    public static boolean isParsedSymbol(String name) {
        return name != null && name.length() >= MIN_LENGTH && !NON_REFERRING_NAMES.contains(name);
    }
}
