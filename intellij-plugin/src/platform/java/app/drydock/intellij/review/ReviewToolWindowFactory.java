package app.drydock.intellij.review;

import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.DrydockSessionsListener;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener.ToolWindowManagerEventType;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The dedicated {@code Drydock Review} tool window (P3): the replacement
 * for the P2 {@code changesViewContent} subtab, which was hard to discover
 * and was rejected by the operator. The window carries exactly one
 * content — the existing {@link ReviewTabProvider}'s panel — and is
 * discoverable beside the Changes host the subtab used to live in.
 *
 * <p><strong>Alignment.</strong> The tool-window placement is persisted
 * per layout and overrides the descriptor's anchor from the first
 * registration on (verified against {@code ToolWindowManagerImpl} on
 * 263: {@code ToolWindowFactory.init} runs during registration, content
 * creation only when the window is shown — aligning there would be too
 * late to make startup placement deterministic). So {@code init} aligns
 * Review with the first <em>available</em> Changes host — the Commit
 * window, then Version Control, the same fallback the subtab's
 * {@code isInCommitToolWindow} had — through
 * {@link ToolWindowManager#invokeLater}, and a project message-bus
 * listener keeps following the host afterwards: the host can register or
 * become available after Review does (VCS detection), and a user can move
 * it. Review's <em>own</em> events are ignored — its {@code setAnchor}
 * fires one for its id, and reacting to it would loop
 * ({@code kb/intellij-findings-triage/2026-10-25-editor-area-diff-decoration-seam}).</p>
 *
 * <p><strong>Availability.</strong> Q8's rule survives the move: the
 * window shows only in projects a Drydock session covers, through the
 * existing {@link ReviewTabVisibility} in-memory check. Availability is
 * re-evaluated when the plugin finishes initializing and whenever the
 * session catalog changes — both paths read only the already-loaded
 * state, so no availability update spawns I/O, a git process or a
 * blocking read. The application itself is <em>always deferred</em> out
 * of {@code init} (through the scheduler parameter of
 * {@link #onInitializationComplete}): the plugin's
 * initialization future can already be complete when the factory
 * attaches its callback, and a synchronous {@code whenComplete} inside
 * {@code ToolWindowFactory.init} would put a changing
 * {@code setAvailable} where {@code ToolWindowImpl} forbids it.</p>
 *
 * <p>No {@code <module>} is declared for the diff product modules — they
 * stay compile-only in build.gradle.kts, resolved at runtime through
 * {@code intellij.platform.vcs.impl}
 * ({@code kb/find-263-diff-api-internal}).</p>
 */
public final class ReviewToolWindowFactory implements ToolWindowFactory, DumbAware {

    private static final Logger SYS_LOG = System.getLogger(ReviewToolWindowFactory.class.getName());

    /**
     * The dedicated window's id: the same pinned "Drydock Review" name the
     * provider's tab and the descriptor must use, so the opener (T8) and
     * the browser resolve this one window.
     */
    static final String TOOL_WINDOW_ID = ReviewTabProvider.TAB_NAME;

    /**
     * The Changes hosts Review aligns with, in preference order: the
     * Commit window first, then Version Control — exactly the fallback the
     * subtab's {@code isInCommitToolWindow="true"} had, where a disabled
     * Commit window moved the content into Version Control. Ids verified
     * against the bundled VCS plugin's descriptor on 263.
     */
    static final List<String> CHANGES_HOST_IDS = List.of("Commit", "Version Control");

    /**
     * Event kinds that re-align: a window moved (anchor, side-tool, or
     * combined moves), the layout was switched, or an availability
     * flipped — the first-available preference must be re-evaluated
     * because it can change the target host. The same set covers
     * host-named events and windowless ones: 263's
     * {@code ToolWindowManagerImpl.fireStateChanged} publishes the
     * anchor and side kinds through the 2-arg windowless overload too
     * (a null source window), not only {@code SetLayout}.
     */
    private static final Set<ToolWindowManagerEventType> REALIGN_EVENTS = EnumSet.of(
            ToolWindowManagerEventType.SetToolWindowAnchor,
            ToolWindowManagerEventType.SetSideToolAndAnchor,
            ToolWindowManagerEventType.SetSideTool,
            ToolWindowManagerEventType.SetLayout,
            ToolWindowManagerEventType.ToolWindowAvailable,
            ToolWindowManagerEventType.ToolWindowUnavailable);

    /**
     * Registration is the alignment's first chance: the stored layout
     * overrides the descriptor's default anchor from the first
     * registration on, so this — not {@link #createToolWindowContent} —
     * is where startup placement is made deterministic.
     *
     * <p>263 note: the source-carrying {@code stateChanged} overload this
     * class listens on is {@code @ApiStatus.Internal}/{@code Experimental}
     * in 263.7031.23 bytecode — it is the only callback that names the
     * event's window, so ignoring Review's own events (below) requires it.
     * </p>
     */
    @Override
    public void init(@NotNull ToolWindow toolWindow) {
        Project project = toolWindow.getProject();
        ToolWindowManager manager = ToolWindowManager.getInstance(project);
        // Via invokeLater: init runs inside the manager's own registration
        // flow, and the aligning setAnchor must not run under it.
        alignLater(toolWindow, manager);
        subscribeToHostMoves(project, toolWindow, manager);
        subscribeToCoverage(toolWindow, project);
    }

    /**
     * Q8 at registration: only windows a Drydock session covers carry a
     * Review window. The existing predicate's in-memory check — the same
     * rule that hid the subtab — so no registration-time I/O either.
     */
    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return new ReviewTabVisibility(project).test(project);
    }

    /**
     * One content, the provider's own panel — never a second content, a
     * placeholder tab or a closeable one. The provider's disposal is
     * bound to the content ({@link OnceDisposal}): the platform disposing
     * the content on removal or project close is the tab's whole
     * teardown path, exactly as the Disposer-registered panel of the task
     * window's factory.
     */
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ReviewTabProvider provider = new ReviewTabProvider(project);
        Content content = ContentFactory.getInstance().createContent(provider.initContent(), "", false);
        content.setCloseable(false);
        Disposer.register(content, new OnceDisposal(provider::disposeContent));
        toolWindow.getContentManager().addContent(content);
    }

    // ---- alignment ----------------------------------------------------------

    /**
     * The first available Changes host, in preference order. A registered
     * but unavailable window does not count: with the Commit window
     * disabled, the subtab fell back to Version Control and so must the
     * alignment.
     */
    static ToolWindow changesHost(ToolWindowManager manager) {
        List<String> available = new ArrayList<>();
        for (String hostId : CHANGES_HOST_IDS) {
            ToolWindow window = manager.getToolWindow(hostId);
            if (window != null && window.isAvailable()) {
                available.add(hostId);
            }
        }
        String hostId = preferredHostId(available);
        return hostId == null ? null : manager.getToolWindow(hostId);
    }

    /**
     * The preferred host id among the available ones, or {@code null} when
     * none is: the pure preference decision, host ids only.
     */
    static String preferredHostId(Collection<String> availableHostIds) {
        for (String hostId : CHANGES_HOST_IDS) {
            if (availableHostIds.contains(hostId)) {
                return hostId;
            }
        }
        return null;
    }

    /**
     * The anchor Review should move to, or {@code null} when it must not
     * move: no host exists to follow, or the window already sits at the
     * host's anchor — a setAnchor call for the current anchor would still
     * fire the self event and rewrite the stored layout for nothing.
     */
    @Nullable
    static ToolWindowAnchor targetAnchor(ToolWindowAnchor current, ToolWindowAnchor hostAnchor) {
        if (hostAnchor == null || hostAnchor.equals(current)) {
            return null;
        }
        return hostAnchor;
    }

    /**
     * Whether one manager event should re-align Review. Self events are
     * ignored first — Review's own setAnchor fires
     * SetToolWindowAnchor for its id, and realigning to it is the loop.
     * A host's move or availability flip realigns. A <em>windowless</em>
     * event of a realign kind realigns too: 263's fireStateChanged
     * publishes SetToolWindowAnchor and the side kinds through the 2-arg
     * windowless overload (a null source window), not only SetLayout —
     * realigning on those closes a window in which the re-correction was
     * missed until the next qualifying event. A self-<em>caused</em>
     * windowless anchor event (Review's own setAnchor naming no window)
     * is safe: the self-id filter cannot drop what named no window, so
     * the second guard breaks the loop instead — {@link #targetAnchor}
     * returns null once Review already sits at the host's anchor, and
     * the realign is a no-op. Every other window's events do not
     * realign.
     */
    static boolean reactsToEvent(String selfId, @Nullable String eventWindowId, ToolWindowManagerEventType type) {
        if (eventWindowId != null && eventWindowId.equals(selfId)) {
            return false; // the Review window's own anchor write, not a host signal
        }
        if (eventWindowId == null) {
            return REALIGN_EVENTS.contains(type);
        }
        return CHANGES_HOST_IDS.contains(eventWindowId) && REALIGN_EVENTS.contains(type);
    }

    /**
     * Whether a registration should realign: only the Changes hosts — the
     * host Review follows can appear after Review itself registers.
     */
    static boolean reactsToRegistration(String registeredId) {
        return CHANGES_HOST_IDS.contains(registeredId);
    }

    /**
     * Subscribes to the manager's events, keeping the alignment current
     * while the window lives. The connection is parented to the tool
     * window's own disposable, so project close tears it down together
     * with the window — no listener survives it. The 263 listener surface used
     * here ({@code toolWindowsRegistered}, the two stateChanged overloads)
     * is the platform's actual bytecode — ToolWindowManagerImpl on
     * 263.7031.23 publishes exactly these three, and the deprecated
     * single-id toolWindowRegistered never fires.
     */
    private static void subscribeToHostMoves(Project project, ToolWindow review, ToolWindowManager manager) {
        project.getMessageBus().connect(review.getDisposable())
                .subscribe(ToolWindowManagerListener.TOPIC, new ToolWindowManagerListener() {
                    /**
                     * The list variant, not the deprecated single-id
                     * one: 263's ToolWindowManagerImpl publishes every
                     * registration — even a single window — as
                     * {@code toolWindowsRegistered(listOf(id), this)}.
                     */
                    @Override
                    public void toolWindowsRegistered(@NotNull List<String> ids, @NotNull ToolWindowManager source) {
                        for (String id : ids) {
                            if (reactsToRegistration(id)) {
                                alignLater(review, manager);
                                return;
                            }
                        }
                    }

                    @Override
                    public void stateChanged(@NotNull ToolWindowManager source,
                                             @NotNull ToolWindow sourceWindow,
                                             @NotNull ToolWindowManagerEventType type) {
                        if (reactsToEvent(TOOL_WINDOW_ID, sourceWindow.getId(), type)) {
                            alignLater(review, manager);
                        }
                    }

                    @Override
                    public void stateChanged(@NotNull ToolWindowManager source, @NotNull ToolWindowManagerEventType type) {
                        if (reactsToEvent(TOOL_WINDOW_ID, null, type)) {
                            alignLater(review, manager);
                        }
                    }
                });
    }

    /**
     * Keeps availability on the coverage rule: Q8 survives the move from
     * the subtab predicate. Two signals — the plugin's initialization
     * completing (registration can run before it, and then no session is
     * loaded yet) and the session catalog changing — and both read only
     * the in-memory state, so neither spawns I/O or blocks. The listener
     * lives on the tool window's disposable.
     */
    private static void subscribeToCoverage(ToolWindow toolWindow, Project project) {
        Runnable applyAvailability = () -> applyCoverageAvailability(toolWindow, project);
        ApplicationManager.getApplication().getMessageBus().connect(toolWindow.getDisposable())
                .subscribe(DrydockSessionsListener.TOPIC, () -> scheduleOnApplicationQueue(applyAvailability));
        onInitializationComplete(DrydockPluginService.getInstance().initialization(), applyAvailability,
                ReviewToolWindowFactory::scheduleOnApplicationQueue);
    }

    /**
     * The production scheduler: where an availability update runs. Never
     * in place, always queued — because
     * {@link #subscribeToCoverage} attaches a {@code whenComplete}
     * during {@code ToolWindowFactory.init}, and when the plugin is
     * already initialized (a reopened project) the callback runs
     * synchronously inside init, where {@code ToolWindowImpl} forbids a
     * changing {@code setAvailable} ("Do not use toolWindow.setAvailable()
     * as part of ToolWindowFactory.init()").
     * {@link Application#invokeLater} queues past init even when already
     * on the EDT — the old EDT-direct run was exactly the synchronous
     * case that init forbids. No application means no IDE
     * process, so no window exists to update; the wiring that reaches
     * here only runs inside one.
     */
    static void scheduleOnApplicationQueue(Runnable update) {
        Application application = ApplicationManager.getApplication();
        if (application == null) {
            SYS_LOG.log(Level.WARNING,
                    "No application: dropping a Review availability update");
            return;
        }
        application.invokeLater(update);
    }

    /**
     * Attaches the availability application to the plugin's
     * initialization completing: the seam for the synchronous-completion
     * case. {@code whenComplete} runs its callback in place when the
     * future is already done — which, during
     * {@code ToolWindowFactory.init} on a reopened project (the service
     * is application-level and survives the project), would put a
     * changing {@code setAvailable} inside init. So the callback only
     * hands the application to the given {@code scheduler}; the
     * scheduler is what keeps it out of init. The scheduler is an
     * explicit parameter, not a mutable field: production passes
     * {@link #scheduleOnApplicationQueue} and tests pass their recording
     * consumer directly — neither can replace scheduling for anyone
     * else.
     */
    static void onInitializationComplete(CompletableFuture<Void> initialization, Runnable applyAvailability,
            Consumer<Runnable> scheduler) {
        initialization.whenComplete((ignored, error) -> scheduler.accept(applyAvailability));
    }

    /**
     * Applies the coverage rule to the window's availability — EDT-only,
     * {@code setAvailable} is Swing-side, which the scheduler guarantees.
     * A disposal race is a no-op, not a failure — the window is going
     * away either way.
     */
    private static void applyCoverageAvailability(ToolWindow toolWindow, Project project) {
        if (project.isDisposed() || toolWindow.isDisposed()) {
            return;
        }
        toolWindow.setAvailable(new ReviewTabVisibility(project).test(project), null);
    }

    /**
     * Schedules one alignment after the manager's current work, dropping
     * it silently when the window is already gone — a late queued
     * alignment must not resurrect a disposed window.
     */
    private static void alignLater(ToolWindow review, ToolWindowManager manager) {
        if (review.isDisposed()) {
            return;
        }
        manager.invokeLater(() -> align(review, manager));
    }

    /**
     * Moves Review to the first available host's anchor. The anchor is
     * the only thing aligned: the host's stripe position and split state
     * are the user's layout, and only a wrong stripe makes the window
     * undiscoverable.
     */
    private static void align(ToolWindow review, ToolWindowManager manager) {
        if (review.isDisposed()) {
            return;
        }
        ToolWindow host = changesHost(manager);
        if (host == review) {
            return;
        }
        ToolWindowAnchor target = host == null ? null : targetAnchor(review.getAnchor(), host.getAnchor());
        if (target == null) {
            return; // no host to follow yet, or already beside it
        }
        SYS_LOG.log(Level.DEBUG, "Aligning ''{0}'' with the Changes host ''{1}'' at {2}",
                TOOL_WINDOW_ID, host.getId(), target);
        // Corrects the persisted layout too: the stored placement no
        // longer overrides the descriptor default once this ran.
        review.setAnchor(target, null);
    }

    // ---- the content's disposal ----------------------------------------------

    /**
     * The provider's disposal bound to the tool-window content, run at
     * most once: whichever disposal path the platform takes first —
     * content removal, the Disposer tree, project close — the provider's
     * own teardown runs exactly one time, and a second path finds the
     * guard already spent.
     */
    static final class OnceDisposal implements Disposable {

        private final Runnable disposal;
        private final AtomicBoolean disposed = new AtomicBoolean();

        OnceDisposal(Runnable disposal) {
            this.disposal = disposal;
        }

        @Override
        public void dispose() {
            if (disposed.compareAndSet(false, true)) {
                disposal.run();
            }
        }
    }
}
