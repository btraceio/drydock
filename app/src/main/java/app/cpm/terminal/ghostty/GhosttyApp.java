package app.cpm.terminal.ghostty;

import javafx.application.Platform;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A running {@code ghostty_app_t} plus its runtime-config callbacks.
 *
 * <p>Per plan rule 27.6/27.17 this transcribes {@code ghostty_runtime_config_s}
 * exactly (see {@link GhosttyAppBinding#RUNTIME_CONFIG_LAYOUT}); every
 * callback field must be a valid function pointer or a null one that
 * libghostty is documented/observed not to invoke unconditionally. Gate 0C
 * only needs {@code wakeup_cb} to actually do something (schedule a tick on
 * the caller-supplied {@link Runnable} -- the caller is expected to pass
 * something like {@code () -> Platform.runLater(app::tick)}); the others
 * are wired to safe, side-effect-free stubs because implementing their
 * full payload semantics (especially {@code action_cb}, whose {@code
 * ghostty_action_s} argument is a large tagged union) is out of scope for
 * this feasibility spike -- see docs/native-integration.md, "What is and
 * isn't wired up for Gate 0C".</p>
 *
 * <p>Part of the narrow native boundary (plan section 2.4/4.2).</p>
 */
public final class GhosttyApp implements AutoCloseable {

    private static final AtomicBoolean PROCESS_INITIALIZED = new AtomicBoolean(false);

    /**
     * Calls {@code ghostty_init} exactly once per process (idempotent; safe
     * to call from multiple call sites/threads). Per
     * docs/native-integration.md ("Lifecycle and thread constraints"),
     * {@code ghostty_init} must be called exactly once before any other
     * libghostty API -- callers should invoke this before {@link
     * #create(SymbolLookup, Runnable)}.
     */
    public static void ensureProcessInitialized(SymbolLookup lookup) {
        if (PROCESS_INITIALIZED.compareAndSet(false, true)) {
            int result = new GhosttyBinding(lookup).init();
            if (result != 0) {
                PROCESS_INITIALIZED.set(false);
                throw new IllegalStateException("ghostty_init() returned " + result + ", expected 0");
            }
        }
    }

    private final GhosttyAppBinding binding;
    private final GhosttyBinding coreBinding;
    private final Arena arena;
    private MemorySegment config;
    private MemorySegment app;
    private boolean closed;

    private GhosttyApp(GhosttyAppBinding binding, GhosttyBinding coreBinding, Arena arena,
                        MemorySegment config, MemorySegment app) {
        this.binding = binding;
        this.coreBinding = coreBinding;
        this.arena = arena;
        this.config = config;
        this.app = app;
    }

    /**
     * Creates a {@code ghostty_app_t}, including its own {@code
     * ghostty_config_t} (freed automatically in {@link #close()}) -- the
     * caller never touches a {@link MemorySegment} directly, keeping the
     * narrow native boundary (plan section 2.4) intact for code outside
     * this package.
     *
     * @param lookup   the libghostty symbol lookup (see {@code GhosttyNativeLibrary#lookup()})
     * @param onWakeup invoked from an arbitrary native thread (the caller must marshal onto
     *                 the correct thread itself, e.g. {@code () -> Platform.runLater(...)})
     *                 whenever libghostty wants {@link #tick()} called again.
     */
    public static GhosttyApp create(SymbolLookup lookup, Runnable onWakeup) {
        return create(lookup, onWakeup, Optional.empty());
    }

    /**
     * Variant that loads a ghostty config file (theme colors, padding --
     * see {@code app.cpm.ui.TerminalThemes}) into the app's config before
     * {@code ghostty_app_new}. Only the given file is loaded; the user's
     * own {@code ~/.config/ghostty} is deliberately never read, so managed
     * terminals always match the app theme deterministically.
     */
    public static GhosttyApp create(SymbolLookup lookup, Runnable onWakeup,
                                     Optional<Path> configFile) {
        GhosttyAppBinding binding = new GhosttyAppBinding(lookup);
        GhosttyBinding coreBinding = new GhosttyBinding(lookup);
        MemorySegment config = loadedConfig(coreBinding, configFile);
        Arena arena = Arena.ofShared();

        MemorySegment wakeupStub = boundUpcall(binding, arena,
            "handleWakeup", MethodType.methodType(void.class, Runnable.class, MemorySegment.class),
            onWakeup, GhosttyAppBinding.WAKEUP_CB_DESCRIPTOR);
        MemorySegment actionStub = plainUpcall(binding, arena,
            "handleAction", MethodType.methodType(boolean.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
            GhosttyAppBinding.ACTION_CB_DESCRIPTOR);
        MemorySegment readClipboardStub = plainUpcall(binding, arena,
            "handleReadClipboard", MethodType.methodType(boolean.class, MemorySegment.class, int.class, MemorySegment.class),
            GhosttyAppBinding.READ_CLIPBOARD_CB_DESCRIPTOR);
        MemorySegment confirmReadClipboardStub = plainUpcall(binding, arena,
            "handleConfirmReadClipboard",
            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class, int.class),
            GhosttyAppBinding.CONFIRM_READ_CLIPBOARD_CB_DESCRIPTOR);
        MemorySegment writeClipboardStub = plainUpcall(binding, arena,
            "handleWriteClipboard",
            MethodType.methodType(void.class, MemorySegment.class, int.class, MemorySegment.class, long.class, boolean.class),
            GhosttyAppBinding.WRITE_CLIPBOARD_CB_DESCRIPTOR);
        MemorySegment closeSurfaceStub = plainUpcall(binding, arena,
            "handleCloseSurface", MethodType.methodType(void.class, MemorySegment.class, boolean.class),
            GhosttyAppBinding.CLOSE_SURFACE_CB_DESCRIPTOR);

        MemorySegment runtimeConfig = arena.allocate(GhosttyAppBinding.RUNTIME_CONFIG_LAYOUT);
        runtimeConfig.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL); // userdata (unused; closures capture state instead)
        // macOS has no X11-style selection clipboard, and JavaFX exposes only
        // the one system clipboard; advertising selection support would make
        // ghostty's copy-on-select clobber the real clipboard on every drag.
        runtimeConfig.set(ValueLayout.JAVA_BOOLEAN, 8, false); // supports_selection_clipboard
        runtimeConfig.set(ValueLayout.ADDRESS, 16, wakeupStub);
        runtimeConfig.set(ValueLayout.ADDRESS, 24, actionStub);
        runtimeConfig.set(ValueLayout.ADDRESS, 32, readClipboardStub);
        runtimeConfig.set(ValueLayout.ADDRESS, 40, confirmReadClipboardStub);
        runtimeConfig.set(ValueLayout.ADDRESS, 48, writeClipboardStub);
        runtimeConfig.set(ValueLayout.ADDRESS, 56, closeSurfaceStub);

        MemorySegment app;
        try {
            app = (MemorySegment) binding.appNew.invoke(runtimeConfig, config);
        } catch (Throwable t) {
            arena.close();
            coreBinding.configFree(config);
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_app_new", t);
        }
        if (app.equals(MemorySegment.NULL)) {
            arena.close();
            coreBinding.configFree(config);
            throw new IllegalStateException("ghostty_app_new returned NULL");
        }
        return new GhosttyApp(binding, coreBinding, arena, config, app);
    }

    private static MemorySegment loadedConfig(GhosttyBinding coreBinding,
                                               Optional<Path> configFile) {
        MemorySegment config = coreBinding.configNew();
        try {
            configFile.ifPresent(path -> coreBinding.configLoadFile(config, path.toString()));
            coreBinding.configFinalize(config);
        } catch (RuntimeException e) {
            coreBinding.configFree(config);
            throw e;
        }
        return config;
    }

    /**
     * Replaces this app's config with one loaded from {@code configFile}
     * (theme switch) via {@code ghostty_app_update_config}. The previous
     * config handle is freed only after libghostty has taken what it needs
     * from the new one (it derives internal copies; the caller-owned handle
     * must stay valid while it is the app's current config -- mirrors
     * Ghostty's own macOS app, which keeps the latest config alive and
     * frees the replaced one). Existing surfaces still need {@link
     * GhosttySurface#applyConfig(GhosttyApp)} to pick the change up.
     */
    public void updateConfig(Path configFile) {
        checkOpen();
        MemorySegment newConfig = loadedConfig(coreBinding, Optional.of(configFile));
        try {
            binding.appUpdateConfig.invoke(app, newConfig);
        } catch (Throwable t) {
            coreBinding.configFree(newConfig);
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_app_update_config", t);
        }
        MemorySegment oldConfig = config;
        config = newConfig;
        coreBinding.configFree(oldConfig);
    }

    /** Package-visible: needed by {@link GhosttySurface} to call {@code ghostty_surface_new}. */
    GhosttyAppBinding binding() {
        return binding;
    }

    /** Package-visible: the app's current (finalized) config, for {@code ghostty_surface_update_config}. */
    MemorySegment configHandle() {
        return config;
    }

    MemorySegment handle() {
        return app;
    }

    /** Calls {@code ghostty_app_tick}. Must be called on the same thread every time (see class Javadoc). */
    public void tick() {
        checkOpen();
        try {
            binding.appTick.invoke(app);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_app_tick", t);
        }
    }

    public void setFocus(boolean focused) {
        checkOpen();
        try {
            binding.appSetFocus.invoke(app, focused);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_app_set_focus", t);
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("GhosttyApp already closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            binding.appFree.invoke(app);
        } catch (Throwable t) {
            throw new GhosttyBinding.GhosttyNativeCallException("ghostty_app_free", t);
        } finally {
            app = MemorySegment.NULL;
            coreBinding.configFree(config);
            config = MemorySegment.NULL;
            arena.close();
        }
    }

    // --- Upcall handlers -----------------------------------------------

    @SuppressWarnings("unused")
    private static void handleWakeup(Runnable onWakeup, MemorySegment userdata) {
        onWakeup.run();
    }

    /**
     * {@code action_cb}: always returns {@code false} ("not handled by the
     * app runtime") without reading the {@code ghostty_action_s} payload.
     * Safe for the actions Gate 0C actually exercises (drawing, resizing,
     * basic typing); very likely NOT complete for a real application (it
     * will not open new windows/tabs, honor "quit", set the clipboard from
     * an OSC 52 write, etc.) -- see docs/native-integration.md.
     */
    @SuppressWarnings("unused")
    private static boolean handleAction(MemorySegment app, MemorySegment target, MemorySegment action) {
        return false;
    }

    /**
     * {@code read_clipboard_cb}: a paste (or OSC 52 read) wants the system
     * clipboard's contents. {@code userdata} is the surface-config token
     * {@link GhosttySurface} planted, resolved back to the surface so the
     * request can be completed via {@code
     * ghostty_surface_complete_clipboard_request}. Completing synchronously
     * from inside the callback is the normal embedded-runtime pattern
     * (Ghostty's own macOS app does the same when the pasteboard is readily
     * readable); the JavaFX clipboard requires the FX thread, which is where
     * every callback-triggering native call (surface_key, tick) is made.
     */
    @SuppressWarnings("unused")
    private static boolean handleReadClipboard(MemorySegment userdata, int location, MemorySegment state) {
        GhosttySurface surface = GhosttySurface.byClipboardToken(userdata.address());
        if (surface == null) {
            return false;
        }
        Runnable complete = () -> {
            String text = Clipboard.getSystemClipboard().getString();
            surface.completeClipboardRequest(text == null ? "" : text, state, false);
        };
        if (Platform.isFxApplicationThread()) {
            complete.run();
        } else {
            Platform.runLater(complete);
        }
        return true;
    }

    /**
     * {@code confirm_read_clipboard_cb}: ghostty's safe-paste check flagged
     * the pending paste (multi-line text, control characters). The managed
     * terminal always hosts a full-screen TUI (claude) where multi-line
     * pastes are routine, so auto-confirm instead of interrupting with a
     * dialog. Completion is deferred to {@code Platform.runLater} so the
     * re-entrant complete call runs after the current native call unwinds.
     */
    @SuppressWarnings("unused")
    private static void handleConfirmReadClipboard(MemorySegment userdata, MemorySegment string,
                                                    MemorySegment state, int request) {
        GhosttySurface surface = GhosttySurface.byClipboardToken(userdata.address());
        if (surface == null) {
            return;
        }
        String text = string.equals(MemorySegment.NULL)
                ? "" : string.reinterpret(Long.MAX_VALUE).getString(0);
        Platform.runLater(() -> surface.completeClipboardRequest(text, state, true));
    }

    /**
     * {@code write_clipboard_cb}: copy-to-clipboard (or an OSC 52 write)
     * publishes terminal text. {@code content} is an array of {@code len}
     * {@code ghostty_clipboard_content_s} entries ({mime, data} C-string
     * pairs); the plain-text entry wins. {@code confirm} (OSC 52
     * ask-before-write) is not surfaced as a dialog -- the write simply
     * proceeds, matching the copy semantics a terminal user expects.
     */
    @SuppressWarnings("unused")
    private static void handleWriteClipboard(MemorySegment userdata, int location,
                                              MemorySegment content, long len, boolean confirm) {
        if (len <= 0 || content.equals(MemorySegment.NULL)) {
            return;
        }
        MemorySegment entries = content.reinterpret(len * 16);
        String text = null;
        for (long i = 0; i < len; i++) {
            MemorySegment mimePtr = entries.get(ValueLayout.ADDRESS, i * 16);
            MemorySegment dataPtr = entries.get(ValueLayout.ADDRESS, i * 16 + 8);
            if (dataPtr.equals(MemorySegment.NULL)) {
                continue;
            }
            String mime = mimePtr.equals(MemorySegment.NULL)
                    ? "" : mimePtr.reinterpret(Long.MAX_VALUE).getString(0);
            String data = dataPtr.reinterpret(Long.MAX_VALUE).getString(0);
            if (text == null || mime.startsWith("text/plain")) {
                text = data;
            }
            if (mime.startsWith("text/plain")) {
                break;
            }
        }
        if (text == null) {
            return;
        }
        String value = text;
        Runnable set = () -> {
            ClipboardContent clipboardContent = new ClipboardContent();
            clipboardContent.putString(value);
            Clipboard.getSystemClipboard().setContent(clipboardContent);
        };
        if (Platform.isFxApplicationThread()) {
            set.run();
        } else {
            Platform.runLater(set);
        }
    }

    @SuppressWarnings("unused")
    private static void handleCloseSurface(MemorySegment userdata, boolean processAlive) {
        // no-op: surface lifecycle in this spike is driven by the JavaFX window closing.
    }

    /** Binds a captured-argument upcall (only {@code handleWakeup} needs this). */
    private static <T> MemorySegment boundUpcall(GhosttyAppBinding binding, Arena arena, String methodName,
                                                   MethodType fullType, T capturedArg, FunctionDescriptor descriptor) {
        try {
            MethodHandle target = MethodHandles.lookup().findStatic(GhosttyApp.class, methodName, fullType);
            MethodHandle bound = MethodHandles.insertArguments(target, 0, capturedArg);
            return binding.linker().upcallStub(bound, descriptor, arena);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to bind upcall stub for " + methodName, e);
        }
    }

    /** Binds a plain (no captured state) upcall whose Java signature matches the C signature exactly. */
    private static MemorySegment plainUpcall(GhosttyAppBinding binding, Arena arena, String methodName,
                                              MethodType type, FunctionDescriptor descriptor) {
        try {
            MethodHandle target = MethodHandles.lookup().findStatic(GhosttyApp.class, methodName, type);
            return binding.linker().upcallStub(target, descriptor, arena);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to bind upcall stub for " + methodName, e);
        }
    }
}
