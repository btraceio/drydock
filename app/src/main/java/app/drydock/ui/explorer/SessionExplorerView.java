package app.drydock.ui.explorer;

import app.drydock.search.SessionSearchService;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.OptionalInt;

/**
 * The Session Explorer (design handoff section A, frame 2a): a collapsible
 * session-scoped search rail beside a read-only code viewer. Shown as the
 * session tab's center when the Explorer sub-tab is active (the native
 * terminal overlay is hidden meanwhile -- see OpenSessionTab.showSubTab).
 *
 * <p>Laid out as an {@link HBox} with a fixed-width animated rail rather
 * than a {@code SplitPane}: the 324px ↔ 46px collapse animation is a
 * simple width {@link Timeline} this way (deliberate deviation from the
 * handoff's literal "SplitPane" wording; same UX).</p>
 */
public final class SessionExplorerView extends HBox {

    private static final double RAIL_EXPANDED_WIDTH = 324;
    private static final double RAIL_COLLAPSED_WIDTH = 46;
    private static final Duration COLLAPSE_ANIMATION = Duration.millis(160);

    private final Path searchRoot;
    private final FileViewer viewer;
    private final SearchRail rail;
    private boolean railCollapsed;

    public SessionExplorerView(Path searchRoot, SessionSearchService searchService) {
        this(searchRoot, searchService, null);
    }

    /**
     * With a non-null {@code overlay}, the viewer marks the current diff
     * scope's changed lines with a green gutter + banner, and Text-search
     * files carrying diff lines get a {@code diff} chip (design handoff
     * section C "Explorer integration").
     */
    public SessionExplorerView(Path searchRoot, SessionSearchService searchService, DiffOverlay overlay) {
        this.searchRoot = searchRoot;
        getStyleClass().add("explorer-root");

        viewer = new FileViewer();
        rail = new SearchRail(searchRoot, searchService, viewer::openFile);
        if (overlay != null) {
            viewer.setDiffOverlay(overlay);
            rail.setDiffFileTest(relativePath -> viewer.hasDiffLines(relativePath));
        }
        rail.setPrefWidth(RAIL_EXPANDED_WIDTH);
        rail.setMinWidth(RAIL_EXPANDED_WIDTH);
        rail.setMaxWidth(RAIL_EXPANDED_WIDTH);
        rail.setOnCollapseRequested(() -> setRailCollapsed(true));
        rail.setOnExpandRequested(() -> setRailCollapsed(false));

        HBox.setHgrow(viewer, Priority.ALWAYS);
        getChildren().setAll(rail, viewer);
    }

    /** Review-tab bridge: opens {@code relativeFile} in the viewer at a 1-based line (⤢ on a changed diff line). */
    public void openFileAtLine(Path relativeFile, int line) {
        viewer.openFile(searchRoot.resolve(relativeFile).normalize(), relativeFile, OptionalInt.of(line), null);
    }

    /** Review-tab bridge: runs a Text-mode search for {@code token} (the "Search in Explorer" chip). */
    public void searchText(String token) {
        setRailCollapsed(false);
        rail.setSearch(token);
    }

    private void setRailCollapsed(boolean collapsed) {
        if (railCollapsed == collapsed) {
            return;
        }
        railCollapsed = collapsed;
        double target = collapsed ? RAIL_COLLAPSED_WIDTH : RAIL_EXPANDED_WIDTH;
        if (collapsed) {
            rail.showCollapsed();
        }
        Timeline animation = new Timeline(new KeyFrame(COLLAPSE_ANIMATION,
                new KeyValue(rail.minWidthProperty(), target),
                new KeyValue(rail.prefWidthProperty(), target),
                new KeyValue(rail.maxWidthProperty(), target)));
        animation.setOnFinished(e -> {
            if (!collapsed) {
                rail.showExpanded();
            }
        });
        animation.play();
    }
}
