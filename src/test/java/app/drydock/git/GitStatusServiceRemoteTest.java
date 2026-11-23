package app.drydock.git;

import app.drydock.domain.SshRemote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitStatusServiceRemoteTest {

    @TempDir
    Path tmp;

    private final SshRemote remote = new SshRemote("user@h", "/srv/app");

    private Path fakeSsh(String script) throws IOException {
        Path fake = tmp.resolve("fake-ssh");
        Files.writeString(fake, "#!/bin/sh\n" + script);
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwxr-xr-x"));
        return fake;
    }

    @Test
    void remoteStatusParsesPorcelain() throws IOException {
        // Branch header + one dirty record, NUL-separated like -z emits.
        Path fake = fakeSsh("printf '# branch.head main\\0001 .M N... 100644 100644 100644 x x f\\000'");
        try (GitStatusService service = new GitStatusService(new GitExecutableLocator(), fake.toString())) {
            GitStatus status = service.getStatus(new GitTarget.Remote(remote)).join();
            assertEquals(new GitBranchState.OnBranch("main"), status.branch());
            assertTrue(status.dirty());
        }
    }

    @Test
    void sshExit255BecomesUnreachable() throws IOException {
        Path fake = fakeSsh("echo 'ssh: connect to host h port 22: Operation timed out' >&2; exit 255");
        try (GitStatusService service = new GitStatusService(new GitExecutableLocator(), fake.toString())) {
            CompletionException thrown = assertThrows(CompletionException.class,
                    () -> service.getStatus(new GitTarget.Remote(remote)).join());
            SshUnreachableException unreachable = assertInstanceOf(SshUnreachableException.class, thrown.getCause());
            assertEquals("user@h", unreachable.host());
            assertTrue(unreachable.stderr().contains("Operation timed out"));
        }
    }

    @Test
    void remoteGitErrorStaysGitCommandFailed() throws IOException {
        // Exit 128 comes from git on the far side, not the transport.
        Path fake = fakeSsh("echo 'fatal: not a git repository' >&2; exit 128");
        try (GitStatusService service = new GitStatusService(new GitExecutableLocator(), fake.toString())) {
            CompletionException thrown = assertThrows(CompletionException.class,
                    () -> service.getStatus(new GitTarget.Remote(remote)).join());
            assertInstanceOf(NotAGitRepositoryException.class, thrown.getCause());
        }
    }

    @Test
    void resolveRemoteRootReturnsToplevel() throws IOException {
        Path fake = fakeSsh("printf '/srv/app\\n'");
        try (GitStatusService service = new GitStatusService(new GitExecutableLocator(), fake.toString())) {
            assertEquals("/srv/app", service.resolveRemoteRepositoryRoot(remote).join());
        }
    }
}
