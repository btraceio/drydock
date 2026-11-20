package app.drydock.intellij.identity;

import app.drydock.domain.RepositoryId;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentIdentitySettingsTest {

    private static final RepositoryId ID = new RepositoryId(UUID.randomUUID());

    /** The two, and only the two, properties the record persists. */
    private static Set<String> keys(RepositoryId id) {
        String prefix = "drydock.agentIdentity." + id;
        return Set.of(prefix + ".enabled", prefix + ".credentialCommand");
    }

    @Test
    void everyFieldRoundTripsUnderTheRepositoryPrefix() {
        AgentIdentitySettings settings = new AgentIdentitySettings(true, "  glue mint --repo .  ");
        Map<String, String> store = new HashMap<>();
        settings.write(store::put, ID);

        assertEquals(keys(ID), store.keySet());
        assertEquals("true", store.get("drydock.agentIdentity." + ID + ".enabled"));
        assertEquals("glue mint --repo .", store.get("drydock.agentIdentity." + ID + ".credentialCommand"));

        AgentIdentitySettings read = AgentIdentitySettings.read(store::get, ID);
        assertTrue(read.enabled());
        assertEquals("glue mint --repo .", read.credentialCommand());
        assertEquals(settings, read);
    }

    @Test
    void readDefaultsWhenNothingIsStored() {
        AgentIdentitySettings settings = AgentIdentitySettings.read(key -> null, ID);

        assertFalse(settings.enabled());
        assertEquals("", settings.credentialCommand());
    }

    @Test
    void aDisabledSettingPersistsWithoutACommandTrace() {
        Map<String, String> store = new HashMap<>();
        new AgentIdentitySettings(false, "").write(store::put, ID);

        assertEquals("false", store.get("drydock.agentIdentity." + ID + ".enabled"));
        assertEquals(null, store.get("drydock.agentIdentity." + ID + ".credentialCommand"),
                "a blank command unsets the key");
    }

    @Test
    void refusalNamesTheMissingCommandOnlyWhenEnabled() {
        assertTrue(AgentIdentitySettings.refusal(true, "").isPresent());
        assertTrue(AgentIdentitySettings.refusal(true, "   ").isPresent());
        assertTrue(AgentIdentitySettings.refusal(true, null).isPresent());
        assertTrue(AgentIdentitySettings.refusal(false, "").isEmpty(),
                "a disabled configuration never refuses");
        assertTrue(AgentIdentitySettings.refusal(true, "glue mint").isEmpty());
    }
}
