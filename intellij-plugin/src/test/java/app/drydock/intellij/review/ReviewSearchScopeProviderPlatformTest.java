package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScopeProvider;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * "Drydock: files in this review" in a booted IDE: registered, absent
 * without a live review, and over exactly the review's surviving files.
 */
public class ReviewSearchScopeProviderPlatformTest extends BasePlatformTestCase {

    public void testTheProviderIsRegistered() {
        assertTrue(SearchScopeProvider.EP_NAME.getExtensionList().stream()
                .anyMatch(ReviewSearchScopeProvider.class::isInstance));
    }

    public void testNoLiveReviewOffersNoScope() {
        assertEquals(List.of(), new ReviewSearchScopeProvider().getSearchScopes(getProject(),
                SimpleDataContext.getProjectContext(getProject())));
    }

    public void testTheScopeHoldsExactlyTheReviewsSurvivingFiles() throws IOException {
        Path root = Files.createTempDirectory("review-scope");
        Files.writeString(root.resolve("Changed.java"), "class Changed {}\n");
        Files.writeString(root.resolve("Untouched.java"), "class Untouched {}\n");
        VirtualFile changed = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("Changed.java"));
        VirtualFile untouched = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("Untouched.java"));
        ReviewTabModel.Snapshot snapshot = ReviewTabModel.snapshot(new ReviewTabModel.Inputs(
                new UnifiedDiff(List.of(file("Changed.java", "M"), file("Gone.java", "D"))),
                List.of(), List.of(), Optional.empty()));

        assertEquals(List.of(root.resolve("Changed.java")), ReviewSearchScopeProvider.paths(root, snapshot));
        GlobalSearchScope scope = ReviewSearchScopeProvider.scopeOf(getProject(),
                new ReviewTabProvider.ReviewFiles(root, snapshot));
        assertEquals(ReviewSearchScopeProvider.SCOPE_NAME, scope.getDisplayName());
        assertTrue(scope.contains(changed));
        assertFalse(scope.contains(untouched));
    }

    private static UnifiedDiff.FileDiff file(String path, String kind) {
        UnifiedDiff.Line added = new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                OptionalInt.of(1), "x");
        return new UnifiedDiff.FileDiff(path, kind, 1, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -0,0 +1,1 @@", List.of(added))));
    }
}
