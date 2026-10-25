package app.drydock.intellij.review;

import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.fragments.LineFragment;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.util.Side;

import org.junit.jupiter.api.Test;

import java.awt.EventQueue;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reusable editor's decisions (T6, spec "Review surface and diff
 * inlays"): first request seeding, in-place swap, reopen after close with
 * a disposed processor ignored, stale selection completions dropped, and
 * the EDT contract. Everything runs headless through the seams — the
 * platform plumbing ({@code DiffVirtualFile.createProcessor}) is the thin
 * bridge this state machine sits under, exercised live in the IDE gate.
 */
class ReviewDiffEditorTest {

    // ---- fixtures -----------------------------------------------------------

    private static final class RecordingOpener implements ReviewDiffEditor.TabOpener {
        int opens;

        @Override
        public void open() {
            opens++;
        }
    }

    private static final class RecordingRequests implements ReviewDiffEditor.RequestFactory {
        final List<ReviewDiffRequests.RequestContext> contexts = new ArrayList<>();
        final List<DiffRequest> built = new ArrayList<>();

        @Override
        public DiffRequest build(ReviewDiffRequests.RequestContext context) {
            contexts.add(context);
            DiffRequest request = marker(context.path());
            built.add(request);
            return request;
        }
    }

    private static final class FakeProcessor implements ReviewDiffEditor.ProcessorHandle {
        final List<DiffRequest> applied = new ArrayList<>();
        boolean disposed;

        @Override
        public void apply(DiffRequest request) {
            applied.add(request);
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }
    }

    private static ReviewDiffRequests.RequestContext context(String path) {
        return new ReviewDiffRequests.RequestContext(path,
                new ReviewDiffRequests.Plan("left\n", "right\n", List.<LineFragment>of(),
                        Side.RIGHT, 0));
    }

    private static DiffRequest marker(String title) {
        return new DiffRequest() {
            @Override
            public String getTitle() {
                return title;
            }
        };
    }

    private static ReviewDiffEditor editor(RecordingOpener opener, RecordingRequests requests) {
        return new ReviewDiffEditor(opener, requests, viewer -> {
        });
    }

    // ---- first request seeding ---------------------------------------------

    @Test
    void firstShowOpensTheTabAndItsNewProcessorIsSeededWithTheCurrentRequest() {
        RecordingOpener opener = new RecordingOpener();
        RecordingRequests requests = new RecordingRequests();
        ReviewDiffEditor editor = editor(opener, requests);

        onEdt(() -> editor.show(context("src/A.java")));

        assertEquals(1, opener.opens);
        assertTrue(requests.contexts.isEmpty(),
                "the open path builds nothing itself: the request is built once, by the seeding");
        // The seeding the spike demanded: the processor a newly opened tab
        // creates is handed the current request, not a blank placeholder.
        assertEquals("src/A.java", onEdtGet(() -> editor.seedRequest().getTitle()));
        assertEquals(List.of("src/A.java"),
                requests.contexts.stream().map(ReviewDiffRequests.RequestContext::path).toList());
        assertEquals("src/A.java", onEdtGet(editor::currentTitle));
    }

    // ---- swap in place ------------------------------------------------------

    @Test
    void laterShowsSwapTheRequestIntoTheLiveProcessorWithoutReopening() {
        RecordingOpener opener = new RecordingOpener();
        RecordingRequests requests = new RecordingRequests();
        ReviewDiffEditor editor = editor(opener, requests);
        onEdt(() -> editor.show(context("src/A.java")));
        FakeProcessor processor = new FakeProcessor();
        onEdt(() -> editor.processorReady(processor));

        onEdt(() -> editor.show(context("src/B.java")));

        assertEquals(1, opener.opens);
        assertEquals(List.of(requests.built.get(0)), processor.applied);
        assertEquals("src/B.java", onEdtGet(editor::currentTitle));
    }

    // ---- reopen after close -------------------------------------------------

    @Test
    void aDisposedProcessorIsIgnoredAndTheNextShowReopensTheTab() {
        RecordingOpener opener = new RecordingOpener();
        RecordingRequests requests = new RecordingRequests();
        ReviewDiffEditor editor = editor(opener, requests);
        onEdt(() -> editor.show(context("src/A.java")));
        FakeProcessor processor = new FakeProcessor();
        onEdt(() -> editor.processorReady(processor));
        processor.disposed = true; // the user closed the tab

        onEdt(() -> editor.show(context("src/C.java")));

        assertEquals(2, opener.opens);
        assertTrue(processor.applied.isEmpty(), "a disposed processor is never applied to");
        assertTrue(requests.contexts.stream().map(ReviewDiffRequests.RequestContext::path)
                .noneMatch(path -> path.equals("src/C.java")),
                "the reopen itself builds nothing: only the seeding builds");
        // The reopened tab's new processor is seeded with the request that reopened it.
        assertEquals("src/C.java", onEdtGet(() -> editor.seedRequest().getTitle()));
        assertEquals(List.of("src/C.java"), requests.contexts.stream()
                .map(ReviewDiffRequests.RequestContext::path)
                .filter(path -> path.equals("src/C.java")).toList(),
                "the seeding is the reopen's one and only build");
    }

