package app.drydock.app;

/**
 * IntelliJ-port replacement for Drydock's login-shell PATH repair. The
 * original ran {@code $SHELL -lic} through {@code popen(3)} and wrote the
 * result back with {@code setenv(3)} via FFM downcalls, because a
 * Finder-launched JVM inherits launchd's bare PATH and Drydock owns its
 * children's environment.
 *
 * <p>None of that machinery belongs to a plugin: the plugin's children
 * (claude, gh, git) inherit the IDE process's environment, and IntelliJ
 * already repairs that PATH itself (it probes the user's shell for PATH
 * additions -- the same {@code -lic} trick -- for terminal and external-tool
 * PATHs). And {@code setenv(3)} is useless here for a second reason: the
 * IDE's JDK has long since snapshotted its environment, and
 * {@code ProcessBuilder} children inherit the snapshot, not {@code getenv(3)}.
 *
 * <p>FFM is also a hard blocker on the target runtime: FFM is preview on
 * Java 21 (JBR's floor for IntelliJ 2025.x) and only final from Java 22, so
 * the downcall version cannot load in a plugin without {@code --enable-preview}.
 *
 * <p>{@link #currentRealPath()} keeps the original name and contract ("the
 * PATH a session child will actually inherit") for the four call sites that
 * locate {@code claude}/{@code gh}/{@code git}. This core source set cannot
 * depend on IntelliJ APIs, so tool-specific callers remain responsible for
 * adding any known fallback locations their wrappers need.
 */
public final class LoginShellEnvironment {

    private LoginShellEnvironment() {
    }

    /**
     * The PATH inherited by {@code ProcessBuilder} children of the IDE JVM.
     */
    public static String currentRealPath() {
        return System.getenv("PATH");
    }

    /**
     * No-op in the plugin: the IDE owns its environment; a {@code setenv(3)}
     * after the JDK snapshot would not reach {@code ProcessBuilder} children
     * anyway. Kept so any future code ported from Drydock's startup compiles.
     */
    public static void mergeLoginShellPath() {
    }
}
