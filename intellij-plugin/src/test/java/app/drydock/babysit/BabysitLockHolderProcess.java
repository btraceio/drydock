package app.drydock.babysit;

import java.nio.file.Path;

/**
 * A second process for the babysitting lock tests: takes the lock on {@code
 * args[0]} the way the detached watcher takes its own ({@link
 * BabysitLocks#tryLock}), prints {@code locked}, and holds it until it is
 * killed -- the OS releasing it then is what "a dead watcher is stale" rests on.
 */
public final class BabysitLockHolderProcess {

    private BabysitLockHolderProcess() {
    }

    public static void main(String[] args) throws Exception {
        BabysitLocks.Held held = BabysitLocks.tryLock(Path.of(args[0]))
                .orElseThrow(() -> new IllegalStateException("the lock is already held"));
        System.out.println("locked");
        System.out.flush();
        Thread.sleep(120_000);
        held.close();
    }
}
