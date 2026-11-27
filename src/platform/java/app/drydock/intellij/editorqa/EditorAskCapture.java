package app.drydock.intellij.editorqa;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import com.intellij.injected.editor.DocumentWindow;
import com.intellij.injected.editor.EditorWindow;
import com.intellij.ide.scratch.ScratchUtil;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;

import app.drydock.editorqa.EditorAskLimits;
import app.drydock.editorqa.EditorAskRecord.TextRange;

/**
 * Eligibility of an editor for "Ask Agent…" and the capture of what the user
 * selected, both in memory only.
 *
 * <p>{@link #refusal} is the cheap check the action runs both to present
 * itself and again at invocation: normal main editor, a real local file
 * backed by that very document, one non-empty primary selection, and none
 * of the diff / injected / console / scratch contexts. It reads only editor
 * and document state already in memory -- no disk, no Git, no index.</p>
 *
 * <p>{@link #capture} runs once, on the EDT, before any asynchronous step.
 * It freezes what the question is about: the document's immutable text
 * snapshot (never a disk reread), the file identity, the half-open UTF-16
 * range {@code [start,end)} with line/column coordinates, and the exact
 * selected substring. It also leaves a live non-greedy {@link RangeMarker}.
 * The marker and the snapshot are different things: edits made while the
 * question is composed move the marker (the decoration), never the
 * snapshot (what is submitted). Nothing here saves, looks up focus, or
 * rereads the source afterwards.</p>
 */
final class EditorAskCapture {

    private EditorAskCapture() {
    }

    /**
     * What was selected, frozen at invocation. The text, range and
     * coordinates are immutable values; {@link #marker()} is the one live
     * part. Whoever finally owns the ask calls {@link #release()}.
     */
    record Snapshot(
            Path file,
            CharSequence document,
            long modificationStamp,
            TextRange range,
            String selectedText,
            RangeMarker marker) {

        Snapshot {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(range, "range");
            Objects.requireNonNull(selectedText, "selectedText");
            Objects.requireNonNull(marker, "marker");
        }

        /** Disposes the live marker; idempotent. */
        void release() {
            marker.dispose();
        }
    }

    /**
     * Why the editor cannot be asked about, or empty when it can. Cheap and
     * in-memory: safe for every presentation update on the EDT.
     *
     * @param diffContext true when the action's data context carries a diff
     *        request or viewer, whatever the editor's own kind says
     */
    static Optional<String> refusal(Editor editor, boolean diffContext) {
        if (editor == null) {
            return Optional.of("No editor");
        }
        if (diffContext || editor.getEditorKind() == EditorKind.DIFF) {
            return Optional.of("Ask Agent is not available in a diff");
        }
        if (editor.getEditorKind() != EditorKind.MAIN_EDITOR) {
            return Optional.of("Ask Agent works in the main text editor only");
        }
        Document document = editor.getDocument();
        if (editor instanceof EditorWindow || document instanceof DocumentWindow) {
            return Optional.of("Ask Agent is not available in injected language fragments");
        }
        Optional<String> file = fileRefusal(document);
        if (file.isPresent()) {
            return file;
        }
        return selectionRefusal(editor);
    }

    /** The main-editor file check alone: a real local file that this document belongs to. */
    private static Optional<String> fileRefusal(Document document) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null || file instanceof LightVirtualFile || !file.isValid() || file.isDirectory()) {
            return Optional.of("Ask Agent needs a file on disk");
        }
        if (ScratchUtil.isScratch(file)) {
            return Optional.of("Ask Agent is not available in scratch files");
        }
        if (file.getFileSystem() != LocalFileSystem.getInstance()) {
            return Optional.of("Ask Agent needs a file on the local file system");
        }
        return Optional.empty();
    }

    private static Optional<String> selectionRefusal(Editor editor) {
        int withSelection = 0;
        for (Caret caret : editor.getCaretModel().getAllCarets()) {
            if (caret.hasSelection()) {
                withSelection++;
            }
        }
        if (withSelection > 1) {
            return Optional.of("Select a single range to ask about");
        }
        Caret primary = editor.getCaretModel().getPrimaryCaret();
        if (!primary.hasSelection() || primary.getSelectionEnd() <= primary.getSelectionStart()) {
            return Optional.of("Select the code to ask about");
        }
        if (primary.getSelectionEnd() - primary.getSelectionStart() > EditorAskLimits.MAX_SELECTION_BYTES) {
            return Optional.of("The selection is longer than " + EditorAskLimits.MAX_SELECTION_BYTES / 1024
                    + " KiB");
        }
        return Optional.empty();
    }

    /**
     * Freezes the primary selection of an editor that passed {@link #refusal}.
     * Must run on the EDT, before any asynchronous step.
     *
     * @throws EditorAskLimits.RejectedInputException if the exact selection
     *         breaks a bound or character rule (never clipped)
     * @throws IllegalStateException if the editor is not eligible
     */
    static Snapshot capture(Editor editor) {
        Document document = editor.getDocument();
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        Caret primary = editor.getCaretModel().getPrimaryCaret();
        if (file == null || !primary.hasSelection()) {
            throw new IllegalStateException("editor is not eligible for capture");
        }
        int start = primary.getSelectionStart();
        int end = primary.getSelectionEnd();
        CharSequence text = document.getImmutableCharSequence();
        String selected = EditorAskLimits.checkSelection(text.subSequence(start, end).toString());
        int startLine = document.getLineNumber(start);
        int endLine = document.getLineNumber(end);
        TextRange range = new TextRange(start, end,
                startLine, start - document.getLineStartOffset(startLine),
                endLine, end - document.getLineStartOffset(endLine));
        RangeMarker marker = document.createRangeMarker(start, end);
        marker.setGreedyToLeft(false);
        marker.setGreedyToRight(false);
        return new Snapshot(file.toNioPath(), text, document.getModificationStamp(), range, selected, marker);
    }
}
