package app.drydock.intellij.editorqa;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.function.Function;
import java.util.function.Supplier;

import com.intellij.ide.scratch.ScratchRootType;
import com.intellij.injected.editor.EditorWindow;
import com.intellij.lang.Language;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import app.drydock.editorqa.EditorAskRecord.TextRange;

/**
 * Editor capture, eligibility, the resolver and the popup action's flow.
 * Local files are real temp files (the light fixture's own files are
 * {@code temp://}, which the action rightly refuses).
 */
public class EditorAskActionTest extends BasePlatformTestCase {

    private final List<Path> cleanup = new ArrayList<>();
    private final List<Editor> editors = new ArrayList<>();
    private final ExecutorService background = Executors.newCachedThreadPool();

    @Override
    protected void tearDown() throws Exception {
        try {
            for (Editor editor : editors) {
                EditorFactory.getInstance().releaseEditor(editor);
            }
            background.shutdownNow();
        } finally {
            for (Path dir : cleanup) {
                deleteTree(dir);
            }
            super.tearDown();
        }
    }

    // ---- registration and presentation ----

    public void testActionIsInTheOrdinaryEditorPopupAndNotTheDiffPopup() {
        ActionManager actions = ActionManager.getInstance();
        assertNotNull(actions.getAction("Drydock.EditorAsk.Ask"));
        assertTrue(groupHas("EditorPopupMenu", "Drydock.EditorAsk.Ask"));
        assertFalse(groupHas("Diff.EditorPopupMenu", "Drydock.EditorAsk.Ask"));
    }

    public void testPresentationGivesAReasonWhenDisabled() throws Exception {
        Editor editor = localEditor("alpha\nbeta\n");
        EditorAskAction action = new EditorAskAction();

        assertTrue(presentation(action, editor).description().contains("not available yet"));
        EditorAskAction.Dispatcher dispatcher = request -> { };
        EditorAskAction.install(dispatcher);
        try {
            Presentation none = presentation(action, editor);
            assertFalse(none.enabled());
            assertTrue(none.description(), none.description().contains("Select"));

            editor.getSelectionModel().setSelection(2, 8);
            Presentation selected = presentation(action, editor);
            assertTrue(selected.visible());
            assertTrue(selected.enabled());

            Editor console = EditorFactory.getInstance().createEditor(editor.getDocument(), getProject(),
                    EditorKind.CONSOLE);
            editors.add(console);
            console.getSelectionModel().setSelection(0, 3);
            assertFalse("a console editor is not offered the action", presentation(action, console).visible());
        } finally {
            EditorAskAction.uninstall(dispatcher);
        }
    }

    // ---- eligibility ----

    public void testEligibilityRefusesEveryNonOrdinaryContext() throws Exception {
        Editor editor = localEditor("alpha\nbeta\n");
        editor.getSelectionModel().setSelection(0, 5);
        assertTrue(EditorAskCapture.refusal(editor, false).isEmpty());
        assertTrue("diff data context", EditorAskCapture.refusal(editor, true).isPresent());

        for (EditorKind kind : List.of(EditorKind.DIFF, EditorKind.CONSOLE, EditorKind.PREVIEW)) {
            Editor other = EditorFactory.getInstance().createEditor(editor.getDocument(), getProject(), kind);
            editors.add(other);
            other.getSelectionModel().setSelection(0, 5);
            assertTrue(kind.toString(), EditorAskCapture.refusal(other, false).isPresent());
        }

        Editor injected = (Editor) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {EditorWindow.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getEditorKind" -> EditorKind.MAIN_EDITOR;
                    case "getDocument" -> editor.getDocument();
                    case "getCaretModel" -> editor.getCaretModel();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        assertTrue(EditorAskCapture.refusal(injected, false).orElseThrow().contains("injected"));

        Editor lightTemp = EditorFactory.getInstance().createEditor(
                myFixture.configureByText("T.txt", "temp file").getViewProvider().getDocument(),
                getProject(), EditorKind.MAIN_EDITOR);
        editors.add(lightTemp);
        lightTemp.getSelectionModel().setSelection(0, 4);
        assertTrue("non-local file", EditorAskCapture.refusal(lightTemp, false).isPresent());

        Editor virtual = EditorFactory.getInstance().createEditor(FileDocumentManager.getInstance().getDocument(
                new LightVirtualFile("V.txt", "virtual text")), getProject(), EditorKind.MAIN_EDITOR);
        editors.add(virtual);
        virtual.getSelectionModel().setSelection(0, 4);
        assertTrue("in-memory file", EditorAskCapture.refusal(virtual, false).isPresent());

        Editor scratch = scratchEditor();
        scratch.getSelectionModel().setSelection(0, 3);
        assertTrue(EditorAskCapture.refusal(scratch, false).orElseThrow().contains("scratch"));
    }

    // ---- capture ----

    public void testCaptureFreezesTheUnsavedPartialMultilineSelectionAgainstLaterChange() throws Exception {
        Path file = temp("cap").resolve("A.java");
        Files.writeString(file, "on disk\n");
        Editor editor = editorFor(file);
        Document document = editor.getDocument();
        WriteCommandAction.runWriteCommandAction(getProject(),
                () -> document.setText("first line\nsecond line\nthird\n"));
        assertTrue("unsaved", FileDocumentManager.getInstance().isDocumentUnsaved(document));
        editor.getSelectionModel().setSelection(6, 18); // "line\nsecond " -- partial lines, spans a break

        EditorAskCapture.Snapshot snapshot = EditorAskCapture.capture(editor);

        assertEquals("line\nsecond ", snapshot.selectedText());
        assertEquals(new TextRange(6, 18, 0, 6, 1, 7), snapshot.range());
        assertEquals(file.toRealPath(), snapshot.file().toRealPath());
        // later change: edit above, move the caret, rewrite disk
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(0, "XX"));
        editor.getCaretModel().moveToOffset(0);
        Files.writeString(file, "completely different\n");
        assertEquals("line\nsecond ", snapshot.selectedText());
        assertEquals("first line\nsecond line\nthird\n", snapshot.document().toString());
        assertTrue("the marker followed the edit", snapshot.marker().isValid());
        assertEquals(8, snapshot.marker().getStartOffset());
        assertFalse("non-greedy left", snapshot.marker().isGreedyToLeft());
        assertFalse("non-greedy right", snapshot.marker().isGreedyToRight());
        snapshot.release();
        assertFalse(snapshot.marker().isValid());
    }

