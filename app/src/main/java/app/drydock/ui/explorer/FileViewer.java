package app.drydock.ui.explorer;

import app.drydock.ui.UiFormats;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;

/**
 * The Session Explorer's code viewer (design handoff section A): its own
 * file-tab strip (independent of the session tabs), a breadcrumb row with
 * a save-state chip and a line-number-gutter toggle, and a
 * syntax-highlighted RichTextFX {@link CodeArea} per file. A tab is
 * editable when its loaded {@link FileContent} is eligible (auto-saving
 * through a {@link FileEditSession}) and read-only otherwise; the
 * breadcrumb chip reports which -- {@code read-only}, {@code editable},
 * {@code unsaved}, {@code saved} or an error state -- and files load off
 * the FX thread.
 */
final class FileViewer extends BorderPane {

    private static final Logger LOG = System.getLogger(FileViewer.class.getName());

    /** Truncate very large files rather than stalling the viewer. */
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;

    private final TabPane fileTabs = new TabPane();
    private final HBox breadcrumb = new HBox(4);
    private final VBox topBox = new VBox(0);
    private final Label emptyState = new Label("Open a file from search to view it.");
    private final StackPane centerStack = new StackPane();
    private final Button gutterToggle = new Button("#");

    // -- Diff overlay (design handoff section C "Explorer integration") ----
    private final HBox diffBanner = new HBox(8);
    private final Label diffBannerLabel = new Label();
    private final Button diffBaseSwitch = new Button("switch base");
    private DiffOverlay diffOverlay;
    /** Latest changed-line map for the overlay's current scope (relative path → 1-based new-file lines). */
    private Map<Path, Set<Integer>> changedLines = Map.of();

    // -- Conflict / error banner (spec: Error handling) ---------------------
    private final HBox editBanner = new HBox(8);
    private final Label editBannerLabel = new Label();
    private final Button editBannerPrimary = new Button();
    private final Button editBannerSecondary = new Button();

    /**
     * The tab the currently shown {@link #editBanner} belongs to, or null when
     * it is hidden. The banner is ONE row shared by every file tab while its
     * text and both handlers are bound to one specific tab, so without an owner
     * the user would see another file's conflict message with buttons acting on
     * that other file. Tracking the owner lets the banner be cleared when the
     * selection moves away, when its session recovers, and when its tab closes.
     */
    private Tab editBannerOwner;

    /** Open files, keyed by absolute path. */
    private final Map<Path, Tab> openFiles = new LinkedHashMap<>();
    private boolean gutterVisible = true;

    /** Auto-save debounce (spec decision 1: long enough to be ceremony-free, short enough to be safe). */
    private static final Duration SAVE_DEBOUNCE = Duration.ofSeconds(2);

