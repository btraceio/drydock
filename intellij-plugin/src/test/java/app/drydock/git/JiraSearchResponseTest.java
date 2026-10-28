package app.drydock.git;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JiraSearchResponseTest {

    private static final String CLOUD_BODY = """
            {"issues": [{"key": "PROJ-123",
                         "self": "https://acme.atlassian.net/rest/api/3/issue/10001",
                         "fields": {"summary": "Fix allocation accounting",
                                    "labels": ["profiling", "bug"],
                                    "updated": "2026-10-20T10:15:30.000+0000"}}],
             "nextPageToken": "1", "isLast": true}
            """;

    @Test
    void parsesEveryFieldOfACloudSearchBody() {
        JiraIssueListing listing = JiraSearchResponse.parse(CLOUD_BODY, "https://acme.atlassian.net/");

        JiraIssue issue = assertInstanceOf(JiraIssueListing.Listed.class, listing).issues().getFirst();
        assertEquals("PROJ-123", issue.key());
        assertEquals("Fix allocation accounting", issue.summary());
        assertEquals("https://acme.atlassian.net/browse/PROJ-123", issue.url());
        assertEquals(List.of("profiling", "bug"), issue.labels());
        assertEquals(Instant.parse("2026-10-20T10:15:30Z"), issue.updatedAt().orElseThrow());
    }

    @Test
    void parsesADataCenterBodyAndKeepsTheContextPath() {
        JiraIssueListing listing = JiraSearchResponse.parse("""
                {"startAt": 0, "total": 1,
                 "issues": [{"key": "OPS-7",
                             "fields": {"summary": "Rotate the fleet keys",
                                        "labels": [],
                                        "updated": "2026-10-20T08:00:00.000-0500"}}]}
                """, "https://jira.example.com/jira/");

        JiraIssue issue = assertInstanceOf(JiraIssueListing.Listed.class, listing).issues().getFirst();
        assertEquals("https://jira.example.com/jira/browse/OPS-7", issue.url());
        assertEquals(Instant.parse("2026-10-20T13:00:00Z"), issue.updatedAt().orElseThrow());
        assertTrue(issue.labels().isEmpty());
    }

    @Test
    void aBadUpdatedKeepsTheRowWithAnEmptyTimestamp() {
        JiraIssueListing listing = JiraSearchResponse.parse("""
                {"issues": [{"key": "OPS-7",
                             "fields": {"summary": "Soon", "updated": "yesterday"}}]}
                """, "https://jira.example.com");

        JiraIssue issue = assertInstanceOf(JiraIssueListing.Listed.class, listing).issues().getFirst();
        assertEquals("OPS-7", issue.key());
        assertTrue(issue.updatedAt().isEmpty());
    }

    @Test
    void malformedEntriesAreSkippedAndTheRestKept() {
        JiraIssueListing listing = JiraSearchResponse.parse("""
                {"issues": [
                    {"fields": {"summary": "No key"}},
                    {"key": "A-1", "fields": {}},
                    {"key": 123, "fields": {"summary": "Numeric key"}},
                    "not an object",
                    {"key": "../x", "fields": {"summary": "Option-shaped key"}},
                    {"key": "PROJ-1", "fields": {"summary": "Kept", "labels": ["", "bug", 5]}}
                ]}
                """, "https://acme.atlassian.net");

        List<JiraIssue> issues = assertInstanceOf(JiraIssueListing.Listed.class, listing).issues();
        assertEquals(1, issues.size());
        assertEquals("PROJ-1", issues.getFirst().key());
        assertEquals(List.of("bug"), issues.getFirst().labels());
    }

    @Test
    void aBodyWithoutAnIssuesArrayIsAFailureNotAnEmptyTracker() {
        assertInstanceOf(JiraIssueListing.Failed.class, JiraSearchResponse.parse("[]", "https://x"));
        assertInstanceOf(JiraIssueListing.Failed.class, JiraSearchResponse.parse("{}", "https://x"));
        assertInstanceOf(JiraIssueListing.Failed.class, JiraSearchResponse.parse("{\"issues\":{}}", "https://x"));
        assertInstanceOf(JiraIssueListing.Failed.class, JiraSearchResponse.parse("nope", "https://x"));
    }

    @Test
    void deeplyNestedInputFailsInsteadOfOverflowingTheStack() {
        StringBuilder body = new StringBuilder("{\"issues\": ");
        body.append("[".repeat(10_000));
        body.append("]".repeat(10_000));
        body.append("}");

        JiraIssueListing listing = JiraSearchResponse.parse(body.toString(), "https://x");
        assertInstanceOf(JiraIssueListing.Failed.class, listing);
        assertEquals("Jira returned a response that could not be parsed",
                ((JiraIssueListing.Failed) listing).message());
    }

    @Test
    void errorMessagesJoinBothPartsAndTruncate() {
        Optional<String> joined = JiraSearchResponse.errorMessages("""
                {"errorMessages": ["Field 'x' does not exist"],
                 "errors": {"jql": "The value 'y' is not valid"}}
                """);
        assertEquals("Field 'x' does not exist; The value 'y' is not valid", joined.orElseThrow());

        Optional<String> truncated = JiraSearchResponse.errorMessages(
                "{\"errorMessages\": [\"" + "x".repeat(400) + "\"]}");
        assertEquals(300, truncated.orElseThrow().length());

        assertTrue(JiraSearchResponse.errorMessages("{}").isEmpty());
        assertTrue(JiraSearchResponse.errorMessages("not json").isEmpty());
    }

    @Test
    void baseUrlsMustBeAbsoluteHttpsSites() {
        assertTrue(JiraConnection.baseUrlRefusal("https://acme.atlassian.net/").isEmpty());
        assertTrue(JiraConnection.baseUrlRefusal("https://jira.example.com/jira").isEmpty());

        assertEquals("Use the site's https:// address, for example https://your-site.atlassian.net",
                JiraConnection.baseUrlRefusal("").orElseThrow());
        assertTrue(JiraConnection.baseUrlRefusal("http://x").isPresent());
        assertTrue(JiraConnection.baseUrlRefusal("acme.atlassian.net").isPresent());
        assertTrue(JiraConnection.baseUrlRefusal("https://u:p@x").isPresent());
        assertTrue(JiraConnection.baseUrlRefusal("https://x?q=1").isPresent());
        assertTrue(JiraConnection.baseUrlRefusal("https ://not a uri").isPresent());
    }
}