    public void testSelectionEndingAtALineStartHasColumnZeroOnTheNextLine() throws Exception {
        Editor editor = localEditor("ab\ncd\n");
        editor.getSelectionModel().setSelection(1, 3);
        EditorAskCapture.Snapshot snapshot = EditorAskCapture.capture(editor);
        assertEquals(new TextRange(1, 3, 0, 1, 1, 0), snapshot.range());
        snapshot.release();
    }

    public void testCaptureRejectsAnOverlongSelectionInsteadOfClipping() throws Exception {
        Editor editor = localEditor("x".repeat(20_000));
        editor.getSelectionModel().setSelection(0, 20_000);
        assertTrue(EditorAskCapture.refusal(editor, false).orElseThrow().contains("longer"));
    }

    // ---- the flow ----

    public void testAnAcceptedQuestionDispatchesTheFrozenSelectionNotTheCurrentOne() throws Exception {
        Editor editor = localEditor("alpha\nbeta\n");
        editor.getSelectionModel().setSelection(0, 5);
        Fixture fx = new Fixture();
        fx.prompter = (project, capture, checkout) -> {
            editor.getSelectionModel().setSelection(6, 10); // the user changed it while the dialog was open
            editor.getCaretModel().moveToOffset(1);
            return Optional.of("What does this do?");
        };

        EditorAskAction.begin(getProject(), editor, false, fx.wiring(resolved("a/A.java")));

        assertEquals(1, fx.dispatched.size());
        EditorAskAction.Request request = fx.dispatched.get(0);
        assertEquals("alpha", request.capture().selectedText());
        assertEquals("What does this do?", request.question());
        assertEquals("a/A.java", request.checkout().relativePath());
        assertTrue("the dispatcher owns the marker", request.capture().marker().isValid());
        assertTrue("busy flag cleared", fx.canBeginAgain(editor));
    }

    public void testEveryNonDispatchingPathReleasesAndDispatchesNothing() throws Exception {
        for (String answer : new String[] {null, "", "   \n", "q".repeat(3_000)}) {
            Editor editor = localEditor("alpha\nbeta\n");
            editor.getSelectionModel().setSelection(0, 5);
            Fixture fx = new Fixture();
            fx.prompter = (project, capture, checkout) -> {
                fx.captured = capture;
                return Optional.ofNullable(answer);
            };
            EditorAskAction.begin(getProject(), editor, false, fx.wiring(resolved("A.java")));
            assertTrue(String.valueOf(answer), fx.dispatched.isEmpty());
            assertFalse("marker released", fx.captured.marker().isValid());
            assertTrue(fx.canBeginAgain(editor));
        }
    }

