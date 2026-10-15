package app.drydock.git;

import app.drydock.git.BranchNameRules.Clause;
import app.drydock.git.BranchNameRules.Refusal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The refname rules extracted from {@code BranchNames.validate}. The
 * evaluation-order cases are the load-bearing ones: {@code BranchNamesTest}
 * asserts only <em>that</em> a name is refused, never <em>which</em> clause
 * refused it, so a reordering during the extraction would leave that suite
 * green while silently changing the message the MCP surface returns.
 */
class BranchNameRulesTest {

    private static final List<String> REMOTES = List.of("origin", "upstream");

    @Test
    void anOrdinaryBranchNameHasNoRefusal() {
        assertEquals(Optional.empty(), BranchNameRules.check("feat/try-a", REMOTES));
    }

    @Test
    void aNameShadowingARemoteIsRefusedWithTheRemoteAsItsToken() {
        Refusal refusal = check("origin/main");

        assertEquals(Clause.SHADOWS_REMOTE, refusal.clause());
        assertEquals("origin", refusal.token());
    }

    @Test
    void theShadowClauseIsTestedBeforeEveryWholeNameClause() {
        // origin/a..b violates SHADOWS_REMOTE and DOT_DOT. Today the shadow
        // loop runs first, so this is the message the MCP surface returns.
        assertEquals(Clause.SHADOWS_REMOTE, check("origin/a..b").clause());
    }

    @Test
    void theRefsPrefixClauseIsTestedBeforeEveryWholeNameClause() {
        assertEquals(Clause.REFS_PREFIX, check("refs/heads/a..b").clause());
    }

    @Test
    void componentClausesAreJudgedComponentMajorNotClauseMajor() {
        // foo.lock/.bar violates COMPONENT_LOCK_SUFFIX in the first component
        // and COMPONENT_LEADING_DOT in the second. Today's loop finishes the
        // first component before looking at the second, so .lock wins. A flat
        // ladder asking "does any component start with '.'" would answer .bar.
        Refusal refusal = check("foo.lock/.bar");

        assertEquals(Clause.COMPONENT_LOCK_SUFFIX, refusal.clause());
        assertEquals("foo.lock", refusal.token());
    }

    @Test
    void theForbiddenCharacterIsTheLeftmostInTheNameNotTheFirstInTheCharacterSet() {
        // '~' precedes '^' in FORBIDDEN_CHARS, but the scan walks the name.
        assertEquals("^", check("a^b~c").token());
    }

    @Test
    void theLongestMatchingRemoteWins() {
        // git allows a remote whose name contains a slash, so both "origin"
        // and "origin/team" can match origin/team/x. Naming the shorter one
        // would be arbitrary; today's code picks whichever the Set iterator
        // happens to yield first, which is salted per JVM run.
        Refusal refusal = BranchNameRules.check("origin/team/x", List.of("origin", "origin/team"))
                .orElseThrow();

        assertEquals("origin/team", refusal.token());
    }

    @Test
    void theRemoteMatchIsCaseInsensitiveBecauseRefFilesAreOnMacOs() {
        assertEquals(Clause.SHADOWS_REMOTE, check("Origin/main").clause());
    }

    @Test
    void aBranchWhoseFirstComponentMerelyStartsWithARemoteNameIsAccepted() {
        assertEquals(Optional.empty(), BranchNameRules.check("originals/x", REMOTES));
    }

    @Test
    void everyClauseIsReachable() {
        assertEquals(Clause.BLANK, check("   ").clause());
        assertEquals(Clause.REFS_PREFIX, check("refs/heads/x").clause());
        assertEquals(Clause.SHADOWS_REMOTE, check("origin/main").clause());
        assertEquals(Clause.DOT_DOT, check("a..b").clause());
        assertEquals(Clause.AT_BRACE, check("a@{b").clause());
        assertEquals(Clause.DOUBLE_SLASH, check("a//b").clause());
        assertEquals(Clause.LEADING_DASH, check("-lead").clause());
        assertEquals(Clause.TRAILING_SLASH_OR_DOT, check("trail/").clause());
        assertEquals(Clause.FORBIDDEN_CHAR, check("a~b").clause());
        assertEquals(Clause.EMPTY_COMPONENT, check("/leading").clause());
        assertEquals(Clause.COMPONENT_LEADING_DOT, check("a/.b").clause());
        assertEquals(Clause.COMPONENT_LOCK_SUFFIX, check("a.lock").clause());
    }

