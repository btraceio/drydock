package app.drydock.ui.explorer;

import app.drydock.ui.UiFormats;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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

    /** Re-highlight debounce, matching SearchRail's keystroke-debounce convention. */
    private static final Duration HIGHLIGHT_DEBOUNCE = Duration.ofMillis(150);

    private PauseTransition highlightDebounce;

    /** Coalescing window for the post-save diff refresh (the cache is shared with Review). */
    private static final Duration DIFF_REFRESH_COALESCE = Duration.ofSeconds(2);

    private PauseTransition diffRefreshDebounce;

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
        editBanner.getChildren().setAll(editBannerLabel, editBannerPrimary, editBannerSecondary);
        editBanner.setAlignment(Pos.CENTER_LEFT);
        editBanner.getStyleClass().add("viewer-edit-banner");
        editBanner.setVisible(false);
        editBanner.setManaged(false);

        centerStack.getChildren().setAll(fileTabs, emptyState);
        setCenter(centerStack);

        fileTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            flushSession(oldTab);
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
            } else {
                startPolling();
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

    private void flushAllSessions() {
        for (FileEditSession session : sessions.values()) {
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
     */
    private void pollOpenFiles() {
        try {
            for (Tab tab : List.copyOf(fileTabs.getTabs())) {
                if (tab.getProperties().get("drydock.session") instanceof FileEditSession session) {
                    session.poll().thenAccept(
                            result -> Platform.runLater(() -> applyPollResult(tab, session, result)));
                }
            }
        } catch (RuntimeException e) {
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
        switch (result.outcome()) {
            case UNCHANGED -> { }
            case RELOAD -> reload(tab, result.text());
            case CONFLICT -> showConflictBanner(tab, session);
            case MISSING -> showMissingBanner(tab, session);
        }
        updateStatusChip();
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
     */
    private void reload(Tab tab, String text) {
        if (!(tab.getContent() instanceof VirtualizedScrollPane<?> pane)
                || !(pane.getContent() instanceof CodeArea area)) {
            return;
        }
        int paragraph = area.getCurrentParagraph();
        int column = area.getCaretColumn();
        replaceTextQuietly(tab, area, text);
        area.getUndoManager().forgetHistory();
        int safeParagraph = Math.max(0, Math.min(paragraph, area.getParagraphs().size() - 1));
        int safeColumn = Math.max(0, Math.min(column, area.getParagraphLength(safeParagraph)));
        area.moveTo(safeParagraph, safeColumn);
        rehighlight(tab, area, text);
    }

    private void showConflictBanner(Tab tab, FileEditSession session) {
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
            session.takeDisk().whenComplete((text, failure) -> Platform.runLater(() -> {
                if (failure != null) {
                    showSaveErrorBanner(tab, session);
                } else {
                    reload(tab, text);
                }
            }));
        });
        showEditBanner();
    }

    private void showMissingBanner(Tab tab, FileEditSession session) {
        editBannerLabel.setText(fileNameOf(tab) + " is no longer on disk.");
        editBannerPrimary.setText("keep mine");
        editBannerPrimary.setOnAction(e -> {
            hideEditBanner();
            session.keepMine();
        });
        editBannerSecondary.setText("close tab");
        editBannerSecondary.setOnAction(e -> {
            hideEditBanner();
            fileTabs.getTabs().remove(tab);
        });
        showEditBanner();
    }

    /** Surfaces a failed write; the buffer is kept and the next edit or Cmd+S retries. */
    private void showSaveErrorBanner(Tab tab, FileEditSession session) {
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

    private void showEditBanner() {
        editBanner.setVisible(true);
        editBanner.setManaged(true);
    }

    private void hideEditBanner() {
        editBanner.setVisible(false);
        editBanner.setManaged(false);
        editBannerSecondary.setVisible(true);
        editBannerSecondary.setManaged(true);
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
        tab.setOnClosed(e -> {
            // The flush's write may still be in flight on the (daemon)
            // ioExecutor thread when this handler returns; removing the
            // session immediately would make it invisible to
            // flushPendingEdits while that write is still pending, which is
            // exactly the data-loss window a shutdown-time flush exists to
            // close. Keep it reachable until the flush actually completes.
            //
            // Capture this tab's own session up front and remove it from
            // `sessions` conditionally on identity (Map.remove(key, value)),
            // not on the path alone: if the file is closed and reopened
            // before this flush settles, a fresh FileEditSession is `put`
            // under the same key, and an unconditional keyed removal here
            // would evict *that* entry instead of this (closed) tab's own,
            // however the two completions race. Removing directly in the
            // whenComplete callback (no Platform.runLater) also matters at
            // shutdown: runLater after the FX toolkit has exited throws into
            // a dropped stage, and the entry would never be removed at all.
            Object sessionProperty = tab.getProperties().get("drydock.session");
            FileEditSession closingSession =
                    sessionProperty instanceof FileEditSession session ? session : null;
            CompletableFuture<Void> pending = flushSession(tab);
            openFiles.remove(file);
            updateEmptyState();
            updateStatusChip();
            if (closingSession != null) {
                if (pending != null) {
                    pending.whenComplete((ignored, error) -> sessions.remove(file, closingSession));
                } else {
                    sessions.remove(file, closingSession);
                }
            }
        });

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
     * CRLF-terminated files to LF for editing, and {@link FileContent#toDiskText()}
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
            if (remainingNanos <= 0) {
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
        }
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
