package app.drydock.ui.review;

/**
 * Launch indirection (see {@code app.drydock.terminal.Gate0cSpikeLauncher}): a
 * JVM whose directly launched main class extends {@code Application} trips
 * JavaFX's "runtime components are missing" check on a plain classpath.
 */
public final class DrawerPageSpikeLauncher {
    private DrawerPageSpikeLauncher() {
    }

    public static void main(String[] args) {
        DrawerPageSpike.main(args);
    }
}
