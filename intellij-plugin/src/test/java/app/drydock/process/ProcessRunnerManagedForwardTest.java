package app.drydock.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.drydock.process.ProcessRunner.ForwardOptions;
import app.drydock.process.ProcessRunner.ManagedForward;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** Lifecycle of the managed SSH forward against a fake child: no real ssh, no real waiting. */
class ProcessRunnerManagedForwardTest {

    private static final List<String> ARGV = List.of("ssh", "-N", "-T", "-R", "127.0.0.1:5001:127.0.0.1:6002",
            "--", "secret-box.example");
    private static final ForwardOptions OPTS = new ForwardOptions(null, Map.of());

    /** Blocking pipe the test feeds; EOF on {@link #end()}. */
    private static final class Pipe extends InputStream {
        private static final byte[] EOF = new byte[0];
        private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private byte[] current = new byte[0];
        private int pos;
        private final AtomicInteger unread = new AtomicInteger();

        void feed(byte[] bytes) {
            unread.addAndGet(bytes.length);
            chunks.add(bytes);
        }

        void end() {
            chunks.add(EOF);
        }

        int unreadBytes() {
            return unread.get();
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                while (pos >= current.length) {
                    byte[] next = chunks.take();
                    if (next == EOF) {
                        chunks.add(EOF);
                        return -1;
                    }
                    current = next;
                    pos = 0;
                }
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            int n = Math.min(len, current.length - pos);
            System.arraycopy(current, pos, b, off, n);
            pos += n;
            unread.addAndGet(-n);
            return n;
        }
    }

    /** Fake child: records destroy calls and the waitFor timeouts; can ignore a polite destroy(). */
    private static final class FakeChild extends Process {
        final Pipe out = new Pipe();
        final Pipe err = new Pipe();
        final CompletableFuture<Process> exited = new CompletableFuture<>();
        final boolean ignoreDestroy;
        final List<Long> waitMillis = new ArrayList<>();
        int destroys;
        int forcibles;
        volatile int exitCode;

        FakeChild(boolean ignoreDestroy) {
            this.ignoreDestroy = ignoreDestroy;
        }

        void exit(int code) {
            exitCode = code;
            out.end();
            err.end();
            exited.complete(this);
        }

