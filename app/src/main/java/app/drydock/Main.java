package app.drydock;

import app.drydock.app.LoginShellEnvironment;

import java.awt.Toolkit;

/**
 * Application entry point.
 *
 * <p>Kept as a plain launcher class (rather than extending {@link
 * javafx.application.Application} directly) so the runnable jar / jlink
 * image works even before the application is fully modularized.</p>
 */
public final class Main {

    private static final String APP_NAME = "Drydock";

    private Main() {
    }

    public static void main(String[] args) {
        // Must run before anything touches System.getenv()/ProcessBuilder
        // (including the AWT init below); see LoginShellEnvironment's Javadoc
        // (the JDK snapshots the environment on first use, and Finder launches
        // need the repaired PATH inside that snapshot).
        LoginShellEnvironment.mergeLoginShellPath();

        // Name the macOS app menu / dock "Drydock" instead of the JVM default
        // "java", for non-bundled launches (jbang, ./gradlew run, the jlink
        // image). The name that macOS shows for the running app is registered
        // by AWT's NSApplicationAWT.registerWithProcessManager, which reads
        // apple.awt.application.name and performs the LaunchServices
        // registration -- JavaFX's Glass uses a plain NSApplication that never
        // does this, so forcing AWT to initialize here (before Glass launches
        // below) is what actually sets the name. Must run before
        // Application.launch; verified to coexist with Glass without a
        // main-thread deadlock. A real .app bundle (./gradlew appImage) instead
        // supplies the name via Info.plist's CFBundleName.
        System.setProperty("apple.awt.application.name", APP_NAME);
        Toolkit.getDefaultToolkit();

        DrydockApplication.main(args);
    }
}
