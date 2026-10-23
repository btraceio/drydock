package app.drydock.intellij.review;

import app.drydock.git.DiffScope;
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.GitStatusService;
import app.drydock.git.UnifiedDiff;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import app.drydock.review.ReviewScope;
import app.drydock.review.SessionReviewScopes;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Reads the two texts a file's diff request is built from — the left
 * (pre-change) and right (post-change) side — plus the commits a scope's
 * ends resolve to (AC14, and AC15's detection half).
 *
 * <p><strong>Every method that touches git or the filesystem is
 * blocking.</strong> Call them on a background thread only — never on the
 * EDT, and never inside the changes tree builder: the builder runs where the
 * tree model is assembled, and a process spawn there is exactly the spike
 * defect this class exists to fix (kb/find-p2-spikes-live-results: the spike
 * ran {@code git show} inside the tree-model builder). The Review tab's
 * selection path loads contents on a background executor and hops back to
 * the EDT with the result; nothing in this class does that hop for the
 * caller.</p>
 *
 * <p>The left side is read at the <em>merge base</em> of {@code
 * scope.base()} and HEAD — the commit {@code DiffService} measures
 * {@link DiffScope#BRANCH_WORKING_TREE} and {@link DiffScope#BASE} from —
 * not at the base branch's tip. The spike read {@code main}'s tip, which is
 * spike defect 2: once {@code main} moves past the fork, every left text
 * and therefore every fragment row is misaligned against the hunks git
 * actually produced. A renamed file is read at its pre-image path ({@code
 * FileDiff.oldPath()}, the T1 seam), because that is where the pre-image
 * content lives; an added file has no left side at all, and a deleted file
 * no right side — both were skipped outright in the spike (defect 3), where
 * here they are ordinary review material with an empty side
 * (kb/find-p2-spikes-live-results).</p>
 *
 * <p>Git runs through {@link ProcessRunner} exactly the way {@link
 * GitStatusService} runs it — the shared locator, a short read timeout,
 * argument lists only, and {@code --end-of-options} in front of every
 * revision and path that could begin with {@code -} (kb/find-p1-impl-gotchas).
 * A failure is never a fabricated success: each one logs a WARNING with a
 * stderr excerpt and yields the empty side, so the diff preview degrades to
 * an empty text the caller can check with {@link #matchesRows} rather than
 * rendering misaligned fragments over plausible-looking content. Hunk
 * identity stays with git (kb/hyp-git-diff-stays-authoritative,
 * kb/find-line-key-contract): the row line numbers {@link #matchesRows}
 * checks are the same 1-based git gutter numbers the line keys derive from.</p>
 */
public final class ReviewContents {

    private static final Logger LOG = System.getLogger(ReviewContents.class.getName());

    /** As in {@link GitStatusService}: every command here is a quick local read. */
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

    /** Added-file kind letter, as {@code git diff} reports it and DiffService parses it. */
    private static final String KIND_ADDED = "A";

    /** Deleted-file kind letter. */
    private static final String KIND_DELETED = "D";

    private final GitExecutableLocator locator;
    private final GitStatusService git;

    /**
     * @param locator resolves the {@code git} executable for this class's
     *                own {@code show}/{@code merge-base} calls
     * @param git     supplies {@link GitStatusService#commitForRefBlocking},
     *                so commit resolution is the one implementation of
     *                "ref → commit id" in the codebase — never re-derived here
     */
    public ReviewContents(GitExecutableLocator locator, GitStatusService git) {
        this.locator = Objects.requireNonNull(locator, "locator");
        this.git = Objects.requireNonNull(git, "git");
    }

    /**
     * The left (pre-change) text of {@code file} in {@code scope}'s diff.
     *
     * <p>The left revision is the merge base of {@code scope.base()} and HEAD
     * for {@link DiffScope#BRANCH_WORKING_TREE} and {@link DiffScope#BASE} —
     * {@code DiffService} measures both from the fork point, so the text the
     * fragments are placed over must come from the same commit — and plain
     * HEAD for {@link DiffScope#WORKING_TREE}, whose diff is {@code HEAD}
     * against the worktree. The content is read with {@code git show
     * --end-of-options <rev>:<path>}, at the pre-image path for a rename
     * ({@code oldPath()}), because a rename's left side lives where the file
     * used to be.</p>
     *
     * <p>An added file (kind {@code A}, untracked files included — they are
     * diffed as additions) has no left side: empty text, and no git process
     * is spawned for it. Any git failure (unresolvable base, path gone at
     * the revision, git missing) logs a WARNING with the stderr excerpt and
     * returns empty. Blocking; never on the EDT, never in the tree builder.</p>
     *
     * <p>The text is returned with its line separators normalized to LF
     * ({@link #normalizeLineSeparators}), like {@link #rightText}.</p>
     */
    public String leftText(ReviewScope scope, UnifiedDiff.FileDiff file) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(file, "file");
        if (KIND_ADDED.equals(file.kind())) {
            return "";
        }
        Optional<String> revision = leftRevision(scope);
        if (revision.isEmpty()) {
            // merge-base already logged why it could not be measured.
            return "";
        }
        return normalizeLineSeparators(show(scope.diffRoot(), revision.get(), preImagePath(file)));
    }

    /**
     * The left-side revision for {@code scope}: HEAD where the diff compares
     * the worktree against it, the merge base of {@code scope.base()} and
     * HEAD where the diff measures from the fork point. {@code
     * ReviewScope.diffScope()} never yields {@link DiffScope#UPSTREAM} (no
     * review scope maps to it), so that arm is a guard, not a behavior.
     */
    private Optional<String> leftRevision(ReviewScope scope) {
        return switch (scope.diffScope()) {
            case WORKING_TREE -> Optional.of("HEAD");
            case BRANCH_WORKING_TREE, BASE -> mergeBase(scope);
            case UPSTREAM -> throw new IllegalStateException(
                    "a review scope never diffs against its upstream: " + scope);
        };
    }

    /**
     * The merge base of {@code scope.base()} and HEAD in {@code
     * scope.diffRoot()} — the commit {@code DiffService} measures a
     * {@code BRANCH_WORKING_TREE}/{@code BASE} diff from. Empty (with a
     * WARNING naming git's own reason) when it cannot be resolved, so the
     * caller yields an empty left side rather than guessing a revision.
     */
    private Optional<String> mergeBase(ReviewScope scope) {
        Path gitExe = locateGit();
        if (gitExe == null) {
            return Optional.empty();
        }
        // --end-of-options: a base ref that looks like an option must reach
        // git as a revision, never as a flag — same belt as everywhere else.
        List<String> command = List.of(
                gitExe.toString(), "-C", scope.diffRoot().toString(),
                "merge-base", "--end-of-options", scope.base(), "HEAD");
        ProcessResult result = run(command);
        if (result == null) {
            return Optional.empty();
        }
        if (result.exitCode() != 0) {
            // No common ancestor, or a base that does not resolve here: git
            // says so on stderr, and the caller must not guess a revision.
            LOG.log(Level.WARNING, "git merge-base failed (exit " + result.exitCode() + ") in "
                    + scope.diffRoot() + " (base " + scope.base() + "): "
                    + ProcessRunner.excerpt(result.stderr()));
            return Optional.empty();
        }
        String base = result.stdout().strip();
        if (base.isEmpty()) {
            LOG.log(Level.WARNING, "git merge-base produced no output in " + scope.diffRoot()
                    + " (base " + scope.base() + ")");
            return Optional.empty();
        }
        return Optional.of(base);
    }

    /**
     * The right (post-change) text of {@code file} in {@code scope}'s diff.
     *
     * <p>Where the scope's diff includes the working tree ({@code
     * WORKING_TREE}, {@code BRANCH_WORKING_TREE}) the right side is the
     * worktree file itself; for {@link DiffScope#BASE} — whose diff ends at
     * HEAD — it is {@code git show --end-of-options HEAD:<path>}, the same
     * commit the diff's right side is measured at. A deleted file (kind
     * {@code D}) has no right side: empty text, no read and no git process.
     * Spike defect 3, fixed: a deletion is review material with an empty
     * right side, not a file to skip. Any failure logs a WARNING with the
     * stderr excerpt and returns empty. Blocking; never on the EDT, never
     * in the tree builder.</p>
     *
     * <p>The text is returned with its line separators normalized to LF
     * ({@link #normalizeLineSeparators}).</p>
     */
    public String rightText(ReviewScope scope, UnifiedDiff.FileDiff file) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(file, "file");
        if (KIND_DELETED.equals(file.kind())) {
            return "";
        }
        if (scope.diffScope() == DiffScope.BASE) {
            return normalizeLineSeparators(show(scope.diffRoot(), "HEAD", file.path()));
        }
        Path worktreeFile = scope.diffRoot().resolve(file.path());
        try {
            return normalizeLineSeparators(Files.readString(worktreeFile));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "could not read " + worktreeFile
                    + ": " + e.getMessage() + "; right side is empty");
            return "";
        }
    }

    /**
     * The scope's base as a resolved commit id — never the ref name — or
     * empty when it cannot be resolved. A verdict or staleness check keyed
     * on {@code "main"} and compared against {@code "main"} would never
     * read as stale; {@link GitStatusService#commitForRefBlocking} is the
     * one implementation of "ref → commit id" (it logs the WARNING for an
     * unresolvable ref itself, and tolerates a missing git executable as
     * empty). Blocking; never on the EDT, never in the tree builder.
     */
    public Optional<String> baseCommit(ReviewScope scope) {
        return git.commitForRefBlocking(scope.diffRoot(), scope.base());
    }

    /**
     * The scope's head as a resolved commit id — never the ref name, and
     * never a display label: {@code SessionReviewScopes.headRevision} maps
     * the {@code "(no branch)"} a local scope carries for a checkout with
     * no branch to {@code HEAD} before it is resolved. Blocking; never on
     * the EDT, never in the tree builder.
     */
    public Optional<String> headCommit(ReviewScope scope) {
        return git.commitForRefBlocking(scope.diffRoot(),
                SessionReviewScopes.headRevision(scope.head()));
    }

    /**
     * The one line-separator normalization of the review preview: CRLF and
     * lone CR become LF. Both sides go through it in {@link #leftText} and
     * {@link #rightText}, so the row check ({@link #matchesRows}), the
     * fragment offsets ({@code ReviewFragments}) and the documents the
     * request is built over ({@code ReviewDiffRequests.toRequest}) all see
     * the same text. The platform's {@code EditorFactory.createDocument}
     * refuses a {@code \r} with an {@code AssertionError} (263 bytecode:
     * {@code DocumentImpl(text)} accepts no CR), so un-normalized CRLF text
     * could never be shown, and normalizing only the documents would leave
     * fragment offsets computed over the longer CRLF text. The hunk rows
     * already carry no CR: {@code DiffService} splits git's output with
     * {@code String.lines()}. Pure; {@code null} reads as empty.
     */
    public static String normalizeLineSeparators(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.indexOf('\r') < 0) {
            return text;
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * Whether {@code text} still carries {@code file}'s hunk rows on the
     * checked side: every row with a line number there (old for the left
     * side, new for the right) must find exactly its own text at that
     * line. The line numbers are the 1-based git gutter numbers the hunks
     * carry (the same numbers line keys derive from,
     * kb/find-line-key-contract). A row past the end of the text, or a line
     * whose content differs from the row — an edit after the diff, even
     * one that keeps or grows the line count, such as lines inserted above
     * a hunk — means fragments computed for the old rows would be placed
     * on the wrong lines (AC15's detection half; the tab shows its
     * "changed since the diff" state and schedules exactly one re-diff
     * when this returns false). The line count is checked first, as a
     * cheap pre-filter.
     *
     * <p>Pure: no git, no I/O, no IDE state. The text is compared after
     * {@link #normalizeLineSeparators}. A text with no rows on the checked
     * side (a pure deletion has no new-side rows; an addition no old-side
     * ones) matches by definition — nothing contradicts it.</p>
     */
    public static boolean matchesRows(String text, UnifiedDiff.FileDiff file, boolean rightSide) {
        String normalized = normalizeLineSeparators(text);
        int lineCount = lineCount(normalized);
        for (UnifiedDiff.Hunk hunk : file.hunks()) {
            for (UnifiedDiff.Line line : hunk.lines()) {
                OptionalInt number = rightSide ? line.newLine() : line.oldLine();
                if (number.isPresent() && number.getAsInt() > lineCount) {
                    return false;
                }
            }
        }
        String[] lines = normalized.split("\n", -1);
        for (UnifiedDiff.Hunk hunk : file.hunks()) {
            for (UnifiedDiff.Line line : hunk.lines()) {
                OptionalInt number = rightSide ? line.newLine() : line.oldLine();
                if (number.isEmpty()) {
                    continue;
                }
                int index = number.getAsInt() - 1;
                if (index < 0 || !lines[index].equals(stripCarriageReturn(line.text()))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A row's text without a trailing CR, so a hand-built CRLF row compares like git's. */
    private static String stripCarriageReturn(String text) {
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }

    /**
     * The number of lines in {@code text}, counting LF terminators the way
     * the fragment offsets count them ({@code ReviewFragments} splits on
     * LF only): {@code "a\nb"} and {@code "a\nb\n"} are both two lines, a
     * trailing newline starts no further line, and empty text is zero
     * lines. A null text is zero lines too — the loading half hands the
     * empty string around, but a null from a caller must read as "nothing
     * to place rows on" rather than throw.
     */
    private static int lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        if (text.charAt(text.length() - 1) == '\n') {
            lines--;
        }
        return lines;
    }

    /** The path the left side is read at: the pre-image path of a rename, the file's own path otherwise. */
    private static String preImagePath(UnifiedDiff.FileDiff file) {
        return file.oldPath().orElse(file.path());
    }

    /**
     * Runs {@code git show --end-of-options <revision>:<path>} in {@code
     * root}, returning the blob content verbatim, or empty (with a WARNING
     * naming the command and a stderr excerpt) when the revision, the path,
     * or git itself fails — never a fabricated success.
     */
    private String show(Path root, String revision, String path) {
        Path gitExe = locateGit();
        if (gitExe == null) {
            return "";
        }
        List<String> command = List.of(
                gitExe.toString(), "-C", root.toString(),
                "show", "--end-of-options", revision + ":" + path);
        ProcessResult result = run(command);
        if (result == null) {
            return "";
        }
        if (result.exitCode() != 0) {
            LOG.log(Level.WARNING, "git show failed (exit " + result.exitCode() + ") in " + root
                    + ": " + ProcessRunner.excerpt(result.stderr()));
            return "";
        }
        return result.stdout();
    }

    /** The located git executable, or {@code null} after logging that none could be found. */
    private Path locateGit() {
        Optional<Path> gitExe = locator.locate();
        if (gitExe.isEmpty()) {
            LOG.log(Level.WARNING, "No git executable found. Searched: " + locator.describeSearched());
            return null;
        }
        return gitExe.get();
    }

    /**
     * Runs one quick git command through the shared {@link ProcessRunner}
     * with {@link GitStatusService}'s options, translating every failure
     * mode into {@code null} (the caller logs and degrades) — except an
     * interrupt, which also restores the thread's interrupt flag so the
     * background loader's shutdown sees it.
     */
    private ProcessResult run(List<String> command) {
        try {
            return ProcessRunner.run(command, new ProcessRunner.Options(null, PROCESS_TIMEOUT, false, Map.of()));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "could not launch git: "
                    + (e.getMessage() == null ? "" : e.getMessage()));
            return null;
        } catch (ProcessTimeoutException e) {
            LOG.log(Level.WARNING, "git timed out after " + PROCESS_TIMEOUT.toSeconds() + "s (killed)");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, "interrupted while waiting for git");
            return null;
        }
    }
}
