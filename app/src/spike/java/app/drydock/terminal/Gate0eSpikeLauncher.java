package app.drydock.terminal;

/**
 * Same indirection as {@link Gate0cSpikeLauncher} (see its Javadoc): a
 * non-{@code Application} main class is required to launch a JavaFX
 * {@code Application} subclass from a plain classpath (no
 * {@code module-info.java}).
 */
public final class Gate0eSpikeLauncher {
    private Gate0eSpikeLauncher() {
    }

    public static void main(String[] args) {
        Gate0eSpike.main(args);
    }
}
