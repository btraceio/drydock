package app.drydock.agent.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shipped {@code gh} wrapper script itself, against fake credential
 * commands and a fake gh on PATH, so the refresh contract is tested as the
 * session will actually execute it: wrapper first on PATH, wrapper drops its
 * own directory, re-invokes the credential command, exports the fresh
 * {@code GH_TOKEN}, execs the next gh.
 */
class GhWrapperScriptTest {

    @Test
    void execsTheNextGhWithTheFreshlyMintedToken(@TempDir Path base) throws Exception {
        Path bin = AgentIdentityWrapper.install(base);
        Path fake = fakeGh(base, """
                #!/bin/sh
                echo "gh called with token [$GH_TOKEN] and args [$*]"
                """);
        Map<String, String> environment = environment(bin, fake, "echo GH_TOKEN=fresh-1");

        Run run = run("pr list --limit 1", base, environment);

        assertEquals(0, run.exitCode());
        assertTrue(run.stdout().contains("token [fresh-1]"), run.stdout());
        assertTrue(run.stdout().contains("args [pr list --limit 1]"), run.stdout());
    }

    @Test
    void removesItsDirectoryByValueWhenItIsNotFirstInPath(@TempDir Path base) throws Exception {
        Path wrapperBin = AgentIdentityWrapper.install(base);
        Path fake = fakeGh(base, "#!/bin/sh\necho token=$GH_TOKEN\n");
        Map<String, String> environment = environment(wrapperBin, fake, "echo GH_TOKEN=fresh");
        environment.put("PATH", fake.getParent() + ":" + wrapperBin + ":" + System.getenv("PATH"));

        Run run = runWrapper(wrapperBin, base, environment, "api", "status");

        assertEquals(0, run.exitCode());
        assertTrue(run.stdout().contains("token=fresh"), run.stdout());
    }

    @Test
    void credentialCommandReceivesEofOnStdin(@TempDir Path base) throws Exception {
        Path bin = AgentIdentityWrapper.install(base);
        Path fake = fakeGh(base, "#!/bin/sh\necho token=$GH_TOKEN\n");
        Map<String, String> environment = environment(bin, fake,
                "if read value; then echo GH_TOKEN=stdin-open; else echo GH_TOKEN=stdin-closed; fi");

        Run run = run("gh api", base, environment);

        assertEquals(0, run.exitCode());
        assertTrue(run.stdout().contains("token=stdin-closed"), run.stdout());
    }

