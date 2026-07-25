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
 */
public final class SettingsModal extends VBox {

    /** Everything the modal reads and writes, supplied by the application wiring. */
    public interface Settings {
        UiTheme theme();

        void setTheme(UiTheme theme);

        double uiFontSize();

        /** Applies the interface size live; persistence is the implementation's job. */
        void setUiFontSize(double size);

        double terminalFontSize();

        /** Applies the terminal size to running surfaces live; persistence likewise. */
        void setTerminalFontSize(double size);

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
                sizeRow("Interface size", UiFontScale.MIN_FONT_SIZE, UiFontScale.MAX_FONT_SIZE,
                        settings.uiFontSize(), settings::setUiFontSize),
                sizeRow("Terminal size", TerminalThemes.MIN_FONT_SIZE, TerminalThemes.MAX_FONT_SIZE,
                        settings.terminalFontSize(), settings::setTerminalFontSize),
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
        dark.setToggleGroup(group);
        light.setToggleGroup(group);
        (settings.theme() == UiTheme.LIGHT ? light : dark).setSelected(true);

        // Applied on selection, not on Done: the whole modal is apply-on-change.
        group.selectedToggleProperty().addListener((obs, old, selected) ->
                settings.setTheme(selected == light ? UiTheme.LIGHT : UiTheme.DARK));

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
     * A font-size slider. The value applies live while dragging so the effect
     * is visible, but the persisting callback fires only when the drag ends --
     * a state write per pixel would be pointless disk traffic.
     */
    private static Region sizeRow(String caption, double min, double max, double initial,
                                  DoubleConsumer onChanged) {
        Slider slider = new Slider(min, max, Math.clamp(initial, min, max));
        slider.setMajorTickUnit(1);
        slider.setMinorTickCount(1);
        slider.setSnapToTicks(true);
        slider.setBlockIncrement(1);

        Label value = new Label(format(slider.getValue()));
        value.getStyleClass().add("settings-value");
        value.setMinWidth(52);

        slider.valueProperty().addListener((obs, old, now) -> {
            value.setText(format(now.doubleValue()));
            onChanged.accept(now.doubleValue());
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
