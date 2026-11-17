package app.drydock.intellij.tasks;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchAttributionTest {

    @Test
    void twoWordNameGivesBothInitials() {
        assertEquals(Optional.of("jb"), BranchAttribution.initialsFromFullName("Jaroslav Bachorik"));
    }

    @Test
    void moreThanThreeWordsGiveOnlyTheFirstThreeInitials() {
        assertEquals(Optional.of("mav"), BranchAttribution.initialsFromFullName("Mary Ann Van Der Berg"));
    }

    @Test
    void dashesAndPunctuationSplitWords() {
        assertEquals(Optional.of("jpd"), BranchAttribution.initialsFromFullName("Jean-Pierre Dupont"));
    }

    @Test
    void singleWordGivesNoInitials() {
        assertEquals(Optional.empty(), BranchAttribution.initialsFromFullName("Cher"));
        assertEquals(Optional.empty(), BranchAttribution.initialsFromFullName("x"));
    }

    @Test
    void loginShapedNameSplitsIntoInitials() {
        assertEquals(Optional.of("jb"), BranchAttribution.initialsFromFullName("jaroslav.bachorik"));
    }

    @Test
    void blankNameGivesNoInitials() {
        assertEquals(Optional.empty(), BranchAttribution.initialsFromFullName(null));
        assertEquals(Optional.empty(), BranchAttribution.initialsFromFullName("   "));
    }

    @Test
    void loginIsSanitizedToSlugCharacters() {
        assertEquals(Optional.of("jaroslav-bachorik"), BranchAttribution.fromLogin("Jaroslav.Bachorik"));
        assertEquals(Optional.of("jb"), BranchAttribution.fromLogin("jb"));
    }

    @Test
    void unusableLoginGivesNothing() {
        assertEquals(Optional.empty(), BranchAttribution.fromLogin(null));
        assertEquals(Optional.empty(), BranchAttribution.fromLogin("---"));
    }

    @Test
    void longLoginIsCapped() {
        String login = "a-very-long-operating-system-login-name-indeed";

        Optional<String> token = BranchAttribution.fromLogin(login);

        assertTrue(token.isPresent());
        assertEquals(32, token.get().length());
        assertTrue(token.get().matches("[a-z0-9-]+"), token.get());
    }

    @Test
    void initialsWinOverTheLogin() {
        assertEquals("jb", BranchAttribution.token("Jaroslav Bachorik", "jaroslav.bachorik"));
    }

    @Test
    void singleWordNameFallsBackToTheLogin() {
        assertEquals("jaroslav-bachorik", BranchAttribution.token("Cher", "jaroslav.bachorik"));
    }

    @Test
    void nothingDerivesAnEmptyToken() {
        assertEquals("", BranchAttribution.token(null, null));
        assertEquals("", BranchAttribution.token("", "  --  "));
    }
}
