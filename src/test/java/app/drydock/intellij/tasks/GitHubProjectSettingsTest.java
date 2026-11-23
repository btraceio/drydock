package app.drydock.intellij.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubProjectSettingsTest {

    @Test
    void repositoryOverrideIsOptionalAndOptionSafe() {
        assertTrue(GitHubProjectSettings.refusal("").isEmpty());
        assertTrue(GitHubProjectSettings.refusal("DataDog/profiling-backend").isEmpty());
        assertTrue(GitHubProjectSettings.refusal("github.example.com/team/repo").isEmpty());
        assertEquals("Use OWNER/REPO or HOST/OWNER/REPO, or leave the repository empty for auto-detection.",
                GitHubProjectSettings.refusal("--repo").orElseThrow());
    }
}
