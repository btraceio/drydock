package app.drydock.testing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkIoPollerThreadsTest {

    @Test
    void aVirtualThreadBlockedOnASocketIsNotALeak() throws Exception {
        // A virtual thread that parks on a socket read makes the JDK start
        // its I/O poller threads (Read-/Write-Poller, Read-/Write-Updater),
        // which live for the rest of the JVM. The platform's thread-leak
        // check must not blame the test that happened to start them first.
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CountDownLatch accepted = new CountDownLatch(1);
            AtomicInteger read = new AtomicInteger(-2);
            Thread reader = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept(); InputStream in = socket.getInputStream()) {
                    accepted.countDown();
                    read.set(in.read());
                } catch (IOException e) {
                    read.set(-3);
                }
            });
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                assertTrue(accepted.await(10, TimeUnit.SECONDS), "the reader never accepted");
                Thread.sleep(200);   // let the reader park on the empty socket
                client.getOutputStream().write(7);
            }
            assertTrue(reader.join(Duration.ofSeconds(10)), "the reader never finished");
            assertEquals(7, read.get());
        }
    }
}
