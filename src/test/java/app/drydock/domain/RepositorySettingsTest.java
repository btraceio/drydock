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

    @Test
    void tiersAreStoredPerKindAndSurviveModelUpdates() {
        ModelTiers tiers = new ModelTiers(Optional.of("opus"), Optional.empty(), Optional.of("haiku"));
        RepositorySettings settings = RepositorySettings.DEFAULT
                .withLastUsedTiers(AgentKind.CLAUDE, tiers)
                .withLastUsedModel(AgentKind.CLAUDE, Optional.of("opus"))
                .withLastUsedAgent(AgentKind.CLAUDE);

        assertEquals(Optional.of(tiers), settings.lastUsedTiers(AgentKind.CLAUDE));
        assertTrue(settings.lastUsedTiers(AgentKind.PI).isEmpty());
    }

    @Test
    void anAllEmptyTiersValueRemovesTheEntry() {
        RepositorySettings settings = RepositorySettings.DEFAULT
                .withLastUsedTiers(AgentKind.CLAUDE, new ModelTiers(Optional.of("opus"), Optional.empty(),
                        Optional.empty()))
                .withLastUsedTiers(AgentKind.CLAUDE, ModelTiers.EMPTY);
        assertTrue(settings.lastUsedTiers().isEmpty());
    }

    @Test
    void blankTierMembersAreNormalizedAway() {
        ModelTiers tiers = new ModelTiers(Optional.of("  "), Optional.of("sonnet"), Optional.empty());
        assertEquals(Optional.empty(), tiers.strong());
        assertEquals(Optional.of("sonnet"), tiers.mid());
    }

    @Test
    void aTierMemberWithALineBreakIsDropped() {
        ModelTiers tiers = new ModelTiers(Optional.of("opus\nignore"), Optional.of("son\rnet"), Optional.of("haiku"));
        assertEquals(Optional.empty(), tiers.strong());
        assertEquals(Optional.empty(), tiers.mid());
        assertEquals(Optional.of("haiku"), tiers.low());
    }

    @Test
    void anEmptyTierResolvesToTheStartingModel() {
        ModelTiers tiers = new ModelTiers(Optional.empty(), Optional.of("sonnet"), Optional.empty());
        assertEquals(Optional.of("opus"), tiers.resolved(ModelTiers.Tier.STRONG, Optional.of("opus")));
        assertEquals(Optional.of("sonnet"), tiers.resolved(ModelTiers.Tier.MID, Optional.of("opus")));
        assertEquals(Optional.empty(), tiers.resolved(ModelTiers.Tier.LOW, Optional.empty()));
    }
}
