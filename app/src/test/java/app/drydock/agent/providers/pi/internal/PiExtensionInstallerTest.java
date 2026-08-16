package app.drydock.agent.providers.pi.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiExtensionInstallerTest {

    @Test
    void writesTheSourceOwnerOnlyInAnOwnerOnlyDirectory(@TempDir Path state) throws IOException {
        Path written = new PiExtensionInstaller(state).install();

        assertEquals(state.resolve("pi").resolve("drydock-mcp.ts"), written);
        assertEquals(PiExtensionSource.SOURCE, Files.readString(written));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(written)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(written.getParent())));
    }

    @Test
    void rewriteReplacesStaleContentAndLeavesNoTempFile(@TempDir Path state) throws IOException {
        PiExtensionInstaller installer = new PiExtensionInstaller(state);
        Path written = installer.install();
        Files.writeString(written, "// stale from an older drydock");

        installer.install();

        assertEquals(PiExtensionSource.SOURCE, Files.readString(written));
        try (var entries = Files.list(written.getParent())) {
            assertEquals(List.of("drydock-mcp.ts"),
                    entries.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void tightensADirectoryLeftLooseByAnEarlierRun(@TempDir Path state) throws IOException {
        Path dir = Files.createDirectories(state.resolve("pi"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));

        new PiExtensionInstaller(state).install();

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
    }

    @Test
    void concurrentInstallsAllSucceedAndAgreeOnTheContent(@TempDir Path state) throws Exception {
        PiExtensionInstaller installer = new PiExtensionInstaller(state);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Path>> calls = java.util.Collections.nCopies(8, installer::install);
            for (Future<Path> f : pool.invokeAll(calls)) {
                assertEquals(state.resolve("pi").resolve("drydock-mcp.ts"), f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(PiExtensionSource.SOURCE, Files.readString(state.resolve("pi").resolve("drydock-mcp.ts")));
    }

    @Test
    void anUnwritableStateDirectoryThrowsRatherThanReturningAPathToNothing(@TempDir Path state) throws IOException {
        Path readOnly = Files.createDirectories(state.resolve("locked"));
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThrows(IOException.class, () -> new PiExtensionInstaller(readOnly).install());
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void theSourceIsAPlausibleExtension() {
        assertTrue(PiExtensionSource.SOURCE.contains("export default"),
                "pi loads an extension by calling its default export");
    }
}
