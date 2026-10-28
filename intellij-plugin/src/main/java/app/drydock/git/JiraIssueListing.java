package app.drydock.git;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of asking a Jira site for a project's issues. Three cases,
 * not two, mirroring {@link GhCliService.IssueListing}: a section that
 * renders "no issues" when the query is broken tells the reader something
 * false, and a section that renders an error when Jira simply is not
 * configured nags the user about a tool they never asked for.
 */
public sealed interface JiraIssueListing {
    /** Jira answered. The list may legitimately be empty. */
    record Listed(List<JiraIssue> issues) implements JiraIssueListing {
        public Listed {
            issues = List.copyOf(issues);
        }
    }

    /** Jira is not configured for this project: show nothing at all. */
    record Unsupported() implements JiraIssueListing { }

    /** Jira is configured and did not answer: say so, with something actionable. */
    record Failed(String message) implements JiraIssueListing {
        public Failed {
            Objects.requireNonNull(message, "message");
        }
    }
}