    @Test
    void theTokenIsEmptyRatherThanNullForClausesThatQuoteNothing() {
        assertEquals("", check("   ").token());
        assertEquals("", check("refs/heads/x").token());
        assertEquals("", check("a..b").token());
        assertEquals("", check("a@{b").token());
        assertEquals("", check("a//b").token());
        assertEquals("", check("-lead").token());
        assertEquals("", check("trail/").token());
    }

    @Test
    void agentMessagesReproduceTodaysMcpWordingVerbatim() {
        assertEquals("branch must not be blank", message("   "));
        assertEquals("branch must not be a fully qualified ref (starts with 'refs/'): refs/heads/x",
                message("refs/heads/x"));
        assertEquals("'origin/main' as a local branch shadows the remote-tracking ref 'origin'; "
                + "pick a name that does not start with a remote name", message("origin/main"));
        assertEquals("branch name must not contain '..': a..b", message("a..b"));
        assertEquals("branch name must not contain '@{': a@{b", message("a@{b"));
        assertEquals("branch name must not contain consecutive slashes: a//b", message("a//b"));
        assertEquals("branch name must not start with '-': -lead", message("-lead"));
        assertEquals("branch name must not end with '/' or '.': trail/", message("trail/"));
        assertEquals("branch name must not contain '~': a~b", message("a~b"));
        assertEquals("branch name must not have an empty path component: /leading", message("/leading"));
        assertEquals("branch name component must not start with '.': .b", message("a/.b"));
        assertEquals("branch name component must not end with '.lock': a.lock", message("a.lock"));
    }

    @Test
    void humanSentencesAreStandaloneSentencesForTheModalsHintLine() {
        assertEquals("Name the branch to create.", human("   "));
        assertEquals("A branch name cannot start with 'refs/'.", human("refs/heads/x"));
        assertEquals("A branch by that name would shadow the remote 'origin'.", human("origin/main"));
        assertEquals("A branch name cannot contain '..'.", human("a..b"));
        assertEquals("A branch name cannot contain '@{'.", human("a@{b"));
        assertEquals("A branch name cannot contain two slashes in a row.", human("a//b"));
        assertEquals("A branch name cannot start with '-'.", human("-lead"));
        assertEquals("A branch name cannot end with '/' or '.'.", human("trail/"));
        assertEquals("A branch name cannot contain the character '~'.", human("a~b"));
        assertEquals("A branch name cannot have an empty path component.", human("/leading"));
        assertEquals("A path component cannot start with '.' ('.b').", human("a/.b"));
        assertEquals("A path component cannot end with '.lock' ('a.lock').", human("a.lock"));
    }

    @Test
    void shortClausesCompleteTheWhichFrameUsedByDropdownRowsAndHints() {
        assertEquals("has no name", shortClause("   "));
        assertEquals("starts with 'refs/'", shortClause("refs/heads/x"));
        assertEquals("shadows the remote 'origin'", shortClause("origin/main"));
        assertEquals("contains '..'", shortClause("a..b"));
        assertEquals("contains '@{'", shortClause("a@{b"));
        assertEquals("contains '//'", shortClause("a//b"));
        assertEquals("starts with '-'", shortClause("-lead"));
        assertEquals("ends with '/' or '.'", shortClause("trail/"));
        assertEquals("contains '~'", shortClause("a~b"));
        assertEquals("has an empty path component", shortClause("/leading"));
        assertEquals("has a component starting with '.'", shortClause("a/.b"));
        assertEquals("has a component ending with '.lock'", shortClause("a.lock"));
    }

    @Test
    void aNullNameIsBlankRatherThanANullPointerException() {
        assertTrue(BranchNameRules.check(null, REMOTES).isPresent());
        assertEquals(Clause.BLANK, BranchNameRules.check(null, REMOTES).orElseThrow().clause());
    }

    @Test
    void controlCharactersAreForbiddenCharacters() {
        assertEquals(Clause.FORBIDDEN_CHAR, check("has\u0007control").clause());
    }

    @Test
    void aRemoteNameContainingASlashIsMatchedAsAWholeComponent() {
        assertEquals(Clause.SHADOWS_REMOTE,
                BranchNameRules.check("foo/bar/main", Set.of("foo/bar")).orElseThrow().clause());
    }

    private static Refusal check(String name) {
        return BranchNameRules.check(name, REMOTES).orElseThrow(
                () -> new AssertionError("expected a refusal for '" + name + "'"));
    }

    private static String message(String name) {
        return BranchNameRules.agentMessage(name, check(name));
    }

    private static String human(String name) {
        return BranchNameRules.humanSentence(check(name));
    }

    private static String shortClause(String name) {
        return BranchNameRules.shortClause(check(name));
    }
}
