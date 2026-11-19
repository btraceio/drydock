package app.drydock.git;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorktreeNamingTest {

    @Test
    void slugDropsTheBranchPrefixAndNormalizes() {
        assertEquals("sidebar-resize", WorktreeNaming.slug("feat/sidebar-resize"));
        assertEquals("rate-limit", WorktreeNaming.slug("fix/rate-limit"));
        assertEquals("auth-mw-v2", WorktreeNaming.slug("feat/Auth MW v2"));
        assertEquals("cleanup", WorktreeNaming.slug("cleanup"));
    }

    @Test
    void slugFallsBackForEmptyOrBareBranches() {
        assertEquals("worktree", WorktreeNaming.slug("feat/"));
        assertEquals("worktree", WorktreeNaming.slug("  "));
        assertEquals("worktree", WorktreeNaming.slug(null));
        assertEquals("worktree", WorktreeNaming.slug("///"));
    }

    @Test
    void defaultDirectoryIsHomeDevWtRepoSlug() {
        Path home = Path.of("/Users/dev");
        assertEquals(Path.of("/Users/dev/dev/wt/drydock-sidebar-resize"),
                WorktreeNaming.defaultDirectory(home, "drydock", "feat/sidebar-resize"));
        assertEquals(Path.of("/Users/dev/dev/wt/my-repo-x"),
                WorktreeNaming.defaultDirectory(home, "My Repo", "x"));
    }

    @Test
    void defaultDirectoryUsesTheConfiguredWorktreesDirectoryWhenPresent() {
        Path home = Path.of("/Users/dev");
        Path configured = Path.of("/Volumes/external/worktrees");
        assertEquals(Path.of("/Volumes/external/worktrees/drydock-sidebar-resize"),
                WorktreeNaming.defaultDirectory(home, Optional.of(configured), "drydock", "feat/sidebar-resize"));
    }

    @Test
    void defaultDirectoryFallsBackToHomeDevWtWhenNotConfigured() {
        Path home = Path.of("/Users/dev");
        assertEquals(Path.of("/Users/dev/dev/wt/drydock-sidebar-resize"),
                WorktreeNaming.defaultDirectory(home, Optional.empty(), "drydock", "feat/sidebar-resize"));
    }

    @Test
    void babysitterDirectoryIsDoubleDashedSoNoBranchSlugCanReachIt() {
        Path base = Path.of("/wt");
        assertEquals(Path.of("/wt/drydock--babysit-pr42"),
                WorktreeNaming.babysitterDirectory(base, "drydock", 42));
        // A watched branch spelled exactly like the suffix still lands on a
        // single-dash directory: the default naming can never emit "--".
        for (String branch : List.of("babysit-pr42", "-babysit-pr42", "x/--babysit-pr42", "Babysit PR42")) {
            Path watched = WorktreeNaming.defaultDirectory(Path.of("/home"), Optional.of(base), "drydock", branch);
            assertNotEquals(WorktreeNaming.babysitterDirectory(base, "drydock", 42), watched, branch);
            assertFalse(watched.getFileName().toString().contains("--"), watched.toString());
        }
        assertEquals(Path.of("/wt/repo--babysit-pr7"), WorktreeNaming.babysitterDirectory(base, " ", 7));
        assertThrows(IllegalArgumentException.class, () -> WorktreeNaming.babysitterDirectory(base, "r", 0));
    }
}
