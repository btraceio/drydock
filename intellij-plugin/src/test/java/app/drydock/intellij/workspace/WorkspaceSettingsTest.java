package app.drydock.intellij.workspace;

import app.drydock.domain.RepositoryId;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The workspace-box session settings as stored in the IDE's application
 * properties: the opt-in flag's default, and the per-repository box and clone
 * keys round-tripping under their exact names.
 */
public class WorkspaceSettingsTest extends BasePlatformTestCase {

    private static final String ENABLED_KEY = "drydock.workspaceSessions.enabled";

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

    public void testFlagDefaultsToFalseAndLeavesNoTraceWhenOff() {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        properties.unsetValue(watch(ENABLED_KEY));

        assertFalse("a fresh install must read the flag as off", WorkspaceSettings.enabled());
        assertNull("a fresh install must store no flag", properties.getValue(ENABLED_KEY));

        WorkspaceSettings.setEnabled(true);
        assertTrue(WorkspaceSettings.enabled());
        assertEquals("true", properties.getValue(ENABLED_KEY));

        // Writing the default unsets the key again, so turning the feature
        // off restores a byte-identical fresh install.
        WorkspaceSettings.setEnabled(false);
        assertFalse(WorkspaceSettings.enabled());
        assertNull(properties.getValue(ENABLED_KEY));
    }

    public void testBoxNameRoundTripsPerRepositoryId() {
        RepositoryId one = RepositoryId.newId();
        RepositoryId other = RepositoryId.newId();
        String oneKey = watch("drydock.workspace." + one + ".box");
        String otherKey = watch("drydock.workspace." + other + ".box");
        PropertiesComponent properties = PropertiesComponent.getInstance();
        assertTrue(WorkspaceSettings.box(one).isEmpty());
        assertTrue(WorkspaceSettings.box(other).isEmpty());

        WorkspaceSettings.setBox(one, "drydock-profiling-backend");
        assertEquals("drydock-profiling-backend", WorkspaceSettings.box(one).orElseThrow());
        assertEquals("drydock-profiling-backend", properties.getValue(oneKey));
        // Per-repository keys: the other repo is untouched.
        assertNull(properties.getValue(otherKey));
        assertTrue(WorkspaceSettings.box(other).isEmpty());

        // A re-created generation replaces the override in place.
        WorkspaceSettings.setBox(one, "drydock-profiling-backend-2");
        assertEquals("drydock-profiling-backend-2", WorkspaceSettings.box(one).orElseThrow());

        // A blank value clears the key instead of storing whitespace.
        WorkspaceSettings.setBox(one, "   ");
        assertTrue(WorkspaceSettings.box(one).isEmpty());
        assertNull(properties.getValue(oneKey));
    }

    // --- the used-names history: never reuse a name the mapping pointed at (INV-25) ---

    public void testEveryMappingWriteIsRememberedInTheUsedNamesHistory() {
        RepositoryId one = RepositoryId.newId();
        String historyKey = watch("drydock.workspace." + one + ".usedBoxes");
        PropertiesComponent properties = PropertiesComponent.getInstance();
        assertTrue(WorkspaceSettings.usedBoxes(one).isEmpty());

        // The repo used three generations: base, -2, then -3.
        WorkspaceSettings.setBox(one, "drydock-profiling-backend");
        WorkspaceSettings.setBox(one, "drydock-profiling-backend-2");
        WorkspaceSettings.setBox(one, "drydock-profiling-backend-3");
        assertEquals(List.of("drydock-profiling-backend", "drydock-profiling-backend-2",
                "drydock-profiling-backend-3"), WorkspaceSettings.usedBoxes(one));

        // Re-recording the current name adds nothing.
        WorkspaceSettings.setBox(one, "drydock-profiling-backend-3");
        assertEquals(3, WorkspaceSettings.usedBoxes(one).size());

        // Clearing the override clears the mapping but NEVER the history:
        // an earlier generation stays unusable for a new box even after the
        // old boxes have left workspaces list.
        WorkspaceSettings.setBox(one, "");
        assertTrue(WorkspaceSettings.box(one).isEmpty());
        assertEquals(List.of("drydock-profiling-backend", "drydock-profiling-backend-2",
                "drydock-profiling-backend-3"), WorkspaceSettings.usedBoxes(one));
        assertNull(properties.getValue(watch("drydock.workspace." + one + ".box")));
    }

    public void testAMalformedUsedNamesHistoryDecodesToItsValidEntriesOnly() {
        RepositoryId one = RepositoryId.newId();
        PropertiesComponent properties = PropertiesComponent.getInstance();
        // Same leniency as the workspaceBox row member: a malformed entry is
        // skipped, never a reason to fail the launch.
        properties.setValue(watch("drydock.workspace." + one + ".usedBoxes"),
                "Bad_Box!,  drydock-valid-2 ,,drydock-valid-3, -starts-with-dash");
        assertEquals(List.of("drydock-valid-2", "drydock-valid-3"), WorkspaceSettings.usedBoxes(one));
    }

