package app.drydock.testing;

import java.nio.file.Path;

/**
 * Test-side path constants that survive the domain's absolute-path contract
 * on every OS. The domain types ({@code Repository}, {@code
 * SessionWorkspace}, …) reject non-absolute paths, and on Windows {@code
 * Path.of("/repo")} is NOT absolute -- it has no root, so the constructor
 * throws and every test built on such a constant fails. Windows-only
 * regressions like that never showed up while the suite ran on macOS only
 * (see the windows-test-trial job in .github/workflows/tests.yml).
 *
 * <p>{@link #abs} is the identity on unix -- {@code Path.of("/repo")} is
 * already absolute -- so it cannot change what a macOS test asserts. On
 * Windows it anchors the same name at the current drive root, keeping the
 * path absolute and the test's meaning (an opaque absolute location)
 * intact.
 */
public final class TestPaths {

    private TestPaths() { }

    /**
     * Maps a unix-style absolute test path ({@code "/repo"}, {@code
     * "/tmp/x/wt"}) to an absolute path on the running OS.
     */
    public static Path abs(String unixAbsolute) {
        Path path = Path.of(unixAbsolute);
        if (path.isAbsolute()) {
            return path;
        }
        // Drop the leading '/' before resolving against the drive root,
        // otherwise resolve() treats the operand as rooted and returns it
        // unchanged on Windows.
        return Path.of("/").toAbsolutePath().resolve(unixAbsolute.substring(1)).normalize();
    }
}
