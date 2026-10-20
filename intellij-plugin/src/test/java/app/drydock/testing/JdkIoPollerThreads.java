package app.drydock.testing;

import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.common.ThreadLeakTracker;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tells the platform's thread-leak check that the JDK's I/O poller threads
 * are not leaks. The first virtual thread to park on a socket starts them
 * (Read-/Write-Poller, Read-/Write-Updater) and they live as long as the
 * JVM, so without this whichever test happens to start them fails after a
 * 10 s wait. Which one does depends on timing: the MCP server's handlers
 * run on virtual threads and only park when a request body is not yet
 * buffered, which a slow CI runner hits and a fast laptop rarely does.
 *
 * <p>Registered for every JUnit 5 test through
 * {@code META-INF/services/org.junit.jupiter.api.extension.Extension}, the
 * same way the platform registers its own {@code ThreadLeakTrackerExtension}.</p>
 */
public final class JdkIoPollerThreads implements BeforeAllCallback {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    @Override
    public void beforeAll(ExtensionContext context) {
        if (REGISTERED.compareAndSet(false, true)) {
            // Never disposed: the threads it names outlive every test.
            ThreadLeakTracker.longRunningThreadCreated(Disposer.newDisposable("JDK I/O poller threads"),
                    "Read-Poller", "Write-Poller", "Read-Updater", "Write-Updater");
        }
    }
}
