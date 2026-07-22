package app.drydock.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
