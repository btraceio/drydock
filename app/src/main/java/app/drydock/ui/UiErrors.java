package app.drydock.ui;

import app.drydock.app.DuplicateRepositoryException;
import app.drydock.git.GitCommandFailedException;
import app.drydock.git.GitCommandInterruptedException;
import app.drydock.git.GitExecutableNotFoundException;
import app.drydock.git.NotAGitRepositoryException;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
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

    /**
     * The one-line reason a view renders inline (a placeholder, a status
     * label) instead of in a dialog. Never blank: a failure carrying no
     * message reports its type, for the same reason {@link #show}'s fallback
     * branch does -- "Something went wrong" is not a diagnosis. Lives here so
     * the inline callers and the dialog agree on what a failure reads as.
     */
    public static String message(Throwable failure) {
        Throwable cause = unwrap(failure);
        String text = cause.getMessage();
        return text == null || text.isBlank() ? cause.getClass().getSimpleName() : text;
    }

    /**
     * The no-diagnosis form, for a failure nothing anticipated: the uncaught
     * FX-thread handler. {@link #show(String, Throwable)} names a known
     * failure mode; here there is none, so the value is the stack trace,
     * carried in the dialog's expandable half -- selectable, so it can be
     * pasted into a report. {@code note} prefixes it (where the same record
     * was also written, so it survives closing the dialog); empty for none.
     *
     * <p>The trace does not wrap: a {@link StackOverflowError} carries
     * hundreds of frames and the point is the repeating cycle at the top, so
     * the pane scrolls rather than reflowing frames into each other.</p>
     */
    public static void showUnexpected(String title, Throwable error, String note) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText("An unexpected error occurred");
        alert.setContentText(String.valueOf(unwrap(error)));

        StringWriter trace = new StringWriter(8192);
        PrintWriter out = new PrintWriter(trace);
        if (!note.isBlank()) {
            out.println(note);
            out.println();
        }
        error.printStackTrace(out);

        TextArea details = new TextArea(trace.toString());
        details.setEditable(false);
        details.setPrefRowCount(18);
        details.setPrefColumnCount(100);
        alert.getDialogPane().setExpandableContent(details);
        alert.showAndWait();
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
