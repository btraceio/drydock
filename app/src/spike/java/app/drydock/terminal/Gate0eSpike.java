package app.drydock.terminal;

import app.drydock.terminal.ghostty.GhosttyApp;
import app.drydock.terminal.ghostty.GhosttyNativeLibrary;
import app.drydock.terminal.ghostty.GhosttySurface;
import app.drydock.terminal.host.DrydockTerminalHost;
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
 * Gate 0E feasibility spike (plan section 7 "Gate 0E" / section 28 "Task
 * 7"): runs the real installed {@code claude} CLI inside the Gate 0C/0D
 * terminal surface, in a disposable throwaway git repository (never this
 * project's own repository), and drives as much of the Gate 0E checklist
 * as is safely automatable without a human at the keyboard.
 *
 * <p>Unlike {@link Gate0dSpike} (which asserts hard pass/fail against
 * deterministic shell output), this spike deliberately does NOT assert
 * hard pass/fail: the CLI's exact TUI layout, timing, and model responses
 * are non-deterministic real-world behavior, not something this project
 * controls. Each step instead logs a full screen dump with a clear marker
 * so a human (or the driving agent) can read the transcript afterward and
 * write up findings/incompatibilities in
 * {@code docs/claude-integration.md}, per plan section 28 "Task 7"
 * ("Document every incompatibility before proceeding").</p>
 *
 * <p>Deliberately does NOT do, per plan section 21 "Security Constraints":
 * pass {@code --dangerously-skip-permissions} or any other permission-
 * bypass flag; inspect or copy authentication tokens; modify managed
 * Claude settings; run any repository path through a shell string (the
 * repo path is passed as a plain argument/cwd, never interpolated into a
 * shell command string). Every prompt sent to the nested Claude session is
 * deliberately read-only / non-destructive (arithmetic questions, "list
 * files", "echo an env var") so that even if this machine's global
 * permission mode auto-approves tool calls, nothing beyond a harmless read
 * happens in the throwaway repo.</p>
 */
public final class Gate0eSpike extends Application {

    private static final boolean AUTO_EXIT = Boolean.getBoolean("app.drydock.gate0e.autoExit");
    private static final String CLAUDE_BIN =
        System.getProperty("app.drydock.gate0e.claude", System.getProperty("user.home") + "/.local/bin/claude");
    private static final String TEST_REPO = System.getProperty("app.drydock.gate0e.repo");

    // Native macOS virtual keycodes -- see Gate0dSpike's field Javadoc for
    // why these are native scancodes, not GHOSTTY_KEY_* ordinals.
    private static final int KEY_ENTER = 36;
    private static final int KEY_ESCAPE = 53;
    private static final int KEY_C = 8;
    private static final int KEY_DOWN = 125;
    private static final int MODS_CTRL = 1 << 1;
    private static final int MODS_SHIFT = 1 << 0;

    private DrydockTerminalHost host;
    private GhosttyApp app;
    private GhosttySurface surface;
    private Stage stage;

    private final List<Runnable> steps = new ArrayList<>();
    private int stepIndex;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        if (TEST_REPO == null || TEST_REPO.isBlank()) {
            log("FAILED: -Dapp.drydock.gate0e.repo=<throwaway git repo path> is required");
            Platform.exit();
            return;
        }
        log("starting; claude=" + CLAUDE_BIN + " repo=" + TEST_REPO);

        var root = new StackPane();
        var scene = new Scene(root, 1100, 750);
        stage.setTitle("Gate 0E -- claude CLI spike");
        stage.setScene(scene);
        stage.show();

        Platform.runLater(() -> {
            try {
                // The surface command is a shell string (`sh -c`); quote the
                // externally-supplied binary path (see the step-8 comment).
                initializeTerminal(scene, shellQuote(CLAUDE_BIN), TEST_REPO);
            } catch (Throwable t) {
                log("FAILED to initialize terminal: " + t);
                t.printStackTrace();
                Platform.exit();
            }
        });

        stage.setOnCloseRequest(event -> shutdown());
    }

    private void initializeTerminal(Scene scene, String command, String cwd) {
        var lookup = GhosttyNativeLibrary.lookup();
        GhosttyApp.ensureProcessInitialized(lookup);
        log("ghostty_init OK");

        host = DrydockTerminalHost.createForCurrentWindow();
        app = GhosttyApp.create(lookup, () -> Platform.runLater(this::tickAndDraw));

        double scale = stage.getOutputScaleX();
        surface = GhosttySurface.create(app, host, scale, command, cwd);
        log("ghostty_surface_new OK (scale=" + scale + ", command=" + command + ", cwd=" + cwd + ")");

        host.setFrame(0, 0, scene.getWidth(), scene.getHeight());
        surface.setSize((int) Math.round(scene.getWidth() * scale), (int) Math.round(scene.getHeight() * scale));
        surface.draw();
        host.setVisible(true);
        host.setKeyEventListener((keyCode, modifierFlags, keyDown, characters, unshiftedCharacters) -> { });
        host.setFocused(true);
        app.setFocus(true);
        surface.setFocus(true);
        app.tick();
        surface.draw();

        if (AUTO_EXIT) {
            buildScript();
            runNextStep();
        }
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

    // --- Scripted transcript ---------------------------------------------

    private void buildScript() {
        // 1. Startup + workspace trust prompt + login/config detection.
        dumpAfter(4000, "startup (trust prompt / initial banner expected here)");
        // Accept the trust prompt if shown (default-highlighted option is
        // normally "Yes, proceed" -- Enter accepts it either way; if no
        // trust prompt appeared this Enter is a harmless no-op on an empty
        // prompt line).
        steps.add(() -> {
            surface.sendKey(KEY_ENTER, 0, true);
            surface.sendKey(KEY_ENTER, 0, false);
            scheduleNext(600);
        });
        dumpAfter(2500, "after accepting trust prompt (main REPL banner expected)");

        // 2. Ordinary prompt + streaming output (arithmetic only -- no
        // tool use needed, so this step is identical regardless of this
        // machine's permission-mode configuration).
        steps.add(() -> {
            type("What is 7 + 5? Answer with just the number, no explanation, no tool use.");
            scheduleNext(300);
        });
        dumpAfter(200, "after typing prompt text, before Enter (multiline/input-box rendering check)");
        steps.add(() -> {
            enter();
            scheduleNext(1500);
        });
        dumpAfter(0, "~1.5s after submit (streaming in progress expected)");
        dumpAfter(4000, "~5.5s after submit (response should be complete)");

        // 3. Multiline prompt editing: backslash + Enter should insert a
        // soft newline rather than submit (Claude Code CLI convention).
        steps.add(() -> {
            type("line one\\");
            enter();
            type("line two (should be same message as line one)");
            scheduleNext(400);
        });
        dumpAfter(0, "after backslash-continuation + second line, before final Enter"
            + " (are both lines in one input box, or did the first Enter submit prematurely?)");
        steps.add(() -> {
            enter();
            scheduleNext(1200);
        });
        dumpAfter(4000, "after submitting the two-line prompt");

        // 4. Ctrl+C cancellation mid-stream.
        steps.add(() -> {
            type("Write a 200 word essay about clouds.");
            enter();
            scheduleNext(1200);
        });
        dumpAfter(0, "~1.2s into a longer response (about to Ctrl+C)");
        steps.add(() -> {
            // unshifted codepoint ('c'=99) required so Ghostty's Kitty-keyboard-
            // protocol encoder can identify this key if claude has negotiated
            // that protocol -- see GhosttySurface.sendKey(int,int,boolean,int)'s
            // Javadoc and docs/claude-integration.md.
            surface.sendKey(KEY_C, MODS_CTRL, true, (int) 'c');
            surface.sendKey(KEY_C, MODS_CTRL, false, (int) 'c');
            scheduleNext(800);
        });
        final String[] rightAfterCtrlC = new String[1];
        steps.add(() -> {
            rightAfterCtrlC[0] = surface.readScreenText();
            log("[dump] right after Ctrl+C:\n" + rightAfterCtrlC[0]);
            scheduleNext(2500);
        });
        steps.add(() -> {
            String after = surface.readScreenText();
            log("[dump] 2.5s after Ctrl+C (did output keep growing after the interrupt?):\n" + after);
            boolean stillIdentical = after.equals(rightAfterCtrlC[0]);
            log("[info] screen unchanged since right-after-Ctrl+C: " + stillIdentical
                + " (true is the expected/healthy case -- generation actually stopped)");
            scheduleNext(200);
        });

        // 5. Read-only tool use + resize during output (list files; a
        // response the model must generate token-by-token, giving a window
        // to resize mid-stream).
        //
        // A prior run of this spike found that sending Enter immediately
        // after the previous response's trailing UI (e.g. "Brewed for 7s")
        // was still redrawing sometimes silently dropped the keystroke,
        // leaving the typed text sitting unsubmitted in the input box and
        // corrupting the next step (whose own typed text got appended onto
        // the same unsubmitted line instead of starting fresh). Documented
        // in docs/claude-integration.md as a real finding, not hidden here
        // -- but a leading settle pause plus a submission-confirmed retry
        // is added so the rest of this scripted transcript does not
        // cascade-fail from that one flaky keystroke.
        steps.add(() -> scheduleNext(600));
        steps.add(() -> {
            type("List the files in the current directory (use your tools), "
                + "then separately count slowly from 1 to 10 on its own lines.");
            enter();
            scheduleNext(900);
        });
        steps.add(() -> {
            // Defensive retry: sending Enter again is a harmless no-op if
            // the first one already submitted (it lands on an empty
            // prompt line), but recovers the sequence if it was dropped.
            enter();
            scheduleNext(300);
        });
        dumpAfter(0, "~1.2s in (permission prompt or tool-execution display expected here if one appears)");
        steps.add(() -> {
            // Resize mid-stream: exercise plan checklist item "terminal
            // resizing during output" against a live, actively-redrawing
            // TUI (not an idle shell prompt like Gate 0D's resize check).
            double w = 1500, h = 900, scale = stage.getOutputScaleX();
            host.setFrame(0, 0, w, h);
            surface.setSize((int) Math.round(w * scale), (int) Math.round(h * scale));
            surface.draw();
            log("[info] resized to " + w + "x" + h + " mid-stream, no exception thrown");
            scheduleNext(3000);
        });
        dumpAfter(3000, "after resize + list-files + count response should be complete");

        // 6. Quit via /exit, then relaunch a fresh session in the same repo.
        steps.add(() -> {
            type("/exit");
            enter();
            scheduleNext(1500);
        });
        steps.add(() -> {
            boolean exited = surface.processExited();
            log("[info] process exited after /exit: " + exited);
            scheduleNext(200);
        });
        dumpAfter(500, "screen state right after /exit");

        steps.add(() -> {
            log("[info] relaunching a fresh claude session in the same repo (new surface, same host view)");
            surface.close();
            double scale = stage.getOutputScaleX();
            surface = GhosttySurface.create(app, host, scale, shellQuote(CLAUDE_BIN), TEST_REPO);
            host.setFrame(0, 0, stage.getScene().getWidth(), stage.getScene().getHeight());
            surface.setSize((int) Math.round(stage.getScene().getWidth() * scale),
                (int) Math.round(stage.getScene().getHeight() * scale));
            surface.draw();
            surface.setFocus(true);
            scheduleNext(3500);
        });
        dumpAfter(1000, "relaunch: fresh session startup (does trust re-prompt for an already-trusted dir?)");
        steps.add(() -> {
            surface.sendKey(KEY_ENTER, 0, true);
            surface.sendKey(KEY_ENTER, 0, false);
            scheduleNext(1500);
        });
        dumpAfter(1500, "relaunch: after Enter (in case of a leftover trust/dialog prompt)");

        // 7. claude --resume in the same repo.
        steps.add(() -> {
            type("/exit");
            enter();
            scheduleNext(1200);
        });
        steps.add(() -> {
            log("[info] launching `claude --resume` as a fresh surface");
            surface.close();
            double scale = stage.getOutputScaleX();
            surface = GhosttySurface.create(app, host, scale, shellQuote(CLAUDE_BIN) + " --resume", TEST_REPO);
            host.setFrame(0, 0, stage.getScene().getWidth(), stage.getScene().getHeight());
            surface.setSize((int) Math.round(stage.getScene().getWidth() * scale),
                (int) Math.round(stage.getScene().getHeight() * scale));
            surface.draw();
            surface.setFocus(true);
            scheduleNext(3000);
        });
        dumpAfter(1500, "`claude --resume` output (session picker? auto-resume? nothing to resume?)");
        // If a picker appeared, Down+Enter to try selecting an entry;
        // harmless no-op if there was no picker.
        steps.add(() -> {
            surface.sendKey(KEY_DOWN, 0, true);
            surface.sendKey(KEY_DOWN, 0, false);
            surface.sendKey(KEY_ENTER, 0, true);
            surface.sendKey(KEY_ENTER, 0, false);
            scheduleNext(2500);
        });
        dumpAfter(1500, "after Down+Enter on the --resume screen");
        steps.add(() -> {
            type("/exit");
            enter();
            scheduleNext(1200);
        });

        // 8. Inherited environment variables: spawn a login shell that
        // exports a marker variable, exec claude from inside it, then ask
        // the nested session to echo that variable via its own tool use
        // (proves the marker reached the claude process's environment, not
        // just this JVM's).
        steps.add(() -> {
            log("[info] launching a login shell that exports DRYDOCK_GATE0E_PROBE and execs claude");
            surface.close();
            double scale = stage.getOutputScaleX();
            // libghostty's embedded `command` field is inherently a shell
            // string (see GhosttySurface.create's Javadoc: it always runs
            // via `sh -c`), so argv-list spawning is not expressible here;
            // the class-Javadoc rule against splicing raw values into shell
            // strings is honored by single-quote-wrapping every level of
            // interpolation (same pattern as SessionManager.shellQuote) --
            // CLAUDE_BIN comes from a system property and must never be
            // parsed as shell syntax.
            String cmd = "/bin/zsh -l -c "
                + shellQuote("export DRYDOCK_GATE0E_PROBE=xyz789_gate0e; exec " + shellQuote(CLAUDE_BIN));
            surface = GhosttySurface.create(app, host, scale, cmd, TEST_REPO);
            host.setFrame(0, 0, stage.getScene().getWidth(), stage.getScene().getHeight());
            surface.setSize((int) Math.round(stage.getScene().getWidth() * scale),
                (int) Math.round(stage.getScene().getHeight() * scale));
            surface.draw();
            surface.setFocus(true);
            scheduleNext(3500);
        });
        steps.add(() -> {
            surface.sendKey(KEY_ENTER, 0, true);
            surface.sendKey(KEY_ENTER, 0, false);
            scheduleNext(1500);
        });
        steps.add(() -> {
            type("Run `echo $DRYDOCK_GATE0E_PROBE` using your bash tool and report the exact output.");
            enter();
            scheduleNext(1500);
        });
        dumpAfter(6000, "env-inheritance probe response (look for xyz789_gate0e)");
        steps.add(() -> {
            type("/exit");
            enter();
            scheduleNext(1500);
        });
        steps.add(() -> {
            enter(); // defensive retry, see the comment on the list-files step above
            scheduleNext(1500);
        });
        steps.add(() -> {
            boolean exited = surface.processExited();
            log("[info] process exited after final /exit: " + exited
                + " (if false, the child claude process is still alive going into shutdown -- see"
                + " docs/claude-integration.md for why that matters for session persistence)");
            scheduleNext(200);
        });

        steps.add(() -> {
            log("automated: closing");
            // Call shutdown() directly, NOT stage.close(): JavaFX's
            // Stage.close() does not fire setOnCloseRequest (that handler
            // only runs for user/OS-initiated close requests), so calling
            // it here would skip surface.closeGracefully() entirely and let
            // AppKit tear down the native view (and the still-live Ghostty
            // surface/child process attached to it) out from under us --
            // which is what was actually crashing the JVM, not a gap in the
            // close-fix logic itself. See docs/phase0-feasibility-report.md.
            shutdown();
        });
    }

    private void dumpAfter(int delayMillis, String label) {
        steps.add(() -> {
            String text = surface.readScreenText();
            log("[dump] " + label + ":\n" + text);
            scheduleNext(delayMillis);
        });
    }

    /**
     * Types {@code text} through the real per-character keyboard codepath
     * ({@code ghostty_surface_key}), matching Gate 0D's finding that {@code
     * ghostty_surface_text} is paste semantics (bracketed-paste markers),
     * not typing.
     */
    private void type(String text) {
        surface.sendTypedText(text);
    }

    private void enter() {
        surface.sendKey(KEY_ENTER, 0, true);
        surface.sendKey(KEY_ENTER, 0, false);
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
            scheduleNext(200);
        }
    }

    private boolean shutdownStarted;

    private void shutdown() {
        // Guard against re-entrancy: stage.close() in finishShutdown() below
        // triggers JavaFX's implicit-exit Platform.exit() (last window
        // closed), which calls this class's own stop() override, which
        // calls shutdown() again -- without this guard that second call
        // would re-run finishShutdown()/stage.close() while the first call
        // is still unwinding.
        if (shutdownStarted) {
            return;
        }
        shutdownStarted = true;
        log("shutting down");
        if (surface != null) {
            GhosttySurface s = surface;
            surface = null;
            // Bounded-wait-then-force-close (docs/phase0-feasibility-report.md
            // "What does not work"): closing a surface via close() while its
            // child process is still alive has been observed to kill the
            // whole JVM. closeGracefully requests Ctrl+D and waits up to 5s
            // for the process to actually exit before falling back to close().
            s.closeGracefully(5000, 200, () -> finishShutdown());
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

    /**
     * Wraps {@code value} as a single POSIX shell single-quoted argument,
     * safe against embedded shell metacharacters (mirrors
     * {@code SessionManager.shellQuote}, which is package-private there).
     */
    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void log(String message) {
        System.out.println("[gate0e] " + message);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
