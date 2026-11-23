package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.intellij.agent.SessionHostModel.Outcome;
import app.drydock.intellij.agent.SessionHostModel.Slot;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationActivationListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.terminal.TerminalExecutionConsole;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.Timer;

import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One window's session host: the project-lifetime presentation authority.
 * It owns the pane bookkeeping ({@link SessionHostModel}), decides which
 * requests are intentional, persists the one remembered session id, announces
 * a changed selection, and fences delayed focus.
 *
 * <p><b>Intent.</b> Only {@link #show}, {@link #focus} and {@link #previous}
 * are intentional. A committed change of the <em>current</em> session
 * identity is the only thing that writes {@link SessionHostState} and
 * publishes {@link SessionHostListener#sessionSelected}; re-activating the
 * current session, hold, close, invalidation and availability changes do
 * neither. Previous/current are data, never a target by themselves. The one
 * exception in spirit is {@link #sessionDone}: a Done flow that emptied the
 * shown slot picks the next remaining catalog session and shows it through
 * {@link #show}, so the committed identity and event follow what is now on
 * screen.</p>
 *
 * <p><b>Adoption.</b> Construction restores the remembered id into primary
 * through {@link SessionHostModel#restorePrimary}: passive, with no intent,
 * history, focus permission, state write, event, open or launch.</p>
 *
 * <p><b>Epoch and focus permission.</b> Every accepted request, refused
 * request, hide, focus departure, hold, close of the held pane, invalidation
 * of a shown pane and the disposal advance a presentation epoch. A delayed
 * focus carries the epoch it was issued under. A matching epoch is necessary
 * but not sufficient: {@linkplain #focusPermitted permission} is an explicit
 * issued state that only an accepted intentional {@link #show}, {@link #focus}
 * or {@link #previous} sets, and that construction, passive adoption and
 * availability recovery never set. A refused newer attempt (unavailable show,
 * absent or unavailable previous, focus of a pane not on screen) changes no
 * layout, history, remembered id, state or event but cancels older focus; hide,
 * departure, disposal and the other layout changes revoke it, so after
 * departure both the old token and the current epoch are refused. This is
 * separate from the metadata loader's own result generations.</p>
 *
 * <p><b>Project and panes.</b> Every controller retains a real, non-null
 * {@link Project}. {@link #paneFor} constructs one {@link SessionPane} per
 * session on first request and keeps it for the controller's life, so the
 * pane, its root and the console it borrows stay the same across slot
 * changes, hold, hide and reopen. The controller never releases, moves,
 * starts or replays a console, and never parents a root; the model
 * operations above do not touch panes.</p>
 *
 * <p><b>Host and lease.</b> As the pane {@link SessionPane.Host} the
 * controller forwards a strip click once to the pane that is attached in the
 * primary or held slot, and turns a deliberate pane focus into {@link #focus}
 * only while an attachment lease is held. The lease is one
 * {@link FileEditor}, by identity: the same owner may acquire again, another
 * owner is refused, a non-owner release is ignored. The owner's release
 * revokes pending focus, hides, detaches the two mounted slot roots and
 * clears the lease; panes, consoles and history stay. Facts are read from the
 * latest-result map that {@link #apply} alone writes.</p>
 *
 * <p><b>Metadata.</b> One {@link SessionFactsLoader}, created lazily at the
 * first retained pane: a new pane loads once, and an accepted
 * {@link #show}, {@link #focus} or {@link #previous} of a pane already held
 * retries a FAILED or absent answer (a known one stays cached in the loader).
 * Reading facts, refresh, age ticks, results and visibility never select and
 * never grant focus. A result may arrive on any thread, even inside the
 * selection; it is applied on the UI thread, once per ID and generation,
 * only to its own retained pane, and moves no layout, history or focus.</p>
 *
 * <p><b>Lifetime.</b> {@link #dispose()} claims one closed flag, checked
 * with the {@link Project}'s own disposal by every request, so rejection is
 * immediate on any thread. The presentation cleanup (panes, lease) runs on
 * the UI thread, inline or queued, and never touches a borrowed console. The
 * loader is disposed once under the same claim; the plugin's shared
 * initialization and executor are not the controller's to cancel.</p>
 *
 * <p><b>Events.</b> The constructor subscribes, on the application bus and
 * with the controller as the connection's parent, to {@link AgentExecutionListener},
 * {@link DrydockSessionsListener} and {@link SessionActivityListener}. A callback
 * may run on any thread; it only marks what changed and queues one UI-thread
 * flush per burst. An execution or activity event refreshes that session's
 * retained pane alone (never closing a document, moving a layout, history or
 * focus, and never releasing or moving a console); a catalog change repaints
 * every retained pane and asks the loader, on a pooled thread, to
 * {@linkplain SessionFactsLoader#refreshInitialized refresh the initialized
 * answers} once, whose results return through {@link #apply} by generation. While
 * an attachment lease is held one 10 s timer repaints the shown panes (age
 * text); it stops with the lease and at disposal. Presentation state is
 * UI-thread only, no I/O.</p>
 */
public final class SessionHostController implements Disposable, SessionPane.Host {

    /**
     * Immutable projection of the model, rebuilt only when it changes.
     * {@code current} and {@code previous} are history data, not targets.
     */
    record Layout(Optional<ManagedSessionId> primary, Optional<ManagedSessionId> held,
                  Optional<ManagedSessionId> current, Optional<ManagedSessionId> previous,
                  Slot focusedSlot) {
    }

    private final SessionHostModel model = new SessionHostModel();
    private final Project project;
    private final SessionHostState state;
    private final Consumer<ManagedSessionId> selectionPublisher;
    private final Function<Consumer<SessionFactsLoader.Result>, SessionFactsLoader> loaders;
    /** The catalog's live session ids, in catalog order; the Done advance picks from it. */
    private final Supplier<List<ManagedSessionId>> remainingSessions;
    private Layout layout;
    private long epoch;
    private boolean permitted;
    private boolean hidden;
    /** The one closed state: claimed under {@link #closing}, read by every lifetime check. */
    private volatile boolean disposed;
    private final Object closing = new Object();
    /** The one lazy loader; created and taken for disposal only under {@link #closing}. */
    private SessionFactsLoader loader;
    private final Map<ManagedSessionId, SessionPane> panes = new LinkedHashMap<>();
    private final Map<ManagedSessionId, SessionFactsLoader.Result> latest = new HashMap<>();
    /** Focus intents waiting for the pane to mount the console; one per id, fired once. */
    private final Map<ManagedSessionId, Runnable> consoleFocusAwaiting = new HashMap<>();
    private FileEditor lease;
    private static final Logger LOG = Logger.getInstance(SessionHostController.class);
    private static final int AGE_TICK_MILLIS = 10_000;
    /** Sessions marked by any thread, drained by the next flush. */
    private final Set<ManagedSessionId> dirty = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean catalogDirty = new AtomicBoolean();
    private final AtomicBoolean flushQueued = new AtomicBoolean();
    /** The one age tick, running only while a lease is held; UI thread. */
    private final Timer ageTicker = new Timer(AGE_TICK_MILLIS, event -> refreshShown());
    private final Map<ManagedSessionId, Integer> refreshes = new HashMap<>();
    /** What the user is actually looking at (T20 reads it); disposed with this controller. */
    final SessionHostVisibility visibility;

    /** The platform's constructor for the declarative project service. */
    public SessionHostController(Project project) {
        this(project, SessionHostState.getInstance(Objects.requireNonNull(project, "project")),
                id -> project.getMessageBus().syncPublisher(SessionHostListener.TOPIC).sessionSelected(id));
    }

    SessionHostController(Project project, SessionHostState state, Consumer<ManagedSessionId> selectionPublisher) {
        this(project, state, selectionPublisher, listener -> SessionFactsLoader.forProject(
                project, DrydockPluginService.getInstance(), listener));
    }

    /** The loader factory is invoked lazily, at most once, and resolves the plugin only then. */
    SessionHostController(Project project, SessionHostState state, Consumer<ManagedSessionId> selectionPublisher,
                          Function<Consumer<SessionFactsLoader.Result>, SessionFactsLoader> loaders) {
        this(project, state, selectionPublisher, loaders, SessionHostController::catalogSessions);
    }

    /** The loader and remaining-session factories are invoked lazily, at most once, and resolve the plugin only then. */
    SessionHostController(Project project, SessionHostState state, Consumer<ManagedSessionId> selectionPublisher,
                          Function<Consumer<SessionFactsLoader.Result>, SessionFactsLoader> loaders,
                          Supplier<List<ManagedSessionId>> remainingSessions) {
        this.project = Objects.requireNonNull(project, "project");
        this.state = Objects.requireNonNull(state, "state");
        this.selectionPublisher = Objects.requireNonNull(selectionPublisher, "selectionPublisher");
        this.loaders = Objects.requireNonNull(loaders, "loaders");
        this.remainingSessions = Objects.requireNonNull(remainingSessions, "remainingSessions");
        state.selectedSession().ifPresent(model::restorePrimary);
        this.layout = project();
        ageTicker.setRepeats(true);
        var connection = ApplicationManager.getApplication().getMessageBus().connect(this);
        connection.subscribe(AgentExecutionListener.TOPIC, new AgentExecutionListener() {
            @Override
            public void executionShown(ManagedSessionId sessionId, Project to) {
                mark(sessionId);
            }

            @Override
            public void executionReleased(ManagedSessionId sessionId) {
                mark(sessionId);
            }

            @Override
            public void executionMoving(ManagedSessionId sessionId, boolean moving) {
                mark(sessionId);
            }

            @Override
            public void executionTerminated(ManagedSessionId sessionId, boolean stopRequested, Integer exitCode) {
                mark(sessionId);
            }
        });
        connection.subscribe(DrydockSessionsListener.TOPIC, (DrydockSessionsListener) () -> {
            catalogDirty.set(true);
            queueFlush();
        });
        connection.subscribe(ApplicationActivationListener.TOPIC, new ApplicationActivationListener() {
            @Override
            public void applicationActivated(@NotNull IdeFrame frame) {
                frameActivated(frame);
            }
        });
        connection.subscribe(SessionActivityListener.TOPIC,
                (SessionActivityListener) (id, activity) -> mark(id));
        this.visibility = new SessionHostVisibility(project, this, this::layout, panes::get, this::closed);
        this.focusRequest = target -> IdeFocusManager.getInstance(project).requestFocus(target, true);
    }

    /** Any thread: one session's pane needs a repaint. */
    private void mark(ManagedSessionId id) {
        if (!closed()) {
            dirty.add(id);
            queueFlush();
        }
    }

    /** Any thread: at most one UI-thread flush is queued for a burst of marks. */
    private void queueFlush() {
        if (!closed() && flushQueued.compareAndSet(false, true)) {
            ApplicationManager.getApplication().invokeLater(
                    this::flush, ModalityState.any(), project.getDisposed());
        }
    }

    /**
     * The coalesced refresh, on the UI thread: repaints each marked retained pane once and, for
     * a catalog change, every retained pane, then hands the loader's refresh to a pooled thread.
     * Selects, moves and focuses nothing.
     */
    private void flush() {
        flushQueued.set(false);
        if (closed()) {
            dirty.clear();
            return;
        }
        boolean catalog = catalogDirty.getAndSet(false);
        for (ManagedSessionId id : Set.copyOf(dirty)) {
            dirty.remove(id);
            if (!catalog) {
                refreshPane(id);
            }
        }
        if (catalog) {
            List.copyOf(panes.keySet()).forEach(this::refreshPane);
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                SessionFactsLoader active = initializedLoader();
                if (active != null) {
                    active.refreshInitialized();
                }
            });
        }
    }

    private void refreshPane(ManagedSessionId id) {
        SessionPane pane = panes.get(id);
        if (pane != null && !closed()) {
            refreshes.merge(id, 1, Integer::sum);
            pane.refresh();
        }
    }

    /** The age tick: repaints the panes in the primary and held slots, nothing else. */
    private void refreshShown() {
        layout.primary().ifPresent(this::refreshPane);
        layout.held().ifPresent(this::refreshPane);
    }

    /** The loader if one was ever created and is still open; never creates one. */
    private SessionFactsLoader initializedLoader() {
        synchronized (closing) {
            return closed() ? null : loader;
        }
    }

    /** How often {@code id}'s pane was repainted by an event or tick (UI thread). */
    int refreshCount(ManagedSessionId id) {
        return refreshes.getOrDefault(id, 0);
    }

    /** Whether the age tick is running; true only while a lease is held. */
    boolean ageTickRunning() {
        return ageTicker.isRunning();
    }

    public static SessionHostController getInstance(Project project) {
        return project.getService(SessionHostController.class);
    }

    /** The cached read-only layout; the same instance until something changes. */
    Layout layout() {
        return layout;
    }

    /** Whether this controller or its project is gone; every request is refused then. */
    private boolean closed() {
        return disposed || project.isDisposed();
    }

    @Override
    public long epoch() {
        return epoch;
    }

    /** Whether a focus request issued under {@code requestEpoch} may still take focus. */
    @Override
    public boolean focusPermitted(long requestEpoch) {
        return !closed() && permitted && !hidden && requestEpoch == epoch;
    }

    /**
     * The one pane of {@code id}, created on first request and kept until disposal;
     * creating it starts that session's metadata load (memory-only on this thread).
     *
     * @throws IllegalStateException once the controller or its project is disposed
     */
    SessionPane paneFor(ManagedSessionId id) {
        Objects.requireNonNull(id, "id");
        if (closed()) {
            throw new IllegalStateException("session host is disposed");
        }
        SessionPane retained = panes.get(id);
        if (retained == null) {
            retained = new SessionPane(project, id, this);
            panes.put(id, retained);
            startFacts(id);
        }
        return retained;
    }

    /** The retained pane in the focused slot, only while a lease is held and the host is alive. */
    Optional<SessionPane> focusedPane() {
        if (lease == null || closed()) {
            return Optional.empty();
        }
        return switch (layout.focusedSlot()) {
            case PRIMARY -> layout.primary().map(panes::get);
            case HELD -> layout.held().map(panes::get);
            case NONE -> Optional.empty();
        };
    }

    /** The latest accepted answer for {@code id}, LOADING included; nothing is fabricated and nothing starts. */
    @Override
    public Optional<SessionPaneFacts> facts(ManagedSessionId id) {
        return Optional.ofNullable(latest.get(id)).map(SessionFactsLoader.Result::facts);
    }

    /** A strip click, forwarded once and only for a pane attached in the primary or held slot. */
    @Override
    public void onStripAction(SessionPane pane, AgentConsoleStrip.StripAction action) {
        if (pane != null && lease != null && !closed()
                && (pane == slotPane(layout.primary()) || pane == slotPane(layout.held()))) {
            pane.command(action);
        }
    }

    /**
     * A deliberate pane focus is {@link #focus} while a lease is held; a released attachment's
     * is ignored, and so is a retained pane that is off the primary and held slots (stale input).
     */
    @Override
    public void paneFocused(ManagedSessionId id) {
        if (lease != null && !closed() && panes.containsKey(id)
                && (id.equals(layout.primary().orElse(null)) || id.equals(layout.held().orElse(null)))) {
            focus(id);
        }
    }

    /**
     * Arms {@code fire} to run once, on the caller's thread (the EDT), when
     * the pane of {@code id} next mounts a console; re-arming replaces. The
     * runnable itself decides whether the focus permission still holds, so
     * a stale arm is naturally silent. Never fires after disposal.
     */
    void awaitConsoleMounted(ManagedSessionId id, Runnable fire) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fire, "fire");
        if (!closed()) {
            consoleFocusAwaiting.put(id, fire);
        }
    }

    /**
     * {@link SessionPane.Host}: the pane of {@code id} mounted a console.
     * One pending focus fires and leaves; a stale one still runs but is
     * fenced by its own permission check.
     */
    @Override
    public void consoleMounted(ManagedSessionId id) {
        Runnable fire = consoleFocusAwaiting.remove(id);
        if (fire != null) {
            fire.run();
        }
    }

    /** Whether the pane of {@code id} currently displays {@code console}. */
    boolean isConsoleMounted(ManagedSessionId id, TerminalExecutionConsole console) {
        SessionPane pane = panes.get(id);
        return pane != null && pane.displays(console);
    }

    /** Whether {@code owner} now holds the lease: free, or already its own. Never grants focus. */
    boolean acquireLease(FileEditor owner) {
        Objects.requireNonNull(owner, "owner");
        if (closed()) {
            return false;
        }
        if (lease == null) {
            lease = owner;
            ageTicker.start();
        }
        return lease == owner;
    }

    /** The owner's release hides, revokes focus and detaches the slot roots; others are ignored. */
    void releaseLease(FileEditor owner) {
        if (owner == null || lease != owner) {
            return;
        }
        hide();
        detachSlot(layout.primary());
        detachSlot(layout.held());
        lease = null;
        ageTicker.stop();
    }

    private SessionPane slotPane(Optional<ManagedSessionId> id) {
        return id.map(panes::get).orElse(null);
    }

    private void detachSlot(Optional<ManagedSessionId> id) {
        SessionPane pane = slotPane(id);
        if (pane != null) {
            pane.detachRoot();
        }
    }

    /** Intentional show; an unavailable session or a disposed host changes nothing. */
    Outcome show(ManagedSessionId id) {
        Objects.requireNonNull(id, "id");
        return closed() ? Outcome.UNAVAILABLE : presented(request(model.show(id)));
    }

    /**
     * Intentional focus of a pane that is on screen. A session that is not in
     * primary or held (history, unavailable) is no target.
     */
    Outcome focus(ManagedSessionId id) {
        Objects.requireNonNull(id, "id");
        if (closed()) {
            return Outcome.UNAVAILABLE;
        }
        Slot slot = id.equals(layout.primary().orElse(null)) ? Slot.PRIMARY
                : id.equals(layout.held().orElse(null)) ? Slot.HELD : Slot.NONE;
        if (slot == Slot.NONE) {
            return request(Outcome.UNAVAILABLE);
        }
        model.focusSlot(slot);
        return presented(request(slot == Slot.HELD ? Outcome.FOCUSED_HELD : Outcome.SHOWN_IN_PRIMARY));
    }

    /** The previous-session action; an unavailable or absent previous changes nothing. */
    Outcome previous() {
        return closed() ? Outcome.UNAVAILABLE : presented(request(model.showPrevious()));
    }

    boolean hold() {
        if (closed() || !model.hold()) {
            return false;
        }
        revoke();
        advance();
        return true;
    }

    void closeHeld() {
        if (!closed() && model.held().isPresent()) {
            model.closeHeld();
            revoke();
            advance();
        }
    }

    /** The session is gone; its pane empties, history and the remembered id are kept. */
    void invalidate(ManagedSessionId id) {
        Objects.requireNonNull(id, "id");
        if (closed()) {
            return;
        }
        boolean shown = model.primary().filter(id::equals).isPresent()
                || model.held().filter(id::equals).isPresent();
        model.invalidate(id);
        if (shown) {
            revoke();
            advance();
        }
    }

    /**
     * A Done flow (per-session or bulk) forgot this session: the pane empties
     * like {@link #invalidate}. When the forgotten session was primary, the
     * host picks up the next remaining catalog session instead of leaving an
     * empty primary pane; a held-session completion only empties the held
     * slot. Either way the layout cache is re-projected, since the model has
     * changed — a held-session Done that skipped the re-projection left the
     * cached layout reporting a phantom held pane. A pick is an intentional
     * show, so the remembered id and selection event follow what is now
     * displayed. With no session left, the emptied primary slot's placeholder
     * names the fact. EDT only: the catalog read is a synchronized in-memory
     * read.
     */
    void sessionDone(ManagedSessionId id) {
        Objects.requireNonNull(id, "id");
        if (closed()) {
            return;
        }
        boolean primaryDone = model.primary().filter(id::equals).isPresent();
        boolean shown = primaryDone || model.held().filter(id::equals).isPresent();
        model.invalidate(id);
        if (shown) {
            revoke();
            advance();
            if (primaryDone) {
                pickNext(id);
            }
        }
    }

    /** The first remaining catalog session this host can still show, done id excluded; empty when none. */
    private Optional<ManagedSessionId> pickNext(ManagedSessionId done) {
        Optional<ManagedSessionId> next = remainingSessions.get().stream()
                .filter(candidate -> !candidate.equals(done))
                .filter(model::isAvailable)
                .findFirst();
        if (next.isPresent()) {
            show(next.get());
        } else {
            // No selection event will follow, so the leased editor is told
            // directly: it unmounts the emptied slot and its placeholder can
            // name the no-sessions fact.
            if (lease instanceof SessionHostEditor editor) {
                editor.refreshLayout();
            }
        }
        return next;
    }

    /** Whether the catalog holds no session at all any more; the emptied slot's placeholder reads it. */
    boolean noRemainingSessions() {
        return remainingSessions.get().isEmpty();
    }

    /** The catalog's live ids in catalog order; an unavailable catalog reads as none, with failures logged. */
    private static List<ManagedSessionId> catalogSessions() {
        try {
            return DrydockPluginService.getInstance().stateStore().state().sessions().stream()
                    .map(ManagedAgentSession::id)
                    .toList();
        } catch (RuntimeException e) {
            LOG.warn("Unable to read the session catalog", e);
            return List.of();
        }
    }

    void revalidate(ManagedSessionId id) {
        if (!closed()) {
            model.revalidate(id);
        }
    }

    /** The host was hidden: pending focus is void until the next request shows it again. */
    void hide() {
        hidden = true;
        revoke();
    }

    /** The user moved focus out of the host (code, search, another tool): no pane is focused. */
    void focusDeparted() {
        if (!closed()) {
            model.focusSlot(Slot.NONE);
            advance();
        }
        revoke();
    }

    /** Where an activation refocus asks for the keyboard; a test seam. */
    private Consumer<JComponent> focusRequest;

    void focusRequester(Consumer<JComponent> focusRequest) {
        this.focusRequest = Objects.requireNonNull(focusRequest, "focusRequest");
    }

    /**
     * The IDE frame came to the front. If it is this project's frame, the
     * keyboard goes to the focused session's terminal one EDT hop later —
     * after the platform's own focus restore has landed, so the terminal's
     * request is the last word. This is the activation contract: returning
     * to the IDE with the Sessions tab in front means typing to the agent,
     * whatever the last focus owner inside the window was. Frames of other
     * projects (and the welcome frame) are not this controller's concern.
     * EDT only.
     */
    void frameActivated(IdeFrame frame) {
        if (closed() || frame == null || frame.getProject() != project) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(
                this::refocusFocusedSession, ModalityState.nonModal(), project.getDisposed());
    }

    /**
     * The delayed step of {@link #frameActivated}: every fact is re-checked
     * at the hop. A refocus fires only while a host editor holds the lease
     * and the focused session's console is showing in an active window —
     * {@link SessionHostVisibility#isViewed}, the one on-screen answer: the
     * host tab is the active editor on screen exactly when its console is
     * showing. A focused slot without a live console takes nothing. The
     * request bypasses the show-intent permission epoch (and the
     * {@code hidden} flag with it: a tab reopened without an intentional
     * show still shows a live console and is a target): the activation is
     * its own intent, and a stale one is cancelled by the re-checked facts,
     * not by a token.
     */
    private void refocusFocusedSession() {
        if (closed() || !(lease instanceof SessionHostEditor)) {
            return;
        }
        Optional<ManagedSessionId> focused = focusedSessionId();
        if (focused.isEmpty() || !visibility.isViewed(focused.get())) {
            return;
        }
        focusedPane().flatMap(SessionPane::focusTarget).ifPresent(focusRequest);
    }

    /** The session in the focused slot, only while a lease is held and the host is alive. */
    private Optional<ManagedSessionId> focusedSessionId() {
        if (lease == null || closed()) {
            return Optional.empty();
        }
        return switch (layout.focusedSlot()) {
            case PRIMARY -> layout.primary();
            case HELD -> layout.held();
            case NONE -> Optional.empty();
        };
    }

    /**
     * The retry step of an accepted presentation: loads the pane that is now
     * actually shown (never a target from history), only if it is retained.
     * A refused one probes nothing.
     */
    private Outcome presented(Outcome outcome) {
        ManagedSessionId shown = switch (outcome) {
            case SHOWN_IN_PRIMARY -> layout.primary().orElse(null);
            case FOCUSED_HELD -> layout.held().orElse(null);
            case UNAVAILABLE, NO_PREVIOUS -> null;
        };
        if (shown != null && panes.containsKey(shown)) {
            startFacts(shown);
        }
        return outcome;
    }

    /**
     * Asks the one loader for {@code id} (memory-only here) and applies what it
     * returns: the cached answer, a LOADING snapshot, a synchronous FAILED, or
     * the inert generation 0 of a disposed loader.
     */
    private void startFacts(ManagedSessionId id) {
        SessionFactsLoader active = loader();
        if (active != null) {
            apply(active.select(id));
        }
    }

    /** The loader, created on first use; null once the controller or its project is closed. */
    private SessionFactsLoader loader() {
        synchronized (closing) {
            if (closed()) {
                return null;
            }
            if (loader == null) {
                loader = loaders.apply(this::delivered);
            }
            return loader;
        }
    }

    /** A loader callback, on any thread: dropped once closed, otherwise applied once on the UI thread. */
    private void delivered(SessionFactsLoader.Result result) {
        if (!closed()) {
            ApplicationManager.getApplication().invokeLater(
                    () -> apply(result), ModalityState.any(), project.getDisposed());
        }
    }

    /**
     * Presents a loader result on the UI thread: only for a live controller, a retained pane and a
     * real generation, and only if it supersedes what is held for the ID. Stores it and gives the
     * same result to that pane alone; changes no layout, history, focus or event.
     */
    void apply(SessionFactsLoader.Result result) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        SessionPane pane = panes.get(result.id());
        if (closed() || pane == null || result.generation() < 1 || !supersedes(latest.get(result.id()), result)) {
            return;
        }
        latest.put(result.id(), result);
        pane.applyFacts(result);
    }

    /** Newer generation wins; within one, a terminal answer replaces LOADING and nothing else does. */
    private static boolean supersedes(SessionFactsLoader.Result held, SessionFactsLoader.Result incoming) {
        if (held == null || incoming.generation() > held.generation()) {
            return true;
        }
        return incoming.generation() == held.generation()
                && held.facts().state() == SessionPaneFacts.State.LOADING
                && incoming.facts().state() != SessionPaneFacts.State.LOADING;
    }

    /**
     * Claims the closed state once, on any thread, and takes the loader for
     * disposal under that claim, then takes the panes and
     * the lease apart on the UI thread: inline there, otherwise queued with no
     * project expiry so a disposing project cannot discard it, and never waited
     * for. Consoles, registry, router and launches are not touched.
     */
    @Override
    public void dispose() {
        SessionFactsLoader taken;
        synchronized (closing) {
            if (disposed) {
                return;
            }
            disposed = true;
            taken = loader;
            loader = null;
        }
        ageTicker.stop();
        dirty.clear();
        Disposer.dispose(visibility);
        if (taken != null) {
            taken.dispose();
        }
        if (ApplicationManager.getApplication().isDispatchThread()) {
            cleanup();
        } else {
            ApplicationManager.getApplication().invokeLater(this::cleanup, ModalityState.any());
        }
    }

    private void cleanup() {
        revoke();
        panes.values().forEach(SessionPane::dispose);
        panes.clear();
        latest.clear();
        consoleFocusAwaiting.clear();
        lease = null;
        ageTicker.stop();
    }

    /**
     * A newer attempt fences older focus. A refused one only cancels it; an
     * accepted one also reveals the host, issues fresh permission and commits intent.
     */
    private Outcome request(Outcome outcome) {
        revoke();
        if (outcome == Outcome.UNAVAILABLE || outcome == Outcome.NO_PREVIOUS) {
            return outcome;
        }
        hidden = false;
        permitted = true;
        advance();
        return outcome;
    }

    /** Every epoch advance cancels permission; only an accepted request issues it again. */
    private void revoke() {
        epoch++;
        permitted = false;
    }

    /** Re-projects the model; a changed current identity is committed and announced exactly once. */
    private void advance() {
        Layout before = layout;
        layout = project();
        if (layout.equals(before)) {
            layout = before;
        }
        Optional<ManagedSessionId> current = layout.current();
        if (current.isPresent() && !current.equals(before.current())) {
            state.setSelectedSession(current);
            selectionPublisher.accept(current.get());
        }
    }

    private Layout project() {
        return new Layout(model.primary(), model.held(), model.current(), model.previous(),
                model.focusedSlot());
    }
}
