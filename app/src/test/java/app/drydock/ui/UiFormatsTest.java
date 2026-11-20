package app.drydock.ui;

import app.drydock.testing.FxTest;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The breadcrumb's eliding: which segment gives way when there is no room. */
class UiFormatsTest extends FxTest {

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

    @Test
    void wrapLeavesShortLinesAlone() {
        assertEquals("one two three", UiFormats.wrap("one two three", 72));
    }

    @Test
    void wrapBreaksLongLinesOnSpaces() {
        assertEquals("alpha beta gamma\ndelta", UiFormats.wrap("alpha beta gamma delta", 18));
    }

    @Test
    void wrapHardBreaksATokenWithNoSeparators() {
        assertEquals("aaaaa\naaaaa\nbbbbb\nbbbbb\nccccc\nccccc",
                UiFormats.wrap("aaaaaaaaaabbbbbbbbbbcccccccccc", 5));
    }

    @Test
    void wrapBreaksALongPathAtSlashes() {
        assertEquals("/one/two/\nthree/four", UiFormats.wrap("/one/two/three/four", 10));
    }

    @Test
    void wrapPreservesEmbeddedNewlines() {
        assertEquals("short\nalpha beta gamma\ndelta", UiFormats.wrap("short\nalpha beta gamma delta", 18));
    }

    @Test
    void labeledLinePadsContinuationsToTheLabelColumn() {
        assertEquals("Dir: alpha\n     beta\n     gamma", UiFormats.labeledLine("Dir", "alpha beta gamma", 12));
    }

    @Test
    void labeledLineHandlesWidthsShorterThanItsPrefix() {
        String expected = "Long: a\n      b\n      c";
        assertEquals(expected, UiFormats.labeledLine("Long", "abc", 0));
        assertEquals(expected, UiFormats.labeledLine("Long", "abc", -5));
    }

    @Test
    void abbreviateHomeReplacesTheUserPrefixWithTilde() {
        String home = System.getProperty("user.home");
        assertEquals("~", UiFormats.abbreviateHome(home));
        assertEquals("~/go/src/demo", UiFormats.abbreviateHome(home + "/go/src/demo"));
    }

    @Test
    void abbreviateHomeLeavesOtherPrefixesAlone() {
        assertEquals("/opt/work/demo", UiFormats.abbreviateHome("/opt/work/demo"));
        assertEquals("host:/home/user/demo", UiFormats.abbreviateHome("host:/home/user/demo"));
    }
}
