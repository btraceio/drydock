package app.drydock.intellij.review;

import app.drydock.review.AnnotationStatus;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Triage;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Swing content of one finding's diff inlay (spec "Review surface and
 * diff inlays", AC1): severity and title, author and relative age, the drift
 * hint when the anchor fell out of the current diff, the whole thread, a
 * comment box that submits on Enter, and the actions the finding's state
 * allows — PROPOSED shows Agree/Won't fix, CONFIRMED and DISMISSED keep
 * the card and expose Undo, and a SENT finding says so instead of pretending
 * a reply.
 *
 * <p><strong>Geometry is the owner's business.</strong> The card fills the
 * width its inlay container assigns (the editor's text width, minus the
 * indent border the host adds): every text block is a non-editable, wrapped,
 * selectable {@link javax.swing.JTextArea} whose preferred height is
 * computed at the assigned width, so nothing truncates and every word is
 * selectable for copy-paste. The width arrives through {@link
 * #setWrapWidth(int)} (the container, on attach and on every editor resize;
 * the card's own {@link #doLayout} re-applies it when the assigned width
 * changes) — until it is set the card reports an unwrapped preferred size,
 * which is why the container must apply the width before the inlay is
 * measured.</p>
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
    private final List<TextBlock> blocks = new ArrayList<>();
    private final TextBlock header = new TextBlock();
    private final JLabel meta = new JLabel();
    private final JLabel state = new JLabel();
    private final TextBlock drift = new TextBlock();
    private final TextBlock thread = new TextBlock();
    private final JTextField comment = new JTextField();
    private final JPanel actions = new JPanel(new BorderLayout());
    private final JPanel actionButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
    private final JLabel progress = new JLabel(PROGRESS_TEXT);
    private ReviewAnnotation finding;
    private boolean busy;
    private boolean disposed;
    /** The content width the text blocks wrap at; 0 until the container assigns one. */
    private int wrapWidth;

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
        actions.add(actionButtons, BorderLayout.EAST);
        // vertical order: header, meta, state, drift hint, thread, comment box, actions, progress
        List.of(header, meta, state, drift, thread, comment, actions, progress).forEach(this::add);
        blocks.addAll(List.of(header, drift, thread));
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

    /**
     * The content width the text blocks wrap at — the inlay container calls
     * this when it attaches the card and whenever the editor's text width
     * changes. Zero or negative (no width yet, or a degenerate container)
     * leaves the blocks unwrapped.
     */
    public void setWrapWidth(int width) {
        checkEdt();
        Insets insets = getInsets();
        int content = width - insets.left - insets.right;
        if (content <= 0 || content == wrapWidth) {
            return;
        }
        wrapWidth = content;
        applyWrapWidth();
        revalidate();
    }

    /** The card's own layout: a changed assigned width re-wraps the blocks at it. */
    @Override
    public void doLayout() {
        super.doLayout();
        Insets insets = getInsets();
        int assigned = getWidth() - insets.left - insets.right;
        if (assigned > 0 && assigned != wrapWidth) {
            wrapWidth = assigned;
            applyWrapWidth();
        }
    }

    /**
     * The preferred size at the assigned width: every block's preferred
     * height is already the wrapped height ({@link #applyWrapWidth} sizes
     * each block at {@code wrapWidth} before its height is read), so this is
     * the height the container and the inlay must reserve — never the
     * unwrapped height a fresh text area would report, which was the
     * truncation.
     */
    @Override
    public Dimension getPreferredSize() {
        Insets insets = getInsets();
        int height = insets.top + insets.bottom;
        int width = Math.max(wrapWidth, 0) + insets.left + insets.right;
        for (Component child : getComponents()) {
            if (!child.isVisible()) {
                continue;
            }
            Dimension preferred = child.getPreferredSize();
            height += preferred.height;
            width = Math.max(width, preferred.width + insets.left + insets.right);
        }
        return new Dimension(width, height);
    }

    /** Re-sizes every text block at the current width so its preferred height is the wrapped height. */
    private void applyWrapWidth() {
        for (TextBlock block : blocks) {
            block.wrapAt(wrapWidth);
        }
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

    String headerText() {
        return header.getText();
    }

    String metaText() {
        return meta.getText();
    }

    List<String> threadLines() {
        return thread.getText().isEmpty() ? List.of() : List.of(thread.getText().split("\n", -1));
    }

    /** The text blocks' wrapped preferred heights, for the wrapping test. */
    List<Integer> blockHeights() {
        return blocks.stream().map(block -> block.getPreferredSize().height).toList();
    }

    private void render() {
        header.setText(finding.effectiveSeverity().wireName() + " · " + finding.displayTitle());
        meta.setText(finding.author() + " · " + age(finding.at()));
        renderState();
        renderThread();
        renderActions();
        applyWrapWidth();
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
        List<String> lines = new ArrayList<>();
        for (ReviewAnnotation.Message message : finding.thread()) {
            lines.add(message.author() + " · " + age(message.at()) + ": " + message.text());
        }
        thread.setText(String.join("\n", lines));
        thread.setVisible(!lines.isEmpty());
    }

    private void renderActions() {
        actionButtons.removeAll();
        if (finding.triage() == Triage.PROPOSED) {
            actionButtons.add(button("Confirm", callbacks::confirm));
            actionButtons.add(button("Dismiss", callbacks::dismiss));
            actionButtons.add(button("Fix", callbacks::fix));
        } else {
            actionButtons.add(button("Undo", callbacks::undo));
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
        for (Component child : actionButtons.getComponents()) {
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

    /**
     * One wrapped, selectable, non-editable text block. The preferred height
     * is the height of the text wrapped at {@code wrapWidth} — recomputed by
     * sizing the area at that width ({@link #wrapAt}), the standard Swing
     * trick, because a fresh text area's preferred size is the unwrapped
     * single line. Selectable but not editable: the reviewer can select and
     * copy the finding's words, never change them.
     */
    private static final class TextBlock extends JTextArea {

        private TextBlock() {
            setLineWrap(true);
            setWrapStyleWord(true);
            setEditable(false);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder());
            setFont((Font) javax.swing.UIManager.get("Label.font"));
        }

        /** Re-sizes the block at {@code width} so its preferred height is the wrapped height. */
        void wrapAt(int width) {
            if (width <= 0) {
                return;
            }
            setSize(width, Integer.MAX_VALUE);
            Dimension wrapped = getPreferredSize();
            setPreferredSize(new Dimension(width, wrapped.height));
        }
    }
}
