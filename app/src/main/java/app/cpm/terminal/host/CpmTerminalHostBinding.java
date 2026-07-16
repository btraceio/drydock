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
import java.nio.charset.StandardCharsets;

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

    /** Java-side shape of {@code cpm_terminal_host_key_event_cb}. */
    @FunctionalInterface
    interface KeyEventListener {
        void onKeyEvent(int keyCode, int modifierFlags, boolean keyDown, String characters,
                        String unshiftedCharacters);
    }

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

    private static final MethodHandle KEY_EVENT_TRAMPOLINE;

    static {
        try {
            KEY_EVENT_TRAMPOLINE = MethodHandles.lookup().findStatic(
                CpmTerminalHostBinding.class,
                "dispatchKeyEvent",
                MethodType.methodType(
                    void.class,
                    KeyEventListener.class,
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
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Linker linker = Linker.nativeLinker();

    private final MethodHandle create;
    private final MethodHandle setFrame;
    private final MethodHandle contentView;
    private final MethodHandle setVisible;
    private final MethodHandle setFocused;
    private final MethodHandle destroy;
    private final MethodHandle setKeyEventCallback;

    CpmTerminalHostBinding(SymbolLookup lookup) {
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
    void setKeyEventCallback(MemorySegment host, KeyEventListener listener, Arena arena) {
        MethodHandle bound = MethodHandles.insertArguments(KEY_EVENT_TRAMPOLINE, 0, listener);
        MemorySegment stub = linker.upcallStub(bound, KEY_EVENT_CB_DESCRIPTOR, arena);
        try {
            setKeyEventCallback.invoke(host, stub, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new HostNativeCallException("cpm_terminal_host_set_key_event_callback", t);
        }
    }

    /** Upcall trampoline invoked directly by native code; see KEY_EVENT_TRAMPOLINE. */
    @SuppressWarnings("unused")
    private static void dispatchKeyEvent(
            KeyEventListener listener,
            MemorySegment userdata,
            short keyCode,
            int modifierFlags,
            int isKeyDown,
            MemorySegment characters,
            long charactersLen,
            MemorySegment unshiftedCharacters,
            long unshiftedCharactersLen) {
        String text = decodeUtf8(characters, charactersLen);
        String unshiftedText = decodeUtf8(unshiftedCharacters, unshiftedCharactersLen);
        listener.onKeyEvent(keyCode & 0xFFFF, modifierFlags, isKeyDown != 0, text, unshiftedText);
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
