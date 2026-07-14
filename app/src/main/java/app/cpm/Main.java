package app.cpm;

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
        CpmApplication.main(args);
    }
}
