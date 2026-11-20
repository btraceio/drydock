package app.drydock.projects;

/**
 * The shared projects list could not be changed: it is malformed (and so
 * must not be overwritten), locked by another Drydock for too long, or the
 * file system refused the write.
 */
public final class SharedProjectsException extends Exception {

    public SharedProjectsException(String message) {
        super(message);
    }

    public SharedProjectsException(String message, Throwable cause) {
        super(message, cause);
    }
}
