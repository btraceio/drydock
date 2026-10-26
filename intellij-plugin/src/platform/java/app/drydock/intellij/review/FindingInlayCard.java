package app.drydock.intellij.review;

import app.drydock.review.AnnotationStatus;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Triage;

import java.awt.Component;
import java.awt.EventQueue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * The Swing content of one finding's diff inlay (spec "Review surface and
 * diff inlays", AC1): severity and title, author and relative age, the drift
 * hint when the anchor fell out of the current diff, the whole thread, a
 * comment box that submits on Enter, and the actions the finding's state
 * allows — PROPOSED shows Confirm/Dismiss/Fix, CONFIRMED and DISMISSED keep
 * the card and expose Undo, and a SENT finding says so instead of pretending
 * a reply.
 *
 * <p>The card renders state and invokes the injected {@link Callbacks};
 * it never touches a store, a process or the filesystem, and it runs no work
 * of its own. A click or an Enter disables the controls and shows progress
 * <em>synchronously, before</em> the callback runs; the owner then reports
 * every completion — success, refusal or failure — with {@link #complete()},
 * which removes progress and restores the controls, while {@link #dispose()}
 * takes the card out of its inlay so a late completion changes nothing.
 * Construction and mutation assert the EDT, like every component the diff
 * owns.</p>
 */
public final class FindingInlayCard extends JPanel {

    /** What the card asks its owner to do; the card itself stays pure Swing. */
    public interface Callbacks {
        void confirm();

        void dismiss();

        void undo();

        void fix();

        void comment(String text);
    }

    private static final String PROGRESS_TEXT = "Working…";

    private final Clock clock;
    private final Callbacks callbacks;
    private final JLabel header = new JLabel();
    private final JLabel meta = new JLabel();
    private final JLabel state = new JLabel();
    private final JLabel drift = new JLabel();
    private final JPanel thread = new JPanel();
    private final JTextField comment = new JTextField();
    private final JLabel progress = new JLabel(PROGRESS_TEXT);
    private final JPanel actions = new JPanel();
    private final List<String> threadLines = new ArrayList<>();
    private ReviewAnnotation finding;
    private boolean busy;
    private boolean disposed;

    /** {@code driftHint} is present exactly when the placement fell back to the first hunk (T3). */
    public FindingInlayCard(ReviewAnnotation finding, Optional<String> driftHint,
                            Clock clock, Callbacks callbacks) {
        this.finding = Objects.requireNonNull(finding, "finding");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        Objects.requireNonNull(driftHint, "driftHint");
        checkEdt();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        drift.setText(driftHint.orElse(""));
        drift.setVisible(driftHint.isPresent());
        progress.setVisible(false);
        comment.addActionListener(e -> submitComment());
        // vertical order: header, meta, state, drift hint, thread, comment box, actions, progress
        List.of(header, meta, state, drift, thread, comment, actions, progress).forEach(this::add);
        render();
    }

    /** Re-renders from the finding's new state: the store-listener rebuild path hands the card its next snapshot. */
    public void update(ReviewAnnotation next) {
        checkEdt();
        finding = Objects.requireNonNull(next, "next");
        render();
    }

    /** Removes progress and restores the controls: the owner calls this for every completion, refusal or failure. */
    public void complete() {
        checkEdt();
        if (disposed) {
            return;
        }
        busy = false;
        progress.setVisible(false);
        setControlsEnabled(true);
    }

    /** The card left its inlay: progress goes, the controls stay dead, and a late completion changes nothing. */
    public void dispose() {
        checkEdt();
        disposed = true;
        busy = false;
        progress.setVisible(false);
        setControlsEnabled(false);
    }

    /** Whether an action is in flight; tests and owners read this rather than the label. */
    public boolean isBusy() {
        return busy;
    }

    // Package-private views for the test: what the rendering said, without a component walk.

    boolean progressVisible() {
        return progress.isVisible();
    }

    boolean stateVisible() {
        return state.isVisible();
    }

    String stateText() {
        return state.getText();
    }

    boolean driftShowing() {
        return drift.isVisible();
    }

    String driftText() {
        return drift.getText();
    }

    List<String> threadLines() {
        return List.copyOf(threadLines);
    }

    private void render() {
        header.setText(finding.effectiveSeverity().wireName() + " · " + finding.displayTitle());
        meta.setText(finding.author() + " · " + age(finding.at()));
        renderState();
        renderThread();
        renderActions();
    }

    private void renderState() {
        List<String> parts = new ArrayList<>();
        if (finding.triage() != Triage.PROPOSED) {
            parts.add(finding.triage().wireName());
        }
        if (finding.status() == AnnotationStatus.SENT) {
            parts.add("sent");
        }
        state.setText(String.join(" · ", parts));
        state.setVisible(!parts.isEmpty());
    }

    private void renderThread() {
        thread.removeAll();
        threadLines.clear();
        thread.setLayout(new BoxLayout(thread, BoxLayout.Y_AXIS));
        for (ReviewAnnotation.Message message : finding.thread()) {
            String line = message.author() + " · " + age(message.at()) + ": " + message.text();
            threadLines.add(line);
            thread.add(new JLabel(line));
        }
        thread.revalidate();
    }

    private void renderActions() {
        actions.removeAll();
        if (finding.triage() == Triage.PROPOSED) {
            actions.add(button("Confirm", callbacks::confirm));
            actions.add(button("Dismiss", callbacks::dismiss));
            actions.add(button("Fix", callbacks::fix));
        } else {
            actions.add(button("Undo", callbacks::undo));
        }
        setControlsEnabled(!busy && !disposed);
        actions.revalidate();
    }

    private JButton button(String text, Runnable onClick) {
        JButton button = new JButton(text);
        button.addActionListener(e -> run(onClick));
        return button;
    }

    private void run(Runnable callback) {
        if (busy || disposed) {
            return;
        }
        busy = true;
        progress.setVisible(true);
        setControlsEnabled(false);
        try {
            callback.run();
        } catch (RuntimeException | Error e) {
            // A synchronous throw happens before any async owner exists to
            // call complete(), so the card restores itself here — exactly
            // like complete(), minus nothing — and then rethrows; the
            // exception is never swallowed.
            complete();
            throw e;
        }
    }

    private void submitComment() {
        String text = comment.getText().strip();
        if (text.isEmpty()) {
            return;
        }
        run(() -> callbacks.comment(text));
    }

    private void setControlsEnabled(boolean enabled) {
        for (Component child : actions.getComponents()) {
            child.setEnabled(enabled);
        }
        comment.setEnabled(enabled);
    }

    private String age(Instant at) {
        return age(at, clock.instant());
    }

    /** Relative age against a given now, clamped like the app's "time ago" (never negative). */
    static String age(Instant at, Instant now) {
        long seconds = Math.max(0, Duration.between(at, now).getSeconds());
        if (seconds < 60) {
            return "now";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m ago";
        }
        if (seconds < 86400) {
            return (seconds / 3600) + "h ago";
        }
        return (seconds / 86400) + "d ago";
    }

    private static void checkEdt() {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("FindingInlayCard must be constructed and mutated on the EDT");
        }
    }
}
