package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.intellij.review.ReviewEditorHighlighters.GroupMark;
import app.drydock.intellij.review.ReviewEditorHighlighters.SourceEditor;
import app.drydock.intellij.review.ReviewEditorHighlighters.SourceEditors;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;

import org.junit.jupiter.api.Test;

import java.awt.geom.AffineTransform;
import java.awt.Color;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import javax.swing.Icon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless T11 tests for the source-editor gutter layer (AC5/AC7's editor
 * half): the project/path/line filters, painting existing and later-created
 * editors, the grouped mark's content and its click's navigation target,
 * idempotent replacement with explicit removal of every previous
 * highlighter, the disposal and listener lifecycle (including teardown via
 * the project-owned parent), release-failure isolation, decoration-failure
 * isolation (a throwing or null addGroupMark), the gutter badge's
 * paint-state discipline, and the EDT-only construction contract — all
 * over injected editor/markup/navigation seams, no live IDE.
 */
class ReviewEditorHighlightersTest {

    private static final Instant AT = Instant.EPOCH;
    private static final String SCOPE = "s1";

    // ---- fixtures -----------------------------------------------------------

    private static UnifiedDiff.Line ctx(int oldLine, int newLine) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT,
                OptionalInt.of(oldLine), OptionalInt.of(newLine), "    context " + newLine);
    }

    private static UnifiedDiff.Line add(int newLine) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                OptionalInt.empty(), OptionalInt.of(newLine), "+   added " + newLine);
    }

    private static UnifiedDiff.FileDiff file(String path) {
        return new UnifiedDiff.FileDiff(path, "M", 1, 1, false, false, List.of(new UnifiedDiff.Hunk(
                "@@ -1,4 +1,4 @@", List.of(ctx(1, 1), add(2), ctx(3, 3), ctx(4, 4)))));
    }

    private static ReviewAnnotation finding(String id, String file, String startKey, Severity severity) {
        return new ReviewAnnotation(SCOPE, id, file, startKey, startKey, severity,
                Confidence.HIGH, Optional.of("Title " + id), "tester", AT,
                List.of(), Optional.empty(), Optional.empty(), List.of(), List.of(),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false, Triage.PROPOSED,
                Optional.empty());
    }

    /** Two marked files: A has a two-finding group at source line 1 and one at 3; B has one at 0. */
    private static ReviewEditorMarkModel model() {
        return ReviewEditorMarkModel.of(
                new UnifiedDiff(List.of(file("src/A.java"), file("src/B.java"))),
                List.of(finding("f1", "src/A.java", "n2", Severity.NIT),
                        finding("f2", "src/A.java", "n2", Severity.BLOCKING),
                        finding("f3", "src/A.java", "n4", Severity.QUESTION),
                        finding("f4", "src/B.java", "n1", Severity.DEVIATION)));
    }

    private static ReviewEditorMarkModel emptyModel() {
        return ReviewEditorMarkModel.of(new UnifiedDiff(List.of(file("src/A.java"))), List.of());
    }

    /** A live {@link Project} stand-in: user data and the identity the project filter reads. */
    private static Project project(String basePath) {
        Map<Key<?>, Object> userData = new HashMap<>();
        return (Project) java.lang.reflect.Proxy.newProxyInstance(
                ReviewEditorHighlightersTest.class.getClassLoader(),
                new Class<?>[] {Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBasePath" -> basePath;
                    case "isDisposed" -> false;
                    case "putUserData" -> {
                        userData.put((Key<?>) args[0], args[1]);
                        yield null;
                    }
                    case "getUserData" -> userData.get(args[0]);
                    case "toString" -> "project@" + basePath;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args[0] == proxy;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return (byte) 0;
    }

    /** The EDT queue fake most tests use: an entry accepted off the EDT runs at once on the caller's thread. */
    private static final class DirectEdt implements ReviewDiffInlays.EdtQueue {
        @Override public void invokeLater(Runnable runnable) {
            runnable.run();
        }
    }

    /** The EDT queue fake the thread tests use: entries wait for an explicit pump. */
    private static final class QueuingEdt implements ReviewDiffInlays.EdtQueue {
        final List<Runnable> queued = new ArrayList<>();

        @Override public void invokeLater(Runnable runnable) {
            queued.add(runnable);
        }

        void pump() {
            List<Runnable> run = List.copyOf(queued);
            queued.clear();
            run.forEach(Runnable::run);
        }
    }

    /**
     * One highlighter handle: {@code released} records the removal attempt —
     * set before a failure, so a throwing release still counts as attempted.
     */
    private static final class FakeOwned implements ReviewDiffInlays.Owned {
        boolean released;
        boolean throwOnRelease;

        @Override public void release() {
            released = true;
            if (throwOnRelease) {
                throw new IllegalStateException("the highlighter was already gone");
            }
        }
    }

    /** One painted group: the line, the mark's content, and the handle that removes it. */
    private record Marked(int line, GroupMark mark, FakeOwned owned) { }

    private static class FakeEditor implements SourceEditor {
        final Project project;
        final String filePath;
        final boolean directory;
        final int lineCount;
        final List<Marked> marks = new ArrayList<>();

        FakeEditor(Project project, String filePath, boolean directory, int lineCount) {
            this.project = project;
            this.filePath = filePath;
            this.directory = directory;
            this.lineCount = lineCount;
        }

        FakeEditor(Project project, String filePath) {
            this(project, filePath, false, 100);
        }

        @Override public Project project() { return project; }

        @Override public String filePath() { return filePath; }

        @Override public boolean isDirectory() { return directory; }

        @Override public int lineCount() { return lineCount; }

        @Override public FakeOwned addGroupMark(int line, GroupMark mark) {
            FakeOwned owned = new FakeOwned();
            marks.add(new Marked(line, mark, owned));
            return owned;
        }

        /** The marks whose highlighters are still in place. */
        List<Marked> live() {
            return marks.stream().filter(marked -> !marked.owned().released).toList();
        }
    }

    private static final class FakeSources implements SourceEditors {
        final List<FakeEditor> editors = new ArrayList<>();
        final List<ReviewEditorHighlighters.Listener> listeners = new ArrayList<>();

        @Override public List<SourceEditor> editors() {
            return List.copyOf(editors);
        }

        @Override public ReviewDiffInlays.Owned register(ReviewEditorHighlighters.Listener listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        void created(FakeEditor editor) {
            editors.add(editor);
            listeners.forEach(listener -> listener.editorCreated(editor));
        }

        void released(FakeEditor editor) {
            editors.remove(editor);
            listeners.forEach(listener -> listener.editorReleased(editor));
        }
    }

    private static final class FlakyEditor extends FakeEditor {
        /** Group lines whose addGroupMark throws (renderer setup failing after the highlighter exists). */
        final Set<Integer> failLines;
        /** Return null instead of a handle: nothing to bookkeep, nothing to release. */
        final boolean nullHandles;
        /** Simulates an editor whose document blows up on inspection, before any mark. */
        boolean throwOnLineCount;

        FlakyEditor(Project project, String filePath, Set<Integer> failLines, boolean nullHandles) {
            super(project, filePath);
            this.failLines = failLines;
            this.nullHandles = nullHandles;
        }

        @Override public int lineCount() {
            if (throwOnLineCount) {
                throw new IllegalStateException("the document went away");
            }
            return super.lineCount();
        }

        @Override public FakeOwned addGroupMark(int line, GroupMark mark) {
            if (failLines.contains(line)) {
                throw new IllegalStateException("renderer setup failed for line " + line);
            }
            return nullHandles ? null : super.addGroupMark(line, mark);
        }
    }

    private static final class RecordingNavigation implements ReviewEditorHighlighters.Navigation {
        final List<String> calls = new ArrayList<>();

        @Override public void openFinding(Project project, String filePath, String findingId) {
            calls.add(filePath + "|" + findingId);
        }
    }

    private static final class Layer {
        final FakeSources sources = new FakeSources();
        final RecordingNavigation navigation = new RecordingNavigation();
        final Project project = project("/repo");
        ReviewEditorHighlighters highlighters;

        Layer(ReviewDiffInlays.EdtQueue edt, Disposable parent) {
            this(edt, parent, Path.of("/repo"));
        }

        Layer(ReviewDiffInlays.EdtQueue edt, Disposable parent, Path checkout) {
            // The layer is constructed and registered on the EDT (its
            // contract); every test therefore builds it there.
            onEdt(() -> highlighters = new ReviewEditorHighlighters(project, checkout, parent,
                    edt, sources, navigation));
        }

        void apply(ReviewEditorMarkModel model) {
            highlighters.applyModel(model);
        }
    }

    private static Layer layer() {
        return new Layer(new DirectEdt(), Disposer.newDisposable());
    }

    // ---- existing editors and the project/path/line filters -------------------

    @Test
    void appliesTheSnapshotToExistingEditorsOfThisProjectOnly() {
        Layer layer = layer();
        FakeEditor ours = new FakeEditor(layer.project, "/repo/src/A.java");
        FakeEditor theirs = new FakeEditor(project("/other"), "/other/src/A.java");
        layer.sources.editors.addAll(List.of(ours, theirs));

        layer.apply(model());

        assertEquals(2, ours.marks.size());
        assertTrue(theirs.marks.isEmpty());
        Marked first = ours.marks.get(0);
        assertEquals(1, first.line());
        assertEquals(Severity.BLOCKING, first.mark().severity());
        assertEquals(2, first.mark().count());
        assertEquals(model().forPath("src/A.java").orElseThrow().byLine().get(1).tooltip(),
                first.mark().tooltip());
        assertEquals(3, ours.marks.get(1).line());
    }

    @Test
    void rejectsFilelessDirectoryOutsideAndUnmarkedEditors() {
        Layer layer = layer();
        FakeEditor noFile = new FakeEditor(layer.project, null, false, 100);
        FakeEditor directory = new FakeEditor(layer.project, "/repo/src", true, 100);
        FakeEditor outside = new FakeEditor(layer.project, "/elsewhere/src/A.java", false, 100);
        FakeEditor unmarked = new FakeEditor(layer.project, "/repo/src/C.java", false, 100);
        layer.sources.editors.addAll(List.of(noFile, directory, outside, unmarked));

        layer.apply(model());

        assertTrue(noFile.marks.isEmpty());
        assertTrue(directory.marks.isEmpty());
        assertTrue(outside.marks.isEmpty());
        assertTrue(unmarked.marks.isEmpty());
    }

    @Test
    void aGroupBeyondTheDocumentLineCountGetsNoMark() {
        Layer layer = layer();
        FakeEditor shortDocument = new FakeEditor(layer.project, "/repo/src/A.java", false, 2);
        layer.sources.editors.add(shortDocument);

        layer.apply(model());

        // Line 1 fits a two-line document; line 3 does not — never a guess.
        assertEquals(1, shortDocument.marks.size());
        assertEquals(1, shortDocument.marks.get(0).line());
    }

    // ---- editors created later -------------------------------------------------

    @Test
    void editorsCreatedLaterGetTheCurrentSnapshot() {
        Layer layer = layer();
        layer.apply(model());
        FakeEditor late = new FakeEditor(layer.project, "/repo/src/B.java");
        assertEquals(1, layer.sources.listeners.size());

        layer.sources.created(late);

        assertEquals(1, late.marks.size());
        assertEquals(0, late.marks.get(0).line());
        assertEquals(Severity.DEVIATION, late.marks.get(0).mark().severity());
    }

    @Test
    void anEditorCreatedBeforeTheFirstSnapshotStaysEmptyUntilItArrives() {
        Layer layer = layer();
        FakeEditor early = new FakeEditor(layer.project, "/repo/src/A.java");
        layer.sources.created(early);

        assertTrue(early.marks.isEmpty());
        layer.apply(model());
        assertEquals(2, early.marks.size());
    }

    // ---- the grouped click's navigation ----------------------------------------

    @Test
    void aGroupClickNavigatesToTheGroupsFirstFindingInStoreOrder() {
        Layer layer = layer();
        FakeEditor ours = new FakeEditor(layer.project, "/repo/src/A.java");
        layer.sources.editors.add(ours);
        layer.apply(model());

        ours.marks.get(0).mark().click().run();
        ours.marks.get(1).mark().click().run();

        assertEquals(List.of("src/A.java|f1", "src/A.java|f3"), layer.navigation.calls);
    }

    // ---- idempotent replacement --------------------------------------------------

    @Test
    void reapplyingReplacesEveryOldHighlighterAndLeavesOneLiveMarkPerGroup() {
        Layer layer = layer();
        FakeEditor ours = new FakeEditor(layer.project, "/repo/src/A.java");
        layer.sources.editors.add(ours);
        layer.apply(model());
        List<Marked> first = List.copyOf(ours.marks);

        layer.apply(model());

        assertTrue(first.stream().allMatch(marked -> marked.owned().released));
        assertEquals(2, ours.live().size());
        assertEquals(List.of(1, 3), ours.live().stream().map(Marked::line).toList());
    }

    @Test
    void anEmptySnapshotRemovesEveryMarkButTheEditorStaysPaintable() {
        Layer layer = layer();
        FakeEditor ours = new FakeEditor(layer.project, "/repo/src/A.java");
        layer.sources.editors.add(ours);
        layer.apply(model());

        layer.apply(emptyModel());

        assertTrue(ours.live().isEmpty());
        layer.apply(model());
        assertEquals(2, ours.live().size());
    }

    // ---- disposal and the listener lifecycle ------------------------------------

    @Test
    void disposeReleasesEveryMarkUnregistersTheListenerAndIgnoresLaterEditors() {
        Layer layer = layer();
        FakeEditor ours = new FakeEditor(layer.project, "/repo/src/A.java");
        layer.sources.editors.add(ours);
        layer.apply(model());

        layer.highlighters.dispose();

        assertTrue(ours.marks.stream().allMatch(marked -> marked.owned().released));
        assertTrue(layer.sources.listeners.isEmpty());
        FakeEditor later = new FakeEditor(layer.project, "/repo/src/A.java");
        layer.sources.created(later);
        layer.apply(model());
        assertTrue(later.marks.isEmpty());
    }

    @Test
    void disposingTheProjectOwnedParentTearsTheLayerDown() {
        Disposable parent = Disposer.newDisposable();
        Layer layer = new Layer(new DirectEdt(), parent);
        FakeEditor ours = new FakeEditor(layer.project, "/repo/src/A.java");
        layer.sources.editors.add(ours);
        layer.apply(model());

        Disposer.dispose(parent);

        assertTrue(ours.marks.stream().allMatch(marked -> marked.owned().released));
        assertTrue(layer.sources.listeners.isEmpty());
        FakeEditor later = new FakeEditor(layer.project, "/repo/src/A.java");
        layer.sources.created(later);
        assertTrue(later.marks.isEmpty());
    }

    @Test
    void aReleasedEditorForgetsItsHandlesWithoutTouchingTheOthers() {
        Layer layer = layer();
        FakeEditor a = new FakeEditor(layer.project, "/repo/src/A.java");
        FakeEditor b = new FakeEditor(layer.project, "/repo/src/B.java");
        layer.sources.editors.addAll(List.of(a, b));
        layer.apply(model());

        layer.sources.released(a);

        assertTrue(a.marks.stream().allMatch(marked -> marked.owned().released));
        assertEquals(1, b.live().size());
        layer.apply(model());
        assertEquals(1, b.live().size());
        assertEquals(2, b.marks.size()); // the released handle and its replacement
    }

    // ---- release-failure isolation -----------------------------------------------

    @Test
    void oneFailingReleaseNeverStrandsTheRest() {
        Layer layer = layer();
        FakeEditor ours = new FakeEditor(layer.project, "/repo/src/A.java");
        layer.sources.editors.add(ours);
        layer.apply(model());
        ours.marks.get(0).owned().throwOnRelease = true;

        layer.apply(model());

        // The failing removal was attempted (and logged), the sibling old
        // handle still released, and the replacement completed.
        assertTrue(ours.marks.get(0).owned().released);
        assertEquals(2, ours.live().size());
        ours.live().get(0).owned().throwOnRelease = true;

        layer.highlighters.dispose();

        assertTrue(ours.marks.stream().allMatch(marked -> marked.owned().released));
        assertTrue(ours.live().isEmpty());
    }

    // ---- decoration-failure isolation ---------------------------------------------

    @Test
    void aThrowingAddSkipsOnlyThatGroupWhileSiblingsAndBookkeepingSurvive() {
        Layer layer = layer();
        // A.java's line-3 group fails after the line-1 mark was added.
        FlakyEditor flaky = new FlakyEditor(layer.project, "/repo/src/A.java", Set.of(3), false);
        FakeEditor sibling = new FakeEditor(layer.project, "/repo/src/B.java");
        layer.sources.editors.addAll(List.of(flaky, sibling));

        layer.apply(model());

        assertEquals(1, flaky.live().size());
        assertEquals(1, flaky.live().get(0).line());
        // the sibling editor was still painted in full
        assertEquals(1, sibling.live().size());
        assertEquals(0, sibling.live().get(0).line());

        // the successful mark stayed tracked: a later apply releases it
        layer.apply(model());
        assertTrue(flaky.marks.get(0).owned().released);
        assertEquals(2, flaky.marks.size()); // the released handle and its replacement
        assertEquals(1, flaky.live().size());
        assertEquals(1, flaky.live().get(0).line());
    }

    @Test
    void aFailingEditorNeverStarvesItsSiblings() {
        Layer layer = layer();
        FlakyEditor broken = new FlakyEditor(layer.project, "/repo/src/A.java", Set.of(), false);
        broken.throwOnLineCount = true;
        FakeEditor healthy = new FakeEditor(layer.project, "/repo/src/B.java");
        layer.sources.editors.addAll(List.of(broken, healthy));

        layer.apply(model());

        assertTrue(broken.marks.isEmpty());
        assertEquals(1, healthy.live().size());
        assertEquals(0, healthy.live().get(0).line());
    }

    @Test
    void aNullHandleIsNotBookkeptAndLaterDisposalStaysClean() {
        Layer layer = layer();
        FlakyEditor nulls = new FlakyEditor(layer.project, "/repo/src/A.java", Set.of(), true);
        layer.sources.editors.add(nulls);

        layer.apply(model());

        assertTrue(nulls.marks.isEmpty()); // nothing bookkept, nothing to release
        layer.highlighters.dispose();      // releasing an empty list must not fail
        layer.apply(model());              // and the layer stays usable for a re-apply
    }

    @Test
    void aRedundantCheckoutPathIsNormalizedBeforeMapping() {
        Layer layer = new Layer(new DirectEdt(), Disposer.newDisposable(), Path.of("/x/../repo"));
        FakeEditor ours = new FakeEditor(layer.project, "/repo/src/A.java");
        layer.sources.editors.add(ours);

        layer.apply(model());

        assertEquals(2, ours.live().size());
    }

    // ---- the hop-to-EDT contract ---------------------------------------------------

    @Test
    void offEdtEntriesHopThroughTheQueueAndDoNothingBeforeItRuns() {
        QueuingEdt edt = new QueuingEdt();
        Layer layer = new Layer(edt, Disposer.newDisposable());
        FakeEditor ours = new FakeEditor(layer.project, "/repo/src/A.java");
        layer.sources.editors.add(ours);

        layer.apply(model());

        assertTrue(ours.marks.isEmpty());
        edt.pump();
        assertEquals(2, ours.live().size());
    }

    @Test
    void anOffEdtListenerCallbackWaitsForTheQueueToo() {
        QueuingEdt edt = new QueuingEdt();
        Layer layer = new Layer(edt, Disposer.newDisposable());
        layer.apply(model());
        FakeEditor late = new FakeEditor(layer.project, "/repo/src/B.java");

        layer.sources.created(late);

        assertTrue(late.marks.isEmpty());
        edt.pump();
        assertEquals(1, late.live().size());
    }

    @Test
    void onEdtEntriesRunInlineWithoutTheQueue() throws Exception {
        QueuingEdt edt = new QueuingEdt();
        Layer layer = new Layer(edt, Disposer.newDisposable());
        FakeEditor ours = new FakeEditor(layer.project, "/repo/src/A.java");
        layer.sources.editors.add(ours);

        onEdt(() -> layer.apply(model()));

        assertTrue(edt.queued.isEmpty());
        assertEquals(2, ours.live().size());
    }

    @Test
    void constructingOffTheEdtIsRejectedWithoutRegisteringAnything() {
        FakeSources sources = new FakeSources();

        // Runs on the test thread — off the EDT the construction must fail
        // before any disposer bookkeeping or listener registration happens.
        assertThrows(IllegalStateException.class, () -> new ReviewEditorHighlighters(
                project("/repo"), Path.of("/repo"), Disposer.newDisposable(),
                new DirectEdt(), sources, new RecordingNavigation()));

        assertTrue(sources.listeners.isEmpty());
    }

    // ---- the gutter badge's paint-state discipline ---------------------------------

    @Test
    void theBadgePaintsThroughItsOwnGraphicsLeavingTheCallersUntouched() {
        Icon base = new Icon() {
            @Override public void paintIcon(Component context, Graphics graphics, int x, int y) {
                graphics.fillRect(x, y, getIconWidth(), getIconHeight());
            }

            @Override public int getIconWidth() { return 16; }

            @Override public int getIconHeight() { return 16; }
        };
        ReviewEditorHighlighters.CountBadgeIcon badge = new ReviewEditorHighlighters.CountBadgeIcon(base, 7);
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        Font font = new Font(Font.MONOSPACED, Font.ITALIC, 12);
        graphics.setFont(font);
        graphics.setColor(Color.MAGENTA);
        AffineTransform transform = AffineTransform.getScaleInstance(2.0, 3.0);
        graphics.setTransform(transform);
        graphics.setClip(new Rectangle(2, 3, 50, 50));

        badge.paintIcon(null, graphics, 8, 8);

        assertEquals(font, graphics.getFont());
        assertEquals(Color.MAGENTA, graphics.getColor());
        assertEquals(transform, graphics.getTransform());
        assertEquals(new Rectangle(2, 3, 50, 50), graphics.getClip());
        assertTrue(hasInk(image)); // the badge really painted underneath
        graphics.dispose();
    }

    @Test
    void aBadgeWithoutABaseIconIsRejected() {
        Icon base = new Icon() {
            @Override public void paintIcon(Component context, Graphics graphics, int x, int y) { }

            @Override public int getIconWidth() { return 1; }

            @Override public int getIconHeight() { return 1; }
        };

        assertThrows(NullPointerException.class,
                () -> new ReviewEditorHighlighters.CountBadgeIcon(null, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new ReviewEditorHighlighters.CountBadgeIcon(base, 1));
    }

    private static boolean hasInk(BufferedImage image) {
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (image.getRGB(x, y) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- helpers --------------------------------------------------------------

    private static void onEdt(Runnable op) {
        if (EventQueue.isDispatchThread()) {
            op.run();
            return;
        }
        try {
            EventQueue.invokeAndWait(op);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the EDT", e);
        } catch (InvocationTargetException e) {
            throw new AssertionError("the EDT runnable failed", e.getCause());
        }
    }
}