    public void testUnresolvableOwnersNeverReachTheQuestion() throws Exception {
        for (String reason : List.of("The file is not in a Git working tree", "git did not answer")) {
            Editor editor = localEditor("alpha\n");
            editor.getSelectionModel().setSelection(0, 5);
            Fixture fx = new Fixture();
            EditorAskAction.begin(getProject(), editor, false,
                    fx.wiring(new EditorAskCheckoutResolver.Refused(reason)));
            assertEquals(0, fx.prompts.get());
            assertTrue(fx.dispatched.isEmpty());
            assertEquals(List.of(reason), fx.reasons);
            assertTrue(fx.canBeginAgain(editor));
        }
    }

    public void testIneligibleInvocationOpensNothingAndCapturesNothing() throws Exception {
        Editor editor = localEditor("alpha\n"); // no selection
        Fixture fx = new Fixture();
        EditorAskAction.begin(getProject(), editor, false, fx.wiring(resolved("A.java")));
        editor.getSelectionModel().setSelection(0, 5);
        EditorAskAction.begin(getProject(), editor, true, fx.wiring(resolved("A.java")));
        fx.dispatcherInstalled = false;
        EditorAskAction.begin(getProject(), editor, false, fx.wiring(resolved("A.java")));
        assertEquals(0, fx.launches.get());
        assertEquals(3, fx.reasons.size());
        assertTrue(fx.dispatched.isEmpty());
    }

