package app.cpm.terminal;

import app.cpm.terminal.ghostty.GhosttyApp;
import app.cpm.terminal.ghostty.GhosttyNativeLibrary;
import app.cpm.terminal.ghostty.GhosttySurface;
import app.cpm.terminal.host.CpmTerminalHost;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Gate 0D feasibility spike (plan section 7 / 28 "Task 6"): spawns
 * {@code /bin/zsh -l} inside the Gate 0C terminal surface and works through
 * as much of the manual checklist (plan section 7 "Gate 0D" and section
 * 22.4) as can be verified without a human at the keyboard.
 *
 * <p>The key trick that makes this possible headlessly is {@link
 * GhosttySurface#readScreenText()} (new in this task): rather than only
 * being able to say "the draw call didn't crash" (Gate 0C's evidence
 * ceiling), each step below sends real input (via {@code
 * ghostty_surface_text}/{@code ghostty_surface_key} -- the same native
 * entry points a real AppKit keystroke reaches, per Gate 0C's verified
 * key-routing path) and then reads back the actual rendered terminal
 * content to assert a specific, hard-to-fake outcome (a marker string
 * appearing in a command's output, {@code $COLUMNS} changing after a
 * resize, the child process actually exiting).</p>
 *
 * <p>What this deliberately does NOT prove, and why (see
 * docs/native-integration.md and docs/manual-terminal-checklist.md for the
 * full breakdown):</p>
 * <ul>
 *   <li>that glyphs are drawn <em>correctly</em> on screen (colour, font
 *       shaping, wide-character cell allocation) -- {@code read_text}
 *       returns decoded cell text, not pixels;</li>
 *   <li>anything requiring the real macOS input stack (physical Option-key
 *       combinations, Cmd+C/Cmd+V through the system pasteboard, mouse-drag
 *       selection, trackpad/mouse scrolling) -- this spike drives ghostty's
 *       C API directly, which is faithful for keyboard *codepaths* but not
 *       for the OS-level gestures themselves;</li>
 *   <li>application-lifecycle behavior (sleep/wake, external display
 *       disconnect, Retina scale changes, Cmd+Tab hide/show) which has no
 *       meaningful headless equivalent.</li>
 * </ul>
 */
public final class Gate0dSpike extends Application {

    private static final boolean AUTO_EXIT = Boolean.getBoolean("app.cpm.gate0d.autoExit");

    // Native macOS virtual keycodes (NOT GHOSTTY_KEY_* C enum ordinals --
    // see GhosttySurface.sendCharKey's Javadoc and Gate0cSpike.onKeyEvent's
    // Javadoc for the Task 6 finding that ghostty_surface_key's `keycode`
    // field is looked up against the platform's native scancode table,
    // src/input/keycodes.zig's macOS ("native") column -- verified there,
    // not hand-guessed).
    private static final int KEY_ENTER = 36;
    private static final int KEY_BACKSPACE = 51;
    private static final int KEY_ESCAPE = 53;
    private static final int KEY_ARROW_LEFT = 123;
    private static final int KEY_C = 8;
    private static final int KEY_D = 2;
    // GHOSTTY_MODS_CTRL, verified in ghostty.h's ghostty_input_mods_e.
    private static final int MODS_CTRL = 1 << 1;

    private CpmTerminalHost host;
    private GhosttyApp app;
    private GhosttySurface surface;
    private Stage stage;

    private final List<Runnable> steps = new ArrayList<>();
    private int stepIndex;
    private int passCount;
    private int failCount;
    private int skipCount;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        log("starting");

        var root = new StackPane();
        var scene = new Scene(root, 900, 600);
        stage.setTitle("Gate 0D -- interactive zsh spike");
        stage.setScene(scene);
        stage.show();
        log("stage shown");

        Platform.runLater(() -> {
            try {
                initializeTerminal(stage, scene);
            } catch (Throwable t) {
                log("FAILED to initialize terminal: " + t);
                t.printStackTrace();
                Platform.exit();
            }
        });

        stage.setOnCloseRequest(event -> shutdown());
    }

    private void initializeTerminal(Stage stage, Scene scene) {
        var lookup = GhosttyNativeLibrary.lookup();
        GhosttyApp.ensureProcessInitialized(lookup);
        log("ghostty_init OK");

        host = CpmTerminalHost.createForCurrentWindow();
        app = GhosttyApp.create(lookup, () -> Platform.runLater(this::tickAndDraw));
        log("ghostty_app_new OK");

        double scale = stage.getOutputScaleX();
        // Spawn /bin/zsh -l explicitly, per plan section 28 "Task 6" (rather
        // than relying on libghostty's own default-shell resolution, which
        // Gate 0C left untested -- see GhosttySurface.create's Javadoc for
        // why "command" always runs via a shell wrapper, making
        // "/bin/zsh -l" a valid value here).
        String home = System.getProperty("user.home");
        surface = GhosttySurface.create(app, host, scale, "/bin/zsh -l", home);
        log("ghostty_surface_new OK (scale=" + scale + ", command=/bin/zsh -l, cwd=" + home + ")");

        resizeTo(scene.getWidth(), scene.getHeight(), scale);
        host.setVisible(true);
        host.setKeyEventListener((keyCode, modifierFlags, keyDown, characters) -> { });
        host.setFocused(true);
        app.setFocus(true);
        surface.setFocus(true);

        app.tick();
        surface.draw();
        log("initial ghostty_app_tick + ghostty_surface_draw OK");

        if (AUTO_EXIT) {
            buildChecklist(stage);
            runNextStep();
        }
    }

    private void resizeTo(double width, double height, double scale) {
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

    // --- Checklist steps -------------------------------------------------

    private void buildChecklist(Stage stage) {
        // Step 0: wait for the login shell to start and render a prompt.
        // (This delay must run *before* the first check -- a real bug caught
        // during development: with no leading wait, this check ran on the
        // very first animation frame, before the pty had produced any
        // output at all, and always failed. Fixed by making step 0 itself
        // the wait+check, scheduled after an initial pause.)
        steps.add(() -> scheduleNext(1500));
        steps.add(() -> {
            String text = surface.readScreenText();
            check("shell prompt rendering", !text.isBlank(),
                "terminal viewport has non-blank content after zsh startup",
                text);
            scheduleNext(400);
        });

        // Step 1: ordinary typing + Return.
        steps.add(() -> {
            type("echo GATE0D_MARK1");
            enter();
            scheduleNext(800);
        });
        steps.add(() -> {
            String text = surface.readScreenText();
            check("ordinary typing + Return", hasOutputLine(text, "GATE0D_MARK1"),
                "a terminal row is exactly \"GATE0D_MARK1\" (the executed echo's own output row,"
                    + " not just the typed command line -- proves Return actually submitted it)", text);
            scheduleNext(200);
        });

        // Step 2: Backspace -- type a wrong word, erase it with Backspace,
        // type the right one, and confirm only the corrected command ran.
        steps.add(() -> {
            type("echo WRONGWORD");
            scheduleNext(200);
        });
        steps.add(() -> {
            for (int i = 0; i < "WRONGWORD".length(); i++) {
                surface.sendKey(KEY_BACKSPACE, 0, true);
                surface.sendKey(KEY_BACKSPACE, 0, false);
            }
            type("GATE0D_MARK2_RIGHT");
            enter();
            scheduleNext(800);
        });
        steps.add(() -> {
            String text = surface.readScreenText();
            boolean gotRight = hasOutputLine(text, "GATE0D_MARK2_RIGHT");
            boolean gotWrong = text.contains("WRONGWORD");
            check("Backspace", gotRight && !gotWrong,
                "corrected command's output row present, erased word absent anywhere on screen",
                text);
            scheduleNext(200);
        });

        // Step 3: arrow keys -- move the cursor left before inserting text,
        // proving cursor movement (not just append-at-end) actually works.
        steps.add(() -> {
            type("echo AB");
            surface.sendKey(KEY_ARROW_LEFT, 0, true);
            surface.sendKey(KEY_ARROW_LEFT, 0, false);
            surface.sendKey(KEY_ARROW_LEFT, 0, true);
            surface.sendKey(KEY_ARROW_LEFT, 0, false);
            type("GATE0D_MARK3_");
            enter();
            scheduleNext(800);
        });
        steps.add(() -> {
            String text = surface.readScreenText();
            check("arrow keys (Left x2 before insert)", hasOutputLine(text, "GATE0D_MARK3_AB"),
                "output row is \"GATE0D_MARK3_AB\" (cursor moved left of \"AB\" before insertion)"
                    + " rather than \"ABGATE0D_MARK3_\" (would mean arrows were ignored)",
                text);
            scheduleNext(200);
        });

        // Step 4: coloured (SGR) output -- can only verify the text portion
        // survived escape-sequence parsing, not that red pixels were drawn.
        steps.add(() -> {
            type("printf '\\033[31mGATE0D_MARK4_COLOR\\033[0m\\n'");
            enter();
            scheduleNext(800);
        });
        steps.add(() -> {
            String text = surface.readScreenText();
            check("coloured (SGR) output -- text survives escape parsing", hasOutputLine(text, "GATE0D_MARK4_COLOR"),
                "printf's own output row is exactly the marker (colour attribute itself NOT verified headlessly)",
                text);
            scheduleNext(200);
        });

        // Step 5: Unicode + emoji.
        steps.add(() -> {
            type("echo GATE0D_MARK5_café_☃_😀");
            enter();
            scheduleNext(800);
        });
        steps.add(() -> {
            String text = surface.readScreenText();
            boolean outputRow = hasOutputLine(text, "GATE0D_MARK5_café_☃_😀");
            boolean hasAccent = text.contains("café");
            boolean hasSnowman = text.contains("☃");
            boolean hasEmoji = text.contains("😀");
            check("Unicode (accented char + snowman + emoji)", outputRow,
                "echo's own output row exactly matches the accented/snowman/emoji marker"
                    + " (accent=" + hasAccent + " snowman=" + hasSnowman + " emoji=" + hasEmoji + ")", text);
            scheduleNext(200);
        });

        // Step 6: resizing -- confirm the resize actually reaches the pty
        // (SIGWINCH -> shell's $COLUMNS), not just that ghostty_surface_set_size
        // was called without crashing (which Gate 0C already covered).
        final String[] columnsBefore = new String[1];
        steps.add(() -> {
            type("echo GATE0D_COLS_BEFORE_$COLUMNS");
            enter();
            scheduleNext(700);
        });
        steps.add(() -> {
            String text = surface.readScreenText();
            columnsBefore[0] = extractAfter(text, "GATE0D_COLS_BEFORE_");
            log("columns before resize: " + columnsBefore[0]);
            resizeTo(1500, 900, stage.getOutputScaleX());
            scheduleNext(500);
        });
        steps.add(() -> {
            type("echo GATE0D_COLS_AFTER_$COLUMNS");
            enter();
            scheduleNext(700);
        });
        steps.add(() -> {
            String text = surface.readScreenText();
            String after = extractAfter(text, "GATE0D_COLS_AFTER_");
            boolean changed = columnsBefore[0] != null && after != null
                && !columnsBefore[0].equals(after) && Integer.parseInt(after) > Integer.parseInt(columnsBefore[0]);
            check("resizing propagates to the shell ($COLUMNS)", changed,
                "COLUMNS before=" + columnsBefore[0] + " after=" + after + " (window widened, expect after > before)",
                text);
            scheduleNext(200);
        });

        // Step 7: vim / alternate-screen full-screen TUI (best-effort --
        // only run if vim is actually installed).
        steps.add(() -> {
            type("command -v vim >/dev/null 2>&1 && echo GATE0D_VIM_PRESENT || echo GATE0D_VIM_ABSENT");
            enter();
            scheduleNext(800);
        });
        steps.add(() -> {
            String text = surface.readScreenText();
            if (text.contains("GATE0D_VIM_PRESENT")) {
                type("vim");
                enter();
                scheduleNext(1200);
            } else {
                skip("vim / alternate-screen TUI", "vim not found on PATH in this shell environment");
                scheduleNext(0);
            }
        });
        steps.add(() -> {
            if (!steps_skippedVim) {
                String duringVim = surface.readScreenText();
                // Exit vim unconditionally (":q!" + Enter after Escape) so the
                // shell is left in a clean state for the remaining steps,
                // regardless of whether the alt-screen check below passes.
                surface.sendKey(KEY_ESCAPE, 0, true);
                surface.sendKey(KEY_ESCAPE, 0, false);
                type(":q!");
                enter();
                boolean looksLikeVim = duringVim.contains("~") || duringVim.toUpperCase().contains("VIM");
                check("vim / alternate-screen TUI launches", looksLikeVim,
                    "screen content while vim was running looked like vim's alternate-screen UI"
                        + " (heuristic: contains '~' empty-line markers or the string VIM)",
                    duringVim);
            }
            scheduleNext(800);
        });
        steps.add(() -> {
            if (!steps_skippedVim) {
                type("echo GATE0D_MARK7_AFTER_VIM");
                enter();
            }
            scheduleNext(800);
        });
        steps.add(() -> {
            if (!steps_skippedVim) {
                String text = surface.readScreenText();
                check("shell usable again after quitting vim", hasOutputLine(text, "GATE0D_MARK7_AFTER_VIM"),
                    "shell echoed and ran a marker command normally after :q!", text);
            }
            scheduleNext(200);
        });

        // Step 8: Ctrl+C -- interrupt a long-running foreground command.
        steps.add(() -> {
            type("sleep 30; echo GATE0D_SLEEP_FINISHED_NORMALLY");
            enter();
            scheduleNext(1000);
        });
        steps.add(() -> {
            surface.sendKey(KEY_C, MODS_CTRL, true);
            surface.sendKey(KEY_C, MODS_CTRL, false);
            type("echo GATE0D_MARK8_AFTER_CTRLC");
            enter();
            scheduleNext(1200);
        });
        steps.add(() -> {
            String text = surface.readScreenText();
            boolean promptReturnedQuickly = hasOutputLine(text, "GATE0D_MARK8_AFTER_CTRLC");
            boolean sleepFinishedNormally = hasOutputLine(text, "GATE0D_SLEEP_FINISHED_NORMALLY");
            check("Ctrl+C interrupts a foreground command", promptReturnedQuickly && !sleepFinishedNormally,
                "shell actually ran a new command ~2s after Ctrl+C (\"sleep 30\" alone would not have"
                    + " finished/printed its trailing echo in that time if Ctrl+C had not interrupted it)",
                text);
            scheduleNext(200);
        });

        // Step 9 (last): Ctrl+D on an empty line exits the login shell.
        steps.add(() -> {
            check("process alive before Ctrl+D", !surface.processExited(),
                "ghostty_surface_process_exited() is false while zsh is still running", null);
            surface.sendKey(KEY_D, MODS_CTRL, true);
            surface.sendKey(KEY_D, MODS_CTRL, false);
            scheduleNext(1200);
        });
        steps.add(() -> {
            boolean exited = surface.processExited();
            check("Ctrl+D exits the shell (process exit)", exited,
                "ghostty_surface_process_exited() is true after sending Ctrl+D on an empty prompt line", null);
            scheduleNext(500);
        });

        steps.add(() -> {
            log("automated: closing");
            // shutdown(), not stage.close() -- see Gate0eSpike's identical
            // fix and docs/phase0-feasibility-report.md: Stage.close() does
            // not fire setOnCloseRequest, so calling it directly here would
            // skip closeGracefully() and let AppKit tear down the native
            // view (and any still-live child process) out from under us.
            shutdown();
        });
    }

    /**
     * Types {@code text} through the real per-character keyboard codepath
     * ({@link GhosttySurface#sendTypedText}), not {@code
     * ghostty_surface_text}'s paste codepath -- see that method's Javadoc
     * for why this distinction mattered in practice for this checklist.
     */
    private void type(String text) {
        text.codePoints().forEach(cp -> {
            boolean consumed = surface.sendCharKey(cp, 0);
            if (!consumed) {
                log("DEBUG: typed char '" + new String(Character.toChars(cp)) + "' NOT consumed by ghostty_surface_key");
            }
        });
    }

    private void enter() {
        boolean consumed = surface.sendKey(KEY_ENTER, 0, true);
        if (!consumed) {
            log("DEBUG: Enter press NOT consumed by ghostty_surface_key");
        }
        surface.sendKey(KEY_ENTER, 0, false);
    }

    private boolean steps_skippedVim;

    /**
     * True if some terminal row, trimmed, equals {@code expected} exactly.
     *
     * <p>Deliberately stricter than {@code String.contains}: a check that
     * only asked "does the marker substring appear anywhere on screen"
     * would trivially pass the moment the marker is <em>typed</em> (it's
     * part of the command line itself), even if Enter never actually
     * submitted the command and it never ran -- exactly the false-positive
     * this project's own Gate 0D development run hit (see
     * docs/native-integration.md, "Task 6 / Gate 0D"). A command's actual
     * output renders on its own terminal row with nothing else on it,
     * distinct from the "prompt + typed command" row that precedes it, so
     * requiring an exact (trimmed) row match is real evidence the shell
     * executed the command and printed its output, not just that the
     * characters reached the screen somehow.</p>
     */
    private static boolean hasOutputLine(String screenText, String expected) {
        for (String line : screenText.split("\n", -1)) {
            if (line.trim().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private String extractAfter(String haystack, String marker) {
        int idx = haystack.lastIndexOf(marker);
        if (idx < 0) {
            return null;
        }
        int start = idx + marker.length();
        int end = start;
        while (end < haystack.length() && Character.isDigit(haystack.charAt(end))) {
            end++;
        }
        return end > start ? haystack.substring(start, end) : null;
    }

    private void scheduleNext(int delayMillis) {
        PauseTransition pause = new PauseTransition(Duration.millis(Math.max(delayMillis, 1)));
        pause.setOnFinished(e -> runNextStep());
        pause.play();
    }

    private void runNextStep() {
        if (stepIndex >= steps.size()) {
            return;
        }
        Runnable step = steps.get(stepIndex++);
        try {
            step.run();
        } catch (Throwable t) {
            log("FAILED step " + (stepIndex - 1) + ": " + t);
            t.printStackTrace();
            failCount++;
            scheduleNext(200);
        }
    }

    private void check(String name, boolean pass, String detail, String screenText) {
        if (pass) {
            passCount++;
            log("[PASS] " + name + " -- " + detail);
        } else {
            failCount++;
            log("[FAIL] " + name + " -- " + detail);
        }
        if (screenText != null) {
            log("       screen text: " + screenText.replace("\n", "\\n").replace("\r", "\\r"));
        }
    }

    private void skip(String name, String reason) {
        skipCount++;
        steps_skippedVim = true;
        log("[SKIP] " + name + " -- " + reason);
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
        log("RESULTS: pass=" + passCount + " fail=" + failCount + " skip=" + skipCount);
        if (surface != null) {
            GhosttySurface s = surface;
            surface = null;
            // Bounded-wait-then-force-close, see Gate0eSpike.shutdown() and
            // docs/phase0-feasibility-report.md "What does not work": this
            // checklist's own last step already exits the shell via Ctrl+D,
            // but shutdown() can also run from a window-close event at any
            // point (e.g. a human tester closing the window mid-checklist),
            // so it must not assume the child has already exited.
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
        System.out.println("[gate0d] " + message);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
