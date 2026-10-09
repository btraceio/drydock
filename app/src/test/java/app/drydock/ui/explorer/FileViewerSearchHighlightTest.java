package app.drydock.ui.explorer;

import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import app.drydock.ui.TestStages;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The viewer's text search highlighting: the standing query paints its
 * matches as a second style layer over the lexer's, in step with the rail's
 * debounced keystrokes, and clears with the query.
 */
class FileViewerSearchHighlightTest extends FxTest {

    private static final String SOURCE = """
            class Needles {
                int first() {
                    return the needle here;
                }

                int second() {
                    return another needle;
                }
            }
            """;

    private Path root;
    private Path file;
    private FileViewer viewer;

    @AfterEach
    void tearDown() throws IOException {
        if (viewer != null) {
            viewer.dispose();
        }
    }

    @Override
    public void start(Stage stage) {
        try {
            root = Files.createTempDirectory("drydock-viewer-search");
            file = root.resolve("src/Needles.java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, SOURCE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        AtomicReference<FileViewer> holder = new AtomicReference<>();
        interact(() -> {
            FileViewer built = new FileViewer(root);
            holder.set(built);
            TestStages.show(stage, new Scene(new StackPane(built), 900, 600));
        });
        viewer = holder.get();
        FxSync.waitForFxEvents();
    }

    /** The code area of the tab open at {@code file}, or null while the load is in flight. */
    private CodeArea openArea() {
        CodeArea[] holder = new CodeArea[1];
        interact(() -> holder[0] = viewer.diagOpenArea(file));
        return holder[0];
    }

    /** The offset of the first character styled "code-match", empty when none is. */
    private OptionalInt firstMatchOffset() {
        int[] at = {-1};
        interact(() -> {
            CodeArea area = openArea();
            if (area == null || area.getText().isEmpty()) {
                return;
            }
            int position = 0;
            for (var span : area.getStyleSpans(0, area.getLength())) {
                if (at[0] < 0 && span.getStyle().contains("code-match")) {
                    at[0] = position;
                }
                position += span.getLength();
            }
        });
        return at[0] < 0 ? OptionalInt.empty() : OptionalInt.of(at[0]);
    }

    /** Total characters styled "code-match" (matches × query length). */
    private int styledMatchChars() {
        int[] count = {0};
        interact(() -> {
            CodeArea area = openArea();
            int position = 0;
            for (var span : area.getStyleSpans(0, area.getLength())) {
                if (span.getStyle().contains("code-match")) {
                    count[0] += span.getLength();
                }
                position += span.getLength();
            }
        });
        return count[0];
    }

    private enum Matched { YES, NO, PENDING }

    private Matched matchState() {
        return firstMatchOffset().isPresent() ? Matched.YES : Matched.NO;
    }

    /** Polls until {@code wanted} or ~1.5s pass (the async load + debounce are off the FX thread). */
    private void awaitMatches(boolean wanted, String why) {
        for (int i = 0; i < 60 && matchState() != (wanted ? Matched.YES : Matched.NO); i++) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            FxSync.waitForFxEvents();
        }
        assertEquals(wanted ? Matched.YES : Matched.NO, matchState(), why);
    }

    @Test
    void openingThroughASearchResultPaintsTheMatches() {
        // The rail's match-line click: open with the query named.
        interact(() -> viewer.openFile(file, root.relativize(file), OptionalInt.empty(), "needle"));
        awaitMatches(true, "the open-time query paints its matches");
        // Case-insensitive by design, so "Needles" (the class) matches too:
        // the first highlight lands on the identifier at offset 6.
        assertEquals(SOURCE.indexOf("Needles"), firstMatchOffset().getAsInt(),
                "the first painted highlight is the first match in the text");
        assertEquals("needle".length() * 3, styledMatchChars(), "all three occurrences are styled");
    }

    @Test
    void typingInTheRailHighlightsTheOpenFileAndClearingClearsIt() {
        // A plain open (no query named): nothing highlighted.
        interact(() -> viewer.openFile(file, root.relativize(file), OptionalInt.empty(), null));
        awaitMatches(false, "a plain open paints no match layer");

        // The rail's debounced keystrokes, one per settled query.
        interact(() -> viewer.setSearchQuery("needle"));
        awaitMatches(true, "the live query highlights the open file");
        assertEquals("needle".length() * 3, styledMatchChars(), "all three occurrences are styled");

        interact(() -> viewer.setSearchQuery(""));
        awaitMatches(false, "an empty query clears the highlight");
    }
}