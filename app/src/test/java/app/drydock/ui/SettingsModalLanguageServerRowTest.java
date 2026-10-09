package app.drydock.ui;

import app.drydock.domain.UiTheme;
import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import app.drydock.ui.SettingsModal.LanguageServerOutcome;
import app.drydock.ui.SettingsModal.Settings;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The language-server row of the settings modal (spec section 3): async
 * loaded like the worktrees directory, but its commit validates before it
 * saves, so this class pins the async UI contract -- a disabled
 * "Loading…" state, visible "Validating…" progress the moment the commit
 * fires, inline refusal text (never a dialog), and controls restored on
 * every completion path. Every callback is a fake future the test
 * completes by hand: no real config file, no real jdt.ls, no real java.
 */
class SettingsModalLanguageServerRowTest extends FxTest {

    private final CompletableFuture<Optional<Path>> load = new CompletableFuture<>();
    private final CompletableFuture<LanguageServerOutcome> save = new CompletableFuture<>();
    private final AtomicInteger saveCalls = new AtomicInteger();
    private final AtomicReference<Optional<Path>> lastSaved = new AtomicReference<>();
    private SettingsModal modal;

    @Override
    public void start(Stage stage) {
        modal = new SettingsModal(new Settings() {
            @Override
            public UiTheme theme() {
                return UiTheme.DARK;
            }

            @Override
            public void setTheme(UiTheme theme) {
            }

            @Override
            public SizeSetting interfaceSize() {
                return new SizeSetting(() -> 13.0, size -> { }, size -> { });
            }

            @Override
            public SizeSetting terminalSize() {
                return new SizeSetting(() -> 13.0, size -> { }, size -> { });
            }

            @Override
            public CompletableFuture<Optional<Path>> loadWorktreesDirectory() {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<Void> saveWorktreesDirectory(Optional<Path> directory) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Boolean> loadOpenChangedFilesInSkim() {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<Void> saveOpenChangedFilesInSkim(boolean value) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Optional<Path>> loadLanguageServerDirectory() {
                return load;
            }

            @Override
            public CompletableFuture<LanguageServerOutcome> saveLanguageServerDirectory(Optional<Path> directory) {
                saveCalls.incrementAndGet();
                lastSaved.set(directory);
                return save;
            }
        }, () -> { });
        Scene scene = new Scene(new StackPane(modal), 600, 800);
        scene.getStylesheets().addAll(
                SettingsModal.class.getResource("/app/drydock/ui/theme-dark.css").toExternalForm(),
                SettingsModal.class.getResource("/app/drydock/ui/app.css").toExternalForm());
        TestStages.show(stage, scene);
    }

    private TextField field() {
        return (TextField) modal.lookup(".language-server-field");
    }

    private Button browse() {
        return (Button) modal.lookup(".language-server-browse");
    }

    private Label status() {
        return (Label) modal.lookup(".language-server-status");
    }

    private void landLoad(Optional<Path> directory) {
        load.complete(directory);
        FxSync.waitForFxEvents();
    }

    @Test
    void theRowIsDisabledWithALoadingPromptUntilTheValueArrives() {
        TextField field = field();
        assertTrue(field.isDisabled(), "the row is disabled while its value is still loading");
        assertEquals("Loading…", field.getPromptText());
        assertTrue(browse().isDisabled());

        landLoad(Optional.of(Path.of("/opt/jdt.ls")));

        assertFalse(field.isDisabled());
        assertFalse(browse().isDisabled());
        assertEquals("/opt/jdt.ls", field.getText(), "the stored path is shown, not a default");
        assertEquals("", status().getText(), "loading a value is not a reason to report anything");
        assertEquals(0, saveCalls.get(), "and not a reason to write it back either");
    }

    @Test
    void aFailedLoadLandsInlineAndLeavesTheRowUsable() {
        load.completeExceptionally(new IOException("config.json is locked"));
        FxSync.waitForFxEvents();

        assertFalse(field().isDisabled(), "the row stays usable so the user can retry");
        assertTrue(status().getText().contains("Could not read the settings file"),
                "the failure is named inline: " + status().getText());
        assertTrue(status().getText().contains("locked"), status().getText());
    }

    @Test
    void aCommitShowsValidatingProgressBeforeTheResultArrives() {
        landLoad(Optional.empty());

        interact(() -> {
            field().setText("/opt/jdt.ls");
            field().getOnAction().handle(new ActionEvent());
        });

        // Still on the FX thread of the commit, before any completion: the
        // click must have visibly done something (house rule), so the
        // progress label is already showing and the controls are already
        // disabled for the duration of the validation.
        assertEquals("Validating…", status().getText());
        assertTrue(field().isDisabled());
        assertTrue(browse().isDisabled());
        assertEquals(1, saveCalls.get());
        assertEquals(Optional.of(Path.of("/opt/jdt.ls")), lastSaved.get());
    }

    @Test
    void aRefusalLandsInlineAndRestoresTheControls() {
        landLoad(Optional.empty());
        interact(() -> {
            field().setText("/opt/not-jdt");
            field().getOnAction().handle(new ActionEvent());
        });

        save.complete(new LanguageServerOutcome.Refused(
                "jdt.ls directory has no config_mac: /opt/not-jdt"));
        FxSync.waitForFxEvents();

        assertEquals("jdt.ls directory has no config_mac: /opt/not-jdt", status().getText(),
                "the refusal reason is shown inline, never in a dialog");
        assertFalse(field().isDisabled());
        assertFalse(browse().isDisabled());
    }

    @Test
    void aSuccessfulSaveLandsInlineAndDoesNotResaveTheCommittedText() {
        landLoad(Optional.of(Path.of("/opt/old")));
        interact(() -> {
            field().setText("/opt/jdt.ls");
            field().getOnAction().handle(new ActionEvent());
        });

        save.complete(LanguageServerOutcome.SAVED);
        FxSync.waitForFxEvents();

        assertEquals("Saved.", status().getText());
        assertFalse(field().isDisabled());
        assertFalse(browse().isDisabled());

        // The committed text is now the baseline: a focus-lost commit (which
        // every close path funnels through) must not fire a second save.
        interact(() -> field().getOnAction().handle(new ActionEvent()));
        FxSync.waitForFxEvents();
        assertEquals(1, saveCalls.get(), "re-committing the same text is a no-op");
    }

    @Test
    void aFailingSaveRestoresTheControlsAndNamesTheFailureInline() {
        landLoad(Optional.empty());
        interact(() -> {
            field().setText("/opt/jdt.ls");
            field().getOnAction().handle(new ActionEvent());
        });

        save.completeExceptionally(new IOException("disk full"));
        FxSync.waitForFxEvents();

        assertFalse(field().isDisabled(), "every completion path re-enables the controls");
        assertFalse(browse().isDisabled());
        assertTrue(status().getText().contains("disk full"),
                "the failure is named inline: " + status().getText());
    }

    @Test
    void flushPendingEditCommitsATypedPathThatNeverHadFocusLoss() {
        // The Esc/backdrop-click close path: a typed value committed only
        // because the row is part of flushPendingEdit.
        landLoad(Optional.empty());
        interact(() -> field().setText("/opt/late"));

        interact(modal::flushPendingEdit);

        assertEquals("Validating…", status().getText());
        assertEquals(1, saveCalls.get());

        save.complete(LanguageServerOutcome.SAVED);
        FxSync.waitForFxEvents();
        assertEquals("Saved.", status().getText());
    }

    @Test
    void flushingTwiceCommitsOnce() {
        landLoad(Optional.empty());
        interact(() -> field().setText("/opt/late"));

        interact(() -> {
            modal.flushPendingEdit();
            modal.flushPendingEdit();
        });

        assertEquals(1, saveCalls.get(), "the commit is idempotent, like the worktrees row's");
        save.complete(LanguageServerOutcome.SAVED);
        FxSync.waitForFxEvents();
        assertEquals("Saved.", status().getText());
    }
}
