package app.drydock.intellij.agent;

import app.drydock.git.UnifiedDiff;

import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.vcsUtil.VcsUtil;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TaskChangesTest extends BasePlatformTestCase {

    public void testIntellijChangesAreIntersectedWithGitsNonIgnoredDiff() {
        Path checkout = Path.of(getProject().getBasePath()).toAbsolutePath().normalize();
        Change tracked = change(checkout.resolve("src/Tracked.java"));
        Change ignored = change(checkout.resolve("build/generated.txt"));
        UnifiedDiff git = new UnifiedDiff(List.of(new UnifiedDiff.FileDiff(
                "src/Tracked.java", "M", 1, 0, false, false, List.of())));

        List<Change> shown = TaskChanges.withDiscoveredChanges(
                getProject(), checkout, List.of(tracked, ignored), git, "HEAD");

        assertEquals(List.of(tracked), shown);
    }

    private static Change change(Path path) {
        return new Change(null, CurrentContentRevision.create(VcsUtil.getFilePath(path, false)));
    }
}
