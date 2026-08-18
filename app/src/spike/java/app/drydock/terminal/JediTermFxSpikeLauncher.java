package app.drydock.terminal;

/**
 * Indirection required to run a {@code javafx.application.Application}
 * subclass from a plain classpath (no {@code module-info.java}): if the
 * JVM's *directly launched* main class extends {@code Application}, the
 * JavaFX launcher refuses to start with "JavaFX runtime components are
 * missing" unless javafx.graphics is on the module path. Launching through
 * an unrelated class that merely calls {@link JediTermFxSpike#main(String[])}
 * avoids that check entirely. See {@link Gate0cSpikeLauncher}'s Javadoc and
 * docs/native-integration.md (this project intentionally stays non-modular).
 */
public final class JediTermFxSpikeLauncher {
    private JediTermFxSpikeLauncher() {
    }

    public static void main(String[] args) {
        JediTermFxSpike.main(args);
    }
}