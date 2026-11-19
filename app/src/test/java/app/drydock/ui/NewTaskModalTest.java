package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.RadioButton;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unified "New task" dialog's plain mode: the destination is chosen
 * inside the dialog, the existing-worktree picker hides worktrees that
 * already host a session, and the Start handler receives the placement the
 * dialog last showed. The branch machinery the new-worktree placement embeds
 * is {@link WorktreeFields}, covered by {@code NewWorktreeModalTest}; this
 * covers the placement switch and the launch record.
 */
class NewTaskModalTest extends FxTest {

    private static Path sharedClone;

    private GitStatusService gitStatusService;
    private WorktreeService worktreeService;
    private NewTaskModal modal;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<NewTaskModal.NewTaskLaunch> launched = new AtomicReference<>();
    private final AtomicReference<Set<Path>> claimed = new AtomicReference<>(Set.of());

    @Override
    public void start(Stage stage) {
        Path clone;
        try {
            clone = sharedClone();
        } catch (Exception e) {
            throw new IllegalStateException("could not build the test clone", e);
        }
        gitStatusService = new GitStatusService();
        worktreeService = new WorktreeService();
        Repository repository = new Repository(RepositoryId.newId(), clone, "example",
                Instant.now(), Instant.now(), RepositorySettings.DEFAULT);
        Supplier<Set<Path>> claimedWorktrees = claimed::get;
        modal = new NewTaskModal(repository, gitStatusService, worktreeService,
                new AgentRegistry(List.of(), null), AgentKind.CLAUDE, false, Optional.empty(),
                kind -> Optional.empty(), claimedWorktrees, () -> closed.set(true),
                (launch, onSuccess) -> {
                    launched.set(launch);
                    onSuccess.run();
                });

        TestStages.show(stage, new Scene(new StackPane(modal), 620, 640));
    }

