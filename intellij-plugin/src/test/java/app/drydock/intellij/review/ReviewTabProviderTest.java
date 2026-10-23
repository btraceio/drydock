package app.drydock.intellij.review;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file-change trigger's filter (spec "Refresh"): a VFS change re-runs
 * the diff only when it lies in the tab's checkout and outside git's own
 * directory — a diff that touches the index must not re-trigger itself.
 *
 * <p>Also the AC15 mismatch memo (P2-A): a file that can never match git's
 * diff text — a symlink, a filter/lfs/working-tree-encoding file, a
 * lone-CR file — fails the content check on every selection, and the
 * reload its mismatch scheduled rebuilt the tree into the same mismatch
 * again, re-running the diff about every 300 ms while the file stayed
 * selected. The memo pins the policy: exactly one automatic reload per
 * identical failure, reset by a genuine change signal.</p>
 */
class ReviewTabProviderTest {

    private static final Path ROOT = Path.of("/repo").toAbsolutePath();

    @Test
    void aChangeInTheCheckoutTriggersAReload() {
        assertTrue(ReviewTabProvider.underCheckout(ROOT, ROOT.resolve("src/A.java").toString()));
        assertTrue(ReviewTabProvider.underCheckout(ROOT, ROOT.toString()));
    }

    @Test
    void gitsOwnDirectoryAndOtherCheckoutsDoNot() {
        assertFalse(ReviewTabProvider.underCheckout(ROOT, ROOT.resolve(".git/index").toString()),
                "the index the diff itself touches");
        assertFalse(ReviewTabProvider.underCheckout(ROOT, ROOT.resolve("sub/.git/HEAD").toString()));
        assertFalse(ReviewTabProvider.underCheckout(ROOT, ROOT.resolveSibling("repo-other/A.java").toString()),
                "a sibling directory sharing the name prefix");
        assertFalse(ReviewTabProvider.underCheckout(ROOT, ""));
        assertFalse(ReviewTabProvider.underCheckout(ROOT, null));
    }

    @Test
    void twoIdenticalMismatchesScheduleExactlyOneAutomaticReload() {
        ReviewTabProvider.MismatchMemo memo = new ReviewTabProvider.MismatchMemo();

        assertTrue(memo.schedulesReload("src/link", "target\n", "working tree\n"),
                "the first mismatch schedules the reload");
        assertFalse(memo.schedulesReload("src/link", "target\n", "working tree\n"),
                "the same file with the same contents on both sides: the memoized failure, no second reload");
    }

    @Test
    void aDifferentFileOrARealContentChangeSchedulesAnotherReload() {
        ReviewTabProvider.MismatchMemo memo = new ReviewTabProvider.MismatchMemo();
        assertTrue(memo.schedulesReload("src/link", "target\n", "tree\n"));

        assertTrue(memo.schedulesReload("src/other", "target\n", "tree\n"),
                "a different file is not the memoized failure");
        assertTrue(memo.schedulesReload("src/link", "changed\n", "tree\n"),
                "the left side changed: a real change, not the memoized failure");
        assertTrue(memo.schedulesReload("src/link", "changed\n", "changed too\n"),
                "the right side changed: a real change, not the memoized failure");
    }

    @Test
    void aChangeSignalResetsTheMemo() {
        ReviewTabProvider.MismatchMemo memo = new ReviewTabProvider.MismatchMemo();
        assertTrue(memo.schedulesReload("src/link", "target\n", "tree\n"));

        // What a file-change event, a turn end, a manual Refresh, and a
        // fresh matching selection all clear the memo with.
        memo.reset();

        assertTrue(memo.schedulesReload("src/link", "target\n", "tree\n"),
                "after a genuine change signal the same mismatch again schedules a reload");
    }
}
