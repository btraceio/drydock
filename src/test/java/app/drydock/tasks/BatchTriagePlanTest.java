package app.drydock.tasks;

import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.JiraIssue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The batch triage planner: the untriaged filter, the hybrid labeling rule, the prompt pieces. */
class BatchTriagePlanTest {

    private static OpenIssue github(int number, String... labels) {
        return new OpenIssue(number, "Title " + number, Optional.empty(), List.of(labels),
                List.of(), Optional.empty());
    }

    private static JiraIssue jira(String key, String... labels) {
        return new JiraIssue(key, "Summary " + key, "https://jira.example.com/browse/" + key,
                List.of(labels), Optional.of(Instant.EPOCH));
    }

    @Test
    void githubUntriagedIsEverythingWithoutTheExactLabel() {
        List<OpenIssue> issues = List.of(github(1), github(2, "triaged"), github(3, "Triaged"),
                github(4, "bug", "triaged"));
        List<OpenIssue> untriaged = BatchTriagePlan.untriagedGitHub(issues, Set.of());
        assertEquals(List.of(1, 3), untriaged.stream().map(OpenIssue::number).toList());
    }

    @Test
    void githubInFlightIssuesAreFilteredEvenWithoutTheLabel() {
        List<OpenIssue> issues = List.of(github(1), github(2), github(3));
        List<OpenIssue> untriaged = BatchTriagePlan.untriagedGitHub(issues, Set.of(2));
        assertEquals(List.of(1, 3), untriaged.stream().map(OpenIssue::number).toList());
    }

    @Test
    void jiraUntriagedIsEverythingWithoutTheExactLabel() {
        List<JiraIssue> issues = List.of(jira("A-1"), jira("A-2", "triaged"), jira("A-3", "bug"));
        List<JiraIssue> untriaged = BatchTriagePlan.untriagedJira(issues, Set.of("A-2"));
        assertEquals(List.of("A-1", "A-3"), untriaged.stream().map(JiraIssue::key).toList());
    }

    @Test
    void anyLabelMentionCountsAsHandlingLabeling() {
        assertTrue(BatchTriagePlan.mentionsLabeling("Label the issue 'triaged' when done."));
        assertTrue(BatchTriagePlan.mentionsLabeling("labels are out of scope here"));
        assertFalse(BatchTriagePlan.mentionsLabeling("Triage the report against the repository."));
        assertFalse(BatchTriagePlan.mentionsLabeling(null));
    }

    @Test
    void theGithubLabelingStepNamesTheLabelAndTheCommand() {
        String step = BatchTriagePlan.githubLabelingStep();
        assertTrue(step.contains("triaged"));
        assertTrue(step.contains("gh issue edit"));
    }

    @Test
    void sessionNamesFollowThePerIssuePresetShape() {
        assertEquals("Triage #7 Title 7", BatchTriagePlan.sessionName(github(7)));
        assertEquals("Triage A-9 Summary A-9", BatchTriagePlan.sessionName(jira("A-9")));
    }

    @Test
    void resumeSurvivorsKeepUnlabeledListedIssuesAndDropTheRest() {
        List<BatchTriageQueue.Entry> entries = List.of(
                new BatchTriageQueue.Entry("1", "t", ""),
                new BatchTriageQueue.Entry("2", "t", ""),
                new BatchTriageQueue.Entry("3", "t", ""),
                new BatchTriageQueue.Entry("bogus", "t", ""));
        List<OpenIssue> listed = List.of(github(1), github(2, "triaged"));
        List<Integer> survivors = BatchTriagePlan.resumeSurvivorsGitHub(entries, listed)
                .stream().map(OpenIssue::number).toList();
        assertEquals(List.of(1), survivors);
    }

    @Test
    void jiraResumeSurvivorsMatchByKey() {
        List<BatchTriageQueue.Entry> entries = List.of(
                new BatchTriageQueue.Entry("A-1", "t", ""),
                new BatchTriageQueue.Entry("A-2", "t", ""),
                new BatchTriageQueue.Entry("A-9", "t", ""));
        List<JiraIssue> listed = List.of(jira("A-1"), jira("A-2", "triaged"), jira("A-3"));
        List<String> survivors = BatchTriagePlan.resumeSurvivorsJira(entries, listed)
                .stream().map(JiraIssue::key).toList();
        assertEquals(List.of("A-1"), survivors);
    }
}
