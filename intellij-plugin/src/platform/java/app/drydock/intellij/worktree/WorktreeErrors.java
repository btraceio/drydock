package app.drydock.intellij.worktree;

import app.drydock.git.GitCommandFailedException;
import app.drydock.git.GitException;
import app.drydock.git.GitExecutableNotFoundException;
import app.drydock.git.NotAGitRepositoryException;
import app.drydock.git.WorktreeLockedException;
import app.drydock.git.WorktreeNotCleanException;
import app.drydock.process.ProcessTimeoutException;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * The user-facing text for a failed worktree operation (AC26): git
 * failures, timeouts, and the lock/not-clean refusals surface as a visible
 * message carrying the stderr excerpt — never a silent no-op (C12) and
 * never a generic "something went wrong" that collapses the distinct
 * failure modes the core's exception hierarchy exists to keep apart
 * (plan section 20).
 *
 * <p>Shared by the create flow (T14) and the checkout-in-worktree flow
 * (T15); both reach it through futures, so the {@code
 * CompletionException}/{@code ExecutionException} unwrap lives here once.</p>
 */
public final class WorktreeErrors {

    private WorktreeErrors() {
    }

    /**
     * One message the user reads in an error dialog. Unwraps the future
     * wrappers first ({@code CompletableFuture} failures arrive as
     * {@code CompletionException}; background-task waits as {@code
     * ExecutionException}), then maps the cause by type.
     */
    public static String visibleMessage(Throwable failure) {
        return mapByType(unwrap(failure));
    }

    /** Peels the future wrappers; nested causes (a service rethrowing another's) unwrap too. */
    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException || current instanceof ExecutionException) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                break;
            }
            current = cause;
        }
        return current;
    }

    private static String mapByType(Throwable failure) {
        if (failure instanceof GitCommandFailedException git) {
            // Its own message already embeds the stderr excerpt; rendering it
            // (rather than rewording) keeps the command, exit code, and the
            // excerpt the core guarantees — the excerpt is the whole point
            // of AC26.
            return git.getMessage();
        }
        if (failure instanceof WorktreeLockedException locked) {
            // Its message names the worktree and the recorded lock reason.
            return locked.getMessage();
        }
        if (failure instanceof WorktreeNotCleanException notClean) {
            return notClean.getMessage();
        }
        if (failure instanceof ProcessTimeoutException timeout) {
            return "Git did not finish within " + timeout.timeout().toSeconds() + "s and was killed: "
                    + String.join(" ", timeout.command());
        }
        if (failure instanceof GitExecutableNotFoundException) {
            // Its message carries the "Searched: …" text.
            return failure.getMessage();
        }
        if (failure instanceof NotAGitRepositoryException) {
            return failure.getMessage();
        }
        if (failure instanceof GitException) {
            // The shared git hierarchy: any subclass without its own clause
            // above still gets its own words, not a generic fallback.
            return failure.getMessage();
        }
        String message = failure.getMessage();
        return message != null && !message.isBlank() ? message : failure.toString();
    }
}
