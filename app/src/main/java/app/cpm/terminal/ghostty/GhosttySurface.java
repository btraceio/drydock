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

    // GHOSTTY_POINT_VIEWPORT / GHOSTTY_POINT_COORD_TOP_LEFT / _BOTTOM_RIGHT,
    // verified against the pinned header (see docs/native-integration.md,
    // "Struct layout verification", Task 6 addendum) rather than guessed.
    private static final int GHOSTTY_POINT_VIEWPORT = 1;
    private static final int GHOSTTY_POINT_COORD_TOP_LEFT = 1;
    private static final int GHOSTTY_POINT_COORD_BOTTOM_RIGHT = 2;

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
        return create(app, host, scaleFactor, null, null);
    }

    /**
     * Same as {@link #create(GhosttyApp, CpmTerminalHost, double)}, but lets
     * the caller override the spawned command and working directory (Task 6
     * / Gate 0D: "spawn {@code /bin/zsh -l} inside the embedded terminal").
     * Per {@code src/apprt/embedded.zig}'s {@code command} field doc
     * comment, {@code command} always runs via a shell (e.g.
     * {@code /bin/sh -c "<command>"}), so a full command line with
     * arguments (e.g. {@code "/bin/zsh -l"}) is valid here. A {@code null}
     * command falls back to libghostty's own default-shell resolution.
     */
    public static GhosttySurface create(GhosttyApp app, CpmTerminalHost host, double scaleFactor,
                                         String command, String workingDirectory) {
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
            if (workingDirectory != null) {
                config.set(ValueLayout.ADDRESS, 40, GhosttyAppBinding.allocateCString(tmp, workingDirectory));
            }
            if (command != null) {
                config.set(ValueLayout.ADDRESS, 48, GhosttyAppBinding.allocateCString(tmp, command));
            }

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
        return sendKey(ghosttyKeyCode, mods, pressed, null);
    }

    // GHOSTTY_KEY_UNIDENTIFIED, verified against the pinned header (it is
    // the first, and therefore 0-valued, ghostty_input_key_e entry).
    private static final int GHOSTTY_KEY_UNIDENTIFIED = 0;

    /**
     * Sends one ordinary <em>typed</em> character through the real keyboard
     * codepath ({@code ghostty_surface_key}, with the resolved character in
     * the event's {@code text} field) -- the same codepath a real AppKit
     * {@code keyDown} delivers a resolved character through (see {@code
     * Surface.zig}'s {@code encodeKey}/{@code keyCallback}).
     *
     * <p>Deliberately NOT implemented via {@link #sendText(String)}
     * ({@code ghostty_surface_text}): a Task 6 (Gate 0D) finding was that
     * {@code ghostty_surface_text}/{@code textCallback} is documented in
     * {@code Surface.zig} as "treat[ing] the input text as if it was pasted
     * from the clipboard" -- once the shell enables bracketed-paste mode
     * (which most shells do shortly after showing an interactive prompt),
     * further {@code sendText} calls get wrapped in {@code \e[200~...\e[201~}
     * bracketed-paste markers, which is semantically wrong for "the user
     * pressed a key" and was observed to corrupt input in this project's own
     * automated Gate 0D checklist run (see docs/native-integration.md,
     * "Task 6 / Gate 0D" -- garbled command lines like {@code "echo
     * WRONGWORD[200~GATE0D_MARK2_RIGHT"}). Real per-character typing should
     * use this method (or the {@link #sendKey(int, int, boolean)} special-key
     * overload); {@link #sendText(String)} should be reserved for an actual
     * paste operation (e.g. Cmd+V from the system pasteboard).</p>
     */
    public boolean sendCharKey(int codepoint, int mods) {
        String text = new String(Character.toChars(codepoint));
        boolean consumed = sendKey(GHOSTTY_KEY_UNIDENTIFIED, mods, true, text);
        sendKey(GHOSTTY_KEY_UNIDENTIFIED, mods, false, null);
        return consumed;
    }

    /** Convenience: sends every codepoint in {@code text} as a separate typed-key press+release. */
    public void sendTypedText(String text) {
        text.codePoints().forEach(cp -> sendCharKey(cp, 0));
    }

    private boolean sendKey(int ghosttyKeyCode, int mods, boolean pressed, String text) {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyStruct = arena.allocate(GhosttyAppBinding.INPUT_KEY_LAYOUT);
            keyStruct.set(ValueLayout.JAVA_INT, 0, pressed ? 1 : 0); // GHOSTTY_ACTION_PRESS = 1, RELEASE = 0
            keyStruct.set(ValueLayout.JAVA_INT, 4, mods);
            keyStruct.set(ValueLayout.JAVA_INT, 8, mods); // consumed_mods: no IME composition in this spike
            keyStruct.set(ValueLayout.JAVA_INT, 12, ghosttyKeyCode);
            MemorySegment textSegment = text != null
                ? GhosttyAppBinding.allocateCString(arena, text)
                : MemorySegment.NULL;
            keyStruct.set(ValueLayout.ADDRESS, 16, textSegment); // text
            int unshifted = (text != null && !text.isEmpty()) ? text.codePointAt(0) : 0;
            keyStruct.set(ValueLayout.JAVA_INT, 24, unshifted); // unshifted_codepoint
            keyStruct.set(ValueLayout.JAVA_BOOLEAN, 28, false); // composing
            return (boolean) binding.surfaceKey.invoke(surface, keyStruct);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_key", t);
        }
    }

    /**
     * Reads back the terminal's currently rendered viewport as plain text
     * (one native call, cell grid -> UTF-8 string; no ANSI/SGR codes are
     * included since {@code ghostty_surface_read_text} returns decoded cell
     * contents, not the raw escape-sequence stream).
     *
     * <p>This is what makes most of Task 6's Gate 0D checklist verifiable
     * headlessly (plan section 7): rather than only being able to say "the
     * draw call didn't crash" or requiring a human to look at a screenshot,
     * the spike can assert that specific expected text (an echoed command's
     * output, a shell prompt reappearing after Ctrl+C, a resized
     * {@code $COLUMNS}, a decoded emoji) actually landed in the terminal's
     * cell grid. It does <em>not</em> prove pixels were drawn correctly
     * (font rendering, colour, glyph shaping) -- see
     * docs/native-integration.md for exactly which checklist items this
     * still leaves for a human.</p>
     *
     * @return the viewport text, or {@code ""} if libghostty reports no
     *         text is available (e.g. surface not yet sized/drawn).
     */
    public String readScreenText() {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment selection = arena.allocate(GhosttyAppBinding.SELECTION_LAYOUT);
            // top_left = viewport top-left corner, bottom_right = viewport
            // bottom-right corner (GHOSTTY_POINT_COORD_TOP_LEFT/BOTTOM_RIGHT
            // ignore x/y and mean "the corner of the given point space"; see
            // ghostty.h's ghostty_point_coord_e).
            selection.set(ValueLayout.JAVA_INT, 0, GHOSTTY_POINT_VIEWPORT);           // top_left.tag
            selection.set(ValueLayout.JAVA_INT, 4, GHOSTTY_POINT_COORD_TOP_LEFT);     // top_left.coord
            selection.set(ValueLayout.JAVA_INT, 16, GHOSTTY_POINT_VIEWPORT);          // bottom_right.tag
            selection.set(ValueLayout.JAVA_INT, 20, GHOSTTY_POINT_COORD_BOTTOM_RIGHT);// bottom_right.coord
            selection.set(ValueLayout.JAVA_BOOLEAN, 32, false);                       // rectangle

            MemorySegment text = arena.allocate(GhosttyAppBinding.TEXT_LAYOUT);
            boolean ok = (boolean) binding.surfaceReadText.invoke(surface, selection, text);
            if (!ok) {
                return "";
            }
            try {
                MemorySegment textPtr = text.get(ValueLayout.ADDRESS, 24);
                long textLen = text.get(ValueLayout.JAVA_LONG, 32);
                if (textPtr.equals(MemorySegment.NULL) || textLen == 0) {
                    return "";
                }
                byte[] bytes = textPtr.reinterpret(textLen).toArray(ValueLayout.JAVA_BYTE);
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            } finally {
                binding.surfaceFreeText.invoke(surface, text);
            }
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_read_text", t);
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
