package app.drydock.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PullRequestLabelFilterTest {

    @Test
    void parsesSingleLabelWithSpacesColonsSlashesAndInternalHyphens() {
        assertEquals("label:\"bug\"", PullRequestLabelFilter.parse("bug").toGitHubSearchQuery());
        assertEquals("label:\"comp: profiling\"",
                PullRequestLabelFilter.parse("  comp: profiling  ").toGitHubSearchQuery());
        assertEquals("label:\"team/runtime\"",
                PullRequestLabelFilter.parse("team/runtime").toGitHubSearchQuery());
        assertEquals("label:\"do-not-merge\"",
                PullRequestLabelFilter.parse("do-not-merge").toGitHubSearchQuery());
        // Lowercase and/or/not inside multi-word labels are part of the label name.
        assertEquals("label:\"wait and see\"",
                PullRequestLabelFilter.parse("wait and see").toGitHubSearchQuery());
        assertEquals("label:\"bug or feature\"",
                PullRequestLabelFilter.parse("bug or feature").toGitHubSearchQuery());
        assertEquals("label:\"do not merge\"",
                PullRequestLabelFilter.parse("do not merge").toGitHubSearchQuery());
        assertEquals("label:\"status - waiting\"",
                PullRequestLabelFilter.parse("status - waiting").toGitHubSearchQuery());
    }

    @Test
    void compilesAndOrNotOperatorsAndPrecedence() {
        assertEquals("label:\"a\" AND label:\"b\"",
                PullRequestLabelFilter.parse("a & b").toGitHubSearchQuery());
        assertEquals("label:\"a\" AND label:\"b\"",
                PullRequestLabelFilter.parse("a && b").toGitHubSearchQuery());
        assertEquals("label:\"a\" AND label:\"b\"",
                PullRequestLabelFilter.parse("a AND b").toGitHubSearchQuery());

        assertEquals("label:\"a\" OR label:\"b\"",
                PullRequestLabelFilter.parse("a | b").toGitHubSearchQuery());
        assertEquals("label:\"a\" OR label:\"b\"",
                PullRequestLabelFilter.parse("a || b").toGitHubSearchQuery());
        assertEquals("label:\"a\" OR label:\"b\"",
                PullRequestLabelFilter.parse("a OR b").toGitHubSearchQuery());

        assertEquals("-label:\"wip\"", PullRequestLabelFilter.parse("!wip").toGitHubSearchQuery());
        assertEquals("-label:\"wip\"", PullRequestLabelFilter.parse("-wip").toGitHubSearchQuery());
        assertEquals("-label:\"wip\"", PullRequestLabelFilter.parse("NOT wip").toGitHubSearchQuery());

        // AND binds tighter than OR; explicit parentheses make precedence unambiguous.
        assertEquals("(label:\"a\" AND label:\"b\") OR label:\"c\"",
                PullRequestLabelFilter.parse("a & b | c").toGitHubSearchQuery());
        assertEquals("label:\"a\" OR (label:\"b\" AND label:\"c\")",
                PullRequestLabelFilter.parse("a | b & c").toGitHubSearchQuery());
        assertEquals("(label:\"a\" OR label:\"b\") AND label:\"c\"",
                PullRequestLabelFilter.parse("(a | b) & c").toGitHubSearchQuery());
    }

    @Test
    void pushesNegationToLeavesViaDeMorgan() {
        assertEquals("label:\"bug\"", PullRequestLabelFilter.parse("!!bug").toGitHubSearchQuery());
        assertEquals("label:\"bug\"", PullRequestLabelFilter.parse("NOT -bug").toGitHubSearchQuery());
        assertEquals("-label:\"a\" OR -label:\"b\"",
                PullRequestLabelFilter.parse("!(a & b)").toGitHubSearchQuery());
        assertEquals("-label:\"a\" AND -label:\"b\"",
                PullRequestLabelFilter.parse("!(a | b)").toGitHubSearchQuery());
        assertEquals("label:\"comp: profiling\" AND (-label:\"comp: core\" OR -label:\"tag: ai generated\")",
                PullRequestLabelFilter.parse("comp: profiling & !(comp: core & tag: ai generated)")
                        .toGitHubSearchQuery());
        assertEquals("-label:\"a\" AND label:\"b\" AND label:\"c\"",
                PullRequestLabelFilter.parse("NOT (a OR NOT (b AND c))").toGitHubSearchQuery());
    }

    @Test
    void supportsQuotedLabelsWithOperatorsLeadingHyphensAndEscapes() {
        assertEquals("label:\"a & b\" AND label:\"-leading\"",
                PullRequestLabelFilter.parse("\"a & b\" & '-leading'").toGitHubSearchQuery());
        assertEquals("label:\"R AND D\" OR label:\"x | y\"",
                PullRequestLabelFilter.parse("\"R AND D\" OR 'x | y'").toGitHubSearchQuery());
        assertEquals("label:\"say \\\"hi\\\"\" AND label:\"a\\\\b\"",
                PullRequestLabelFilter.parse("\"say \\\"hi\\\"\" & \"a\\\\b\"").toGitHubSearchQuery());
    }

    @Test
    void matchesEvaluatesBooleanLogicCaseInsensitively() {
        PullRequestLabelFilter filter = PullRequestLabelFilter.parse(
                "(comp: profiling | comp: jfr) & !tag: wip");

        assertTrue(filter.matches(List.of("Comp: Profiling", "type: bug fix")));
        assertTrue(filter.matches(List.of("comp: jfr")));
        assertFalse(filter.matches(List.of("comp: profiling", "TAG: WIP")));
        assertFalse(filter.matches(List.of("comp: core")));
        assertFalse(filter.matches(List.of()));

        PullRequestLabelFilter deMorgan = PullRequestLabelFilter.parse("!(a & b)");
        assertTrue(deMorgan.matches(List.of()));
        assertTrue(deMorgan.matches(List.of("a")));
        assertTrue(deMorgan.matches(List.of("b")));
        assertFalse(deMorgan.matches(List.of("a", "b")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "\"\"",
            "\"   \"",
            "''",
            "\"unterminated",
            "'unterminated",
            "\"bad\\n\"",
            "\"dangling\\",
            "bug &",
            "& bug",
            "bug |",
            "| bug",
            "NOT",
            "!",
            "-",
            "()",
            "(bug",
            "bug)",
            "\"a\" \"b\"",
            "a NOT b",
            "(a) (b)",
            "bug\nother",
            "bug\rother",
            "bug\0other"
    })
    void rejectsMalformedFiltersWithAClearRefusal(String bad) {
        Optional<String> refusal = PullRequestLabelFilter.refusal(bad);
        assertTrue(refusal.isPresent(), () -> "expected refusal for: " + bad);
        assertFalse(refusal.get().isBlank());
        assertThrows(IllegalArgumentException.class, () -> PullRequestLabelFilter.parse(bad));
    }

    @Test
    void enforcesSizeAndDepthBounds() {
        String tooLong = "a".repeat(PullRequestLabelFilter.MAX_INPUT_LENGTH + 1);
        assertTrue(PullRequestLabelFilter.refusal(tooLong).isPresent());

        String tooDeep = "(".repeat(PullRequestLabelFilter.MAX_DEPTH + 1)
                + "bug"
                + ")".repeat(PullRequestLabelFilter.MAX_DEPTH + 1);
        assertTrue(PullRequestLabelFilter.refusal(tooDeep).isPresent());

        String deepNot = "!".repeat(PullRequestLabelFilter.MAX_DEPTH + 1) + "bug";
        assertTrue(PullRequestLabelFilter.refusal(deepNot).isPresent());
    }
}
