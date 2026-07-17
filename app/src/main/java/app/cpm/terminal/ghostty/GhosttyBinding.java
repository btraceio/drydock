package app.cpm.terminal.ghostty;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * Hand-written Foreign Function &amp; Memory (FFM) bindings for the smallest
 * slice of the public libghostty C API needed for Gate 0B (the FFM smoke
 * test): {@code ghostty_init}, {@code ghostty_info}, and
 * {@code ghostty_config_new}/{@code ghostty_config_free}.
 *
 * <p>Hand-written rather than generated (e.g. via {@code jextract}): the
 * plan asks for "the smallest FFM binding needed to initialize libghostty",
 * and {@code ghostty.h} exposes a very large surface (app/surface/config/
 * input handling) that later tasks (Gate 0C onward) will bind incrementally
 * as each part is actually needed. Per plan rule 27.17 ("keep generated FFM
 * sources separate from handwritten code"): if/when a later task does
 * generate bindings (e.g. for the much larger surface-embedding API), those
 * generated sources must live in a separate source set/package from this
 * hand-written one, not be mixed into it.</p>
 *
 * <p>Every layout and signature here is transcribed directly from the
 * pinned {@code build/native/include/ghostty.h} (see
 * {@code build/generated/ghostty-version.properties} for the exact pinned
 * Ghostty commit) -- per plan rule 27.6, nothing here is invented.</p>
 *
 * <p>Part of the narrow native boundary package (plan section 2.4/4.2): no
 * code outside {@code app.cpm.terminal.ghostty} may reference {@link
 * MemorySegment}, {@link MethodHandle}, or {@link Linker} for libghostty.</p>
 */
final class GhosttyBinding {

    /**
     * Mirrors {@code ghostty_info_s} in {@code ghostty.h}:
     * <pre>{@code
     * typedef enum {
     *   GHOSTTY_BUILD_MODE_DEBUG,
     *   GHOSTTY_BUILD_MODE_RELEASE_SAFE,
     *   GHOSTTY_BUILD_MODE_RELEASE_FAST,
     *   GHOSTTY_BUILD_MODE_RELEASE_SMALL,
     * } ghostty_build_mode_e;
     *
     * typedef struct {
     *   ghostty_build_mode_e build_mode;
     *   const char* version;
     *   uintptr_t version_len;
     * } ghostty_info_s;
     * }</pre>
     * The enum is a plain C {@code int} (4 bytes); the following pointer
     * field is 8-byte aligned, so there are 4 bytes of padding between
     * {@code build_mode} and {@code version}.
     */
    static final StructLayout GHOSTTY_INFO_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("build_mode"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("version"),
        ValueLayout.JAVA_LONG.withName("version_len")
    ).withName("ghostty_info_s");

    private static final ValueLayout.OfInt BUILD_MODE_HANDLE_LAYOUT = ValueLayout.JAVA_INT;

    private final MethodHandle ghosttyInit;
    private final MethodHandle ghosttyInfo;
    private final MethodHandle ghosttyConfigNew;
    private final MethodHandle ghosttyConfigFree;
    private final MethodHandle ghosttyConfigLoadFile;
    private final MethodHandle ghosttyConfigFinalize;

    GhosttyBinding(java.lang.foreign.SymbolLookup lookup) {
        Linker linker = Linker.nativeLinker();

        // int ghostty_init(uintptr_t argc, char** argv);
        this.ghosttyInit = linker.downcallHandle(
            find(lookup, "ghostty_init"),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS
            )
        );

        // ghostty_info_s ghostty_info(void);
        // Struct-by-value return: the downcall handle takes a leading
        // SegmentAllocator argument that the Linker uses to materialize the
        // returned struct (register- or memory-based, per the platform C
        // ABI -- handled transparently by java.lang.foreign).
        this.ghosttyInfo = linker.downcallHandle(
            find(lookup, "ghostty_info"),
            FunctionDescriptor.of(GHOSTTY_INFO_LAYOUT)
        );

        // ghostty_config_t ghostty_config_new();
        this.ghosttyConfigNew = linker.downcallHandle(
            find(lookup, "ghostty_config_new"),
            FunctionDescriptor.of(ValueLayout.ADDRESS)
        );

        // void ghostty_config_free(ghostty_config_t);
        this.ghosttyConfigFree = linker.downcallHandle(
            find(lookup, "ghostty_config_free"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );

        // void ghostty_config_load_file(ghostty_config_t, const char*);
        this.ghosttyConfigLoadFile = linker.downcallHandle(
            find(lookup, "ghostty_config_load_file"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        // void ghostty_config_finalize(ghostty_config_t);
        this.ghosttyConfigFinalize = linker.downcallHandle(
            find(lookup, "ghostty_config_finalize"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );
    }

    private static MemorySegment find(java.lang.foreign.SymbolLookup lookup, String name) {
        return lookup.find(name)
            .orElseThrow(() -> new IllegalStateException(
                "Symbol '" + name + "' not found in libghostty. This usually means the pinned "
                    + "Ghostty commit or the build (scripts/build-ghostty.sh) changed the public "
                    + "API surface -- re-check build/native/include/ghostty.h."));
    }

    /**
     * Calls {@code ghostty_init(0, NULL)} (no CLI args passed through).
     *
     * @return the raw return code; {@code 0} ({@code GHOSTTY_SUCCESS}) means
     *     success.
     */
    int init() {
        try {
            return (int) ghosttyInit.invoke(0L, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new GhosttyNativeCallException("ghostty_init", t);
        }
    }

    /**
     * Calls {@code ghostty_info()} and returns a Java-side copy of its
     * fields (build mode ordinal + decoded version string). Uses the given
     * allocator only for the transient native struct; the returned {@link
     * Info} record owns no native memory.
     */
    Info info(SegmentAllocator allocator) {
        try {
            MemorySegment struct = (MemorySegment) ghosttyInfo.invoke(allocator);
            int buildMode = struct.get(BUILD_MODE_HANDLE_LAYOUT, 0);
            MemorySegment versionPtr = struct.get(ValueLayout.ADDRESS, 8);
            long versionLen = struct.get(ValueLayout.JAVA_LONG, 16);
            String version = "";
            if (versionLen > 0 && !versionPtr.equals(MemorySegment.NULL)) {
                MemorySegment versionBytes = versionPtr.reinterpret(versionLen);
                byte[] bytes = versionBytes.toArray(ValueLayout.JAVA_BYTE);
                version = new String(bytes, StandardCharsets.UTF_8);
            }
            return new Info(buildMode, version);
        } catch (Throwable t) {
            throw new GhosttyNativeCallException("ghostty_info", t);
        }
    }

    /**
     * Calls {@code ghostty_config_new()}. The caller owns the returned
     * handle and must eventually pass it to {@link #configFree(MemorySegment)}.
     */
    MemorySegment configNew() {
        try {
            return (MemorySegment) ghosttyConfigNew.invoke();
        } catch (Throwable t) {
            throw new GhosttyNativeCallException("ghostty_config_new", t);
        }
    }

    /**
     * Calls {@code ghostty_config_load_file(config, path)}. The path string
     * is copied into a transient confined arena for the duration of the call.
     */
    void configLoadFile(MemorySegment config, String path) {
        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            ghosttyConfigLoadFile.invoke(config, arena.allocateFrom(path));
        } catch (Throwable t) {
            throw new GhosttyNativeCallException("ghostty_config_load_file", t);
        }
    }

    /** Calls {@code ghostty_config_finalize(config)} (must run after all loads, before use). */
    void configFinalize(MemorySegment config) {
        try {
            ghosttyConfigFinalize.invoke(config);
        } catch (Throwable t) {
            throw new GhosttyNativeCallException("ghostty_config_finalize", t);
        }
    }

    /** Calls {@code ghostty_config_free(config)}. */
    void configFree(MemorySegment config) {
        try {
            ghosttyConfigFree.invoke(config);
        } catch (Throwable t) {
            throw new GhosttyNativeCallException("ghostty_config_free", t);
        }
    }

    /** Decoded copy of {@code ghostty_info_s}. */
    record Info(int buildModeOrdinal, String version) {
    }

    /** Wraps a {@link Throwable} from a native downcall in an unchecked exception. */
    static final class GhosttyNativeCallException extends RuntimeException {
        GhosttyNativeCallException(String function, Throwable cause) {
            super("Native call to '" + function + "' failed", cause);
        }
    }
}
