package app.drydock.git;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * Git's refname rules for a branch name Drydock is about to <em>mint</em>
 * with {@code git worktree add -b}, plus the remote-shadow rule that is
 * Drydock's own.
 *
 * <p>A name is checked here when, and only when, it is about to be created:
 * the typed name in the create-worktree modal's new-branch mode, the
 * {@code branch} argument of {@code worktree_create}, and the local name
 * derived when a remote-tracking ref is adopted. Checking out an existing
 * local branch mints nothing and is never checked -- refusing
 * {@code origin/main} there would be advice a user cannot act on, and would
 * make the branch this rule set exists to prevent impossible to clean up.</p>
 *
 * <p>The load-bearing case is a name that collides with a remote: creating
 * local branch {@code refs/heads/origin/main} <em>shadows</em>
 * {@code refs/remotes/origin/main} for every short-name lookup, so a later
 * {@code git merge origin/main} would silently target this commit instead of
 * the fetched ref. {@code git worktree add} exits 0 and warns only on stderr,
 * so nothing downstream would catch it. This was verified empirically against
 * real git before the rule was written.</p>
 *
 * <p>The remote check compares the whole first path component against each
 * remote name, case-insensitively, for three reasons: (1) a remote name that
 * only prefixes the first component -- {@code "originals"} vs. remote
 * {@code "origin"} -- must not match, only a whole component does; (2) a ref
 * file such as {@code .git/refs/heads/origin/main} is a plain {@code open()}
 * on the macOS default case-insensitive filesystem, so a branch named
 * {@code "Origin/main"} lands in the very same place and shadows the remote
 * just the same -- verified against real git 2.49; and (3) git permits a
 * remote name that itself contains a slash (e.g. {@code remote.foo/bar.url}),
 * so the check cannot simply split the branch on {@code "/"} and compare only
 * the first segment.</p>
 *
 * <p><strong>The evaluation order below is normative.</strong> A name can
 * violate two clauses at once, and which one is reported is a wire contract:
 * these refusals are rendered verbatim into {@code worktree_create}'s error
 * messages. In particular the component clauses are judged
 * <em>component-major</em> -- each component through all three clauses before
 * the next component is looked at -- so {@code foo.lock/.bar} reports the
 * {@code .lock} suffix and not the leading dot. The forbidden-character scan
 * likewise walks the <em>name</em>, so {@code a^b~c} reports {@code ^}.</p>
 *
 * <p>The rules are applied here rather than by spawning
 * {@code git check-ref-format}: this runs on every keystroke in the modal,
 * the rules are stable, and a per-call process spawn to validate a string is
 * not the kind of real work {@code ProcessRunner} exists for.</p>
 */
public final class BranchNameRules {

    private BranchNameRules() {
    }

    /** Characters git's refname rules forbid anywhere in the name, besides control characters. */
    private static final String FORBIDDEN_CHARS = " ~^:?*[\\";

    /** Which rule a name broke. The order of declaration is the order of evaluation. */
    public enum Clause {
        BLANK, REFS_PREFIX, SHADOWS_REMOTE, DOT_DOT, AT_BRACE, DOUBLE_SLASH,
        LEADING_DASH, TRAILING_SLASH_OR_DOT, FORBIDDEN_CHAR,
        EMPTY_COMPONENT, COMPONENT_LEADING_DOT, COMPONENT_LOCK_SUFFIX
    }

    /**
     * Why a name cannot be minted.
     *
     * @param token the matched remote, the offending character, or the
     *              offending path component -- and {@code ""}, never
     *              {@code null}, for the clauses that quote nothing
     */
    public record Refusal(Clause clause, String token) {

        public Refusal {
            Objects.requireNonNull(clause, "clause");
            Objects.requireNonNull(token, "token");
        }
    }

    /**
     * Empty when {@code name} is safe to create as a local branch.
     *
     * @param remotes every configured remote; must not be null (callers that
     *                take it from an agent guard that separately, so the
     *                message stays theirs)
     */
    public static Optional<Refusal> check(String name, Collection<String> remotes) {
        Objects.requireNonNull(remotes, "remotes");

        if (name == null || name.isBlank()) {
            return refusal(Clause.BLANK);
        }
        if (name.startsWith("refs/")) {
            return refusal(Clause.REFS_PREFIX);
        }

        // Longest match wins: a remote name may itself contain a slash, so
        // both "origin" and "origin/team" can match origin/team/x, and naming
        // the shorter one would be arbitrary.
        String shadowed = null;
        for (String remote : remotes) {
            String withSlash = remote + "/";
            if (name.regionMatches(true, 0, withSlash, 0, withSlash.length())
                    && (shadowed == null || remote.length() > shadowed.length())) {
                shadowed = remote;
            }
        }
        if (shadowed != null) {
            return refusal(Clause.SHADOWS_REMOTE, shadowed);
        }

        if (name.contains("..")) {
            return refusal(Clause.DOT_DOT);
        }
        if (name.contains("@{")) {
            return refusal(Clause.AT_BRACE);
        }
        if (name.contains("//")) {
            return refusal(Clause.DOUBLE_SLASH);
        }
        if (name.startsWith("-")) {
            return refusal(Clause.LEADING_DASH);
        }
        if (name.endsWith("/") || name.endsWith(".")) {
            return refusal(Clause.TRAILING_SLASH_OR_DOT);
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (FORBIDDEN_CHARS.indexOf(c) >= 0 || Character.isISOControl(c)) {
                return refusal(Clause.FORBIDDEN_CHAR, String.valueOf(c));
            }
        }

        // Component-major, deliberately: see the class Javadoc.
        for (String component : name.split("/", -1)) {
            if (component.isEmpty()) {
                return refusal(Clause.EMPTY_COMPONENT);
            }
            if (component.startsWith(".")) {
                return refusal(Clause.COMPONENT_LEADING_DOT, component);
            }
            if (component.endsWith(".lock")) {
                return refusal(Clause.COMPONENT_LOCK_SUFFIX, component);
            }
        }

        return Optional.empty();
    }

