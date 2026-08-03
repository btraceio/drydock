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

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private SymbolLens() {
    }

    /** The identifiers {@code text} uses more than once, keywords and short names excluded. */
    static Set<String> symbolsIn(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_SCANNED_CHARS) {
            return Set.of();
        }
        Map<String, Integer> counts = new HashMap<>();
        Matcher matcher = IDENTIFIER.matcher(text);
        while (matcher.find()) {
            String word = matcher.group();
            if (word.length() < SymbolPeekService.MIN_SYMBOL_LENGTH
                    || SymbolPeekService.KEYWORDS.contains(word.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            counts.merge(word, 1, Integer::sum);
        }
        Set<String> repeated = new LinkedHashSet<>();
        counts.forEach((word, count) -> {
            if (count > 1) {
                repeated.add(word);
            }
        });
        return repeated;
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
