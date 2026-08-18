package app.drydock.ui;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression, found by screenshotting a live fork: a worktree session's header
 * collapsed its chips and buttons to {@code uncom...}, {@code Fi...} and
 * {@code ru...} at 1100px, because an HBox spreads a width deficit across every
 * child that will shrink. The path is the only thing here that survives being
 * elided, so it must be the only thing that gives.
 *
 * <p>Drives {@link OpenSessionTab#layOutSessionHeader} rather than a real tab:
 * a tab needs a native terminal surface and cannot be built headlessly. The
 * fixture is lighter than the real header (one chip, a shorter title), so it
 * gives at a narrower width than the app did -- with the pinning removed it
 * fails at 700px, not 1100px. It guards the property, not the threshold.</p>
 */
class SessionHeaderLayoutTest extends ApplicationTest {

    private Region back;
    private VBox titleBlock;
    private Label contextLine;
    private HBox chips;
    private MenuButton fork;
    private StackPane finishBox;
    private HBox statusPill;
    private Region rename;
    private HBox header;
    private StackPane parent;

    @Override
    public void start(Stage stage) {
        // Content matching a forked worktree session -- the case that broke.
        back = new Button("←");
        Label title = new Label("handoff-e2e-main-codex");
        title.setMinWidth(0);
        title.setMaxWidth(Double.MAX_VALUE);
        contextLine = new Label("◫ main-codex  →  ⎇ main  ·  /Users/jbachorik/dev/wt/handoff-e2e-main-codex");
        contextLine.setMinWidth(0);
        contextLine.setMaxWidth(Double.MAX_VALUE);
        titleBlock = new VBox(1, title, contextLine);
        chips = new HBox(6, new Label("uncommitted"));
        fork = new MenuButton("Fork to…");
        finishBox = new StackPane(new Button("Finish ▸"));
        statusPill = new HBox(6, new Label("running"));
        rename = new Button("✎");

        header = OpenSessionTab.layOutSessionHeader(back, titleBlock, chips, fork,
                finishBox, statusPill, rename);
        // A StackPane, not a Pane: Pane lays children out at their PREFERRED
        // width, so the header was never short of space and the whole test
        // measured an unstressed row.
        parent = new StackPane(header);
        Scene scene = new Scene(parent, 1100, 200);
        scene.getStylesheets().setAll(
                SessionHeaderLayoutTest.class.getResource("/app/drydock/ui/theme-dark.css").toExternalForm(),
                SessionHeaderLayoutTest.class.getResource("/app/drydock/ui/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @BeforeEach
    void settle() {
        interact(() -> { });
    }

    /** Squeezes the PARENT: resizing the header itself leaves nothing to redistribute. */
    private void squeezeTo(double width) {
        interact(() -> {
            parent.resize(width, 200);
            parent.applyCss();
            parent.layout();
        });
    }

    @Test
    void chipsAndButtonsKeepTheirLabelsWhileTheHeaderNarrows() {
        for (double width : new double[]{1100, 900, 700, 560}) {
            squeezeTo(width);

            for (Region pinned : List.of(back, chips, fork, finishBox, statusPill, rename)) {
                assertTrue(pinned.getWidth() >= pinned.prefWidth(-1) - 0.5,
                        "at " + width + "px a header control squeezed to " + pinned.getWidth()
                                + ", below its preferred " + pinned.prefWidth(-1));
            }
        }
    }

    /**
     * The other half of the property, and the half that makes the test mean
     * something: at these widths the row really is short of space, so the
     * assertion above is about where the deficit landed and not about a row
     * that happened to fit.
     */
    @Test
    void theTitleBlockIsWhatAbsorbsTheDeficit() {
        double roomy = titleBlockWidthAt(1100);
        double cramped = titleBlockWidthAt(560);

        assertTrue(cramped < roomy,
                "the title block must give as the header narrows, but went " + roomy + " -> " + cramped);
        assertTrue(cramped < contextLine.prefWidth(-1),
                "at 560px the context line should be elided, yet the title block still had "
                        + cramped + "px for a preferred " + contextLine.prefWidth(-1));
    }

    private double titleBlockWidthAt(double width) {
        squeezeTo(width);
        return titleBlock.getWidth();
    }
}
