package app.drydock.terminal.ghostty;

import app.drydock.terminal.api.Shortcut;
import app.drydock.terminal.api.TerminalSurface;
import app.drydock.terminal.host.DrydockTerminalHost;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.System.Logger;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A running {@code ghostty_surface_t} attached to a caller-provided AppKit
 * {@code NSView*} (see {@code app.drydock.terminal.host.DrydockTerminalHost}).
 *
 * <p>Gate 0C (plan section 7/28 "Task 5"): this is the class that actually
 * proves the "preferred model" (libghostty renders into a native macOS view
 * hosted inside the JavaFX window) works, by successfully calling {@code
 * ghostty_surface_new} with the host shim's content view and driving {@code
 * ghostty_surface_draw}/{@code ghostty_surface_set_size} without a crash.</p>
 *
 * <p>Part of the narrow native boundary (plan section 2.4/4.2).</p>
 */
public final class GhosttySurface implements TerminalSurface, AutoCloseable {

    private static final Logger LOG = System.getLogger(GhosttySurface.class.getName());

    private static final int GHOSTTY_PLATFORM_MACOS = 1;

    // GHOSTTY_POINT_VIEWPORT / GHOSTTY_POINT_COORD_TOP_LEFT / _BOTTOM_RIGHT,
    // verified against the pinned header (see docs/native-integration.md,
    // "Struct layout verification", Task 6 addendum) rather than guessed.
    private static final int GHOSTTY_POINT_VIEWPORT = 1;
    private static final int GHOSTTY_POINT_COORD_TOP_LEFT = 1;
    private static final int GHOSTTY_POINT_COORD_BOTTOM_RIGHT = 2;

    /**
     * Maps the opaque token planted in each surface config's {@code
     * userdata} back to its {@link GhosttySurface}. libghostty hands that
     * userdata (and nothing else identifying the surface) to the app-level
     * clipboard callbacks ({@link GhosttyApp}); completing a paste request
     * needs the surface handle, so the callbacks resolve it here. The token
     * is a plain counter value smuggled through the pointer -- libghostty
     * never dereferences userdata.
     */
    private static final Map<Long, GhosttySurface> CLIPBOARD_REGISTRY = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_CLIPBOARD_TOKEN = new AtomicLong(1);

    private final GhosttyAppBinding binding;
    private final long clipboardToken;
    private MemorySegment surface;
    private boolean closed;

    private GhosttySurface(GhosttyAppBinding binding, MemorySegment surface, long clipboardToken) {
        this.binding = binding;
        this.surface = surface;
        this.clipboardToken = clipboardToken;
    }

    /** The surface registered under a clipboard-callback userdata token, or {@code null}. */
    static GhosttySurface byClipboardToken(long token) {
        return CLIPBOARD_REGISTRY.get(token);
    }

    /**
     * Creates a surface rendering into {@code host}'s content view (see
     * {@link DrydockTerminalHost#contentViewHandle()}). Taking the {@code
     * DrydockTerminalHost} itself (rather than a raw pointer) keeps {@link
     * MemorySegment} entirely inside the two native-boundary packages
     * ({@code app.drydock.terminal.ghostty} and {@code app.drydock.terminal.host});
     * no caller outside them ever needs to hold one (plan section 2.4).
     *
     * @param scaleFactor the AppKit view's backing scale factor (2.0 on
     *                     Retina displays; see {@code javafx.stage.Window#getOutputScaleX()},
     *                     a public JavaFX API -- no internal API needed for this value).
     */
    public static GhosttySurface create(GhosttyApp app, DrydockTerminalHost host, double scaleFactor) {
        return create(app, host, scaleFactor, null, null);
    }

