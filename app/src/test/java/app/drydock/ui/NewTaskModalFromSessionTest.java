package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.TaskRelation;
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
 * The from-a-session "New task" dialog: the placement offers the parent's
 * own checkout and a sub-branch + new worktree, the relation toggle only
 * rides the sub-branch placement, and the launch carries the chosen {@link
 * TaskRelation}. The relation is the parent-waits gate's input (see
 * {@code WorktreeLifecycleController}), so the launch record is the contract.
 */
class NewTaskModalFromSessionTest extends FxTest {

    private static Path sharedClone;

    private GitStatusService gitStatusService;
    private WorktreeService worktreeService;
    private NewTaskModal modal;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<NewTaskModal.NewTaskLaunch> launched = new AtomicReference<>();
    private final AtomicReference<ManagedAgentSession> parent = new AtomicReference<>();
    private StackPane root;

    /** Whether start() builds the detached-parent shape for the refusal test. */
    private static final AtomicBoolean detachedParent = new AtomicBoolean(false);

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
        parent.set(sessionOn(clone, detachedParent.get()));
        String parentBranch = detachedParent.get() ? null : "main";
        modal = new NewTaskModal(repository, gitStatusService, worktreeService,
                new AgentRegistry(List.of(), null), AgentKind.CLAUDE, false, Optional.empty(),
                kind -> Optional.empty(), () -> Set.of(), () -> closed.set(true),
                (launch, onSuccess) -> {
                    launched.set(launch);
                    onSuccess.run();
                },
                parent.get(), parentBranch);

        root = new StackPane(modal);
        TestStages.show(stage, new Scene(root, 620, 640));
    }

    @BeforeEach
    void awaitCatalog() throws InterruptedException {
        // The new-worktree placement's Create gates on the branch catalog.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            AtomicBoolean ready = new AtomicBoolean();
            interact(() -> ready.set(!startButton().isDisable()));
            if (ready.get()) {
                FxSync.waitForFxEvents();
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("the dialog never became startable");
    }

    @AfterEach
    void closeServices() {
        gitStatusService.close();
        worktreeService.close();
    }

    @Test
    void theDialogOpensOnTheParentsCheckoutWithNoRelation() {
        assertTrue(placement("⎇  Same branch/worktree").isSelected());
        assertFalse(relationGroupVisible());

        interact(() -> startButton().fire());

        assertTrue(closed.get());
        assertEquals(NewTaskModal.Placement.SAME_CHECKOUT, launched.get().placement());
        assertTrue(launched.get().relation().isEmpty(), "a same-checkout task carries no relation");
        assertEquals(parent.get(), launched.get().parent().orElseThrow());
    }

    @Test
    void theSubBranchPlacementCarriesTheRelationToggleDefaultingToFollowUp() throws InterruptedException {
        interact(() -> placement("◫  New worktree…").fire());

        assertTrue(relationGroupVisible());
        assertTrue(placement("Follow-up — stacked").isSelected());
        assertFalse(placement("Parent waits for this task").isSelected());

        interact(() -> newBranchField().setText("feat/sub-task"));
        awaitStartEnabled();
        interact(() -> startButton().fire());

        assertEquals(NewTaskModal.Placement.NEW_WORKTREE, launched.get().placement());
        assertEquals("main", launched.get().newWorktreeBase(), "the fork-from seed is the parent's branch");
        assertEquals(Optional.of(TaskRelation.FOLLOW_UP), launched.get().relation());
        assertEquals(parent.get(), launched.get().parent().orElseThrow());
    }

    @Test
    void parentWaitsIsTheDeliberateOptIn() throws InterruptedException {
        interact(() -> placement("◫  New worktree…").fire());
        interact(() -> placement("Parent waits for this task").fire());
        interact(() -> newBranchField().setText("feat/sub-task"));
        awaitStartEnabled();
        interact(() -> startButton().fire());

        assertEquals(Optional.of(TaskRelation.PARENT_WAITS), launched.get().relation());
    }

    @Test
    void aDetachedParentOffersNoSubBranchPlacement() {
        runWithDetachedParent();

        assertTrue(placement("◫  New worktree…").isDisabled(), "no parent branch, no sub-branch");
        assertTrue(placement("⎇  Same branch/worktree").isSelected());
    }

    // ---- fixture ------------------------------------------------------------

    private static ManagedAgentSession sessionOn(Path checkout, boolean detached) {
        // A session in a worktree checkout keeps the chip reading the real
        // path; for the detached shape the checkout is the clone itself, whose
        // branch the dialog would refuse to read (the caller passes null).
        return new ManagedAgentSession(
                app.drydock.domain.ManagedSessionId.newId(), RepositoryId.newId(), "parent session",
                new app.drydock.domain.AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new app.drydock.domain.SessionWorkspace(checkout,
                        Optional.of(checkout), true),
                app.drydock.domain.SessionStatus.INACTIVE, Instant.now(), Instant.now(),
                Optional.empty(), app.drydock.domain.PrLink.none(), false, Optional.empty());
    }

    /** Rebuilds the modal against a detached parent; one extra dialog per run is fine. */
    private void runWithDetachedParent() {
        interact(() -> {
            closed.set(false);
            launched.set(null);
            detachedParent.set(true);
            Path clone = sharedClone;
            Repository repository = new Repository(RepositoryId.newId(), clone, "example",
                    Instant.now(), Instant.now(), RepositorySettings.DEFAULT);
            modal = new NewTaskModal(repository, gitStatusService, worktreeService,
                    new AgentRegistry(List.of(), null), AgentKind.CLAUDE, false, Optional.empty(),
                    kind -> Optional.empty(), () -> Set.of(), () -> closed.set(true),
                    (launch, onSuccess) -> launched.set(launch),
                    sessionOn(clone, true), null);
            root.getChildren().add(modal);
        });
        FxSync.waitForFxEvents();
    }

    private RadioButton placement(String text) {
        return modal.lookupAll(".seg-toggle-button").stream()
                .filter(RadioButton.class::isInstance)
                .map(RadioButton.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button labeled " + text));
    }

    private Button startButton() {
        return (Button) modal.lookup(".worktree-create-button");
    }

    private javafx.scene.control.TextField newBranchField() {
        return modal.lookupAll(".worktree-new-branch").stream()
                .map(javafx.scene.control.TextField.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no new-branch field in the scene"));
    }

    private boolean relationGroupVisible() {
        // The group is shown/hidden as a whole, so its children's own
        // visibility flags never change; the group's flag is the truth.
        return ((javafx.scene.layout.Region) modal.lookup(".task-relation-group")).isVisible();
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

    private static synchronized Path sharedClone() throws Exception {
        if (sharedClone != null) {
            return sharedClone;
        }
        Path tmp = Files.createTempDirectory("drydock-new-task-from-session");
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
