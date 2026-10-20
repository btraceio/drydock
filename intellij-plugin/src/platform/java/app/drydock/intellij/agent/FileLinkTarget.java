package app.drydock.intellij.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which paths in an agent's console are links into its worktree, as pure
 * functions (cross-project tabs, frame F2).
 *
 * <p>A path counts when it names an existing file inside the agent's own
 * worktree, absolute or relative to it, optionally followed by
 * {@code :<line>} (and a column, ignored). Anything outside the worktree is
 * left alone: a link that opened another project's file would be the
 * non-project-file trap the handoff routes around.</p>
 */
final class FileLinkTarget {

    /** A path-looking run with an extension, and an optional {@code :line[:column]}. */
    private static final Pattern TOKEN = Pattern.compile(
            "(?<![\\w/.\\-])((?:\\.{1,2}/|/)?[\\w.\\-]+(?:/[\\w.\\-]+)*\\.[A-Za-z0-9]+)(?::(\\d+))?(?::\\d+)?");

    private FileLinkTarget() {
    }

    /** Where in a console line a candidate starts and ends, and its text. */
    record Token(int start, int end, String text) {
    }

    /** A file in the worktree, and the 1-based line to open it at. */
    record Target(Path file, int line) {
        Target {
            Objects.requireNonNull(file, "file");
        }
    }

    /** The path-looking candidates in {@code line}, in order. */
    static List<Token> tokens(String line) {
        List<Token> found = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(line);
        while (matcher.find()) {
            found.add(new Token(matcher.start(), matcher.end(), matcher.group()));
        }
        return found;
    }

    /** {@code token} as a file in {@code worktree} that {@code exists}, or empty. */
    static Optional<Target> resolve(String token, Path worktree, Predicate<Path> exists) {
        Matcher matcher = TOKEN.matcher(token);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        Path root = worktree.toAbsolutePath().normalize();
        Path named = Path.of(matcher.group(1));
        Path file = (named.isAbsolute() ? named : root.resolve(named)).normalize();
        if (!file.startsWith(root) || file.equals(root) || !exists.test(file)) {
            return Optional.empty();
        }
        return Optional.of(new Target(file, lineOf(matcher.group(2))));
    }

    /** The 1-based line, 1 when absent or not a number an editor could have. */
    private static int lineOf(String digits) {
        if (digits == null) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(digits));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
