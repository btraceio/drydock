package app.drydock.intellij.settings;

import app.drydock.agent.api.AgentKind;
import app.drydock.editorqa.EditorAskHarnessPolicy;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import org.junit.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The editor Q&A settings as stored in the IDE's application properties:
 * the harness choice and the per-harness model overrides round-tripping
 * under their exact names, key families disjoint from every other Drydock
 * settings family (INV-3 -- Q&A recording must not touch the ordinary
 * sessions' defaults, and only this class's keys may move).
 */
public class EditorAskSettingsTest extends BasePlatformTestCase {

    private static final String HARNESS_KEY = "drydock.editorAsk.harness";

    private final List<String> touchedKeys = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Start from a fresh install: a concurrent run of this suite shares
        // this test sandbox's properties file, so a previous run's leftovers
        // (e.g. from a test that failed mid-way) must not leak in.
        clearKeys();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            clearKeys();
        } finally {
            super.tearDown();
        }
    }

    private void clearKeys() {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        properties.unsetValue(HARNESS_KEY);
        for (AgentKind kind : new AgentKind[] { AgentKind.PI, AgentKind.CLAUDE, AgentKind.CODEX }) {
            properties.unsetValue(EditorAskSettings.modelKey(kind));
        }
        for (String key : touchedKeys) {
            properties.unsetValue(key);
        }
    }

    private String watch(String key) {
        touchedKeys.add(key);
        return key;
    }

    // --- the harness choice ---

    public void testHarnessDefaultsToAutoStoringNothing() {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        properties.unsetValue(watch(HARNESS_KEY));

        assertEquals(EditorAskHarnessPolicy.Choice.AUTO, EditorAskSettings.harness());
        assertNull("a fresh install must store no choice", properties.getValue(HARNESS_KEY));
    }

    public void testHarnessRoundTripsAndAutoUnsetsTheKey() {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        watch(HARNESS_KEY);

        EditorAskSettings.setHarness(EditorAskHarnessPolicy.Choice.PI);
        assertEquals("pi", properties.getValue(HARNESS_KEY));
        assertEquals(EditorAskHarnessPolicy.Choice.PI, EditorAskSettings.harness());

        EditorAskSettings.setHarness(EditorAskHarnessPolicy.Choice.CLAUDE);
        assertEquals(EditorAskHarnessPolicy.Choice.CLAUDE, EditorAskSettings.harness());

        EditorAskSettings.setHarness(EditorAskHarnessPolicy.Choice.CODEX);
        assertEquals(EditorAskHarnessPolicy.Choice.CODEX, EditorAskSettings.harness());

        // Writing the default unsets the key, exactly like WorkspaceSettings'
        // opt-in flag: turning Q&A back to Auto restores a fresh install.
        EditorAskSettings.setHarness(EditorAskHarnessPolicy.Choice.AUTO);
        assertEquals(EditorAskHarnessPolicy.Choice.AUTO, EditorAskSettings.harness());
        assertNull(properties.getValue(HARNESS_KEY));
    }

    public void testAStoredNonQaNameDecodesToAuto() {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        properties.setValue(watch(HARNESS_KEY), "terminal");
        assertEquals("terminal is an ordinary kind, never a Q&A choice",
                EditorAskHarnessPolicy.Choice.AUTO, EditorAskSettings.harness());
        properties.setValue(HARNESS_KEY, "nonsense");
        assertEquals(EditorAskHarnessPolicy.Choice.AUTO, EditorAskSettings.harness());
    }

    // --- the settings page's harness combo (apply/reset/isModified agree) ---

    public void testPageAppliesAPinnedHarnessAndResetRestoresIt() throws Exception {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        watch(HARNESS_KEY);
        DrydockConfigurable page = new DrydockConfigurable();
        try {
            page.createComponent();
            assertEquals(EditorAskHarnessPolicy.Choice.AUTO, page.selectedAskHarness());
            assertFalse(page.isModified());

            page.selectAskHarness(EditorAskHarnessPolicy.Choice.CLAUDE);
            assertTrue(page.isModified());
            page.apply();
            assertEquals("claude", properties.getValue(HARNESS_KEY));
            assertEquals(EditorAskHarnessPolicy.Choice.CLAUDE, EditorAskSettings.harness());
            assertFalse("a stored pin must not read as modified", page.isModified());

            // Re-applying an untouched page keeps the pin (no silent unset).
            page.apply();
            assertEquals("claude", properties.getValue(HARNESS_KEY));

            page.selectAskHarness(EditorAskHarnessPolicy.Choice.PI);
            page.reset();
            assertEquals(EditorAskHarnessPolicy.Choice.CLAUDE, page.selectedAskHarness());
        } finally {
            page.disposeUIResources();
        }

        // Reopen: a fresh page preselects the stored pin.
        DrydockConfigurable reopened = new DrydockConfigurable();
        try {
            reopened.createComponent();
            assertEquals(EditorAskHarnessPolicy.Choice.CLAUDE, reopened.selectedAskHarness());
            assertFalse(reopened.isModified());
        } finally {
            reopened.disposeUIResources();
        }
    }

    // --- the per-harness model overrides ---

    public void testModelsRoundTripPerHarnessAndBlankClears() {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        String piKey = watch("drydock.editorAsk.model.pi");
        String claudeKey = watch("drydock.editorAsk.model.claude");
        String codexKey = watch("drydock.editorAsk.model.codex");

        assertTrue(EditorAskSettings.model(AgentKind.PI).isEmpty());

        EditorAskSettings.setModel(AgentKind.PI, " pi-large ");
        assertEquals("pi-large", EditorAskSettings.model(AgentKind.PI).orElseThrow());
        assertEquals("pi-large", properties.getValue(piKey));

        // Per-harness keys: writing Pi's model touches no other harness.
        assertNull(properties.getValue(claudeKey));
        assertNull(properties.getValue(codexKey));
        assertTrue(EditorAskSettings.model(AgentKind.CLAUDE).isEmpty());

        EditorAskSettings.setModel(AgentKind.CLAUDE, "claude-sonnet");
        EditorAskSettings.setModel(AgentKind.CODEX, "gpt-5");
        assertEquals(Optional.of("claude-sonnet"), EditorAskSettings.model(AgentKind.CLAUDE));
        assertEquals(Optional.of("gpt-5"), EditorAskSettings.model(AgentKind.CODEX));

        // A blank or null value clears the key instead of storing whitespace.
        EditorAskSettings.setModel(AgentKind.PI, "   ");
        assertTrue(EditorAskSettings.model(AgentKind.PI).isEmpty());
        assertNull(properties.getValue(piKey));
        EditorAskSettings.setModel(AgentKind.CLAUDE, null);
        assertNull(properties.getValue(claudeKey));
    }

    public void testTerminalCarriesNoModelKey() {
        assertTrue(EditorAskSettings.model(AgentKind.TERMINAL).isEmpty());
        assertNull(EditorAskSettings.modelKey(AgentKind.TERMINAL));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> EditorAskSettings.setModel(AgentKind.TERMINAL, "whatever"));
    }

    // --- Q&A settings leave the ordinary settings families untouched (INV-3) ---

    public void testQaWritesTouchOnlyQaKeys() {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        String workspaceEnabled = watch("drydock.workspaceSessions.enabled");
        String ordinaryBox = watch("drydock.workspace." + "some-repo" + ".box");
        String ordinaryClone = watch("drydock.workspace." + "some-repo" + ".clone");

        EditorAskSettings.setHarness(EditorAskHarnessPolicy.Choice.PI);
        EditorAskSettings.setModel(AgentKind.PI, "pi-large");
        EditorAskSettings.setModel(AgentKind.CLAUDE, "claude-sonnet");
        EditorAskSettings.setModel(AgentKind.CODEX, "gpt-5");

        // The ordinary last-used harness/model/tiers live in state.json's
        // RepositorySettings, and the workspace-box settings have their own
        // key family: no Q&A write may land in either.
        assertNull(properties.getValue(workspaceEnabled));
        assertNull(properties.getValue(ordinaryBox));
        assertNull(properties.getValue(ordinaryClone));
        // And everything the Q&A writes sits under its own prefix.
        for (String key : new String[] { HARNESS_KEY, "drydock.editorAsk.model.pi",
                "drydock.editorAsk.model.claude", "drydock.editorAsk.model.codex" }) {
            assertNotNull("Q&A write must be visible under its own key", properties.getValue(key));
            assertTrue(key.startsWith("drydock.editorAsk."));
        }
    }
}
