package app.drydock.terminal;

import app.drydock.terminal.ghostty.GhosttyApp;
import app.drydock.terminal.ghostty.GhosttyNativeLibrary;
import app.drydock.terminal.ghostty.GhosttySurface;
import app.drydock.terminal.host.DrydockTerminalHost;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

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
 * {@code -Dapp.drydock.gate0c.autoExit=true} (which the Gradle task does by
 * default) to make the spike run a scripted sequence -- show, resize,
 * type, screenshot, close -- and exit on its own, so its behavior can be
 * checked from logs without a human driving the window.</p>
 */
public final class Gate0cSpike extends Application {

    private static final boolean AUTO_EXIT =
        Boolean.getBoolean("app.drydock.gate0c.autoExit");

    private DrydockTerminalHost host;
    private GhosttyApp app;
    private GhosttySurface surface;
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
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

        host = DrydockTerminalHost.createForCurrentWindow();
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
     * {@code native-host/DrydockTerminalHost.h}) into ghostty calls. This
     * translation is deliberately minimal -- plain typed characters plus a
     * handful of special keys (Enter, Backspace, Tab, Escape, arrows) --
     * sufficient to demonstrate "keyboard input reaches the terminal"
     * (Gate 0C's acceptance criterion); a full key-mapping table
     * (matching Gate 0D's much larger checklist) is out of scope for this
     * task. See docs/native-integration.md.
     *
     * <p><b>Task 6 (Gate 0D) correction:</b> {@code ghostty_input_key_s.keycode}
     * is NOT a {@code GHOSTTY_KEY_*} C enum ordinal (this file originally,
     * incorrectly, translated into one) -- {@code
     * src/apprt/embedded.zig}'s {@code KeyEvent.core()} looks the incoming
     * {@code keycode} up in {@code input.keycodes.entries[].native}, i.e. it
     * must be the raw platform-native virtual keycode (exactly what this
     * method already receives from AppKit). The previous {@code SPECIAL_KEYS}
     * map translated to the wrong number space -- e.g. it sent 53
     * ({@code GHOSTTY_KEY_BACKSPACE}'s ordinal) for the Backspace key, which
     * native macOS keycode 53 actually means "Escape". This was never caught
     * by Gate 0C's automated check because that check only exercised a plain
     * typed character (which goes through the separate {@code
     * ghostty_surface_text} codepath, not {@code keycode} at all); Task 6's
     * headless {@code ghostty_surface_read_text} checks caught it. See
     * docs/native-integration.md, "Task 6 / Gate 0D".</p>
     */
    private void onKeyEvent(int keyCode, int modifierFlags, boolean keyDown, String characters,
                            String unshiftedCharacters) {
        // Unconditional logging of every event this method is called with,
        // requested to debug a real observation: pressing Ctrl+C on a
        // physical keyboard produced no visible effect, and the only
        // adjacent log line was an unrelated "special keyCode=51" (Delete)
        // event. Without this, a genuine Ctrl+C keyDown that produces a
        // non-printable "characters" string (the raw 0x03 ETX byte) would
        // previously log via the "text=" branch below with that control
        // byte embedded literally in the log line -- effectively invisible
        // in a terminal/log viewer, which is indistinguishable from "no
        // event arrived at all". This line always fires first and always
        // escapes non-printable characters, so it can answer definitively
        // whether AppKit ever delivered the Ctrl+C keyDown to Java.
        log("key event: RAW keyCode=" + keyCode + " down=" + keyDown + " modifierFlags=0x"
            + Integer.toHexString(modifierFlags) + " characters=" + escapeForLog(characters)
            + " unshiftedCharacters=" + escapeForLog(unshiftedCharacters));

        if (surface == null) {
            return;
        }
        int mods = translateModifiers(modifierFlags);
        // Any Control/Command combination is a keyboard shortcut (e.g.
        // Ctrl+C for SIGINT, Cmd+C for copy), not typed text -- it must go
        // through ghostty_surface_key (with the modifier bits set), never
        // ghostty_surface_text. Previously, a Ctrl+<letter> combination not
        // in SPECIAL_KEYS fell through to the "characters non-empty" branch
        // below and was sent via sendText -- semantically "pasted" text
        // (see GhosttySurface.sendCharKey's Javadoc on why sendText is paste
        // semantics), and once a shell enables bracketed-paste mode (as
        // observed in Gate 0D), that wraps the raw control byte in
        // `\e[200~...\e[201~` markers, which most shells/programs do not
        // interpret as an interrupt -- a plausible second cause of Ctrl+C
        // appearing to do nothing, independent of whether the event even
        // reaches this method.
        boolean isShortcut = (mods & (GHOSTTY_MODS_CTRL | GHOSTTY_MODS_SUPER)) != 0;
        if (SPECIAL_KEYS.contains(keyCode) || isShortcut) {
            // unshiftedCharacters (AppKit's charactersIgnoringModifiers) is
            // required, not optional, here: Ghostty's Kitty-keyboard-protocol
            // encoder identifies non-functional keys (plain letters/digits,
            // e.g. the 'C' in Ctrl+C) purely by unshifted_codepoint, and
            // silently drops the whole event (writes nothing to the pty) if
            // it's 0 with no fallback text -- see
            // GhosttySurface.sendKey(int,int,boolean,int)'s Javadoc and
            // docs/claude-integration.md ("Incompatibility: Ctrl+C did not
            // cancel an in-progress response", root-caused). SPECIAL_KEYS
            // entries (arrows/Enter/etc.) don't need this -- they're
            // identified by native keycode -> GHOSTTY_KEY_* -> Kitty's own
            // predefined-key table -- but passing it unconditionally here is
            // harmless for them (unshifted defaults to 0 when
            // charactersIgnoringModifiers is empty, identical to before).
            int unshiftedCodepoint = (!keyDown || unshiftedCharacters.isEmpty())
                ? 0
                : unshiftedCharacters.codePointAt(0);
            surface.sendKey(keyCode, mods, keyDown, unshiftedCodepoint);
            log("key event: special/shortcut keyCode=" + keyCode + " down=" + keyDown + " mods=" + mods
                + " unshiftedCodepoint=" + unshiftedCodepoint);
            return;
        }
        if (keyDown && !characters.isEmpty()) {
            surface.sendText(characters);
            log("key event: text=\"" + escapeForLog(characters) + "\"");
        }
    }

    /** Renders control/non-printable characters visibly instead of embedding raw bytes in log output. */
    private static String escapeForLog(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                sb.append(String.format("\\x%02x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // NSEventModifierFlags bit positions (AppKit) -> GHOSTTY_MODS_* bit positions.
    private static final int NS_SHIFT = 1 << 17;
    private static final int NS_CONTROL = 1 << 18;
    private static final int NS_OPTION = 1 << 19;
    private static final int NS_COMMAND = 1 << 20;

    // GHOSTTY_MODS_* bit values, as produced by translateModifiers below.
    private static final int GHOSTTY_MODS_CTRL = 2;
    private static final int GHOSTTY_MODS_SUPER = 8;

    private static int translateModifiers(int nsModifierFlags) {
        int mods = 0;
        if ((nsModifierFlags & NS_SHIFT) != 0) mods |= 1 /* GHOSTTY_MODS_SHIFT */;
        if ((nsModifierFlags & NS_CONTROL) != 0) mods |= 2 /* GHOSTTY_MODS_CTRL */;
        if ((nsModifierFlags & NS_OPTION) != 0) mods |= 4 /* GHOSTTY_MODS_ALT */;
        if ((nsModifierFlags & NS_COMMAND) != 0) mods |= 8 /* GHOSTTY_MODS_SUPER */;
        return mods;
    }

    // Native macOS virtual keycodes this spike recognizes as "special" (not
    // plain text) -- passed straight through to ghostty_surface_key's
    // keycode field unmodified (see onKeyEvent's Javadoc). Verified against
    // third_party/ghostty/src/input/keycodes.zig's macOS ("native", index 4)
    // column, not hand-guessed.
    private static final Set<Integer> SPECIAL_KEYS = Set.of(
        36,  // Return / Enter
        51,  // Delete (Backspace)
        48,  // Tab
        53,  // Escape
        123, // Left arrow
        124, // Right arrow
        125, // Down arrow
        126  // Up arrow
    );

    /**
     * Scripted sequence used when {@code -Dapp.drydock.gate0c.autoExit=true}:
     * type a short string, resize the window, capture a screenshot (best
     * effort -- requires Screen Recording permission on modern macOS; a
     * failure here is logged, not fatal), then close. This lets the
     * process's exit code and log output serve as automated evidence for
     * a human who cannot watch the window live.
     */
    private void runAutomatedSequence(Stage stage) {
        var timeline = new Timeline(
            new KeyFrame(Duration.seconds(1), e -> {
                surface.sendText("echo gate0c\r");
                log("automated: sent test text (direct API call, not a real keystroke)");
            }),
            new KeyFrame(Duration.seconds(2), e -> {
                stage.toFront();
                stage.requestFocus();
                sendRealKeystroke("q");
            }),
            new KeyFrame(Duration.seconds(3), e -> {
                stage.setWidth(1000);
                stage.setHeight(700);
                log("automated: resized stage");
            }),
            new KeyFrame(Duration.seconds(4), e -> captureScreenshot()),
            new KeyFrame(Duration.seconds(5), e -> {
                log("automated: closing");
                // shutdown(), not stage.close() -- see Gate0eSpike's
                // identical fix and docs/phase0-feasibility-report.md:
                // Stage.close() does not fire setOnCloseRequest, so this
                // would skip surface.closeGracefully() and let AppKit tear
                // down the native view (and any live child) directly.
                shutdown();
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

    private boolean shutdownStarted;

    private void shutdown() {
        // Guard against re-entrancy -- see Gate0eSpike.shutdown()'s identical
        // guard for why (stage.close() below triggers JavaFX's implicit-exit
        // Platform.exit(), which re-invokes this class's stop() override).
        if (shutdownStarted) {
            return;
        }
        shutdownStarted = true;
        log("shutting down");
        if (surface != null) {
            GhosttySurface s = surface;
            surface = null;
            // Bounded-wait-then-force-close, see Gate0eSpike.shutdown() and
            // docs/phase0-feasibility-report.md "What does not work".
            s.closeGracefully(5000, 200, this::finishShutdown);
            return;
        }
        finishShutdown();
    }

    private void finishShutdown() {
        if (app != null) {
            app.close();
            app = null;
        }
        if (host != null) {
            host.close();
            host = null;
        }
        log("shutdown complete, no crash");
        stage.close();
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