    /**
     * Same as {@link #create(GhosttyApp, DrydockTerminalHost, double)}, but lets
     * the caller override the spawned command and working directory (Task 6
     * / Gate 0D: "spawn {@code /bin/zsh -l} inside the embedded terminal").
     * Per {@code src/apprt/embedded.zig}'s {@code command} field doc
     * comment, {@code command} always runs via a shell (e.g.
     * {@code /bin/sh -c "<command>"}), so a full command line with
     * arguments (e.g. {@code "/bin/zsh -l"}) is valid here. A {@code null}
     * command falls back to libghostty's own default-shell resolution.
     */
    public static GhosttySurface create(GhosttyApp app, DrydockTerminalHost host, double scaleFactor,
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
            long clipboardToken = NEXT_CLIPBOARD_TOKEN.getAndIncrement();
            config.set(ValueLayout.ADDRESS, 16, MemorySegment.ofAddress(clipboardToken)); // userdata
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
            GhosttySurface created = new GhosttySurface(binding, surface, clipboardToken);
            CLIPBOARD_REGISTRY.put(clipboardToken, created);
            return created;
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_new", t);
        }
    }

    @Override
    public void setSize(int widthPx, int heightPx) {
        checkFxThread();
        checkOpen();
        try {
            binding.surfaceSetSize.invoke(surface, widthPx, heightPx);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_set_size", t);
        }
    }

    @Override
    public void setFocus(boolean focused) {
        checkFxThread();
        checkOpen();
        try {
            binding.surfaceSetFocus.invoke(surface, focused);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_set_focus", t);
        }
    }

    @Override
    public void draw() {
        checkFxThread();
        checkOpen();
        try {
            binding.surfaceDraw.invoke(surface);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_draw", t);
        }
    }

    /**
     * Reports the mouse position over the surface (view-local points,
     * top-left origin -- Ghostty's own SurfaceView convention). Required
     * for mouse-reporting TUIs, which hit-test wheel/click events against
     * the last reported position. {@code mods} is a ghostty mods bitmask
     * (same encoding as {@code sendKey}).
     */
    @Override
    public void sendMousePos(double x, double y, int modifierFlags) {
        sendMousePosGhostty(x, y, GhosttyKeyTranslator.translateModifiers(modifierFlags));
    }

    private void sendMousePosGhostty(double x, double y, int mods) {
        checkFxThread();
        checkOpen();
        try {
            binding.surfaceMousePos.invoke(surface, x, y, mods);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_mouse_pos", t);
        }
    }

    /**
     * Forwards a mouse button press/release at the last reported position
     * (drives text selection and click reporting to mouse-aware TUIs).
     * {@code state} is a {@code ghostty_input_mouse_state_e} (1 press,
     * 0 release), {@code button} a {@code ghostty_input_mouse_button_e}
     * (1 left, 2 right, 3 middle), {@code mods} the ghostty mods bitmask
     * (same encoding as {@code sendKey}).
     */
    @Override
    public void sendMouseButton(int state, int button, int modifierFlags) {
        sendMouseButtonGhostty(state, button, GhosttyKeyTranslator.translateModifiers(modifierFlags));
    }

    private void sendMouseButtonGhostty(int state, int button, int mods) {
        checkFxThread();
        checkOpen();
        try {
            binding.surfaceMouseButton.invoke(surface, state, button, mods);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_mouse_button", t);
        }
    }

    /**
     * Forwards a mouse-wheel/trackpad scroll to the terminal (scrollback in
     * the shell, or mouse-scroll reporting to a TUI that enabled it).
     * {@code scrollMods} is the packed {@code ghostty_input_scroll_mods_t}
     * produced by the host shim's scrollWheel handler.
     */
    @Override
    public void sendMouseScroll(double deltaX, double deltaY, int scrollMods) {
        checkFxThread();
        checkOpen();
        try {
            binding.surfaceMouseScroll.invoke(surface, deltaX, deltaY, scrollMods);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_mouse_scroll", t);
        }
    }

    @Override
    public Optional<Shortcut> dispatchKeyEvent(int keyCode, int modifierFlags, boolean keyDown,
                                               String characters, String unshiftedCharacters) {
        // Classification policy (app-shortcut interception, special-key vs
        // typed-character split, unshifted-codepoint rule) lives in
        // GhosttyKeyTranslator; this method performs the effects.
        switch (GhosttyKeyTranslator.translate(keyCode, modifierFlags, keyDown, characters, unshiftedCharacters)) {
            case GhosttyKeyTranslator.AppShortcut(Shortcut shortcut, boolean down) -> {
                // App shortcuts run on key-down only; both edges are swallowed by the caller.
                return down ? Optional.of(shortcut) : Optional.empty();
            }
            case GhosttyKeyTranslator.ForwardKey(int code, int mods, boolean down, int unshiftedCodepoint) -> {
                sendKey(code, mods, down, unshiftedCodepoint);
                return Optional.empty();
            }
            case GhosttyKeyTranslator.TypeCharacters(String typed, int mods) -> {
                typed.codePoints().forEach(cp -> sendCharKey(cp, mods));
                return Optional.empty();
            }
            case GhosttyKeyTranslator.Ignore ignored -> {
                return Optional.empty();
            }
        }
    }

    @Override
    public void submitLine(String line) {
        sendTypedText(line);
        // Return keypress (raw macOS keycode; see GhosttyKeyTranslator).
        sendKey(GhosttyKeyTranslator.KEY_RETURN, 0, true, 0);
        sendKey(GhosttyKeyTranslator.KEY_RETURN, 0, false, 0);
    }

    /**
     * Applies {@code app}'s current config to this live surface via
     * {@code ghostty_surface_update_config} (used for theme switches --
     * call after {@link GhosttyApp#updateConfig}).
     */
    public void applyConfig(GhosttyApp app) {
        checkFxThread();
        checkOpen();
        try {
            binding.surfaceUpdateConfig.invoke(surface, app.configHandle());
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_update_config", t);
        }
    }

    @Override
    public void refresh() {
        checkFxThread();
        checkOpen();
        try {
            binding.surfaceRefresh.invoke(surface);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_refresh", t);
        }
    }

    /** Feeds literal text (e.g. from a keyDown's resolved {@code characters}) to the surface. */
    public void sendText(String text) {
        checkFxThread();
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
     * <p>Does not populate {@code unshifted_codepoint}. That is harmless for
     * genuinely functional keys (Enter, arrows, etc. -- present in Ghostty's
     * Kitty-protocol {@code kitty_entries} table by {@code event.key}, not by
     * codepoint), but for a Ctrl/Cmd-modified <em>letter</em> key (e.g.
     * Ctrl+C) this silently drops the event whenever the foreground program
     * has negotiated Kitty keyboard protocol -- see {@link #sendKey(int, int,
     * boolean, int)} and docs/claude-integration.md, "Incompatibility: Ctrl+C
     * did not cancel an in-progress response". Prefer that overload for any
     * modified letter/digit shortcut.</p>
     *
     * @param ghosttyKeyCode one of the {@code GHOSTTY_KEY_*} ordinals from {@code ghostty.h}
     * @param mods           a bitwise-OR of {@code GHOSTTY_MODS_*}
     * @param pressed        {@code true} for key-down/repeat, {@code false} for key-up
     */
    public boolean sendKey(int ghosttyKeyCode, int mods, boolean pressed) {
        return sendKey(ghosttyKeyCode, mods, pressed, null, 0);
    }

    /**
     * Sends a non-text key event, additionally specifying the key's
     * unshifted base-character codepoint (e.g. {@code 'c'} = 99 for a Ctrl+C
     * press -- AppKit's {@code NSEvent.charactersIgnoringModifiers}).
     *
     * <p>Required for correct behavior when the terminal has Kitty keyboard
     * protocol active (common for TUI frameworks like Ink, which `claude`'s
     * CLI is built on): Ghostty's Kitty encoder
     * (third_party/ghostty/src/input/key_encode.zig's {@code kitty()})
     * identifies non-functional keys (plain ASCII letters/digits) purely by
     * {@code unshifted_codepoint}; a native macOS keycode alone is not
     * enough. Without it, {@code entry_} resolves to {@code null} and (since
     * there is also no UTF-8 {@code text} for a Ctrl-combo) the encoder
     * writes nothing at all to the pty -- the keypress vanishes silently,
     * with no error, no consumed=false signal a caller could detect. This was
     * confirmed as the root cause of Ctrl+C appearing to do nothing against
     * `claude` while working correctly against a plain shell (which uses
     * Ghostty's non-Kitty "legacy" encoder instead, and does not need this
     * field) -- see docs/claude-integration.md.</p>
     */
    public boolean sendKey(int ghosttyKeyCode, int mods, boolean pressed, int unshiftedCodepoint) {
        return sendKey(ghosttyKeyCode, mods, pressed, null, unshiftedCodepoint);
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
        boolean consumed = sendKey(GHOSTTY_KEY_UNIDENTIFIED, mods, true, text, codepoint);
        sendKey(GHOSTTY_KEY_UNIDENTIFIED, mods, false, null, 0);
        return consumed;
    }

    /** Convenience: sends every codepoint in {@code text} as a separate typed-key press+release. */
    public void sendTypedText(String text) {
        text.codePoints().forEach(cp -> sendCharKey(cp, 0));
    }

    private boolean sendKey(int ghosttyKeyCode, int mods, boolean pressed, String text, int unshiftedCodepointOverride) {
        checkFxThread();
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
            int unshifted = unshiftedCodepointOverride != 0
                ? unshiftedCodepointOverride
                : (text != null && !text.isEmpty()) ? text.codePointAt(0) : 0;
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
    @Override
    public String readScreenText() {
        checkFxThread();
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
                return new String(bytes, StandardCharsets.UTF_8);
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

    /**
     * Completes a pending clipboard read request (paste) with the given
     * text. {@code state} is the opaque request pointer libghostty handed to
     * the app runtime's {@code read_clipboard_cb}/{@code
     * confirm_read_clipboard_cb} (see {@link GhosttyApp}); libghostty owns
     * and frees it once this call completes the request. {@code confirmed}
     * marks a paste the app vouches for even if ghostty's safe-paste check
     * flagged it (e.g. multi-line text).
     *
     * <p>Silently drops the request if this surface is already closed (the
     * request state then leaks a few bytes inside libghostty, which beats
     * calling into a freed surface).</p>
     */
    void completeClipboardRequest(String text, MemorySegment state, boolean confirmed) {
        if (closed) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cstring = GhosttyAppBinding.allocateCString(arena, text);
            binding.surfaceCompleteClipboardRequest.invoke(surface, cstring, state, confirmed);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_complete_clipboard_request", t);
        }
    }

    @Override
    public boolean processExited() {
        checkFxThread();
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

    /** Native calls are FX-Application-Thread-only (see GhosttyApp's class Javadoc); fail fast elsewhere. */
    private static void checkFxThread() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Not on the JavaFX Application Thread");
        }
    }

    /**
     * Frees the surface immediately. Per the reproducible, documented defect
     * in docs/phase0-feasibility-report.md ("What does not work") and
     * docs/claude-integration.md ("Incompatibility: closing a surface with a
     * live child process"), calling {@code ghostty_surface_free} while the
     * spawned child process (shell/{@code claude}) is still alive has been
     * observed to terminate the entire JVM process with no catchable Java
     * exception -- most likely a Zig-level panic/abort inside libghostty's
     * own teardown (the {@code renderer.threadEnter(...) catch unreachable}
     * re-entry in {@code Surface.deinit()}, which is far more likely to fail
     * while the renderer is still actively busy servicing a live child's
     * output than when it is already idle).
     *
     * <p>Callers should prefer {@link #closeGracefully(long, long, Runnable)}
     * for any surface that might still have a live process. This method
     * remains available for the already-exited case (e.g. after {@link
     * #processExited()} is confirmed {@code true}) and as the terminal step
     * of {@code closeGracefully}.</p>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        CLIPBOARD_REGISTRY.remove(clipboardToken);
        try {
            binding.surfaceFree.invoke(surface);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_surface_free", t);
        } finally {
            surface = MemorySegment.NULL;
        }
    }

    /**
     * Non-blocking, JavaFX-Application-Thread-only graceful shutdown (plan
     * section 9 "Lifecycle rules": "Closing a tab prompts before terminating
     * a running process"; plan section 11.2's spirit of not silently killing
     * work applies equally to closing).
     *
     * <p>Requests a graceful exit (Ctrl+D, matching Gate 0D's verified
     * "Ctrl+D exits the shell" behavior on an assumed-idle prompt line), then
     * polls {@link #processExited()} every {@code pollIntervalMillis} until
     * either the process exits or {@code gracePeriodMillis} elapses, calling
     * {@link #close()} only once the process is confirmed exited.</p>
     *
     * <p>If the grace period elapses with the process still alive, this
     * still calls {@link #close()} as a last resort (a hung/unresponsive
     * child must not permanently leak the surface), but that call carries
     * the same crash risk documented on {@link #close()} -- there is no
     * public libghostty API to forcibly kill the child process without
     * freeing the surface (see docs/native-integration.md). Choose {@code
     * gracePeriodMillis} generously enough that this fallback is a rare
     * safety net, not the common path.</p>
     *
     * @param onDone invoked (still on the JavaFX Application Thread) once the
     *               surface is closed, whether via graceful exit or timeout;
     *               may be {@code null}.
     */
    @Override
    public void closeGracefully(long gracePeriodMillis, long pollIntervalMillis, Runnable onDone) {
        if (closed) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        if (processExited()) {
            close();
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        LOG.log(Logger.Level.INFO, "closeGracefully: child process still alive, requesting Ctrl+D exit"
            + " and waiting up to {0}ms before forcing close", gracePeriodMillis);
        // Unshifted codepoint ('d'=100) required so Ghostty's Kitty-keyboard-
        // protocol encoder can identify this key if the child program (e.g.
        // claude) has negotiated that protocol -- see
        // sendKey(int,int,boolean,int)'s Javadoc and
        // docs/claude-integration.md. Without it this Ctrl+D would be
        // silently dropped exactly like the Ctrl+C finding there.
        // KEY_D is the raw kVK_ANSI_D platform keycode (not a GHOSTTY_KEY_*
        // ordinal -- Task 6 finding, see docs/native-integration.md,
        // "Struct layout verification"), verified working via Gate 0D's
        // Ctrl+D-exits-the-shell check.
        sendKey(GhosttyKeyTranslator.KEY_D, GhosttyKeyTranslator.MODS_CTRL, true, (int) 'd');
        sendKey(GhosttyKeyTranslator.KEY_D, GhosttyKeyTranslator.MODS_CTRL, false, (int) 'd');
        pollUntilExitedOrTimeout(System.currentTimeMillis() + gracePeriodMillis, pollIntervalMillis, onDone);
    }

    private void pollUntilExitedOrTimeout(long deadlineMillis, long pollIntervalMillis, Runnable onDone) {
        if (closed) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        boolean exited = processExited();
        boolean timedOut = System.currentTimeMillis() >= deadlineMillis;
        if (exited || timedOut) {
            if (!exited) {
                LOG.log(Logger.Level.WARNING, "closeGracefully: grace period elapsed with the child process"
                    + " still alive; forcing close (this carries the documented ghostty_surface_free"
                    + " live-child crash risk -- see docs/phase0-feasibility-report.md)");
            }
            close();
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        PauseTransition pause = new PauseTransition(Duration.millis(pollIntervalMillis));
        pause.setOnFinished(event -> pollUntilExitedOrTimeout(deadlineMillis, pollIntervalMillis, onDone));
        pause.play();
    }
}
