package app.drydock.ui.explorer;

import app.drydock.search.SessionSearchService;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.Duration;
import org.fxmisc.richtext.CodeArea;

import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The Session Explorer (design handoff section A, frame 2a): a collapsible
 * session-scoped search rail beside an editable, auto-saving code viewer
 * (files that cannot be written back safely stay read-only). Shown as the
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

    /**
     * Bounded so a stuck filesystem cannot hang application shutdown.
     * Fully-qualified because this class already imports {@link
     * javafx.util.Duration} for the collapse animation -- the
     * same-name-different-package exception AGENTS.md allows.
     */
    private static final java.time.Duration FLUSH_TIMEOUT = java.time.Duration.ofSeconds(2);

    private final Path searchRoot;
    private final FileViewer viewer;
    private final SearchRail rail;
    private boolean railCollapsed;
    private ExplorerTrailStore trailStore;
    private String trailKey;

    public SessionExplorerView(Path searchRoot, SessionSearchService searchService) {
        this(searchRoot, searchService, null);
    }

    /** Persists this session's trail; null leaves the trail in memory only. */
    public void setTrailStore(ExplorerTrailStore store, String sessionKey) {
        this.trailStore = store;
        this.trailKey = sessionKey;
        if (store != null && sessionKey != null) {
            ExplorerTrailStore.Trail restored = store.load(sessionKey);
            viewer.restoreTrail(restored.waypoints(), restored.cursor());
        }
    }

    /**
     * Wires the peek card's "ask the agent" action. Absent -- not greyed --
     * when {@code available} says the bound session cannot be asked
     * anything (delta hard rules).
     */
    public void setAgentBridge(BooleanSupplier available, Consumer<String> sendPrompt) {
        viewer.setAgentAvailable(available == null ? () -> false : available);
        viewer.setOnAskAgent(peek -> {
            if (sendPrompt == null) {
                return;
            }
            sendPrompt.accept(askPrompt(peek));
        });
    }

    /**
     * The question the peek's {@code a} sends: the symbol, where it is
     * declared, and every call site -- the same context the reader has in
     * front of them, on one line because {@code sendPrompt} submits at the
     * first newline.
     */
    static String askPrompt(SymbolPeek peek) {
        StringBuilder prompt = new StringBuilder("In ")
                .append(peek.relativePath()).append(" line ").append(peek.startLine())
                .append(", explain ").append(peek.symbol()).append(". Occurrences: ");
        int shown = 0;
        for (SymbolPeek.Occurrence occurrence : peek.occurrences()) {
            if (shown++ == 12) {
                prompt.append("… (").append(peek.occurrences().size() - 12).append(" more)");
                break;
            }
            prompt.append(occurrence.relativePath()).append(':').append(occurrence.line())
                    .append(occurrence.inDiff() ? " (in diff)" : "").append("; ");
        }
        return prompt.toString().strip();
    }

    /**
     * {@code ⌘[} / {@code ⌘]} along the trail. False when the trail cannot
     * move that way, which is what lets the global shortcut fall back to its
     * original meaning (previous/next session tab) at the trail's ends
     * instead of the Explorer silently swallowing it.
     */
    public boolean navigateTrail(int direction) {
        return viewer.navigateTrail(direction);
    }

    /**
     * Esc, topmost first: one peek card. False when the Explorer had nothing
     * to unwind, so the global Esc chain carries on to its next step.
     */
    public boolean unwindOverlay() {
        return viewer.unwindPeek();
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

        viewer = new FileViewer(searchRoot);
        viewer.setPeekService(new SymbolPeekService(searchRoot, searchService));
        viewer.setOnTrailChanged(this::persistTrail);
        rail = new SearchRail(searchRoot, searchService, viewer::openFile);
        installShortcuts();
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

    /**
     * Blocks until this Explorer's unsaved file edits are on disk. Called on
     * the shutdown path: the viewer's I/O threads are daemons, so a
     * fire-and-forget flush is killed mid-write at JVM exit.
     */
    public void flushPendingEdits() {
        viewer.flushPendingEdits(FLUSH_TIMEOUT);
    }

    /**
     * Flushes unsaved edits and releases the viewer's I/O executor. One-way:
     * call it when this Explorer's session tab is going away (tab removal or
     * shutdown), never on a sub-tab switch -- the executor must survive that.
     */
    public void dispose() {
        dispose(true);
    }

    /**
     * As {@link #dispose()}, with the flush optional. Pass {@code false} only
     * when this Explorer's {@link #flushPendingEdits()} has just run: a
     * flushing {@code dispose()} would otherwise hand it a second full flush
     * budget, doubling the worst-case shutdown freeze on a hung disk.
     */
    public void dispose(boolean flush) {
        viewer.dispose(flush);
    }

    /** Review-tab bridge: opens {@code relativeFile} in the viewer at a 1-based line (⤢ on a changed diff line). */
    public void openFileAtLine(Path relativeFile, int line) {
        viewer.openFile(searchRoot.resolve(relativeFile).normalize(), relativeFile, OptionalInt.of(line), null);
    }

    /** Diagnostic-only (see MainWorkspace.diagTypeInExplorer): types into the open file's code area. */
    public void diagType(String text) {
        viewer.diagType(text);
    }

    /**
     * Opens a peek for {@code symbol} through the same path a click on it
     * takes. Diagnostic- and test-only: a real click needs a hit test
     * against laid-out glyphs, which is exactly what neither the headless
     * harness nor the screenshot driver can aim.
     */
    public void diagPeek(String symbol) {
        viewer.diagPeek(symbol);
    }

    /** Diagnostic- and test-only: how many peek cards are stacked. */
    public int diagPeekDepth() {
        return viewer.diagPeekDepth();
    }

    /** Diagnostic- and test-only: the trail as its chips read, oldest first. */
    public java.util.List<String> diagTrail() {
        return viewer.trail().waypoints().stream().map(NavigationTrail.Waypoint::label).toList();
    }

    /** Review-tab bridge: runs a Text-mode search for {@code token} (the "Search in Explorer" chip). */
    public void searchText(String token) {
        setRailCollapsed(false);
        rail.setSearch(token);
    }

    private void persistTrail() {
        if (trailStore != null && trailKey != null) {
            NavigationTrail trail = viewer.trail();
            trailStore.save(trailKey, new ExplorerTrailStore.Trail(trail.waypoints(), trail.cursor()));
        }
    }

    /**
     * The Explorer's own keyboard layer (delta "Keyboard").
     *
     * <p>An event <em>filter</em> on this view, so the keys work wherever the
     * focus is inside the Explorer -- and gated on the focus owner not being
     * a text input, which here means the search field <em>and</em> an
     * editable code area: single-letter shortcuts must never eat a
     * keystroke aimed at a file the reader is editing.</p>
     */
    private void installShortcuts() {
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isShortcutDown() || event.isAltDown() || event.isMetaDown()) {
                return;
            }
            if (typingSomewhere()) {
                return;
            }
            switch (event.getCode()) {
                case ENTER -> {
                    if (viewer.isPeekOpen()) {
                        viewer.promoteTopPeek();
                        event.consume();
                    }
                }
                case U -> {
                    if (viewer.isPeekOpen()) {
                        viewer.togglePeekUsages();
                        event.consume();
                    }
                }
                case A -> {
                    if (viewer.isPeekOpen()) {
                        viewer.askTopPeek();
                        event.consume();
                    }
                }
                default -> { }
            }
        });
    }

    /**
     * Whether a keystroke belongs to something the reader is typing into.
     * {@link CodeArea} is not a {@link TextInputControl}, so the usual
     * instance check alone would let {@code a} in an editable file open a
     * peek action instead of typing an "a".
     */
    private boolean typingSomewhere() {
        Node focused = getScene() == null ? null : getScene().getFocusOwner();
        if (focused instanceof TextInputControl) {
            return true;
        }
        return focused instanceof CodeArea area && area.isEditable();
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
