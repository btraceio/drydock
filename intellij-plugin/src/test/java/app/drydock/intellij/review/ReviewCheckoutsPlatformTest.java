package app.drydock.intellij.review;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;
import java.util.List;

/** The checkouts opened for review with no agent: registered, matched lexically, forgotten. */
public class ReviewCheckoutsPlatformTest extends BasePlatformTestCase {

    private static final Path CHECKOUT = Path.of("/worktrees/repo-pr-40");

    @Override
    protected void tearDown() throws Exception {
        try {
            ReviewCheckouts.forget(CHECKOUT);
        } finally {
            super.tearDown();
        }
    }

    public void testARegisteredCheckoutIsCoveredUntilItIsForgotten() {
        assertFalse(ReviewCheckouts.covers(CHECKOUT));

        ReviewCheckouts.register(Path.of("/worktrees/x/../repo-pr-40"));
        assertTrue("matched by the normalized path", ReviewCheckouts.covers(CHECKOUT));
        assertFalse(ReviewCheckouts.covers(Path.of("/worktrees/repo-pr-41")));

        ReviewCheckouts.forget(CHECKOUT);
        assertFalse(ReviewCheckouts.covers(CHECKOUT));
    }

    public void testRegisteringTwiceKeepsOneEntry() {
        assertEquals(List.of("/a", "/b"), ReviewCheckouts.with(List.of("/a", "/b"), "/a"));
        assertEquals(List.of("/b"), ReviewCheckouts.without(List.of("/a", "/b"), "/a"));
    }
}
