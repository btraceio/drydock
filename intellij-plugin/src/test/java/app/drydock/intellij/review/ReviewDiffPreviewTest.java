package app.drydock.intellij.review;

import com.intellij.diff.requests.SimpleDiffRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless AC17 unit half for {@link ReviewDiffPreview}: the placement
 * decision matrix (mirroring the platform rule, bytecode-verified on
 * IU-263.7031.23: splitter iff the content is horizontal or the Commit
 * tool window is not enabled) and the selection feed that keeps one
 * preview on the current selection. The real hosts need a running IDE
 * (a {@code DiffRequestPanel}) and are exercised live (AC24).
 */
class ReviewDiffPreviewTest {

    // --- AC17: the placement decision matrix --------------------------------

    @Test
    void horizontalContentUsesTheSplitterWhateverTheCommitWindow() {
        // The platform rule's first branch: !isContentVertical -> true,
        // before the Commit tool window is even consulted (bytecode).
        assertTrue(ReviewDiffPreview.useSplitter(false, true));
        assertTrue(ReviewDiffPreview.useSplitter(false, false));
    }

    @Test
    void verticalContentUsesTheSplitterOnlyWithoutTheCommitToolWindow() {
        // Vertical content + enabled Commit tool window is the one
        // editor-tab case; anything else falls back to the splitter.
        assertFalse(ReviewDiffPreview.useSplitter(true, true));
        assertTrue(ReviewDiffPreview.useSplitter(true, false));
    }

    // --- AC17: the Host surface stays exactly show/dispose -------------------

    @Test
    void hostSurfaceIsExactlyShowAndDispose() {
        List<String> names = Arrays.stream(ReviewDiffPreview.Host.class.getMethods())
                .filter(method -> method.getDeclaringClass() == ReviewDiffPreview.Host.class)
                .map(Method::getName)
                .sorted()
                .toList();
        assertEquals(List.of("dispose", "show"), names,
                "the pinned Host interface is show(SimpleDiffRequest) and dispose(), nothing else");
        for (Method method : ReviewDiffPreview.Host.class.getMethods()) {
            assertTrue(Modifier.isAbstract(method.getModifiers()),
                    () -> "Host must stay an abstract surface: " + method);
        }
    }

    // --- AC17: one preview, selection order, stale drops ---------------------

    @Test
    void fakeHostRecordsShowOrderAsSelectionsChange() {
        RecordingHost host = new RecordingHost();
        ReviewDiffPreview.SelectionPreview preview = new ReviewDiffPreview.SelectionPreview(host);

        preview.selection("file-a");
        preview.requestReady("file-a", request("A"));

        preview.selection("file-b");
        // file-a's late completion must not repaint over file-b's selection
        preview.requestReady("file-a", request("A (stale)"));
        preview.requestReady("file-b", request("B"));

        assertEquals(List.of("A", "B"), host.titles());
    }

    @Test
    void aRequestBeforeAnySelectionIsDropped() {
        RecordingHost host = new RecordingHost();
        ReviewDiffPreview.SelectionPreview preview = new ReviewDiffPreview.SelectionPreview(host);

        preview.requestReady("file-a", request("A"));

        assertEquals(List.of(), host.titles(),
                "nothing has been picked to preview yet");
    }

    @Test
    void theSameSelectionShowsEachOfItsRequestsInArrivalOrder() {
        RecordingHost host = new RecordingHost();
        ReviewDiffPreview.SelectionPreview preview = new ReviewDiffPreview.SelectionPreview(host);

        preview.selection("file-a");
        preview.requestReady("file-a", request("A1"));
        preview.requestReady("file-a", request("A2"));

        assertEquals(List.of("A1", "A2"), host.titles(),
                "a content refresh of the current selection re-targets the same preview, in order");
    }

    @Test
    void aStaleSelectionTokenIsNeverReanimated() {
        RecordingHost host = new RecordingHost();
        ReviewDiffPreview.SelectionPreview preview = new ReviewDiffPreview.SelectionPreview(host);

        preview.selection("file-a");
        preview.selection("file-b");
        preview.requestReady("file-a", request("A"));

        assertEquals(List.of(), host.titles(),
                "the selection moved on before the request was ready");
    }

    @Test
    void nullArgumentsAreRefused() {
        ReviewDiffPreview.SelectionPreview preview =
                new ReviewDiffPreview.SelectionPreview(new RecordingHost());

        assertThrows(NullPointerException.class, () -> preview.selection(null));
        assertThrows(NullPointerException.class,
                () -> preview.requestReady(null, request("A")));
        assertThrows(NullPointerException.class,
                () -> preview.requestReady("file-a", null));
        assertThrows(NullPointerException.class,
                () -> new ReviewDiffPreview.SelectionPreview(null));
    }

    // --- fixtures ------------------------------------------------------------

    /** A fake {@link ReviewDiffPreview.Host} recording what the tab would show. */
    private static final class RecordingHost implements ReviewDiffPreview.Host {
        private final List<String> titles = new ArrayList<>();

        @Override
        public void show(SimpleDiffRequest request) {
            titles.add(request.getTitle());
        }

        @Override
        public void dispose() {
            // nothing owned; the recording is the test's evidence
        }

        List<String> titles() {
            return List.copyOf(titles);
        }
    }

    /** A named request; production requests come from {@code ReviewDiffRequests.toRequest}. */
    private static SimpleDiffRequest request(String title) {
        return new SimpleDiffRequest(title, List.of(), List.of());
    }
}
