package app.drydock.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalEditorLauncherTest {

    @TempDir
    Path tempDir;

    @Test
    void substitutesFileIntoEveryTemplateArgument() throws IOException, InterruptedException {
        // A path containing a space, to prove this never goes through a shell
        // string (plan section 21): a shell would need explicit quoting, but
        // ProcessBuilder's argument-list form does not.
        Path target = Files.createDirectory(tempDir.resolve("dir with spaces"));
        Path marker = tempDir.resolve("marker.txt");

        // "cp -R {file} <marker-dir>" as the template: proves {file} is
        // substituted with the exact path, unsplit, as a single argument.
        Path destinationDir = Files.createDirectory(tempDir.resolve("dest"));
        ExternalEditorLauncher launcher = new ExternalEditorLauncher(
                List.of("/bin/cp", "-R", "{file}", destinationDir.toString()));

        launcher.open(target);

        // cp runs asynchronously (ProcessBuilder#start does not wait); give
        // it a moment, then assert the directory was copied under its own
        // (space-containing) name -- proof the whole path arrived intact as
        // one argument.
        Path copied = destinationDir.resolve("dir with spaces");
        for (int i = 0; i < 50 && !Files.exists(copied); i++) {
            Thread.sleep(20);
        }
        assertTrue(Files.exists(copied), "expected " + copied + " to exist after cp completed");
    }

    @Test
    void rejectsAnEmptyTemplate() {
        assertThrows(IllegalArgumentException.class, () -> new ExternalEditorLauncher(List.of()));
    }

    @Test
    void defaultTemplateIsCodeFile() {
        assertEquals(List.of("code", "{file}"), ExternalEditorLauncher.DEFAULT_TEMPLATE);
    }
}
