package app.drydock.ui.explorer;

import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * The semantic minimap (Explorer delta, part 2): an 18px strip down the
 * viewer's right edge whose ticks are the file's changed regions, findings,
 * current search hits and already-read members.
 *
 * <p>Every tick is a real focusable button with a tooltip naming its target,
 * which is what makes the strip usable without a mouse and without a legend
 * -- the delta asks for exactly that.</p>
 */
final class Minimap extends Pane {

    static final double WIDTH = 18;

    private static final double TICK_WIDTH = 11;
    private static final double TICK_HEIGHT = 3;

    private List<MinimapTicks.Tick> ticks = List.of();
    private IntConsumer onGoToLine = line -> { };

    Minimap() {
        getStyleClass().add("explorer-minimap");
        setMinWidth(WIDTH);
        setPrefWidth(WIDTH);
        setMaxWidth(WIDTH);
    }

    void setOnGoToLine(IntConsumer handler) {
        this.onGoToLine = handler == null ? line -> { } : handler;
    }

    void setTicks(List<MinimapTicks.Tick> ticks) {
        this.ticks = List.copyOf(ticks);
        getChildren().clear();
        for (MinimapTicks.Tick tick : this.ticks) {
            Button mark = new Button();
            mark.getStyleClass().addAll("minimap-tick", styleFor(tick.kind()));
            mark.setPrefSize(TICK_WIDTH, TICK_HEIGHT);
            mark.setMinSize(TICK_WIDTH, TICK_HEIGHT);
            mark.setMaxSize(TICK_WIDTH, TICK_HEIGHT);
            Tooltip tip = new Tooltip(tick.tooltip());
            tip.setShowDelay(Duration.millis(250));
            mark.setTooltip(tip);
            mark.setOnAction(e -> onGoToLine.accept(tick.line()));
            getChildren().add(mark);
        }
        requestLayout();
    }

    private static String styleFor(MinimapTicks.Kind kind) {
        return switch (kind) {
            case CHANGED -> "changed";
            case FINDING -> "finding";
            case SEARCH_HIT -> "search-hit";
            case READ -> "read";
        };
    }

    @Override
    protected void layoutChildren() {
        double height = getHeight();
        for (int i = 0; i < getChildren().size() && i < ticks.size(); i++) {
            Region mark = (Region) getChildren().get(i);
            double y = ticks.get(i).position() * Math.max(0, height - TICK_HEIGHT);
            mark.resizeRelocate(3, y, TICK_WIDTH, TICK_HEIGHT);
        }
    }
}
