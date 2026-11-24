package app.drydock.intellij.editorqa;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.editorqa.EditorAskLimits;
import app.drydock.editorqa.EditorAskPorts.Submission;
import app.drydock.editorqa.EditorAskPorts.Result;

/**
 * The question composer of a Q&A session tab: the only place a question is
 * typed in that tab (the marked terminal is display-only). Submitting hands
 * the text to the coordinator through {@link Sink} as a normal FIFO turn --
 * same ID and sequence as an editor ask, no anchor.
 *
 * <p>The submit shows "Submitting…" at once and disables the controls; the
 * sink runs on the background executor, never the EDT, and every completion
 * path (accepted, refused, thrown, rejected input, stale generation)
 * clears that progress. Closing the UI ({@link #dispose}) only stops UI
 * updates: a question already handed over stays admitted, because
 * admission belongs to the coordinator, not to this view.</p>
 */
public final class EditorAskComposerPanel extends JPanel {

    /** Submits a validated question; may block until admission, so it is only called off the EDT. */
    @FunctionalInterface
    public interface Sink {
        CompletableFuture<Result> submit(String question);
    }

    /**
     * Builds the submission of a tab question: an ordinary FIFO turn with the
     * same ID/sequence machinery as an editor ask, but with no file, range,
     * selection or anchor (nothing is fabricated; no editor inlay is restored).
     */
    public static Submission tabSubmission(String canonicalRoot, ManagedAgentSession candidateRow, String question) {
        return new Submission(canonicalRoot, candidateRow, null, null, null, question, null);
    }

    private final Sink sink;
    private final Executor background;
    private final Executor ui;
    private final JTextArea input = new JTextArea(3, 40);
    private final JButton send = new JButton("Ask");
    private final JLabel status = new JLabel(" ");
    private String unavailableReason;
    private boolean busy;
    private long generation;
    private boolean disposed;
    private String shownReason;

    public EditorAskComposerPanel(Sink sink) {
        this(sink, Runnable::run, SwingUtilities::invokeLater);
    }

    /** {@code background} runs the sink; {@code ui} hops results back (both injectable for tests). */
    public EditorAskComposerPanel(Sink sink, Executor background, Executor ui) {
        super(new BorderLayout(4, 4));
        this.sink = Objects.requireNonNull(sink, "sink");
        this.background = Objects.requireNonNull(background, "background");
        this.ui = Objects.requireNonNull(ui, "ui");
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        status.putClientProperty("html.disable", Boolean.TRUE);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        south.add(send);
        south.add(status);
        add(new JScrollPane(input), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        send.addActionListener(e -> submit());
        refresh();
    }

    /** Disables the composer with a reason (no harness, Q&A off), or re-enables it when {@code null}. */
    public void setUnavailable(String reason) {
        unavailableReason = reason == null || reason.isBlank() ? null : reason;
        refresh();
    }

    /** UI updates stop; an admission already requested is not touched. */
    public void dispose() {
        disposed = true;
        generation++;
    }

    public boolean isSendEnabled() {
        return send.isEnabled();
    }

    public String statusText() {
        return status.getText();
    }

    public void setQuestion(String text) {
        input.setText(text);
    }

    /** Triggers a submit as the button does. Must run on the EDT. */
    public void submit() {
        if (disposed || busy || unavailableReason != null) {
            return;
        }
        String question = input.getText();
        try {
            EditorAskLimits.checkQuestion(question);
        } catch (RuntimeException e) {
            status.setText(e.getMessage() == null ? "The question cannot be sent." : e.getMessage());
            return;
        }
        busy = true;
        long mine = ++generation;
        status.setText("Submitting…");
        refresh();
        try {
            background.execute(() -> run(mine, question));
        } catch (RuntimeException e) {
            finish(mine, null, e);
        }
    }

    private void run(long mine, String question) {
        CompletableFuture<Result> f;
        try {
            f = Objects.requireNonNull(sink.submit(question), "sink returned no future");
        } catch (RuntimeException e) {
            hop(() -> finish(mine, null, e));
            return;
        }
        f.whenComplete((r, e) -> hop(() -> finish(mine, r, e)));
    }

    private void hop(Runnable r) {
        try {
            ui.execute(r);
        } catch (RuntimeException e) {
            // the UI is gone; there is nothing left to clear
        }
    }

    private void finish(long mine, Result result, Throwable error) {
        if (disposed || mine != generation) {
            return; // a closed view must not be touched; admission is unaffected
        }
        busy = false;
        if (error != null || result == null) {
            status.setText("The question could not be submitted; it was not sent.");
        } else if (!result.accepted()) {
            status.setText(result.refusal() == null ? "The question was not accepted." : result.refusal());
        } else {
            input.setText("");
            status.setText("Queued as " + result.id());
        }
        refresh();
    }

    private void refresh() {
        boolean blocked = unavailableReason != null;
        send.setEnabled(!blocked && !busy);
        input.setEnabled(!blocked);
        if (blocked && !busy) {
            shownReason = unavailableReason;
            status.setText(unavailableReason);
        } else if (!blocked && shownReason != null) {
            if (shownReason.equals(status.getText())) {
                status.setText(" ");
            }
            shownReason = null;
        }
    }
}
