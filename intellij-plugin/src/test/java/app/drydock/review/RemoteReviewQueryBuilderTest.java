package app.drydock.review;

import app.drydock.domain.SshRemote;
import app.drydock.git.DiffScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteReviewQueryBuilderTest {
    private static final String HEAD = "a".repeat(40);
    private static final String MB = "b".repeat(40);
    private final RemoteReviewQueryBuilder builder = new RemoteReviewQueryBuilder(new SshRemote("h", "/srv/repo"));

    /** The one remote command string (last ssh operand), after the fixed {@code git -C '/srv/repo' } prefix. */
    private static String remote(List<String> argv) {
        assertEquals("ssh", argv.get(0));
        assertEquals("--", argv.get(argv.size() - 3));
        assertEquals("h", argv.get(argv.size() - 2));
        String command = argv.get(argv.size() - 1);
        String prefix = "git -C '/srv/repo' ";
        assertTrue(command.startsWith(prefix), command);
        return command.substring(prefix.length());
    }

    @Test
    void diffArgvPerScopeWithRightSidePinnedToHead() {
        String common = "'diff' '--no-color' '--no-ext-diff' '-U3' '--end-of-options' ";
        assertEquals(common + "'" + HEAD + "'",
                remote(builder.diffCommand(DiffScope.WORKING_TREE, "main", 3, HEAD, Optional.empty())));
        assertEquals(common + "'origin/main..." + HEAD + "'",
                remote(builder.diffCommand(DiffScope.BASE, "origin/main", 3, HEAD, Optional.empty())));
        assertEquals(common + "'@{upstream}..." + HEAD + "'",
                remote(builder.diffCommand(DiffScope.UPSTREAM, "main", 3, HEAD, Optional.empty())));
        assertEquals(common + "'" + MB + "'",
                remote(builder.diffCommand(DiffScope.BRANCH_WORKING_TREE, "main", 3, HEAD, Optional.of(MB))));
        assertThrows(IllegalArgumentException.class,
                () -> builder.diffCommand(DiffScope.BRANCH_WORKING_TREE, "main", 3, HEAD, Optional.empty()));
    }

    @Test
    void hostileBranchStaysAnOperandAfterEndOfOptions() {
        String hostile = "--output=/tmp/x";
        String diff = remote(builder.diffCommand(DiffScope.BASE, hostile, 3, HEAD, Optional.empty()));
        assertTrue(diff.contains("'--end-of-options' '" + hostile + "..." + HEAD + "'"), diff);
        assertEquals("'merge-base' '--end-of-options' '" + hostile + "' '" + HEAD + "'",
                remote(builder.mergeBaseCommand(hostile, HEAD)));
        assertEquals("'rev-parse' '--verify' '--end-of-options' '" + hostile + "^{commit}'",
                remote(builder.resolveCommand(hostile)));
        assertEquals("'rev-list' '--count' '--end-of-options' '" + hostile + ".." + HEAD + "'",
                remote(builder.commitsAheadCommand(hostile, HEAD)));
    }

    @Test
    void hostilePathIsOneQuotedOperandAndLeftSideUsesRenameOldPath() {
        String left = RemoteReviewQueryBuilder.leftPath("new.txt", Optional.of("-old; rm -rf x"));
        assertEquals("-old; rm -rf x", left);
        assertEquals("new.txt", RemoteReviewQueryBuilder.leftPath("new.txt", Optional.empty()));
        assertEquals("'cat-file' '--end-of-options' 'blob' '" + MB + ":-old; rm -rf x'",
                remote(builder.blobCommand(MB, left)));
        assertThrows(IllegalArgumentException.class, () -> builder.blobCommand("--foo", "x"));
        assertThrows(IllegalArgumentException.class, () -> builder.blobCommand(MB, "a\0b"));
        assertThrows(IllegalArgumentException.class,
                () -> builder.diffCommand(DiffScope.BASE, "main", 3, "HEAD", Optional.empty()));
    }

    @Test
    void workingTreeMetadataQueriesAndNoMutatingSubcommand() {
        assertEquals("'diff' '--cached' '--name-only' '-z'", remote(builder.stagedCommand()));
        assertEquals("'ls-files' '--others' '--exclude-standard' '-z'", remote(builder.untrackedCommand()));
        List<List<String>> all = List.of(builder.headCommand(), builder.resolveCommand("main"),
                builder.mergeBaseCommand("main", HEAD), builder.stagedCommand(), builder.untrackedCommand(),
                builder.diffCommand(DiffScope.BASE, "main", 3, HEAD, Optional.empty()),
                builder.commitsAheadCommand("main", HEAD), builder.blobCommand(HEAD, "p"));
        List<String> allowed = List.of("rev-parse", "merge-base", "diff", "ls-files", "rev-list", "cat-file");
        for (List<String> argv : all) {
            String subcommand = remote(argv).split(" ")[0].replace("'", "");
            assertTrue(allowed.contains(subcommand), subcommand);
            assertTrue(argv.stream().noneMatch(a -> a.contains("fetch") || a.contains("checkout")
                    || a.contains("reset") || a.contains(" sh ") || a.equals("-c")), argv.toString());
        }
    }
}
