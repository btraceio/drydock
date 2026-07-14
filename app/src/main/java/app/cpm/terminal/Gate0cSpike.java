package app.cpm.terminal;

import app.cpm.terminal.ghostty.GhosttyApp;
import app.cpm.terminal.ghostty.GhosttyNativeLibrary;
import app.cpm.terminal.ghostty.GhosttySurface;
import app.cpm.terminal.host.CpmTerminalHost;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gate 0C feasibility spike (plan section 7 / 28 "Task 5"): the smallest
 * possible JavaFX window that embeds one Ghostty terminal surface, with no
 * repository-manager or Claude-specific code.
 *
 * <p>See docs/native-integration.md ("Gate 0C: rendering a terminal
 * surface") for:</p>
 * <ul>
 *   <li>why the "preferred model" (libghostty renders directly into a
 *       native macOS view hosted inside the JavaFX window) was chosen, and
 *       the empirical evidence that JavaFX does not expose that view
 *       through any public API;</li>
 *   <li>exactly what is and isn't verified automatically vs. what requires
 *       a human looking at the screen.</li>
 * </ul>
 *
 * <p>Run via {@code ./gradlew gate0cSpike}. Pass
 * {@code -Dapp.cpm.gate0c.autoExit=true} (which the Gradle task does by
 * default) to make the spike run a scripted sequence -- show, resize,
 * type, screenshot, close -- and exit on its own, so its behavior can be
 * checked from logs without a human driving the window.</p>
 */
public final class Gate0cSpike extends Application {

    private static final boolean AUTO_EXIT =
        Boolean.getBoolean("app.cpm.gate0c.autoExit");

    private CpmTerminalHost host;
    private GhosttyApp app;
    private GhosttySurface surface;

    @Override
    public void start(Stage stage) {
        log("starting");

        var root = new StackPane();
        var scene = new Scene(root, 900, 600);
        stage.setTitle("Gate 0C -- Ghostty terminal surface spike");
        stage.setScene(scene);
        stage.show();
        log("stage shown");

        // The Glass native window (and therefore its NSView) only exists
        // after Stage#show(); creating the host one runLater tick later
        // guarantees Glass has finished window creation, and keeps host
        // creation off the show() call stack itself.
        Platform.runLater(() -> {
            try {
                initializeTerminal(stage, scene, root);
            } catch (Throwable t) {
                log("FAILED to initialize terminal: " + t);
                t.printStackTrace();
                Platform.exit();
            }
        });

        stage.setOnCloseRequest(event -> shutdown());
    }

    private void initializeTerminal(Stage stage, Scene scene, StackPane root) {
        var lookup = GhosttyNativeLibrary.lookup();
        GhosttyApp.ensureProcessInitialized(lookup);
        log("ghostty_init OK");

        host = CpmTerminalHost.createForCurrentWindow();
        log("AppKit host view created and attached to JavaFX window's NSView");

        app = GhosttyApp.create(lookup, () -> Platform.runLater(this::tickAndDraw));
        log("ghostty_app_new OK");

        double scale = stage.getOutputScaleX();
        surface = GhosttySurface.create(app, host, scale);
        log("ghostty_surface_new OK (scale=" + scale + ")");

        resizeTo(scene.getWidth(), scene.getHeight(), scale);
        host.setVisible(true);

        scene.widthProperty().addListener((obs, oldV, newV) ->
            resizeTo(newV.doubleValue(), scene.getHeight(), stage.getOutputScaleX()));
        scene.heightProperty().addListener((obs, oldV, newV) ->
            resizeTo(scene.getWidth(), newV.doubleValue(), stage.getOutputScaleX()));

        host.setKeyEventListener(this::onKeyEvent);

        // Focus the terminal surface as soon as the window is focused, and
        // whenever the window regains focus.
        stage.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                requestFocus();
            }
        });
        requestFocus();

        app.tick();
        surface.draw();
        log("initial ghostty_app_tick + ghostty_surface_draw OK");

        if (AUTO_EXIT) {
            runAutomatedSequence(stage);
        }
    }

    private void requestFocus() {
        host.setFocused(true);
        app.setFocus(true);
        surface.setFocus(true);
        log("focus set (host + app + surface)");
    }

    private void resizeTo(double width, double height, double scale) {
        if (host == null || surface == null) {
            return;
        }
        host.setFrame(0, 0, width, height);
        int pxWidth = (int) Math.round(width * scale);
        int pxHeight = (int) Math.round(height * scale);
        surface.setSize(pxWidth, pxHeight);
        surface.draw();
        log("resized: " + width + "x" + height + " logical -> " + pxWidth + "x" + pxHeight + " px");
    }

    private void tickAndDraw() {
        if (app == null) {
            return;
        }
        app.tick();
        if (surface != null) {
            surface.draw();
        }
    }

    /**
     * Translates a raw AppKit key event (see
     * {@code native-host/CpmTerminalHost.h}) into ghostty calls. This
     * translation is deliberately minimal -- plain typed characters plus a
     * handful of special keys (Enter, Backspace, Tab, Escape, arrows) --
     * sufficient to demonstrate "keyboard input reaches the terminal"
     * (Gate 0C's acceptance criterion); a full key-mapping table
     * (matching Gate 0D's much larger checklist) is out of scope for this
     * task. See docs/native-integration.md.
     */
    private void onKeyEvent(int keyCode, int modifierFlags, boolean keyDown, String characters) {
        if (surface == null) {
            return;
        }
        int mods = translateModifiers(modifierFlags);
        Integer ghosttyKey = SPECIAL_KEYS.get(keyCode);
        if (ghosttyKey != null) {
            surface.sendKey(ghosttyKey, mods, keyDown);
            log("key event: special keyCode=" + keyCode + " down=" + keyDown + " mods=" + mods);
            return;
        }
        if (keyDown && !characters.isEmpty()) {
            surface.sendText(characters);
            log("key event: text=\"" + characters.replace("\r", "\\r").replace("\n", "\\n") + "\"");
        }
    }

    // NSEventModifierFlags bit positions (AppKit) -> GHOSTTY_MODS_* bit positions.
    private static final int NS_SHIFT = 1 << 17;
    private static final int NS_CONTROL = 1 << 18;
    private static final int NS_OPTION = 1 << 19;
    private static final int NS_COMMAND = 1 << 20;

    private static int translateModifiers(int nsModifierFlags) {
        int mods = 0;
        if ((nsModifierFlags & NS_SHIFT) != 0) mods |= 1 /* GHOSTTY_MODS_SHIFT */;
        if ((nsModifierFlags & NS_CONTROL) != 0) mods |= 2 /* GHOSTTY_MODS_CTRL */;
        if ((nsModifierFlags & NS_OPTION) != 0) mods |= 4 /* GHOSTTY_MODS_ALT */;
        if ((nsModifierFlags & NS_COMMAND) != 0) mods |= 8 /* GHOSTTY_MODS_SUPER */;
        return mods;
    }

    // macOS virtual key codes -> GHOSTTY_KEY_* ordinals (see ghostty.h).
    private static final java.util.Map<Integer, Integer> SPECIAL_KEYS = java.util.Map.of(
        36, 58, // Return -> GHOSTTY_KEY_ENTER
        51, 53, // Delete (backspace) -> GHOSTTY_KEY_BACKSPACE
        48, 64, // Tab -> GHOSTTY_KEY_TAB
        53, 120, // Escape -> GHOSTTY_KEY_ESCAPE
        123, 76, // Left arrow -> GHOSTTY_KEY_ARROW_LEFT
        124, 77, // Right arrow -> GHOSTTY_KEY_ARROW_RIGHT
        125, 75, // Down arrow -> GHOSTTY_KEY_ARROW_DOWN
        126, 78  // Up arrow -> GHOSTTY_KEY_ARROW_UP
    );

    /**
     * Scripted sequence used when {@code -Dapp.cpm.gate0c.autoExit=true}:
     * type a short string, resize the window, capture a screenshot (best
     * effort -- requires Screen Recording permission on modern macOS; a
     * failure here is logged, not fatal), then close. This lets the
     * process's exit code and log output serve as automated evidence for
     * a human who cannot watch the window live.
     */
    private void runAutomatedSequence(Stage stage) {
        var timeline = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), e -> {
                surface.sendText("echo gate0c\r");
                log("automated: sent test text (direct API call, not a real keystroke)");
            }),
            new javafx.animation.KeyFrame(javafx.util.Duration.seconds(2), e -> {
                stage.toFront();
                stage.requestFocus();
                sendRealKeystroke("q");
            }),
            new javafx.animation.KeyFrame(javafx.util.Duration.seconds(3), e -> {
                stage.setWidth(1000);
                stage.setHeight(700);
                log("automated: resized stage");
            }),
            new javafx.animation.KeyFrame(javafx.util.Duration.seconds(4), e -> captureScreenshot()),
            new javafx.animation.KeyFrame(javafx.util.Duration.seconds(5), e -> {
                log("automated: closing");
                stage.close();
            })
        );
        timeline.play();
    }

    /**
     * Best-effort: asks macOS System Events to synthesize a real OS-level
     * keystroke, so the automated run can verify (via the "key event:
     * text=" log line from {@link #onKeyEvent}) that keyboard input
     * actually flows AppKit responder chain -> host shim -> ghostty, not
     * just that {@link GhosttySurface#sendText} works in isolation. This
     * requires the process to have Accessibility permission and to
     * actually hold OS-level key focus; a failure (permission denied, or
     * simply no "key event:" log line appearing afterward) is logged, not
     * fatal -- see docs/native-integration.md, "What Gate 0C could vs
     * could not verify automatically".
     */
    private void sendRealKeystroke(String character) {
        try {
            Process p = new ProcessBuilder(
                "osascript", "-e",
                "tell application \"System Events\" to keystroke \"" + character + "\"")
                .inheritIO()
                .start();
            int exit = p.waitFor();
            log("automated: osascript synthetic keystroke '" + character + "' exit=" + exit);
        } catch (IOException | InterruptedException e) {
            log("automated: osascript synthetic keystroke failed: " + e);
        }
    }

    private void captureScreenshot() {
        try {
            Path outDir = Path.of("build");
            Files.createDirectories(outDir);
            Path outFile = outDir.resolve("gate0c-screenshot.png");
            Process p = new ProcessBuilder("screencapture", "-x", outFile.toAbsolutePath().toString())
                .inheritIO()
                .start();
            int exit = p.waitFor();
            log("automated: screencapture exit=" + exit + " -> " + outFile.toAbsolutePath());
        } catch (IOException | InterruptedException e) {
            log("automated: screencapture failed: " + e);
        }
    }

    private void shutdown() {
        log("shutting down");
        if (surface != null) {
            surface.close();
            surface = null;
        }
        if (app != null) {
            app.close();
            app = null;
        }
        if (host != null) {
            host.close();
            host = null;
        }
        log("shutdown complete, no crash");
    }

    @Override
    public void stop() {
        shutdown();
    }

    private static void log(String message) {
        System.out.println("[gate0c] " + message);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
