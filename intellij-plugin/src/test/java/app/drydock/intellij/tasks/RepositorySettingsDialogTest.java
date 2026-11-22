package app.drydock.intellij.tasks;

import app.drydock.domain.RepositoryId;
import app.drydock.intellij.identity.AgentIdentitySettings;
import app.drydock.intellij.workspace.WorkspaceSettings;
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
 * The sectioned per-repository settings dialog as stored in the IDE's
 * application properties: each section pre-fills from its settings record, a
 * save writes every section back under its exact keys, blank workspace text
 * clears an override, and the pure refusals reject an invalid box name, a
 * relative clone path, and an agent identity enabled without a command.
 */
public class RepositorySettingsDialogTest extends BasePlatformTestCase {

    private static final Instant ADDED = Instant.parse("2026-10-20T17:00:00Z");

    private final List<String> touchedKeys = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // The workspace-box section exists in the dialog only while the
        // experimental opt-in is on; the workspace tests below drive it.
        WorkspaceSettings.setEnabled(true);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            PropertiesComponent properties = PropertiesComponent.getInstance();
            WorkspaceSettings.setEnabled(false);
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

    /** Opens the dialog on the EDT, edits the workspace and identity fields, and saves. */
    private void editAndSave(RepositoryId id, String boxName, String clonePath,
                             boolean identityEnabled, String identityCommand) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            RepositorySettingsDialog dialog = new RepositorySettingsDialog(getProject(), project(id), "");
            try {
                dialog.boxNameField().setText(boxName);
                dialog.clonePathField().setText(clonePath);
                dialog.identityEnabledCheckbox().setSelected(identityEnabled);
                dialog.identityCommandField().setText(identityCommand);
                dialog.workspaceSection().save();
                dialog.agentIdentitySection().save();
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });
    }

    private void assertIdentityPrefilledWith(RepositoryId id, boolean enabled, String command) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            RepositorySettingsDialog dialog = new RepositorySettingsDialog(getProject(), project(id), "");
            try {
                assertEquals(enabled, dialog.identityEnabledCheckbox().isSelected());
                assertEquals(command, dialog.identityCommandField().getText());
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });
    }

    public void testIdentitySettingsRoundTripUnderTheirExactKeys() {
        RepositoryId id = RepositoryId.newId();
        String enabledKey = watch("drydock.agentIdentity." + id + ".enabled");
        String commandKey = watch("drydock.agentIdentity." + id + ".credentialCommand");
        PropertiesComponent properties = PropertiesComponent.getInstance();

        // Fresh: off, no command.
        assertIdentityPrefilledWith(id, false, "");

        editAndSave(id, "", "", true, "glue mint --repo .");
        assertEquals("true", properties.getValue(enabledKey));
        assertEquals("glue mint --repo .", properties.getValue(commandKey));
        assertIdentityPrefilledWith(id, true, "glue mint --repo .");

        // Off again, and the command key leaves no trace.
        editAndSave(id, "", "", false, "");
        assertEquals("false", properties.getValue(enabledKey));
        assertNull(properties.getValue(commandKey));
    }

    public void testWorkspaceFieldsRoundTripThroughWorkspaceSettings() {
        RepositoryId id = RepositoryId.newId();
        String boxKey = watch("drydock.workspace." + id + ".box");
        String cloneKey = watch("drydock.workspace." + id + ".clone");
        PropertiesComponent properties = PropertiesComponent.getInstance();

        editAndSave(id, "drydock-profiling-backend", "/home/me/src/profiling-backend", false, "");
        assertEquals(Optional.of("drydock-profiling-backend"), WorkspaceSettings.box(id));
        assertEquals(Optional.of("/home/me/src/profiling-backend"), WorkspaceSettings.clonePath(id));
        assertEquals("drydock-profiling-backend", properties.getValue(boxKey));
        assertEquals("/home/me/src/profiling-backend", properties.getValue(cloneKey));

        // A blank value clears the override instead of storing whitespace.
        editAndSave(id, "   ", "", false, "");
        assertTrue(WorkspaceSettings.box(id).isEmpty());
        assertTrue(WorkspaceSettings.clonePath(id).isEmpty());
        assertNull(properties.getValue(boxKey));
        assertNull(properties.getValue(cloneKey));
    }

    public void testBoxNameRefusalAcceptsOnlyCliValidNamesOrEmpty() {
        assertTrue(WorkspaceSettings.boxNameRefusal(null).isEmpty());
        assertTrue(WorkspaceSettings.boxNameRefusal("").isEmpty());
        assertTrue(WorkspaceSettings.boxNameRefusal("drydock-profiling-backend").isEmpty());
        assertTrue(WorkspaceSettings.boxNameRefusal("  drydock-profiling-backend  ").isEmpty());
        // Uppercase, underscores, a leading dash, and a name longer than 63
        // characters are all refused: the workspaces CLI would reject them,
        // and a persisted binding must never carry them.
        assertTrue(WorkspaceSettings.boxNameRefusal("Drydock-Backend").isPresent());
        assertTrue(WorkspaceSettings.boxNameRefusal("drydock_backend").isPresent());
        assertTrue(WorkspaceSettings.boxNameRefusal("-drydock-backend").isPresent());
        assertTrue(WorkspaceSettings.boxNameRefusal("a".repeat(64)).isPresent());
        assertEquals(Optional.empty(), WorkspaceSettings.boxNameRefusal("a".repeat(63)));
    }

    public void testClonePathRefusalAcceptsOnlyAFullPathOrEmpty() {
        assertTrue(WorkspaceSettings.clonePathRefusal(null).isEmpty());
        assertTrue(WorkspaceSettings.clonePathRefusal("").isEmpty());
        assertTrue(WorkspaceSettings.clonePathRefusal("/home/me/src/profiling-backend").isEmpty());
        // A relative path cannot name a location on the box.
        assertTrue(WorkspaceSettings.clonePathRefusal("src/profiling-backend").isPresent());
        assertTrue(WorkspaceSettings.clonePathRefusal("src/profiling-backend").orElseThrow()
                .contains("full path"));
    }

    public void testIdentityRefusalNamesTheMissingCommandOnlyWhenEnabled() {
        assertTrue(AgentIdentitySettings.refusal(true, "").isPresent());
        assertTrue(AgentIdentitySettings.refusal(true, "   ").isPresent());
        assertTrue(AgentIdentitySettings.refusal(false, "").isEmpty());
        assertTrue(AgentIdentitySettings.refusal(true, "glue mint").isEmpty());
    }

    public void testIdentityValidationOnTheDialogRejectsEnabledWithoutACommand() {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            RepositorySettingsDialog dialog = new RepositorySettingsDialog(
                    getProject(), project(RepositoryId.newId()), "");
            try {
                dialog.identityEnabledCheckbox().setSelected(true);
                dialog.identityCommandField().setText("");
                assertNotNull(dialog.doValidate()); // enabled without a command refuses to save
                dialog.identityCommandField().setText("glue mint");
                assertNull(dialog.doValidate());
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });
    }
}
