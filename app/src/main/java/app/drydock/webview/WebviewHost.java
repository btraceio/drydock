package app.drydock.webview;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import app.drydock.terminal.host.JavaFxNativeView;
import javafx.application.Platform;

/**
 * Facade over the WKWebView embedding shim (native-host/DrydockWebviewHost.h):
 * creates a web view attached to the current JavaFX window and exposes the
 * small operation set the code-view drawer needs -- frame, visibility, focus,
 * URL loading, and the two-way JS&harr;Java bridge. Counterpart of {@code
 * app.drydock.terminal.host.DrydockTerminalHost}; hides FFM types entirely.
 *
 * <p><b>Threading:</b> every method here must be called on the JavaFX
 * Application Thread, which on this project's macOS/JavaFX setup is the same
 * thread as the AppKit main thread (see docs/native-integration.md). There is
 * no internal synchronization; calling from any other thread is a programming
 * error -- AppKit and WebKit are not thread-safe.</p>
 *
 * <p><b>Lifecycle:</b> the web view is a native overlay on the window's
 * content view: it paints over the whole scene while visible. Owners must
 * gate visibility from every input (tab selection, sub-tab, drawer state) and
 * call {@link #close} on teardown -- same discipline as the terminal host.
 * WebKit runs its content in separate out-of-process renderers; a hidden web
 * view still costs its page, so owners that are done with a page should
 * navigate it away or close the host.</p>
 */
public final class WebviewHost implements AutoCloseable {

    private final WebviewHostBinding binding;
    private final Arena messageCallbackArena = Arena.ofShared();
    private MemorySegment handle;
    private boolean destroyed;
    private boolean messageListenerRegistered;

    private WebviewHost(WebviewHostBinding binding, MemorySegment handle) {
        this.binding = binding;
        this.handle = handle;
    }

    /**
     * Creates the web view attached to the current (most recently shown)
     * JavaFX window. Must be called after the {@code Stage} has been shown
     * (see {@link JavaFxNativeView#currentWindowNsView()} for the exact
     * semantics); creating one runLater tick after {@code show()} is the
     * established pattern (Glass finishes window creation then).
     */
    public static WebviewHost createForCurrentWindow() {
        checkFxThread();
        WebviewHostBinding binding = WebviewHostBinding.of(WebviewHostLibrary.lookup());
        MemorySegment handle = binding.create(JavaFxNativeView.currentWindowNsView());
        if (handle.equals(MemorySegment.NULL)) {
            throw new IllegalStateException(
                    "drydock_webview_host_create returned NULL; see native stderr (NSLog) for details.");
        }
        return new WebviewHost(binding, handle);
    }

    /**
     * Sets the web view's frame, in the parent content view's coordinate
     * space (top-left origin, matching JavaFX's scene coordinates -- the
     * shim's container is flipped; the same convention as the terminal host).
     */
    public void setFrame(double x, double y, double width, double height) {
        checkFxThread();
        checkNotDestroyed();
        binding.setFrame(handle, x, y, width, height);
    }

    public void setVisible(boolean visible) {
        checkFxThread();
        checkNotDestroyed();
        binding.setVisible(handle, visible);
    }

    public void setFocused(boolean focused) {
        checkFxThread();
        checkNotDestroyed();
        binding.setFocused(handle, focused);
    }

    /**
     * Navigates to an absolute URL. {@code file://} URLs are loaded with read
     * access to the file's enclosing directory; other schemes go through a
     * plain request. Pop-ups are blocked by the shim.
     */
    public void loadUrl(String url) {
        checkFxThread();
        checkNotDestroyed();
        binding.loadUrl(handle, url);
    }

    /** Java &rarr; JS: delivers {@code json} to {@code window.drydock.receive(json)} in the page. */
    public void postMessage(String json) {
        checkFxThread();
        checkNotDestroyed();
        binding.postMessage(handle, json);
    }

    /**
     * Registers the JS &rarr; Java message callback (register-once; a second
     * registration throws {@link IllegalStateException} -- the upcall stub
     * would leak otherwise, same contract as the terminal host's listeners).
     */
    public void setMessageListener(WebviewHostBinding.MessageCallback listener) {
        checkFxThread();
        checkNotDestroyed();
        if (messageListenerRegistered) {
            throw new IllegalStateException("message listener already registered");
        }
        messageListenerRegistered = true;
        binding.setMessageCallback(handle, listener, messageCallbackArena);
    }

    @Override
    public void close() {
        checkFxThread();
        if (destroyed) {
            return;
        }
        destroyed = true;
        binding.destroy(handle);
        handle = MemorySegment.NULL;
        messageCallbackArena.close();
    }

    private void checkNotDestroyed() {
        if (destroyed) {
            throw new IllegalStateException("WebviewHost already destroyed");
        }
    }

    /** Enforces the class-Javadoc threading contract; fail fast elsewhere. */
    private static void checkFxThread() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Not on the JavaFX Application Thread");
        }
    }
}
