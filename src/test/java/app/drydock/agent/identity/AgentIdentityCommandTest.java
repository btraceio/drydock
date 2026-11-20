package app.drydock.agent.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentIdentityCommandTest {

    private final AgentIdentityCommand command = new AgentIdentityCommand();

    @Test
    void runsTheCommandInTheSessionWorkingDirectory(@TempDir Path directory) throws Exception {
        AgentIdentitySpec spec = command.run(
                "echo GIT_AUTHOR_NAME=minted; echo minted > ./marker", directory);

        assertEquals(Optional.of("minted"), spec.value(AgentIdentitySpec.GIT_AUTHOR_NAME));
        assertTrue(Files.exists(directory.resolve("marker")), "the command ran in the session working directory");
    }

    @Test
    void aNonZeroExitFailsLoudlyWithTheStderrExcerpt(@TempDir Path directory) {
        AgentIdentityException thrown = assertThrows(AgentIdentityException.class,
                () -> command.run("echo boom >&2; exit 3", directory));

        assertTrue(thrown.getMessage().contains("exit code 3"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("boom"), thrown.getMessage());
    }

    @Test
    void aMalformedOutputFailsLoudly(@TempDir Path directory) {
        AgentIdentityException thrown = assertThrows(AgentIdentityException.class,
                () -> command.run("echo garbage", directory));

        assertTrue(thrown.getMessage().contains("not KEY=VALUE"), thrown.getMessage());
    }

    @Test
    void aCommandExceedingItsTimeoutFailsLoudly(@TempDir Path directory) {
        AgentIdentityCommand patient = new AgentIdentityCommand(Duration.ofSeconds(1));

        AgentIdentityException thrown = assertThrows(AgentIdentityException.class,
                () -> patient.run("sleep 30", directory));

        assertTrue(thrown.getMessage().contains("did not finish within"), thrown.getMessage());
    }

    @Test
    void aStdinReadingCommandSeesEofInsteadOfPrompting(@TempDir Path directory) {
        // discardInput redirects stdin from /dev/null: a command that would
        // prompt for a passphrase must see EOF, not hang until the timeout.
        AgentIdentitySpec spec = command.run("if read -r line; then echo GH_TOKEN=got-input; else echo GH_TOKEN=eof; fi",
                directory);

        assertEquals(Optional.of("eof"), spec.value(AgentIdentitySpec.GH_TOKEN));
    }
}
