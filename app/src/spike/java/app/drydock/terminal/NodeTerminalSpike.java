package app.drydock.terminal;

import app.drydock.terminal.api.TerminalHostView;
import app.drydock.terminal.api.TerminalRuntime;
import app.drydock.terminal.api.TerminalSpec;
import app.drydock.terminal.api.TerminalSurface;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.Optional;

/**
 * Integration spike for the JediTermFX terminal backend: drives the REAL
 * {@link TerminalFactory} + the JediTermFX {@code terminal.api} impls (not
 * the raw widget, which {@link JediTermFxSpike} covers) through the same
 * seam the app uses -- {@code createRuntime}/{@code createHostForCurrentWindow}
 * /{@code openSurface}/{@code processExited}/{@code closeGracefully} -- so the
 * Windows CI runner verifies the integration layer, not just the library.
 *
 * <p>Forces the JediTermFX backend via {@code -Dapp.drydock.terminal.backend=jediterm}
 * (set by the gate task), so it runs on any host: macOS dev and the Windows
 * runner alike, without libghostty. The spec runs {@code echo
 * DRYDOCK_NODE_SPIKE_OK}, which exits on its own on both platforms ({@code bash -lc}
 * / {@code cmd /c}) -- so pass/fail is readable from logs as "processExited=true"
 * without driving an interactive shell. {@code submitLine} is exercised
 * separately by {@link JediTermFxSpike} (which sends echo+exit to a live shell
 * over ConPTY); this spike covers {@code openSurface} + the embedded-node
 * wiring + exit detection + graceful close.</p>
 *
 * <p>Run via {@code ./gradlew nodeTerminalSpike}. Auto-exits by default;
 * {@code -Papp.drydock.nodeterm.interactive} leaves the window open.</p>
 */
public final class NodeTerminalSpike extends Application {

    private static final boolean AUTO_EXIT =
        Boolean.getBoolean("app.drydock.nodeterm.autoExit");

    private TerminalRuntime runtime;
    private TerminalHostView host;
    private TerminalSurface surface;

    @Override
    public void start(Stage stage) {
        log("starting (backend=%s, os=%s, autoExit=%s)",
            System.getProperty("app.drydock.terminal.backend"),
            System.getProperty("os.name"), AUTO_EXIT);

        runtime = TerminalFactory.createRuntime(() -> {}, Optional.empty());
        host = TerminalFactory.createHostForCurrentWindow();
        TerminalSpec spec = new TerminalSpec("echo DRYDOCK_NODE_SPIKE_OK",
                System.getProperty("user.home"));
        surface = runtime.openSurface(host, 1.0, spec);

        boolean hasNode = host.embeddedNode().isPresent();
        log("openSurface done; embeddedNode=%s processExited=%s",
            hasNode, surface.processExited());

        // Show the embedded node so JavaFX lays it out (realistic render path).
        host.embeddedNode().ifPresent(node -> {
            stage.setTitle("Node terminal spike (TerminalFactory + JediTermFX)");
            stage.setScene(new Scene(new StackPane(node), 600, 400));
            stage.show();
        });

        if (!AUTO_EXIT) {
            return;
        }
        var scripted = new Timeline(
            new KeyFrame(Duration.millis(500),
                e -> log("500ms: processExited=%s", surface.processExited())),
            new KeyFrame(Duration.seconds(2), e -> {
                boolean exited = surface.processExited();
                log("2s: processExited=%s (expected true)", exited);
                surface.closeGracefully(2000, 100, () -> {
                    log("closeGracefully done");
                    try { runtime.close(); } catch (Exception ignored) {}
                    try { host.close(); } catch (Exception ignored) {}
                    Platform.exit();
                });
            }),
            // Safety net: never hang the runner.
            new KeyFrame(Duration.seconds(6), e -> {
                log("TIMEOUT -- forcing exit");
                Platform.exit();
            })
        );
        scripted.play();
    }

    private static void log(String fmt, Object... args) {
        System.out.println("[node-spike] " + String.format(fmt, args));
    }

    public static void main(String[] args) {
        launch(args);
    }
}