    public void testBlockedGitKeepsTheEdtResponsiveAndShowsProgressAtOnce() throws Exception {
        Editor editor = localEditor("alpha\nbeta\n");
        editor.getSelectionModel().setSelection(0, 5);
        CountDownLatch gitEntered = new CountDownLatch(1);
        CountDownLatch releaseGit = new CountDownLatch(1);
        Path file = Path.of(FileDocumentManager.getInstance().getFile(editor.getDocument()).getPath());
        EditorAskCheckoutResolver blocking = new EditorAskCheckoutResolver(new EditorAskCheckoutResolver.GitProbe() {
            @Override
            public Optional<Path> topLevel(Path directory) throws IOException {
                gitEntered.countDown();
                try {
                    releaseGit.await();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                return Optional.of(directory);
            }

            @Override
            public Optional<Path> mainCheckout(Path topLevel) {
                return Optional.empty();
            }
        });
        Fixture fx = new Fixture();
        fx.launcher = (project, title, work, onEdt) -> {
            fx.launches.incrementAndGet();
            fx.progressTitle = title;
            background.execute(() -> {
                EditorAskCheckoutResolver.Outcome outcome = work.get();
                ApplicationManager.getApplication().invokeLater(() -> onEdt.accept(outcome));
            });
        };
        fx.prompter = (project, capture, checkout) -> Optional.of("why?");

        long started = System.nanoTime();
        EditorAskAction.begin(getProject(), editor, false, fx.wiring(blocking::resolve));
        assertTrue("begin returned while git is blocked", TimeUnit.NANOSECONDS.toSeconds(
                System.nanoTime() - started) < 5);
        assertTrue(gitEntered.await(10, TimeUnit.SECONDS));
        assertEquals("progress was requested at once", EditorAskAction.PROGRESS_TITLE, fx.progressTitle);
        assertTrue(fx.dispatched.isEmpty());
        assertFalse("a second click is refused while preparing", fx.canBeginAgain(editor));
        assertEquals(1, fx.launches.get());

        releaseGit.countDown();
        pump(() -> !fx.dispatched.isEmpty());
        assertEquals("why?", fx.dispatched.get(0).question());
        assertTrue(fx.canBeginAgain(editor));
        assertNotNull(file);
    }

    // ---- the resolver, against real git ----

    public void testASymlinkSpellingOfTheCheckoutIsTheSameCheckout() throws Exception {
        Path repo = repo("proj");
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/A.java"), "a\n");
        commit(repo);
        Path alias = temp("alias").resolve("alias-of-proj");
        Files.createSymbolicLink(alias, repo);

        EditorAskCheckoutResolver resolver = EditorAskCheckoutResolver.production();
        EditorAskCheckoutResolver.Resolution direct = resolved(resolver.resolve(repo.resolve("src/A.java")));
        EditorAskCheckoutResolver.Resolution viaAlias = resolved(resolver.resolve(alias.resolve("src/A.java")));

        assertEquals(direct, viaAlias);
        assertEquals(repo.toRealPath(), direct.checkout());
        assertEquals("src/A.java", direct.relativePath());
        assertFalse(direct.topLevelIsCatalogRoot());
    }

    public void testSameBasenameWorktreesAreTwoCheckoutsOfOneCatalogRoot() throws Exception {
        Path repo = repo("main-repo");
        Files.writeString(repo.resolve("F.txt"), "f\n");
        commit(repo);
        Path one = temp("w1").resolve("work");
        Path two = temp("w2").resolve("work");
        git(repo, "worktree", "add", "-b", "b1", one.toString());
        git(repo, "worktree", "add", "-b", "b2", two.toString());

        EditorAskCheckoutResolver resolver = EditorAskCheckoutResolver.production();
        EditorAskCheckoutResolver.Resolution a = resolved(resolver.resolve(one.resolve("F.txt")));
        EditorAskCheckoutResolver.Resolution b = resolved(resolver.resolve(two.resolve("F.txt")));

        assertEquals(one.toRealPath(), a.checkout());
        assertEquals(two.toRealPath(), b.checkout());
        assertFalse(a.checkout().equals(b.checkout()));
        assertEquals(repo.toRealPath(), a.catalogRoot());
        assertEquals(a.catalogRoot(), b.catalogRoot());
        assertEquals("F.txt", a.relativePath());
    }

    public void testSeparateGitDirAndUnbornHeadFallBackToTheVerifiedTopLevel() throws Exception {
        Path base = temp("sep");
        Path gitDir = base.resolve("store");
        Path work = base.resolve("work");
        Files.createDirectories(work);
        git(base, "init", "-q", "--separate-git-dir", gitDir.toString(), work.toString());
        Files.writeString(work.resolve("S.txt"), "s\n");
        commit(work);

        EditorAskCheckoutResolver.Resolution separate = resolved(
                EditorAskCheckoutResolver.production().resolve(work.resolve("S.txt")));
        assertEquals(work.toRealPath(), separate.checkout());
        assertEquals(work.toRealPath(), separate.catalogRoot());
        assertTrue(separate.topLevelIsCatalogRoot());

        Path unborn = repo("unborn");
        Files.writeString(unborn.resolve("U.txt"), "u\n");
        EditorAskCheckoutResolver.Resolution fresh = resolved(
                EditorAskCheckoutResolver.production().resolve(unborn.resolve("U.txt")));
        assertEquals(unborn.toRealPath(), fresh.catalogRoot());
    }

    public void testNoWorkingTreeAndEscapingSymlinksAreRefused() throws Exception {
        Path plain = temp("plain").resolve("P.txt");
        Files.writeString(plain, "p\n");
        assertTrue(EditorAskCheckoutResolver.production().resolve(plain) instanceof EditorAskCheckoutResolver.Refused);

        Path repo = repo("esc");
        Files.writeString(repo.resolve("In.txt"), "i\n");
        commit(repo);
        Path outside = temp("outside").resolve("Out.txt");
        Files.writeString(outside, "o\n");
        Path link = repo.resolve("Link.txt");
        Files.createSymbolicLink(link, outside);
        EditorAskCheckoutResolver.Outcome outcome = EditorAskCheckoutResolver.production().resolve(link);
        assertTrue(String.valueOf(outcome), outcome instanceof EditorAskCheckoutResolver.Refused);
    }

    public void testNonVerdictFailuresAndAmbiguousAssociationsAreRefusedNotGuessed() throws Exception {
        Path dir = temp("fake");
        Path file = dir.resolve("F.txt");
        Files.writeString(file, "f\n");
        EditorAskCheckoutResolver timeout = new EditorAskCheckoutResolver(probe(
                () -> {
                    throw new IOException("git did not answer in 20s");
                }, Optional.empty()));
        assertTrue(timeout.resolve(file) instanceof EditorAskCheckoutResolver.Refused);

        EditorAskCheckoutResolver missingMain = new EditorAskCheckoutResolver(
                probe(() -> Optional.of(dir), Optional.of(dir.resolve("gone"))));
        assertTrue(missingMain.resolve(file) instanceof EditorAskCheckoutResolver.Refused);

        EditorAskCheckoutResolver unrelatedTop = new EditorAskCheckoutResolver(
                probe(() -> Optional.of(temp("elsewhere")), Optional.empty()));
        assertTrue(unrelatedTop.resolve(file) instanceof EditorAskCheckoutResolver.Refused);
    }

    // ---- helpers ----

    private static boolean groupHas(String groupId, String actionId) {
        ActionManager actions = ActionManager.getInstance();
        DefaultActionGroup group = (DefaultActionGroup) actions.getAction(groupId);
        assertNotNull(groupId, group);
        return Arrays.stream(group.getChildActionsOrStubs()).anyMatch(child -> actionId.equals(actions.getId(child)));
    }

    private record Presentation(boolean visible, boolean enabled, String description) {
    }

    private Presentation presentation(EditorAskAction action, Editor editor) {
        AnActionEvent event = TestActionEvent.createTestEvent(action, SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject())
                .add(CommonDataKeys.EDITOR, editor).build());
        action.update(event);
        return new Presentation(event.getPresentation().isVisible(), event.getPresentation().isEnabled(),
                String.valueOf(event.getPresentation().getDescription()));
    }

