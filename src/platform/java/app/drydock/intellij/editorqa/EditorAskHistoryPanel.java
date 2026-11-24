package app.drydock.intellij.editorqa;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import app.drydock.editorqa.EditorAskPorts.Result;
import app.drydock.editorqa.EditorAskRecord;
import app.drydock.intellij.editorqa.EditorAskUiModel.Recovery;
import app.drydock.intellij.editorqa.EditorAskUiModel.Row;

/**
 * The bounded exchange history of one checkout's Q&A session, one component
 * per exchange <em>ID</em>: status/progress, the question, recovery actions
 * and the full answer. Every string that came from a user, a file or an
 * agent (question, answer, failure reason) is shown in a non-editable
 * {@link JTextArea}, which never interprets markup; the few labels are
 * ours and carry {@code html.disable}. Updates patch the ID-keyed
 * components in place, so selection and scroll survive progress ticks.
 *
 * <p>The panel only reads {@link EditorAskUiModel}; the model's coalesced
 * notifications drive it. {@link #dispose} detaches it; nothing here owns
 * or cancels an admission.</p>
 */
public final class EditorAskHistoryPanel extends JPanel {

    /** What the recovery buttons do; the host wires them (Settings, session terminal, resubmission). */
    public interface Actions {
        void openSettings();

        /** The explicit user path for trust/auth/permission: bring the session's own terminal forward. */
        void openSessionTerminal();

        /** Resubmits the exchange as a <em>new</em> exchange (new ID, new reservation). */
        CompletableFuture<Result> retry(EditorAskRecord record);
    }

    private static final class View {
        final String id;
        final JPanel card = new JPanel();
        final JLabel status = new JLabel();
        final JTextArea question = plain();
        final JTextArea answer = plain();
        final JTextArea reason = plain();
        final JPanel actions = new JPanel();
        String actionsKey = "";
        boolean retrying;

