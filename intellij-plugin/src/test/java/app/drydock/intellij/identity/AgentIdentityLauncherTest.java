package app.drydock.intellij.identity;

import app.drydock.agent.identity.AgentIdentityEnvFile;
import app.drydock.agent.identity.AgentIdentityException;
import app.drydock.agent.identity.AgentIdentityPipeline;
import app.drydock.agent.identity.AgentIdentitySpec;
import app.drydock.agent.providers.terminal.TerminalAgentProvider;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentIdentityLauncherTest {

    private static final RepositoryId REPO = new RepositoryId(UUID.randomUUID());
    private static final ManagedSessionId SESSION = ManagedSessionId.newId();

    /** The settings store each test builds its launcher against. */
    private final Map<String, String> store = new HashMap<>();

    @Test
    void aDisabledRepositoryLaunchesWithoutInjection(@TempDir Path base) {
        store.put(key(".enabled"), "false");

        assertTrue(launcher(base).prepare(SESSION, base, "claude --do").isEmpty());
    }

    @Test
    void aSessionWithoutARowLaunchesWithoutInjection(@TempDir Path base) {
        store.put(key(".enabled"), "true");
        store.put(key(".credentialCommand"), "glue mint");

        assertTrue(launcher(base).prepare(ManagedSessionId.newId(), base, "claude --do").isEmpty());
    }

    @Test
    void aPlainTerminalSessionLaunchesWithoutInjection(@TempDir Path base) {
        enable();

        assertTrue(launcher(base).prepare(SESSION, base, TerminalAgentProvider.SHELL_COMMAND).isEmpty());
    }

    @Test
    void anEnabledRepositoryWrapsTheCommandInTheSourcingPrefix(@TempDir Path base) throws Exception {
        enable();
        AgentIdentityLauncher launcher = new AgentIdentityLauncher(
                new AgentIdentityPipeline((command, directory) -> AgentIdentitySpec.parse("GH_TOKEN=tok\n"),
                        new AgentIdentityEnvFile(base), base),
                id -> id.equals(SESSION) ? Optional.of(REPO) : Optional.empty(),
                this::settings);

        Optional<AgentIdentityLauncher.Prepared> prepared =
                launcher.prepare(SESSION, base, "claude --do");

        assertTrue(prepared.isPresent());
        String command = prepared.orElseThrow().command();
        assertTrue(command.startsWith("set -ea; . "), command);
        assertTrue(command.contains("; rm -f "), command);
        assertTrue(command.endsWith("; exec claude --do"), command);
        assertTrue(Files.exists(base.resolve("identity").resolve("env").resolve(SESSION + ".env")),
                "the env file exists between preparation and the launch");
        assertTrue(Files.exists(prepared.orElseThrow().wrapperBin().resolve("gh")));
    }

    @Test
    void aFailedMintFailsThePreparationLoudly(@TempDir Path base) {
        enable();
        AgentIdentityLauncher launcher = new AgentIdentityLauncher(
                new AgentIdentityPipeline((command, directory) -> {
                    throw new AgentIdentityException("the mint failed");
                }, new AgentIdentityEnvFile(base), base),
                id -> Optional.of(REPO),
                this::settings);

        AgentIdentityException thrown = assertThrows(AgentIdentityException.class,
                () -> launcher.prepare(SESSION, base, "claude --do"));

        assertEquals("the mint failed", thrown.getMessage());
    }

    @Test
    void aFishOrWindowsShellSkipsTheInjection() {
        assertTrue(AgentIdentityLauncher.posixSkipReason("Mac OS X", "/usr/local/bin/fish") != null);
        assertTrue(AgentIdentityLauncher.posixSkipReason("Windows 11", "cmd.exe") != null);
        assertEquals(null, AgentIdentityLauncher.posixSkipReason("Mac OS X", "/bin/zsh"));
        assertEquals(null, AgentIdentityLauncher.posixSkipReason("Linux", "/bin/bash"));
        assertEquals(null, AgentIdentityLauncher.posixSkipReason("Mac OS X", null));
    }

    private void enable() {
        store.put(key(".enabled"), "true");
        store.put(key(".credentialCommand"), "glue mint");
    }

    private String key(String suffix) {
        return "drydock.agentIdentity." + REPO + suffix;
    }

    private AgentIdentitySettings settings(RepositoryId repository) {
        return new AgentIdentitySettings(
                Boolean.parseBoolean(store.getOrDefault(key(".enabled"), "false")),
                store.getOrDefault(key(".credentialCommand"), ""));
    }

    private AgentIdentityLauncher launcher(Path base) {
        return new AgentIdentityLauncher(new AgentIdentityPipeline(base),
                id -> id.equals(SESSION) ? Optional.of(REPO) : Optional.empty(),
                this::settings);
    }
}
