package app.drydock.webview;

/**
 * Launch indirection, identical in purpose to
 * {@code app.drydock.terminal.Gate0cSpikeLauncher}: a plain-classpath JVM whose
 * directly launched main class extends {@code Application} trips JavaFX's
 * "runtime components are missing" check; launching {@link WebviewSpike} through
 * this unrelated class avoids it.
 */
public final class WebviewSpikeLauncher {
    private WebviewSpikeLauncher() {
    }

    public static void main(String[] args) {
        WebviewSpike.main(args);
    }
}