        View(String id) {
            this.id = id;
            card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Color.GRAY),
                    BorderFactory.createEmptyBorder(4, 4, 4, 4)));
            status.putClientProperty("html.disable", Boolean.TRUE);
            actions.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 0));
            for (JComponent c : new JComponent[] {status, question, reason, answer, actions}) {
                c.setAlignmentX(Component.LEFT_ALIGNMENT);
                card.add(c);
            }
        }
    }

    private static JTextArea plain() {
        JTextArea a = new JTextArea();
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setOpaque(false);
        a.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
        return a;
    }

    private final EditorAskUiModel model;
    private final String root;
    private final Actions actions;
    private final Executor ui;
    private final Map<String, View> views = new LinkedHashMap<>();
    private final JPanel list = new JPanel();
    private final JTextArea notices = plain();
    private final JScrollPane scroll;
    private final Runnable detach;
    private boolean disposed;
    private String pendingSelect;

    public EditorAskHistoryPanel(EditorAskUiModel model, String root, Actions actions) {
        this(model, root, actions, SwingUtilities::invokeLater);
    }

    public EditorAskHistoryPanel(EditorAskUiModel model, String root, Actions actions, Executor ui) {
        super(new BorderLayout());
        this.model = Objects.requireNonNull(model, "model");
        this.root = Objects.requireNonNull(root, "root");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.ui = Objects.requireNonNull(ui, "ui");
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        notices.setVisible(false);
        add(notices, BorderLayout.NORTH);
        scroll = new JScrollPane(list);
        add(scroll, BorderLayout.CENTER);
        this.detach = model.addListener(this::refresh);
        refresh();
    }

    /** Detaches from the model. Pending submissions or retries are unaffected. */
    public void dispose() {
        disposed = true;
        detach.run();
    }

    /** Re-reads the model and patches the ID-keyed components. EDT only. */
    public void refresh() {
        if (disposed) {
            return;
        }
        List<Row> rows = model.rows(root);
        Map<String, Row> byId = new HashMap<>();
        for (Row r : rows) {
            byId.put(r.id(), r);
        }
        views.keySet().removeIf(id -> {
            if (byId.containsKey(id)) {
                return false;
            }
            list.remove(views.get(id).card);
            return true;
        });
        int index = 0;
        for (Row r : rows) {
            View v = views.get(r.id());
            if (v == null) {
                v = new View(r.id());
                views.put(r.id(), v);
                list.add(v.card, index);
            }
            update(v, r);
            index++;
        }
        List<String> n = model.notices(root);
        notices.setText(String.join("\n", n));
        notices.setVisible(!n.isEmpty());
        list.revalidate();
        list.repaint();
        if (pendingSelect != null && views.containsKey(pendingSelect)) {
            String id = pendingSelect;
            pendingSelect = null;
            select(id);
        }
    }

    private void update(View v, Row r) {
        EditorAskRecord rec = r.record();
        setText(v.status, "#" + rec.acceptanceSequence() + "  " + r.label());
        setText(v.question, rec.question());
        String reason = r.reason();
        boolean showReason = reason != null && !reason.equals(r.label()) && rec.isSettled();
        setText(v.reason, showReason ? reason : "");
        v.reason.setVisible(showReason);
        boolean hasAnswer = rec.answer() != null;
        setText(v.answer, hasAnswer ? rec.answer() : "");
        v.answer.setVisible(hasAnswer);
        String key = r.recovery() + "|" + r.settingsPointer() + "|" + v.retrying;
        if (!key.equals(v.actionsKey)) {
            v.actionsKey = key;
            rebuildActions(v, r);
        }
    }

    private void rebuildActions(View v, Row r) {
        v.actions.removeAll();
        if (r.settingsPointer() || r.recovery() == Recovery.SETTINGS) {
            JButton b = new JButton("Open Settings");
            b.addActionListener(e -> actions.openSettings());
            v.actions.add(b);
        }
        if (r.recovery() == Recovery.USER_TERMINAL) {
            JButton b = new JButton("Open the session terminal");
            b.addActionListener(e -> actions.openSessionTerminal());
            v.actions.add(b);
        }
        if (r.recovery() != Recovery.NONE) {
            JButton b = new JButton(v.retrying ? "Retrying…" : "Retry");
            b.setEnabled(!v.retrying);
            b.addActionListener(e -> retry(v, r.record()));
            v.actions.add(b);
        }
        v.actions.setVisible(v.actions.getComponentCount() > 0);
        v.actions.revalidate();
    }

    private void retry(View v, EditorAskRecord record) {
        if (v.retrying) {
            return;
        }
        v.retrying = true;
        refresh();
        CompletableFuture<Result> f;
        try {
            f = Objects.requireNonNull(actions.retry(record));
        } catch (RuntimeException e) {
            clearRetry(v);
            return;
        }
        f.whenComplete((res, err) -> {
            try {
                ui.execute(() -> clearRetry(v));
            } catch (RuntimeException e) {
                // UI gone
            }
        });
    }

    private void clearRetry(View v) {
        v.retrying = false; // every completion path, including failure, clears the progress
        if (!disposed) {
            refresh();
        }
    }

    private static void setText(JLabel l, String text) {
        if (!Objects.equals(l.getText(), text)) {
            l.setText(text);
        }
    }

    private static void setText(JTextArea a, String text) {
        if (!Objects.equals(a.getText(), text)) {
            a.setText(text);
        }
    }

    /** Scrolls the component of exchange {@code id} into view; if it is not mounted yet, does so once it is. */
    public boolean select(String id) {
        View v = views.get(id);
        if (v == null) {
            pendingSelect = id;
            return false;
        }
        pendingSelect = null;
        list.validate();
        Rectangle b = v.card.getBounds();
        list.scrollRectToVisible(b);
        v.card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.ORANGE, 2),
                BorderFactory.createEmptyBorder(2, 2, 2, 2)));
        for (View other : views.values()) {
            if (other != v && other.card.getBorder() != null) {
                other.card.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            }
        }
        return true;
    }

    // ---- test access ----

    JPanel cardOf(String id) {
        View v = views.get(id);
        return v == null ? null : v.card;
    }

    String statusOf(String id) {
        return views.get(id).status.getText();
    }

    JTextArea answerAreaOf(String id) {
        return views.get(id).answer;
    }

    JPanel actionsOf(String id) {
        return views.get(id).actions;
    }

    int viewCount() {
        return views.size();
    }
}
