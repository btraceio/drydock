package app.drydock.intellij.terminal;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.providers.pi.PiAgentProvider;
import app.drydock.agent.providers.pi.internal.PiExecutableLocator;

import com.intellij.execution.configurations.PtyCommandLine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The provider command must survive the same PTY and login shell used by agent tabs. */
class PiPromptPtyPlatformTest {

    @Test
    void multilineImplementationPromptReachesPiThroughTheLaunchEnvironment(@TempDir Path dir) throws Exception {
        Path captured = dir.resolve("captured.txt");
        Path fakePi = dir.resolve("pi");
        Files.writeString(fakePi, "#!/bin/sh\nprintf '%s' \"$DRYDOCK_PI_INITIAL_PROMPT\""
                + " > \"${0%/*}/captured.txt\"\n");
        Files.setPosixFilePermissions(fakePi, PosixFilePermissions.fromString("rwxr-xr-x"));
        PiAgentProvider provider = new PiAgentProvider(new PiExecutableLocator(fakePi));
        provider.init(new AgentContext(dir.resolve("state"), dir.resolve("activity"), ForkJoinPool.commonPool()));
        String prompt = ("Implement issue #42.\n\nReview the issue's gates and add `Closes #42`.\n")
                .repeat(48);
        LaunchPlan plan = provider.buildCreateCommand(new CreateContext("issue", "", dir,
                Optional.empty(), Optional.empty(), false, false, Optional.of(prompt)));

        PtyCommandLine commandLine = new PtyCommandLine(ShellCommandAdapter.shellArgv(plan));
        commandLine.withWorkDirectory(dir.toString());
        commandLine.withCharset(StandardCharsets.UTF_8);
        commandLine.withConsoleMode(false);
        Process process = commandLine.createProcess();

        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "the fake Pi launch did not exit");
        assertEquals(0, process.exitValue());
        assertEquals(prompt, new String(Base64.getDecoder().decode(Files.readString(captured)),
                StandardCharsets.UTF_8));
    }
}
