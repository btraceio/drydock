package app.drydock.intellij.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The per-user token that distinguishes one user's generated task branches
 * from another's on a shared repository: initials derived from git's
 * {@code user.name} ({@code "Jaroslav Bachorik"} → {@code "jb"}; a
 * dotted login-shaped name splits the same way, {@code jaroslav.bachorik}
 * → {@code jb}), or the
 * sanitized OS login when initials cannot be derived -- {@code user.name}
 * unset, or a single word, whose lone initial identifies a name rather than
 * a person. Empty when neither source yields anything usable, in which case
 * the generated branch simply carries no token.
 *
 * <p>Sanitizing is unconditional: the token is spliced into a git branch
 * name and a worktree folder name, so only lowercase alphanumerics and
 * dashes survive, the same character class {@code WorktreeNaming.slug}
 * keeps.</p>
 */
final class BranchAttribution {

    /** Bound on the fallback token so a long login cannot bloat every branch and folder name. */
    private static final int MAX_TOKEN_LENGTH = 32;

    /** Initials come from at most the first three words of the full name. */
    private static final int MAX_INITIALS = 3;

    private BranchAttribution() { }

    /**
     * Initials of the full name's words: {@code "Jaroslav Bachorik"} →
     * {@code "jb"}. A single word (or nothing at all) yields empty -- the
     * caller falls back to the OS login -- because a lone initial says
     * almost nothing about who that is.
     */
    static Optional<String> initialsFromFullName(String userName) {
        if (userName == null || userName.isBlank()) {
            return Optional.empty();
        }
        List<String> words = words(userName);
        if (words.size() < 2) {
            return Optional.empty();
        }
        StringBuilder initials = new StringBuilder();
        for (String word : words.subList(0, Math.min(words.size(), MAX_INITIALS))) {
            initials.append(Character.toLowerCase(word.charAt(0)));
        }
        return Optional.of(initials.toString());
    }

    /** The sanitized OS login, or empty when it sanitizes to nothing. */
    static Optional<String> fromLogin(String login) {
        if (login == null || login.isBlank()) {
            return Optional.empty();
        }
        String slug = login.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(slug.length() <= MAX_TOKEN_LENGTH ? slug : slug.substring(0, MAX_TOKEN_LENGTH));
    }

    /** The attribution token, or {@code ""} when neither source yields one. */
    static String token(String gitUserName, String osLogin) {
        return initialsFromFullName(gitUserName)
                .or(() -> fromLogin(osLogin))
                .orElse("");
    }

    /** Maximal runs of letters, so {@code "Jean-Pierre Dupont"} splits into three words. */
    private static List<String> words(String userName) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < userName.length(); i++) {
            char c = userName.charAt(i);
            if (Character.isLetter(c)) {
                current.append(c);
            } else if (current.length() > 0) {
                words.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            words.add(current.toString());
        }
        return words;
    }
}
