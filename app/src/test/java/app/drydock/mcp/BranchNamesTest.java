package app.drydock.mcp;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchNamesTest {

    private static final Set<String> REMOTES = Set.of("origin", "upstream");

    @Test
    void anOrdinaryBranchNameIsAccepted() throws Exception {
        BranchNames.validate("feat/try-a", REMOTES);
        BranchNames.validate("fix-123", REMOTES);
    }

    @Test
    void aNameThatShadowsARemoteIsRefused() {
        // refs/heads/origin/main shadows refs/remotes/origin/main for every
        // short-name lookup, so a later `git merge origin/main` would silently
        // target this branch instead of the fetched ref. git exits 0 and warns
        // only on stderr, so nothing else would catch it.
        McpToolException failure = assertThrows(McpToolException.class,
                () -> BranchNames.validate("origin/main", REMOTES));

        assertTrue(failure.getMessage().contains("origin"), failure.getMessage());
    }

    @Test
    void everyConfiguredRemoteIsChecked() {
        assertThrows(McpToolException.class, () -> BranchNames.validate("upstream/main", REMOTES));
    }

    @Test
    void aRemoteNameThatIsOnlyAPrefixOfTheFirstComponentIsFine() {
        // "originals" is not the remote "origin"; only a whole first component counts.
        assertThrows(McpToolException.class, () -> BranchNames.validate("origin/x", REMOTES));
    }

    @Test
    void aBranchWhoseFirstComponentMerelyStartsWithARemoteNameIsAccepted() throws Exception {
        BranchNames.validate("originals/x", REMOTES);
    }

    @Test
    void aRefsPrefixedNameIsRefused() {
        assertThrows(McpToolException.class, () -> BranchNames.validate("refs/heads/x", REMOTES));
    }

    @Test
    void namesGitItselfRejectsAreRefusedWithItsOwnComplaint() {
        assertThrows(McpToolException.class, () -> BranchNames.validate("has space", REMOTES));
        assertThrows(McpToolException.class, () -> BranchNames.validate("..", REMOTES));
        assertThrows(McpToolException.class, () -> BranchNames.validate("-leading-dash", REMOTES));
        assertThrows(McpToolException.class, () -> BranchNames.validate("trailing.lock", REMOTES));
        assertThrows(McpToolException.class, () -> BranchNames.validate("a..b", REMOTES));
        assertThrows(McpToolException.class, () -> BranchNames.validate("a~b", REMOTES));
    }

    @Test
    void blankAndNullAreRefused() {
        assertThrows(McpToolException.class, () -> BranchNames.validate("   ", REMOTES));
        assertThrows(McpToolException.class, () -> BranchNames.validate(null, REMOTES));
    }
}