    /**
     * The double-build defect the fix removed: the open and reopen paths used
     * to build the request in {@code show} and then discard it (the seeding
     * built it again), leaking a document pair per discarded build.
     */
    @Test
    void eachShownContextBuildsItsRequestExactlyOnceFirstSwapAndReopen() {
        RecordingOpener opener = new RecordingOpener();
        RecordingRequests requests = new RecordingRequests();
        ReviewDiffEditor editor = editor(opener, requests);
        FakeProcessor processor = new FakeProcessor();

        // First show: the tab opens; the one build is the seeding's.
        onEdt(() -> editor.show(context("src/A.java")));
        assertEquals(1, opener.opens);
        assertEquals(0, requests.contexts.size(), "show builds nothing on the open path");
        onEdt(() -> editor.processorReady(processor));
        assertEquals("src/A.java", onEdtGet(() -> editor.seedRequest().getTitle()));
        assertEquals(List.of("src/A.java"),
                requests.contexts.stream().map(ReviewDiffRequests.RequestContext::path).toList());

        // Swap: the live processor gets the request built once, in place.
        onEdt(() -> editor.show(context("src/B.java")));
        assertEquals(1, opener.opens);
        assertEquals(List.of("src/A.java", "src/B.java"),
                requests.contexts.stream().map(ReviewDiffRequests.RequestContext::path).toList());
        assertEquals(List.of(requests.built.get(1)), processor.applied,
                "the swap's single build is applied in place");

        // Reopen after close: nothing is built and discarded; the seeding builds once.
        processor.disposed = true; // the user closed the tab
        onEdt(() -> editor.show(context("src/C.java")));
        assertEquals(2, opener.opens);
        assertEquals(List.of("src/A.java", "src/B.java"),
                requests.contexts.stream().map(ReviewDiffRequests.RequestContext::path).toList(),
                "the reopen path builds nothing itself");
        assertEquals("src/C.java", onEdtGet(() -> editor.seedRequest().getTitle()));
        assertEquals(List.of("src/A.java", "src/B.java", "src/C.java"),
                requests.contexts.stream().map(ReviewDiffRequests.RequestContext::path).toList(),
                "exactly one build per shown context, none discarded");
    }

    // ---- stale selection suppression ----------------------------------------

    @Test
    void aRequestForASupersededSelectionIsDropped() {
        RecordingOpener opener = new RecordingOpener();
        RecordingRequests requests = new RecordingRequests();
        ReviewDiffEditor editor = editor(opener, requests);

        onEdt(() -> editor.selection("sel-1"));
        onEdt(() -> editor.requestReady("sel-1", context("src/A.java")));
        assertEquals(1, opener.opens);

        onEdt(() -> editor.selection("sel-2"));
        onEdt(() -> editor.requestReady("sel-1", context("src/stale.java")));

        assertEquals(1, opener.opens, "a stale selection's request never opens a tab");
        assertTrue(requests.contexts.isEmpty(), "nothing was built for the dropped stale request");

        onEdt(() -> editor.requestReady("sel-2", context("src/B.java")));
        assertEquals(2, opener.opens);
        assertEquals("src/B.java", onEdtGet(() -> editor.seedRequest().getTitle()));
        assertEquals(List.of("src/B.java"),
                requests.contexts.stream().map(ReviewDiffRequests.RequestContext::path).toList(),
                "the current selection's request is the only one ever built");
    }

    @Test
    void aRequestBeforeAnySelectionIsDropped() {
        RecordingOpener opener = new RecordingOpener();
        RecordingRequests requests = new RecordingRequests();
        ReviewDiffEditor editor = editor(opener, requests);

        onEdt(() -> editor.requestReady("sel-1", context("src/A.java")));

        assertEquals(0, opener.opens);
        assertTrue(requests.contexts.isEmpty(), "nothing was picked to show");
    }

    // ---- EDT contract -------------------------------------------------------

    @Test
    void showSelectionAndRequestReadyAssertTheEdt() {
        ReviewDiffEditor editor = editor(new RecordingOpener(), new RecordingRequests());
        ReviewDiffRequests.RequestContext context = context("src/A.java");

        assertThrows(IllegalStateException.class, () -> runOffEdt(() -> editor.show(context)));
        assertThrows(IllegalStateException.class, () -> runOffEdt(() -> editor.selection("sel-1")));
        assertThrows(IllegalStateException.class,
                () -> runOffEdt(() -> editor.requestReady("sel-1", context)));
        assertThrows(IllegalStateException.class, () -> runOffEdt(editor::seedRequest));
        assertThrows(IllegalStateException.class,
                () -> runOffEdt(() -> editor.processorReady(new FakeProcessor())));
    }

    // ---- helpers ------------------------------------------------------------

    private static final class FakeDiffViewer implements FrameDiffTool.DiffViewer {
        @Override
        public javax.swing.JComponent getComponent() {
            return null;
        }

        @Override
        public javax.swing.JComponent getPreferredFocusedComponent() {
            return null;
        }

        @Override
        public FrameDiffTool.ToolbarComponents init() {
            return null;
        }

        @Override
        public void dispose() {
        }
    }

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

    private static <T> T onEdtGet(java.util.function.Supplier<T> op) {
        AtomicReference<T> out = new AtomicReference<>();
        onEdt(() -> out.set(op.get()));
        return out.get();
    }

    private static void runOffEdt(Runnable op) {
        // The probe runs on a private thread, which is never the EDT —
        // wherever the test framework itself happens to run — so the
        // exception asserted is the probe's own, not this helper's.
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                op.run();
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        other.start();
        try {
            other.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted joining the off-EDT probe", e);
        }
        Throwable failure = thrown.get();
        if (failure == null) {
            throw new AssertionError("the off-EDT probe did not throw, but it must");
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("the off-EDT probe threw a checked exception", failure);
    }
}
