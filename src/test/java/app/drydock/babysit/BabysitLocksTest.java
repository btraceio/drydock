package app.drydock.babysit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BabysitLocksTest {

    @TempDir
    Path tempDir;

    @Test
    void staleHeldCloseDoesNotReleaseLaterLock() throws IOException {
        Path file = tempDir.resolve("babysit.lock");
        BabysitLocks.Held first = BabysitLocks.tryLock(file).orElseThrow();
        first.close();

        try (BabysitLocks.Held second = BabysitLocks.tryLock(file).orElseThrow()) {
            first.close();
            assertTrue(BabysitLocks.tryLock(file).isEmpty());
        }

        try (BabysitLocks.Held afterSecond = BabysitLocks.tryLock(file).orElseThrow()) {
            // The second close releases the reservation, allowing a fresh acquisition.
        }
    }
}
