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
    void tearDown() {
        interact(() -> view.dispose());
        searchService.close();
    }

    private void openFile(String relative) {
        interact(() -> view.openFileAtLine(Path.of(relative), 1));
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
        interact(() -> view.diagPeek("clamp"));
        waitForFxEvents();
        org.testfx.util.WaitForAsyncUtils.sleep(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
        waitForFxEvents();

        assertEquals(1, lookup(".peek-card").queryAll().size());
        Label title = lookup(".peek-title").query();
        assertTrue(title.getText().startsWith("clamp"), title.getText());

        interact(() -> view.diagPeek("width"));
        waitForFxEvents();
        org.testfx.util.WaitForAsyncUtils.sleep(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
        waitForFxEvents();
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
    void theAskActionIsAbsentWithoutASession() {
        openFile("ui/Sidebar.java");
        interact(() -> view.diagPeek("clamp"));
        waitForFxEvents();
        org.testfx.util.WaitForAsyncUtils.sleep(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
        waitForFxEvents();

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
        for (int i = 0; i < PeekLayer.MAX_DEPTH + 1; i++) {
            String symbol = i % 2 == 0 ? "clamp" : "width";
            interact(() -> view.diagPeek(symbol));
            waitForFxEvents();
            org.testfx.util.WaitForAsyncUtils.sleep(1200, java.util.concurrent.TimeUnit.MILLISECONDS);
            waitForFxEvents();
        }
        assertEquals(PeekLayer.MAX_DEPTH, onFx(() -> view.diagPeekDepth()));
        Label toast = lookup(".explorer-toast").query();
        assertTrue(toast.isVisible() && toast.getText().contains("esc to unwind"), toast.getText());
    }

    /** Keeps the temp tree out of the developer's working copy. */
    @AfterEach
    void deleteTempTree() throws IOException {
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
