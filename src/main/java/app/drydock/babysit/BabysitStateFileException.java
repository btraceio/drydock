package app.drydock.babysit;

/**
 * The babysitting state file could not be changed: it is malformed or was
 * written by a newer schema (and so must not be overwritten), it stayed
 * locked by another process for too long, or the file system refused the
 * write.
 */
public final class BabysitStateFileException extends Exception {

    public BabysitStateFileException(String message) {
        super(message);
    }

    public BabysitStateFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
