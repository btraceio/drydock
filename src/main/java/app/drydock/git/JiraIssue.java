package app.drydock.git;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One Jira issue shown in a registered project's Issues section. */
public record JiraIssue(String key, String summary, String url, List<String> labels,
                        Optional<Instant> updatedAt, Optional<String> priority) {
    public JiraIssue {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(url, "url");
        labels = List.copyOf(labels);
        Objects.requireNonNull(updatedAt, "updatedAt");
        priority = Objects.requireNonNullElse(priority, Optional.empty());
    }

    /** The shape before issues carried their priority: no priority. */
    public JiraIssue(String key, String summary, String url, List<String> labels,
                     Optional<Instant> updatedAt) {
        this(key, summary, url, labels, updatedAt, Optional.empty());
    }
}
