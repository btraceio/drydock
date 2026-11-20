package app.drydock.testing;

import app.drydock.app.ApplicationStateStore;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Flushes every live {@link ApplicationStateStore} after each test.
 *
 * <p>The store persists asynchronously on a daemon writer thread. A test
 * that ends without {@code flush()} leaves that writer free to write the
 * state file into the test's {@code @TempDir} after the test method has
 * returned, racing JUnit's directory cleanup — the failure surfaces as
 * {@code Failed to close extension context} with a suppressed
 * {@code DirectoryNotEmptyException}, on whichever test of the class
 * happens to lose the race, and only on a runner slow enough for the
 * writer to still be in flight. The store documents this contract
 * ("flush exposes the barrier ... so tests do not race pending writes"),
 * but every test class must remember it; this extension enforces it once
 * for all of them, the same way {@link JdkIoPollerThreads} is registered.</p>
 */
public final class ApplicationStateStoreFlush implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        ApplicationStateStore.flushAll();
    }
}