    /**
     * ONE single-threaded executor for every open file's I/O. Single-threaded
     * is load-bearing, not incidental: it serializes each session's write
     * against its own stamp capture and polls (see FileEditSession's
     * concurrency invariant). Daemon so a missed close cannot hang the JVM --
     * the shutdown path still flushes explicitly (flushPendingEdits).
     */
    private final ScheduledExecutorService ioExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "explorer-file-io");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Edit sessions of the open editable tabs, keyed by absolute path
     * (read-only tabs have none). Mutated on the FX thread but iterated by
     * {@link #flushPendingEdits}, whose whole point is to run off the FX
     * thread (see its own Javadoc) -- {@link Collections#synchronizedMap}
     * keeps that iteration safe against concurrent {@code put}/{@code
     * remove} without giving up insertion order.
     */
    private final Map<Path, FileEditSession> sessions = Collections.synchronizedMap(new LinkedHashMap<>());

    /** Breadcrumb status chip: read-only / editable / unsaved / saved (spec decision 4). */
    private final Label statusChip = new Label("read-only");
    private PauseTransition chipReset;

    /** How often open files are checked for external (Claude) edits. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(1500);

    private ScheduledFuture<?> poller;

    /**
     * Whether a poll round's reads are still outstanding. Written from the
     * executor thread (the tick), the FX thread (the scan) and whichever thread
     * completes the last read, hence atomic.
     */
    private final AtomicBoolean pollRoundInFlight = new AtomicBoolean();

    /** Re-highlight debounce, matching SearchRail's keystroke-debounce convention. */
    private static final Duration HIGHLIGHT_DEBOUNCE = Duration.ofMillis(150);

    private PauseTransition highlightDebounce;

    /** Coalescing window for the post-save diff refresh (the cache is shared with Review). */
    private static final Duration DIFF_REFRESH_COALESCE = Duration.ofSeconds(2);

    private PauseTransition diffRefreshDebounce;

    /** Flush budget for {@link #dispose()}, matching the shutdown chain's. */
    private static final Duration DISPOSE_FLUSH_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Set by {@link #dispose()}. The viewer is dead afterwards: a re-attach
     * must not restart the poller, since {@link #ioExecutor} is shut down and
     * would reject the task.
     *
     * <p>Deliberately non-volatile: every writer and every reader is on the FX
     * thread ({@code dispose()} is called from tab removal and the shutdown
     * chain, {@code startPolling()} from the scene listener), so the field is
     * thread-confined and needs no publication. Anything reading it off the FX
     * thread would break that invariant and must make it volatile.</p>
     */
    private boolean disposed;

    FileViewer() {
        getStyleClass().add("file-viewer");

        fileTabs.getStyleClass().add("viewer-tabs");
        fileTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);

        breadcrumb.getStyleClass().add("viewer-breadcrumb");
        breadcrumb.setAlignment(Pos.CENTER_LEFT);

        gutterToggle.getStyleClass().add("viewer-gutter-toggle");
        gutterToggle.setFocusTraversable(false);
        gutterToggle.setTooltip(new Tooltip("Toggle line numbers"));
        gutterToggle.setOnAction(e -> toggleGutter());

        emptyState.getStyleClass().add("viewer-empty");

        diffBannerLabel.getStyleClass().add("viewer-diff-banner-label");
        diffBaseSwitch.getStyleClass().add("viewer-diff-base-switch");
        diffBaseSwitch.setFocusTraversable(false);
        diffBaseSwitch.setTooltip(new Tooltip("Switch the diff overlay's base"));
        diffBaseSwitch.setOnAction(e -> {
            if (diffOverlay != null) {
                diffOverlay.cycleScope();
                refreshDiffOverlay();
            }
        });
        diffBanner.getChildren().setAll(diffBannerLabel, diffBaseSwitch);
        diffBanner.setAlignment(Pos.CENTER_LEFT);
        diffBanner.getStyleClass().add("viewer-diff-banner");
        diffBanner.setVisible(false);
        diffBanner.setManaged(false);

        editBannerLabel.getStyleClass().add("viewer-edit-banner-label");
        editBannerPrimary.getStyleClass().add("viewer-diff-base-switch");
        editBannerPrimary.setFocusTraversable(false);
        editBannerSecondary.getStyleClass().add("viewer-diff-base-switch");
        editBannerSecondary.setFocusTraversable(false);
        // The label must be the only thing that gives way when the row is
        // narrow. Without this the HBox shrinks the BUTTONS first and both
        // collapse to "..." -- two identical unreadable controls, one of which
        // discards the user's unsaved edits. Caught by the visual pass; the
        // long missing-file wording made it reproduce at ordinary widths.
        editBannerPrimary.setMinWidth(Region.USE_PREF_SIZE);
        editBannerSecondary.setMinWidth(Region.USE_PREF_SIZE);
        editBannerLabel.setMinWidth(0);
        HBox.setHgrow(editBannerLabel, Priority.ALWAYS);
        editBanner.getChildren().setAll(editBannerLabel, editBannerPrimary, editBannerSecondary);
        editBanner.setAlignment(Pos.CENTER_LEFT);
        editBanner.getStyleClass().add("viewer-edit-banner");
        editBanner.setVisible(false);
        editBanner.setManaged(false);

        centerStack.getChildren().setAll(fileTabs, emptyState);
        setCenter(centerStack);

        fileTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            flushSession(oldTab);
            // The edit banner is bound to one tab; showing another tab's
            // conflict/error over this file -- with buttons that act on that
            // other file -- would be actively misleading.
            if (editBannerOwner != newTab) {
                hideEditBanner();
                raiseBannerFor(newTab);
            }
            updateBreadcrumb(newTab);
            updateEmptyState();
            updateDiffBanner();
        });
        updateBreadcrumb(null);
        updateEmptyState();

        // Poll only while the viewer is actually in the scene graph.
        // OpenSessionTab.showSubTab swaps the tab's center node, so leaving
        // the Explorer detaches this viewer and nulls its scene -- which is
        // also the flush signal for the sub-tab-switch and tab-close cases
        // (spec: Lifecycle).
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                flushAllSessions();
                stopPolling();
                stopTransitions();
            } else {
                startPolling();
                // The chip is not repainted while detached, so a save that
                // landed (or failed) in the meantime would leave it showing
                // whatever was true when the user left the Explorer.
                updateStatusChip();
            }
        });
    }

    // ---- Diff overlay -------------------------------------------------------

    /** Wires the shared changed-line overlay (null-safe: never set for sessions without a repository). */
    void setDiffOverlay(DiffOverlay overlay) {
        this.diffOverlay = overlay;
        refreshDiffOverlay();
    }

    /** Whether the overlay's current scope touches {@code relativePath} (drives the rail's {@code diff} chip). */
    boolean hasDiffLines(Path relativePath) {
        return changedLines.containsKey(relativePath);
    }

    /** Re-fetches the changed-line map for the overlay's current scope and restyles every open tab. */
    private void refreshDiffOverlay() {
        if (diffOverlay == null) {
            return;
        }
        diffOverlay.changedSet().whenComplete((map, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                LOG.log(Level.DEBUG, "Changed-line overlay failed", failure);
                changedLines = Map.of();
            } else {
                changedLines = map;
            }
            for (Tab tab : fileTabs.getTabs()) {
                if (tab.getContent() instanceof VirtualizedScrollPane<?> pane
                        && pane.getContent() instanceof CodeArea area) {
                    applyGutter(area, changedLinesFor(tab));
                }
            }
            updateDiffBanner();
        }));
    }

    private Set<Integer> changedLinesFor(Tab tab) {
        Path relative = (Path) tab.getProperties().get("drydock.relative");
        return relative == null ? Set.of() : changedLines.getOrDefault(relative, Set.of());
    }

    /** Banner: "N lines here are part of the diff vs <base>", with the base switchable in place. */
    private void updateDiffBanner() {
        Tab selected = fileTabs.getSelectionModel().getSelectedItem();
        int count = selected == null ? 0 : changedLinesFor(selected).size();
        boolean show = diffOverlay != null && count > 0;
        if (show) {
            diffBannerLabel.setText(count + (count == 1 ? " line here is part of " : " lines here are part of ")
                    + diffOverlay.scopeLabel());
        }
        diffBanner.setVisible(show);
        diffBanner.setManaged(show);
    }

    // ---- Disk polling / reload / conflict --------------------------------

    private void startPolling() {
        if (disposed) {
            return;
        }
        if (poller == null) {
            poller = ioExecutor.scheduleWithFixedDelay(this::pollOpenFiles,
                    POLL_INTERVAL.toMillis(), POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void stopPolling() {
        if (poller != null) {
            poller.cancel(false);
            poller = null;
        }
    }

    /**
     * Stops every debounce/one-shot timer this viewer owns (AGENTS.md: nothing
     * with a timer may outlive the node). A {@code diffRefreshDebounce} left
     * armed past detach would drop the diff cache the Review tab shares and
     * spawn a {@code git diff} for a viewer nobody is looking at.
     */
    private void stopTransitions() {
        if (highlightDebounce != null) {
            highlightDebounce.stop();
            highlightDebounce = null;
        }
        if (diffRefreshDebounce != null) {
            diffRefreshDebounce.stop();
            diffRefreshDebounce = null;
        }
        if (chipReset != null) {
            chipReset.stop();
            chipReset = null;
        }
    }

    /**
     * Flushes every live session. Iterating {@link Collections#synchronizedMap}'s
     * {@code values()} view directly would be a bug here, not a style point:
     * {@code sessions.remove} runs from the ioExecutor thread in a tab-close
     * completion, so a concurrent structural change would throw {@link
     * java.util.ConcurrentModificationException} out of this FX event handler
     * BEFORE {@code stopPolling()} -- leaving the poller running against a
     * detached viewer with some sessions never flushed. Snapshot under the
     * monitor and iterate the snapshot, exactly as {@link #flushPendingEdits}
     * does.
     */
    private void flushAllSessions() {
        List<FileEditSession> snapshot;
        synchronized (sessions) {
            snapshot = new ArrayList<>(sessions.values());
        }
        for (FileEditSession session : snapshot) {
            session.flush();
        }
    }

    /**
     * One round of external-change detection across every open editable tab.
     *
     * <p>Runs on {@link #ioExecutor} via {@code scheduleWithFixedDelay}, which
     * silently drops all future runs if this ever throws -- there would be no
     * signal that the user's stale-file protection just died. Every tick is
     * therefore caught and logged rather than allowed to propagate.</p>
     *
     * <p>The tick itself only schedules: the tab list and every tab's property
     * map are JavaFX-owned structures that {@link #openFile} and tab closes
     * mutate on the FX thread, so reading them from the executor thread could
     * hand {@link #applyPollResult} a torn snapshot containing an
     * already-removed tab -- a conflict banner wired to a dead tab. The scan
     * therefore happens on the FX thread; the poll it starts is still
     * asynchronous, so nothing blocks there.</p>
     *
     * <p>{@code scheduleWithFixedDelay} only spaces the ticks that post the
     * scan, not the reads they start, so a tick is skipped entirely while the
     * previous round's reads are still outstanding. Without that backpressure
     * several tabs on a slow filesystem would enqueue a fresh round every
     * {@link #POLL_INTERVAL} on the one executor thread the SAVES also use,
     * and the queue in front of a user's write would keep growing.</p>
     */
    private void pollOpenFiles() {
        if (!pollRoundInFlight.compareAndSet(false, true)) {
            LOG.log(Level.DEBUG, "Skipping disk poll: the previous round has not finished");
            return;
        }
        try {
            Platform.runLater(this::pollOpenFilesOnFxThread);
        } catch (RuntimeException e) {
            pollRoundInFlight.set(false);
            LOG.log(Level.WARNING, "Could not schedule disk poll; will retry next tick", e);
        }
    }

    private void pollOpenFilesOnFxThread() {
        try {
            List<Map.Entry<Tab, FileEditSession>> targets = new ArrayList<>();
            for (Tab tab : fileTabs.getTabs()) {
                if (tab.getProperties().get("drydock.session") instanceof FileEditSession session) {
                    targets.add(Map.entry(tab, session));
                }
            }
            if (targets.isEmpty()) {
                pollRoundInFlight.set(false);
                return;
            }
            CompletableFuture<?>[] round = new CompletableFuture<?>[targets.size()];
            for (int i = 0; i < targets.size(); i++) {
                Tab tab = targets.get(i).getKey();
                FileEditSession session = targets.get(i).getValue();
                round[i] = session.poll().thenAccept(
                        result -> Platform.runLater(() -> applyPollResult(tab, session, result)));
            }
            // Cleared on EVERY completion path, failures included: a round that
            // never clears the flag would stop the stale-file protection dead
            // for the rest of the viewer's life.
            CompletableFuture.allOf(round)
                    .whenComplete((ignored, failure) -> pollRoundInFlight.set(false));
        } catch (RuntimeException e) {
            pollRoundInFlight.set(false);
            LOG.log(Level.WARNING, "Disk poll failed; will retry next tick", e);
        }
    }

    /**
     * Clean tab + changed file: silently adopt Claude's edits, so an open
     * tab never goes stale. Dirty tab + changed file: raise the conflict
     * banner and leave both versions intact (auto-save is already disarmed
     * inside the session).
     */
    private void applyPollResult(Tab tab, FileEditSession session, FileEditSession.PollResult result) {
        if (!fileTabs.getTabs().contains(tab)) {
            // The poll was started on the FX thread against a live tab, but the
            // result lands one executor hop plus one runLater later -- long
            // enough for the user to have closed the tab. Reloading its
            // CodeArea or raising a banner wired to it would act on a file
            // nobody has open any more.
            return;
        }
        switch (result.outcome()) {
            case UNCHANGED -> {
                // Nothing changed, so nothing to repaint -- and repainting
                // anyway would cut the deliberate 2s "saved" flash short at a
                // random point in the poll interval.
                return;
            }
            case RELOAD -> reload(tab, session, result.text());
            case CONFLICT -> showConflictBanner(tab, session);
            case MISSING -> showMissingBanner(tab, session);
        }
        // Only for the tab whose chip this actually is. A background tab's
        // reload/conflict/missing repainting the chip would cut the selected
        // tab's deliberate 2s "saved ✓" flash short at a random point in the
        // poll interval -- the same hazard the UNCHANGED branch above avoids,
        // and the reason announceSaved() is gated on the selection too.
        if (ownsBannerRow(tab)) {
            updateStatusChip();
        }
        updateDirtyDot(tab);
    }

    /**
     * Replaces a clean tab's text with the disk content, holding the caret
     * where it was.
     *
     * <p>Undo history is forgotten deliberately: {@code replaceText} pushes
     * onto RichTextFX's UndoManager, so one Cmd+Z after a reload the user did
     * not notice would restore their stale buffer -- which auto-save would
     * then write over Claude's edits.</p>
     *
     * <p>{@code session} may be null (a read-only tab has none). When it is
     * not, the buffer is re-synced from the text just applied: the session
     * adopted the disk text on the executor thread one FX pulse ago, so a
     * keystroke landing in that window leaves it DIRTY holding text typed on
     * top of the PRE-reload buffer, while {@link #replaceTextQuietly}
     * suppresses the listener that would otherwise tell it the buffer moved on.
     * Without the re-sync the area would show Claude's text while the session
     * held the user's stale text -- and wrote it over Claude's edits two
     * seconds later with no conflict raised.</p>
     */
    private void reload(Tab tab, FileEditSession session, String text) {
        if (session != null && session.state() == FileEditSession.State.CONFLICT) {
            // A conflict the user has not answered yet. Adopting the disk text
            // here would answer it for them -- in the disk's favour, discarding
            // their buffer -- which is exactly what the banner exists to ask.
            // (The legitimate "disk wins" path, takeDisk, leaves the session
            // CLEAN before it calls back in here, so it is unaffected.)
            return;
        }
        if (!(tab.getContent() instanceof VirtualizedScrollPane<?> pane)
                || !(pane.getContent() instanceof CodeArea area)) {
            // The session has already adopted the disk text, so silently
            // returning would leave buffer and session diverged with no signal.
            LOG.log(Level.WARNING, "Cannot reload " + fileNameOf(tab)
                    + ": tab content is not a CodeArea; its buffer is now out of sync with disk");
            return;
        }
        int paragraph = area.getCurrentParagraph();
        int column = area.getCaretColumn();
        // Known, accepted one-pulse loss: a keystroke landing between the
        // executor's RELOAD decision and this apply is overwritten here (and
        // then confirmed away by the session.edit below). Closing it would mean
        // diffing/merging the buffer against the disk text on the FX thread for
        // a window one frame wide, against a decision the session has ALREADY
        // committed to -- disproportionate, and a merge is the one thing this
        // feature deliberately does not do. The reload only happens for a CLEAN
        // session, so the loss is at most the characters typed inside that pulse.
        replaceTextQuietly(tab, area, text);
        if (session != null && session.state() != FileEditSession.State.CLEAN) {
            // Cheap and idempotent: the text handed back IS the disk text the
            // session already adopted, so the write this re-arms is a no-op
            // byte-wise. Only the DIRTY-in-the-window case reaches here.
            session.edit(text);
            updateDirtyDot(tab);
        }
        area.getUndoManager().forgetHistory();
        int safeParagraph = Math.max(0, Math.min(paragraph, area.getParagraphs().size() - 1));
        int safeColumn = Math.max(0, Math.min(column, area.getParagraphLength(safeParagraph)));
        area.moveTo(safeParagraph, safeColumn);
        // The external edit shifted line numbers, so the green changed-line
        // markers are now pointing at pre-reload lines. Nothing else repaints
        // them until the next save-triggered overlay refresh.
        applyGutter(area, changedLinesFor(tab));
        rehighlight(tab, area, text);
    }

    /**
     * Re-raises the banner a newly selected tab is entitled to. The
     * {@code show*Banner} methods only paint the selected tab's banner (the row
     * is shared), so a background tab's conflict or save failure would
     * otherwise be invisible for good in the save-error case -- nothing
     * re-raises it, unlike CONFLICT and MISSING which the poller re-reports
     * every tick. Driven off session state, so it is correct for both.
     */
    private void raiseBannerFor(Tab tab) {
        if (tab == null
                || !(tab.getProperties().get("drydock.session") instanceof FileEditSession session)) {
            return;
        }
        if (session.disarmed()) {
            // Checked before the state switch, and outside it because "the file
            // is gone" is not a State: it is orthogonal to whatever the buffer
            // itself is (DIRTY, ERROR, even CONFLICT), and it outranks all of
            // them -- a conflict banner offering "reload" is meaningless for a
            // file that is no longer there to reload. Without this a background
            // tab whose file vanished, whose showMissingBanner call returned
            // early because it did not own the banner row, would never tell the
            // user anything at all: only CONFLICT and ERROR were re-derivable.
            showMissingBanner(tab, session);
            return;
        }
        switch (session.state()) {
            case CONFLICT -> showConflictBanner(tab, session);
            case ERROR -> showSaveErrorBanner(tab, session);
            default -> { }
        }
    }

    /**
     * Whether {@code tab} may claim the shared banner row. The row is ONE set
     * of controls whose text and both handlers are bound to one tab, so a
     * background tab's conflict would render over the file the user is actually
     * looking at, with a "reload" button that discards the OTHER file's unsaved
     * edits.
     */
    private boolean ownsBannerRow(Tab tab) {
        return fileTabs.getSelectionModel().getSelectedItem() == tab;
    }

    private void showConflictBanner(Tab tab, FileEditSession session) {
        if (!ownsBannerRow(tab)) {
            return;
        }
        resetEditBanner(tab);
        String name = fileNameOf(tab);
        editBannerLabel.setText(name + " changed on disk while you were editing it.");
        editBannerPrimary.setText("keep mine");
        editBannerPrimary.setOnAction(e -> {
            hideEditBanner();
            session.keepMine();
        });
        editBannerSecondary.setText("reload");
        editBannerSecondary.setOnAction(e -> {
            hideEditBanner();
            takeDisk(tab, session);
        });
        showEditBanner();
    }

    private void showMissingBanner(Tab tab, FileEditSession session) {
        if (!ownsBannerRow(tab)) {
            return;
        }
        resetEditBanner(tab);
        // Not only deletion: this same outcome covers a file that became
        // unreadable, or that was replaced by content this editor must not
        // write back (binary, oversized, mixed terminators). The wording has to
        // cover all three, and has to say what "close tab" costs.
        // Kept short enough to survive a narrow viewer: the visual pass showed
        // the previous three-clause wording pushing the row past its width. The
        // full explanation lives in the tooltip, the cost of "close tab" stays
        // in the visible text.
        editBannerLabel.setText(fileNameOf(tab)
                + " is gone or unwritable on disk. Closing the tab discards your edits.");
        editBannerLabel.setTooltip(new Tooltip(fileNameOf(tab)
                + " was deleted, became unreadable, or was replaced by content this"
                + " editor cannot save (binary, oversized, or mixed line endings)."));
        editBannerPrimary.setText("keep mine");
        editBannerPrimary.setOnAction(e -> {
            hideEditBanner();
            session.keepMine();
        });
        editBannerSecondary.setText("close tab");
        editBannerSecondary.setOnAction(e -> {
            hideEditBanner();
            // Deliberately asymmetric with the user's own close gesture: NO
            // flush. The user just chose to abandon a file that is gone from
            // disk; writing the buffer back would recreate exactly the file
            // they abandoned.
            //
            // Passing flush=false is NOT enough on its own. Removing the tab
            // moves the selection, which synchronously runs the selection
            // listener's flushSession(oldTab) -- and the code area losing focus
            // runs another flush -- both before closeTab is even reached. So
            // the veto has to live in the session itself (abandon()), and the
            // property is detached up front so nothing can find the session
            // through the tab either. What closeTab still needs is handed to it
            // explicitly.
            session.abandon();
            tab.getProperties().remove("drydock.session");
            fileTabs.getTabs().remove(tab);
            // Tab.CLOSED_EVENT (and therefore setOnClosed) fires only from
            // TabPaneBehavior.closeTab -- a programmatic getTabs().remove does
            // NOT run the close handler, so the cleanup has to be invoked by
            // hand or this file stays in `openFiles` (unreopenable for the rest
            // of the session) and in `sessions`.
            closeTab(tab, false, session);
        });
        showEditBanner();
    }

    /** Reloads from disk, resolving a conflict in the disk's favour. */
    private void takeDisk(Tab tab, FileEditSession session) {
        session.takeDisk().whenComplete((text, failure) -> Platform.runLater(() -> {
            if (!fileTabs.getTabs().contains(tab)) {
                // The read is async (one executor hop plus one runLater), long
                // enough for the user to have closed the tab meanwhile. Same
                // guard the poll path carries: reloading an orphaned CodeArea --
                // or re-arming its session's write through reload's session.edit
                // -- would act on a file nobody has open any more.
                return;
            }
            if (failure != null) {
                showReadErrorBanner(tab, session, failure);
            } else {
                reload(tab, session, text);
            }
        }));
    }

    /**
     * Surfaces a failed {@link FileEditSession#takeDisk()}. Distinct from
     * {@link #showSaveErrorBanner}: a READ failed, so "could not save" is the
     * wrong verb, {@code lastError()} was never set by this path (it would
     * render "unknown error"), and retrying with {@code flush()} would be a
     * guaranteed no-op -- the session is still in CONFLICT, where the write
     * path returns early. The retry therefore re-invokes {@code takeDisk} and
     * the message comes from the failure itself.
     */
    private void showReadErrorBanner(Tab tab, FileEditSession session, Throwable failure) {
        if (!ownsBannerRow(tab)) {
            // Same shared-row hazard as the other banners: the read is async, so
            // the selection can move while it is in flight. The session is still
            // in CONFLICT, so reselecting the tab re-raises the conflict banner.
            return;
        }
        resetEditBanner(tab);
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        String detail = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        editBannerLabel.setText("Could not read " + fileNameOf(tab) + " from disk: " + detail);
        editBannerPrimary.setText("retry");
        editBannerPrimary.setOnAction(e -> {
            hideEditBanner();
            takeDisk(tab, session);
        });
        editBannerSecondary.setText("keep mine");
        editBannerSecondary.setOnAction(e -> {
            hideEditBanner();
            session.keepMine();
        });
        showEditBanner();
    }

    /** Surfaces a failed write; the buffer is kept and the next edit or Cmd+S retries. */
    private void showSaveErrorBanner(Tab tab, FileEditSession session) {
        if (!ownsBannerRow(tab)) {
            // Nothing re-raises a save failure later. For a tab still in the
            // pane that is fine: reselecting it re-derives the banner from the
            // session's ERROR state (raiseBannerFor). A tab that has already
            // been closed has no such second chance -- its session is dropped
            // and never reselected -- so closeTab logs at WARNING whenever the
            // close flush leaves the buffer unwritten, ERROR or not.
            return;
        }
        resetEditBanner(tab);
        IOException error = session.lastError();
        editBannerLabel.setText("Could not save " + fileNameOf(tab) + ": "
                + (error == null ? "unknown error" : error.getMessage()));
        editBannerPrimary.setText("retry");
        editBannerPrimary.setOnAction(e -> {
            hideEditBanner();
            session.flush();
        });
        editBannerSecondary.setVisible(false);
        editBannerSecondary.setManaged(false);
        showEditBanner();
    }

    /**
     * Clears every control the previous banner may have customised and claims
     * the row for {@code owner}. The banner is one shared row whose buttons
     * carry per-tab handlers and whose secondary button {@link
     * #showSaveErrorBanner} hides, so without this an error-then-conflict
     * sequence would render the conflict banner with no "reload" button --
     * leaving "keep mine" as the user's only option and quietly steering them
     * into clobbering the external edits this whole mechanism exists to
     * protect.
     */
    private void resetEditBanner(Tab owner) {
        editBannerOwner = owner;
        editBannerPrimary.setVisible(true);
        editBannerPrimary.setManaged(true);
        editBannerPrimary.setOnAction(null);
        editBannerSecondary.setVisible(true);
        editBannerSecondary.setManaged(true);
        editBannerSecondary.setOnAction(null);
    }

    private void showEditBanner() {
        editBanner.setVisible(true);
        editBanner.setManaged(true);
    }

    private void hideEditBanner() {
        editBanner.setVisible(false);
        editBanner.setManaged(false);
        resetEditBanner(null);
    }

    private String fileNameOf(Tab tab) {
        Path file = (Path) tab.getProperties().get("drydock.file");
        return file == null ? "This file" : file.getFileName().toString();
    }

    /** Opens {@code file} in a viewer tab (or focuses the existing one), optionally jumping to a 1-based line. */
    void openFile(Path file, Path relativePath, OptionalInt jumpToLine, String highlightQuery) {
        Tab existing = openFiles.get(file);
        if (existing != null) {
            fileTabs.getSelectionModel().select(existing);
            jumpToLine.ifPresent(line -> scrollTo(existing, line));
            return;
        }

        CodeArea area = new CodeArea();
        area.getStyleClass().add("code-area");
        area.setEditable(false);
        applyGutter(area, changedLines.getOrDefault(relativePath, Set.of()));

        Tab tab = new Tab();
        tab.setGraphic(buildFileTabGraphic(file));
        tab.setContent(new VirtualizedScrollPane<>(area));
        tab.getProperties().put("drydock.file", file);
        tab.getProperties().put("drydock.relative", relativePath);
        tab.setOnCloseRequest(event -> vetoCloseOfUnresolvedConflict(tab, event));
        tab.setOnClosed(e -> closeTab(tab, true));

        openFiles.put(file, tab);
        fileTabs.getTabs().add(tab);
        fileTabs.getSelectionModel().select(tab);
        updateEmptyState();
        updateDiffBanner();

        Thread.ofVirtual().start(() -> {
            FileContent content = loadFile(file);
            String text = content.text();
            SyntaxHighlighter.Language language =
                    SyntaxHighlighter.Language.fromFileName(file.getFileName().toString());
            var spans = SyntaxHighlighter.computeHighlighting(text, language);
            // Search-match highlighting is a SECOND style layer on top of
            // the lexer spans (handoff: "match highlight as a second style
            // layer"), merged by union so the token color survives.
            if (highlightQuery != null && !highlightQuery.isBlank()) {
                spans = spans.overlay(matchSpans(text, highlightQuery), (base, match) -> {
                    if (match.isEmpty()) {
                        return base;
                    }
                    List<String> merged = new ArrayList<>(base);
                    merged.addAll(match);
                    return merged;
                });
            }
            var styled = spans;
            Platform.runLater(() -> {
                // Text first, editing second: a failure attaching editing
                // (the session constructor's IllegalArgumentException, a
                // RejectedExecutionException) must degrade to a working
                // read-only tab, not a permanently blank one.
                replaceTextQuietly(tab, area, text);
                if (text.length() > 0) {
                    area.setStyleSpans(0, styled);
                }
                // The tab may have been closed while this load was off the
                // FX thread; setOnClosed only cleans up a session that
                // exists at close time, so attaching one here for a tab that
                // is no longer the open one for `file` would create an entry
                // nothing ever removes. Only attach if this tab is still it.
                if (content.editable() && openFiles.get(file) == tab) {
                    try {
                        attachEditing(tab, area, file, content);
                    } catch (RuntimeException ex) {
                        // A throw here (e.g. the session constructor's
                        // IllegalArgumentException) must not skip the chip
                        // update and jump-to-line below -- the tab still
                        // degrades to a working read-only tab at the right
                        // line rather than silently dropping both.
                        LOG.log(Level.WARNING, "Failed to attach editing for " + file, ex);
                    }
                }
                updateStatusChip();
                jumpToLine.ifPresent(line -> scrollTo(tab, line));
            });
        });
    }

    /**
     * Vetoes the user's ✕ on a tab whose session is in an unresolved {@link
     * FileEditSession.State#CONFLICT}, and shows them why.
     *
     * <p>The ordinary close flushes ({@link #closeTab(Tab, boolean)} →
     * {@link #flushSession}), but a conflicted session's write path returns
     * early -- so that same gesture, which saves a plain DIRTY tab, would drop
     * the user's buffer with no prompt, no banner and no log. A background
     * conflict is not even on screen when it happens. Rather than pick a winner
     * for them, keep the tab: select it and re-raise its conflict banner so the
     * "keep mine / reload" choice is in front of them. Once they answer, the
     * session leaves CONFLICT and the next ✕ closes normally.</p>
     *
     * <p>This is the ONLY route the user's gesture takes:
     * {@code Tab.TAB_CLOSE_REQUEST_EVENT} is fired by {@code
     * TabPaneBehavior.canCloseTab} before the tab is removed, and consuming it
     * is how a JavaFX tab close is refused. Programmatic removals
     * ({@code getTabs().remove}, e.g. the missing-file banner's "close tab")
     * never fire it, so the abandon path cannot be blocked here -- and an
     * abandoned session is excluded explicitly anyway, since the user has
     * already made that call and nothing may hold their tab hostage over it.</p>
     */
    private void vetoCloseOfUnresolvedConflict(Tab tab, Event event) {
        if (!(tab.getProperties().get("drydock.session") instanceof FileEditSession session)
                || session.state() != FileEditSession.State.CONFLICT
                || session.abandoned()) {
            return;
        }
        event.consume();
        LOG.log(Level.DEBUG, "Refusing to close " + fileNameOf(tab) + " with an unresolved conflict");
        fileTabs.getSelectionModel().select(tab);
        raiseBannerFor(tab);
    }

    private static StyleSpans<Collection<String>> matchSpans(String text, String query) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        int lastEnd = 0;
        int from = 0;
        int at;
        while ((at = lowerText.indexOf(lowerQuery, from)) >= 0) {
            builder.add(List.of(), at - lastEnd);
            builder.add(List.of("code-match"), lowerQuery.length());
            lastEnd = at + lowerQuery.length();
            from = lastEnd;
        }
        builder.add(List.of(), text.length() - lastEnd);
        return builder.create();
    }

    /**
     * Loads {@code file} for display. Content problems (binary, bad
     * encoding, oversized) come back as a non-{@link FileContent#editable()}
     * buffer rather than an exception; only an unreadable file falls back to
     * the error placeholder, which is likewise never editable.
     *
     * <p>The editor works in LF line terminators: {@link FileContent} normalises
     * CRLF-terminated files to LF for editing, and {@link FileContent#toDiskText(String)}
     * restores the file's own terminator on write. This keeps the editor's logic
     * simple and means a single-line edit does not rewrite every line of a CRLF file.
     */
    private FileContent loadFile(Path file) {
        try {
            return FileContent.load(file, MAX_FILE_BYTES);
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "Could not read " + file, e);
            return new FileContent("Could not read " + file + ": " + e.getMessage(),
                    false, false, FileContent.Terminator.NONE);
        }
    }

    /**
     * Turns a loaded tab into an editable one: an auto-saving {@link
     * FileEditSession} behind the {@link CodeArea}.
     *
     * <p>{@code suppressDirty} guards every PROGRAMMATIC text replacement
     * (this initial load, and every reload). {@code textProperty} cannot
     * tell a keystroke from a {@code replaceText}, so without the guard a
     * freshly opened file would immediately mark itself dirty and schedule a
     * write the user never asked for -- and a reload would re-arm a write of
     * the content it had just superseded.</p>
     */
    private void attachEditing(Tab tab, CodeArea area, Path file, FileContent content) {
        FileEditSession session =
                new FileEditSession(file, content, ioExecutor, SAVE_DEBOUNCE, MAX_FILE_BYTES);
        sessions.put(file, session);
        tab.getProperties().put("drydock.session", session);

        session.setOnStateChanged(state -> Platform.runLater(() -> {
            updateDirtyDot(tab);
            boolean isSelectedTab = fileTabs.getSelectionModel().getSelectedItem() == tab;
            if (state == FileEditSession.State.CLEAN) {
                // Recovery clears the banner: nothing else hides it, so a
                // "Could not save X" (or an unresolved conflict) would keep
                // sitting over a file that has since saved perfectly well.
                if (editBannerOwner == tab) {
                    hideEditBanner();
                }
                // Only flash "saved" for the tab actually being looked at --
                // this callback fires for whichever session just saved, which
                // may be a background tab, and must not stomp the chip
                // currently shown for a different, selected tab.
                if (isSelectedTab) {
                    announceSaved();
                }
                onSaved();
            } else if (isSelectedTab) {
                updateStatusChip();
            }
        }));
        session.setOnSaveFailed(error -> Platform.runLater(() -> {
            updateStatusChip();
            showSaveErrorBanner(tab, session);
        }));

        area.setEditable(true);
        area.textProperty().addListener((obs, oldText, newText) -> {
            if (Boolean.TRUE.equals(tab.getProperties().get("drydock.suppressDirty"))) {
                return;
            }
            session.edit(newText);
            updateDirtyDot(tab);
            updateStatusChip();
            scheduleRehighlight(tab, area);
        });
        // Cmd+S forces an immediate flush (spec decision 1).
        area.setOnKeyPressed(event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.S) {
                session.flush();
                event.consume();
            }
        });
        // Losing focus -- tab switch away from the Explorer, window losing
        // focus -- forces a flush too (spec: flush on blur, file-tab switch,
        // tab close). Debounce alone would leave edits unsaved for up to
        // SAVE_DEBOUNCE after the user's attention has already moved on.
        area.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                session.flush();
            }
        });
    }

    /**
     * Replaces a tab's text without marking it dirty; see {@link #attachEditing}.
     *
     * <p>Suppressing only for the dynamic extent of {@code area.replaceText}
     * relies on RichTextFX delivering {@code textProperty} changes
     * synchronously within that call -- never deferred to a later pulse or
     * another thread. If that ever stopped holding, a deferred emission
     * would silently dirty every file on open, with no test able to catch
     * it (no headless-FX harness exists for this package).</p>
     */
    private void replaceTextQuietly(Tab tab, CodeArea area, String text) {
        Object previous = tab.getProperties().get("drydock.suppressDirty");
        tab.getProperties().put("drydock.suppressDirty", Boolean.TRUE);
        try {
            area.replaceText(text);
        } finally {
            // Restore rather than force FALSE: a nested/reload call (Task 5)
            // must not clear an outer caller's suppression.
            tab.getProperties().put("drydock.suppressDirty", previous == null ? Boolean.FALSE : previous);
        }
    }

    /**
     * The single cleanup path for a tab leaving the strip, shared by the user's
     * close gesture ({@code setOnClosed}) and by the missing-file banner's
     * "close tab" action, which removes the tab programmatically and so never
     * fires that handler. Assumes the tab is already out of {@link #fileTabs}.
     *
     * <p>The flush's write may still be in flight on the (daemon) ioExecutor
     * thread when this returns; removing the session immediately would make it
     * invisible to {@link #flushPendingEdits} while that write is still
     * pending, which is exactly the data-loss window a shutdown-time flush
     * exists to close. Keep it reachable until the flush actually completes.</p>
     *
     * <p>This tab's own session is captured up front and removed from {@code
     * sessions} conditionally on identity ({@code Map.remove(key, value)}), not
     * on the path alone: if the file is closed and reopened before this flush
     * settles, a fresh {@link FileEditSession} is {@code put} under the same
     * key, and an unconditional keyed removal here would evict <em>that</em>
     * entry instead of this (closed) tab's own, however the two completions
     * race. Removing directly in the {@code whenComplete} callback (no {@link
     * Platform#runLater}) also matters at shutdown: {@code runLater} after the
     * FX toolkit has exited throws into a dropped stage, and the entry would
     * never be removed at all.</p>
     *
     * @param flush whether to force this tab's pending edits out on the way.
     *     True for every ordinary close. False for the missing-file banner
     *     alone: the user has just chosen to abandon a file that is already
     *     gone from disk, so writing its buffer back would recreate the very
     *     file they abandoned.
     */
    private void closeTab(Tab tab, boolean flush) {
        Object sessionProperty = tab.getProperties().get("drydock.session");
        closeTab(tab, flush, sessionProperty instanceof FileEditSession session ? session : null);
    }

    /**
     * As {@link #closeTab(Tab, boolean)}, but with the closing session handed in
     * rather than read off the tab. The missing-file "close tab" path detaches
     * the {@code drydock.session} property BEFORE removing the tab -- so that no
     * listener firing during the removal can find the session and flush it -- and
     * therefore has to supply it here, or the entry would be left in {@link
     * #sessions} for the rest of the viewer's life.
     */
    private void closeTab(Tab tab, boolean flush, FileEditSession closingSession) {
        Path file = (Path) tab.getProperties().get("drydock.file");
        CompletableFuture<Void> pending = flush ? flushSession(tab) : null;
        if (file != null) {
            openFiles.remove(file);
        }
        if (editBannerOwner == tab) {
            hideEditBanner();
        }
        updateEmptyState();
        updateStatusChip();
        if (closingSession != null && file != null) {
            if (pending != null) {
                pending.whenComplete((ignored, error) -> {
                    // The tab has already left the pane, so showSaveErrorBanner
                    // early-returns and the session is about to be dropped:
                    // this is the only trace a close-time loss will ever leave.
                    // A successful flush leaves CLEAN; SAVING means the
                    // debounce's own write is still in flight and may yet land.
                    // Everything else lost the bytes: ERROR from a failed write,
                    // CONFLICT from this flush's pre-write disk re-read (the
                    // close gesture is only vetoed for an ALREADY-known
                    // conflict), or DIRTY from a write the MISSING gate vetoed
                    // or the executor rejected at shutdown. A throwable from the
                    // future is a task that died in a way writeIfDirty never
                    // recorded (so no state to name); CONFLICT and vetoed DIRTY
                    // carry no throwable, so the state name is the diagnosis.
                    FileEditSession.State state = closingSession.state();
                    boolean lost = error != null
                            || (state != FileEditSession.State.CLEAN
                                && state != FileEditSession.State.SAVING);
                    if (lost) {
                        String message = "Unsaved edits to " + file
                                + " were NOT written while closing the tab"
                                + (error != null ? "" : " (" + state + ")");
                        Throwable cause = error != null ? error : closingSession.lastError();
                        if (cause != null) {
                            LOG.log(Level.WARNING, message, cause);
                        } else {
                            LOG.log(Level.WARNING, message);
                        }
                    }
                    sessions.remove(file, closingSession);
                });
            } else {
                sessions.remove(file, closingSession);
            }
        }
    }

    /**
     * Forces a pending save out for {@code tab} (blur / tab switch / close).
     * Null- and read-only-safe. Returns the flush's future, or {@code null}
     * if there was no session to flush.
     */
    private CompletableFuture<Void> flushSession(Tab tab) {
        if (tab != null && tab.getProperties().get("drydock.session") instanceof FileEditSession session) {
            return session.flush();
        }
        return null;
    }

    /**
     * Blocks until every open file's pending edits are on disk. The shutdown
     * path's entry point -- {@link #ioExecutor}'s thread is a daemon, so a
     * fire-and-forget flush is killed mid-write at JVM exit.
     *
     * <p>{@code timeout} is a shared deadline for the whole call, not a
     * per-session budget: worst case with N open files is bounded by {@code
     * timeout}, not N * timeout. Each session gets whatever of the budget
     * remains when its turn comes, down to zero. Each session's flush is
     * also isolated from the others -- {@link FileEditSession} swallows
     * write failures, but not a throwing {@code onSaveFailed} handler (this
     * class's handler calls {@link Platform#runLater}, which throws once the
     * FX toolkit has exited, exactly when this path can run) -- so one
     * failure logs and moves on rather than aborting every file behind it.</p>
     */
    void flushPendingEdits(Duration timeout) {
        List<FileEditSession> snapshot;
        synchronized (sessions) {
            snapshot = new ArrayList<>(sessions.values());
        }
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        for (FileEditSession session : snapshot) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            // Gated on there being something to lose: a CLEAN session's flush
            // is a no-op whether or not the budget is gone, and warning about
            // it would train readers to scroll past the line that means a real
            // file really did not get written.
            if (remainingNanos <= 0 && session.state() != FileEditSession.State.CLEAN) {
                // The shared deadline is already exhausted: flushBlocking
                // would take its Duration.ZERO/TimeoutException path
                // immediately, which only reports through onSaveFailed --
                // in this class just a chip repaint, and at shutdown the
                // chip is long gone. Without this log a genuinely unflushed
                // file leaves no trace anywhere.
                LOG.log(Level.WARNING,
                        "Shutdown flush deadline exhausted before flushing " + session.file()
                                + "; its pending edits may be lost");
            }
            Duration remaining = Duration.ofNanos(Math.max(0, remainingNanos));
            try {
                session.flushBlocking(remaining);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Failed to flush pending edits for " + session.file(), e);
            }
            if (session.lastError() != null) {
                LOG.log(Level.WARNING, "Pending edits for " + session.file()
                        + " may not have been saved at shutdown", session.lastError());
            }
            if (session.state() == FileEditSession.State.CONFLICT) {
                // Checked AFTER the flush, not before: as well as a conflict
                // the user was already shown and never answered, the flush
                // itself can raise one -- the write path re-reads the file
                // immediately before writing, so a change that landed inside
                // the poller's blind window surfaces right here. The user's
                // close gesture is vetoed while a conflict is unresolved
                // (vetoCloseOfUnresolvedConflict), but shutdown cannot ask
                // anyone anything: these edits are genuinely not going to disk
                // and this line is the only trace they will ever leave.
                LOG.log(Level.WARNING, "Unsaved edits to " + session.file()
                        + " were NOT written at shutdown: the file changed on disk"
                        + " and the conflict was never resolved");
            }
            if (session.state() == FileEditSession.State.DIRTY) {
                // A successful flush leaves CLEAN, so DIRTY here can only mean
                // the write was vetoed -- the file is gone or no longer
                // editable on disk and the missing-file question was never
                // answered (possibly never even asked, on a background tab) --
                // or a keystroke landed after the write started. Either way
                // these bytes are not going to disk, shutdown cannot ask
                // anyone anything, and neither of the two gates above fires:
                // lastError is null and the state is not CONFLICT. Without
                // this line the loss is completely silent.
                LOG.log(Level.WARNING, "Unsaved edits to " + session.file()
                        + " were NOT written at shutdown: the save was either vetoed"
                        + " (the file is gone or no longer editable on disk) or"
                        + " superseded by a later edit");
            }
        }
    }

    /**
     * Flushes unsaved edits, stops every timer this viewer owns and shuts
     * {@link #ioExecutor} down. One-way and idempotent: the viewer is dead
     * afterwards.
     *
     * <p>Scene detach cannot be the trigger -- it fires on every sub-tab
     * switch and the executor must survive the re-attach -- so nothing else
     * ever stops that executor, and each session tab whose Explorer was
     * opened would otherwise keep an {@code explorer-file-io} thread alive
     * for the life of the process. Called from the owning tab's removal and
     * from the shutdown chain.</p>
     */
    void dispose() {
        dispose(true);
    }

    /**
     * As {@link #dispose()}, but with the flush optional.
     *
     * <p>{@code flush=false} exists for a caller that has ALREADY flushed this
     * viewer -- {@code MainWorkspace.flushExplorerEdits}, which flushes every
     * Explorer first and only then disposes them all. Since {@code dispose()}
     * is itself a bounded flush, letting it flush again there would give each
     * Explorer its budget twice: on a hung disk, 2 * N * {@code
     * DISPOSE_FLUSH_TIMEOUT} of frozen FX thread instead of N.</p>
     *
     * @param flush whether to write pending edits out before tearing down.
     *     Only ever false when the caller has just flushed this viewer itself.
     */
    void dispose(boolean flush) {
        if (disposed) {
            return;
        }
        if (flush) {
            // Before the shutdown: flushPendingEdits' blocking waits run ON the
            // ioExecutor, so a shutdown() first would reject them and drop the
            // very edits this call exists to save.
            flushPendingEdits(DISPOSE_FLUSH_TIMEOUT);
        }
        disposed = true;
        stopPolling();
        stopTransitions();
        ioExecutor.shutdown();
    }

    /**
     * Schedules a diff-overlay refresh after a save. Coalesced rather than
     * per-save: {@link DiffOverlay#invalidate()} drops a cache the Review tab
     * shares, so each refresh costs both views a fresh {@code git diff}, and
     * a 2s save debounce would otherwise mean a subprocess every couple of
     * seconds while typing.
     */
    private void onSaved() {
        if (diffOverlay == null) {
            return;
        }
        if (getScene() == null) {
            // Detach runs flushAllSessions() and only then stopTransitions();
            // those flushes complete afterwards on the ioExecutor and hop back
            // here, so arming a fresh debounce now would outlive the detach the
            // stop was supposed to cover -- dropping the diff cache Review
            // shares and spawning a git diff for a viewer nobody is looking at.
            return;
        }
        if (diffRefreshDebounce != null) {
            diffRefreshDebounce.stop();
        }
        diffRefreshDebounce = new PauseTransition(
                javafx.util.Duration.millis(DIFF_REFRESH_COALESCE.toMillis()));
        diffRefreshDebounce.setOnFinished(e -> {
            diffOverlay.invalidate();
            refreshDiffOverlay();
        });
        diffRefreshDebounce.play();
    }

    /**
     * Recomputes the lexer spans for {@code area} off the FX thread. The
     * search-match layer is deliberately not re-derived: it is a load-time
     * artifact of "open from search result", and recomputing it while the
     * user types would fight the caret.
     */
    private void rehighlight(Tab tab, CodeArea area, String text) {
        Path file = (Path) tab.getProperties().get("drydock.file");
        if (file == null) {
            return;
        }
        SyntaxHighlighter.Language language =
                SyntaxHighlighter.Language.fromFileName(file.getFileName().toString());
        Thread.ofVirtual().start(() -> {
            var spans = SyntaxHighlighter.computeHighlighting(text, language);
            Platform.runLater(() -> {
                if (text.equals(area.getText()) && !text.isEmpty()) {
                    area.setStyleSpans(0, spans);
                }
            });
        });
    }

    private void scheduleRehighlight(Tab tab, CodeArea area) {
        if (highlightDebounce != null) {
            highlightDebounce.stop();
        }
        highlightDebounce = new PauseTransition(javafx.util.Duration.millis(HIGHLIGHT_DEBOUNCE.toMillis()));
        highlightDebounce.setOnFinished(e -> rehighlight(tab, area, area.getText()));
        highlightDebounce.play();
    }

    private void scrollTo(Tab tab, int oneBasedLine) {
        if (tab.getContent() instanceof VirtualizedScrollPane<?> pane
                && pane.getContent() instanceof CodeArea area) {
            int paragraph = Math.max(0, Math.min(oneBasedLine - 1, area.getParagraphs().size() - 1));
            area.moveTo(paragraph, 0);
            area.showParagraphAtCenter(paragraph);
        }
    }

    private HBox buildFileTabGraphic(Path file) {
        String name = file.getFileName().toString();
        Label badge = new Label(FileTypeBadges.badgeFor(name));
        badge.getStyleClass().add("filetype-badge");
        Label label = new Label(name);
        label.getStyleClass().add("viewer-tab-label");
        HBox graphic = new HBox(6, badge, label);
        graphic.setAlignment(Pos.CENTER_LEFT);
        return graphic;
    }

    private void updateBreadcrumb(Tab tab) {
        breadcrumb.getChildren().clear();
        if (tab == null) {
            return;
        }
        Path relative = (Path) tab.getProperties().get("drydock.relative");
        Path shown = relative != null ? relative : (Path) tab.getProperties().get("drydock.file");
        if (shown == null) {
            return;
        }
        breadcrumb.getChildren().addAll(UiFormats.breadcrumbSegments(shown));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        breadcrumb.getChildren().addAll(spacer, statusChip, gutterToggle);
        updateStatusChip();
    }

    /**
     * Drives the breadcrumb chip from the selected tab's session (spec
     * decision 4). {@link FileEditSession.State#CLEAN} always renders as the
     * resting "editable" chip here -- a freshly opened file, or switching to
     * an already-clean tab, must not claim "saved" for a save nobody just
     * made. The "saved ✓" flash is a separate, one-shot announcement (see
     * {@link #announceSaved}) fired only on an actual DIRTY/SAVING -> CLEAN
     * transition.
     */
    /**
     * Diagnostic-only (see MainWorkspace.diagTypeInExplorer): appends {@code
     * text} to the selected tab's code area, driving the real
     * {@code textProperty} listener -- so this dirties the session, arms the
     * debounce and moves the chip exactly as typing does.
     */
    public void diagType(String text) {
        Tab selected = fileTabs.getSelectionModel().getSelectedItem();
        if (selected != null
                && selected.getContent() instanceof VirtualizedScrollPane<?> pane
                && pane.getContent() instanceof CodeArea area) {
            area.requestFocus();
            area.insertText(0, text);
        }
    }

    private void updateStatusChip() {
        Tab selected = fileTabs.getSelectionModel().getSelectedItem();
        FileEditSession session = selected == null ? null
                : (FileEditSession) selected.getProperties().get("drydock.session");
        statusChip.getStyleClass().removeAll("read-only-chip", "editable-chip", "unsaved-chip",
                "saved-chip", "error-chip");
        if (session == null) {
            statusChip.setText("read-only");
            statusChip.getStyleClass().add("read-only-chip");
            return;
        }
        switch (session.state()) {
            case CLEAN -> {
                statusChip.setText("editable");
                statusChip.getStyleClass().add("editable-chip");
            }
            case DIRTY, SAVING -> {
                statusChip.setText("unsaved");
                statusChip.getStyleClass().add("unsaved-chip");
            }
            case CONFLICT -> {
                statusChip.setText("conflict");
                statusChip.getStyleClass().add("error-chip");
            }
            case ERROR -> {
                statusChip.setText("save failed");
                statusChip.getStyleClass().add("error-chip");
            }
        }
    }

    /**
     * One-shot "saved ✓" flash for a real save, then fades back to
     * {@link #updateStatusChip}'s resting "editable" chip. Caller ({@link
     * #attachEditing}'s state-change handler) has already confirmed this is
     * the selected tab and that the transition was a genuine save, not just
     * a repaint of an already-clean file.
     */
    private void announceSaved() {
        statusChip.getStyleClass().removeAll("read-only-chip", "editable-chip", "unsaved-chip",
                "saved-chip", "error-chip");
        statusChip.setText("saved ✓");
        statusChip.getStyleClass().add("saved-chip");
        fadeChipToEditable();
    }

    /** "saved ✓" settles back to the resting "editable" state after a moment. */
    private void fadeChipToEditable() {
        if (chipReset != null) {
            chipReset.stop();
        }
        if (getScene() == null) {
            // Same post-detach re-arming hazard as onSaved(): a flush completing
            // after stopTransitions() must not leave a timer running on a viewer
            // that is out of the scene graph. The chip is repainted on re-attach.
            return;
        }
        chipReset = new PauseTransition(javafx.util.Duration.seconds(2));
        chipReset.setOnFinished(e -> {
            Tab selected = fileTabs.getSelectionModel().getSelectedItem();
            if (selected != null && selected.getProperties().get("drydock.session")
                    instanceof FileEditSession session && session.state() == FileEditSession.State.CLEAN) {
                updateStatusChip();
            }
        });
        chipReset.play();
    }

    /** A "•" on the file tab while it holds unsaved edits, so a background tab is not silently dirty. */
    private void updateDirtyDot(Tab tab) {
        if (!(tab.getProperties().get("drydock.session") instanceof FileEditSession session)) {
            return;
        }
        boolean dirty = session.state() == FileEditSession.State.DIRTY
                || session.state() == FileEditSession.State.CONFLICT
                || session.state() == FileEditSession.State.ERROR;
        if (tab.getGraphic() instanceof HBox graphic) {
            boolean shown = graphic.getChildren().stream()
                    .anyMatch(node -> node.getStyleClass().contains("viewer-tab-dirty"));
            if (shown == dirty) {
                // Called on every poll tick (1.5s) and every keystroke:
                // rebuilding an identical Label each time is pure garbage and
                // can flicker the tab graphic.
                return;
            }
            graphic.getChildren().removeIf(node -> node.getStyleClass().contains("viewer-tab-dirty"));
            if (dirty) {
                Label dot = new Label("•");
                dot.getStyleClass().add("viewer-tab-dirty");
                graphic.getChildren().add(dot);
            }
        }
    }

    private void updateEmptyState() {
        boolean empty = fileTabs.getTabs().isEmpty();
        emptyState.setVisible(empty);
        emptyState.setManaged(empty);
        if (empty) {
            updateBreadcrumb(null);
            setTop(null);
        } else {
            topBox.getChildren().setAll(breadcrumb, diffBanner, editBanner);
            setTop(topBox);
        }
    }

    private void toggleGutter() {
        gutterVisible = !gutterVisible;
        for (Tab tab : fileTabs.getTabs()) {
            if (tab.getContent() instanceof VirtualizedScrollPane<?> pane
                    && pane.getContent() instanceof CodeArea area) {
                applyGutter(area, changedLinesFor(tab));
            }
        }
    }

    /**
     * Paragraph gutter: line numbers (toggleable) plus the GREEN
     * changed-line marker for lines the current diff scope touches
     * (design handoff section C: the Explorer viewer marks the scope's
     * changed lines). The marker column is always present so line starts
     * stay aligned whether or not a given line changed.
     */
    private void applyGutter(CodeArea area, Set<Integer> changed) {
        if (!gutterVisible && changed.isEmpty()) {
            area.setParagraphGraphicFactory(null);
            return;
        }
        IntFunction<Node> numbers = gutterVisible ? LineNumberFactory.get(area) : null;
        area.setParagraphGraphicFactory(line -> {
            HBox box = new HBox(2);
            box.setAlignment(Pos.CENTER_LEFT);
            Region marker = new Region();
            marker.getStyleClass().add("changed-line-marker");
            if (changed.contains(line + 1)) {
                marker.getStyleClass().add("on");
            }
            box.getChildren().add(marker);
            if (numbers != null) {
                box.getChildren().add(numbers.apply(line));
            }
            return box;
        });
    }

    /** Filetype badge letters shared by the search rail and the viewer tabs. */
    static final class FileTypeBadges {
        private FileTypeBadges() {
        }

        static String badgeFor(String fileName) {
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".java")) {
                return "J";
            }
            if (lower.endsWith(".kt") || lower.endsWith(".kts")) {
                return "K";
            }
            if (lower.endsWith(".gradle")) {
                return "G";
            }
            if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
                return "M";
            }
            if (lower.endsWith(".css")) {
                return "#";
            }
            if (lower.endsWith(".json")) {
                return "{}";
            }
            if (lower.endsWith(".xml") || lower.endsWith(".html")) {
                return "<>";
            }
            if (lower.endsWith(".sh") || lower.endsWith(".zsh") || lower.endsWith(".bash")) {
                return "$";
            }
            int dot = lower.lastIndexOf('.');
            if (dot >= 0 && dot < lower.length() - 1) {
                return lower.substring(dot + 1, dot + 2).toUpperCase(Locale.ROOT);
            }
            return "·";
        }
    }
}