        @Override public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }
        @Override public InputStream getInputStream() {
            return out;
        }
        @Override public InputStream getErrorStream() {
            return err;
        }
        @Override public synchronized boolean waitFor(long t, TimeUnit u) {
            waitMillis.add(u.toMillis(t));
            return exited.isDone();
        }
        @Override public int waitFor() throws InterruptedException {
            throw new AssertionError("unbounded wait");
        }
        @Override public int exitValue() {
            if (!exited.isDone()) {
                throw new IllegalThreadStateException();
            }
            return exitCode;
        }
        @Override public synchronized void destroy() {
            destroys++;
            if (!ignoreDestroy) {
                exit(143);
            }
        }
        @Override public synchronized Process destroyForcibly() {
            forcibles++;
            exit(137);
            return this;
        }
        @Override public boolean isAlive() {
            return !exited.isDone();
        }
        @Override public CompletableFuture<Process> onExit() {
            return exited;
        }
    }

    private static ManagedForward start(FakeChild child) throws IOException {
        return ProcessRunner.startSshForward(ARGV, OPTS, (argv, o) -> child);
    }

    private static void awaitReaders(ManagedForward f) throws InterruptedException {
        for (int i = 0; i < 200 && f.readersAlive(); i++) {
            Thread.sleep(10);
        }
        assertFalse(f.readersAlive(), "reader threads must be joined");
    }

    @Test
    void connectRefusalNotifiesExitAndRedactsTransportCoordinates() throws Exception {
        FakeChild child = new FakeChild(false);
        try (ManagedForward f = start(child)) {
            List<Integer> seen = new ArrayList<>();
            f.onExit(seen::add);
            child.err.feed("ssh: connect to host secret-box.example port 22: Connection refused\n"
                    .getBytes(StandardCharsets.UTF_8));
            child.exit(255);
            assertEquals(255, f.exit().get(2, TimeUnit.SECONDS));
            awaitReaders(f);
            assertEquals(List.of(255), seen);
            assertTrue(f.stderr().contains("Connection refused"));
            assertFalse(f.stderr().contains("secret-box.example"));
        }
    }

    @Test
    void earlyExitDoesNotNeedCloseAndCloseStaysSafe() throws Exception {
        FakeChild child = new FakeChild(false);
        ManagedForward f = start(child);
        child.exit(1);
        assertEquals(1, f.exit().get(2, TimeUnit.SECONDS));
        f.close();
        f.close();
        assertEquals(1, child.destroys, "destroy is idempotent per handle");
        assertEquals(0, child.forcibles);
        awaitReaders(f);
    }

    @Test
    void cancelDestroysGracefullyAndJoinsReaders() throws Exception {
        FakeChild child = new FakeChild(false);
        ManagedForward f = start(child);
        assertTrue(f.isAlive());
        f.close();
        assertEquals(1, child.destroys);
        assertEquals(0, child.forcibles);
        assertEquals(143, f.exit().get(2, TimeUnit.SECONDS));
        assertFalse(f.readersAlive());
    }

    @Test
    void stuckChildIsForcedAfterTwoSecondGrace() throws Exception {
        FakeChild child = new FakeChild(true);
        ManagedForward f = start(child);
        f.close();
        assertEquals(1, child.destroys);
        assertEquals(1, child.forcibles);
        assertEquals(List.of(2000L, 2000L), child.waitMillis);
        assertFalse(f.readersAlive());
    }

    @Test
    void keepaliveLossAfterLongLifeIsAnExitNotification() throws Exception {
        FakeChild child = new FakeChild(false);
        ManagedForward f = start(child);
        child.err.feed("Timeout, server not responding.\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(f.isAlive());
        assertTrue(child.waitMillis.isEmpty(), "no lifetime deadline is ever armed while running");
        child.exit(255);
        assertEquals(255, f.exit().get(2, TimeUnit.SECONDS));
        awaitReaders(f);
        assertTrue(f.stderr().contains("server not responding"));
    }

    @Test
    void drainsKeepAtMost64KiBButConsumeEverything() throws Exception {
        FakeChild child = new FakeChild(false);
        ManagedForward f = start(child);
        byte[] chunk = "x".repeat(8192).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 25; i++) {
            child.out.feed(chunk);
            child.err.feed(chunk);
        }
        child.exit(0);
        awaitReaders(f);
        assertEquals(0, child.out.unreadBytes());
        assertEquals(0, child.err.unreadBytes());
        assertEquals(64 * 1024, f.stdout().length());
        assertEquals(64 * 1024, f.stderr().length());
    }

    @Test
    void credentialsAreRedactedFromDiagnostics() throws Exception {
        FakeChild child = new FakeChild(false);
        ManagedForward f = start(child);
        child.err.feed("Authorization: Bearer abc123 token=zzz -R 127.0.0.1:5001:127.0.0.1:6002\n"
                .getBytes(StandardCharsets.UTF_8));
        child.exit(255);
        awaitReaders(f);
        String text = f.stderr();
        assertFalse(text.contains("abc123") || text.contains("zzz") || text.contains("5001"), text);
    }

    @Test
    void refusesAnythingButAnSshForward() {
        assertThrows(IllegalArgumentException.class,
                () -> ProcessRunner.startSshForward(List.of("sh", "-c", "ssh -N"), OPTS, (a, o) -> null));
        assertThrows(IllegalArgumentException.class,
                () -> ProcessRunner.startSshForward(List.of("ssh", "host"), OPTS, (a, o) -> null));
    }
}
