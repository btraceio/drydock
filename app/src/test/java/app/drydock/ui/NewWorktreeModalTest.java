package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.git.BranchRef;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import app.drydock.ui.NewWorktreeState.Mode;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of the create-worktree modal that only a scene can show: the mode
 * switch itself. {@code NewWorktreeStateTest} covers what each mode derives;
 * this covers that the right mode, the right control and the right ref reach
 * that derivation.
 *
 * <p>Runs against a real clone so the catalog is the one the app would build
 * -- including the two pairs that only a real repository produces: a local
 * branch literally named {@code origin/foo} alongside remote-tracking
 * {@code origin/foo}, and a remote ref whose derived local name cannot be
 * minted.</p>
 */
class NewWorktreeModalTest extends ApplicationTest {

    /** Probe for building a portable ⌘E: the platform decides what SHORTCUT is. */
    private static final KeyCombination SWITCH_MODE =
            new KeyCodeCombination(KeyCode.E, KeyCombination.SHORTCUT_DOWN);

    private static Path sharedClone;

    private GitStatusService gitStatusService;
    private WorktreeService worktreeService;
    private NewWorktreeModal modal;
    private Mode createdMode;
    private BranchRef createdRef;
    private String createdBranch;

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
        modal = new NewWorktreeModal(repository, gitStatusService, worktreeService,
                new AgentRegistry(List.of(), null), AgentKind.CLAUDE, false, () -> { },
                (mode, outcome, branch, base, directory, task, agent) -> {
                    createdMode = mode;
                    createdBranch = branch;
                    createdRef = outcome instanceof app.drydock.git.BranchCheckout.Outcome.Ready ready
                            ? ready.ref()
                            : null;
                });

