package app.drydock.git;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One Jira issue shown in a registered project's Issues section. */
public record JiraIssue(String key, String summary, String url, List<String> labels,
                        Optional<Instant> updatedAt) {
    public JiraIssue {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(url, "url");
        labels = List.copyOf(labels);
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
