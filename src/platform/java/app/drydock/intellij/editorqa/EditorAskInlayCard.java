package app.drydock.intellij.editorqa;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import app.drydock.editorqa.EditorAskRecord;

/**
 * The card an editor question leaves below its last selected line: a
 * foldable view of one exchange.
 *
 * <p>Collapsed it shows the question line, a 1-3 line plain preview of the
 * answer, the answer timestamp and an always-visible <em>Open session</em>
 * button; expanded it shows the full question and answer. Every string is
 * untrusted agent or user text and is rendered as plain text in
 * {@link JTextArea}s -- never through an HTML-interpreting label -- and the
 * only action is the plugin-owned Open session callback (INV-13). The card
 * never touches the document. After {@link #dispose()} an update or click
 * changes nothing.</p>
 */
final class EditorAskInlayCard extends JPanel {

    /** Lines of the answer a collapsed card shows. */
    static final int PREVIEW_LINES = 3;
    private static final int PREVIEW_LINE_CHARS = 160;
    private static final int QUESTION_CHARS = 160;
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.getDefault());

    private final ZoneId zone;
    private final Runnable openSession;
    private final JButton toggle = new JButton();
    private final JTextArea question = plain();
    private final JTextArea body = plain();
    private final JLabel time = new JLabel();
    private final JButton open = new JButton("Open session");
    private EditorAskRecord record;
    private boolean expanded;
    private boolean disposed;

    EditorAskInlayCard(EditorAskRecord record, ZoneId zone, Runnable openSession) {
        super(new BorderLayout());
        this.zone = Objects.requireNonNull(zone, "zone");
        this.openSession = Objects.requireNonNull(openSession, "openSession");
        this.record = Objects.requireNonNull(record, "record");
        toggle.setBorderPainted(false);
        toggle.setContentAreaFilled(false);
        toggle.setFocusable(false);
        toggle.addActionListener(e -> toggle());
        open.addActionListener(e -> {
            if (!disposed) {
                this.openSession.run();
            }
        });
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(toggle, BorderLayout.WEST);
        header.add(question, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(time);
        right.add(open);
        header.add(right, BorderLayout.EAST);
        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(header);
        center.add(Box.createVerticalStrut(2));
        center.add(body);
        add(center, BorderLayout.CENTER);
        setBorder(BorderFactory.createCompoundBorder(JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(2, 4)));
        render();
    }

    private static JTextArea plain() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFocusable(false);
        return area;
    }

    /** Replaces the shown exchange (same ID); ignored once disposed. */
    void update(EditorAskRecord updated) {
        Objects.requireNonNull(updated, "updated");
        if (disposed || updated.equals(record)) {
            return;
        }
        record = updated;
        render();
    }

    void toggle() {
        if (disposed) {
            return;
        }
        expanded = !expanded;
        render();
    }

    /** Detaches the card: later updates and clicks are no-ops. Idempotent. */
    void dispose() {
        disposed = true;
    }

    boolean isDisposed() {
        return disposed;
    }

    JComponent openSessionButton() {
        return open;
    }

    boolean expanded() {
        return expanded;
    }

    String questionText() {
        return question.getText();
    }

    String bodyText() {
        return body.getText();
    }

    String timeText() {
        return time.getText();
    }

    /** The first {@value #PREVIEW_LINES} non-blank lines of {@code text}, each bounded; ellipsis when more. */
    static String preview(String text) {
        String[] lines = text.strip().split("\\R");
        StringBuilder out = new StringBuilder();
        int shown = 0;
        boolean more = false;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            if (shown == PREVIEW_LINES) {
                more = true;
                break;
            }
            if (shown > 0) {
                out.append('\n');
            }
            out.append(line.length() > PREVIEW_LINE_CHARS ? line.substring(0, PREVIEW_LINE_CHARS) + "…" : line);
            shown++;
        }
        return more ? out + "\n…" : out.toString();
    }

    private void render() {
        toggle.setText(expanded ? "▾" : "▸");
        String q = record.question();
        String oneLine = q.replaceAll("\\s+", " ").strip();
        question.setText(expanded ? q : oneLine.length() > QUESTION_CHARS ? oneLine.substring(0, QUESTION_CHARS) + "…" : oneLine);
        Instant at = record.answeredAt() != null ? record.answeredAt() : record.askedAt();
        time.setText(TIME.format(at.atZone(zone)));
        String answer = record.answer();
        String text;
        if (answer != null) {
            text = expanded ? answer : preview(answer);
        } else {
            text = record.isSettled() ? "No answer was received." : "Waiting for the agent's answer…";
        }
        body.setText(text);
        revalidate();
        repaint();
    }
}
