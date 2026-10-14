package app.drydock.ui.review;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import app.drydock.webview.WebviewHost;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * The review tab's embedded code viewer: the file behind a hunk, rendered in
 * a native WKWebView overlay ({@code app.drydock.webview.WebviewHost})
 * positioned over an otherwise-empty JavaFX anchor region -- the same
 * layout-marker pattern {@code TerminalBridge} uses for the terminal surface.
 *
 * <p>Syntax highlighting is real TextMate highlighting (VS Code's grammars),
 * via the Shiki bundle built at Gradle time (classpath resource
 * {@code /shiki.iife.js}, built by the {@code buildHighlighterBundle} task;
 * see {@code app/highlighter/shiki-wrapper.mjs}). The bundle and the page are
 * written into a temp directory the page loads from; if the bundle is ever
 * missing the page falls back to plain, uncolored rendering rather than
 * failing.</p>
 *
 * <p>The web view does not render into the scene graph: it is an overlay on
 * the window's content view that paints over everything while visible.
 * Visibility is therefore the AND of every owner input -- {@link
 * #setWorkspaceVisible} (selected tab, no modal), {@link #setSubTabActive}
 * (the Review sub-tab is showing) and {@link #open(Path, int)} -- mirroring
 * {@code TerminalBridge#applyVisibility}; missing one clause lets the overlay
 * paint over unrelated sub-tabs or modals.</p>
 *
 * <p><b>Threading:</b> construction and every method run on the JavaFX
 * Application Thread (which is the AppKit main thread); {@code
 * WebviewHost} enforces this and fails fast.</p>
 */
final class WebCodeDrawer {

    /** Lines rendered before the page notes the truncation; a viewer, not an editor. Package-private for the page test. */
    static final int MAX_LINES = 20_000;

    /** Line length cap; longer lines are cut with a visible marker. */
    private static final int MAX_LINE_LENGTH = 2_000;

    private final Region anchor = new Region();
    private final Label fileLabel = new Label();
    private final Label statusLabel = new Label();
    private final HBox header;
    private final VBox content;
    private final ObjectProperty<Window> window = new SimpleObjectProperty<>();
    private Runnable onClose;

    private WebviewHost host;
    /** The file the page currently shows, so re-shows after sub-tab switches restore it. */
    private Path currentFile;
    private int currentLine;
    /** Whether the drawer UI is attached at all (vs. closed). */
    private boolean open;
    private boolean workspaceVisible = true;
    private boolean subTabActive;
    private boolean disposed;

    WebCodeDrawer() {
        anchor.getStyleClass().add("web-code-drawer-anchor");
        fileLabel.getStyleClass().add("web-code-drawer-file");
        statusLabel.getStyleClass().add("web-code-drawer-status");
        var closeButton = new Button("✕");
        closeButton.getStyleClass().addAll("web-code-drawer-close", "review-hunk-explorer");
        closeButton.setTooltip(new Tooltip("Close the code viewer"));
        closeButton.setOnAction(e -> close());
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header = new HBox(8, fileLabel, spacer, statusLabel, closeButton);
        header.getStyleClass().add("web-code-drawer-header");
        content = new VBox(header, anchor);
        content.getStyleClass().add("web-code-drawer");
        VBox.setVgrow(anchor, Priority.ALWAYS);
        // Geometry tracking, mirroring OpenSessionTab's wiring for the terminal
        // placeholder: any local-bounds or transform change re-positions the
        // native view, and a pure backing-scale change (window move between
        // monitors) is covered by re-reading the bounds on the next pulse.
        anchor.boundsInLocalProperty().addListener((obs, o, n) -> updateGeometry());
        anchor.localToSceneTransformProperty().addListener((obs, o, n) -> updateGeometry());
        anchor.sceneProperty().addListener((obs, oldScene, newScene) -> {
            Window newWindow = newScene == null ? null : newScene.getWindow();
            window.set(newWindow);
            if (newWindow != null) {
                // First attach: give the layout pass a chance to size the
                // anchor before positioning the overlay.
                Platform.runLater(this::updateGeometry);
            }
        });
    }

    /** The scene-graph side of the drawer (header + anchor), for the owner to place. */
    Region content() {
        return content;
    }

    /** Invoked when the user closes the drawer; the owner detaches {@link #content()} from the layout. */
    void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    /**
     * Opens the drawer showing {@code file} (absolute path in the scope's
     * checkout) with {@code line} (1-based) highlighted. Re-opening with a
     * new file/line replaces the page.
     */
    void open(Path file, int line) {
        if (disposed) {
            return;
        }
        currentFile = file;
        currentLine = line;
        if (host == null) {
            // One runLater after first show() is the established creation
            // window; here the window has existed for a long time.
            try {
                host = WebviewHost.createForCurrentWindow();
                host.setMessageListener(this::onWebMessage);
            } catch (RuntimeException e) {
                statusLabel.setText("web viewer unavailable: " + e.getMessage());
                return;
            }
        }
        open = true;
        fileLabel.setText(file.getFileName().toString());
        loadCurrentPage();
        applyVisibility();
    }

    void close() {
        open = false;
        applyVisibility();
        if (onClose != null) {
            onClose.run();
        }
    }

    private void loadCurrentPage() {
        if (host == null || currentFile == null) {
            return;
        }
        try {
            Path dir = renderPageDir(currentFile, currentLine);
            Path page = dir.resolve("page.html");
            dir.toFile().deleteOnExit();
            page.toFile().deleteOnExit();
            host.loadUrl(page.toUri().toString());
            statusLabel.setText("");
        } catch (IOException e) {
            statusLabel.setText("could not read " + currentFile + ": " + e);
        }
    }

    private void onWebMessage(String message) {
        // The bridge is live but carries no behaviour yet: the page reports
        // readiness and (future annotation work) clicked lines. Log only.
        System.getLogger(WebCodeDrawer.class.getName())
                .log(System.Logger.Level.DEBUG, "web code drawer message: {0}", message);
    }

    /** Records MainWorkspace's verdict (selected tab, no modal) and applies the combined visibility. */
    void setWorkspaceVisible(boolean visible) {
        workspaceVisible = visible;
        applyVisibility();
    }

    /** Records whether the Review sub-tab is the active one and applies the combined visibility. */
    void setSubTabActive(boolean active) {
        subTabActive = active;
        applyVisibility();
        if (active && open) {
            // Re-position after the sub-tab's layout pass; the anchor was
            // detached while another sub-tab owned the center.
            Platform.runLater(this::updateGeometry);
        }
    }

    /** AND of every visibility input; also hands focus back to JavaFX when hiding. */
    private void applyVisibility() {
        if (disposed || host == null) {
            return;
        }
        boolean visible = open && workspaceVisible && subTabActive;
        if (!visible) {
            // Release first responder before hiding, so key routing returns
            // to the FX content view (the shim's set_focused contract).
            host.setFocused(false);
        }
        host.setVisible(visible);
        if (visible) {
            updateGeometry();
        }
    }

    /** Positions the native view over the anchor's scene bounds. */
    private void updateGeometry() {
        if (disposed || host == null || anchor.getScene() == null) {
            return;
        }
        Scene scene = anchor.getScene();
        if (scene.getWindow() == null) {
            return;
        }
        Bounds sceneBounds = anchor.localToScene(anchor.getBoundsInLocal());
        if (sceneBounds.getWidth() <= 0 || sceneBounds.getHeight() <= 0) {
            return;
        }
        host.setFrame(sceneBounds.getMinX(), sceneBounds.getMinY(),
                sceneBounds.getWidth(), sceneBounds.getHeight());
    }

    /** Destroys the native view; idempotent. Called from the tab's teardown path. */
    void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (host != null) {
            host.close();
            host = null;
        }
    }

    // ---- Page rendering ----

    /** Extracts the classpath highlighter bundle once per process; null when absent (plain fallback). */
    private static volatile Path extractedBundle;

    private static Path highlighterBundle() throws IOException {
        Path bundle = extractedBundle;
        if (bundle != null && Files.isRegularFile(bundle)) {
            return bundle;
        }
        synchronized (WebCodeDrawer.class) {
            if (extractedBundle == null || !Files.isRegularFile(extractedBundle)) {
                try (var input = WebCodeDrawer.class.getResourceAsStream("/shiki.iife.js")) {
                    if (input == null) {
                        return null; // bundle not built; the page falls back to plain rendering
                    }
                    Path dir = Files.createTempDirectory("drydock-highlighter");
                    dir.toFile().deleteOnExit();
                    bundle = dir.resolve("shiki.iife.js");
                    Files.copy(input, bundle);
                    bundle.toFile().deleteOnExit();
                    extractedBundle = bundle;
                }
            }
            return extractedBundle;
        }
    }

    /**
     * Writes the page into a fresh temp directory (page + highlighter bundle
     * as siblings; {@code loadFileURL} read access is directory-scoped). The
     * page embeds the raw code and lets the Shiki bundle render it -- real
     * TextMate highlighting (VS Code's grammars; see
     * app/highlighter/shiki-wrapper.mjs), one div.ln per line -- falling back
     * to plain rendering when the bundle is absent or fails.
     */
    // Package-private for the unit test (same package); the page is static data.
    static Path renderPageDir(Path file, int targetLine) throws IOException {
        List<String> allLines = Files.readAllLines(file, StandardCharsets.UTF_8);
        boolean truncated = allLines.size() > MAX_LINES;
        List<String> lines = truncated ? allLines.subList(0, MAX_LINES) : allLines;
        String code = String.join("\n", lines);

        Path dir = Files.createTempDirectory("drydock-code-");
        Path bundle = highlighterBundle();
        if (bundle != null) {
            Files.copy(bundle, dir.resolve("shiki.iife.js"));
            dir.resolve("shiki.iife.js").toFile().deleteOnExit();
        }

        String html = """
                <!DOCTYPE html>
                <html><head><meta charset="utf-8"><style>
                  body { margin:0; font: 12px/1.5 Menlo, monospace; background:#1e1e1e; color:#d4d4d4; }
                  .ln { padding: 0 8px; white-space: pre-wrap; word-break: break-all; cursor: default; }
                  .ln .no { display:inline-block; width:48px; color:#6e7681; text-align:right; margin-right:12px; user-select:none; }
                  .target { background: #365a76; }
                  .notice { padding: 8px; color:#6e7681; font-style: italic; }
                </style></head><body>
                %s<div id="code"></div>
                %s
                <script>
                  const CODE = %s;
                  const LANG = %s;
                  const TARGET = %d;
                  const FILE = %s;

                  function escapeHtml(s) {
                    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
                  }
                  function plainHtml() {
                    return CODE.split('\\n').map(function (line, index) {
                      const no = index + 1;
                      return '<div class="ln" id="L' + no + '"><span class="no">' + no
                          + '</span>' + escapeHtml(line) + '</div>';
                    }).join('\\n');
                  }
                  function install(codeHtml, bg, fg) {
                    document.getElementById('code').innerHTML = codeHtml;
                    if (bg) document.body.style.background = bg;
                    if (fg) document.body.style.color = fg;
                    document.querySelectorAll('.ln').forEach(function (el) {
                      el.addEventListener('click', function () {
                        webkit.messageHandlers.drydock.postMessage(JSON.stringify(
                            {event: 'lineclick', line: parseInt(el.id.slice(1))}));
                      });
                    });
                  }
                  function gotoLine(line) {
                    const el = document.getElementById('L' + line);
                    if (!el) return;
                    document.querySelectorAll('.target').forEach(function (n) { n.classList.remove('target'); });
                    el.classList.add('target');
                    el.scrollIntoView({block: 'center'});
                    webkit.messageHandlers.drydock.postMessage(JSON.stringify({event: 'moved', line: line}));
                  }
                  function done() {
                    gotoLine(TARGET);
                    webkit.messageHandlers.drydock.postMessage(JSON.stringify({event: 'ready', file: FILE, renderer: renderer}));
                  }
                  let renderer = 'plain';
                  if (window.DrydockShiki) {
                    DrydockShiki.highlight(CODE, LANG)
                        .then(function (r) { renderer = 'shiki'; install(r.html, r.bg, r.fg); done(); })
                        .catch(function (e) { install(plainHtml(), null, null); done(); });
                  } else {
                    install(plainHtml(), null, null);
                    done();
                  }
                </script></body></html>
                """.formatted(
                truncated
                        ? "<div class=\"notice\">showing the first " + MAX_LINES + " lines</div>"
                        : "",
                bundle != null ? "<script src=\"./shiki.iife.js\"></script>" : "",
                json(code),
                json(languageFor(file)),
                targetLine,
                json(file.getFileName().toString()));

        Path page = dir.resolve("page.html");
        Files.writeString(page, html, StandardCharsets.UTF_8);
        return dir;
    }

    /** File extension -> bundled Shiki language id; unknown extensions render plain. */
    /** Package-private for the unit test. */
    static String languageFor(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        return switch (ext) {
            case "java" -> "java";
            case "kt", "kts" -> "kotlin";
            case "js", "mjs", "cjs" -> "javascript";
            case "ts", "mts", "cts", "tsx", "jsx" -> "typescript";
            case "json" -> "json";
            case "py" -> "python";
            case "go" -> "go";
            case "rs" -> "rust";
            case "c", "h" -> "c";
            case "cpp", "cc", "cxx", "hpp", "hh" -> "cpp";
            case "cs" -> "csharp";
            case "css" -> "css";
            case "html", "htm" -> "html";
            case "xml" -> "xml";
            case "yaml", "yml" -> "yaml";
            case "md" -> "markdown";
            case "sh", "bash", "zsh" -> "shellscript";
            case "sql" -> "sql";
            case "toml" -> "toml";
            case "rb" -> "ruby";
            case "swift" -> "swift";
            case "php" -> "php";
            case "zig" -> "zig";
            case "diff", "patch" -> "diff";
            default -> "text";
        };
    }

    /** Escapes, then applies the length cap (the cut marker is HTML, so it goes on after escaping). */
    private static String renderLine(String line) {
        String escaped = escape(line);
        if (escaped.length() > MAX_LINE_LENGTH) {
            return escaped.substring(0, MAX_LINE_LENGTH) + "<span class=\"cut\"> …[line cut]</span>";
        }
        return escaped;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * JSON string literal safe to embed in a JS script: escapes backslash,
     * quote, control characters (a raw newline inside a JSON string literal
     * is invalid and would kill the whole script), and U+2028/U+2029 (valid
     * JSON string content, invalid in JS string literals before ES2019).
     */
    private static String json(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\u2028' -> out.append("\\u2028");
                case '\u2029' -> out.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
