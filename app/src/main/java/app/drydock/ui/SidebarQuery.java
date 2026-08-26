package app.drydock.ui;

import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The sidebar filter's text matcher: compiles the raw filter field text once
 * per rebuild into a {@link Predicate} that {@link RepositorySidebar}'s
 * {@code matchesRepo}/{@code matchesNode}/{@code matchesPullRequest} consult
 * instead of a plain {@code String.contains}. Three shapes, picked by syntax:
 *
 * <ul>
 *   <li><b>Substring</b> (default, no special syntax) -- the historical
 *       behaviour: case-insensitive {@code contains}. Existing tests that
 *       type {@code "login"} and expect it to match {@code "login session"}
 *       keep passing untouched.</li>
 *   <li><b>Glob</b> -- when the query contains {@code *} or {@code ?} (and
 *       is not a regex form), translated to an equivalent regex with
 *       {@code *} &rarr; {@code .*} and {@code ?} &rarr; {@code .}, matched
 *       with {@code find} (so {@code *log*} matches anywhere, like substring
 *       does). Other regex metacharacters are escaped.</li>
 *   <li><b>Regex</b> -- when the query is wrapped in {@code /…/} or prefixed
 *       with {@code re:}, the body is compiled with {@code CASE_INSENSITIVE
 *       | DOTALL} and matched with {@code find}. An unparseable body falls
 *       back to a literal substring match of the body, never throwing --
 *       a typo in the filter must not blank the sidebar.</li>
 * </ul>
 *
 * <p>Every shape is case-insensitive; the substring/glob paths lowercase
 * both sides, the regex path uses the {@code CASE_INSENSITIVE} flag. A
 * {@code null}/blank raw query yields {@link #matchAll()}.</p>
 */
final class SidebarQuery {

    private final Predicate<String> test;
    private final boolean trivial;

    private SidebarQuery(Predicate<String> test, boolean trivial) {
        this.test = test;
        this.trivial = trivial;
    }

    /** The matcher that accepts everything (no filter text). */
    static SidebarQuery matchAll() {
        return new SidebarQuery(text -> true, true);
    }

    /** Compiles {@code raw} (the filter field's exact text) into a matcher. */
    static SidebarQuery of(String raw) {
        String query = raw == null ? "" : raw.strip();
        if (query.isEmpty()) {
            return matchAll();
        }
        String regexBody = regexBody(query);
        if (regexBody != null) {
            try {
                Pattern pattern = Pattern.compile(regexBody, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                return new SidebarQuery(text -> text != null && pattern.matcher(text).find(), false);
            } catch (PatternSyntaxException e) {
                return substring(regexBody);
            }
        }
        if (hasGlobMeta(query)) {
            try {
                Pattern pattern = Pattern.compile(globToRegex(query), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                return new SidebarQuery(text -> text != null && pattern.matcher(text).find(), false);
            } catch (PatternSyntaxException e) {
                return substring(query);
            }
        }
        return substring(query);
    }

    /** Whether {@code text} matches the compiled query. {@code null} text never matches. */
    boolean matches(String text) {
        return text != null && test.test(text);
    }

    /** Whether this matcher is the no-filter {@link #matchAll()} (no text typed). */
    boolean isTrivial() {
        return trivial;
    }

    /** Case-insensitive substring, the historical default. */
    private static SidebarQuery substring(String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        return new SidebarQuery(text -> text != null && text.toLowerCase(Locale.ROOT).contains(needle), false);
    }

    /** Returns the regex body if {@code query} selects the regex form, else {@code null}. */
    private static String regexBody(String query) {
        if (query.startsWith("re:")) {
            return query.substring(3);
        }
        if (query.length() >= 2 && query.startsWith("/") && query.endsWith("/")) {
            return query.substring(1, query.length() - 1);
        }
        return null;
    }

    private static boolean hasGlobMeta(String query) {
        return query.indexOf('*') >= 0 || query.indexOf('?') >= 0;
    }

    /** Translates a glob ({@code *}/{@code ?}) to a regex, escaping every other regex metacharacter. */
    private static String globToRegex(String glob) {
        StringBuilder out = new StringBuilder(glob.length() * 2);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> out.append(".*");
                case '?' -> out.append('.');
                default -> {
                    if ("\\.[]{}()+^$|/".indexOf(c) >= 0) {
                        out.append('\\');
                    }
                    out.append(c);
                }
            }
        }
        return out.toString();
    }
}