    /**
     * The wording {@code worktree_create} has always returned, reproduced word
     * for word. This is a wire contract with agents that parse or quote it;
     * change it only deliberately.
     */
    public static String agentMessage(String name, Refusal refusal) {
        return switch (refusal.clause()) {
            case BLANK -> "branch must not be blank";
            case REFS_PREFIX ->
                    "branch must not be a fully qualified ref (starts with 'refs/'): " + name;
            case SHADOWS_REMOTE -> "'" + name + "' as a local branch shadows the remote-tracking ref '"
                    + refusal.token() + "'; pick a name that does not start with a remote name";
            case DOT_DOT -> "branch name must not contain '..': " + name;
            case AT_BRACE -> "branch name must not contain '@{': " + name;
            case DOUBLE_SLASH -> "branch name must not contain consecutive slashes: " + name;
            case LEADING_DASH -> "branch name must not start with '-': " + name;
            case TRAILING_SLASH_OR_DOT -> "branch name must not end with '/' or '.': " + name;
            case FORBIDDEN_CHAR -> "branch name must not contain '" + refusal.token() + "': " + name;
            case EMPTY_COMPONENT -> "branch name must not have an empty path component: " + name;
            case COMPONENT_LEADING_DOT ->
                    "branch name component must not start with '.': " + refusal.token();
            case COMPONENT_LOCK_SUFFIX ->
                    "branch name component must not end with '.lock': " + refusal.token();
        };
    }

    /**
     * A standalone sentence for the modal's hint line. Separate from
     * {@link #agentMessage} because the two audiences differ: the agent-facing
     * text keeps git's own register and quotes the whole name it was given,
     * while the human is already looking at the field they typed it into.
     */
    public static String humanSentence(Refusal refusal) {
        return switch (refusal.clause()) {
            case BLANK -> "Name the branch to create.";
            case REFS_PREFIX -> "A branch name cannot start with 'refs/'.";
            case SHADOWS_REMOTE ->
                    "A branch by that name would shadow the remote '" + refusal.token() + "'.";
            case DOT_DOT -> "A branch name cannot contain '..'.";
            case AT_BRACE -> "A branch name cannot contain '@{'.";
            case DOUBLE_SLASH -> "A branch name cannot contain two slashes in a row.";
            case LEADING_DASH -> "A branch name cannot start with '-'.";
            case TRAILING_SLASH_OR_DOT -> "A branch name cannot end with '/' or '.'.";
            case FORBIDDEN_CHAR ->
                    "A branch name cannot contain the character '" + refusal.token() + "'.";
            case EMPTY_COMPONENT -> "A branch name cannot have an empty path component.";
            case COMPONENT_LEADING_DOT ->
                    "A path component cannot start with '.' ('" + refusal.token() + "').";
            case COMPONENT_LOCK_SUFFIX ->
                    "A path component cannot end with '.lock' ('" + refusal.token() + "').";
        };
    }

    /**
     * A clause fragment completing {@code "…, which "}, for dropdown rows and
     * the unmintable hints, where the sentence already has a subject.
     */
    public static String shortClause(Refusal refusal) {
        return switch (refusal.clause()) {
            case BLANK -> "has no name";
            case REFS_PREFIX -> "starts with 'refs/'";
            case SHADOWS_REMOTE -> "shadows the remote '" + refusal.token() + "'";
            case DOT_DOT -> "contains '..'";
            case AT_BRACE -> "contains '@{'";
            case DOUBLE_SLASH -> "contains '//'";
            case LEADING_DASH -> "starts with '-'";
            case TRAILING_SLASH_OR_DOT -> "ends with '/' or '.'";
            case FORBIDDEN_CHAR -> "contains '" + refusal.token() + "'";
            case EMPTY_COMPONENT -> "has an empty path component";
            case COMPONENT_LEADING_DOT -> "has a component starting with '.'";
            case COMPONENT_LOCK_SUFFIX -> "has a component ending with '.lock'";
        };
    }

    private static Optional<Refusal> refusal(Clause clause) {
        return refusal(clause, "");
    }

    private static Optional<Refusal> refusal(Clause clause, String token) {
        return Optional.of(new Refusal(clause, token));
    }
}
