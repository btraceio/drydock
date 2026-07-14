package app.cpm.terminal.ghostty;

import app.cpm.terminal.host.CpmTerminalHost;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A running {@code ghostty_surface_t} attached to a caller-provided AppKit
 * {@code NSView*} (see {@code app.cpm.terminal.host.CpmTerminalHost}).
 *
 * <p>Gate 0C (plan section 7/28 "Task 5"): this is the class that actually
 * proves the "preferred model" (libghostty renders into a native macOS view
 * hosted inside the JavaFX window) works, by successfully calling {@code
 * ghostty_surface_new} with the host shim's content view and driving {@code
 * ghostty_surface_draw}/{@code ghostty_surface_set_size} without a crash.</p>
 *
 * <p>Part of the narrow native boundary (plan section 2.4/4.2).</p>
 */
public final class GhosttySurface implements AutoCloseable {

    private static final int GHOSTTY_PLATFORM_MACOS = 1;

    private final GhosttyAppBinding binding;
    private MemorySegment surface;
    private boolean closed;

    private GhosttySurface(GhosttyAppBinding binding, MemorySegment surface) {
        this.binding = binding;
        this.surface = surface;
    }

    /**
     * Creates a surface rendering into {@code host}'s content view (see
     * {@link CpmTerminalHost#contentViewHandle()}). Taking the {@code
     * CpmTerminalHost} itself (rather than a raw pointer) keeps {@link
     * MemorySegment} entirely inside the two native-boundary packages
     * ({@code app.cpm.terminal.ghostty} and {@code app.cpm.terminal.host});
     * no caller outside them ever needs to hold one (plan section 2.4).
     *
     * @param scaleFactor the AppKit view's backing scale factor (2.0 on
     *                     Retina displays; see {@code javafx.stage.Window#getOutputScaleX()},
     *                     a public JavaFX API -- no internal API needed for this value).
     */
    public static GhosttySurface create(GhosttyApp app, CpmTerminalHost host, double scaleFactor) {
        GhosttyAppBinding binding = app.binding();
        MemorySegment nsView = host.contentViewHandle();
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment config = (MemorySegment) binding.surfaceConfigNew.invoke(tmp);
            // Copy onto a segment we can safely take the address of after
            // ghostty_surface_config_new()'s allocator-backed result (the
            // Linker-provided struct-by-value return already lives in a
            // segment allocated from `tmp`, so this is already addressable;
            // re-assigning fields below mutates it in place).
            config.set(ValueLayout.JAVA_INT, 0, GHOSTTY_PLATFORM_MACOS); // platform_tag
            config.set(ValueLayout.ADDRESS, 8, nsView);                  // platform.macos.nsview
            config.set(ValueLayout.JAVA_DOUBLE, 24, scaleFactor);        // scale_factor

            MemorySegment surface = (MemorySegment) binding.surfaceNew.invoke(app.handle(), config);
            if (surface.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("ghostty_surface_new returned NULL");
            }
            return new GhosttySurface(binding, surface);
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_new", t);
        }
    }

    public void setSize(int widthPx, int heightPx) {
        checkOpen();
        try {
            binding.surfaceSetSize.invoke(surface, widthPx, heightPx);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_set_size", t);
        }
    }

    public void setFocus(boolean focused) {
        checkOpen();
        try {
            binding.surfaceSetFocus.invoke(surface, focused);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_set_focus", t);
        }
    }

    public void draw() {
        checkOpen();
        try {
            binding.surfaceDraw.invoke(surface);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_draw", t);
        }
    }

    public void refresh() {
        checkOpen();
        try {
            binding.surfaceRefresh.invoke(surface);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_refresh", t);
        }
    }

    /** Feeds literal text (e.g. from a keyDown's resolved {@code characters}) to the surface. */
    public void sendText(String text) {
        checkOpen();
        if (text.isEmpty()) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cstring = GhosttyAppBinding.allocateCString(arena, text);
            // length excludes the NUL terminator we added.
            long len = cstring.byteSize() - 1;
            binding.surfaceText.invoke(surface, cstring, len);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_text", t);
        }
    }

    /**
     * Sends a non-text key event (e.g. Enter, Backspace, an arrow key).
     *
     * @param ghosttyKeyCode one of the {@code GHOSTTY_KEY_*} ordinals from {@code ghostty.h}
     * @param mods           a bitwise-OR of {@code GHOSTTY_MODS_*}
     * @param pressed        {@code true} for key-down/repeat, {@code false} for key-up
     */
    public boolean sendKey(int ghosttyKeyCode, int mods, boolean pressed) {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyStruct = arena.allocate(GhosttyAppBinding.INPUT_KEY_LAYOUT);
            keyStruct.set(ValueLayout.JAVA_INT, 0, pressed ? 1 : 0); // GHOSTTY_ACTION_PRESS = 1, RELEASE = 0
            keyStruct.set(ValueLayout.JAVA_INT, 4, mods);
            keyStruct.set(ValueLayout.JAVA_INT, 8, mods); // consumed_mods: no IME composition in this spike
            keyStruct.set(ValueLayout.JAVA_INT, 12, ghosttyKeyCode);
            keyStruct.set(ValueLayout.ADDRESS, 16, MemorySegment.NULL); // text
            keyStruct.set(ValueLayout.JAVA_INT, 24, 0); // unshifted_codepoint
            keyStruct.set(ValueLayout.JAVA_BOOLEAN, 28, false); // composing
            return (boolean) binding.surfaceKey.invoke(surface, keyStruct);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_key", t);
        }
    }

    public boolean processExited() {
        checkOpen();
        try {
            return (boolean) binding.surfaceProcessExited.invoke(surface);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_process_exited", t);
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("GhosttySurface already closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            binding.surfaceFree.invoke(surface);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_free", t);
        } finally {
            surface = MemorySegment.NULL;
        }
    }
}
