package app.drydock.workspace;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TmuxListingTest {

    private static final String UUID_A = "3f1a2b3c-1234-4d5e-9f0a-bcdef0123456";
    private static final String UUID_B = "00112233-4455-6677-8899-aabbccddeeff";

    @Test
    void drydockNamesAreKeptAndNonDrydockSessionsIgnored() {
        TmuxListing.Result result = TmuxListing.parse(0,
                "main\r\npair\ndrydock-" + UUID_A + "\ndrydock-" + UUID_B + "\nother\n", "");
        TmuxListing.Sessions sessions = assertInstanceOf(TmuxListing.Sessions.class, result);
        assertEquals(Set.of("drydock-" + UUID_A, "drydock-" + UUID_B), sessions.names());
    }

    @Test
    void nearMissNamesAreIgnoredNotKept() {
        // The regex is the whole contract: anything that is not exactly
        // drydock-<36 lowercase-hex-or-dash> is another session, not ours.
        TmuxListing.Result result = TmuxListing.parse(0, String.join("\n",
                "drydock-" + UUID_A,
                "drydock-short",
                "DRYDOCK-" + UUID_B,
                "drydock-" + UUID_A + "x",
                "drydock-" + UUID_A.substring(0, 20),
                ""), "");
        TmuxListing.Sessions sessions = assertInstanceOf(TmuxListing.Sessions.class, result);
        assertEquals(Set.of("drydock-" + UUID_A), sessions.names());
    }

    @Test
    void bothNoServerFormsAreTheValidEmptyAnswer() {
        // A box with no tmux server has zero sessions — a valid answer, never
        // an error (INV-9): both observed stderr forms decode to the empty set.
        List<String> noServerForms = List.of(
                "no server running on /tmp/tmux-501/default",
                "error connecting to /tmp/tmux-501/default: No such file or directory");
        for (String stderr : noServerForms) {
            TmuxListing.Result result = TmuxListing.parse(1, "", stderr);
            TmuxListing.Sessions sessions = assertInstanceOf(TmuxListing.Sessions.class, result, stderr);
            assertEquals(Set.of(), sessions.names(), stderr);
        }
    }

    @Test
    void garbageAndOtherFailuresAreUnknownNeverAnEmptySet() {
        List<String> failures = List.of(
                "lost connection while listing",
                "some unrecognized tmux error",
                "");
        for (String stderr : failures) {
            TmuxListing.Result result = TmuxListing.parse(1, "", stderr);
            TmuxListing.Unknown unknown = assertInstanceOf(TmuxListing.Unknown.class, result, stderr);
            assertTrue(unknown.reason().contains("exit 1"), unknown.reason());
        }
        TmuxListing.Result crashed = TmuxListing.parse(2, "", "internal error");
        TmuxListing.Unknown unknown = assertInstanceOf(TmuxListing.Unknown.class, crashed);
        assertTrue(unknown.reason().contains("internal error"), unknown.reason());

        // ssh's own 255 reaches the parser as "anything else" too.
        TmuxListing.Result unreachable = TmuxListing.parse(255, "", "ssh: connect timed out");
        assertInstanceOf(TmuxListing.Unknown.class, unreachable);

        // A failure with stdout only still carries an excerpt.
        TmuxListing.Result stdoutOnly = TmuxListing.parse(3, "partial table", "");
        TmuxListing.Unknown fromStdout = assertInstanceOf(TmuxListing.Unknown.class, stdoutOnly);
        assertTrue(fromStdout.reason().contains("partial table"), fromStdout.reason());
    }

    @Test
    void aListingBeyondTheBoundsIsRefusedNotParsed() {
        // 4096 lines is the last acceptable size; one more is a typed refusal.
        TmuxListing.Result atLineBound = TmuxListing.parse(0, "x\n".repeat(4096), "");
        assertInstanceOf(TmuxListing.Sessions.class, atLineBound);
        TmuxListing.Result overLines = TmuxListing.parse(0, "x\n".repeat(4097), "");
        TmuxListing.Unknown tooManyLines = assertInstanceOf(TmuxListing.Unknown.class, overLines);
        assertTrue(tooManyLines.reason().contains("4096"), tooManyLines.reason());

        // The byte bound sits on top of the line bound; exactly at it parses.
        TmuxListing.Result atByteBound = TmuxListing.parse(0, "a".repeat(TmuxListing.MAX_BYTES), "");
        assertInstanceOf(TmuxListing.Sessions.class, atByteBound);
        TmuxListing.Result overBytes = TmuxListing.parse(0, "a".repeat(TmuxListing.MAX_BYTES + 1), "");
        TmuxListing.Unknown tooManyBytes = assertInstanceOf(TmuxListing.Unknown.class, overBytes);
        assertTrue(tooManyBytes.reason().contains("bytes"), tooManyBytes.reason());

        // The stderr bound refuses even a would-be no-server answer beyond it.
        TmuxListing.Result hugeStderr = TmuxListing.parse(1, "",
                "no server running " + "e".repeat(TmuxListing.MAX_BYTES));
        assertInstanceOf(TmuxListing.Unknown.class, hugeStderr);
    }

    @Test
    void anEmptyExitZeroListingIsTheValidEmptyAnswer() {
        TmuxListing.Result result = TmuxListing.parse(0, "", "");
        TmuxListing.Sessions sessions = assertInstanceOf(TmuxListing.Sessions.class, result);
        assertEquals(Set.of(), sessions.names());
    }
}
