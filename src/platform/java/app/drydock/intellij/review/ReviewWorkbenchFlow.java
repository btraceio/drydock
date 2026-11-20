package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.FlowModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import com.intellij.ide.util.PropertiesComponent;

import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One workbench's flow review (design A): the queue of hunks each
 * {@link WorkbenchState} carries, the hunk the centre shows, and the
 * reviewer's seen and flagged marks. The queue pane, the centre and the
 * header listen to it.
 *
 * <p>Seeing is implicit: leaving a hunk (Next, Back, picking another row,
 * a jump to a finding) marks the hunk left as seen. Next on the last hunk
 * opens the finish screen instead. Opening the workbench marks nothing.
 * The marks persist per scope ({@link ReviewFlowMarks}). EDT.</p>
 */
final class ReviewWorkbenchFlow {

    /** Whether the queue hides seen hunks; per user. */
    static final String HIDE_SEEN_KEY = "drydock.review.flow.hideSeen";

    private final Supplier<Optional<String>> scopeId;
    private final ReviewFlowMarks marks;
    private final Consumer<WorkbenchState.Navigator> tabChange;
    private final List<Runnable> listeners = new ArrayList<>();
    private WorkbenchState state;
    private FlowModel.Queue queue = FlowModel.Queue.EMPTY;
    private String marksScope;
    private Set<String> seen = new LinkedHashSet<>();
    private Set<String> flagged = new LinkedHashSet<>();
    private String current;
    private boolean finishing;
    private boolean hideSeen;
    /** A target that arrived before the first queue: {@link #showLine}'s arguments. */
    private String pendingFile;
    private String pendingKey;

    /**
     * {@code scopeId} is the live review's scope id, read on every render;
     * {@code tabChange} receives the queue tab the reviewer picked.
     */
    ReviewWorkbenchFlow(Supplier<Optional<String>> scopeId, ReviewFlowMarks marks,
                        Consumer<WorkbenchState.Navigator> tabChange) {
        this.scopeId = Objects.requireNonNull(scopeId, "scopeId");
        this.marks = Objects.requireNonNull(marks, "marks");
        this.tabChange = Objects.requireNonNull(tabChange, "tabChange");
        hideSeen = PropertiesComponent.getInstance().getBoolean(HIDE_SEEN_KEY, false);
    }

