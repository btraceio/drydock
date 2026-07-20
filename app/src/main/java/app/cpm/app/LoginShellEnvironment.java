package app.cpm.app;

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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Repairs the process {@code PATH} when the application was launched from
 * Finder/the Dock. launchd starts apps with the bare system PATH
 * ({@code /usr/bin:/bin:/usr/sbin:/sbin}), so every child this process
 * spawns -- {@code claude} inside the embedded terminal (libghostty runs
 * the session command via {@code /bin/sh -c}, inheriting this process's
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
 */
public final class LoginShellEnvironment {

    private static final Logger LOG = System.getLogger(LoginShellEnvironment.class.getName());

    /** How long the login shell gets to print its PATH before the app launches without the fix. */
    private static final long PROBE_TIMEOUT_MILLIS = 3000;

    private static final String MARKER_START = "__CPM_PATH__";
    private static final String MARKER_END = "__CPM_END__";

    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle POPEN = downcall("popen",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle PCLOSE = downcall("pclose",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle FGETS = downcall("fgets",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle GETENV = downcall("getenv",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SETENV = downcall("setenv",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    private LoginShellEnvironment() {
    }

    /**
     * Merges the login shell's PATH into this process's environment. Best
     * effort: a broken/slow shell startup logs a warning and the app
     * launches with whatever PATH it inherited (correct for terminal
     * launches, bare for Finder launches). Runs the probe on a daemon
     * thread so a login shell that hangs (e.g. a blocking zshrc) cannot
     * hang application startup past {@link #PROBE_TIMEOUT_MILLIS}.
     */
    public static void mergeLoginShellPath() {
        // Once the timeout expires the application proceeds (and the JDK
        // may take its one-time environment snapshot at any moment); the
        // still-running daemon worker must then never call setenv(3) -- a
        // late mutation would race concurrent getenv(3) callers and diverge
        // from the JDK's snapshot. The flag is checked immediately before
        // the setenv call.
        AtomicBoolean abandoned = new AtomicBoolean();
        Thread worker = new Thread(() -> probeAndMerge(abandoned), "login-shell-path-probe");
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(PROBE_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            abandoned.set(true);
            LOG.log(Level.WARNING, "Login shell did not report its PATH within "
                    + PROBE_TIMEOUT_MILLIS + "ms; child processes keep the inherited PATH");
        }
    }

    private static void probeAndMerge(AtomicBoolean abandoned) {
        try {
            String loginPath = readLoginShellPath();
            if (loginPath == null || loginPath.isBlank()) {
                LOG.log(Level.WARNING, "Could not read the login shell PATH; keeping the inherited PATH");
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
                if (abandoned.get()) {
                    LOG.log(Level.WARNING,
                            "Login shell PATH arrived after the startup timeout; leaving the environment untouched");
                    return;
                }
                setenv("PATH", value);
                LOG.log(Level.INFO, "PATH merged from the login shell: {0}", value);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Login shell PATH merge failed; keeping the inherited PATH", t);
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
