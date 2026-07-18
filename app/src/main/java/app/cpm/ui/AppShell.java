package app.cpm.ui;

import app.cpm.domain.UiTheme;
import javafx.scene.Scene;
import javafx.scene.control.SplitPane;
import javafx.stage.StageStyle;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.function.Consumer;

/**
 * The whole window shell (design handoff section 1): custom title bar on
 * top of a horizontal {@link SplitPane} (sidebar | main pane), stacked
 * under the {@link ModalLayer}. Owns the {@link ThemeManager} and the
 * undecorated-stage plumbing (drag, resize, sidebar width clamp).
 */
public final class AppShell {

    /** Sidebar resize clamp (handoff section 2). */
    private static final double SIDEBAR_MIN = 220;
    private static final double SIDEBAR_MAX = 520;

    private static final double MIN_WINDOW_WIDTH = 720;
    private static final double MIN_WINDOW_HEIGHT = 480;

    private final Scene scene;
    private final SplitPane splitPane;
    private final Region sidebar;
    private final ModalLayer modalLayer = new ModalLayer();
    private final ThemeManager themeManager;
    private final TitleBar titleBar;

    public AppShell(Stage stage, String title, Region sidebar, Region mainPane,
                    double initialSidebarWidth, UiTheme initialTheme, Consumer<UiTheme> onThemeChanged,
                    double sceneWidth, double sceneHeight) {
        this.sidebar = sidebar;

        sidebar.setMinWidth(SIDEBAR_MIN);
        sidebar.setMaxWidth(SIDEBAR_MAX);
        splitPane = new SplitPane(sidebar, mainPane);
        SplitPane.setResizableWithParent(sidebar, false);

        titleBar = new TitleBar(stage, title,
                () -> showShortcutsOverlay(),
                () -> toggleTheme());

        BorderPane shell = new BorderPane();
        shell.setTop(titleBar);
        shell.setCenter(splitPane);

        StackPane root = new StackPane(shell, modalLayer);

        scene = new Scene(root, sceneWidth, sceneHeight);
        themeManager = new ThemeManager(scene, initialTheme, theme -> {
            titleBar.showThemeGlyphFor(theme);
            onThemeChanged.accept(theme);
        });
        titleBar.showThemeGlyphFor(initialTheme);

        stage.initStyle(StageStyle.UNDECORATED);
        stage.setMinWidth(MIN_WINDOW_WIDTH);
        stage.setMinHeight(MIN_WINDOW_HEIGHT);
        stage.setScene(scene);
        StageResizer.install(stage, root, MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT);

        double clampedSidebar = Math.clamp(initialSidebarWidth, SIDEBAR_MIN, SIDEBAR_MAX);
        splitPane.setDividerPositions(clampedSidebar / sceneWidth);
    }

    public Scene scene() {
        return scene;
    }

    public ModalLayer modalLayer() {
        return modalLayer;
    }

    public ThemeManager themeManager() {
        return themeManager;
    }

    /** Current absolute sidebar width in px, for persistence on shutdown. */
    public double sidebarWidth() {
        return sidebar.getWidth();
    }

    public void toggleTheme() {
        themeManager.toggle();
    }

    public void showShortcutsOverlay() {
        modalLayer.show(ShortcutsOverlay.create(modalLayer::close));
    }
}
