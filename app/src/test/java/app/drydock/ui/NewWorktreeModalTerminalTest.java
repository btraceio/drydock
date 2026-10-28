package app.drydock.ui;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.providers.terminal.TerminalAgentProvider;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The TERMINAL guard of the create-worktree modal: a plain shell has no agent
 * to hand a task to, and typed task text would be EXECUTED by the shell the
 * moment the session starts -- so the task field (like the model picker) is
 * hidden when Terminal is the selected harness, and the launch never carries
 * the task regardless of what was typed (see {@code
 * StartSessionModal.taskFor}, the shared guard both modals route through).
 */
class NewWorktreeModalTerminalTest extends FxTest {

    private static Path sharedClone;

    private NewWorktreeModal modal;

    @Override
    public void start(Stage stage) {
        Path clone;
        try {
            clone = sharedClone();
        } catch (Exception e) {
            throw new IllegalStateException("could not build the test clone", e);
        }
        AgentContext ctx = new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"),
                ForkJoinPool.commonPool());
        AgentRegistry registry = new AgentRegistry(List.of(new TerminalAgentProvider()), ctx);
        Repository repository = new Repository(RepositoryId.newId(), clone, "example",
                java.time.Instant.now(), java.time.Instant.now(), RepositorySettings.DEFAULT);
        modal = new NewWorktreeModal(repository, new GitStatusService(), new WorktreeService(),
                registry, AgentKind.TERMINAL, false, kind -> Optional.empty(), () -> { },
                (mode, outcome, branch, base, directory, task, agent, model, eval) -> { });

        TestStages.show(stage, new Scene(new StackPane(modal), 620, 640));
    }

    @Test
    void terminalHidesTheTaskFieldAndTheModelPicker() {
        // The modal hides the field's GROUP (label + field); the TextArea
        // child keeps its own flags, so the group is what is asserted on.
        VBox taskGroup = (VBox) taskField().getParent();
        assertFalse(taskGroup.isVisible(), "typed task text would be executed by the shell");
        assertFalse(taskGroup.isManaged());
        // Same for the model picker: the modal hides the ModelSelector VBox,
        // and the ComboBox inside keeps its own flags.
        assertFalse(modelCombo().getParent().isVisible());
        assertFalse(modelCombo().getParent().isManaged());
        FxSync.waitForFxEvents();
    }

    private TextArea taskField() {
        for (Node node : modal.lookupAll(".worktree-task")) {
            if (node instanceof TextArea textArea) {
                return textArea;
            }
        }
        throw new AssertionError("the task field is not in the scene");
    }

    private Node modelCombo() {
        return modal.lookup(".worktree-model-combo");
    }

    /**
     * Built once per class (FxTest calls start() before every test) and
     * owned from createTempDirectory on: a build that fails part-way deletes
     * its temp dir before rethrowing, and a built clone is deleted in
     * {@link #deleteSharedClone}.
     */
    private static synchronized Path sharedClone() throws Exception {
        if (sharedClone != null) {
            return sharedClone;
        }
        Path clone = Files.createTempDirectory("drydock-terminal-modal");
        try {
            new ProcessBuilder("git", "init", "-b", "main", clone.toString())
                    .start().waitFor();
            new ProcessBuilder("git", "-C", clone.toString(), "config", "user.name", "Test")
                    .start().waitFor();
            new ProcessBuilder("git", "-C", clone.toString(), "config", "user.email", "test@example.com")
                    .start().waitFor();
            Files.writeString(clone.resolve("README.md"), "hello\n");
            new ProcessBuilder("git", "-C", clone.toString(), "add", "README.md")
                    .start().waitFor();
            new ProcessBuilder("git", "-C", clone.toString(), "commit", "-m", "initial commit")
                    .start().waitFor();
        } catch (Throwable t) {
            deleteTree(clone);
            throw t; // precise rethrow: only Exception subtypes or unchecked can reach here
        }
        sharedClone = clone;
        return clone;
    }

    @AfterAll
    static synchronized void deleteSharedClone() {
        if (sharedClone == null) {
            return;
        }
        // The clone ITSELF: the temp dir is the clone, so its parent is the
        // system temp directory and must never be touched.
        deleteTree(sharedClone);
        sharedClone = null;
    }

    /** Best-effort recursive delete: a temp tree that outlives the run is not a failure. */
    private static void deleteTree(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException | UncheckedIOException ignored) {
            // best effort: Files.walk surfaces mid-walk I/O errors as UncheckedIOException
        }
    }
}
