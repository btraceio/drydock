package app.drydock.intellij.workspace;

import app.drydock.domain.RepositoryId;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

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
}