    public void testClonePathRoundTripsPerRepositoryId() {
        RepositoryId one = RepositoryId.newId();
        RepositoryId other = RepositoryId.newId();
        String oneKey = watch("drydock.workspace." + one + ".clone");
        String otherKey = watch("drydock.workspace." + other + ".clone");
        PropertiesComponent properties = PropertiesComponent.getInstance();

        assertTrue(WorkspaceSettings.clonePath(one).isEmpty());
        assertTrue(WorkspaceSettings.clonePath(other).isEmpty());

        WorkspaceSettings.setClonePath(one, "/home/me/src/profiling-backend");
        assertEquals("/home/me/src/profiling-backend", WorkspaceSettings.clonePath(one).orElseThrow());
        assertEquals("/home/me/src/profiling-backend", properties.getValue(oneKey));
        assertNull(properties.getValue(otherKey));
        assertTrue(WorkspaceSettings.clonePath(other).isEmpty());

        // Discovery re-runs update the cached path in place.
        WorkspaceSettings.setClonePath(one, "/home/me/src/profiling-backend-v2");
        assertEquals("/home/me/src/profiling-backend-v2", WorkspaceSettings.clonePath(one).orElseThrow());

        // A null value clears the key.
        WorkspaceSettings.setClonePath(one, null);
        assertTrue(WorkspaceSettings.clonePath(one).isEmpty());
        assertNull(properties.getValue(oneKey));
    }

    // --- the clone cache is trusted only for the box it was recorded for ---

    public void testAClonePathIsTrustedOnlyForTheBoxItWasRecordedFor() {
        RepositoryId one = RepositoryId.newId();
        String cloneKey = watch("drydock.workspace." + one + ".clone");
        String cloneBoxKey = watch("drydock.workspace." + one + ".cloneBox");
        PropertiesComponent properties = PropertiesComponent.getInstance();

        // A clone discovered on the first generation is served to that box…
        WorkspaceSettings.recordDiscoveredClone(one, "drydock-profiling-backend",
                "/home/u/dev/profiling-backend");
        assertEquals(Optional.of("/home/u/dev/profiling-backend"),
                WorkspaceSettings.clonePathFor(one, "drydock-profiling-backend"));
        assertEquals("drydock-profiling-backend", WorkspaceSettings.cloneBox(one).orElseThrow());

        // …and NOT to a re-created generation: the fresh box discovers its
        // own clone instead of inheriting the old box's path.
        assertTrue(WorkspaceSettings.clonePathFor(one, "drydock-profiling-backend-2").isEmpty());
        // The raw .clone key keeps the last recorded path for the dialog to show.
        assertEquals(Optional.of("/home/u/dev/profiling-backend"), WorkspaceSettings.clonePath(one));

        // The fresh generation's own discovery re-stamps the cache.
        WorkspaceSettings.recordDiscoveredClone(one, "drydock-profiling-backend-2",
                "/home/u/dev2/profiling-backend");
        assertEquals(Optional.of("/home/u/dev2/profiling-backend"),
                WorkspaceSettings.clonePathFor(one, "drydock-profiling-backend-2"));
        assertTrue(WorkspaceSettings.clonePathFor(one, "drydock-profiling-backend").isEmpty());

        // The dialog's manual override is stamped with the mapping's current
        // box, so it is trusted for that box — and, after a re-create, not
        // carried into the fresh generation.
        WorkspaceSettings.setBox(one, "drydock-profiling-backend-3");
        WorkspaceSettings.setClonePath(one, "/home/u/manual/profiling-backend");
        assertEquals(Optional.of("/home/u/manual/profiling-backend"),
                WorkspaceSettings.clonePathFor(one, "drydock-profiling-backend-3"));
        assertTrue(WorkspaceSettings.clonePathFor(one, "drydock-profiling-backend-4").isEmpty());

        // An invalid or absent stamp trusts the cached path for NO box: a
        // legacy .clone value from before the stamp existed is re-discovered.
        properties.setValue(cloneBoxKey, "Not A Box!");
        assertTrue(WorkspaceSettings.cloneBox(one).isEmpty());
        assertTrue(WorkspaceSettings.clonePathFor(one, "drydock-profiling-backend-3").isEmpty());
        properties.setValue(cloneBoxKey, null);
        assertTrue(WorkspaceSettings.clonePathFor(one, "drydock-profiling-backend-3").isEmpty());
        // The dialog still reads the raw override.
        assertEquals(Optional.of("/home/u/manual/profiling-backend"), WorkspaceSettings.clonePath(one));
        assertNotNull(properties.getValue(cloneKey));

        // Clearing the clone override clears both keys.
        WorkspaceSettings.setClonePath(one, null);
        assertTrue(WorkspaceSettings.clonePath(one).isEmpty());
        assertNull(properties.getValue(cloneKey));
        assertNull(properties.getValue(cloneBoxKey));
    }
}