    /** Collects what the flow did; defaults dispatch to a list and prompt "ok". */
    private final class Fixture {
        final List<EditorAskAction.Request> dispatched = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();
        final AtomicInteger launches = new AtomicInteger();
        final AtomicInteger prompts = new AtomicInteger();
        boolean dispatcherInstalled = true;
        volatile String progressTitle;
        EditorAskCapture.Snapshot captured;
        EditorAskAction.Launcher launcher = (project, title, work, onEdt) -> {
            launches.incrementAndGet();
            progressTitle = title;
            onEdt.accept(work.get());
        };
        EditorAskAction.Prompter prompter = (project, capture, checkout) -> Optional.of("ok");

        EditorAskAction.Wiring wiring(EditorAskCheckoutResolver.Outcome outcome) {
            return wiring(file -> outcome);
        }

        EditorAskAction.Wiring wiring(Function<Path, EditorAskCheckoutResolver.Outcome> resolver) {
            Supplier<EditorAskAction.Dispatcher> dispatcher = () -> dispatcherInstalled ? dispatched::add : null;
            return new EditorAskAction.Wiring(dispatcher, resolver, launcher,
                    (project, capture, checkout) -> {
                        prompts.incrementAndGet();
                        return prompter.ask(project, capture, checkout);
                    },
                    (editor, reason) -> reasons.add(reason));
        }

        boolean canBeginAgain(Editor editor) {
            return EditorAskAction.disabledReason(editor, false, true).filter(r -> r.contains("already")).isEmpty();
        }
    }

    private static EditorAskCheckoutResolver.Outcome resolved(String relative) {
        return new EditorAskCheckoutResolver.Resolved(new EditorAskCheckoutResolver.Resolution(
                Path.of("/repo"), Path.of("/repo"), false, relative));
    }

    private static EditorAskCheckoutResolver.Resolution resolved(EditorAskCheckoutResolver.Outcome outcome) {
        assertTrue(String.valueOf(outcome), outcome instanceof EditorAskCheckoutResolver.Resolved);
        return ((EditorAskCheckoutResolver.Resolved) outcome).resolution();
    }

    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private static EditorAskCheckoutResolver.GitProbe probe(IoSupplier<Optional<Path>> top, Optional<Path> main) {
        return new EditorAskCheckoutResolver.GitProbe() {
            @Override
            public Optional<Path> topLevel(Path directory) throws IOException {
                return top.get();
            }

            @Override
            public Optional<Path> mainCheckout(Path topLevel) {
                return main;
            }
        };
    }

    private Editor localEditor(String text) throws IOException {
        Path file = temp("ed").resolve("F.java");
        Files.writeString(file, text);
        return editorFor(file);
    }

    private Editor editorFor(Path file) {
        VirtualFile virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
        assertNotNull(virtual);
        Document document = FileDocumentManager.getInstance().getDocument(virtual);
        assertNotNull(document);
        Editor editor = EditorFactory.getInstance().createEditor(document, getProject(), EditorKind.MAIN_EDITOR);
        editors.add(editor);
        return editor;
    }

    private Editor scratchEditor() {
        VirtualFile scratch = WriteAction.computeAndWait(() -> ScratchRootType.getInstance()
                .createScratchFile(getProject(), "ask.txt", Language.ANY, "scratch text"));
        assertNotNull(scratch);
        Document document = FileDocumentManager.getInstance().getDocument(scratch);
        Editor editor = EditorFactory.getInstance().createEditor(document, getProject(), EditorKind.MAIN_EDITOR);
        editors.add(editor);
        return editor;
    }

    private void pump(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            Thread.sleep(10);
        }
        assertTrue("timed out", condition.getAsBoolean());
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }

    private Path temp(String name) throws IOException {
        Path dir = Files.createTempDirectory("drydock-ask-" + name).toRealPath();
        cleanup.add(dir);
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), dir.toString());
        return dir;
    }

    private Path repo(String name) throws IOException, InterruptedException {
        Path root = temp("repo").resolve(name);
        Files.createDirectories(root);
        git(root, "init", "-q");
        return root;
    }

    private void commit(Path root) throws IOException, InterruptedException {
        git(root, "add", "-A");
        git(root, "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false",
                "commit", "-q", "-m", "init");
    }

    private static void git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(command + "\n" + output, 0, process.waitFor());
    }
}
