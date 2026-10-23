package app.drydock.intellij.review;

import app.drydock.git.DiffScope;
import app.drydock.git.DiffService;
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.GitStatusService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewScope;
import com.intellij.diff.fragments.LineFragment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests against real temporary git repositories, in the style
 * of {@code DiffServiceTest}: the review contents the diff preview places
 * its fragments over (AC14), and the row/text agreement check (AC15's
 * detection half). The {@code FileDiff}s come from {@link DiffService}
 * itself, so what is read is verified against the same diff that produced
 * the hunks.
 */
class ReviewContentsTest {

    private final DiffService diffs = new DiffService();
    private final GitStatusService gitStatus = new GitStatusService();
    private final ReviewContents contents =
            new ReviewContents(new GitExecutableLocator(), gitStatus);

    @AfterEach
    void close() {
        diffs.close();
        gitStatus.close();
    }

    /**
     * AC14, spike defect 2: {@code main} advanced past the fork and changed
     * the SAME file, so its tip no longer holds the content the diff was
     * measured from. The left text must be the merge base's content in both
     * fork-measured scopes ({@code BRANCH_WORKING_TREE} for a worktree
     * scope, {@code BASE} for a branch scope) — never the base tip, which
     * the spike read.
     */
    @Test
    void leftTextReadsTheMergeBaseNotTheBaseTipWhenMainMovedPastTheFork(
            @TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir, "one\ntwo\nthree\n");
        String mergeBase = shaOf(repo, "HEAD");
        runGit(repo, "checkout", "-b", "feat/change");
        Files.writeString(repo.resolve("README.md"), "one\nBRANCH\nthree\nfour\n");
        runGit(repo, "add", "README.md");
        commit(repo, "change on branch");
        runGit(repo, "checkout", "main");
        Files.writeString(repo.resolve("README.md"), "one\nMAIN MOVED ON\nthree\n");
        runGit(repo, "add", "README.md");
        commit(repo, "main moves on past the fork");
        runGit(repo, "checkout", "feat/change");

        // The fixture must actually separate the two: the test is vacuous if
        // main's tip equals the merge base's content.
        String mainTip = runGitOutput(repo, "show", "--end-of-options", "main:README.md");
        String mergeBaseContent = runGitOutput(repo, "show", "--end-of-options", mergeBase + ":README.md");
        assertNotEquals(mergeBaseContent, mainTip, "fixture: main's tip must differ from the fork");