        stage.setScene(new Scene(new StackPane(modal), 620, 640));
        stage.show();
    }

    /**
     * Deliberately NOT in {@code start}: that runs on the FX thread, and the
     * catalog arrives through {@code Platform.runLater}, so waiting there
     * blocks the very thread that would deliver it.
     */
    @BeforeEach
    void awaitCatalog() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            AtomicBoolean loaded = new AtomicBoolean();
            interact(() -> loaded.set(!picker().getItems().isEmpty()));
            if (loaded.get()) {
                WaitForAsyncUtils.waitForFxEvents();
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("the branch catalog never arrived");
    }

    @AfterEach
    void closeServices() {
        gitStatusService.close();
        worktreeService.close();
    }

    // ---- opening state ------------------------------------------------------

    @Test
    void theModalOpensInNewBranchMode() {
        assertTrue(segment("New branch").isSelected());
        assertFalse(segment("Existing branch").isSelected());
        assertTrue(newBranchField().isVisible());
        assertFalse(picker().isVisible());
        assertTrue(baseGroupVisible(), "Fork from applies to new branches");
        assertEquals("feat/", newBranchField().getText());
    }

    @Test
    void theRefreshButtonStaysInBothModesBecauseNewModeNeedsAFreshCatalogToo() {
        Node refresh = modal.lookup(".worktree-refresh-button");

        assertTrue(refresh.isVisible());
        switchMode();
        assertTrue(refresh.isVisible());
    }

    // ---- the switch ---------------------------------------------------------

    @Test
    void theShortcutFlipsTheModeWithTheCaretInTheBranchEditor() {
        interact(() -> newBranchField().requestFocus());

        switchMode();

        assertTrue(segment("Existing branch").isSelected());
        assertTrue(picker().isVisible());
        assertFalse(newBranchField().isVisible());
        assertFalse(baseGroupVisible(), "Fork from does not apply to a branch that already exists");

        switchMode();
        assertTrue(segment("New branch").isSelected());
        assertTrue(newBranchField().isVisible());
        assertFalse(picker().isVisible());
    }

    @Test
    void focusFollowsTheModeSoTypingLandsInTheControlThatIsShowing() {
        switchMode();
        assertTrue(picker().getEditor().isFocused(), "the picker's editor should have focus");

        switchMode();
        assertTrue(newBranchField().isFocused(), "the new-branch field should have focus");
    }

    /**
     * {@code ToggleButton.fire()} toggles unconditionally, so a two-button
     * group of them can be clicked into no selection at all -- i.e. into no
     * mode. These are {@code RadioButton}s for exactly this.
     */
    @Test
    void clickingTheAlreadySelectedSegmentDoesNotClearTheMode() {
        switchMode();

        interact(() -> segment("Existing branch").fire());

        assertTrue(segment("Existing branch").isSelected());
        assertTrue(picker().isVisible());
    }

    @Test
    void eachControlKeepsItsOwnTextAcrossARoundTrip() {
        interact(() -> newBranchField().setText("feat/typed-here"));
        switchMode();
        interact(() -> picker().getEditor().setText("feat/free"));
        switchMode();

        assertEquals("feat/typed-here", newBranchField().getText());
        switchMode();
        assertEquals("feat/free", picker().getEditor().getText());
    }

    // ---- the directory follows the active control ---------------------------

    @Test
    void theShortcutReDerivesTheDirectoryFromTheNewlyActiveControl() {
        interact(() -> newBranchField().setText("feat/login"));
        assertTrue(directory().endsWith("example-login"), directory());

        interact(() -> picker().getEditor().setText("feat/free"));
        // Still new-branch mode: the hidden control must not touch the directory.
        assertTrue(directory().endsWith("example-login"), directory());

        switchMode();
        assertTrue(directory().endsWith("example-free"), directory());
    }

    /**
     * {@code WorktreeNaming.slug("")} is {@code "worktree"}, so without the
     * blank guard the directory would flip to {@code example-worktree} and
     * back every time the mode changed.
     */
    @Test
    void switchingIntoABlankControlLeavesTheDirectoryAlone() {
        interact(() -> newBranchField().setText("feat/login"));
        interact(() -> picker().getEditor().setText(""));

        switchMode();

        assertTrue(directory().endsWith("example-login"), directory());
    }

    // ---- gated while a creation is in flight --------------------------------

    @Test
    void theSegmentsAndTheShortcutAreBothInertWhileACreationIsInFlight() {
        interact(() -> modal.showCreating());

        assertTrue(segment("New branch").isDisabled());
        assertTrue(segment("Existing branch").isDisabled());
        // Disabling a node does not disable a KEY_PRESSED filter on its
        // ancestor, so the gate needs its own test.
        switchMode();
        assertTrue(segment("New branch").isSelected(), "⌘E must not flip mode mid-creation");
    }

    // ---- the offer ----------------------------------------------------------

    @Test
    void theOfferNamesItsTargetWhenTheResolvedRefIsNotWhatWasTyped() {
        interact(() -> newBranchField().setText("foo"));

        // "foo" exists only as origin/foo on the remote: the ordinary
        // fetch-then-check-out flow, and the resolved name differs.
        assertEquals("Check out origin/foo instead", offer().getText());
        assertTrue(offer().isVisible());
    }

    @Test
    void theOfferSaysNoNameWhenTheResolvedRefIsExactlyWhatWasTyped() {
        interact(() -> newBranchField().setText("feat/free"));

        assertEquals("Check it out instead", offer().getText());
    }

    @Test
    void anOccupiedBranchGetsNoOfferBecauseCheckingItOutIsWhatCannotHappen() {
        interact(() -> newBranchField().setText("main"));

        assertFalse(offer().isVisible());
        assertTrue(hint().contains("already exists"), hint());
    }

    @Test
    void pressingTheOfferLandsInExistingModeOnTheRefItNamed() {
        interact(() -> newBranchField().setText("foo"));

        interact(() -> offer().fire());

        assertTrue(segment("Existing branch").isSelected());
        assertEquals("origin/foo", picker().getValue().name());
        assertTrue(picker().getValue().remote(), "the remote-tracking ref, not the local typo branch");
    }

    /**
     * A local branch named {@code origin/foo} is exactly what the remote-shadow
     * bug creates, and it coexists with remote-tracking {@code origin/foo}.
     * Asserted on the preview rather than the picker's value: checking the
     * value alone stays green while {@code derive} resolves the other ref.
     */
    @Test
    void theOfferHandsOverTheRefSoTheWrongSameNamedBranchIsNotCheckedOut() {
        interact(() -> newBranchField().setText("foo"));
        interact(() -> offer().fire());

        assertEquals("$ git worktree add " + directory() + " -b foo --track origin/foo", preview());
    }

    /**
     * The value-listener case. {@code updateDisplayNode} writes the editor
     * only when the converter's string differs from the text already there,
     * so picking the other duplicate-named row changes the value and writes
     * no text -- and without a {@code valueProperty()} listener nothing would
     * recompute.
     */
    @Test
    void pickingTheOtherDuplicateNamedRowUpdatesThePreviewWithNoTextChange() {
        switchMode();
        interact(() -> picker().setValue(remoteRef("origin/foo")));
        String textAfterFirstPick = picker().getEditor().getText();
        String remotePreview = preview();

        interact(() -> picker().setValue(localRef("origin/foo")));

        assertEquals(textAfterFirstPick, picker().getEditor().getText(), "no editor text changed");
        assertEquals("$ git worktree add " + directory() + " -b foo --track origin/foo", remotePreview);
        assertEquals("$ git worktree add " + directory() + " origin/foo", preview());
    }

    // ---- the picker's own resolution ----------------------------------------

    /**
     * ENTER commits via {@code setValue(converter.fromString(text))}, which
     * fabricates a local, never-occupied ref. The catalog has to be what
     * answers, or Create would light up for a branch that is taken.
     */
    @Test
    void anEnterCommitOnAnOccupiedNameStillYieldsTheOccupancyHint() {
        switchMode();
        interact(() -> picker().getEditor().setText("main"));
        interact(() -> picker().getEditor().fireEvent(
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false)));

        assertTrue(hint().startsWith("Already checked out in"), hint());
        assertTrue(createButton().isDisabled());
    }

    @Test
    void anUnknownNameInExistingModeSaysSoAndDisablesCreate() {
        switchMode();
        interact(() -> picker().getEditor().setText("no-such-branch"));

        assertEquals("No branch named 'no-such-branch'.", hint());
        assertTrue(createButton().isDisabled());
        assertFalse(offer().isVisible(), "the mirror-image offer is deliberately not built");
    }

    /**
     * {@code setDisable} on a {@code ListCell} blocks the mouse, not keyboard
     * traversal of the popup -- so the row is disabled AND the outcome still
     * refuses it.
     */
    @Test
    void aRowWhoseLocalNameCannotBeMintedIsDisabledNotMerelyRelabelled() {
        switchMode();
        interact(() -> picker().getEditor().setText("origin/origin/main"));

        assertEquals("Checking out origin/origin/main would create local origin/main, "
                + "which shadows the remote 'origin'.", hint());
        assertTrue(createButton().isDisabled());
    }

    // ---- what reaches the create call ---------------------------------------

    @Test
    void createHandsOverTheModeAndTheResolvedLocalNameNotTheTypedText() {
        switchMode();
        interact(() -> picker().setValue(remoteRef("origin/foo")));

        interact(() -> createButton().fire());

        assertEquals(Mode.EXISTING, createdMode);
        assertEquals("foo", createdBranch);
        assertEquals("origin/foo", createdRef.name());
        assertTrue(createdRef.remote());
    }

    @Test
    void createInNewModeHandsOverTheTypedTextAndTheNewMode() {
        interact(() -> newBranchField().setText("feat/brand-new"));

        interact(() -> createButton().fire());

        assertEquals(Mode.NEW, createdMode);
        assertEquals("feat/brand-new", createdBranch);
    }

    // ---- lookups ------------------------------------------------------------

    private RadioButton segment(String text) {
        return modal.lookupAll(".seg-toggle-button").stream()
                .map(RadioButton.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no segment " + text));
    }

    private TextField newBranchField() {
        return (TextField) modal.lookup(".worktree-new-branch");
    }

    @SuppressWarnings("unchecked")
    private ComboBox<BranchRef> picker() {
        return (ComboBox<BranchRef>) modal.lookup(".worktree-branch-combo");
    }

    private Button offer() {
        return (Button) modal.lookup(".worktree-hint-action");
    }

    private Button createButton() {
        return (Button) modal.lookup(".worktree-create-button");
    }

    private String hint() {
        return ((Label) modal.lookup(".worktree-hint")).getText();
    }

    private String preview() {
        return ((Label) modal.lookup(".worktree-command-preview")).getText();
    }

    private String directory() {
        return modal.lookupAll(".worktree-field").stream()
                .map(TextField.class::cast)
                .filter(field -> !field.getStyleClass().contains("worktree-new-branch"))
                .findFirst()
                .orElseThrow()
                .getText();
    }

    /** "Fork from" is the only group holding the base combo. */
    private boolean baseGroupVisible() {
        return modal.lookup(".worktree-base-combo").getParent().isVisible();
    }

    private BranchRef remoteRef(String name) {
        return pickerItem(name, true);
    }

    private BranchRef localRef(String name) {
        return pickerItem(name, false);
    }

    private BranchRef pickerItem(String name, boolean remote) {
        return picker().getItems().stream()
                .filter(branch -> branch.name().equals(name) && branch.remote() == remote)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no " + (remote ? "remote" : "local") + " " + name + " in " + picker().getItems()));
    }

    private void switchMode() {
        KeyEvent meta = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.E, false, false, false, true);
        KeyEvent control = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.E, false, true, false, false);
        KeyEvent chord = SWITCH_MODE.match(meta) ? meta : control;
        interact(() -> modal.fireEvent(chord));
    }

    // ---- fixture ------------------------------------------------------------

    /**
     * One clone for the class: every test reads the catalog and none mutates
     * it, and building a repository per test is slow enough to time the shared
     * JavaFX toolkit out.
     *
     * <p>What the branches are for: {@code main} is occupied by the clone's
     * own checkout; {@code feat/free} is a free local branch; upstream
     * {@code foo} becomes remote-tracking {@code origin/foo}, which coexists
     * with the deliberately confusing <em>local</em> branch of the same name;
     * and upstream {@code origin/main} becomes {@code origin/origin/main},
     * whose derived local name shadows the remote.</p>
     */
    private static synchronized Path sharedClone() throws Exception {
        if (sharedClone != null) {
            return sharedClone;
        }
        Path tmp = Files.createTempDirectory("drydock-worktree-modal");
        Path upstream = Files.createDirectory(tmp.resolve("upstream"));
        git(upstream, "init", "--initial-branch=main");
        git(upstream, "config", "user.email", "test@example.com");
        git(upstream, "config", "user.name", "Test");
        Files.writeString(upstream.resolve("README.md"), "hello\n");
        git(upstream, "add", ".");
        git(upstream, "commit", "-m", "initial");
        git(upstream, "branch", "foo");
        git(upstream, "branch", "origin/main");

        Path clone = tmp.resolve("clone");
        git(tmp, "clone", upstream.toString(), clone.toString());
        git(clone, "config", "user.email", "test@example.com");
        git(clone, "config", "user.name", "Test");
        git(clone, "branch", "feat/free");
        git(clone, "branch", "origin/foo");

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
        java.util.List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", arguments) + " failed: " + output);
        }
    }
}
