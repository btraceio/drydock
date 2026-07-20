package app.cpm.terminal.host;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.System.Logger;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Hand-written FFM bindings for {@code native-host/CpmTerminalHost.h} (the
 * AppKit host shim, plan section 8).
 *
 * <p>Part of the narrow native boundary (plan section 2.4/4.2): no code
 * outside {@code app.cpm.terminal.host}/{@code app.cpm.terminal.ghostty}
 * may reference {@link MemorySegment}, {@link MethodHandle}, or {@link
 * Linker} for terminal-surface purposes.</p>
 */
final class CpmTerminalHostBinding {

    private static final Logger LOG = System.getLogger(CpmTerminalHostBinding.class.getName());

    // The listener shapes are CpmTerminalHost's own public interfaces
    // (KeyEventListener etc.) -- the binding deliberately defines no
    // duplicates, so CpmTerminalHost can pass caller listeners through
    // without wrapping.

    /** Native shape of {@code cpm_terminal_host_key_event_cb}. */
    private static final FunctionDescriptor KEY_EVENT_CB_DESCRIPTOR = FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,   // void* userdata
        ValueLayout.JAVA_SHORT, // uint16_t key_code
        ValueLayout.JAVA_INT,  // uint32_t modifier_flags
        ValueLayout.JAVA_INT,  // int is_key_down
        ValueLayout.ADDRESS,   // const char* characters
        ValueLayout.JAVA_LONG, // size_t characters_len
        ValueLayout.ADDRESS,   // const char* unshifted_characters
        ValueLayout.JAVA_LONG  // size_t unshifted_characters_len
    );

    /** Native shape of {@code cpm_terminal_host_scroll_event_cb}. */
    private static final FunctionDescriptor SCROLL_EVENT_CB_DESCRIPTOR = FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,     // void* userdata
        ValueLayout.JAVA_DOUBLE, // double delta_x
        ValueLayout.JAVA_DOUBLE, // double delta_y
        ValueLayout.JAVA_BYTE    // uint8_t scroll_mods (packed ghostty ScrollMods)
    );

    /** Native shape of {@code cpm_terminal_host_mouse_pos_event_cb}. */
    private static final FunctionDescriptor MOUSE_POS_EVENT_CB_DESCRIPTOR = FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,     // void* userdata
        ValueLayout.JAVA_DOUBLE, // double x (view-local, top-left origin, points)
        ValueLayout.JAVA_DOUBLE, // double y
        ValueLayout.JAVA_INT     // uint32_t modifier_flags
    );

    /** Native shape of {@code cpm_terminal_host_mouse_button_event_cb}. */
    private static final FunctionDescriptor MOUSE_BUTTON_EVENT_CB_DESCRIPTOR = FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,  // void* userdata
        ValueLayout.JAVA_INT, // int state (ghostty_input_mouse_state_e)
        ValueLayout.JAVA_INT, // int button (ghostty_input_mouse_button_e)
        ValueLayout.JAVA_INT  // uint32_t modifier_flags
    );

    private static final MethodHandle KEY_EVENT_TRAMPOLINE;
    private static final MethodHandle SCROLL_EVENT_TRAMPOLINE;
    private static final MethodHandle MOUSE_POS_EVENT_TRAMPOLINE;
    private static final MethodHandle MOUSE_BUTTON_EVENT_TRAMPOLINE;

    static {
        try {
            KEY_EVENT_TRAMPOLINE = MethodHandles.lookup().findStatic(
                CpmTerminalHostBinding.class,
                "dispatchKeyEvent",
                MethodType.methodType(
                    void.class,
                    CpmTerminalHost.KeyEventListener.class,
                    MemorySegment.class,
                    short.class,
                    int.class,
                    int.class,
                    MemorySegment.class,
                    long.class,
                    MemorySegment.class,
                    long.class
                )
            );
            SCROLL_EVENT_TRAMPOLINE = MethodHandles.lookup().findStatic(
                CpmTerminalHostBinding.class,
                "dispatchScrollEvent",
                MethodType.methodType(
                    void.class,
                    CpmTerminalHost.ScrollEventListener.class,
                    MemorySegment.class,
                    double.class,
                    double.class,
                    byte.class
                )
            );
            MOUSE_POS_EVENT_TRAMPOLINE = MethodHandles.lookup().findStatic(
                CpmTerminalHostBinding.class,
                "dispatchMousePosEvent",
                MethodType.methodType(
                    void.class,
                    CpmTerminalHost.MousePosEventListener.class,
                    MemorySegment.class,
                    double.class,
                    double.class,
                    int.class
                )
            );
            MOUSE_BUTTON_EVENT_TRAMPOLINE = MethodHandles.lookup().findStatic(
                CpmTerminalHostBinding.class,
                "dispatchMouseButtonEvent",
                MethodType.methodType(
                    void.class,
                    CpmTerminalHost.MouseButtonEventListener.class,
                    MemorySegment.class,
                    int.class,
                    int.class,
                    int.class
                )
            );
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Process-wide instances, one per {@link SymbolLookup} (itself a
     * process-wide singleton -- {@code CpmTerminalHostLibrary.lookup()}),
     * so the downcall handles are linked once per process instead of once
     * per {@code CpmTerminalHost}; the handles are stateless and
     * thread-safe to share.
     */
    private static final ConcurrentMap<SymbolLookup, CpmTerminalHostBinding> INSTANCES = new ConcurrentHashMap<>();

    /** Returns the process-wide binding for {@code lookup}, linking it on first use. */
    static CpmTerminalHostBinding of(SymbolLookup lookup) {
        return INSTANCES.computeIfAbsent(lookup, CpmTerminalHostBinding::new);
    }

    private final Linker linker = Linker.nativeLinker();

    private final MethodHandle create;
    private final MethodHandle setFrame;
    private final MethodHandle contentView;
    private final MethodHandle setVisible;
    private final MethodHandle setFocused;
    private final MethodHandle destroy;
    private final MethodHandle setKeyEventCallback;
    private final MethodHandle setScrollEventCallback;
    private final MethodHandle setMousePosEventCallback;
    private final MethodHandle setMouseButtonEventCallback;

    private CpmTerminalHostBinding(SymbolLookup lookup) {
        // cpm_terminal_host_t cpm_terminal_host_create(void* parent_nsview);
        this.create = linker.downcallHandle(
            find(lookup, "cpm_terminal_host_create"),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        // void cpm_terminal_host_set_frame(host, double, double, double, double);
        this.setFrame = linker.downcallHandle(
            find(lookup, "cpm_terminal_host_set_frame"),
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_DOUBLE,
                ValueLayout.JAVA_DOUBLE,
                ValueLayout.JAVA_DOUBLE,
                ValueLayout.JAVA_DOUBLE
            )
        );

        // void* cpm_terminal_host_content_view(host);
        this.contentView = linker.downcallHandle(
            find(lookup, "cpm_terminal_host_content_view"),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        // void cpm_terminal_host_set_visible(host, bool);
        this.setVisible = linker.downcallHandle(
            find(lookup, "cpm_terminal_host_set_visible"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN)
        );

        // void cpm_terminal_host_set_focused(host, bool);
        this.setFocused = linker.downcallHandle(
            find(lookup, "cpm_terminal_host_set_focused"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN)
        );

        // void cpm_terminal_host_destroy(host);
        this.destroy = linker.downcallHandle(
            find(lookup, "cpm_terminal_host_destroy"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );

        // void cpm_terminal_host_set_key_event_callback(host, cb, userdata);
        this.setKeyEventCallback = linker.downcallHandle(
            find(lookup, "cpm_terminal_host_set_key_event_callback"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        // void cpm_terminal_host_set_scroll_event_callback(host, cb, userdata);
        this.setScrollEventCallback = linker.downcallHandle(
            find(lookup, "cpm_terminal_host_set_scroll_event_callback"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        // void cpm_terminal_host_set_mouse_pos_event_callback(host, cb, userdata);
        this.setMousePosEventCallback = linker.downcallHandle(
            find(lookup, "cpm_terminal_host_set_mouse_pos_event_callback"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        // void cpm_terminal_host_set_mouse_button_event_callback(host, cb, userdata);
        this.setMouseButtonEventCallback = linker.downcallHandle(
            find(lookup, "cpm_terminal_host_set_mouse_button_event_callback"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );
    }

    private static MemorySegment find(SymbolLookup lookup, String name) {
        return lookup.find(name)
            .orElseThrow(() -> new IllegalStateException(
                "Symbol '" + name + "' not found in libcpmterminalhost. Re-run "
                    + "scripts/build-native-host.sh and check native-host/CpmTerminalHost.h."));
    }

    MemorySegment create(MemorySegment parentNsView) {
        try {
            return (MemorySegment) create.invoke(parentNsView);
        } catch (Throwable t) {
            throw new HostNativeCallException("cpm_terminal_host_create", t);
        }
    }

    void setFrame(MemorySegment host, double x, double y, double width, double height) {
        try {
            setFrame.invoke(host, x, y, width, height);
        } catch (Throwable t) {
            throw new HostNativeCallException("cpm_terminal_host_set_frame", t);
        }
    }

    MemorySegment contentView(MemorySegment host) {
        try {
            return (MemorySegment) contentView.invoke(host);
        } catch (Throwable t) {
            throw new HostNativeCallException("cpm_terminal_host_content_view", t);
        }
    }

    void setVisible(MemorySegment host, boolean visible) {
        try {
            setVisible.invoke(host, visible);
        } catch (Throwable t) {
            throw new HostNativeCallException("cpm_terminal_host_set_visible", t);
        }
    }

    void setFocused(MemorySegment host, boolean focused) {
        try {
            setFocused.invoke(host, focused);
        } catch (Throwable t) {
            throw new HostNativeCallException("cpm_terminal_host_set_focused", t);
        }
    }

    void destroy(MemorySegment host) {
        try {
            destroy.invoke(host);
        } catch (Throwable t) {
            throw new HostNativeCallException("cpm_terminal_host_destroy", t);
        }
    }

    /**
     * Registers {@code listener} as the host's key-event callback. Creates
     * an FFM upcall stub allocated from {@code arena}; the caller must keep
     * {@code arena} open for as long as key events may still arrive (i.e.
     * until after {@link #destroy(MemorySegment)} has been called for this
     * host), otherwise a native call into a freed trampoline would crash
     * the process.
     */
    void setKeyEventCallback(MemorySegment host, CpmTerminalHost.KeyEventListener listener, Arena arena) {
        MethodHandle bound = MethodHandles.insertArguments(KEY_EVENT_TRAMPOLINE, 0, listener);
        MemorySegment stub = linker.upcallStub(bound, KEY_EVENT_CB_DESCRIPTOR, arena);
        try {
            setKeyEventCallback.invoke(host, stub, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new HostNativeCallException("cpm_terminal_host_set_key_event_callback", t);
        }
    }

    /**
     * Registers {@code listener} as the host's scroll-event callback; same
     * arena-lifetime contract as {@link #setKeyEventCallback}.
     */
    void setScrollEventCallback(MemorySegment host, CpmTerminalHost.ScrollEventListener listener, Arena arena) {
        MethodHandle bound = MethodHandles.insertArguments(SCROLL_EVENT_TRAMPOLINE, 0, listener);
        MemorySegment stub = linker.upcallStub(bound, SCROLL_EVENT_CB_DESCRIPTOR, arena);
        try {
            setScrollEventCallback.invoke(host, stub, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new HostNativeCallException("cpm_terminal_host_set_scroll_event_callback", t);
        }
    }

    /**
     * Registers {@code listener} as the host's mouse-position callback; same
     * arena-lifetime contract as {@link #setKeyEventCallback}.
     */
    void setMousePosEventCallback(MemorySegment host, CpmTerminalHost.MousePosEventListener listener, Arena arena) {
        MethodHandle bound = MethodHandles.insertArguments(MOUSE_POS_EVENT_TRAMPOLINE, 0, listener);
        MemorySegment stub = linker.upcallStub(bound, MOUSE_POS_EVENT_CB_DESCRIPTOR, arena);
        try {
            setMousePosEventCallback.invoke(host, stub, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new HostNativeCallException("cpm_terminal_host_set_mouse_pos_event_callback", t);
        }
    }

    /**
     * Registers {@code listener} as the host's mouse-button callback; same
     * arena-lifetime contract as {@link #setKeyEventCallback}.
     */
    void setMouseButtonEventCallback(MemorySegment host, CpmTerminalHost.MouseButtonEventListener listener, Arena arena) {
        MethodHandle bound = MethodHandles.insertArguments(MOUSE_BUTTON_EVENT_TRAMPOLINE, 0, listener);
        MemorySegment stub = linker.upcallStub(bound, MOUSE_BUTTON_EVENT_CB_DESCRIPTOR, arena);
        try {
            setMouseButtonEventCallback.invoke(host, stub, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new HostNativeCallException("cpm_terminal_host_set_mouse_button_event_callback", t);
        }
    }

    // Every dispatch trampoline below is wrapped in try/catch (Throwable): a
    // Java exception escaping an FFM upcall stub into native code terminates
    // the whole JVM, so a throwing user-supplied listener must be logged and
    // swallowed here, never propagated.

    /** Upcall trampoline invoked directly by native code; see MOUSE_BUTTON_EVENT_TRAMPOLINE. */
    @SuppressWarnings("unused")
    private static void dispatchMouseButtonEvent(
            CpmTerminalHost.MouseButtonEventListener listener,
            MemorySegment userdata,
            int state,
            int button,
            int modifierFlags) {
        try {
            listener.onMouseButtonEvent(state, button, modifierFlags);
        } catch (Throwable t) {
            LOG.log(Logger.Level.ERROR, "mouse-button event listener failed", t);
        }
    }

    /** Upcall trampoline invoked directly by native code; see MOUSE_POS_EVENT_TRAMPOLINE. */
    @SuppressWarnings("unused")
    private static void dispatchMousePosEvent(
            CpmTerminalHost.MousePosEventListener listener,
            MemorySegment userdata,
            double x,
            double y,
            int modifierFlags) {
        try {
            listener.onMousePosEvent(x, y, modifierFlags);
        } catch (Throwable t) {
            LOG.log(Logger.Level.ERROR, "mouse-position event listener failed", t);
        }
    }

    /** Upcall trampoline invoked directly by native code; see SCROLL_EVENT_TRAMPOLINE. */
    @SuppressWarnings("unused")
    private static void dispatchScrollEvent(
            CpmTerminalHost.ScrollEventListener listener,
            MemorySegment userdata,
            double deltaX,
            double deltaY,
            byte scrollMods) {
        try {
            listener.onScrollEvent(deltaX, deltaY, scrollMods & 0xFF);
        } catch (Throwable t) {
            LOG.log(Logger.Level.ERROR, "scroll event listener failed", t);
        }
    }

    /** Upcall trampoline invoked directly by native code; see KEY_EVENT_TRAMPOLINE. */
    @SuppressWarnings("unused")
    private static void dispatchKeyEvent(
            CpmTerminalHost.KeyEventListener listener,
            MemorySegment userdata,
            short keyCode,
            int modifierFlags,
            int isKeyDown,
            MemorySegment characters,
            long charactersLen,
            MemorySegment unshiftedCharacters,
            long unshiftedCharactersLen) {
        try {
            String text = decodeUtf8(characters, charactersLen);
            String unshiftedText = decodeUtf8(unshiftedCharacters, unshiftedCharactersLen);
            listener.onKeyEvent(keyCode & 0xFFFF, modifierFlags, isKeyDown != 0, text, unshiftedText);
        } catch (Throwable t) {
            LOG.log(Logger.Level.ERROR, "key event listener failed", t);
        }
    }

    private static String decodeUtf8(MemorySegment segment, long len) {
        if (len <= 0 || segment.equals(MemorySegment.NULL)) {
            return "";
        }
        MemorySegment bytes = segment.reinterpret(len);
        return new String(bytes.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    static final class HostNativeCallException extends RuntimeException {
        HostNativeCallException(String function, Throwable cause) {
            super("Native call to '" + function + "' failed", cause);
        }
    }
}
