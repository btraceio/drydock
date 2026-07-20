package app.cpm.terminal;

/**
 * Indirection required to run a {@code javafx.application.Application}
 * subclass from a plain classpath (no {@code module-info.java}): if the
 * JVM's *directly launched* main class extends {@code Application}, the
 * JavaFX launcher refuses to start with "JavaFX runtime components are
 * missing" unless javafx.graphics is on the module path. Launching through
 * an unrelated class that merely calls {@link Gate0cSpike#main(String[])}
 * avoids that check entirely. See docs/native-integration.md and plan
 * section 6.4 (this project intentionally stays non-modular for now).
 */
public final class Gate0cSpikeLauncher {
    private Gate0cSpikeLauncher() {
    }

    public static void main(String[] args) {
        Gate0cSpike.main(args);
    }
}
