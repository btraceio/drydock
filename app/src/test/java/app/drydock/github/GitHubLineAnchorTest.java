package app.drydock.github;

import app.drydock.github.GitHubLineAnchor.Anchor;
import app.drydock.github.GitHubLineAnchor.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Line keys to the anchors GitHub's review API accepts. */
class GitHubLineAnchorTest {

    @Test
    void aSingleAddedLineCarriesNoStart() {
        Anchor anchor = GitHubLineAnchor.of("n42", "n42");

        assertEquals(42, anchor.line());
        assertEquals(Side.RIGHT, anchor.side());
        assertFalse(anchor.startLine().isPresent(), "a one-line comment must not send start_line");
        assertTrue(anchor.startSide().isEmpty());
    }

    @Test
    void aSingleDeletedLineIsLeftSide() {
        Anchor anchor = GitHubLineAnchor.of("o17", "o17");

        assertEquals(17, anchor.line());
        assertEquals(Side.LEFT, anchor.side());
        assertFalse(anchor.startLine().isPresent());
    }

    @Test
    void aRangeInsideTheNewFileIsRightOnBothEnds() {
        Anchor anchor = GitHubLineAnchor.of("n40", "n48");

        assertEquals(40, anchor.startLine().getAsInt());
        assertEquals(Side.RIGHT, anchor.startSide().orElseThrow());
        assertEquals(48, anchor.line());
        assertEquals(Side.RIGHT, anchor.side());
    }

    @Test
    void aRangeFromADeletionIntoItsReplacementKeepsBothSides() {
        // The commonest multi-line review comment there is. GitHub renders it
        // as "Comment on lines -55 to +58"; collapsing it onto one side would
        // reuse old-file line 55 as a new-file line number.
        Anchor anchor = GitHubLineAnchor.of("o55", "n58");

        assertEquals(55, anchor.startLine().getAsInt());
        assertEquals(Side.LEFT, anchor.startSide().orElseThrow());
        assertEquals(58, anchor.line());
        assertEquals(Side.RIGHT, anchor.side());
    }

    @Test
    void identicalNumbersOnDifferentSidesAreStillARange() {
        // o12 and n12 are different physical lines, so this is NOT collapsible
        // to a single-line anchor even though the numbers match.
        Anchor anchor = GitHubLineAnchor.of("o12", "n12");

        assertEquals(12, anchor.startLine().getAsInt());
        assertEquals(Side.LEFT, anchor.startSide().orElseThrow());
        assertEquals(Side.RIGHT, anchor.side());
    }

    @Test
    void aMalformedKeyIsRejectedRatherThanGuessed() {
        assertThrows(IllegalArgumentException.class, () -> GitHubLineAnchor.of("x9", "n9"));
        assertThrows(IllegalArgumentException.class, () -> GitHubLineAnchor.of("n9", ""));
    }
}
