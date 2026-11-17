package app.drydock.intellij.workspace;

import app.drydock.domain.RepositoryId;
import app.drydock.projects.SharedProject;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The per-project Workspace box dialog as stored in the IDE's application
 * properties: the fields pre-fill from {@code WorkspaceSettings} and a save
 * writes them back under their exact keys, blank text clears an override, the
 * pure refusals reject invalid names and relative paths, and the opt-in flag's
 * default false is what keeps the whole surface hidden (the flag-off menu
 * layout itself is pinned in the tasks package's TaskMenuTest).
 */
public class WorkspaceBoxDialogTest extends BasePlatformTestCase {

    private static final String ENABLED_KEY = "drydock.workspaceSessions.enabled";
    private static final Instant ADDED = Instant.parse("2026-10-20T17:00:00Z");

    private final List<String> touchedKeys = new ArrayList<>();

    @Override
    protected void tearDown() throws Exception {
        try {
            PropertiesComponent properties = PropertiesComponent.getInstance();
            for (String key : touchedKeys) {
                properties.unsetValue(key);
            }
        } finally {
            super.tearDown();
        }
    }

    private String watch(String key) {
        touchedKeys.add(key);
        return key;
    }

    private SharedProject project(RepositoryId id) {
        return SharedProject.local(id, Path.of("/work/profiling-backend"), "profiling-backend", ADDED);
    }

    /** Edits both fields in the dialog shown on the EDT and saves, like an OK. */
    private void editAndSave(RepositoryId id, String boxName, String clonePath) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            WorkspaceBoxDialog dialog = new WorkspaceBoxDialog(getProject(), project(id));
            try {
                dialog.boxNameField().setText(boxName);
                dialog.clonePathField().setText(clonePath);
                dialog.save();
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });
    }

    private void assertPrefilledWith(RepositoryId id, String boxName, String clonePath) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            WorkspaceBoxDialog dialog = new WorkspaceBoxDialog(getProject(), project(id));
            try {
                assertEquals(boxName, dialog.boxNameField().getText());
                assertEquals(clonePath, dialog.clonePathField().getText());
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });
    }

    public void testTheOptInFlagDefaultsToOffSoTheSurfaceStaysHidden() {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        properties.unsetValue(watch(ENABLED_KEY));

        // The flag the Settings checkbox writes and the menu build reads:
        // off by default, and storing nothing, so a fresh install exposes no
        // workspace-box UI anywhere (TaskMenu lays the entry out only while
        // the flag is on, and the panel registers its action only then).
        assertFalse(WorkspaceSettings.enabled());
        assertNull(properties.getValue(ENABLED_KEY));
    }

    public void testFieldsRoundTripThroughWorkspaceSettingsUnderTheirExactKeys() {
        RepositoryId id = RepositoryId.newId();
        String boxKey = watch("drydock.workspace." + id + ".box");
        String cloneKey = watch("drydock.workspace." + id + ".clone");
        PropertiesComponent properties = PropertiesComponent.getInstance();

        // Fresh: both fields pre-fill empty.
        assertPrefilledWith(id, "", "");

        // A save writes both keys exactly as entered (WorkspaceSettings
        // strips the box name; a valid path is stored verbatim).
        editAndSave(id, "drydock-profiling-backend", "/home/me/src/profiling-backend");
        assertEquals(Optional.of("drydock-profiling-backend"), WorkspaceSettings.box(id));
        assertEquals("drydock-profiling-backend", properties.getValue(boxKey));
        assertEquals(Optional.of("/home/me/src/profiling-backend"), WorkspaceSettings.clonePath(id));
        assertEquals("/home/me/src/profiling-backend", properties.getValue(cloneKey));

        // A re-open pre-fills from what was saved, and a re-save updates
        // both keys in place.
        assertPrefilledWith(id, "drydock-profiling-backend", "/home/me/src/profiling-backend");
        editAndSave(id, "drydock-profiling-backend-2", "/home/me/src/profiling-backend-v2");
        assertEquals(Optional.of("drydock-profiling-backend-2"), WorkspaceSettings.box(id));
        assertEquals(Optional.of("/home/me/src/profiling-backend-v2"), WorkspaceSettings.clonePath(id));
        assertEquals("drydock-profiling-backend-2", properties.getValue(boxKey));
    }

    public void testBlankTextClearsTheOverridesRatherThanStoringWhitespace() {
        RepositoryId id = RepositoryId.newId();
        String boxKey = watch("drydock.workspace." + id + ".box");
        String cloneKey = watch("drydock.workspace." + id + ".clone");
        PropertiesComponent properties = PropertiesComponent.getInstance();

        editAndSave(id, "drydock-profiling-backend", "/home/me/src/profiling-backend");
        // The manual escape hatch removed: back to the default name and
        // discovery, with no key left behind.
        editAndSave(id, "   ", "");
        assertTrue(WorkspaceSettings.box(id).isEmpty());
        assertTrue(WorkspaceSettings.clonePath(id).isEmpty());
        assertNull(properties.getValue(boxKey));
        assertNull(properties.getValue(cloneKey));
    }

    public void testBoxNameRefusalAcceptsOnlyCliValidNamesOrEmpty() {
        assertTrue(WorkspaceBoxDialog.boxNameRefusal(null).isEmpty());
        assertTrue(WorkspaceBoxDialog.boxNameRefusal("").isEmpty());
        assertTrue(WorkspaceBoxDialog.boxNameRefusal("drydock-profiling-backend").isEmpty());
        assertTrue(WorkspaceBoxDialog.boxNameRefusal("drydock-profiling-backend-2").isEmpty());
        assertTrue(WorkspaceBoxDialog.boxNameRefusal("  drydock-profiling-backend  ").isEmpty());
        // Uppercase, underscores, a leading dash, and a name longer than 63
        // characters are all refused: the workspaces CLI would reject them,
        // and a persisted binding must never carry them.
        assertTrue(WorkspaceBoxDialog.boxNameRefusal("Drydock-Backend").isPresent());
        assertTrue(WorkspaceBoxDialog.boxNameRefusal("drydock_backend").isPresent());
        assertTrue(WorkspaceBoxDialog.boxNameRefusal("-drydock-backend").isPresent());
        assertTrue(WorkspaceBoxDialog.boxNameRefusal("a".repeat(64)).isPresent());
        assertEquals(Optional.empty(), WorkspaceBoxDialog.boxNameRefusal("a".repeat(63)));
    }

    public void testClonePathRefusalAcceptsOnlyAFullPathOrEmpty() {
        assertTrue(WorkspaceBoxDialog.clonePathRefusal(null).isEmpty());
        assertTrue(WorkspaceBoxDialog.clonePathRefusal("").isEmpty());
        assertTrue(WorkspaceBoxDialog.clonePathRefusal("/home/me/src/profiling-backend").isEmpty());
        // A relative path cannot name a location on the box.
        assertTrue(WorkspaceBoxDialog.clonePathRefusal("src/profiling-backend").isPresent());
        assertTrue(WorkspaceBoxDialog.clonePathRefusal("src/profiling-backend").orElseThrow()
                .contains("full path"));
    }
}
