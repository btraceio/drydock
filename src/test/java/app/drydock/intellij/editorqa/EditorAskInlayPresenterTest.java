package app.drydock.intellij.editorqa;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.swing.JButton;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import app.drydock.domain.ManagedSessionId;
import app.drydock.editorqa.EditorAskAnchor;
import app.drydock.editorqa.EditorAskRecord;
import app.drydock.editorqa.EditorAskRecord.Status;
import app.drydock.editorqa.EditorAskRecord.TextRange;
import app.drydock.intellij.editorqa.EditorAskInlayPresenter.Location;
import app.drydock.state.json.JsonValue;

/** Inline card presentation, tracking and release on real editors of real local files. */
public class EditorAskInlayPresenterTest extends BasePlatformTestCase {

    private static final String ROOT = "/chk";
    private static final String FILE = "F.java";
    private static final String TEXT = "zero\none selected\ntwo\nthree\n";
    private static final int START = 5;
    private static final int END = 17; // "one selected" excludes the newline

    private final List<Path> cleanup = new ArrayList<>();
    private final List<Editor> editors = new ArrayList<>();
    private final List<String> persisted = new CopyOnWriteArrayList<>();
    private final List<JsonValue> anchors = new CopyOnWriteArrayList<>();
    private final List<String> opened = new CopyOnWriteArrayList<>();
    private final EditorAskUiModel model = new EditorAskUiModel(Runnable::run, 0);
    private Optional<Location> located = Optional.of(new Location(ROOT, FILE));
    private EditorAskInlayPresenter presenter;

    @Override
    protected void tearDown() throws Exception {
        try {
            if (presenter != null) {
                presenter.close();
            }
            for (Editor editor : editors) {
                if (!editor.isDisposed()) {
                    EditorFactory.getInstance().releaseEditor(editor);
                }
            }
            model.close();
        } finally {
            for (Path p : cleanup) {
                Files.deleteIfExists(p);
            }
            super.tearDown();
        }
    }

    private void startPresenter() {
        presenter = new EditorAskInlayPresenter(getProject(), getTestRootDisposable(),
                EditorAskEditorObserver.platformSources(), model, new EditorAskInlayPresenter.Source() {
                    @Override public Optional<Location> locate(Path file) { return located; }

                    @Override public void persistAnchor(String checkout, String id, JsonValue anchor) {
                        persisted.add(id);
                        anchors.add(anchor);
                    }

                    @Override public void openSession(Project project, EditorAskRecord record) {
                        opened.add(record.id());
                    }
                }, Runnable::run, r -> ApplicationManager.getApplication().invokeLater(r), ZoneOffset.UTC);
    }

