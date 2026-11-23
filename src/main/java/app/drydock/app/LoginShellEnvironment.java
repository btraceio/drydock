package app.drydock.app;

/**
 * IntelliJ-port replacement for Drydock's login-shell PATH repair. The
 * original ran {@code $SHELL -lic} through {@code popen(3)} and wrote the
 * result back with {@code setenv(3)} via FFM downcalls, because a
 * Finder-launched JVM inherits launchd's bare PATH and Drydock owns its
 * children's environment.
 *
 * <p>None of that machinery belongs to a plugin. IntelliJ probes the user's
 * shell itself (the same {@code -lic} trick), but it keeps the result in its
 * own environment map, which its terminal and external tools use: the JVM's
 * {@code System.getenv("PATH")}, which every {@code ProcessBuilder} child
 * inherits, stays launchd's bare PATH. So the plugin hands the probed PATH
 * here ({@link #useShellPath}), and {@code ProcessRunner} gives it to its
 * children. {@code setenv(3)} would not help: the IDE's JDK has long since
 * snapshotted its environment, and children inherit the snapshot.
 *
 * <p>FFM is also a hard blocker on the target runtime: FFM is preview on
 * Java 21 (JBR's floor for IntelliJ 2025.x) and only final from Java 22, so
 * the downcall version cannot load in a plugin without {@code --enable-preview}.
 *
 * <p>{@link #currentRealPath()} keeps the original name and contract ("the
 * PATH a session child will actually inherit") for the call sites that
 * locate {@code claude}/{@code gh}/{@code git}. This core source set cannot
 * depend on IntelliJ APIs, so tool-specific callers remain responsible for
 * adding any known fallback locations their wrappers need.
 */
public final class LoginShellEnvironment {

    private LoginShellEnvironment() {
    }

    /** The login shell's PATH, once the plugin has handed it over; null until then. */
    private static volatile String shellPath;

    /**
     * The PATH a child should run with: the login shell's, once known
     * ({@link #useShellPath}), else the JVM's own.
     */
    public static String currentRealPath() {
        String shell = shellPath;
        return shell != null ? shell : System.getenv("PATH");
    }

    /** Hands over the login shell's PATH; a null or blank one changes nothing. Any thread. */
    public static void useShellPath(String path) {
        if (path != null && !path.isBlank()) {
            shellPath = path;
        }
    }

    /**
     * No-op in the plugin: the IDE owns its environment; a {@code setenv(3)}
     * after the JDK snapshot would not reach {@code ProcessBuilder} children
     * anyway. Kept so any future code ported from Drydock's startup compiles.
     */
    public static void mergeLoginShellPath() {
    }
}
