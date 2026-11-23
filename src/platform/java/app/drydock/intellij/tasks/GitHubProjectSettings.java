package app.drydock.intellij.tasks;

import app.drydock.domain.RepositoryId;
import app.drydock.git.GhCliService;

import com.intellij.ide.util.PropertiesComponent;

import java.util.Objects;
import java.util.Optional;

/** Non-secret GitHub presentation settings for one registered Drydock project. */
record GitHubProjectSettings(boolean issuesEnabled, boolean pullRequestsEnabled, Optional<String> repository) {

    private static final String PREFIX = "drydock.github.";

    GitHubProjectSettings {
        Objects.requireNonNull(repository, "repository");
        repository = repository.map(String::strip).filter(value -> !value.isEmpty());
    }

    static GitHubProjectSettings load(RepositoryId id) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        String prefix = prefix(id);
        return new GitHubProjectSettings(
                properties.getBoolean(prefix + ".issuesEnabled", true),
                properties.getBoolean(prefix + ".pullRequestsEnabled", true),
                Optional.ofNullable(properties.getValue(prefix + ".repository")));
    }

    void save(RepositoryId id) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        String prefix = prefix(id);
        properties.setValue(prefix + ".issuesEnabled", issuesEnabled, true);
        properties.setValue(prefix + ".pullRequestsEnabled", pullRequestsEnabled, true);
        properties.setValue(prefix + ".repository", repository.orElse(null));
    }

    static Optional<String> refusal(String repository) {
        String value = repository == null ? "" : repository.strip();
        if (value.isEmpty() || GhCliService.validRepository(value)) {
            return Optional.empty();
        }
        return Optional.of("Use OWNER/REPO or HOST/OWNER/REPO, or leave the repository empty for auto-detection.");
    }

    private static String prefix(RepositoryId id) {
        return PREFIX + id;
    }
}