    private Editor editor(String text) throws IOException {
        Path dir = Files.createTempDirectory("drydock-ask-inlay").toRealPath();
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), dir.toString());
        Path file = dir.resolve("F" + cleanup.size() + ".java");
        cleanup.add(file);
        cleanup.add(dir);
        Files.writeString(file, text);
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
        assertNotNull(vf);
        return editorOn(FileDocumentManager.getInstance().getDocument(vf));
    }

    private Editor editorOn(Document document) {
        Editor e = EditorFactory.getInstance().createEditor(document, getProject(), EditorKind.MAIN_EDITOR);
        editors.add(e);
        return e;
    }

    private void waitFor(BooleanSupplier c) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        while (!c.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        }
    }

    private void settle() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            Thread.sleep(20);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        }
    }

    private static EditorAskRecord record(Document doc, int start, int end, Status status, String answer, boolean anchored) {
        TextRange range = new TextRange(start, end, doc.getLineNumber(start), start - doc.getLineStartOffset(doc.getLineNumber(start)),
                doc.getLineNumber(end), end - doc.getLineStartOffset(doc.getLineNumber(end)));
        JsonValue anchor = anchored
                ? EditorAskAnchor.capture(ROOT, FILE, doc.getImmutableCharSequence(), start, end).toJson() : null;
        Instant t = Instant.parse("2030-01-02T03:04:00Z");
        String text = doc.getImmutableCharSequence().subSequence(start, end).toString();
        EditorAskRecord base = new EditorAskRecord(ID.get(), 1, OWNER, FILE, range, text, "why?", t,
                Status.AWAITING, null, null, null, anchor);
        return status == Status.ANSWERED ? base.answered(answer, t.plusSeconds(60)) : base;
    }

    private static final ManagedSessionId OWNER = ManagedSessionId.newId();
    private static final ThreadLocal<String> ID = ThreadLocal.withInitial(EditorAskRecord::newId);

    private EditorAskRecord answeredRecord(Document doc, int s, int e, String answer) {
        return record(doc, s, e, Status.ANSWERED, answer, true);
    }

    public void testReopenShowsPreviewTimestampToggleAndOpenSessionWithoutTouchingTheDocument() throws Exception {
        Editor editor = editor(TEXT);
        Document doc = editor.getDocument();
        long stamp = doc.getModificationStamp();
        EditorAskRecord rec = answeredRecord(doc, START, END, "a1\na2\na3\na4\na5");
        model.recorded(ROOT, rec);
        startPresenter();
        waitFor(() -> presenter.cardOf(editor, rec.id()) != null);

        EditorAskInlayCard card = presenter.cardOf(editor, rec.id());
        assertNotNull(card);
        assertEquals(1, presenter.cardLineOf(editor, rec.id()));
        assertEquals("a1\na2\na3\n…", card.bodyText());
        assertTrue(card.timeText(), card.timeText().length() > 0);
        assertEquals("why?", card.questionText());
        card.toggle();
        assertEquals("a1\na2\na3\na4\na5", card.bodyText());
        card.toggle();
        assertFalse(card.expanded());
        ((JButton) card.openSessionButton()).doClick();
        assertEquals(List.of(rec.id()), opened);
        assertEquals(stamp, doc.getModificationStamp());
        assertFalse(UndoManager.getInstance(getProject()).isUndoAvailable(null));
        assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(doc));
    }

    public void testSelectionEndingAtColumnZeroOfNextLineStaysOnItsLastSelectedLine() throws Exception {
        Editor editor = editor(TEXT);
        Document doc = editor.getDocument();
        int end = doc.getLineStartOffset(2); // column 0 of line 2
        EditorAskRecord rec = answeredRecord(doc, START, end, "ok");
        model.recorded(ROOT, rec);
        startPresenter();
        waitFor(() -> presenter.cardOf(editor, rec.id()) != null);
        assertEquals(1, presenter.cardLineOf(editor, rec.id()));
    }

    public void testSplitEditorsEachGetOneCardAndRepeatedUpdatesDoNotDuplicate() throws Exception {
        Editor first = editor(TEXT);
        Editor second = editorOn(first.getDocument());
        EditorAskRecord rec = answeredRecord(first.getDocument(), START, END, "ok");
        model.recorded(ROOT, rec);
        startPresenter();
        waitFor(() -> presenter.cardOf(first, rec.id()) != null && presenter.cardOf(second, rec.id()) != null);
        assertNotSame(presenter.cardOf(first, rec.id()), presenter.cardOf(second, rec.id()));
        EditorAskInlayCard card = presenter.cardOf(first, rec.id());
        model.recorded(ROOT, rec);
        model.recorded(ROOT, rec);
        settle();
        assertSame(card, presenter.cardOf(first, rec.id()));
        assertEquals(Set.of(rec.id()), presenter.shownIds(first));
        assertEquals(2, first.getInlayModel().getBlockElementsInRange(0, TEXT.length()).size()
                + second.getInlayModel().getBlockElementsInRange(0, TEXT.length()).size());
    }

    public void testCloseAndReopenDuringAnAnswerKeepsTheCardAndTakesTheAnswer() throws Exception {
        Editor editor = editor(TEXT);
        Document doc = editor.getDocument();
        EditorAskRecord pending = record(doc, START, END, Status.AWAITING, null, true);
        model.recorded(ROOT, pending);
        startPresenter();
        waitFor(() -> presenter.cardOf(editor, pending.id()) != null);
        assertEquals("Waiting for the agent's answer…", presenter.cardOf(editor, pending.id()).bodyText());

        EditorFactory.getInstance().releaseEditor(editor);
        settle();
        assertEquals(0, presenter.attachedEditors());
        Editor reopened = editorOn(doc);
        model.recorded(ROOT, pending.answered("done", Instant.parse("2030-01-02T03:05:00Z")));
        waitFor(() -> presenter.cardOf(reopened, pending.id()) != null
                && "done".equals(presenter.cardOf(reopened, pending.id()).bodyText()));
        assertEquals("done", presenter.cardOf(reopened, pending.id()).bodyText());
    }

    public void testEditsAboveAnIntactSelectionMoveTheCardAndPersistTheAnchor() throws Exception {
        Editor editor = editor(TEXT);
        Document doc = editor.getDocument();
        EditorAskRecord rec = answeredRecord(doc, START, END, "ok");
        model.recorded(ROOT, rec);
        startPresenter();
        waitFor(() -> presenter.cardOf(editor, rec.id()) != null);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> doc.insertString(0, "new line\n"));
        waitFor(() -> presenter.cardLineOf(editor, rec.id()) == 2 && !persisted.isEmpty());
        assertEquals(2, presenter.cardLineOf(editor, rec.id()));
        assertEquals(rec.id(), persisted.get(0));
        EditorAskAnchor moved = EditorAskAnchor.fromJson(parsePersisted()).orElseThrow();
        assertEquals(START + 9, moved.startOffset());
    }

    private JsonValue parsePersisted() {
        return anchors.get(0);
    }

    public void testReplacementAndDeletionRemoveTheCardButKeepTheRecord() throws Exception {
        Editor editor = editor(TEXT);
        Document doc = editor.getDocument();
        EditorAskRecord rec = answeredRecord(doc, START, END, "ok");
        model.recorded(ROOT, rec);
        startPresenter();
        waitFor(() -> presenter.cardOf(editor, rec.id()) != null);
        EditorAskInlayCard card = presenter.cardOf(editor, rec.id());
        WriteCommandAction.runWriteCommandAction(getProject(), () -> doc.replaceString(START, END, "one SELECTED"));
        waitFor(() -> presenter.cardOf(editor, rec.id()) == null);
        assertNull(presenter.cardOf(editor, rec.id()));
        assertTrue(card.isDisposed());
        assertEquals(1, model.rows(ROOT).size());
        assertEquals(0, editor.getInlayModel().getBlockElementsInRange(0, doc.getTextLength()).size());
    }

    public void testMissingFileOrChangedDocumentOrRepeatedTextGivesNoInlayAndKeepsHistory() throws Exception {
        located = Optional.empty();
        Editor missing = editor(TEXT);
        EditorAskRecord rec = answeredRecord(missing.getDocument(), START, END, "ok");
        model.recorded(ROOT, rec);
        startPresenter();
        settle();
        assertNull(presenter.cardOf(missing, rec.id()));

        located = Optional.of(new Location(ROOT, FILE));
        String repeated = "zero\none selected\ntwo\none selected\n";
        Editor changed = editor(repeated); // the same selected text exists elsewhere, but the document differs
        waitFor(() -> presenter.attachedEditors() == 2);
        settle();
        assertNull("never searches for a similar substring", presenter.cardOf(changed, rec.id()));
        assertEquals(1, model.rows(ROOT).size());
    }

    public void testNoUpdateReachesADiscardedEditor() throws Exception {
        Editor editor = editor(TEXT);
        EditorAskRecord rec = record(editor.getDocument(), START, END, Status.AWAITING, null, true);
        model.recorded(ROOT, rec);
        startPresenter();
        waitFor(() -> presenter.cardOf(editor, rec.id()) != null);
        EditorAskInlayCard card = presenter.cardOf(editor, rec.id());
        EditorFactory.getInstance().releaseEditor(editor);
        settle();
        model.recorded(ROOT, rec.answered("late", Instant.parse("2030-01-02T03:05:00Z")));
        settle();
        assertTrue(card.isDisposed());
        assertEquals("Waiting for the agent's answer…", card.bodyText());
        assertNull(presenter.cardOf(editor, rec.id()));
    }

    public void testCloseReleasesCardsMarkersAndListenersAndKeepsTheHistory() throws Exception {
        Editor editor = editor(TEXT);
        EditorAskRecord rec = answeredRecord(editor.getDocument(), START, END, "kept");
        model.recorded(ROOT, rec);
        startPresenter();
        waitFor(() -> presenter.cardOf(editor, rec.id()) != null);
        EditorAskInlayCard card = presenter.cardOf(editor, rec.id());
        presenter.close();
        presenter.flush(5, TimeUnit.SECONDS);
        assertTrue(card.isDisposed());
        assertEquals(0, presenter.attachedEditors());
        assertEquals(0, editor.getInlayModel().getBlockElementsInRange(0, TEXT.length()).size());
        assertEquals("kept", model.rows(ROOT).get(0).record().answer());
        assertEquals(rec.id(), model.rows(ROOT).get(0).id());
        presenter.close(); // idempotent
    }

    public void testLiveCaptureMarkerIsAdoptedAndFollowsTheSelection() throws Exception {
        Editor editor = editor(TEXT);
        startPresenter();
        waitFor(() -> presenter.attachedEditors() == 1);
        editor.getSelectionModel().setSelection(START, END);
        EditorAskCapture.Snapshot snap = EditorAskCapture.capture(editor);
        String id = EditorAskRecord.newId();
        ID.set(id);
        assertTrue(presenter.adopt(editor, snap, new Location(ROOT, FILE), id));
        EditorAskRecord rec = record(editor.getDocument(), START, END, Status.AWAITING, null, false);
        model.recorded(ROOT, rec);
        waitFor(() -> presenter.cardOf(editor, id) != null);
        assertEquals(1, presenter.cardLineOf(editor, id));
        ID.remove();
    }
}
