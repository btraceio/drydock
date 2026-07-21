package app.drydock.terminal.host;

import app.drydock.terminal.api.TerminalHostView;
import javafx.application.Platform;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Public entry point for the AppKit host shim (plan section 8): creates an
 * AppKit child view attached to the current JavaFX window and exposes it as
 * an opaque handle usable by e.g. the Ghostty adapter
 * ({@code app.drydock.terminal.ghostty}) via {@link #contentViewHandle()}.
 *
 * <p>This is the only public class in {@code app.drydock.terminal.host}; it
 * hides {@link MemorySegment} entirely from its own public API (the sole
 * exception is {@link #contentViewHandle()}, which returns the raw pointer
 * because the Ghostty adapter -- itself part of the narrow native boundary,
 * plan section 2.4 -- needs it verbatim for {@code
 * ghostty_platform_macos_s.nsview}; no code outside the two native-boundary
 * packages may call that method).</p>
 *
 * <p><b>Threading:</b> every method here must be called on the JavaFX
 * Application Thread, which on this project's macOS/JavaFX setup is the
 * same thread as the AppKit main thread (see docs/native-integration.md).
 * There is no internal synchronization; calling from any other thread is a
 * programming error, not merely a performance concern -- AppKit is not
 * thread-safe.</p>
 */
public final class DrydockTerminalHost implements TerminalHostView, AutoCloseable {

    private final DrydockTerminalHostBinding binding;
    private final Arena keyCallbackArena = Arena.ofShared();
    private MemorySegment handle;
    private boolean destroyed;
    // Register-once flags, one per callback slot: each registration allocates
    // an upcall stub from keyCallbackArena that is only reclaimed at close(),
    // so silently replacing a listener would leak the old stub.
    private boolean keyListenerRegistered;
    private boolean scrollListenerRegistered;
    private boolean mousePosListenerRegistered;
    private boolean mouseButtonListenerRegistered;

    private DrydockTerminalHost(DrydockTerminalHostBinding binding, MemorySegment handle) {
        this.binding = binding;
        this.handle = handle;
    }

    /**
     * Creates a host view attached to the current (most recently shown)
     * JavaFX window. See {@link JavaFxNativeView#currentWindowNsView()} for
     * the exact "current window" semantics and its single-window-spike
     * limitation.
     */
    public static DrydockTerminalHost createForCurrentWindow() {
        checkFxThread();
        DrydockTerminalHostBinding binding = DrydockTerminalHostBinding.of(DrydockTerminalHostLibrary.lookup());
        MemorySegment parentNsView = JavaFxNativeView.currentWindowNsView();
        MemorySegment handle = binding.create(parentNsView);
        if (handle.equals(MemorySegment.NULL)) {
            throw new IllegalStateException(
                "drydock_terminal_host_create returned NULL; see native stderr for details.");
        }
        return new DrydockTerminalHost(binding, handle);
    }

    /** Sets the host view's frame, in the parent window's content-view coordinate space. */
    @Override
    public void setFrame(double x, double y, double width, double height) {
        checkFxThread();
        checkNotDestroyed();
        binding.setFrame(handle, x, y, width, height);
    }

    @Override
    public void setVisible(boolean visible) {
        checkFxThread();
        checkNotDestroyed();
        binding.setVisible(handle, visible);
    }

    @Override
    public void setFocused(boolean focused) {
        checkFxThread();
        checkNotDestroyed();
        binding.setFocused(handle, focused);
    }

    /**
     * Registers the callback invoked for every keyDown/keyUp/flagsChanged
     * the host view receives. May be called at most once per host (see the
     * register-once flags above); a second registration throws {@link
     * IllegalStateException}.
     */
    @Override
    public void setKeyEventListener(TerminalHostView.KeyEventListener listener) {
        checkFxThread();
        checkNotDestroyed();
        if (keyListenerRegistered) {
            throw new IllegalStateException("key event listener already registered");
        }
        keyListenerRegistered = true;
        binding.setKeyEventCallback(handle, listener, keyCallbackArena);
    }

    /** Registers the callback invoked for every scrollWheel event the host view receives (register-once). */
    @Override
    public void setScrollEventListener(TerminalHostView.ScrollEventListener listener) {
        checkFxThread();
        checkNotDestroyed();
        if (scrollListenerRegistered) {
            throw new IllegalStateException("scroll event listener already registered");
        }
        scrollListenerRegistered = true;
        binding.setScrollEventCallback(handle, listener, keyCallbackArena);
    }

    /** Registers the callback invoked for mouseMoved events (and immediately before each scroll; register-once). */
    @Override
    public void setMousePosEventListener(TerminalHostView.MousePosEventListener listener) {
        checkFxThread();
        checkNotDestroyed();
        if (mousePosListenerRegistered) {
            throw new IllegalStateException("mouse position event listener already registered");
        }
        mousePosListenerRegistered = true;
        binding.setMousePosEventCallback(handle, listener, keyCallbackArena);
    }

    /** Registers the callback invoked for mouse button presses/releases (a position event precedes each; register-once). */
    @Override
    public void setMouseButtonEventListener(TerminalHostView.MouseButtonEventListener listener) {
        checkFxThread();
        checkNotDestroyed();
        if (mouseButtonListenerRegistered) {
            throw new IllegalStateException("mouse button event listener already registered");
        }
        mouseButtonListenerRegistered = true;
        binding.setMouseButtonEventCallback(handle, listener, keyCallbackArena);
    }

    /**
     * Returns the raw {@code NSView*} that a renderer (e.g. libghostty via
     * the Ghostty adapter) should target. Restricted, by convention, to
     * callers within the narrow native boundary packages.
     */
    public MemorySegment contentViewHandle() {
        checkFxThread();
        checkNotDestroyed();
        return binding.contentView(handle);
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
        keyCallbackArena.close();
    }

    private void checkNotDestroyed() {
        if (destroyed) {
            throw new IllegalStateException("DrydockTerminalHost already destroyed");
        }
    }

    /** Enforces the class-Javadoc threading contract (AppKit is not thread-safe); fail fast elsewhere. */
    private static void checkFxThread() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Not on the JavaFX Application Thread");
        }
    }
}
