package app.drydock.ui;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The breadcrumb's eliding: which segment gives way when there is no room. */
class UiFormatsTest extends ApplicationTest {

    @Override
    public void start(Stage stage) {
        // No scene needed: these are node factories, not a view. The
        // toolkit is what the Labels want, and extending ApplicationTest is
        // how every other UI test in this tree gets one.
    }

    @Test
    void aLongBreadcrumbElidesFromTheLeft() {
        List<Node> nodes = UiFormats.breadcrumbSegments(
                Path.of("src/main/java/demo/util/Strings.java"), 3);
        List<String> texts = nodes.stream().map(node -> ((Label) node).getText()).toList();
        assertEquals(List.of("…", "›", "demo", "›", "util", "›", "Strings.java"), texts,
                "the file name is the part that must never be the thing that goes");
    }

    @Test
    void aShortBreadcrumbIsUnchanged() {
        List<Node> nodes = UiFormats.breadcrumbSegments(Path.of("docs/README.md"), 3);
        List<String> texts = nodes.stream().map(node -> ((Label) node).getText()).toList();
        assertEquals(List.of("docs", "›", "README.md"), texts);
    }
}
