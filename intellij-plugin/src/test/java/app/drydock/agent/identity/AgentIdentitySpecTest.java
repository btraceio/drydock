package app.drydock.agent.identity;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentIdentitySpecTest {

    @Test
    void parsesKnownKeysIntoTheirAccessors() {
        AgentIdentitySpec spec = AgentIdentitySpec.parse("""
                GIT_AUTHOR_NAME=Drydock Agent
                GIT_AUTHOR_EMAIL=agent@example.com
                GIT_COMMITTER_NAME=Drydock Agent
                GIT_COMMITTER_EMAIL=agent@example.com
                GIT_CREDENTIAL_HELPER_SPEC=!/opt/glue/helper.sh
                GH_TOKEN=tok-123
                """);

        assertEquals(Optional.of("Drydock Agent"), spec.value(AgentIdentitySpec.GIT_AUTHOR_NAME));
        assertEquals(Optional.of("agent@example.com"), spec.value(AgentIdentitySpec.GIT_AUTHOR_EMAIL));
        assertEquals(Optional.of("Drydock Agent"), spec.value(AgentIdentitySpec.GIT_COMMITTER_NAME));
        assertEquals(Optional.of("agent@example.com"), spec.value(AgentIdentitySpec.GIT_COMMITTER_EMAIL));
        assertEquals(Optional.of("!/opt/glue/helper.sh"), spec.value(AgentIdentitySpec.GIT_CREDENTIAL_HELPER_SPEC));
        assertEquals(Optional.of("tok-123"), spec.value(AgentIdentitySpec.GH_TOKEN));
    }

    @Test
    void valuesMayCarryEqualsSignsAndSpaces() {
        AgentIdentitySpec spec = AgentIdentitySpec.parse("GIT_AUTHOR_NAME=Bot = Agent\n");

        assertEquals(Optional.of("Bot = Agent"), spec.value(AgentIdentitySpec.GIT_AUTHOR_NAME));
    }

    @Test
    void aTrailingCarriageReturnIsStripped() {
        AgentIdentitySpec spec = AgentIdentitySpec.parse("GH_TOKEN=tok\r\n");

        assertEquals(Optional.of("tok"), spec.value(AgentIdentitySpec.GH_TOKEN));
    }

    @Test
    void blankLinesAreSkipped() {
        AgentIdentitySpec spec = AgentIdentitySpec.parse("\nGH_TOKEN=tok\n\n");

        assertEquals(Optional.of("tok"), spec.value(AgentIdentitySpec.GH_TOKEN));
    }

    @Test
    void aRepeatedKeyKeepsItsLastValue() {
        AgentIdentitySpec spec = AgentIdentitySpec.parse("GH_TOKEN=first\nGH_TOKEN=second\n");

        assertEquals(Optional.of("second"), spec.value(AgentIdentitySpec.GH_TOKEN));
    }

    @Test
    void unknownKeysAreKeptForTheSessionEnvironment() {
        AgentIdentitySpec spec = AgentIdentitySpec.parse("GLUE_EXTRA_SETTING=on\nGH_TOKEN=tok\n");

        assertEquals(Optional.of("on"), spec.value("GLUE_EXTRA_SETTING"));
        assertEquals(2, spec.variables().size());
    }

    @Test
    void anUnknownKeyAloneIsStillAValidSpec() {
        AgentIdentitySpec spec = AgentIdentitySpec.parse("GLUE_EXTRA_SETTING=on\n");

        assertEquals(Map.of("GLUE_EXTRA_SETTING", "on"), spec.variables());
    }

    @Test
    void emptyOutputFailsLoudly() {
        AgentIdentityException thrown = assertThrows(AgentIdentityException.class,
                () -> AgentIdentitySpec.parse(""));

        assertTrue(thrown.getMessage().contains("no KEY=VALUE lines"), thrown.getMessage());
    }

    @Test
    void aLineWithoutAnEqualsSignFailsLoudly() {
        AgentIdentityException thrown = assertThrows(AgentIdentityException.class,
                () -> AgentIdentitySpec.parse("GH_TOKEN\n"));

        assertTrue(thrown.getMessage().contains("not KEY=VALUE"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("GH_TOKEN"), thrown.getMessage());
    }

    @Test
    void anInvalidVariableNameFailsLoudly() {
        AgentIdentityException thrown = assertThrows(AgentIdentityException.class,
                () -> AgentIdentitySpec.parse("1TOKEN=abc\n"));

        assertTrue(thrown.getMessage().contains("invalid variable name"), thrown.getMessage());
    }

    @Test
    void anEmptyValueFailsLoudly() {
        AgentIdentityException thrown = assertThrows(AgentIdentityException.class,
                () -> AgentIdentitySpec.parse("GH_TOKEN=\n"));

        assertTrue(thrown.getMessage().contains("empty value"), thrown.getMessage());
    }

    @Test
    void aMissingValueFailsLoudly() {
        AgentIdentityException thrown = assertThrows(AgentIdentityException.class,
                () -> AgentIdentitySpec.parse("GH_TOKEN\n"));

        assertTrue(thrown.getMessage().contains("not KEY=VALUE"), thrown.getMessage());
    }
}
