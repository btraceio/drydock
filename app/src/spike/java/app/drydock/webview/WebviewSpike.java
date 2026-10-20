package app.drydock.webview;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.webview.WebviewHost;

/**
 * Web-renderer feasibility spike: the smallest possible JavaFX window that
 * embeds a WKWebView (via native-host/DrydockWebviewHost.m) and drives the
 * JS<->Java bridge in both directions.
 *
 * <p>What it proves automatically (auto-exit mode, default):</p>
 * <ol>
 *   <li>a WKWebView can be attached to the JavaFX Glass window's NSView and
 *       render a page (the same embedding slot the terminal host uses);</li>
 *   <li>Java -> JS: {@code postMessage} reaches the page and the page reacts
 *       (navigates to a line, reports back);</li>
 *   <li>JS -> Java: the page's {@code webkit.messageHandlers.drydock} posts
 *       arrive at the registered Java listener;</li>
 *   <li>memory cost: RSS sampled before/after the web view loads.</li>
 * </ol>
 *
 * <p>What needs a human in {@code -Papp.drydock.webview.interactive} mode:</p>
 * <ul>
 *   <li>typing into the page's input field (does Glass swallow keys destined
 *       for the WKWebView first responder?);</li>
 *   <li>Cmd-shortcuts while the web view is focused (do they still reach the
 *       app's menu bar?);</li>
 *   <li>scroll/click feel, HiDPI sharpness, focus hand-off between the web
 *       view and JavaFX controls.</li>
 * </ul>
 *
 * <p>Run via {@code ./gradlew webviewSpike}.</p>
 */
public final class WebviewSpike extends Application {

    private static final boolean AUTO_EXIT =
        Boolean.getBoolean("app.drydock.webview.autoExit");

    private static final long RSS_PROBE_TIMEOUT_SECONDS = 15;

    public static void main(String[] args) {
        launch(WebviewSpike.class, args);
    }

    private WebviewHost host;
    private Stage stage;
    private volatile boolean receivedJavaToJsEcho;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        log("starting (autoExit=" + AUTO_EXIT + ")");

        var status = new Label("creating web view...");
        var root = new BorderPane();
        root.setBottom(buildControls(status));
        var scene = new Scene(root, 900, 600);
        stage.setTitle("Webview spike -- WKWebView in a JavaFX window");
        stage.setScene(scene);
        stage.show();
        log("stage shown");

        // Same creation-time rule as Gate0cSpike: Glass has finished window
        // creation one runLater tick after show().
        Platform.runLater(() -> {
            try {
                initializeWebview(stage, scene, status);
            } catch (Throwable t) {
                log("FAILED to initialize web view: " + t);
                t.printStackTrace();
                Platform.exit();
            }
        });

