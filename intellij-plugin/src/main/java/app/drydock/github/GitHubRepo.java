package app.drydock.github;

import java.util.Objects;
import java.util.Optional;

/** One GitHub search result row (design handoff section 7). */
public record GitHubRepo(
        String fullName,
        Optional<String> description,
        long stars,
        Optional<String> language,
        String cloneUrl
) {
    public GitHubRepo {
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(cloneUrl, "cloneUrl");
    }

    /** Directory name a clone of this repo gets by default. */
    public String defaultDirectoryName() {
        int slash = fullName.lastIndexOf('/');
        return slash >= 0 ? fullName.substring(slash + 1) : fullName;
    }
}
