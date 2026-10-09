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
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

        /**
         * The jdt.ls directory (exact Java references, tier 3), or empty
         * when tier 3 is not configured. Loaded off the FX thread like the
         * worktrees directory.
         */
        CompletableFuture<Optional<Path>> loadLanguageServerDirectory();

        /**
         * Validates and saves the jdt.ls directory. Completes with {@link
         * LanguageServerOutcome.Refused} for a validation refusal -- shown
         * INLINE by the row, never an error dialog (spec section 3: the
         * refusal is quiet; nothing about a missing server is an error the
         * reviewer has to dismiss) -- and exceptionally for an I/O failure.
         */
        CompletableFuture<LanguageServerOutcome> saveLanguageServerDirectory(Optional<Path> directory);
    }

    /**
     * The outcome of committing the language-server directory: saved, or
     * refused with the reason the row shows inline. A view-side twin of the
     * config-side result so this class stays a pure view -- the application
     * wiring maps between them.
     */
    public sealed interface LanguageServerOutcome {

        LanguageServerOutcome SAVED = new Saved();

        record Saved() implements LanguageServerOutcome {
        }

        record Refused(String reason) implements LanguageServerOutcome {
        }
    }

    private static final double MODAL_WIDTH = 520;

    /**
     * Flushes pending text-field edits -- today the worktrees directory and
     * the language-server directory, each row registering its own commit.
     * Wired by those rows and invoked by {@code ModalLayer}'s {@code
     * onClosed} callback (see {@link #flushPendingEdit}) so a typed value is
     * committed no matter which of Done/×/Esc/backdrop-click closes the
     * modal -- only Done and × happen to move focus off the field first.
     * Empty until the rows run. Each row's commit is idempotent, so running
     * it after that row already committed via focus loss is harmless.
     */
    private final List<Runnable> pendingEditFlushes = new ArrayList<>();

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
                sectionTitle("Language server"),
                languageServerRow(settings),
                footer);
    }

    /**
     * Commits a pending edit in any text-field row, if one is pending.
     * Meant to be passed as {@code ModalLayer}'s {@code onClosed} callback:
     * Esc and a backdrop click hide the modal without ever moving focus
     * off the text fields, so a field's own focus-lost commit never fires
     * and a typed path would otherwise be silently discarded. Idempotent
     * per row -- see the rows' {@code commit} -- so calling this after Done
     * or × (which already committed via focus loss) is harmless.
     */
    public void flushPendingEdit() {
        pendingEditFlushes.forEach(Runnable::run);
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
            // A real commit attempt: clear any earlier invalid-path message.
            hint.getStyleClass().setAll("settings-hint");
            hint.setText("New worktrees are created here.");
            Optional<Path> directory;
            try {
                directory = text.isEmpty() ? Optional.empty() : Optional.of(Path.of(text));
            } catch (InvalidPathException invalid) {
                // Inline, before anything is disabled or saved: the row stays
                // usable and the user fixes the text here.
                hint.getStyleClass().setAll("worktree-error");
                hint.setText("Not a valid path: " + invalid.getReason());
                return;
            }
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
        pendingEditFlushes.add(commit);

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
        // Set while the listener below puts a failed save's tick back, so the
        // revert is not mistaken for the reader changing their mind again.
        boolean[] reverting = {false};
        settings.loadOpenChangedFilesInSkim().whenComplete((value, failure) -> Platform.runLater(() -> {
            box.setSelected(failure == null ? value : true);
            box.setDisable(false);
            // Same shape as worktreesRow's commit: disable for the duration
            // of the write (which also rules out an overlapping save, since
            // a disabled checkbox cannot be clicked again) and surface a
            // failure rather than leaving a ticked box that never reached
            // disk.
            box.selectedProperty().addListener((obs, was, is) -> {
                if (reverting[0]) {
                    return;
                }
                box.setDisable(true);
                settings.saveOpenChangedFilesInSkim(is).whenComplete((ignored, saveFailure) ->
                        Platform.runLater(() -> {
                            box.setDisable(false);
                            if (saveFailure != null) {
                                // Put the tick back where disk still has it.
                                // Leaving it on the value that failed to save
                                // claims a setting that is not in effect, and
                                // the reader would have to toggle twice to
                                // retry. The guard stops the revert being
                                // read as a fresh edit and saving again.
                                reverting[0] = true;
                                box.setSelected(was);
                                reverting[0] = false;
                                UiErrors.show("Could not save the Explorer preference", saveFailure);
                            }
                        }));
            });
        }));
        return new VBox(4, box, hint);
    }

    /**
     * The "jdt.ls directory" row (spec section 3): async-loaded like the
     * worktrees directory, but the commit VALIDATES before it saves, so it
     * needs visible progress ("Validating…", shown synchronously at commit
     * -- house rule: the click visibly does something before the result
     * arrives) and inline outcome text. Every outcome lands inline in the
     * status label -- including a validation refusal, which is never an
     * {@code UiErrors} dialog (spec: nothing about a missing server is an
     * error the reviewer has to dismiss) -- and every completion path,
     * success, refusal, and failure alike, re-enables the controls: no
     * stranded spinner. {@code javaHome} deliberately has no row here; it
     * stays hand-editable in config.json and the save preserves it.
     */
    private Region languageServerRow(Settings settings) {
        TextField field = new TextField();
        // worktree-field/worktree-cancel-button for the same reasons as
        // worktreesRow (a dark-modal input and its secondary action);
        // language-server-* are lookup hooks for the row's own tests, since
        // both rows would otherwise share every style class.
        field.getStyleClass().addAll("worktree-field", "language-server-field");
        field.setPromptText("Loading…");
        field.setDisable(true);
        Button browse = new Button("Browse…");
        browse.getStyleClass().addAll("worktree-cancel-button", "language-server-browse");
        browse.setDisable(true);

        Label hint = new Label("Optional. The unpacked jdt.ls directory (config_mac and plugins/), "
                + "for exact Java references. The JDK it runs under (javaHome) is set by "
                + "editing ~/.drydock/config.json.");
        hint.getStyleClass().add("settings-hint");
        hint.setWrapText(true);

        // The inline outcome text: "Validating…" while the save is in flight,
        // then the refusal reason, a quiet "Saved.", or a failure message.
        Label status = new Label("");
        status.getStyleClass().addAll("settings-hint", "language-server-status");

        // Same shape as worktreesRow: the text last committed (or loaded), so
        // a close raced against the load commits nothing, and a re-entered
        // commit has something stable to compare against.
        String[] lastCommitted = {""};
        boolean[] committing = {false};

        settings.loadLanguageServerDirectory().whenComplete((directory, failure) -> Platform.runLater(() -> {
            field.setDisable(false);
            browse.setDisable(false);
            field.setPromptText("Not configured — exact references stay lexical");
            if (failure == null) {
                String text = directory.map(Path::toString).orElse("");
                field.setText(text);
                lastCommitted[0] = text;
            } else {
                // Inline, not a dialog, and the row stays usable: the user can
                // still type a path and retry the save.
                status.getStyleClass().setAll("worktree-error", "language-server-status");
                status.setText("Could not read the settings file: " + UiErrors.message(failure));
            }
        }));

        Runnable commit = () -> {
            String text = field.getText() == null ? "" : field.getText().strip();
            if (committing[0] || text.equals(lastCommitted[0])) {
                return; // a no-op close or a re-entered focus-loss: no progress to clear
            }
            Optional<Path> directory;
            try {
                directory = text.isEmpty() ? Optional.empty() : Optional.of(Path.of(text));
            } catch (InvalidPathException invalid) {
                // Inline, never a dialog, and before committing[0]: nothing is
                // disabled or saved, so the row stays usable for a retry.
                status.getStyleClass().setAll("worktree-error", "language-server-status");
                status.setText("Not a valid path: " + invalid.getReason());
                return;
            }
            committing[0] = true;
            field.setDisable(true);
            browse.setDisable(true);
            // Synchronously, so the commit visibly does something the moment
            // it fires; cleared or replaced on every completion below.
            status.getStyleClass().setAll("settings-hint", "language-server-status");
            status.setText("Validating…");
            settings.saveLanguageServerDirectory(directory).whenComplete((outcome, failure) ->
                    Platform.runLater(() -> {
                        // Success, refusal, and failure all re-enable the row:
                        // nothing here may leave the controls disabled.
                        committing[0] = false;
                        field.setDisable(false);
                        browse.setDisable(false);
                        if (failure != null) {
                            status.getStyleClass().setAll("worktree-error", "language-server-status");
                            status.setText("Could not save the language server path: "
                                    + UiErrors.message(failure));
                        } else if (outcome instanceof LanguageServerOutcome.Refused refused) {
                            // Inline, never a dialog: the refusal names what was
                            // wrong with the directory so the user can fix it here.
                            status.getStyleClass().setAll("worktree-error", "language-server-status");
                            status.setText(refused.reason());
                        } else {
                            status.getStyleClass().setAll("settings-hint", "language-server-status");
                            status.setText("Saved.");
                            lastCommitted[0] = text;
                        }
                    }));
        };
        pendingEditFlushes.add(commit);

        field.setOnAction(e -> commit.run());
        field.focusedProperty().addListener((obs, had, has) -> {
            if (!has) {
                commit.run();
            }
        });

        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose the jdt.ls directory");
            File chosen = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
            if (chosen != null) {
                field.setText(chosen.getAbsolutePath());
                commit.run();
            }
        });

        HBox control = new HBox(8, field, browse);
        control.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);
        return new VBox(4, labelled("jdt.ls directory", control), hint, status);
    }
}