        stage.setOnCloseRequest(event -> shutdown());
    }

    private HBox buildControls(Label status) {
        var box = new HBox(8);
        var gotoButton = new Button("Java -> JS: go to line 37");
        gotoButton.setOnAction(e -> {
            host.postMessage("{\"cmd\":\"goto\",\"line\":37}");
            status.setText("sent goto 37; watch the page scroll + report back");
        });
        box.getChildren().addAll(gotoButton, status);
        return box;
    }

    private void initializeWebview(Stage stage, Scene scene, Label status) throws Exception {
        long rssBefore = probeRssKb("before web view creation");

        // The production facade (app.drydock.webview in main) -- the spike now
        // doubles as its runnable smoke test.
        host = WebviewHost.createForCurrentWindow();
        log("WKWebView container attached to JavaFX window's NSView");

        host.setMessageListener(message -> {
            log("JS -> Java message: " + message);
            if (message.contains("\"event\":\"moved\"")) {
                receivedJavaToJsEcho = true;
            }
            if (message.contains("\"event\":\"ready\"")) {
                status.setText("page ready");
            }
        });

        double width = scene.getWidth();
        double height = scene.getHeight() - 40;
        host.setFrame(0, 0, width, height);
        host.setVisible(true);
        log("web view visible at %.0fx%.0f".formatted(width, height));

        scene.widthProperty().addListener((obs, oldV, newV) ->
                host.setFrame(0, 0, newV.doubleValue(), height));
        scene.heightProperty().addListener((obs, oldV, newV) ->
                host.setFrame(0, 0, scene.getWidth(), newV.doubleValue() - 40));

        host.loadUrl(writeFixture().toUri().toString());
        log("fixture page load requested");

        if (AUTO_EXIT) {
            runAutoExitSequence(rssBefore);
        } else {
            log("interactive mode: type into the page's input, try Cmd+Q, click buttons");
        }
    }

    private void runAutoExitSequence(long rssBefore) {
        var steps = new Timeline(
                // Page load + ready message must have arrived by now.
                new KeyFrame(Duration.seconds(3), e ->
                        log("step 1: page load window elapsed (ready message logged above if the JS->Java upcall works)")),
                // Java -> JS: navigate to line 37; the page answers with
                // {"event":"moved","line":37}, proving the full round trip.
                new KeyFrame(Duration.seconds(3.5), e -> {
                    host.postMessage("{\"cmd\":\"goto\",\"line\":37}");
                    log("step 2: sent Java -> JS {\"cmd\":\"goto\",\"line\":37}");
                }),
                new KeyFrame(Duration.seconds(5.5), e -> {
                    long rssAfter = probeRssKb("after web view load");
                    log("RSS before=%.1f MB after=%.1f MB (delta=%.1f MB)".formatted(
                            rssBefore / 1024.0, rssAfter / 1024.0,
                            (rssAfter - rssBefore) / 1024.0));
                }),
                new KeyFrame(Duration.seconds(6), e -> {
                    boolean ok = receivedJavaToJsEcho;
                    log(ok
                            ? "RESULT: PASS -- embedding + Java->JS + JS->Java round trip verified"
                            : "RESULT: FAIL -- no {\"event\":\"moved\"} echo received from the page");
                    shutdown();
                    Platform.exit();
                }));
        steps.setCycleCount(1);
        steps.play();
    }

    /** Writes the fixture page to a temp file and returns its path. */
    private static Path writeFixture() throws Exception {
        StringBuilder lines = new StringBuilder();
        for (int i = 1; i <= 120; i++) {
            lines.append("<div class=\"ln\" id=\"L").append(i)
                    .append("\"><span class=\"no\">").append(i)
                    .append("</span>  // fixture source line ").append(i)
                    .append("</div>\n");
        }
        String html = """
                <!DOCTYPE html>
                <html><head><meta charset="utf-8"><style>
                  body { margin:0; font: 13px/1.45 Menlo, monospace; }
                  .ln { padding: 0 8px; white-space: pre; }
                  .ln .no { display:inline-block; width:40px; color:#999; text-align:right; margin-right:12px; }
                  .target { background: #ffe9a8; outline: 1px solid #e8b93c; }
                  #bar { position:fixed; top:0; left:0; right:0; padding:6px 8px;
                         background:#eee; border-bottom:1px solid #ccc; }
                  #bar input { width:60px; }
                </style></head><body>
                <div id="bar">
                  <input id="lineInput" placeholder="line">
                  <button onclick="go(document.getElementById('lineInput').value)">Go</button>
                  <span id="status"></span>
                </div>
                <div style="margin-top:40px">
                %s</div>
                <script>
                  function go(line) {
                    var el = document.getElementById('L' + line);
                    if (!el) return;
                    document.querySelectorAll('.target').forEach(function (n) { n.classList.remove('target'); });
                    el.classList.add('target');
                    el.scrollIntoView({block: 'center'});
                    document.getElementById('status').textContent = 'at line ' + line;
                    webkit.messageHandlers.drydock.postMessage(JSON.stringify({event: 'moved', line: line}));
                  }
                  window.drydock = {
                    receive: function (msg) { console.log('Java -> JS: ' + JSON.stringify(msg));
                                              if (msg && msg.cmd === 'goto') go(msg.line); }
                  };
                  window.addEventListener('DOMContentLoaded', function () {
                    webkit.messageHandlers.drydock.postMessage(JSON.stringify({event: 'ready', lines: 120}));
                  });
                </script></body></html>
                """.formatted(lines.toString());

        Path fixture = Files.createTempFile("drydock-webview-spike-", ".html");
        Files.writeString(fixture, html, StandardCharsets.UTF_8);
        fixture.toFile().deleteOnExit();
        log("fixture written to " + fixture);
        return fixture;
    }

    /** Blocking; called from the FX thread only via short timeline steps that accept the stall. */
    private static long probeRssKb(String when) {
        // ProcessRunner.run blocks; on the FX thread that is acceptable here
        // only because ps(1) returns in milliseconds. The probe runs off-thread
        // and joins with a timeout to keep even a hung ps from freezing the UI.
        var done = new CountDownLatch(1);
        long[] rss = { -1 };
        Thread.ofVirtual().start(() -> {
            try {
                rss[0] = probeRssKbBlocking();
            } catch (Exception e) {
                log("RSS probe failed (" + when + "): " + e);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(RSS_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log("RSS probe timed out (" + when + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return rss[0];
    }

    /** The actual blocking ps(1) probe; runs on the virtual thread only. */
    private static long probeRssKbBlocking() throws Exception {
        long pid = ProcessHandle.current().pid();
        ProcessResult result = ProcessRunner.run(
                List.of("ps", "-o", "rss=", "-p", Long.toString(pid)),
                null, java.time.Duration.ofSeconds(5));
        return Long.parseLong(result.stdout().trim());
    }

    private void shutdown() {
        if (host != null) {
            try {
                host.close();
                log("web view destroyed");
            } catch (Throwable t) {
                log("web view close failed: " + t);
            }
            host = null;
        }
        if (stage != null) {
            stage.close();
        }
    }

    private static void log(String message) {
        System.out.println("[webview-spike] " + message);
    }
}
