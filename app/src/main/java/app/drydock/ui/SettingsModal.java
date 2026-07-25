package app.drydock.ui;

import app.drydock.domain.UiTheme;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
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
import java.util.function.DoubleConsumer;

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

        double uiFontSize();

        /** Applies the interface size live; called on every slider tick while dragging. */
        void setUiFontSize(double size);

        /** Persists the interface size; called once, when a drag ends (see {@link #sizeRow}). */
        void commitUiFontSize(double size);

        double terminalFontSize();

        /** Applies the terminal size to running surfaces live; called on every slider tick while dragging. */
        void setTerminalFontSize(double size);

        /** Persists the terminal size; called once, when a drag ends (see {@link #sizeRow}). */
        void commitTerminalFontSize(double size);

        CompletableFuture<Optional<Path>> loadWorktreesDirectory();

        CompletableFuture<Void> saveWorktreesDirectory(Optional<Path> directory);
    }

    private static final double MODAL_WIDTH = 520;

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
                        settings.uiFontSize(), true, settings::setUiFontSize, settings::commitUiFontSize),
                sizeRow("Terminal size", TerminalThemes.MIN_FONT_SIZE, TerminalThemes.MAX_FONT_SIZE,
                        settings.terminalFontSize(), false, settings::setTerminalFontSize,
                        settings::commitTerminalFontSize),
                sectionTitle("Worktrees"),
                worktreesRow(settings),
                footer);
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
     * A font-size slider. The value applies live while dragging (every tick
     * calls {@code onChanged}) so the effect is visible immediately, but
     * {@code onCommitted} -- the persisting callback -- fires only once the
     * drag ends: a state write per pixel would be pointless disk traffic.
     * {@link Slider#valueChangingProperty()} is what distinguishes the two --
     * true for the whole span of a mouse drag -- so a discrete, non-drag
     * change (an arrow key, a click that lands directly on a value with no
     * drag) never sees it go true and instead commits immediately on the one
     * {@code valueProperty} tick it produces, which is correct: there is no
     * burst to debounce.
     *
     * @param halfStepResolution whether the slider snaps to 0.5 as well as
     *                           whole values (the interface slider does,
     *                           because {@link UiFontScale} honours the
     *                           fraction; the terminal slider does not,
     *                           because {@link TerminalThemes} rounds to
     *                           int, so a fractional readout there would
     *                           lie about what the terminal actually renders)
     */
    private static Region sizeRow(String caption, double min, double max, double initial,
                                  boolean halfStepResolution, DoubleConsumer onChanged, DoubleConsumer onCommitted) {
        Slider slider = new Slider(min, max, Math.clamp(initial, min, max));
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
            onChanged.accept(now.doubleValue());
            if (!slider.isValueChanging()) {
                onCommitted.accept(now.doubleValue());
            }
        });
        slider.valueChangingProperty().addListener((obs, was, changing) -> {
            if (!changing) {
                onCommitted.accept(slider.getValue());
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
        TextField field = new TextField();
        field.setPromptText("Loading…");
        field.setDisable(true);
        Button browse = new Button("Browse…");
        browse.setDisable(true);

        Label hint = new Label("New worktrees are created here.");
        hint.getStyleClass().add("settings-hint");

        // Load off the FX thread (UserConfig reads the file); the controls
        // stay disabled with a "Loading…" prompt until it lands, so the row
        // never shows a stale or empty value as if it were the real one.
        settings.loadWorktreesDirectory().whenComplete((directory, failure) -> Platform.runLater(() -> {
            field.setDisable(false);
            browse.setDisable(false);
            field.setPromptText(System.getProperty("user.home") + "/dev/wt");
            if (failure == null) {
                directory.ifPresent(dir -> field.setText(dir.toString()));
            } else {
                UiErrors.show("Could not read the settings file", failure);
            }
        }));

        Runnable commit = () -> {
            String text = field.getText() == null ? "" : field.getText().strip();
            Optional<Path> directory = text.isEmpty() ? Optional.empty() : Optional.of(Path.of(text));
            field.setDisable(true);
            browse.setDisable(true);
            settings.saveWorktreesDirectory(directory).whenComplete((ignored, failure) ->
                    Platform.runLater(() -> {
                        // Every path re-enables: success, failure, and the
                        // early return inside saveWorktreesDirectory.
                        field.setDisable(false);
                        browse.setDisable(false);
                        if (failure != null) {
                            UiErrors.show("Could not save the worktrees directory", failure);
                        }
                    }));
        };

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
}
