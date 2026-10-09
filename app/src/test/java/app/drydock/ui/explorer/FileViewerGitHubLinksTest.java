package app.drydock.ui.explorer;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import app.drydock.ui.TestStages;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Explorer breadcrumb's GitHub links: they exist only when the checkout's
 * origin is github.com, and aim at the file. The probe is one async
 * {@code git remote get-url origin} per viewer, so the buttons appear a
 * moment after the first open.
 *
 * <p>No commit is created in the fixture: the probe runs {@code remote
 * get-url} only, {@code rev-parse HEAD} runs per click, and this test never
 * clicks -- the URLs' shape is pinned by {@code GitHubLinkServiceTest}, so
 * nothing here can open a browser.</p>
 */
class FileViewerGitHubLinksTest extends FxTest {

    private final StackPane wrapper = new StackPane();
    private FileViewer githubViewer;
    private FileViewer labViewer;
    private Path githubRoot;
    private Path labRoot;

    @Override
    public void start(Stage stage) {
        try {
            githubRoot = repoWithRemote(
                    "https://github.com/DataDog/drydock-fixtures.git");
            labRoot = repoWithRemote("git@gitlab.ddbuild.io:DataDog/profiling-backend.git");
        } catch (Exception e) {
            throw new IllegalStateException("fixture repo setup failed", e);
        }
        interact(() -> {
            githubViewer = new FileViewer(githubRoot);
            labViewer = new FileViewer(labRoot);
            // Show the github viewer first; the test swaps the root's child.
            wrapper.getChildren().setAll(githubViewer);
            Scene scene = new Scene(wrapper, 900, 600);
            scene.getStylesheets().addAll(
                    getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm(),
                    getClass().getResource("/app/drydock/ui/app.css").toExternalForm());
            TestStages.show(stage, scene);
        });
        FxSync.waitForFxEvents();
    }

    private static Path repoWithRemote(String remoteUrl) throws Exception {
        Path root = Files.createTempDirectory("drydock-viewer-github");
        Path file = root.resolve("src/Linked.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class Linked {\n}\n");
        git(root, List.of("init"));
        git(root, List.of("remote", "add", "origin", remoteUrl));
        return root;
    }

    private void showViewer(FileViewer viewer, Path root) throws IOException {
        Path file = root.resolve("src/Linked.java");
        interact(() -> {
            wrapper.getChildren().setAll(viewer);
            viewer.openFile(file, root.relativize(file), OptionalInt.empty(), null);
        });
        FxSync.waitForFxEvents();
    }

    @Test
    void aGithubRemoteRendersBothLinkButtonsInTheBreadcrumb() throws IOException {
        showViewer(githubViewer, githubRoot);
        await(2, "both github.com and github.dev render once the probe answers");
        assertTrue(linkButtons() == 2, "both github.com and github.dev render once the probe answers");
    }

    @Test
    void aNonGithubRemoteRendersNoLinkButtons() throws Exception {
        showViewer(labViewer, labRoot);
        await(0, "the probe knows it is not github");
        assertEquals(0, linkButtons(), "no dead buttons on a non-github remote");
    }

    /** Waits until {@code expected} link buttons show, or ~1.5s pass. */
    private void await(int expected, String why) {
        for (int i = 0; i < 60 && linkButtons() != expected; i++) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            FxSync.waitForFxEvents();
        }
        assertEquals(expected, linkButtons(), why);
    }

    private int linkButtons() {
        int[] count = {0};
        interact(() -> count[0] = wrapper.lookupAll(".file-link-button").size());
        return count[0];
    }

    private static void git(Path cwd, List<String> args) {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(cwd.toString());
        command.addAll(args);
        ProcessResult result;
        try {
            result = ProcessRunner.run(command, null, Duration.ofSeconds(15));
        } catch (Exception e) {
            throw new IllegalStateException("git " + args + " failed", e);
        }
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git " + args + " exited " + result.exitCode()
                    + ": " + result.stderr());
        }
    }
}