    @Test
    void aTimeoutIsALoudDistinctFailure(@TempDir Path base) throws Exception {
        Path bin = AgentIdentityWrapper.install(base);
        Path fake = fakeGh(base, "#!/bin/sh\necho SHOULD-NOT-RUN\n");
        Path fakeSleep = fake.getParent().resolve("sleep");
        Files.writeString(fakeSleep, "#!/bin/sh\ncase \"$1\" in 30|1) exit 0 ;; *) exec /bin/sleep \"$@\" ;; esac\n");
        Files.setPosixFilePermissions(fakeSleep, java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        Map<String, String> environment = environment(bin, fake, "exec /bin/sleep 60");

        Run run = run("gh api", base, environment);

        assertEquals(97, run.exitCode());
        assertTrue(run.stderr().contains("did not finish within 30s"), run.stderr());
        assertTrue(!run.stdout().contains("SHOULD-NOT-RUN"), run.stdout());
    }

    @Test
    void everyInvocationReMintsTheToken(@TempDir Path base) throws Exception {
        Path bin = AgentIdentityWrapper.install(base);
        Path fake = fakeGh(base, """
                #!/bin/sh
                echo "token=$GH_TOKEN"
                """);
        // A command whose minted token changes on every call: a counter file
        // in the working directory (the command's cwd), so the test needs no
        // shell builtins beyond POSIX.
        Files.writeString(base.resolve("n"), "0");
        Map<String, String> environment = environment(bin, fake,
                "c=$(($(cat n)+1)); echo $c > n; echo GH_TOKEN=minted-$c");

        String first = run("gh api", base, environment).stdout().strip();
        Thread.sleep(10);
        String second = run("gh api", base, environment).stdout().strip();

        assertTrue(first.startsWith("token=minted-"), first);
        assertTrue(second.startsWith("token=minted-"), second);
        assertTrue(!first.equals(second), "two invocations must not share a token: " + first + " vs " + second);
    }

    @Test
    void aFailingCredentialCommandIsALoudFailure(@TempDir Path base) throws Exception {
        Path bin = AgentIdentityWrapper.install(base);
        Path fake = fakeGh(base, "#!/bin/sh\necho SHOULD-NOT-RUN\n");
        Map<String, String> environment = environment(bin, fake, "echo boom >&2; exit 4");

        Run run = run("gh api", base, environment);

        assertEquals(97, run.exitCode());
        assertTrue(run.stderr().contains("credential command failed"), run.stderr());
        assertTrue(!run.stdout().contains("SHOULD-NOT-RUN"), run.stdout());
    }

    @Test
    void aCredentialCommandWithoutGhTokenIsALoudFailure(@TempDir Path base) throws Exception {
        Path bin = AgentIdentityWrapper.install(base);
        Path fake = fakeGh(base, "#!/bin/sh\necho SHOULD-NOT-RUN\n");
        Map<String, String> environment = environment(bin, fake, "echo OTHER_KEY=value");

        Run run = run("gh api", base, environment);

        assertEquals(97, run.exitCode());
        assertTrue(run.stderr().contains("no GH_TOKEN line"), run.stderr());
    }

    @Test
    void aMissingCredentialCommandVariableIsALoudFailure(@TempDir Path base) throws Exception {
        Path bin = AgentIdentityWrapper.install(base);
        Path fake = fakeGh(base, "#!/bin/sh\necho SHOULD-NOT-RUN\n");
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", bin + ":" + fake.getParent() + ":" + System.getenv("PATH"));

        Run run = run("gh api", base, environment);

        assertEquals(97, run.exitCode());
        assertTrue(run.stderr().contains("DRYDOCK_AGENT_CREDENTIAL_COMMAND is not set"), run.stderr());
    }

    @Test
    void theWrapperNeverRecursesIntoItself(@TempDir Path base) throws Exception {
        // PATH holds ONLY the wrapper directory: the next gh in line would be
        // the wrapper again, which must refuse rather than loop.
        Path bin = AgentIdentityWrapper.install(base);
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", bin.toString());
        environment.put("DRYDOCK_AGENT_CREDENTIAL_COMMAND", "echo GH_TOKEN=tok");

        Run run = run("gh api", base, environment);

        assertEquals(97, run.exitCode());
        assertTrue(run.stderr().contains("no gh found on PATH beyond the wrapper itself"), run.stderr());
    }

    private static Path fakeGh(Path base, String script) throws IOException {
        Path dir = Files.createDirectories(base.resolve("fake-bin"));
        Path gh = dir.resolve("gh");
        Files.writeString(gh, script);
        Files.setPosixFilePermissions(gh, java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        return gh;
    }

    private static Map<String, String> environment(Path wrapperBin, Path fakeGh, String credentialCommand) {
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", wrapperBin + ":" + fakeGh.getParent() + ":" + System.getenv("PATH"));
        environment.put("DRYDOCK_AGENT_CREDENTIAL_COMMAND", credentialCommand);
        return environment;
    }

    private static Run run(String ghArguments, Path workingDirectory, Map<String, String> environment)
            throws IOException, InterruptedException {
        return runCommand(List.of("/bin/sh", "-c", "gh " + ghArguments), workingDirectory, environment);
    }

    private static Run runWrapper(Path wrapperBin, Path workingDirectory, Map<String, String> environment,
                                  String... arguments) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>();
        command.add(wrapperBin.resolve("gh").toString());
        command.addAll(List.of(arguments));
        return runCommand(command, workingDirectory, environment);
    }

    private static Run runCommand(List<String> command, Path workingDirectory, Map<String, String> environment)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("the wrapped gh call did not finish within 30s");
        }
        return new Run(process.exitValue(),
                new String(process.getInputStream().readAllBytes()),
                new String(process.getErrorStream().readAllBytes()));
    }

    private record Run(int exitCode, String stdout, String stderr) {
    }
}
