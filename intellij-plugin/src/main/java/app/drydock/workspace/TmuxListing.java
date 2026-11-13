package app.drydock.workspace;

import app.drydock.process.ProcessRunner;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The parser for {@code tmux ls -F '#{session_name}'} run over the box ssh
 * seam: pure classification of one command's exit status and output into a
 * live drydock-session set, with tmux's own "no server" answers recognized
 * as the valid empty answer they are (workspace-persistent-sessions
 * investigation: one {@code tmux ls} per box is the exact liveness signal,
 * and "no server running" is a zero-session box, not an error).
 *
 * <p>Three outcomes, never conflated (INV-9/INV-20): exit 0 → the set of
 * drydock session names; exit 1 with either no-server form in stderr → the
 * <em>empty</em> set; anything else — other exit codes, unrecognized stderr,
 * or output beyond the size/line bounds — → {@link Unknown}, a typed failure
 * for the caller to log. Parsing is bounded ({@link #MAX_BYTES} bytes,
 * {@link #MAX_LINES} lines) so a hostile or runaway listing becomes a typed
 * refusal, never a parse of unbounded input.</p>
 */
public final class TmuxListing {

    /** Line bound for one listing (INV-20): a box never owns thousands of sessions. */
    public static final int MAX_LINES = 4096;

    /** Size bound for one listing (INV-20); the ssh run is additionally capped at the seam. */
    public static final int MAX_BYTES = 256 * 1024;

    /**
     * A drydock persistent-session name: {@code drydock-} plus a
     * {@link app.drydock.domain.ManagedSessionId ManagedSessionId} (36
     * lowercase hex digits and dashes, the UUID shape). Every other session
     * on the box — the user's own, other tools' — is ignored, not an error.
     */
    private static final Pattern DRYDOCK_SESSION = Pattern.compile("^drydock-[0-9a-f-]{36}$");

    /** The stderr text tmux prints when there is no server to list (both observed forms). */
    private static final String NO_SERVER = "no server running";
    private static final String CANNOT_CONNECT = "error connecting to";

    private TmuxListing() {
    }

    /** The outcome of classifying one {@code tmux ls} run. */
    public sealed interface Result {
    }

    /**
     * The live drydock session names. Empty is a <em>valid</em> answer (no
     * server, or a server with no drydock sessions); it is produced only by
     * exit 0 or one of the two no-server forms, never by a failure.
     */
    public record Sessions(Set<String> names) implements Result {
    }

    /** The run could not be interpreted: a typed failure carrying the reason, never an empty set. */
    public record Unknown(String reason) implements Result {
    }

    /**
     * Classifies one {@code tmux ls} run. Exit 0 parses the bounded listing
     * and keeps only drydock session names; exit 1 with either no-server
     * form in the stderr is the valid empty answer; anything else is
     * {@link Unknown} with the stderr (or stdout) excerpt as its reason.
     */
    public static Result parse(int exitCode, String stdout, String stderr) {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        if (exitCode == 0) {
            if (byteLength(stdout) > MAX_BYTES) {
                return new Unknown("tmux ls output exceeded " + MAX_BYTES + " bytes");
            }
            if (byteLength(stderr) > MAX_BYTES) {
                return new Unknown("tmux ls stderr exceeded " + MAX_BYTES + " bytes");
            }
            String[] lines = lines(stdout);
            if (lines.length > MAX_LINES) {
                return new Unknown("tmux ls output exceeded " + MAX_LINES + " lines");
            }
            Set<String> names = new LinkedHashSet<>();
            for (String line : lines) {
                if (DRYDOCK_SESSION.matcher(line).matches()) {
                    names.add(line);
                }
            }
            return new Sessions(Set.copyOf(names));
        }
        if (exitCode == 1 && byteLength(stderr) <= MAX_BYTES
                && (stderr.contains(NO_SERVER) || stderr.contains(CANNOT_CONNECT))) {
            return new Sessions(Set.of());
        }
        String excerpt = ProcessRunner.excerpt(stderr.isBlank() ? stdout : stderr);
        return new Unknown("tmux ls failed (exit " + exitCode + "): " + excerpt);
    }

    /** Splits into lines, tolerating CRLF and one trailing newline; an empty string is no lines. */
    private static String[] lines(String stdout) {
        if (stdout.isEmpty()) {
            return new String[0];
        }
        String[] raw = stdout.split("\n", -1);
        int count = raw.length;
        if (count > 1 && raw[count - 1].isEmpty()) {
            count--;
        }
        String[] lines = new String[count];
        for (int i = 0; i < count; i++) {
            String line = raw[i];
            lines[i] = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        }
        return lines;
    }

    private static int byteLength(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
