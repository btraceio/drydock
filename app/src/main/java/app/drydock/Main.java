package app.drydock;

import app.drydock.app.LoginShellEnvironment;

/**
 * Application entry point.
 *
 * <p>Kept as a plain launcher class (rather than extending {@link
 * javafx.application.Application} directly) so the runnable jar / jlink
 * image works even before the application is fully modularized.</p>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        // Must run before anything touches System.getenv()/ProcessBuilder;
        // see LoginShellEnvironment's Javadoc (the JDK snapshots the
        // environment on first use, and Finder launches need the repaired
        // PATH inside that snapshot).
        LoginShellEnvironment.mergeLoginShellPath();
        DrydockApplication.main(args);
    }
}
