package app.drydock.agent.identity;

import app.drydock.domain.ManagedSessionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentIdentityPipelineTest {

    private final ManagedSessionId session = ManagedSessionId.newId();

    @Test
    void prepareMintsTheEnvFileAndTheWrapper(@TempDir Path base) throws Exception {
        AgentIdentityPipeline pipeline = pipeline(base, "GH_TOKEN=tok-123\n");

        AgentIdentityPipeline.Session prepared =
                pipeline.prepare(session, "glue mint", base, Optional.of("https://github.com/OWNER/REPO.git"));

        assertTrue(Files.exists(prepared.envFile()));
        assertTrue(Files.exists(prepared.wrapperBin().resolve("gh")));
        Map<String, String> variables = rendered(prepared.envFile());
        assertEquals("tok-123", variables.get("GH_TOKEN"));
    }

    @Test
    void theCredentialCommandTravelsInTheEnvFileForTheWrapper(@TempDir Path base) throws Exception {
        AgentIdentityPipeline pipeline = pipeline(base, "GH_TOKEN=tok\n");

        AgentIdentityPipeline.Session prepared =
                pipeline.prepare(session, "my-glue --mint", base, Optional.empty());

        assertEquals("my-glue --mint",
                rendered(prepared.envFile()).get(AgentIdentitySpec.CREDENTIAL_COMMAND_VARIABLE));
    }

    @Test
    void gitConfigEntriesAreAppendedAfterTheMintedVariables(@TempDir Path base) throws Exception {
        AgentIdentityPipeline pipeline = pipeline(base, "GIT_CREDENTIAL_HELPER_SPEC=!/opt/glue/helper\n");

        AgentIdentityPipeline.Session prepared =
                pipeline.prepare(session, "glue", base, Optional.of("git@github.com:OWNER/REPO.git"));

        Map<String, String> variables = rendered(prepared.envFile());
        assertEquals("3", variables.get("GIT_CONFIG_COUNT"));
        assertEquals("credential.helper", variables.get("GIT_CONFIG_KEY_0"));
        assertEquals("", variables.get("GIT_CONFIG_VALUE_0"));
        assertEquals("credential.helper", variables.get("GIT_CONFIG_KEY_1"));
        assertEquals("!/opt/glue/helper", variables.get("GIT_CONFIG_VALUE_1"));
        assertEquals("url.https://github.com/OWNER/REPO.git.insteadOf", variables.get("GIT_CONFIG_KEY_2"));
        assertEquals("git@github.com:OWNER/REPO.git", variables.get("GIT_CONFIG_VALUE_2"));
    }

    @Test
    void aFailedOriginLookupRejectsAConfiguredCredentialHelper(@TempDir Path base) {
        AgentIdentityPipeline pipeline = pipeline(base, "GIT_CREDENTIAL_HELPER_SPEC=!/opt/glue/helper\\n");

        AgentIdentityException thrown = assertThrows(AgentIdentityException.class,
                () -> pipeline.prepare(session, "glue", base, Optional.empty(), true));

        assertTrue(thrown.getMessage().contains("repository origin could not be resolved"), thrown.getMessage());
        assertTrue(!Files.exists(base.resolve("identity").resolve("env").resolve(session + ".env")));
    }

    @Test
    void aFailedOriginLookupDoesNotRejectIdentityWithoutAHelper(@TempDir Path base) throws Exception {
        AgentIdentityPipeline pipeline = pipeline(base, "GH_TOKEN=tok\\n");

        AgentIdentityPipeline.Session prepared = pipeline.prepare(session, "glue", base, Optional.empty(), true);

        assertEquals("0", rendered(prepared.envFile()).get("GIT_CONFIG_COUNT"));
    }

    @Test
    void withoutAHelperSpecThereIsNoGitConfigPlumbing(@TempDir Path base) throws Exception {
        AgentIdentityPipeline pipeline = pipeline(base, "GH_TOKEN=tok\n");

        AgentIdentityPipeline.Session prepared =
                pipeline.prepare(session, "glue", base, Optional.of("git@github.com:OWNER/REPO.git"));

        assertEquals("0", rendered(prepared.envFile()).get("GIT_CONFIG_COUNT"));
    }

    @Test
    void plumbingOverridesAMintedVariableOfTheSameName(@TempDir Path base) throws Exception {
        // A credential command that mints GIT_CONFIG_COUNT must not break the
        // injection: the plumbing is written after the minted variables.
        AgentIdentityPipeline pipeline = pipeline(base, "GIT_CONFIG_COUNT=99\nGH_TOKEN=tok\n");

        AgentIdentityPipeline.Session prepared =
                pipeline.prepare(session, "glue", base, Optional.empty());

        assertEquals("0", rendered(prepared.envFile()).get("GIT_CONFIG_COUNT"));
    }

    @Test
    void aFailedCommandFailsThePreparation(@TempDir Path base) {
        AgentIdentityPipeline pipeline = pipeline(base, (command, directory) -> {
            throw new AgentIdentityException("boom");
        });

        AgentIdentityException thrown = assertThrows(AgentIdentityException.class,
                () -> pipeline.prepare(session, "glue", base, Optional.empty()));

        assertEquals("boom", thrown.getMessage());
    }

    @Test
    void thePrefixSourcesDeletesThenExecs() {
        String prefix = AgentIdentityPipeline.prefix(Path.of("/base/identity/env/abc.env"), "claude --do");

        assertEquals("set -ea; . '/base/identity/env/abc.env'; rm -f '/base/identity/env/abc.env'; exec claude --do",
                prefix);
    }

    /** A pipeline whose credential command is a canned line of output. */
    private static AgentIdentityPipeline pipeline(Path base, String cannedOutput) {
        return new AgentIdentityPipeline((command, directory) -> AgentIdentitySpec.parse(cannedOutput),
                new AgentIdentityEnvFile(base), base);
    }

    private static AgentIdentityPipeline pipeline(Path base,
            AgentIdentityPipeline.CredentialRunner stub) {
        return new AgentIdentityPipeline(stub, new AgentIdentityEnvFile(base), base);
    }

    /** Reads an env file back into its variables (single-quoted values). */
    private static Map<String, String> rendered(Path envFile) throws Exception {
        Map<String, String> variables = new LinkedHashMap<>();
        for (String line : Files.readString(envFile).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            String key = line.substring(0, separator);
            String quoted = line.substring(separator + 1);
            assertTrue(quoted.startsWith("'") && quoted.endsWith("'"), line);
            variables.put(key, quoted.substring(1, quoted.length() - 1).replace("'\\''", "'"));
        }
        return variables;
    }
}
