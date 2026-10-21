package app.drydock.intellij.agent;

import app.drydock.intellij.agent.FileLinkTarget.Target;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which paths in an agent's console become links into its worktree (frame F2). */
class FileLinkTargetTest {

    private static final Path WORKTREE = Path.of("/w/b");
    private static final Predicate<Path> EXISTS = Set.of(Path.of("/w/b/core/A.java"), Path.of("/w/b/README.md"),
            Path.of("/w/other/B.java"))::contains;

    @Test
    void aRelativePathWithALineResolvesUnderTheWorktree() {
        assertEquals(Optional.of(new Target(Path.of("/w/b/core/A.java"), 212)),
                FileLinkTarget.resolve("core/A.java:212", WORKTREE, EXISTS));
    }

    @Test
    void anAbsolutePathWithoutALineResolvesToItsFirstLine() {
        assertEquals(Optional.of(new Target(Path.of("/w/b/README.md"), 1)),
                FileLinkTarget.resolve("/w/b/README.md", WORKTREE, EXISTS));
    }

    @Test
    void aPathOutsideTheWorktreeIsNotALink() {
        assertEquals(Optional.empty(), FileLinkTarget.resolve("/w/other/B.java", WORKTREE, EXISTS));
        assertEquals(Optional.empty(), FileLinkTarget.resolve("../other/B.java", WORKTREE, EXISTS));
    }

    @Test
    void aMissingFileIsNotALink() {
        assertEquals(Optional.empty(), FileLinkTarget.resolve("core/Gone.java:3", WORKTREE, EXISTS));
    }

    @Test
    void tokensAreFoundWhereTheyAreInTheLine() {
        assertEquals(List.of(new FileLinkTarget.Token(17, 32, "core/A.java:212")),
                FileLinkTarget.tokens("Reading chunk in core/A.java:212 now"));
    }
}
