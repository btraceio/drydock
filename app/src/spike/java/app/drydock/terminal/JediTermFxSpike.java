package app.drydock.terminal;

import com.techsenger.jeditermfx.core.ProcessTtyConnector;
import com.techsenger.jeditermfx.core.util.TermSize;
import com.techsenger.jeditermfx.ui.JediTermFxWidget;
import com.techsenger.jeditermfx.ui.settings.DefaultSettingsProvider;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Cross-platform terminal spike: a real embedded terminal rendered in pure
 * JavaFX (JediTermFX) driven by a pty4j PTY -- the candidate Windows backend,
 * since the macOS path (full libghostty Metal surface + AppKit host shim)
 * cannot run on Windows (see docs/windows-terminal-spike.md).
 *
 * <p>Unlike the Ghostty gate tasks this spike is <em>pure Java + pty4j's own
 * native PTY libs</em>: no Zig, no Xcode, no libghostty, no AppKit. It runs the
 * host's native shell -- {@code /bin/bash --login} on macOS/Linux,
 * {@code cmd.exe} on Windows (ConPTY via pty4j) -- so the same code path is
 * exercised on every platform, which is the whole point of evaluating it as
 * the Windows backend.</p>
 *
 * <p>Run via {@code ./gradlew jeditermSpike}. By default the gate task sets
 * {@code -Dapp.drydock.jediterm.autoExit=true}, which starts the shell, sends
 * {@code echo DRYDOCK_SPIKE_OK} + {@code exit}, and exits when the process
 * does -- so pass/fail is readable from logs without a human driving the
 * window. Pass {@code -Papp.drydock.jediterm.interactive} to leave a live
 * shell open instead.</p>
 *
 * <p>What this spike verifies: that JediTermFX + pty4j resolve and run against
 * the project's JDK 26 / JavaFX 26 (classpath-mode, no module path), that a
 * PTY is created and a shell launched in it, that input typed through the
 * widget reaches the shell (the {@code exit} it reads proves the input
 * direction works), and that the shell exiting tears the widget down
 * cleanly. What it does <em>not</em> verify from this host: the Windows
 * ConPTY path specifically (cmd.exe) -- that needs a Windows run; the
 * macOS bash path is the proxy that exercises the shared render + PTY
 * plumbing.</p>
 */
public final class JediTermFxSpike extends Application {

    private static final boolean AUTO_EXIT =
        Boolean.getBoolean("app.drydock.jediterm.autoExit");

    private JediTermFxWidget widget;
    private PtyProcess process;
    private SpikePtyConnector connector;
    private volatile boolean finished;

    @Override
    public void start(Stage stage) {
        log("starting (autoExit=%s, os=%s)", AUTO_EXIT, System.getProperty("os.name"));
        try {
            process = startShell();
        } catch (Exception e) {
            log("FAILED to start PTY: %s", e);
            Platform.exit();
            return;
        }
        connector = new SpikePtyConnector(process);
        widget = new JediTermFxWidget(80, 24, new DefaultSettingsProvider());
        widget.setTtyConnector(connector);
        widget.start();

        var root = new StackPane(widget.getPane());
        var scene = new Scene(root, 900, 600);
        stage.setTitle("JediTermFX + pty4j spike (Windows terminal candidate)");
        stage.setScene(scene);
        stage.show();
        log("widget started; process alive=%s", process.isAlive());

        if (!AUTO_EXIT) {
            return;
        }
        // Send a line then exit, so the shell reading "exit" off its PTY
        // proves the input pipeline (widget -> PTY -> shell) works end to
        // end. The echo is human-visible evidence in an interactive run.
        var scripted = new Timeline(
            new KeyFrame(Duration.seconds(1), e -> {
                try {
                    connector.write("echo DRYDOCK_SPIKE_OK\r");
                    connector.write("exit\r");
                    log("sent echo+exit; process alive=%s", process.isAlive());
                } catch (Exception ex) {
                    log("write failed: %s", ex);
                }
            }),
            // Safety net: if the shell never exits on its own, do not hang.
            new KeyFrame(Duration.seconds(5), e -> finish("timeout"))
        );
        scripted.play();
        // The expected path: the shell exits, the emulator thread closes,
        // and we tear down as soon as that happens.
        process.onExit().thenAccept(p ->
            Platform.runLater(() -> finish("process exited code=" + p.exitValue())));
    }

    private void finish(String reason) {
        if (finished) {
            return;
        }
        finished = true;
        log("finish (%s); process alive=%s", reason, process != null && process.isAlive());
        try {
            if (widget != null) {
                widget.close();
            }
        } catch (Exception ignored) {
            // A close failure during teardown is not the thing under test.
        }
        Platform.exit();
    }

    private static PtyProcess startShell() throws Exception {
        Map<String, String> env = new HashMap<>(System.getenv());
        String[] command;
        if (isWindows()) {
            command = new String[] {"cmd.exe"};
        } else {
            command = new String[] {"/bin/bash", "--login"};
            env.put("TERM", "xterm-256color");
        }
        return new PtyProcessBuilder()
            .setCommand(command)
            .setEnvironment(env)
            .start();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void log(String fmt, Object... args) {
        System.out.println("[jediterm-spike] " + String.format(fmt, args));
    }

    public static void main(String[] args) {
        launch(args);
    }

    /**
     * Wraps a pty4j {@link PtyProcess} as a JediTermFX {@code TtyConnector}.
     * Replicates {@code com.techsenger.jeditermfx.app.pty.PtyProcessTtyConnector}
     * (which lives in the {@code jeditermfx-app} module we deliberately do
     * not depend on -- it hardcodes a Linux JavaFX classifier by default,
     * wrong under Gradle) so the spike needs only {@code jeditermfx-ui}.
     * The base {@link ProcessTtyConnector} supplies read/write/ready/waitFor
     * over the process streams; the three overrides here are the PTY-specific
     * bits (resize via {@link PtyProcess#setWinSize}, liveness, name).
     */
    private static final class SpikePtyConnector extends ProcessTtyConnector {
        private final PtyProcess process;

        SpikePtyConnector(PtyProcess process) {
            super(process, StandardCharsets.UTF_8);
            this.process = process;
        }

        @Override
        public void resize(TermSize size) {
            if (isConnected()) {
                process.setWinSize(new WinSize(size.getColumns(), size.getRows()));
            }
        }

        @Override
        public boolean isConnected() {
            return process.isAlive();
        }

        @Override
        public String getName() {
            return "spike";
        }
    }
}