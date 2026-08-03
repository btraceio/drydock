package app.drydock.ui.explorer;

import app.drydock.search.SessionSearchService;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Explorer's trail and peek layer through the headless JavaFX harness
 * (Monocle + TestFX): a real Stage with the real stylesheets, which is the
 * only place "is every chip focus-traversable" and "does Esc unwind one
 * card" are actually answerable.
 */
class SessionExplorerViewTest extends ApplicationTest {

    private Path root;
    private SessionSearchService searchService;
    private SessionExplorerView view;

    @Override
    public void start(Stage stage) {
        try {
            root = Files.createTempDirectory("drydock-explorer-view");
            Files.createDirectories(root.resolve("ui"));
            Files.writeString(root.resolve("ui/SizeSetting.java"), """
                    package ui;

                    final class SizeSetting {
                        static final int MIN_W = 220;

                        double clamp(double w) {
                            return Math.max(MIN_W, Math.min(520, w));
                        }
                    }
                    """);
            Files.writeString(root.resolve("ui/Sidebar.java"), """
                    package ui;

                    final class Sidebar {
                        private final SizeSetting sizing = new SizeSetting();

                        int width() {
                            return (int) sizing.clamp(widthProperty.get());
                        }

                        void layoutChildren() {
                            double w = width();
                            content.resizeRelocate(0, 0, w, getHeight());
                        }
                    }
                    """);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        searchService = new SessionSearchService();
        view = new SessionExplorerView(root, searchService);
        Scene scene = new Scene(view, 1200, 800);
        scene.getStylesheets().addAll(
                SessionExplorerView.class.getResource("/app/drydock/ui/theme-dark.css").toExternalForm(),
                SessionExplorerView.class.getResource("/app/drydock/ui/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown() throws IOException {
        interact(() -> view.dispose());
        searchService.close();
        deleteTempTree();
    }

    private void openFile(String relative) {
        interact(() -> view.openFileAtLine(Path.of(relative), 1));
        waitForFxEvents();
    }

    /**
     * Peeks {@code symbol} and waits for the card, rather than sleeping a
     * fixed interval: resolution is a repository text search whose latency is
     * the machine's, not ours.
     */
    private void peek(String symbol) {
        int before = onFx(() -> view.diagPeekDepth());
        interact(() -> view.diagPeek(symbol));
        try {
            org.testfx.util.WaitForAsyncUtils.waitFor(10, java.util.concurrent.TimeUnit.SECONDS,
                    () -> onFx(() -> view.diagPeekDepth()) > before);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("no peek card appeared for " + symbol, e);
        }
        waitForFxEvents();
    }

    /** Runs {@code action} on the FX thread and hands its value back. */
    private <T> T onFx(java.util.concurrent.Callable<T> action) {
        return org.testfx.util.WaitForAsyncUtils.waitForAsyncFx(5000, action);
    }

    private void waitForFxEvents() {
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
    }

    private List<String> trailChips() {
        return lookup(".trail-chip").queryAll().stream()
                .map(node -> ((Button) node).getText())
                .toList();
    }

    @Test
    void openingFilesGrowsTheTrail() {
        openFile("ui/Sidebar.java");
        openFile("ui/SizeSetting.java");

        assertEquals(List.of("Sidebar.java", "SizeSetting.java"), trailChips());
        Button current = lookup(".trail-chip").queryAll().stream()
                .map(Button.class::cast)
                .filter(chip -> chip.getPseudoClassStates().stream()
                        .anyMatch(pseudo -> pseudo.getPseudoClassName().equals("current")))
                .findFirst().orElseThrow();
        assertEquals("SizeSetting.java", current.getText());
    }

    @Test
    void trailBackAndForwardStepThroughTheOpenedFiles() {
        openFile("ui/Sidebar.java");
        openFile("ui/SizeSetting.java");

        assertTrue(onFx(() -> view.navigateTrail(-1)));
        waitForFxEvents();
        assertEquals("Sidebar.java", currentChip());

        assertFalse(onFx(() -> view.navigateTrail(-1)),
                "at the trail's start the shortcut must fall through, not be swallowed");

        assertTrue(onFx(() -> view.navigateTrail(1)));
        waitForFxEvents();
        assertEquals("SizeSetting.java", currentChip());
        assertFalse(onFx(() -> view.navigateTrail(1)));
    }

    @Test
    void everyTrailControlIsFocusTraversable() {
        openFile("ui/Sidebar.java");
        for (Node node : lookup(".trail-chip").queryAll()) {
            assertTrue(node.isFocusTraversable(), "trail chip must be reachable by keyboard");
        }
        assertTrue(lookup(".trail-step-button").queryAll().stream().allMatch(Node::isFocusTraversable));
        assertTrue(lookup(".trail-pin-button").query().isFocusTraversable());
    }

    @Test
    void pinningMarksTheCurrentWaypoint() {
        openFile("ui/Sidebar.java");
        clickOn(lookup(".trail-pin-button").queryButton());
        waitForFxEvents();
        assertTrue(trailChips().get(0).startsWith("📌"));
        assertTrue(lookup(".explorer-toast").query().isVisible());
    }

    private String currentChip() {
        return lookup(".trail-chip").queryAll().stream()
                .map(Button.class::cast)
                .filter(chip -> chip.getPseudoClassStates().stream()
                        .anyMatch(pseudo -> pseudo.getPseudoClassName().equals("current")))
                .map(Button::getText)
                .findFirst().orElseThrow();
    }

    @Test
    void aPeekOpensOverTheViewerAndEscClosesExactlyOne() {
        openFile("ui/Sidebar.java");
        peek("clamp");

        assertEquals(1, lookup(".peek-card").queryAll().size());
        Label title = lookup(".peek-title").query();
        assertTrue(title.getText().startsWith("clamp"), title.getText());

        peek("width");
        assertEquals(1, lookup(".peek-card").queryAll().size(), "only the top card is built in full");
        assertEquals(1, lookup(".peek-card-ghost").queryAll().size(), "the card below it shows as a ghost");

        assertTrue(onFx(() -> view.unwindOverlay()));
        waitForFxEvents();
        assertEquals(0, lookup(".peek-card-ghost").queryAll().size());
        assertEquals(1, lookup(".peek-card").queryAll().size(), "esc closed exactly one");

        assertTrue(onFx(() -> view.unwindOverlay()));
        waitForFxEvents();
        assertTrue(lookup(".peek-card").queryAll().isEmpty());
        assertFalse(onFx(() -> view.unwindOverlay()),
                "with nothing open Esc must fall through to the global chain");
    }

    @Test
    void thePeekCardIsLaidOutOverTheBottomRightOfTheViewer() {
        openFile("ui/Sidebar.java");
        peek("clamp");

        javafx.scene.layout.Region card = lookup(".peek-card").query();
        javafx.geometry.Bounds card_ = card.localToScene(card.getBoundsInLocal());
        javafx.scene.Node viewer = lookup(".file-viewer").query();
        javafx.geometry.Bounds viewerBounds = viewer.localToScene(viewer.getBoundsInLocal());

        assertTrue(card_.getWidth() > 300,
                "the card is a card, not a collapsed minimum: " + card_.getWidth());
        assertTrue(card_.getMaxX() <= viewerBounds.getMaxX() + 1
                        && card_.getMinX() > viewerBounds.getMinX() + viewerBounds.getWidth() / 3,
                "the card sits against the viewer's right edge: " + card_ + " in " + viewerBounds);
        assertTrue(card_.getMinY() > viewerBounds.getMinY() + viewerBounds.getHeight() / 3,
                "…and its lower half: " + card_ + " in " + viewerBounds);
    }

    @Test
    void theAskActionIsAbsentWithoutASession() {
        openFile("ui/Sidebar.java");
        peek("clamp");

        List<String> actions = lookup(".peek-action").queryAll().stream()
                .map(node -> ((Button) node).getText())
                .toList();
        assertTrue(actions.stream().noneMatch(text -> text.contains("ask")),
                "agent-dependent actions degrade to absent, not disabled: " + actions);
        assertTrue(actions.stream().anyMatch(text -> text.contains("open for real")));
    }


    @Test
    void thePeekStackIsCappedAndSaysSo() {
        openFile("ui/Sidebar.java");
        for (int i = 0; i < PeekLayer.MAX_DEPTH; i++) {
            peek(i % 2 == 0 ? "clamp" : "width");
        }
        // One more than the cap allows: refused, and said so.
        interact(() -> view.diagPeek("clamp"));
        waitForFxEvents();
        assertEquals(PeekLayer.MAX_DEPTH, onFx(() -> view.diagPeekDepth()));
        Label toast = lookup(".explorer-toast").query();
        assertTrue(toast.isVisible() && toast.getText().contains("esc to unwind"), toast.getText());
    }

    /** Keeps the temp tree out of the developer's working copy. */
    private void deleteTempTree() throws IOException {
        if (root != null && Files.exists(root)) {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // A temp tree that outlives the test is not a failure.
                    }
                });
            }
        }
    }
}
