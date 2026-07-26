package app.drydock.ui;

import app.drydock.app.DuplicateRepositoryException;
import app.drydock.git.GitCommandFailedException;
import app.drydock.git.GitCommandInterruptedException;
import app.drydock.git.GitExecutableNotFoundException;
import app.drydock.git.NotAGitRepositoryException;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;

import java.io.IOException;
import java.util.concurrent.CompletionException;

/**
 * Turns a failure into a user-visible {@link Alert} that states what
 * failed, which executable/path was involved, the exit code, and the
 * relevant stderr excerpt where applicable (plan section 20: "never reduce
 * these to 'Something went wrong'"). Public only because the UI
 * sub-packages ({@code app.drydock.ui.review} etc.) share it.
 */
public final class UiErrors {

    private UiErrors() {
    }

    /** Unwraps a {@link CompletionException} (as produced by CompletableFuture chains) to its cause. */
    public static Throwable unwrap(Throwable failure) {
        return (failure instanceof CompletionException && failure.getCause() != null)
                ? failure.getCause()
                : failure;
    }

    static void show(String title, Throwable failure) {
        Throwable cause = unwrap(failure);
        String header;
        String body;

        if (cause instanceof GitExecutableNotFoundException e) {
            header = "Git executable not found";
            body = e.getMessage();
        } else if (cause instanceof NotAGitRepositoryException e) {
            header = "Not a Git repository";
            body = e.getMessage();
        } else if (cause instanceof GitCommandInterruptedException e) {
            // Distinct from a failure/timeout: nothing is wrong with the
            // repository, the wait was cut short (cancellation, shutdown), and
            // the generic branch would title that "GitCommandInterruptedException".
            header = "Git command interrupted";
            body = "Command: " + String.join(" ", e.command());
        } else if (cause instanceof GitCommandFailedException e) {
            header = "Git command failed (exit " + e.exitCode() + ")";
            body = "Command: " + String.join(" ", e.command())
                    + (e.stderrExcerpt().isBlank() ? "" : System.lineSeparator() + System.lineSeparator() + e.stderrExcerpt());
        } else if (cause instanceof DuplicateRepositoryException e) {
            header = "Repository already registered";
            body = e.getMessage();
        } else if (cause instanceof IOException e) {
            header = "Could not launch external process";
            body = e.getMessage();
        } else {
            // Still never "Something went wrong": name the exception type
            // and message explicitly, since this branch means a failure
            // mode this code does not yet have a specific case for.
            header = cause.getClass().getSimpleName();
            body = String.valueOf(cause.getMessage());
        }

        alert(title, header, body);
    }

    /**
     * The no-exception form: the failure is already a sentence, because the
     * step that failed reported it instead of throwing (see {@code
     * WorktreeSessionCleanup}). Wrapping such a reason in an
     * {@code IllegalStateException} just to reuse the {@link Throwable} form
     * would put "IllegalStateException" in the header, exactly where the user
     * needs the reason.
     */
    static void show(String title, String header, String detail) {
        alert(title, header, detail);
    }

    private static void alert(String title, String header, String body) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(header);

        TextArea details = new TextArea(body);
        details.setEditable(false);
        details.setWrapText(true);
        details.setPrefRowCount(6);
        alert.getDialogPane().setContent(details);
        alert.showAndWait();
    }
}
