package app.drydock.intellij.tasks;

import app.drydock.git.GhCliService.OpenPullRequest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SearchTerms}: the pr:/issue:/is: prefixes, quoting, and the field matchers. */
class SearchTermsTest {

    private static SearchTerms<InboxModel.GroupKind> parse(String text) {
        return SearchTerms.parse(text, InboxModel::kindOf);
    }

    private static OpenPullRequest pr(int number, String title, String head) {
        return new OpenPullRequest(number, title, head, "main", false, Optional.of("someone"), Optional.empty());
    }

    // -- parsing -----------------------------------------------------------

    @Test
    void plainTextWithoutPrefixesIsTheTitleText() {
        SearchTerms<InboxModel.GroupKind> terms = parse("parser fix");
        assertTrue(terms.pullRequests().isEmpty());
        assertTrue(terms.issues().isEmpty());
        assertTrue(terms.groupKinds().isEmpty());
        assertEquals("parser fix", terms.plainText());
        assertFalse(terms.isEmpty());
    }

    @Test
    void prefixTermsAreExtractedFromThePlainLeftovers() {
        SearchTerms<InboxModel.GroupKind> terms = parse("pr:12 idle issue:PROJ-7 fix");
        assertEquals(List.of("12"), terms.pullRequests());
        assertEquals(List.of("PROJ-7"), terms.issues());
        assertEquals("idle fix", terms.plainText());
    }

    @Test
    void isWordsStayGroupWords() {
        SearchTerms<InboxModel.GroupKind> terms = parse("is:done is:nonsense fix");
        assertEquals(Set.of(InboxModel.GroupKind.DONE), terms.groupKinds());
        // An unknown is:<word> is plain text, as before.
        assertEquals("is:nonsense fix", terms.plainText());
    }

    @Test
    void plainQuotedPhraseIsDequotedAndEmptyQuotedTokensAreDropped() {
        assertEquals("dark mode fix", parse("\"dark mode\" fix").plainText());
        assertTrue(parse("\"\"").isEmpty());
        assertEquals("", parse("\"\"").plainText());
        assertEquals("fix", parse("\"\" fix").plainText());
        assertEquals("fix", parse("\"  \" fix").plainText());
    }

    @Test
    void parserUsesTheInjectedGroupDecoder() {
        SearchTerms<String> terms = SearchTerms.parse("is:done is:nonsense", token ->
                token.equalsIgnoreCase("done") ? Optional.of("decoded-done") : Optional.empty());
        assertEquals(Set.of("decoded-done"), terms.groupKinds());
        assertEquals("is:nonsense", terms.plainText());
    }

    @Test
    void parserHasNoDependencyOnInboxModel() throws Exception {
        try (var classBytes = SearchTerms.class.getResourceAsStream("SearchTerms.class")) {
            assertTrue(classBytes != null);
            assertFalse(new String(classBytes.readAllBytes(), StandardCharsets.ISO_8859_1).contains("InboxModel"));
        }
    }

    @Test
    void quotedTermKeepsItsSpaces() {
        SearchTerms<InboxModel.GroupKind> terms = parse("pr:\"dark mode\" issue:\"login fails\"");
        assertEquals(List.of("dark mode"), terms.pullRequests());
        assertEquals(List.of("login fails"), terms.issues());
        assertTrue(terms.plainText().isEmpty());
    }

    @Test
    void multipleTermsOfOneKindAndTogether() {
        SearchTerms<InboxModel.GroupKind> terms = parse("pr:12 pr:checkout");
        assertEquals(List.of("12", "checkout"), terms.pullRequests());
    }

    @Test
    void argumentlessPrefixConstrainsNothing() {
        SearchTerms<InboxModel.GroupKind> terms = parse("pr: issue:");
        assertTrue(terms.pullRequests().isEmpty());
        assertTrue(terms.issues().isEmpty());
        assertTrue(terms.isEmpty());
    }

    @Test
    void caseInsensitivePrefixes() {
        SearchTerms<InboxModel.GroupKind> terms = parse("PR:12 Issue:PROJ-7");
        assertEquals(List.of("12"), terms.pullRequests());
        assertEquals(List.of("PROJ-7"), terms.issues());
    }

