package app.drydock.lsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one real-process contract test of the tier (spec section 9): the
 * process class's own behavior — cat echo round-trip, spawn failure,
 * stdin-EOF exit, bounded forced kill of a child that ignores EOF,
 * idempotent and interruption-safe close — pinned with local binaries
 * only, never a language server.
 */
class LspServerProcessTest {

    /** Regression ceiling: a wedged close must fail the test, not hang the suite. */
    private static final long CLOSE_BOUND_MILLIS = 10_000;

    @Test
    @Timeout(30)
    void catEchoesBytesBackAndExitsCleanlyOnStdinEof() throws Exception {
        LspServerProcess server = LspServerProcess.start(List.of("/bin/cat"), null);
        try {
            byte[] payload = "Content-Length: 7\r\n\r\n{\"id\":1}".getBytes(StandardCharsets.UTF_8);
            server.stdin().write(payload);
            server.stdin().flush();

            byte[] echoed = new byte[payload.length];
            int read = 0;
            while (read < echoed.length) {
                int n = server.stdout().read(echoed, read, echoed.length - read);
                assertTrue(n > 0, "cat must not close stdout while stdin is open");
                read += n;
            }
            assertArrayEquals(payload, echoed);

            // stdin EOF is the graceful exit signal: cat quits, its stdout
            // pipe delivers EOF, and the exit future carries the code.
            server.stdin().close();
            assertEquals(-1, server.stdout().read(), "child death must surface as EOF on stdout");
            assertEquals(0, server.exitFuture().get(5, TimeUnit.SECONDS));
        } finally {
            server.close();
        }
    }

    @Test
    @Timeout(30)
    void spawnFailureThrowsNamingTheExecutable() {
        List<String> command = List.of("/no/such/drydock-lsp-test-executable");

        IOException e = assertThrows(IOException.class,
                () -> LspServerProcess.start(command, null));

        assertEquals(LspServerProcess.LaunchException.class, e.getClass());
        assertTrue(e.getMessage().contains("/no/such/drydock-lsp-test-executable"),
                "spawn failure must name the command: " + e.getMessage());
    }

    @Test
    @Timeout(30)
    void closeOfAChildThatIgnoresStdinEofIsBoundedAndForced() throws Exception {
        // `sleep` never reads stdin, so closing it changes nothing: only
        // destroyForcibly() can end it. Grace (1 s) + kill join (2 s) +
        // drainer join (2 s) is the internal worst case; the test ceiling
        // leaves margin for a slow machine without hiding a regression.
        LspServerProcess server = LspServerProcess.start(List.of("/bin/sleep", "600"), null);

        long start = System.nanoTime();
        server.close();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < CLOSE_BOUND_MILLIS,
                "forced close must be bounded, took " + elapsedMillis + " ms");
        // The child was actually released, not merely abandoned at the bound.
        int exit = server.exitFuture().get(5, TimeUnit.SECONDS);
        assertTrue(exit != 0, "a force-killed child reports a non-zero code, got " + exit);
    }

    @Test
    @Timeout(30)
    void closeIsIdempotentAndFastWhenAlreadyClosed() throws Exception {
        LspServerProcess server = LspServerProcess.start(List.of("/bin/cat"), null);

        long start = System.nanoTime();
        server.close();
        server.close();
        server.close();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < CLOSE_BOUND_MILLIS,
                "repeated close must not re-wait the bounds, took " + elapsedMillis + " ms");
        assertEquals(0, server.exitFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(30)
    void interruptedCloseRestoresTheFlagAndStillReleasesTheChild() throws Exception {
        LspServerProcess server = LspServerProcess.start(List.of("/bin/sleep", "600"), null);
        Throwable[] failure = new Throwable[1];
        Thread closer = new Thread(() -> {
            try {
                server.close();
            } catch (Throwable t) {
                failure[0] = t;
            }
        }, "test-closer");
        closer.start();
        // The closer is inside the graceful wait (or still before it);
        // either way the interrupt must be honored, not swallowed.
        Thread.sleep(100);
        closer.interrupt();
        closer.join(CLOSE_BOUND_MILLIS);

        assertFalse(closer.isAlive(), "close must return even when interrupted");
        assertNull(failure[0], "close must not throw");
        assertTrue(closer.isInterrupted(), "close must restore the interrupt flag");
        // Interruption is not an excuse to leak the child.
        int exit = server.exitFuture().get(5, TimeUnit.SECONDS);
        assertTrue(exit != 0, "interrupted close must still release the child, got " + exit);
    }

    @Test
    @Timeout(30)
    void startsInTheGivenWorkingDirectory(@TempDir Path dir) throws Exception {
        LspServerProcess server = LspServerProcess.start(List.of("/bin/pwd"), dir);
        try {
            String pwd = new String(server.stdout().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertEquals(dir.toRealPath().toString(), pwd);
        } finally {
            server.close();
        }
    }

    @Test
    @Timeout(30)
    void argumentsArePassedVerbatimWithoutAShell() throws Exception {
        // A shell would fold the spaces and execute the semicolon; an
        // argument list must deliver the string byte for byte.
        String argument = "two  spaces; printf boom";
        LspServerProcess server = LspServerProcess.start(List.of("/bin/echo", argument), null);
        try {
            String echoed = new String(server.stdout().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertEquals(argument, echoed);
        } finally {
            server.close();
        }
    }
}
