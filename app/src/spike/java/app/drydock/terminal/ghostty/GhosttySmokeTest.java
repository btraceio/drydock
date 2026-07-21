package app.drydock.terminal.ghostty;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;

/**
 * Command-line smoke test for Gate 0B of the implementation plan
 * (section 7): loads libghostty via FFM, calls a harmless
 * initialization/version/config function, validates the return value, and
 * shuts down cleanly.
 *
 * <p>Run via {@code ./gradlew :app:ffmSmokeTest} (or the root alias
 * {@code ./gradlew ffmSmokeTest}).</p>
 *
 * <p>This class is the one place outside {@link GhosttyBinding}/{@link
 * GhosttyNativeLibrary} that is allowed to hold a {@link MemorySegment}
 * (the opaque {@code ghostty_config_t} handle) -- it still lives inside the
 * narrow native boundary package ({@code app.drydock.terminal.ghostty}), it is
 * simply the package's own command-line entry point rather than internal
 * plumbing.</p>
 */
public final class GhosttySmokeTest {

    private GhosttySmokeTest() {
    }

    public static void main(String[] args) {
        System.out.println("== Ghostty FFM smoke test (Gate 0B) ==");
        System.out.println("os.arch: " + System.getProperty("os.arch"));

        SymbolLookup lookup = GhosttyNativeLibrary.lookup();
        GhosttyBinding binding = GhosttyBinding.of(lookup);

        int initResult = binding.init();
        System.out.println("ghostty_init() -> " + initResult);
        if (initResult != 0) {
            throw new IllegalStateException(
                "ghostty_init() returned " + initResult + ", expected 0 (GHOSTTY_SUCCESS)");
        }

        try (Arena arena = Arena.ofConfined()) {
            GhosttyBinding.Info info = binding.info(arena);
            System.out.println("ghostty_info() -> build_mode=" + info.buildModeOrdinal()
                + " version=\"" + info.version() + "\"");
            if (info.version().isBlank()) {
                throw new IllegalStateException("ghostty_info() returned a blank version string");
            }
        }

        MemorySegment config = binding.configNew();
        System.out.println("ghostty_config_new() -> " + config);
        if (config.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("ghostty_config_new() returned NULL");
        }

        binding.configFree(config);
        System.out.println("ghostty_config_free() -> ok");

        System.out.println("== Ghostty FFM smoke test PASSED ==");
    }
}
