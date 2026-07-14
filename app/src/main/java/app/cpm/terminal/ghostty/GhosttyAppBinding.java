package app.cpm.terminal.ghostty;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * Hand-written FFM bindings for the slice of the libghostty C API needed to
 * create an app + one surface and drive it (Gate 0C, plan section 7/28
 * "Task 5"): {@code ghostty_app_new/free/tick/set_focus},
 * {@code ghostty_surface_config_new/new/free/set_size/set_focus/draw/text/key}.
 *
 * <p>Kept as a separate file from {@link GhosttyBinding} (Gate 0B's
 * init/info/config-only bindings) rather than merged into it, so each
 * task's additions stay individually reviewable; both are equally
 * hand-written (there is no generated/handwritten split yet -- see plan
 * rule 27.17 and {@link GhosttyBinding}'s class Javadoc).</p>
 *
 * <p>Struct layouts below were NOT hand-computed from the header by eye;
 * each was cross-checked with a throwaway {@code sizeof()}/{@code
 * _Alignof()} C program compiled against the pinned
 * {@code build/native/include/ghostty.h} (see docs/native-integration.md,
 * "Struct layout verification"), to avoid silently-wrong padding
 * assumptions (plan rule 27.6: do not invent APIs/ABI details).</p>
 *
 * <p>Part of the narrow native boundary (plan section 2.4/4.2).</p>
 */
final class GhosttyAppBinding {

    // ghostty_target_s { tag: i32; pad: i32; target: union { surface: *anyopaque } }
    // Verified: sizeof == 16, alignof == 8.
    static final StructLayout TARGET_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("tag"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("target")
    ).withName("ghostty_target_s");

    // ghostty_action_s { tag: i32; pad: i32; action: <union, largest member 24 bytes> }
    // Verified: sizeof == 32, alignof == 8. Fields are intentionally left
    // unnamed/opaque: this binding never reads action payloads (see
    // GhosttyApp.actionCallback, which always returns false without
    // inspecting the union) -- only the overall size/alignment matters for
    // correct upcall argument marshalling.
    static final StructLayout ACTION_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_LONG
    ).withName("ghostty_action_s");

    // ghostty_runtime_config_s: userdata, bool + pad, then 6 function pointers.
    // Verified: sizeof == 64, alignof == 8.
    static final StructLayout RUNTIME_CONFIG_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.ADDRESS.withName("userdata"),
        ValueLayout.JAVA_BOOLEAN.withName("supports_selection_clipboard"),
        MemoryLayout.paddingLayout(7),
        ValueLayout.ADDRESS.withName("wakeup_cb"),
        ValueLayout.ADDRESS.withName("action_cb"),
        ValueLayout.ADDRESS.withName("read_clipboard_cb"),
        ValueLayout.ADDRESS.withName("confirm_read_clipboard_cb"),
        ValueLayout.ADDRESS.withName("write_clipboard_cb"),
        ValueLayout.ADDRESS.withName("close_surface_cb")
    ).withName("ghostty_runtime_config_s");

    // ghostty_surface_config_s. Verified: sizeof == 88, alignof == 8.
    static final StructLayout SURFACE_CONFIG_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("platform_tag"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("platform_nsview"), // union: only macos.nsview used
        ValueLayout.ADDRESS.withName("userdata"),
        ValueLayout.JAVA_DOUBLE.withName("scale_factor"),
        ValueLayout.JAVA_FLOAT.withName("font_size"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("working_directory"),
        ValueLayout.ADDRESS.withName("command"),
        ValueLayout.ADDRESS.withName("env_vars"),
        ValueLayout.JAVA_LONG.withName("env_var_count"),
        ValueLayout.ADDRESS.withName("initial_input"),
        ValueLayout.JAVA_BOOLEAN.withName("wait_after_command"),
        MemoryLayout.paddingLayout(3),
        ValueLayout.JAVA_INT.withName("context")
    ).withName("ghostty_surface_config_s");

    // ghostty_input_key_s. Verified: sizeof == 32, alignof == 8.
    static final StructLayout INPUT_KEY_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("action"),
        ValueLayout.JAVA_INT.withName("mods"),
        ValueLayout.JAVA_INT.withName("consumed_mods"),
        ValueLayout.JAVA_INT.withName("keycode"),
        ValueLayout.ADDRESS.withName("text"),
        ValueLayout.JAVA_INT.withName("unshifted_codepoint"),
        ValueLayout.JAVA_BOOLEAN.withName("composing"),
        MemoryLayout.paddingLayout(3)
    ).withName("ghostty_input_key_s");

    // Upcall descriptors, transcribed field-by-field from the typedefs in
    // ghostty.h (see the header comment above each function pointer field
    // in ghostty_runtime_config_s for the exact C signature).
    static final FunctionDescriptor WAKEUP_CB_DESCRIPTOR =
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
    static final FunctionDescriptor ACTION_CB_DESCRIPTOR = FunctionDescriptor.of(
        ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, TARGET_LAYOUT, ACTION_LAYOUT);
    static final FunctionDescriptor READ_CLIPBOARD_CB_DESCRIPTOR = FunctionDescriptor.of(
        ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
    static final FunctionDescriptor CONFIRM_READ_CLIPBOARD_CB_DESCRIPTOR = FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
    static final FunctionDescriptor WRITE_CLIPBOARD_CB_DESCRIPTOR = FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_BOOLEAN);
    static final FunctionDescriptor CLOSE_SURFACE_CB_DESCRIPTOR =
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN);

    private final Linker linker = Linker.nativeLinker();

    final MethodHandle appNew;
    final MethodHandle appFree;
    final MethodHandle appTick;
    final MethodHandle appSetFocus;
    final MethodHandle surfaceConfigNew;
    final MethodHandle surfaceNew;
    final MethodHandle surfaceFree;
    final MethodHandle surfaceSetSize;
    final MethodHandle surfaceSetFocus;
    final MethodHandle surfaceDraw;
    final MethodHandle surfaceRefresh;
    final MethodHandle surfaceText;
    final MethodHandle surfaceKey;
    final MethodHandle surfaceProcessExited;

    GhosttyAppBinding(SymbolLookup lookup) {
        // ghostty_app_t ghostty_app_new(const ghostty_runtime_config_s*, ghostty_config_t);
        this.appNew = linker.downcallHandle(
            find(lookup, "ghostty_app_new"),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );
        // void ghostty_app_free(ghostty_app_t);
        this.appFree = linker.downcallHandle(
            find(lookup, "ghostty_app_free"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );
        // void ghostty_app_tick(ghostty_app_t);
        this.appTick = linker.downcallHandle(
            find(lookup, "ghostty_app_tick"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );
        // void ghostty_app_set_focus(ghostty_app_t, bool);
        this.appSetFocus = linker.downcallHandle(
            find(lookup, "ghostty_app_set_focus"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN)
        );
        // ghostty_surface_config_s ghostty_surface_config_new();
        this.surfaceConfigNew = linker.downcallHandle(
            find(lookup, "ghostty_surface_config_new"),
            FunctionDescriptor.of(SURFACE_CONFIG_LAYOUT)
        );
        // ghostty_surface_t ghostty_surface_new(ghostty_app_t, const ghostty_surface_config_s*);
        this.surfaceNew = linker.downcallHandle(
            find(lookup, "ghostty_surface_new"),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );
        // void ghostty_surface_free(ghostty_surface_t);
        this.surfaceFree = linker.downcallHandle(
            find(lookup, "ghostty_surface_free"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );
        // void ghostty_surface_set_size(ghostty_surface_t, uint32_t, uint32_t);
        this.surfaceSetSize = linker.downcallHandle(
            find(lookup, "ghostty_surface_set_size"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        );
        // void ghostty_surface_set_focus(ghostty_surface_t, bool);
        this.surfaceSetFocus = linker.downcallHandle(
            find(lookup, "ghostty_surface_set_focus"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN)
        );
        // void ghostty_surface_draw(ghostty_surface_t);
        this.surfaceDraw = linker.downcallHandle(
            find(lookup, "ghostty_surface_draw"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );
        // void ghostty_surface_refresh(ghostty_surface_t);
        this.surfaceRefresh = linker.downcallHandle(
            find(lookup, "ghostty_surface_refresh"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );
        // void ghostty_surface_text(ghostty_surface_t, const char*, uintptr_t);
        this.surfaceText = linker.downcallHandle(
            find(lookup, "ghostty_surface_text"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
        );
        // bool ghostty_surface_key(ghostty_surface_t, ghostty_input_key_s);
        this.surfaceKey = linker.downcallHandle(
            find(lookup, "ghostty_surface_key"),
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, INPUT_KEY_LAYOUT)
        );
        // bool ghostty_surface_process_exited(ghostty_surface_t);
        this.surfaceProcessExited = linker.downcallHandle(
            find(lookup, "ghostty_surface_process_exited"),
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS)
        );
    }

    private static MemorySegment find(SymbolLookup lookup, String name) {
        return lookup.find(name)
            .orElseThrow(() -> new IllegalStateException(
                "Symbol '" + name + "' not found in libghostty. Re-check "
                    + "build/native/include/ghostty.h for an API change."));
    }

    Linker linker() {
        return linker;
    }

    static MemorySegment allocateCString(java.lang.foreign.Arena arena, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        MemorySegment segment = arena.allocate(bytes.length + 1L);
        MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.length);
        segment.set(ValueLayout.JAVA_BYTE, bytes.length, (byte) 0);
        return segment;
    }

}
