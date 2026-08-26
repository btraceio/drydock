package app.drydock.ui;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replacing a modal ends it, and its owner has to hear about that.
 *
 * <p>{@code show} used to overwrite the {@code onClosed} hook without
 * running it, so the hook only ever fired through {@code close()}. Any flow
 * whose completion signal IS that hook was then stranded the moment anything
 * else opened a modal over it -- {@code ⌘N} over an open Start-session modal
 * left the pull-request row that opened it disabled and reading "Opening…"
 * for the rest of the process, with no way to review that PR again.</p>
 */
class ModalLayerTest extends ApplicationTest {

    private ModalLayer layer;
    private final List<String> ended = new ArrayList<>();

    @Override
    public void start(Stage stage) {
        layer = new ModalLayer();
        TestStages.show(stage, new Scene(new StackPane(layer), 400, 300));
    }

    @Test
    void replacingAModalRunsItsOwnClosedHook() {
        interact(() -> {
            layer.show(modal("first"), () -> ended.add("first"));
            layer.show(modal("second"), () -> ended.add("second"));
        });

        assertEquals(List.of("first"), ended,
                "the replaced modal's owner must learn that its modal is gone");
        assertTrue(layer.isShowingModal(), "the replacement is showing");
    }

    @Test
    void aReplacedModalIsNotEndedASecondTimeWhenTheLayerCloses() {
        interact(() -> {
            layer.show(modal("first"), () -> ended.add("first"));
            layer.show(modal("second"), () -> ended.add("second"));
            layer.close();
        });

        assertEquals(List.of("first", "second"), ended,
                "each modal ends exactly once, in order");
    }

    @Test
    void showingIntoAnEmptyLayerEndsNothing() {
        interact(() -> layer.show(modal("only"), () -> ended.add("only")));

        assertEquals(List.of(), ended, "there was no outgoing modal to end");
    }

    @Test
    void aHookThatClosesTheLayerCannotReEnterItself() {
        interact(() -> {
            layer.show(modal("first"), () -> {
                ended.add("first");
                layer.close();
            });
            layer.show(modal("second"), () -> ended.add("second"));
        });

        assertEquals(List.of("first"), ended, "the outgoing hook is cleared before it runs");
    }

    private static Region modal(String text) {
        return new StackPane(new Label(text));
    }
}