    @Test
    void emptyTextIsNoConstraint() {
        assertTrue(parse(null).isEmpty());
        assertTrue(parse("  ").isEmpty());
    }

    // -- server-side search queries ---------------------------------------

    @Test
    void thePullRequestSearchQueryJoinsTextPrTermsWithThePlainText() {
        SearchTerms<InboxModel.GroupKind> terms = parse("pr:12 pr:checkout \"dark mode\" fix");

        // Number terms resolve by exact lookup, never by --search; a quoted
        // span stays one quoted token so gh searches the phrase.
        assertEquals("checkout \"dark mode\" fix", terms.pullRequestSearchQuery());
        assertEquals(List.of(12), terms.pullRequestNumbers());
    }

    @Test
    void theIssueSearchQueryJoinsIssueTermsWithThePlainText() {
        assertEquals("PROJ-7 allocation", parse("issue:PROJ-7 allocation").issueSearchQuery());
        assertEquals("idle", parse("pr:12 idle").issueSearchQuery(),
                "the plain text narrows issue titles too");
    }

    @Test
    void noTermsMeansNoSearchQueryAndNoExactLookups() {
        SearchTerms<InboxModel.GroupKind> empty = parse("");
        assertEquals("", empty.pullRequestSearchQuery());
        assertEquals("", empty.issueSearchQuery());
        assertTrue(empty.pullRequestNumbers().isEmpty());
    }

    // -- pull request matching --------------------------------------------

    @Test
    void prNumberMatchesExactly() {
        assertTrue(SearchTerms.pullRequest("12", new SearchTerms.PrRef(12, "t", "h", Optional.empty())));
        assertFalse(SearchTerms.pullRequest("1", new SearchTerms.PrRef(12, "t", "h", Optional.empty())));
        assertTrue(SearchTerms.pullRequest("#12", new SearchTerms.PrRef(12, "t", "h", Optional.empty())));
        assertFalse(SearchTerms.pullRequest("2147483648", SearchTerms.PrRef.of(12, "t", "h")));
        assertFalse(SearchTerms.pullRequest("999999999999999999999999999999999999999999",
                SearchTerms.PrRef.of(12, "t", "h")));
    }

    @Test
    void prFreeTextMatchesTitleBranchAndAuthor() {
        assertTrue(SearchTerms.pullRequest("dark", SearchTerms.PrRef.of(1, "Fix the dark mode", "fix/ui")));
        assertTrue(SearchTerms.pullRequest("fix/ui", SearchTerms.PrRef.of(1, "t", "fix/ui")));
        assertTrue(SearchTerms.pullRequest("someone", new SearchTerms.PrRef(1, "t", "h", Optional.of("someone"))));
        assertFalse(SearchTerms.pullRequest("nowhere", SearchTerms.PrRef.of(1, "t", "fix/ui")));
    }

    // -- issue matching ----------------------------------------------------

    @Test
    void githubIssueNumberMatchesExactly() {
        assertTrue(SearchTerms.issue("12", new SearchTerms.IssueRef("#12", Optional.of("t"))));
        assertFalse(SearchTerms.issue("1", new SearchTerms.IssueRef("#12", Optional.of("t"))));
    }

    @Test
    void jiraKeyTailMatchesButNotMidKey() {
        assertTrue(SearchTerms.issue("42", new SearchTerms.IssueRef("PROJ-42", Optional.of("t"))));
        assertFalse(SearchTerms.issue("4", new SearchTerms.IssueRef("PROJ-42", Optional.of("t"))));
    }

    @Test
    void issueKeyAndSummaryMatchAsText() {
        assertTrue(SearchTerms.issue("PROJ-7", new SearchTerms.IssueRef("PROJ-7", Optional.of("t"))));
        assertTrue(SearchTerms.issue("login", new SearchTerms.IssueRef("PROJ-7", Optional.of("Login fails"))));
        // A mirror address carries no title: only its key can match.
        assertFalse(SearchTerms.issue("login", new SearchTerms.IssueRef("PROJ-7", Optional.empty())));
    }
}