    @BeforeEach
    void awaitWorktrees() throws InterruptedException {
        // The listing loads asynchronously; the refresh button carries the
        // load state ("…" while in flight).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            AtomicBoolean loaded = new AtomicBoolean();
            interact(() -> loaded.set("⟳".equals(existingRefresh().getText())));
            if (loaded.get()) {
                FxSync.waitForFxEvents();
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("the worktree listing never arrived");
    }

    @AfterEach
    void closeServices() {
        gitStatusService.close();
        worktreeService.close();
    }

    // ---- the placement switch -----------------------------------------------

    @Test
    void theDialogOpensOnTheMainCheckoutPlacement() {
        assertTrue(placement("⎇  Main checkout").isSelected());
        assertTrue(startButton().isVisible());
        assertFalse(startButton().isDisable());
        assertTrue(chipText().contains("example"), chipText());
    }

    @Test
    void pressingStartOnTheMainCheckoutDeliversTheLaunchAndClosesTheDialog() {
        interact(() -> startButton().fire());

        assertTrue(closed.get());
        assertEquals(NewTaskModal.Placement.MAIN_CHECKOUT, launched.get().placement());
        assertEquals(AgentKind.CLAUDE, launched.get().agent());
    }

    @Test
    void theExistingWorktreePickerExcludesTheMainCheckoutAndClaimedWorktrees() {
        interact(() -> placement("◫  Existing worktree").fire());

        assertEquals(1, existingPicker().getItems().size(), "the clone carries one non-main worktree");
    }

    @Test
    void aClaimedWorktreeDoesNotShowUpInThePicker() throws Exception {
        interact(() -> {
            try {
                claimed.set(Set.of(worktreePath()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        interact(() -> existingRefresh().fire());
        awaitPickerEmpty();

        interact(() -> placement("◫  Existing worktree").fire());

        assertTrue(existingPicker().getItems().isEmpty());
        assertTrue(startButton().isDisable(), "an empty picker has nothing to start on");
    }

    @Test
    void selectingAnExistingWorktreeEnablesStartAndDeliversItsLaunch() throws Exception {
        interact(() -> placement("◫  Existing worktree").fire());
        interact(() -> existingPicker().getSelectionModel().selectFirst());

        assertFalse(startButton().isDisable());
        interact(() -> startButton().fire());

        assertTrue(closed.get());
        assertEquals(NewTaskModal.Placement.EXISTING_WORKTREE, launched.get().placement());
        assertTrue(launched.get().existingWorktree().isPresent());
        assertEquals(worktreePath(), launched.get().existingWorktree().orElseThrow().path());
    }

    // ---- the new-worktree placement -----------------------------------------

    @Test
    void theNewWorktreePlacementShowsTheBranchFieldsAndStartsOnceTheNameIsValid() throws InterruptedException {
        interact(() -> placement("◫  New worktree…").fire());

        assertTrue(newBranchField().isVisible(), "the branch fields are the new-worktree placement's content");

        interact(() -> newBranchField().setText("feat/from-the-dialog"));
        awaitStartEnabled();
        interact(() -> startButton().fire());

        assertEquals(NewTaskModal.Placement.NEW_WORKTREE, launched.get().placement());
        assertEquals("feat/from-the-dialog", launched.get().newWorktreeBranch());
        assertTrue(launched.get().newWorktreeDirectory().endsWith("example-from-the-dialog"),
                launched.get().newWorktreeDirectory().toString());
    }

    // ---- fixture ------------------------------------------------------------

    private RadioButton placement(String text) {
        return modal.lookupAll(".seg-toggle-button").stream()
                .filter(RadioButton.class::isInstance)
                .map(RadioButton.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no placement button labeled " + text));
    }

    private Button startButton() {
        return (Button) modal.lookup(".worktree-create-button");
    }

    @SuppressWarnings("unchecked")
    private javafx.scene.control.ComboBox<app.drydock.git.WorktreeService.Worktree> existingPicker() {
        return (javafx.scene.control.ComboBox<app.drydock.git.WorktreeService.Worktree>)
                modal.lookup(".worktree-branch-combo");
    }

    private Button existingRefresh() {
        return (Button) modal.lookup(".worktree-refresh-button");
    }

    private javafx.scene.control.TextField newBranchField() {
        return modal.lookupAll(".worktree-new-branch").stream()
                .map(javafx.scene.control.TextField.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no new-branch field in the scene"));
    }

    private String chipText() {
        return ((javafx.scene.control.Label) modal.lookup(".worktree-base-chip")).getText();
    }

    private Path worktreePath() throws IOException {
        // git resolves the temp directory's symlink, so the listing carries
        // the real path; the claimed-set comparison needs the same spelling.
        return sharedClone.getParent().toRealPath().resolve("example-wt-second");
    }

    private void awaitStartEnabled() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            AtomicBoolean enabled = new AtomicBoolean();
            interact(() -> enabled.set(!startButton().isDisable()));
            if (enabled.get()) {
                FxSync.waitForFxEvents();
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Start never became enabled for the typed branch");
    }

    private void awaitPickerEmpty() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            AtomicBoolean empty = new AtomicBoolean();
            interact(() -> empty.set(existingPicker().getItems().isEmpty()));
            if (empty.get()) {
                FxSync.waitForFxEvents();
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("the picker never emptied");
    }

    private static synchronized Path sharedClone() throws Exception {
        if (sharedClone != null) {
            return sharedClone;
        }
        Path tmp = Files.createTempDirectory("drydock-new-task-modal");
        Path upstream = Files.createDirectory(tmp.resolve("upstream"));
        git(upstream, "init", "--initial-branch=main");
        git(upstream, "config", "user.email", "test@example.com");
        git(upstream, "config", "user.name", "Test");
        Files.writeString(upstream.resolve("README.md"), "hello\n");
        git(upstream, "add", ".");
        git(upstream, "commit", "-m", "initial");

        Path clone = tmp.resolve("clone");
        git(tmp, "clone", upstream.toString(), clone.toString());
        git(clone, "config", "user.email", "test@example.com");
        git(clone, "config", "user.name", "Test");
        git(clone, "worktree", "add", tmp.resolve("example-wt-second").toString(), "-b", "wt/second");

        sharedClone = clone;
        return clone;
    }

    @AfterAll
    static synchronized void deleteSharedClone() throws IOException {
        if (sharedClone == null) {
            return;
        }
        Path tmp = sharedClone.getParent();
        try (var walk = Files.walk(tmp)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A temp tree that outlives the run is not a failure.
                }
            });
        }
        sharedClone = null;
    }

    private static void git(Path workingDirectory, String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        if (process.waitFor() != 0) {
            throw new IOException(new String(process.getInputStream().readAllBytes()));
        }
    }
}
