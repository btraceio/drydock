package app.drydock.babysit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The detached watcher's command line; the spawn itself is not exercised (it would outlive the test). */
class BabysitWatcherProcessTest {

    @Test
    void theCommandIsNohupTheRunningJavaTheCoreClassPathAndTheGeneration() {
        UUID generation = UUID.fromString("00000000-0000-0000-0000-000000000042");

        List<String> command = BabysitWatcherProcess.command(Optional.of(Path.of("/usr/bin/nohup")),
                Path.of("/jbr/bin/java"), Path.of("/plugin/lib/drydock-core.jar"), Path.of("/config/drydock"),
                generation);

        assertEquals(List.of("/usr/bin/nohup", "/jbr/bin/java", "-Xmx128m", "-XX:+UseSerialGC",
                "-Djava.awt.headless=true", "-cp", "/plugin/lib/drydock-core.jar",
                "app.drydock.babysit.BabysitWatcherMain", "/config/drydock", generation.toString()), command);
        assertEquals("/jbr/bin/java", BabysitWatcherProcess.command(Optional.empty(), Path.of("/jbr/bin/java"),
                Path.of("/cp"), Path.of("/b"), generation).get(0));
    }

    @Test
    void theClassPathEntryComesFromTheClassFilesUrl() throws Exception {
        String resource = "app/drydock/babysit/BabysitWatcherProcess.class";
        assertEquals(Path.of("/plugin/lib/drydock-core.jar"), BabysitWatcherProcess.classPathOf(
                new URL("jar:file:/plugin/lib/drydock-core.jar!/" + resource), resource));
        assertEquals(Path.of("/build/classes/java/core"), BabysitWatcherProcess.classPathOf(
                new URL("file:/build/classes/java/core/" + resource), resource));
        assertThrows(IOException.class, () -> BabysitWatcherProcess.classPathOf(
                new URL("http://example.com/" + resource), resource));
    }

    @Test
    void theJavaAndTheClassPathAreThisRuntimesAndThisCodeSource() throws Exception {
        assertTrue(Files.isExecutable(BabysitWatcherProcess.javaExecutable()));
        Path classPath = BabysitWatcherProcess.classPath();
        assertTrue(Files.exists(classPath), classPath.toString());
        assertTrue(Files.isDirectory(classPath)
                ? Files.exists(classPath.resolve("app/drydock/babysit/BabysitWatcherMain.class"))
                : classPath.toString().endsWith(".jar"), classPath.toString());
        assertEquals(Path.of("/b", "babysit", "watcher.log"), BabysitWatcherProcess.logFile(Path.of("/b")));
    }
}
