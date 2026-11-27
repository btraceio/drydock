package app.drydock.intellij.editorqa;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/** The lifecycle observer: existing editors, create/release, eligibility, de-duplication and close. */
public class EditorAskEditorObserverTest extends BasePlatformTestCase {

    private final List<Editor> editors = new ArrayList<>();
    private final List<Path> cleanup = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private EditorAskEditorObserver.Listener platformListener;
    private boolean unregistered;
    private List<Editor> existing = List.of();

    @Override
    protected void tearDown() throws Exception {
        try {
            for (Editor e : editors) {
                EditorFactory.getInstance().releaseEditor(e);
            }
        } finally {
            for (Path p : cleanup) {
                Files.deleteIfExists(p);
            }
            super.tearDown();
        }
    }

    private EditorAskEditorObserver observer() {
        EditorAskEditorObserver.Sources sources = new EditorAskEditorObserver.Sources() {
            @Override public List<Editor> editors() { return existing; }

            @Override public Runnable register(EditorAskEditorObserver.Listener listener, Disposable parent) {
                platformListener = listener;
                return () -> unregistered = true;
            }
        };
        EditorAskEditorObserver observer = new EditorAskEditorObserver(getProject(), sources,
                new EditorAskEditorObserver.Callback() {
                    @Override public void attached(Editor editor) { events.add("attached:" + editors.indexOf(editor)); }

                    @Override public void released(Editor editor) { events.add("released:" + editors.indexOf(editor)); }
                }, Runnable::run);
        observer.start(getTestRootDisposable());
        return observer;
    }

    private Editor local(EditorKind kind) throws IOException {
        Path dir = Files.createTempDirectory("drydock-observer").toRealPath();
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), dir.toString());
        Path file = dir.resolve("O.java");
        Files.writeString(file, "x\n");
        cleanup.add(file);
        cleanup.add(dir);
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        Editor e = EditorFactory.getInstance().createEditor(doc, getProject(), kind);
        editors.add(e);
        return e;
    }

    public void testExistingNormalEditorsAreAttachedAndOthersAreNot() throws Exception {
        Editor normal = local(EditorKind.MAIN_EDITOR);
        Editor console = local(EditorKind.CONSOLE);
        Editor diff = local(EditorKind.DIFF);
        existing = List.of(normal, console, diff);
        observer();
        assertEquals(List.of("attached:0"), events);
    }

    public void testCreateAndReleaseAreFollowedOnceEach() throws Exception {
        observer();
        Editor e = local(EditorKind.MAIN_EDITOR);
        platformListener.editorCreated(e);
        platformListener.editorCreated(e); // duplicate create
        platformListener.editorReleased(e);
        platformListener.editorReleased(e); // duplicate release
        assertEquals(List.of("attached:0", "released:0"), events);
    }

    public void testAnEditorSeenByEnumerationAndEventIsAttachedOnce() throws Exception {
        Editor e = local(EditorKind.MAIN_EDITOR);
        existing = List.of(e);
        observer();
        platformListener.editorCreated(e);
        assertEquals(List.of("attached:0"), events);
    }

    public void testNonLocalOrInMemoryOrOtherProjectEditorsNeverQualify() throws Exception {
        Editor temp = EditorFactory.getInstance().createEditor(
                myFixture.configureByText("T.txt", "t").getViewProvider().getDocument(),
                getProject(), EditorKind.MAIN_EDITOR);
        editors.add(temp);
        observer();
        platformListener.editorCreated(temp);
        assertTrue(events.isEmpty());
    }

    public void testCloseUnregistersReleasesAttachedAndDropsLaterEvents() throws Exception {
        Editor a = local(EditorKind.MAIN_EDITOR);
        existing = List.of(a);
        EditorAskEditorObserver observer = observer();
        observer.close();
        assertTrue(unregistered);
        assertEquals(List.of("attached:0", "released:0"), events);
        platformListener.editorCreated(local(EditorKind.MAIN_EDITOR));
        observer.close();
        assertEquals(2, events.size());
    }
}
