package app.drydock.intellij.tasks;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The search field's prefixes, parsed in one pure place for both cards:
 * {@code pr:} and {@code issue:} terms (quoted when they contain spaces),
 * the {@code is:<group>} words, and the plain title text left over. A
 * {@code project:} prefix never reaches here — the field turns it into the
 * chip before the text is filtered by.
 *
 * <p>Every extracted term is a constraint the result must satisfy; several
 * terms of one kind AND together, and the kinds AND with the plain text.
 * A term with spaces must be quoted ({@code pr:"dark mode"}); unquoted
 * words after a prefix are separate terms. An argumentless prefix
 * ({@code pr:} alone, still being typed) constrains nothing rather than
 * matching everything.</p>
 *
 * <p>A term matches a pull request on its number (digits, optionally
 * {@code #-prefixed}), title, head branch or author, and an issue on its
 * number or key ({@code #12}, {@code PROJ-123}), summary or the label of a
 * mirror address ({@link app.drydock.tasks.TaskMirror#label}). Number terms
 * match exactly: {@code pr:12} is PR 12, not PR 123.</p>
 */
final class SearchTerms<G> {

    /** One pull request a search result can be matched against. */
    record PrRef(int number, String title, String headRefName, Optional<String> author) {
        PrRef {
            if (author == null) {
                author = Optional.empty();
            }
        }

        static PrRef of(int number, String title, String headRefName) {
            return new PrRef(number, title, headRefName, Optional.empty());
        }
    }

    /**
     * One issue a search result can be matched against. {@code key} is the
     * issue's short name — {@code #12} for a GitHub issue (number terms
     * match it exactly), the Jira key for a ticket (number terms match the
     * key's {@code -<digits>} tail, so {@code issue:42} is PROJ-42, not
     * PROJ-142).
     */
    record IssueRef(String key, Optional<String> title) {
        IssueRef {
            if (title == null) {
                title = Optional.empty();
            }
        }

        static IssueRef of(String key, String title) {
            return new IssueRef(key, Optional.ofNullable(title));
        }
    }

    private final List<String> pullRequests;
    private final List<String> issues;
    private final Set<G> groupKinds;
    private final List<String> plain;

    private SearchTerms(List<String> pullRequests, List<String> issues, Set<G> groupKinds, List<String> plain) {
        this.pullRequests = List.copyOf(pullRequests);
        this.issues = List.copyOf(issues);
        this.groupKinds = Set.copyOf(groupKinds);
        this.plain = List.copyOf(plain);
    }

    /** The parsed search text: every constraint the result must satisfy. Pure. */
    static <G> SearchTerms<G> parse(String text, Function<String, Optional<G>> kindDecoder) {
        List<String> pullRequests = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        Set<G> groupKinds = new LinkedHashSet<>();
        List<String> plain = new ArrayList<>();
        for (String token : tokens(text)) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.startsWith("pr:")) {
                String term = unquote(token.substring("pr:".length()));
                if (!term.isEmpty()) {
                    pullRequests.add(term);
                }
            } else if (lower.startsWith("issue:")) {
                String term = unquote(token.substring("issue:".length()));
                if (!term.isEmpty()) {
                    issues.add(term);
                }
            } else if (lower.startsWith("is:")) {
                Optional<G> kind = kindDecoder.apply(token.substring("is:".length()));
                if (kind.isPresent()) {
                    groupKinds.add(kind.get());
                } else {
                    plain.add(token);
                }
            } else {
                String textToken = unquote(token);
                if (!textToken.isEmpty()) {
                    plain.add(textToken);
                }
            }
        }
        return new SearchTerms<>(pullRequests, issues, groupKinds, plain);
    }

    /** The {@code pr:} terms; every one must match. */
    List<String> pullRequests() {
        return pullRequests;
    }

    /** The {@code issue:} terms; every one must match. */
    List<String> issues() {
        return issues;
    }

    /**
     * The server-side query for a pull-request search fetch: the
     * {@code pr:} terms that name a PR by text — number terms are left out,
     * they resolve by exact lookup ({@link #pullRequestNumbers()}) — plus
     * the plain text, with every multi-word span re-quoted for gh's
     * {@code --search}. Empty when no fetch is worth starting. Pure.
     */
    String pullRequestSearchQuery() {
        List<String> tokens = new ArrayList<>();
        for (String term : pullRequests) {
            if (!term.isBlank() && !isDigits(unhash(term))) {
                tokens.add(searchToken(term));
            }
        }
        for (String token : plain) {
            tokens.add(searchToken(token));
        }
        return String.join(" ", tokens);
    }

    /** Same rule for a GitHub issue search fetch; empty when nothing to ask. Pure. */
    String issueSearchQuery() {
        List<String> tokens = new ArrayList<>();
        for (String term : issues) {
            if (!term.isBlank()) {
                tokens.add(searchToken(term));
            }
        }
        for (String token : plain) {
            tokens.add(searchToken(token));
        }
        return String.join(" ", tokens);
    }

    /** The {@code pr:} terms that name a PR by number ({@code 12}, {@code #12}). Pure. */
    List<Integer> pullRequestNumbers() {
        List<Integer> numbers = new ArrayList<>();
        for (String term : pullRequests) {
            String needle = unhash(term);
            if (isDigits(needle)) {
                try {
                    numbers.add(Integer.parseInt(needle));
                } catch (NumberFormatException ignored) {
                    // Longer than an int: no PR carries that number.
                }
            }
        }
        return List.copyOf(numbers);
    }

    /** One term for gh's {@code --search}: multi-word spans stay one quoted token. */
    private static String searchToken(String term) {
        return term.contains(" ") ? '"' + term + '"' : term;
    }

    /** The decoded {@code is:<group>} constraints. */
    Set<G> groupKinds() {
        return groupKinds;
    }

    /** The plain title text: the leftover tokens joined by single spaces. */
    String plainText() {
        return String.join(" ", plain);
    }

    /** Whether no constraint of any kind is present. */
    boolean isEmpty() {
        return pullRequests.isEmpty() && issues.isEmpty() && groupKinds.isEmpty() && plain.isEmpty();
    }

    /** Whether every term matches at least one of the references — AND across terms, existential
     *  across references. Pure. */
    static boolean matchAllPullRequests(List<String> terms, List<PrRef> refs) {
        for (String term : terms) {
            if (refs.stream().noneMatch(ref -> pullRequest(term, ref))) {
                return false;
            }
        }
        return true;
    }

    /** Same rule for {@code issue:} terms. Pure. */
    static boolean matchAllIssues(List<String> terms, List<IssueRef> refs) {
        for (String term : terms) {
            if (refs.stream().noneMatch(ref -> issue(term, ref))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether every {@code pr:} term matches at least one of the
     * references — AND across terms, existential across references.
     */
    boolean matchPullRequests(List<PrRef> refs) {
        return matchAllPullRequests(pullRequests, refs);
    }

    /** Same rule for {@code issue:} terms. */
    boolean matchIssues(List<IssueRef> refs) {
        return matchAllIssues(issues, refs);
    }

    /** One term against one pull request. */
    static boolean pullRequest(String term, PrRef ref) {
        String needle = unhash(term);
        if (isDigits(needle)) {
            try {
                return ref.number() == Integer.parseInt(needle);
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return contains(ref.title(), needle) || contains(ref.headRefName(), needle)
                || ref.author().map(author -> contains(author, needle)).orElse(false);
    }

    /** One term against one issue. */
    static boolean issue(String term, IssueRef ref) {
        String needle = unhash(term);
        if (isDigits(needle)) {
            return ref.key().startsWith("#")
                    ? ref.key().substring(1).equals(needle)
                    : ref.key().toLowerCase(Locale.ROOT).endsWith("-" + needle.toLowerCase(Locale.ROOT));
        }
        return contains(ref.key(), needle) || ref.title().map(title -> contains(title, needle)).orElse(false);
    }

    /**
     * The search text's whitespace-separated tokens, with a quoted span
     * kept as one token wherever the quotes sit: {@code pr:"dark mode"} is
     * one token, and a lone {@code "} toggles rather than fails. Pure.
     */
    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                current.append(c);
            } else if (Character.isWhitespace(c) && !quoted) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    /** The term without one wrapping pair of quotes, if it has them. */
    private static String unquote(String term) {
        String stripped = term.strip();
        return stripped.length() >= 2 && stripped.startsWith("\"") && stripped.endsWith("\"")
                ? stripped.substring(1, stripped.length() - 1).strip()
                : stripped;
    }

    /** The term without a leading {@code #}, so {@code pr:#12} and {@code pr:12} agree. */
    private static String unhash(String term) {
        return term.startsWith("#") ? term.substring(1) : term;
    }

    private static boolean isDigits(String term) {
        if (term.isEmpty()) {
            return false;
        }
        for (int i = 0; i < term.length(); i++) {
            if (!Character.isDigit(term.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(String text, String needle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
