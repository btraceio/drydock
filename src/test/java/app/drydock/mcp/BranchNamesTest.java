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
    void theRemoteCheckIsCaseInsensitiveBecauseRefFilesAreOnMacOs() {
        // .git/refs/heads/<name> is a plain file open() on the macOS default
        // case-insensitive filesystem, so "Origin/main" would land in the same
        // place as "origin/main" and shadow refs/remotes/origin/main just the
        // same. Verified against real git 2.49 in a throwaway repo: `git
        // branch Origin/main HEAD` exits 0, and `origin/main` then resolves to
        // the agent-chosen commit instead of the fetched upstream one.
        assertThrows(McpToolException.class, () -> BranchNames.validate("Origin/main", REMOTES));
        assertThrows(McpToolException.class, () -> BranchNames.validate("Upstream/main", REMOTES));
    }

    @Test
    void aRemoteNameContainingASlashIsMatchedAsAWholeComponentNotJustTheFirstSegment() {
        // git permits remote.foo/bar.url, so a remote's own name can contain a
        // slash; the check must compare against the whole remote name, not
        // just branch.split("/")[0].
        Set<String> remotes = Set.of("foo/bar");
        assertThrows(McpToolException.class, () -> BranchNames.validate("foo/bar/main", remotes));
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
    void aLeadingSlashOrEmptyPathComponentIsRejected() {
        assertThrows(McpToolException.class, () -> BranchNames.validate("/leading", REMOTES));
        assertThrows(McpToolException.class, () -> BranchNames.validate("a//b", REMOTES));
    }

    @Test
    void namesGitItselfRejectsAreRefusedWithItsOwnComplaint() {
        assertThrows(McpToolException.class, () -> BranchNames.validate("has space", REMOTES));
        assertThrows(McpToolException.class, () -> BranchNames.validate("..", REMOTES));
        assertThrows(McpToolException.class, () -> BranchNames.validate("-leading-dash", REMOTES));
        assertThrows(McpToolException.class, () -> BranchNames.validate("trailing.lock", REMOTES));
        assertThrows(McpToolException.class, () -> BranchNames.validate("a..b", REMOTES));
        assertThrows(McpToolException.class, () -> BranchNames.validate("a~b", REMOTES));
        assertThrows(McpToolException.class, () -> BranchNames.validate("a@{b", REMOTES));
        assertThrows(McpToolException.class, () -> BranchNames.validate("trailing-slash/", REMOTES));
        assertThrows(McpToolException.class, () -> BranchNames.validate("has\\backslash", REMOTES));
        assertThrows(McpToolException.class, () -> BranchNames.validate("has\u0007control", REMOTES));
    }

    @Test
    void blankAndNullAreRefused() {
        // "" is blank without containing any of the forbidden characters, so
        // this pins the isBlank() check itself rather than the space-is-a-
        // forbidden-character rule (a plain "   " would still be caught by
        // that rule even if the blank check were deleted).
        assertThrows(McpToolException.class, () -> BranchNames.validate("", REMOTES));
        assertThrows(McpToolException.class, () -> BranchNames.validate("   ", REMOTES));
        assertThrows(McpToolException.class, () -> BranchNames.validate(null, REMOTES));
    }

    @Test
    void aNullRemoteNamesSetIsRefusedRatherThanThrowingANullPointerException() {
        assertThrows(McpToolException.class, () -> BranchNames.validate("feat/x", null));
    }
}
