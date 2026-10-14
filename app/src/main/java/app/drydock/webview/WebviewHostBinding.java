package app.drydock.webview;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * FFM binding for {@code libdrydockwebviewhost} (native-host/DrydockWebviewHost.h).
 * Counterpart of {@code app.drydock.terminal.host.DrydockTerminalHostBinding}.
 * Package-private: only {@link WebviewHost} (this package's facade) may hold
 * the upcall stubs and handles.
 *
 * <p>Every method here must be called on the JavaFX Application Thread (the
 * AppKit main thread on this setup); the facade ({@link WebviewHost}) enforces
 * that. The message-callback upcall swallows and logs every throwable --
 * an exception escaping an upcall stub terminates the JVM.</p>
 */
final class WebviewHostBinding {

    /** JS -> Java message: NUL-terminated UTF-8 body (owned by the shim, valid for the call only). */
    public interface MessageCallback {
        void onMessage(String body);
    }

    private static final Linker LINKER = Linker.nativeLinker();

    private final MethodHandle create;
    private final MethodHandle setFrame;
    private final MethodHandle setVisible;
    private final MethodHandle setFocused;
    private final MethodHandle loadUrl;
    private final MethodHandle postMessage;
    private final MethodHandle setMessageCallback;
    private final MethodHandle destroy;

    private WebviewHostBinding(SymbolLookup lookup) {
        create = downcall(lookup, "drydock_webview_host_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        setFrame = downcall(lookup, "drydock_webview_host_set_frame",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS,
                        ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                        ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE));
        setVisible = downcall(lookup, "drydock_webview_host_set_visible",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
        setFocused = downcall(lookup, "drydock_webview_host_set_focused",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
        loadUrl = downcall(lookup, "drydock_webview_host_load_url",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        postMessage = downcall(lookup, "drydock_webview_host_post_message",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        setMessageCallback = downcall(lookup, "drydock_webview_host_set_message_callback",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        destroy = downcall(lookup, "drydock_webview_host_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }

    /** Creates the binding over an already-loaded {@code libdrydockwebviewhost} lookup. */
    static WebviewHostBinding of(SymbolLookup lookup) {
        return new WebviewHostBinding(lookup);
    }

    MemorySegment create(MemorySegment parentNsView) {
        return (MemorySegment) invoke(create, parentNsView);
    }

    void setFrame(MemorySegment handle, double x, double y, double width, double height) {
        invoke(setFrame, handle, x, y, width, height);
    }

    void setVisible(MemorySegment handle, boolean visible) {
        invoke(setVisible, handle, visible);
    }

    void setFocused(MemorySegment handle, boolean focused) {
        invoke(setFocused, handle, focused);
    }

    void loadUrl(MemorySegment handle, String url) {
        try (Arena arena = Arena.ofConfined()) {
            invoke(loadUrl, handle, arena.allocateFrom(url));
        }
    }

    void postMessage(MemorySegment handle, String json) {
        try (Arena arena = Arena.ofConfined()) {
            invoke(postMessage, handle, arena.allocateFrom(json));
        }
    }

    /**
     * Registers the JS -> Java message callback (register-once; the upcall
     * stub is allocated from {@code arena}, which the facade closes only
     * after {@link #destroy} has NULLed the native callback).
     */
    void setMessageCallback(MemorySegment handle, MessageCallback callback, Arena arena) {
        // Upcall adapter: the stub hands us (userdata, body*, len); the body
        // pointer is only valid for the duration of the call, so it is copied
        // to a Java String here, inside the upcall. All throwables are
        // swallowed -- an exception escaping an upcall stub terminates the JVM.
        MethodHandle target;
        try {
            target = MethodHandles.lookup().findVirtual(WebviewHostBinding.class,
                    "messageUpcall",
                    MethodType.methodType(void.class,
                            MessageCallback.class, MemorySegment.class, MemorySegment.class, long.class))
                    .bindTo(this)
                    .bindTo(callback);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build message upcall", e);
        }
        MemorySegment stub = LINKER.upcallStub(target,
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                arena);
        invoke(setMessageCallback, handle, stub, MemorySegment.NULL);
    }

    void destroy(MemorySegment handle) {
        invoke(destroy, handle);
    }

    @SuppressWarnings("unused") // invoked reflectively as the upcall target
    private void messageUpcall(MessageCallback callback, MemorySegment userdata,
                               MemorySegment body, long length) {
        try {
            callback.onMessage(body.reinterpret(length + 1).getString(0));
        } catch (Throwable t) {
            System.getLogger(WebviewHostBinding.class.getName())
                    .log(System.Logger.Level.ERROR, "webview message upcall failed", t);
        }
    }

    private static Object invoke(MethodHandle mh, Object... args) {
        try {
            return mh.invokeWithArguments(args);
        } catch (Throwable t) {
            if (t instanceof RuntimeException rt) {
                throw rt;
            }
            if (t instanceof Error e) {
                throw e;
            }
            throw new IllegalStateException("native call failed: " + mh, t);
        }
    }

    private static MethodHandle downcall(SymbolLookup lookup, String name,
                                         FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lookup.findOrThrow(name), descriptor);
    }
}
