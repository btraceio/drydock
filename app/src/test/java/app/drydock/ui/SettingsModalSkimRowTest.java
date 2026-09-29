package app.drydock.ui;

import app.drydock.domain.UiTheme;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Explorer row of the settings modal: it must show the STORED value,
 * not the default, and it must not write that value straight back the
 * moment it displays it.
 */
class SettingsModalSkimRowTest extends ApplicationTest {

    private final AtomicReference<Boolean> saved = new AtomicReference<>();
    private SettingsModal modal;

    @Override
    public void start(Stage stage) {
        modal = new SettingsModal(new SettingsModal.Settings() {
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
                // A fresh CheckBox is already unselected, so the stored
                // value must be true for applying it to be a real state
                // transition -- otherwise a listener wired before the load
                // lands would have nothing to fire on, and this test would
                // pass against that bug.
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<Void> saveOpenChangedFilesInSkim(boolean value) {
                saved.set(value);
                return CompletableFuture.completedFuture(null);
            }
        }, () -> { });
        Scene scene = new Scene(new StackPane(modal), 600, 700);
        scene.getStylesheets().addAll(
                SettingsModal.class.getResource("/app/drydock/ui/theme-dark.css").toExternalForm(),
                SettingsModal.class.getResource("/app/drydock/ui/app.css").toExternalForm());
        TestStages.show(stage, scene);
    }

    @Test
    void theSkimCheckboxReadsAndWritesThePreference() {
        WaitForAsyncUtils.waitForFxEvents();
        CheckBox box = (CheckBox) modal.lookup(".settings-check");
        assertTrue(box.isSelected(), "the modal shows the stored preference, not the default");
        assertFalse(box.isDisabled(), "…and is enabled once the value has arrived");
        assertEquals(null, saved.get(), "showing a value is not a reason to write it back");

        interact(() -> box.setSelected(false));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(Boolean.FALSE, saved.get());
    }
}
