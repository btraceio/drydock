package app.drydock.ui;

import app.drydock.domain.UiTheme;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The Settings modal, reached from the title-bar gear or ⌘,. Four settings,
 * applied as they change (the macOS preferences convention -- there is no
 * OK/Cancel; Done, Esc, and × all just close).
 *
 * <p>Holds no manager or store: everything it can change arrives as a
 * callback in {@link Settings}, so persistence stays with the single state
 * writer and this class stays a view.</p>
 *
 * <p>The theme radio reads {@link Settings#theme()} once, at construction,
 * and never again -- so nothing else may change the theme while this modal
 * is open, or the radio would silently drift from reality with no way for
 * the user to click it back (a same-value selection fires no {@code
 * ToggleGroup} event). {@code DrydockApplication}'s ⌘⇧L branch is gated on
 * {@code modalLayer().isShowingModal()} for exactly this reason.</p>
 */
public final class SettingsModal extends VBox {

    /** Everything the modal reads and writes, supplied by the application wiring. */
    public interface Settings {
        UiTheme theme();

        void setTheme(UiTheme theme);

        /** The interface font size: read, applied live, and persisted (see {@link SizeSetting}). */
        SizeSetting interfaceSize();

        /** The terminal font size, with the same three parts as {@link #interfaceSize()}. */
        SizeSetting terminalSize();

        CompletableFuture<Optional<Path>> loadWorktreesDirectory();

        CompletableFuture<Void> saveWorktreesDirectory(Optional<Path> directory);

        /** Whether a file in the current change opens folded to its signatures (Explorer delta, part 2). */
        CompletableFuture<Boolean> loadOpenChangedFilesInSkim();

        CompletableFuture<Void> saveOpenChangedFilesInSkim(boolean value);
    }

    private static final double MODAL_WIDTH = 520;

    /**
     * Flushes a pending worktrees-directory edit, wired by {@link
     * #worktreesRow} and invoked by {@code ModalLayer}'s {@code onClosed}
     * callback (see {@link #flushPendingEdit}) so a typed value is
     * committed no matter which of Done/×/Esc/backdrop-click closes the
     * modal -- only Done and × happen to move focus off the field first.
     * A no-op until {@link #worktreesRow} runs.
     */
    private Runnable pendingWorktreesFlush = () -> { };

    public SettingsModal(Settings settings, Runnable onClose) {
        getStyleClass().add("modal");
        setMaxWidth(MODAL_WIDTH);
        setMaxHeight(Region.USE_PREF_SIZE);
        setSpacing(12);

        Label title = new Label("Settings");
        title.getStyleClass().add("modal-title");
        Button close = new Button("×");
        close.getStyleClass().add("icon-button");
        close.setOnAction(e -> onClose.run());
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, headerSpacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        Button done = new Button("Done");
        done.getStyleClass().add("worktree-create-button");
        done.setDefaultButton(true);
        done.setOnAction(e -> onClose.run());
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footer = new HBox(8, footerSpacer, done);

        getChildren().addAll(header,
                sectionTitle("Appearance"),
                themeRow(settings),
                // The interface slider keeps its 0.5px resolution -- UiFontScale genuinely
                // honours fractional sizes. The terminal slider is integer-only (see sizeRow):
                // TerminalThemes rounds to int, so a fractional readout there would lie.
                sizeRow("Interface size", UiFontScale.MIN_FONT_SIZE, UiFontScale.MAX_FONT_SIZE,
                        true, settings.interfaceSize()),
                sizeRow("Terminal size", TerminalThemes.MIN_FONT_SIZE, TerminalThemes.MAX_FONT_SIZE,
                        false, settings.terminalSize()),
                sectionTitle("Worktrees"),
                worktreesRow(settings),
                sectionTitle("Explorer"),
                skimRow(settings),
                footer);
    }

    /**
     * Commits a pending worktrees-directory edit, if any. Meant to be
     * passed as {@code ModalLayer}'s {@code onClosed} callback: Esc and a
     * backdrop click hide the modal without ever moving focus off the text
     * field, so the field's own focus-lost commit never fires and a typed
     * path would otherwise be silently discarded. Idempotent -- see
     * {@link #worktreesRow}'s {@code commit} -- so calling this after Done
     * or × (which already committed via focus loss) is harmless.
     */
    public void flushPendingEdit() {
        pendingWorktreesFlush.run();
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("settings-section-title");
        return label;
    }

    private static Region themeRow(Settings settings) {
        ToggleGroup group = new ToggleGroup();
        RadioButton dark = new RadioButton("Dark");
        RadioButton light = new RadioButton("Light");
        dark.getStyleClass().add("settings-radio");
        light.getStyleClass().add("settings-radio");
        dark.setToggleGroup(group);
        light.setToggleGroup(group);
        (settings.theme() == UiTheme.LIGHT ? light : dark).setSelected(true);

        // Applied on selection, not on Done: the whole modal is apply-on-change.
        // `selected == light` is the real test; the `dark` fallback below is
        // unreachable via the UI (the group always has one toggle selected,
        // and DrydockApplication's cmd+shift+L branch is gated on the modal
        // being closed -- see the class Javadoc), but naming it explicitly
        // beats letting a null toggle silently coerce to DARK.
        group.selectedToggleProperty().addListener((obs, old, selected) ->
                settings.setTheme(selected == light ? UiTheme.LIGHT
                        : selected == dark ? UiTheme.DARK
                        : settings.theme()));

        return labelled("Theme", new HBox(12, dark, light));
    }

    /** A settings row: a fixed-width caption on the left, the control on the right. */
    private static Region labelled(String caption, Region control) {
        Label label = new Label(caption);
        label.getStyleClass().add("settings-row-label");
        label.setMinWidth(120);
        HBox row = new HBox(12, label, control);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(control, Priority.ALWAYS);
        return row;
    }

    /**
     * A font-size slider. Every tick applies the size live, so the effect is
     * visible immediately, while persisting is left to {@link SizeSetting} --
     * which needs to know whether the tick is part of a drag, since a state
     * write per pixel would be pointless disk traffic.
     * {@link Slider#valueChangingProperty()} is the only signal for that: it
     * is true for the whole span of a mouse drag, and this row is where the
     * two events (a tick, and a drag's release) are mapped onto that type's
     * two entry points.
     *
     * @param halfStepResolution whether the slider snaps to 0.5 as well as
     *                           whole values (the interface slider does,
     *                           because {@link UiFontScale} honours the
     *                           fraction; the terminal slider does not,
     *                           because {@link TerminalThemes} rounds to
     *                           int, so a fractional readout there would
     *                           lie about what the terminal actually renders)
     */
    private static Region sizeRow(String caption, double min, double max,
                                  boolean halfStepResolution, SizeSetting setting) {
        Slider slider = new Slider(min, max, Math.clamp(setting.current(), min, max));
        slider.getStyleClass().add("settings-slider");
        slider.setMajorTickUnit(1);
        slider.setMinorTickCount(halfStepResolution ? 1 : 0);
        slider.setSnapToTicks(true);
        slider.setBlockIncrement(1);

        Label value = new Label(format(slider.getValue()));
        value.getStyleClass().add("settings-value");
        value.setMinWidth(52);

        slider.valueProperty().addListener((obs, old, now) -> {
            value.setText(format(now.doubleValue()));
            setting.changed(now.doubleValue(), slider.isValueChanging());
        });
        slider.valueChangingProperty().addListener((obs, was, changing) -> {
            if (!changing) {
                setting.dragEnded(slider.getValue());
            }
        });

        HBox control = new HBox(10, slider, value);
        control.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(slider, Priority.ALWAYS);
        return labelled(caption, control);
    }

    /** "13 px" / "13.5 px" -- no trailing zero on whole sizes. */
    static String format(double size) {
        return (size == Math.rint(size)
                ? String.valueOf((int) size)
                : String.valueOf(Math.round(size * 10) / 10.0)) + " px";
    }

    private Region worktreesRow(Settings settings) {
        // Reuses the New-worktree modal's classes rather than inventing new
        // ones: this is the same kind of control doing the same job (a path
        // input and its secondary action), and unstyled stock controls fall
        // back to modena's light defaults, which render as a white field and
        // a grey 3D button inside the dark modal.
        TextField field = new TextField();
        field.getStyleClass().add("worktree-field");
        field.setPromptText("Loading…");
        field.setDisable(true);
        Button browse = new Button("Browse…");
        browse.getStyleClass().add("worktree-cancel-button");
        browse.setDisable(true);

        Label hint = new Label("New worktrees are created here.");
        hint.getStyleClass().add("settings-hint");

        // The text last committed (or loaded), so `commit` below can tell a
        // real edit from a no-op close and never fire a redundant save --
        // and so a `commit` re-entered mid-save (see `committing`) has
        // something stable to compare against. Starts at "" to match the
        // field's initial (disabled, empty) text, so a close raced against
        // the load below commits nothing rather than saving an empty
        // directory over whatever is actually on disk.
        String[] lastCommitted = {""};
        // Re-entrancy guard: `commit` disables the field while it is the
        // focus owner, which JavaFX resolves by moving focus off it --
        // synchronously re-entering `commit` via the focusedProperty
        // listener below, before the outer call has even reached
        // saveWorktreesDirectory. Without this, one keystroke's Enter can
        // fire two overlapping saves.
        boolean[] committing = {false};

        // Load off the FX thread (UserConfig reads the file); the controls
        // stay disabled with a "Loading…" prompt until it lands, so the row
        // never shows a stale or empty value as if it were the real one.
        settings.loadWorktreesDirectory().whenComplete((directory, failure) -> Platform.runLater(() -> {
            field.setDisable(false);
            browse.setDisable(false);
            field.setPromptText(System.getProperty("user.home") + "/dev/wt");
            if (failure == null) {
                String text = directory.map(Path::toString).orElse("");
                field.setText(text);
                lastCommitted[0] = text;
            } else {
                UiErrors.show("Could not read the settings file", failure);
            }
        }));

        Runnable commit = () -> {
            String text = field.getText() == null ? "" : field.getText().strip();
            if (committing[0] || text.equals(lastCommitted[0])) {
                return;
            }
            Optional<Path> directory = text.isEmpty() ? Optional.empty() : Optional.of(Path.of(text));
            committing[0] = true;
            field.setDisable(true);
            browse.setDisable(true);
            settings.saveWorktreesDirectory(directory).whenComplete((ignored, failure) ->
                    Platform.runLater(() -> {
                        // saveWorktreesDirectory delegates to
                        // UserConfig.updateAsync: success and failure are its
                        // only two completions, so both are handled here
                        // together, unconditionally re-enabling the row.
                        committing[0] = false;
                        field.setDisable(false);
                        browse.setDisable(false);
                        if (failure != null) {
                            UiErrors.show("Could not save the worktrees directory", failure);
                        } else {
                            lastCommitted[0] = text;
                        }
                    }));
        };
        pendingWorktreesFlush = commit;

        field.setOnAction(e -> commit.run());
        field.focusedProperty().addListener((obs, had, has) -> {
            if (!has) {
                commit.run();
            }
        });

        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose the worktrees directory");
            File chosen = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
            if (chosen != null) {
                field.setText(chosen.getAbsolutePath());
                commit.run();
            }
        });

        HBox control = new HBox(8, field, browse);
        control.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);
        return new VBox(4, labelled("Directory", control), hint);
    }

    /**
     * Disabled until its value arrives, like every other async-backed row
     * here: a checkbox that shows a default it has not read yet would let
     * one click write that default back over the user's real preference.
     */
    private static Region skimRow(Settings settings) {
        CheckBox box = new CheckBox("Open changed files folded to their signatures");
        box.getStyleClass().add("settings-check");
        box.setDisable(true);
        Label hint = new Label("Skim mode. Press z in the Explorer to switch either way.");
        hint.getStyleClass().add("settings-check-hint");
        // The listener is attached only once the stored value has landed:
        // wiring it before would make the very act of showing the loaded
        // value fire a save of the value we just read.
        settings.loadOpenChangedFilesInSkim().whenComplete((value, failure) -> Platform.runLater(() -> {
            box.setSelected(failure == null ? value : true);
            box.setDisable(false);
            // Same shape as worktreesRow's commit: disable for the duration
            // of the write (which also rules out an overlapping save, since
            // a disabled checkbox cannot be clicked again) and surface a
            // failure rather than leaving a ticked box that never reached
            // disk.
            box.selectedProperty().addListener((obs, was, is) -> {
                box.setDisable(true);
                settings.saveOpenChangedFilesInSkim(is).whenComplete((ignored, saveFailure) ->
                        Platform.runLater(() -> {
                            box.setDisable(false);
                            if (saveFailure != null) {
                                UiErrors.show("Could not save the Explorer preference", saveFailure);
                            }
                        }));
            });
        }));
        return new VBox(4, box, hint);
    }
}
