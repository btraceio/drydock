package app.drydock;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DrydockApplicationTest {

    @Test
    void windowTitleIsSet() {
        assertEquals("Drydock", DrydockApplication.WINDOW_TITLE);
        assertFalse(DrydockApplication.WINDOW_TITLE.isBlank());
    }

    @Test
    void aDiagnosticRunKeepsItsProjectsListBesideItsThrowawayState() {
        // A diag run seeding, adding to, or removing from the user's real
        // ~/.drydock/projects.json would change their real app and IDE.
        assertEquals(Path.of("/tmp/diag/projects.json"), DrydockApplication.sharedProjectsFileFor(
                Optional.of("/tmp/diag/state.json"), Optional.empty(), Path.of("/home/me/.drydock/projects.json")));
        assertEquals(Path.of("/elsewhere/p.json"), DrydockApplication.sharedProjectsFileFor(
                Optional.of("/tmp/diag/state.json"), Optional.of("/elsewhere/p.json"),
                Path.of("/home/me/.drydock/projects.json")));
        assertEquals(Path.of("/home/me/.drydock/projects.json"), DrydockApplication.sharedProjectsFileFor(
                Optional.empty(), Optional.empty(), Path.of("/home/me/.drydock/projects.json")));
    }
}