        for (ReviewScope.Kind kind : new ReviewScope.Kind[] {
                ReviewScope.Kind.WORKTREE, ReviewScope.Kind.BRANCH}) {
            ReviewScope scope = scope(kind, repo, "main", "feat/change");
            UnifiedDiff.FileDiff readme = fileByPath(
                    diffs.diff(scope.diffRoot(), scope.diffScope(), scope.base()).get(), "README.md");

            assertEquals(mergeBaseContent, contents.leftText(scope, readme),
                    kind + ": left text is the merge base's content");
        }
    }

    /** AC14, Q6: a renamed file's left text is read at its pre-image path. */
    @Test
    void aRenamedFilesLeftTextIsReadAtItsPreImagePath(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir, "one\ntwo\n");
        runGit(repo, "checkout", "-b", "feat/rename");
        runGit(repo, "mv", "README.md", "RENAMED.md");
        Files.writeString(repo.resolve("RENAMED.md"), "one\ntwo\nthree\n");
        runGit(repo, "add", "-A");
        commit(repo, "rename and edit");

        for (ReviewScope.Kind kind : new ReviewScope.Kind[] {
                ReviewScope.Kind.WORKTREE, ReviewScope.Kind.BRANCH}) {
            ReviewScope scope = scope(kind, repo, "main", "feat/rename");
            UnifiedDiff diff = diffs.diff(scope.diffRoot(), scope.diffScope(), scope.base()).get();
            UnifiedDiff.FileDiff renamed = fileByPath(diff, "RENAMED.md");

            assertEquals("R", renamed.kind(), kind + ": fixture carries a rename");
            assertEquals(Optional.of("README.md"), renamed.oldPath(), kind + ": with a pre-image path");
            assertEquals("one\ntwo\n", contents.leftText(scope, renamed),
                    kind + ": left text is the pre-image path's content at the merge base");
        }
    }

    /**
     * A working-tree scope's sides: the left is HEAD ({@code git diff HEAD}
     * measures from there) and the right is the worktree file, so an
     * uncommitted edit shows up on the right only.
     */
    @Test
    void workingTreeScopeReadsHeadOnTheLeftAndTheWorktreeOnTheRight(
            @TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir, "one\ntwo\nthree\n");
        Files.writeString(repo.resolve("README.md"), "one\nEDITED\nthree\n");
        ReviewScope scope = scope(ReviewScope.Kind.WORKING_TREE, repo, "main", "(no branch)");
        UnifiedDiff.FileDiff readme = fileByPath(
                diffs.diff(repo, DiffScope.WORKING_TREE, "main").get(), "README.md");

        assertEquals("one\ntwo\nthree\n", contents.leftText(scope, readme),
                "left is HEAD's content");
        assertEquals("one\nEDITED\nthree\n", contents.rightText(scope, readme),
                "right is the worktree file's content");
    }

    /**
     * A base scope ends at HEAD, so its right side is HEAD's content —
     * not the worktree's (the branch is checked out here, but a scope whose
     * diff ends at HEAD must not leak uncommitted edits into it).
     */
    @Test
    void baseScopeReadsHeadOnTheRight(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir, "one\ntwo\nthree\n");
        runGit(repo, "checkout", "-b", "feat/change");
        Files.writeString(repo.resolve("README.md"), "one\nCHANGED\nthree\n");
        runGit(repo, "add", "README.md");
        commit(repo, "change on branch");
        Files.writeString(repo.resolve("README.md"), "one\nUNCOMMITTED\nthree\n");
        ReviewScope scope = scope(ReviewScope.Kind.BRANCH, repo, "main", "feat/change");
        UnifiedDiff.FileDiff readme = fileByPath(
                diffs.diff(repo, DiffScope.BASE, "main").get(), "README.md");

        assertEquals("one\nCHANGED\nthree\n", contents.rightText(scope, readme),
                "right is HEAD's content, not the worktree's");
        assertEquals("one\ntwo\nthree\n", contents.leftText(scope, readme),
                "left is the merge base's content");
    }

    /**
     * Spike defect 3, fixed: an added file has no left side (empty, no
     * object to read) and a deleted file no right side (empty, no worktree
     * file to read) — both remain review material. The deleted file's left
     * side is its full pre-delete content, and the added file's right side
     * is the content the diff added.
     */
    @Test
    void anAddedFileHasNoLeftSideAndADeletedFileNoRightSide(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir, "one\n");
        Files.writeString(repo.resolve("gone.txt"), "delete me\n");
        runGit(repo, "add", "gone.txt");
        commit(repo, "add a file to delete");
        runGit(repo, "checkout", "-b", "feat/mixed");
        runGit(repo, "rm", "gone.txt");
        Files.writeString(repo.resolve("new.txt"), "brand new\n");
        runGit(repo, "add", "new.txt");
        commit(repo, "add one, delete one");
        ReviewScope scope = scope(ReviewScope.Kind.BRANCH, repo, "main", "feat/mixed");
        UnifiedDiff diff = diffs.diff(repo, DiffScope.BASE, "main").get();

        UnifiedDiff.FileDiff added = fileByPath(diff, "new.txt");
        assertEquals("A", added.kind());
        assertEquals("", contents.leftText(scope, added), "an added file has no left side");
        assertEquals("brand new\n", contents.rightText(scope, added),
                "the added file's right side is HEAD's content");

        UnifiedDiff.FileDiff deleted = fileByPath(diff, "gone.txt");
        assertEquals("D", deleted.kind());
        assertEquals("", contents.rightText(scope, deleted), "a deleted file has no right side");
        assertEquals("delete me\n", contents.leftText(scope, deleted),
                "a deleted file's left side is its pre-delete content");
    }

    /**
     * AC15's detection half: the hunk rows' highest line number on the
     * checked side must not exceed the text's line count. Pure — the
     * {@code FileDiff}s are parsed from a literal diff, no git involved.
     */
    @Test
    void matchesRowsDetectsATextThatCannotCarryTheHunkRows() {
        UnifiedDiff diff = DiffService.parseUnified("""
                diff --git a/Sample.java b/Sample.java
                index 1111111..2222222 100644
                --- a/Sample.java
                +++ b/Sample.java
                @@ -1,3 +1,3 @@ class Sample {
                 context1
                -old
                +new
                @@ -10,2 +10,3 @@ void method() {
                 context2
                +appended
                 context3
                diff --git a/Fresh.java b/Fresh.java
                new file mode 100644
                index 0000000..3333333
                --- /dev/null
                +++ b/Fresh.java
                @@ -0,0 +1,2 @@
                +line one
                +line two
                """);
        UnifiedDiff.FileDiff sample = fileByPath(diff, "Sample.java");
        UnifiedDiff.FileDiff fresh = fileByPath(diff, "Fresh.java");

        // Sample's rows reach old line 11 and new line 12 (context3 at the
        // end of hunk 2 carries both; git numbers are 1-based, so a text
        // with exactly those rows at those lines matches).
        String left = sampleLeft();
        String right = sampleRight();
        assertTrue(ReviewContents.matchesRows(left, sample, false), "left: the rows sit at their old lines");
        assertTrue(ReviewContents.matchesRows(right, sample, true), "right: the rows sit at their new lines");
        assertFalse(ReviewContents.matchesRows(firstLines(left, 10), sample, false),
                "left: a 10-line text cannot carry an old row at 11");
        assertFalse(ReviewContents.matchesRows(firstLines(right, 11), sample, true),
                "right: an 11-line text cannot carry a new row at 12");
        assertFalse(ReviewContents.matchesRows("", sample, true),
                "an empty text cannot carry any row");
        // Fresh has rows only on the new side: the left side has nothing to
        // contradict, and one line cannot carry its two added rows.
        assertTrue(ReviewContents.matchesRows("", fresh, false),
                "left: an added file's left side has no rows to contradict");
        assertFalse(ReviewContents.matchesRows("only line\n", fresh, true),
                "right: two added rows do not fit one line");
        assertTrue(ReviewContents.matchesRows("line one\nline two\n", fresh, true),
                "right: exactly the two added rows fit");
        assertFalse(ReviewContents.matchesRows("line one\nline 2\n", fresh, true),
                "right: two lines, but not the two added rows");
    }

    /**
     * AC15's detection compares content, not just the line count: an edit
     * after the diff that grows the file (lines inserted above a hunk) or
     * keeps its length (a row's line rewritten) must read as changed; a
     * CRLF copy of the same content must not.
     */
    @Test
    void matchesRowsComparesTheRowTextNotOnlyTheLineCount() {
        UnifiedDiff.FileDiff sample = fileByPath(DiffService.parseUnified(SAMPLE_DIFF), "Sample.java");
        String right = sampleRight();

        assertFalse(ReviewContents.matchesRows("inserted above\n" + right, sample, true),
                "a line inserted above the hunks shifts every row: changed since the diff");
        assertFalse(ReviewContents.matchesRows(right.replace("\nnew\n", "\nrewritten\n"), sample, true),
                "same line count, different row content: changed since the diff");
        assertTrue(ReviewContents.matchesRows(right.replace("\n", "\r\n"), sample, true),
                "CRLF is a line-separator difference, not a content one");
    }

    /** The one normalization both sides go through: CRLF and lone CR become LF. */
    @Test
    void lineSeparatorsAreNormalizedToLf() {
        assertEquals("a\nb\nc\n", ReviewContents.normalizeLineSeparators("a\r\nb\rc\n"));
        assertEquals("plain\n", ReviewContents.normalizeLineSeparators("plain\n"));
        assertEquals("", ReviewContents.normalizeLineSeparators(null));
    }

    /**
     * A CRLF file's two sides come back LF-normalized, so the row check, the
     * fragment offsets and the preview documents all address one text:
     * every fragment offset lies inside that text, on a line boundary, and
     * no CR reaches the request plan (EditorFactory.createDocument would
     * throw on one).
     */
    @Test
    void aCrlfFilesSidesAreNormalizedAndItsFragmentsPointIntoTheNormalizedText(
            @TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir, "one\r\ntwo\r\nthree\r\n");
        Files.writeString(repo.resolve("README.md"), "one\r\nTWO\r\nthree\r\nfour\r\n");
        ReviewScope scope = scope(ReviewScope.Kind.WORKING_TREE, repo, "main", "main");
        UnifiedDiff.FileDiff readme = fileByPath(
                diffs.diff(scope.diffRoot(), scope.diffScope(), scope.base()).get(), "README.md");

        String left = contents.leftText(scope, readme);
        String right = contents.rightText(scope, readme);
        assertEquals("one\ntwo\nthree\n", left);
        assertEquals("one\nTWO\nthree\nfour\n", right);
        assertTrue(ReviewContents.matchesRows(left, readme, false));
        assertTrue(ReviewContents.matchesRows(right, readme, true));

        List<LineFragment> fragments =
                ReviewFragments.fragmentsFor(readme, left, right);
        assertFalse(fragments.isEmpty());
        for (LineFragment fragment : fragments) {
            assertTrue(fragment.getEndOffset1() <= left.length() && fragment.getEndOffset2() <= right.length(),
                    "fragment offsets lie inside the normalized texts");
            assertTrue(fragment.getStartOffset2() == 0 || right.charAt(fragment.getStartOffset2() - 1) == '\n',
                    "a fragment starts on a line boundary of the normalized text");
        }
        // The plan accepts the normalized texts (it refuses a CR outright).
        ReviewDiffRequests.plan(readme, left, right, fragments);
    }

    /**
     * Commit resolution yields resolved commit ids — never ref names, never
     * a display label — or empty when the ref cannot be resolved here; the
     * {@code "(no branch)"} head of a checkout with no branch resolves to
     * HEAD.
     */
    @Test
    void commitResolutionGivesCommitIdsNeverRefNames(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir, "one\n");
        runGit(repo, "checkout", "-b", "feat/change");
        Files.writeString(repo.resolve("README.md"), "one\nCHANGED\n");
        runGit(repo, "add", "README.md");
        commit(repo, "change on branch");
        ReviewScope scope = scope(ReviewScope.Kind.WORKTREE, repo, "main", "feat/change");

        assertEquals(Optional.of(shaOf(repo, "main")), contents.baseCommit(scope),
                "base resolves to main's commit id, not the name 'main'");
        assertEquals(Optional.of(shaOf(repo, "HEAD")), contents.headCommit(scope),
                "head resolves to HEAD's commit id, not the branch name");
        assertNotEquals(contents.baseCommit(scope), contents.headCommit(scope));
        assertTrue(contents.headCommit(scope).map(sha -> sha.matches("[0-9a-f]{40}")).orElse(false),
                "a resolved commit id is 40 hex digits");

        ReviewScope noBranch = scope(ReviewScope.Kind.WORKING_TREE, repo, "main", "(no branch)");
        assertEquals(Optional.of(shaOf(repo, "HEAD")), contents.headCommit(noBranch),
                "a '(no branch)' head resolves to HEAD");

        ReviewScope unresolvable = scope(ReviewScope.Kind.WORKTREE, repo, "no-such-branch", "feat/change");
        assertEquals(Optional.empty(), contents.baseCommit(unresolvable),
                "an unresolvable base is empty, never the ref name");
    }

    // ---- fixtures ----

    /** A text of {@code n} lines, each its 1-based number. */
    /** The Sample.java diff of the row-check tests. */
    private static final String SAMPLE_DIFF = """
            diff --git a/Sample.java b/Sample.java
            index 1111111..2222222 100644
            --- a/Sample.java
            +++ b/Sample.java
            @@ -1,3 +1,3 @@ class Sample {
             context1
            -old
            +new
            @@ -10,2 +10,3 @@ void method() {
             context2
            +appended
             context3
            """;

    /** Sample.java's pre-image: its old rows at old lines 1, 2, 10 and 11, filler elsewhere. */
    private static String sampleLeft() {
        return withRows(11, Map.of(1, "context1", 2, "old", 10, "context2", 11, "context3"));
    }

    /** Sample.java's post-image: its new rows at new lines 1, 2, 10, 11 and 12, filler elsewhere. */
    private static String sampleRight() {
        return withRows(12, Map.of(1, "context1", 2, "new", 10, "context2", 11, "appended", 12, "context3"));
    }

    private static String withRows(int n, Map<Integer, String> rows) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            text.append(rows.getOrDefault(i, "filler " + i)).append('\n');
        }
        return text.toString();
    }

    private static String firstLines(String text, int n) {
        StringBuilder out = new StringBuilder();
        String[] lines = text.split("\n");
        for (int i = 0; i < n; i++) {
            out.append(lines[i]).append('\n');
        }
        return out.toString();
    }

    private static UnifiedDiff.FileDiff fileByPath(UnifiedDiff diff, String path) {
        return diff.files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
    }

    /**
     * A local review scope over {@code repo}: a worktree scope for {@code
     * WORKTREE}, whose diff root is the checkout itself, a working-tree
     * scope with no worktree, and a branch scope for {@code BRANCH}. The
     * base and head are the raw refs/labels the real registry would carry.
     */
    private static ReviewScope scope(ReviewScope.Kind kind, Path repo, String base, String head) {
        return new ReviewScope("test-" + kind, kind, repo,
                kind == ReviewScope.Kind.WORKING_TREE ? Optional.empty() : Optional.of(repo),
                base, head, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static String shaOf(Path repo, String ref) throws IOException, InterruptedException {
        return runGitOutput(repo, "rev-parse", "--verify", "--end-of-options", ref + "^{commit}").strip();
    }

    private static Path initCommittedRepo(Path parent, String readmeContent)
            throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        Files.writeString(repo.resolve("README.md"), readmeContent);
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");
        return repo;
    }

    private static void commit(Path repo, String message) throws IOException, InterruptedException {
        runGit(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", message);
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        runGitOutput(repo, args);
    }

    private static String runGitOutput(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (exit " + exitCode + "): " + output);
        }
        return output;
    }
}