    /** Calls {@code listener} after every change. */
    void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Adopts {@code next}'s queue, built with the state
     * ({@link WorkbenchState#of}): the current hunk stays when the queue
     * still has it, else the first unseen hunk shows, else the first.
     */
    void render(WorkbenchState next) {
        requireEdt();
        state = Objects.requireNonNull(next, "next");
        Optional<String> scope = scopeId.get();
        if (!scope.equals(Optional.ofNullable(marksScope))) {
            marksScope = scope.orElse(null);
            seen = scope.map(marks::seen).orElseGet(LinkedHashSet::new);
            flagged = scope.map(marks::flagged).orElseGet(LinkedHashSet::new);
            current = null;
            finishing = false;
        }
        queue = next.flow();
        if (current == null || queue.indexOf(current) < 0) {
            current = queue.firstUnseen(seen).or(() -> queue.order().stream().findFirst())
                    .map(FlowModel.Hunk::key).orElse(null);
        }
        changed();
        applyPending();
    }

    /** The latest state; null before the first render. */
    WorkbenchState state() {
        return state;
    }

    FlowModel.Queue queue() {
        return queue;
    }

    /** The hunk the centre shows. */
    Optional<FlowModel.Hunk> current() {
        return current == null ? Optional.empty() : queue.hunk(current);
    }

    /** Whether the centre shows the finish screen. */
    boolean finishing() {
        return finishing;
    }

    boolean hideSeen() {
        return hideSeen;
    }

    /** The digests seen, as stored; {@link FlowModel.Hunk#seenIn} adds the approved ones. */
    Set<String> seen() {
        return Set.copyOf(seen);
    }

    Set<String> flagged() {
        return Set.copyOf(flagged);
    }

    /** Whether the current hunk is flagged. */
    boolean currentFlagged() {
        return current().map(hunk -> flagged.contains(hunk.digest())).orElse(false);
    }

    /** Shows the hunk with {@code key}, marking the one left as seen. */
    void go(String key) {
        requireEdt();
        if (queue.indexOf(key) < 0 || (key.equals(current) && !finishing)) {
            return;
        }
        leaveCurrent();
        current = key;
        finishing = false;
        changed();
    }

    /** Next: marks the current hunk seen and shows the following one, or the finish screen after the last. */
    void next() {
        requireEdt();
        if (finishing || current == null) {
            return;
        }
        Optional<FlowModel.Hunk> following = queue.after(current);
        leaveCurrent();
        following.ifPresentOrElse(hunk -> current = hunk.key(), () -> finishing = true);
        changed();
    }

    /** Back: marks the current hunk seen and shows the one before; from the finish screen, the last hunk. */
    void back() {
        requireEdt();
        if (finishing) {
            finishing = false;
            changed();
            return;
        }
        Optional<FlowModel.Hunk> previous = current == null ? Optional.empty() : queue.before(current);
        if (previous.isEmpty()) {
            return;
        }
        leaveCurrent();
        current = previous.get().key();
        changed();
    }

    /** Flags or unflags the current hunk. */
    void toggleFlag() {
        requireEdt();
        current().ifPresent(hunk -> {
            if (!flagged.remove(hunk.digest())) {
                flagged.add(hunk.digest());
            }
            if (marksScope != null) {
                marks.storeFlagged(marksScope, flagged, liveDigests());
            }
            changed();
        });
    }

    /** Shows the finish screen; the current hunk is not marked. */
    void finish() {
        requireEdt();
        if (!finishing) {
            finishing = true;
            changed();
        }
    }

    /** Leaves the finish screen for the first unseen hunk, or the current one when every hunk is seen. */
    void backToDiff() {
        requireEdt();
        queue.firstUnseen(seen).ifPresent(hunk -> current = hunk.key());
        finishing = false;
        changed();
    }

    /**
     * Shows the hunk of {@code file} that holds {@code lineKey}, else the
     * file's first hunk; nothing when the queue lists no hunk of the file.
     * Before the first queue arrives, the target waits for it.
     */
    void showLine(String file, String lineKey) {
        requireEdt();
        pendingFile = Objects.requireNonNull(file, "file");
        pendingKey = Objects.requireNonNull(lineKey, "lineKey");
        applyPending();
    }

    private void applyPending() {
        if (pendingFile == null || queue.order().isEmpty()) {
            return;
        }
        String file = pendingFile;
        String key = pendingKey;
        pendingFile = null;
        pendingKey = null;
        queue.hunkAt(file, key)
                .or(() -> queue.order().stream().filter(hunk -> hunk.file().equals(file)).findFirst())
                .ifPresent(hunk -> go(hunk.key()));
    }

    void setHideSeen(boolean hide) {
        requireEdt();
        if (hide != hideSeen) {
            hideSeen = hide;
            PropertiesComponent.getInstance().setValue(HIDE_SEEN_KEY, hide);
            changed();
        }
    }

    /** The reviewer picked a queue tab; the next state shows it. */
    void showTab(WorkbenchState.Navigator tab) {
        tabChange.accept(tab);
    }

    private void leaveCurrent() {
        current().ifPresent(hunk -> {
            if (seen.add(hunk.digest()) && marksScope != null) {
                marks.storeSeen(marksScope, seen, liveDigests());
            }
        });
    }

    private List<String> liveDigests() {
        return queue.order().stream().map(FlowModel.Hunk::digest).toList();
    }

    private void changed() {
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }

    private static void requireEdt() {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("ReviewWorkbenchFlow runs on the EDT");
        }
    }
}
