package app.drydock.intellij.review;

import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;

/**
 * Q9: the save of open documents that every user-triggered review load
 * (Changes link, Refresh, tab reload) runs first, on the EDT.
 *
 * <p>The platform requires the write-intent lock for
 * {@code saveAllDocuments} (it asserts it through
 * {@code TransactionGuardImpl.assertWriteSafeEnvironment}), and the EDT
 * holds none implicitly — not inside a Swing button handler, and not in
 * {@code actionPerformed} unless the action requires the RW lock. Every
 * call therefore goes through {@link WriteIntentReadAction}: synchronous,
 * still on the EDT, so the diff that follows the save sees saved content.</p>
 */
final class ReviewDocumentSaves {

    private ReviewDocumentSaves() {
    }

    /** Saves all open documents under the write-intent lock; EDT. */
    static void saveOpenDocuments() {
        WriteIntentReadAction.run(() -> FileDocumentManager.getInstance().saveAllDocuments());
    }
}
