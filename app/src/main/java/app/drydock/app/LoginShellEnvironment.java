package app.drydock.app;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Repairs the process {@code PATH} when the application was launched from
 * Finder/the Dock. launchd starts apps with the bare system PATH
 * ({@code /usr/bin:/bin:/usr/sbin:/sbin}), so every child this process
 * spawns -- {@code claude} inside the embedded terminal (libghostty runs
 * the session command through a shell, inheriting this process's
 * environment), {@code gh}, anything a claude session itself runs -- would
 * miss user-installed tools. This asks the user's login shell for its PATH
 * and merges it into the real process environment with {@code setenv(3)}.
 *
 * <p>Everything here deliberately avoids the JDK's own process/environment
 * APIs: the JDK snapshots the environment ONCE (at class-init of its
 * internal {@code ProcessEnvironment}, triggered by the first
 * {@code System.getenv()} or {@code ProcessBuilder} use), and children
 * spawned via {@code ProcessBuilder} inherit that snapshot. The probe
 * therefore runs through {@code popen(3)} and reads the current value via
 * {@code getenv(3)}, so {@link #mergeLoginShellPath()} can finish its
 * {@code setenv(3)} before the snapshot is ever taken -- which is also why
 * it must be the first thing {@code Main.main} does.</p>
 *
 * <p>This is a macOS-only fix. The POSIX libc calls ({@code popen},
 * {@code setenv}) it depends on are not exposed by the JDK's
 * {@code defaultLookup} on Windows, and the underlying launchd-PATH
 * problem does not exist there. {@link #SUPPORTED} gates the class so
 * {@link #mergeLoginShellPath()} is a no-op (and the downcall handles
 * stay {@code null}) on non-macOS hosts -- including the Windows CI
 * runner that boots this class to verify the rest of the application
 * startup.</p>
 */
public final class LoginShellEnvironment {

    private static final Logger LOG = System.getLogger(LoginShellEnvironment.class.getName());

    /** How long the login shell gets to print its PATH before the app launches without the fix. */
    private static final long PROBE_TIMEOUT_MILLIS = 3000;

    private static final String MARKER_START = "__DRYDOCK_PATH__";
    private static final String MARKER_END = "__DRYDOCK_END__";

    /**
     * True only on hosts whose process environment the JDK exposes POSIX
     * libc symbols ({@code popen}, {@code setenv}, ...) through
     * {@code Linker.defaultLookup()} -- in practice, macOS (Linux is
     * untested by this class but would also satisfy the check; the macOS
     * check is the narrow one that matches this project's stated
     * supported platforms and keeps the gate honest). Read once at class
     * init; the downcall handles below are resolved only when this is
     * true, so the class can load on Windows without throwing.
     */
    private static final boolean SUPPORTED = isMacOs();

    private static boolean isMacOs() {
        String os = System.getProperty("os.name", "");
        return os.startsWith("Mac OS X") || os.startsWith("macOS");
    }

    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle POPEN = SUPPORTED ? downcall("popen",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)) : null;
    private static final MethodHandle PCLOSE = SUPPORTED ? downcall("pclose",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)) : null;
    private static final MethodHandle FGETS = SUPPORTED ? downcall("fgets",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS)) : null;
    private static final MethodHandle GETENV = SUPPORTED ? downcall("getenv",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)) : null;
    private static final MethodHandle SETENV = SUPPORTED ? downcall("setenv",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT)) : null;

    private LoginShellEnvironment() {
    }

    /**
     * Merges the login shell's PATH into this process's environment. Best
     * effort: a broken/slow shell startup logs a warning and the app
     * launches with whatever PATH it inherited. The probe runs on a daemon
     * thread so a login shell that hangs (e.g. a blocking zshrc) cannot hang
     * application startup past {@link #PROBE_TIMEOUT_MILLIS}. A no-op on
     * non-macOS hosts (see {@link #SUPPORTED}).
     *
     * <p>Two apply paths, both race-free against the JDK's one-time
     * environment snapshot (taken lazily on the first {@code System.getenv}
     * / {@code ProcessBuilder} use, which {@code Toolkit.getDefaultToolkit}
     * triggers moments after this returns):
     *
     * <p><b>Fast</b> (shell reports PATH within {@code PROBE_TIMEOUT_MILLIS}):
     * the merge is applied on this (main) thread, then the JDK snapshot is
     * forced on the same thread, so the snapshot captures the repaired PATH.
     * Both {@code ProcessBuilder} children (which inherit the snapshot) and
     * terminal children (ghostty's fork/exec, which inherit the real env)
     * see the repaired PATH.
     *
     * <p><b>Slow</b> (shell takes longer): main proceeds without waiting,
     * forces the JDK snapshot -- with the inherited, bare PATH -- and hands
     * the merge to the daemon. When the probe eventually completes the
     * daemon applies it to the real env with {@code setenv(3)}, strictly
     * after the snapshot (the handoff latch is released only once the
     * snapshot is done), so the daemon's {@code setenv} cannot race the
     * snapshot's {@code environ} read. Terminal-launched sessions (pi,
     * claude) read the real env and still receive the repaired PATH;
     * {@code ProcessBuilder}-spawned tools (git/gh/docker/ddtool probes)
     * keep the bare snapshot. Applying late rather than abandoning is what
     * keeps a slow zshrc from breaking every agent session: the sessions
     * launch through the terminal, not the JDK.
     */
    public static void mergeLoginShellPath() {
        if (!SUPPORTED) {
            return;
        }
        AtomicReference<String> probed = new AtomicReference<>();
        CountDownLatch probeDone = new CountDownLatch(1);
        CountDownLatch mainDecided = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                String loginPath = readLoginShellPath();
                if (loginPath != null) {
                    probed.set(loginPath);
                }
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "Login shell PATH probe failed; keeping the inherited PATH", t);
            } finally {
                probeDone.countDown();
            }
            // Wait for main to decide who applies. In the fast case main
            // applies itself and clears the flag; in the slow case main sets
            // it after forcing the snapshot. The await is what makes the
            // boundary (probe finishes right at the timeout) deterministic:
            // the merge is applied exactly once, never lost and never
            // doubled, and the daemon's setenv never overlaps the snapshot.
            try {
                mainDecided.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (workerShouldApply) {
                applyMerge(probed.get(), true);
            }
        }, "login-shell-path-probe");
        worker.setDaemon(true);
        worker.start();
        boolean fast;
        try {
            fast = probeDone.await(PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fast = false;
        }
        if (fast) {
            // Apply on this thread, then force the snapshot AFTER the setenv
            // so ProcessBuilder children capture the repaired PATH. Both
            // calls are on the main thread, so no concurrent getenv/setenv.
            applyMerge(probed.get(), false);
            System.getenv("PATH");
            workerShouldApply = false;
            mainDecided.countDown();
        } else {
            LOG.log(Level.WARNING, "Login shell did not report its PATH within "
                    + PROBE_TIMEOUT_MILLIS + "ms; the merge will be applied late. "
                    + "Terminal-launched sessions (pi, claude) still receive the repaired PATH; "
                    + "ProcessBuilder-spawned tools keep the inherited PATH.");
            // Force the snapshot BEFORE releasing the handoff, so the daemon's
            // later setenv cannot race the snapshot's environ read.
            System.getenv("PATH");
            workerShouldApply = true;
            mainDecided.countDown();
        }
    }

    /**
     * Set by main after it decides who applies the merge; read by the daemon
     * after {@code mainDecided} opens. The latch's happens-before makes the
     * volatile load safe without further sync.
     */
    private static volatile boolean workerShouldApply;

    /**
     * Merges {@code loginPath} into the real {@code PATH}. {@code late} only
     * changes the log line (the merge is equally correct either way; the
     * distinction is for diagnosing a slow shell). Null/blank {@code loginPath}
     * is a failed probe -- logged once on the fast path, silent on the late
     * path (the slow path already warned about the timeout).
     */
    private static void applyMerge(String loginPath, boolean late) {
        try {
            if (loginPath == null || loginPath.isBlank()) {
                if (!late) {
                    LOG.log(Level.WARNING, "Could not read the login shell PATH; keeping the inherited PATH");
                }
                return;
            }
            String current = getenv("PATH");
            LinkedHashSet<String> merged = new LinkedHashSet<>();
            for (String entry : loginPath.split(":")) {
                if (!entry.isBlank()) {
                    merged.add(entry);
                }
            }
            // Keep extras from the inherited PATH (a terminal launch may
            // carry per-session entries the fresh login shell lacks).
            if (current != null) {
                for (String entry : current.split(":")) {
                    if (!entry.isBlank()) {
                        merged.add(entry);
                    }
                }
            }
            String value = String.join(":", merged);
            if (!value.equals(current)) {
                setenv("PATH", value);
                LOG.log(Level.INFO, "PATH merged from the login shell{0}: {1}",
                        new Object[] { late ? " (applied late)" : "", value });
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Login shell PATH merge failed; keeping the inherited PATH", t);
        }
    }

    /**
     * The live value of {@code PATH} in this process's real environment, read
     * via {@code getenv(3)} -- not the JDK's one-time snapshot, which is
     * frozen at startup and does not reflect a merge applied after it. For
     * launch-command builders that must know whether a dependency is already
     * on the PATH a terminal-launched session will inherit.
     *
     * <p>Safe to call after startup, once the login-shell merge has settled
     * (fast or late); the merge worker's {@code setenv(3)} is done by the
     * time the user opens a session. Falls back to {@link System#getenv} on
     * non-macOS hosts.
     */
    public static String currentRealPath() {
        if (!SUPPORTED) {
            return System.getenv("PATH");
        }
        try {
            return getenv("PATH");
        } catch (Throwable t) {
            return System.getenv("PATH");
        }
    }

    /**
     * {@code $SHELL -lic 'printf ...'} via popen (falling back to {@code
     * /bin/zsh} when {@code SHELL} is unset -- the macOS default; a user
     * whose PATH lives in {@code .bashrc}/{@code config.fish} needs their
     * actual login shell probed, not zsh's config). Interactive AND login
     * ({@code -lic}, the same trick VS Code uses): PATH additions for user
     * tools typically live in {@code .zshrc}, which a non-interactive login
     * shell never sources -- probed with {@code -lc} alone, claude's own
     * {@code ~/.local/bin} was missing. The shell script is single-quoted so
     * popen's own {@code /bin/sh -c} wrapper cannot expand {@code $PATH}
     * with the pre-merge value; markers isolate the value from any output
     * the user's shell startup files print.
     */
    private static String readLoginShellPath() throws Throwable {
        String shell = getenv("SHELL");
        // The value is embedded double-quoted in a /bin/sh command line;
        // reject anything that could escape the quoting rather than trying
        // to quote arbitrary bytes.
        if (shell == null || shell.isBlank() || !shell.startsWith("/")
                || shell.chars().anyMatch(c -> c == '"' || c == '\\' || c == '$' || c == '`')) {
            shell = "/bin/zsh";
        }
        String command = "TERM=dumb \"" + shell + "\" -lic 'printf \"\\n" + MARKER_START + "%s" + MARKER_END + "\\n\" \"$PATH\"'";
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment file = (MemorySegment) POPEN.invoke(
                    arena.allocateFrom(command), arena.allocateFrom("r"));
            if (file.equals(MemorySegment.NULL)) {
                return null;
            }
            StringBuilder output = new StringBuilder();
            MemorySegment buffer = arena.allocate(4096);
            try {
                while (output.length() < 1_000_000) {
                    MemorySegment line = (MemorySegment) FGETS.invoke(buffer, 4096, file);
                    if (line.equals(MemorySegment.NULL)) {
                        break;
                    }
                    output.append(cString(buffer));
                }
            } finally {
                PCLOSE.invoke(file);
            }
            int start = output.lastIndexOf(MARKER_START);
            if (start < 0) {
                return null;
            }
            int end = output.indexOf(MARKER_END, start);
            if (end < 0) {
                return null;
            }
            return output.substring(start + MARKER_START.length(), end);
        }
    }

    private static String getenv(String name) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = (MemorySegment) GETENV.invoke(arena.allocateFrom(name));
            return value.equals(MemorySegment.NULL)
                    ? null
                    : cString(value.reinterpret(Long.MAX_VALUE));
        }
    }

    /**
     * Decodes the NUL-terminated C string at the start of {@code segment}.
     * Unlike {@link MemorySegment#getString}, malformed byte sequences are
     * replaced rather than thrown on: shell startup files can print
     * arbitrary non-UTF-8 bytes, and one bad line must not abort the whole
     * PATH merge.
     */
    private static String cString(MemorySegment segment) {
        long length = 0;
        while (length < segment.byteSize() && segment.get(ValueLayout.JAVA_BYTE, length) != 0) {
            length++;
        }
        byte[] bytes = new byte[(int) Math.min(length, Integer.MAX_VALUE)];
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, 0, bytes, 0, bytes.length);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            // Unreachable with REPLACE actions; kept because decode() declares it.
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void setenv(String name, String value) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            SETENV.invoke(arena.allocateFrom(name), arena.allocateFrom(value), 1);
        }
    }

    private static MethodHandle downcall(String symbol, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(
                LINKER.defaultLookup().find(symbol).orElseThrow(
                        () -> new IllegalStateException("libc symbol not found: " + symbol)),
                descriptor);
    }
}
