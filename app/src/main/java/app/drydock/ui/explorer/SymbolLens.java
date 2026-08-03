package app.drydock.ui.explorer;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which identifiers in an open file are worth peeking at, and the style
 * layer that underlines them (Explorer delta, part 1).
 *
 * <p>The rule is the Review symbol lens's rule, applied to a file instead of
 * a diff: an identifier that appears <em>more than once</em> is a symbol
 * worth following; one that appears once is the thing the reader is already
 * looking at. Keywords and identifiers under three characters are excluded,
 * because an underline on every word means nothing.</p>
 *
 * <p>Computed once per load, off the FX thread, alongside the lexer spans.</p>
 */
final class SymbolLens {

    /** Files past this many characters get no lens: the scan is linear, but so is the reader's patience. */
    private static final int MAX_SCANNED_CHARS = 1_500_000;

    private static final Pattern IDENTIFIER = app.drydock.review.SymbolWords.IDENTIFIER;

    private SymbolLens() {
    }

    /**
     * The identifiers in {@code text} worth following: those it uses more
     * than once, plus those that <em>lead somewhere else</em> -- a
     * capitalised name (a type) or a name applied to an argument list (a
     * call).
     *
     * <p>The "more than once" rule alone is Review's rule, and it is wrong
     * for a file: a type or method mentioned here exactly once is the single
     * most valuable thing to follow when hopping into unfamiliar code, and
     * an underline it never gets is a hop the reader cannot make. Locals
     * ({@code w}, {@code raw}, {@code delta}) still get nothing, which is
     * what keeps the underline meaningful.</p>
     */
    static Set<String> symbolsIn(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_SCANNED_CHARS) {
            return Set.of();
        }
        Map<String, Integer> counts = new HashMap<>();
        Set<String> followable = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER.matcher(text);
        while (matcher.find()) {
            String word = matcher.group();
            if (!app.drydock.review.SymbolWords.isSymbol(word)) {
                continue;
            }
            counts.merge(word, 1, Integer::sum);
            boolean typeLike = Character.isUpperCase(word.charAt(0));
            boolean callLike = matcher.end() < text.length() && text.charAt(matcher.end()) == '(';
            if (typeLike || callLike) {
                followable.add(word);
            }
        }
        Set<String> lens = new LinkedHashSet<>(followable);
        counts.forEach((word, count) -> {
            if (count > 1) {
                lens.add(word);
            }
        });
        return lens;
    }

    /**
     * A style layer marking every occurrence of {@code symbols}. Empty
     * (one span covering the whole text) when there is nothing to mark, so
     * the caller can overlay it unconditionally.
     */
    static StyleSpans<Collection<String>> spans(String text, Set<String> symbols) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        if (text == null || text.isEmpty()) {
            builder.add(List.of(), 0);
            return builder.create();
        }
        if (symbols.isEmpty()) {
            builder.add(List.of(), text.length());
            return builder.create();
        }
        int last = 0;
        Matcher matcher = IDENTIFIER.matcher(text);
        while (matcher.find()) {
            if (!symbols.contains(matcher.group())) {
                continue;
            }
            builder.add(List.of(), matcher.start() - last);
            builder.add(List.of("symbol-lens"), matcher.end() - matcher.start());
            last = matcher.end();
        }
        builder.add(List.of(), text.length() - last);
        return builder.create();
    }
}
