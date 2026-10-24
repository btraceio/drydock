package app.drydock.domain;

import app.drydock.agent.api.AgentKind;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositorySettingsTest {

    @Test
    void aBlankModelClearsAnExistingEntry() {
        RepositorySettings settings = RepositorySettings.DEFAULT
                .withLastUsedModel(AgentKind.PI, Optional.of("gpt-5.4"))
                .withLastUsedModel(AgentKind.PI, Optional.of("  "));
        assertEquals(Optional.empty(), settings.lastUsedModel(AgentKind.PI));
        assertTrue(settings.lastUsedModels().isEmpty());
    }

    @Test
    void anEmptyStringOnAnEmptyMapStoresNothing() {
        RepositorySettings settings = RepositorySettings.DEFAULT.withLastUsedModel(AgentKind.PI, Optional.of(""));
        assertTrue(settings.lastUsedModels().isEmpty());
        assertEquals(Optional.empty(), settings.lastUsedModel(AgentKind.PI));
    }

    @Test
    void aNonBlankModelIsStoredVerbatim() {
        RepositorySettings settings = RepositorySettings.DEFAULT.withLastUsedModel(AgentKind.PI, Optional.of(" m "));
        assertEquals(Optional.of(" m "), settings.lastUsedModel(AgentKind.PI));
    }

    @Test
    void aBlankModelRemovesOnlyItsOwnKindAndKeepsTheAgent() {
        RepositorySettings settings = RepositorySettings.DEFAULT
                .withLastUsedModel(AgentKind.PI, Optional.of("pi-model"))
                .withLastUsedModel(AgentKind.CLAUDE, Optional.of("opus"))
                .withLastUsedAgent(AgentKind.CLAUDE)
                .withLastUsedModel(AgentKind.PI, Optional.of(" "));
        assertEquals(Map.of(AgentKind.CLAUDE, "opus"), settings.lastUsedModels());
        assertEquals(Optional.of(AgentKind.CLAUDE), settings.lastUsedAgent());
    }

    @Test
    void anEmptyModelStillRemovesTheEntry() {
        RepositorySettings settings = RepositorySettings.DEFAULT
                .withLastUsedModel(AgentKind.PI, Optional.of("pi-model"))
                .withLastUsedModel(AgentKind.PI, Optional.empty());
        assertTrue(settings.lastUsedModels().isEmpty());
    }
}
