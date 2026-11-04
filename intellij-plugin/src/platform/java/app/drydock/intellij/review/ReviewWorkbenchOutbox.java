package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.OutboxModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The workbench's bottom pane, the outbox's session lane (spec §8): the
 * findings Send to Session would hand the bound session, the dismissed ones
 * counted, the button and, in a pull request review, where the fixes land.
 * The button says why it is disabled, shows
 * "Sending…" until the send completes, and the outcome goes to the pane's
 * notice line. EDT.
 */
final class ReviewWorkbenchOutbox extends JPanel {

    static final String TITLE = "Session lane";
    static final String SEND = "Send to Session";
    static final String SENDING = "Sending…";
    static final String NO_SESSION = "No agent session is bound to this review.";
    static final String COMMENTED = "your comment";
    static final String FIXES_LAND_ON = "Fixes land on:";
    static final String SAME_BRANCH = "Same branch";
    static final String STACKED_SUB_PR = "Stacked sub-PR";

    /** What the pane asks of the live review. */
    interface Actions {
        /** Sends the lane to the bound session; completes with the message to show, empty when none. */
        CompletableFuture<Optional<String>> send();

        /** "Fixes land on" was chosen (a pull request review only): a stacked sub-PR, or the PR's own branch. */
        default void fixesLandOn(boolean stackedSubPr) {
        }
    }

    private final Actions actions;
    private final JBLabel counts = new JBLabel();
    final JButton send = new JButton(SEND);
    final JBLabel reason = new JBLabel();
    final JBLabel notice = new JBLabel();
    private final JPanel rows = new JPanel();
    final JRadioButton sameBranch = new JRadioButton(SAME_BRANCH);
    final JRadioButton stackedSubPr = new JRadioButton(STACKED_SUB_PR);
    private final JPanel fixesLandOn = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
    private OutboxModel.Snapshot shown = OutboxModel.Snapshot.EMPTY;
    private boolean sending;

    ReviewWorkbenchOutbox(Actions actions) {
        super(new BorderLayout());
        this.actions = Objects.requireNonNull(actions, "actions");
        JBLabel title = new JBLabel(TITLE);
        title.setFont(JBUI.Fonts.label().asBold());
        counts.setForeground(UIUtil.getContextHelpForeground());
        reason.setForeground(UIUtil.getContextHelpForeground());
        notice.setForeground(UIUtil.getContextHelpForeground());
        send.addActionListener(e -> send());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        bar.add(title);
        bar.add(counts);
        bar.add(send);
        bar.add(reason);
        bar.add(notice);
        bar.setBorder(JBUI.Borders.empty(4, 4));
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setBorder(JBUI.Borders.empty(0, 12, 4, 8));
        ButtonGroup group = new ButtonGroup();
        group.add(sameBranch);
        group.add(stackedSubPr);
        sameBranch.addActionListener(e -> actions.fixesLandOn(false));
        stackedSubPr.addActionListener(e -> actions.fixesLandOn(true));
        stackedSubPr.setToolTipText("The agent commits the fixes on a new branch from the PR's head and opens a "
                + "pull request on top of it; drydock creates no branch itself.");
        fixesLandOn.add(new JBLabel(FIXES_LAND_ON));
        fixesLandOn.add(sameBranch);
        fixesLandOn.add(stackedSubPr);
        fixesLandOn.setBorder(JBUI.Borders.emptyLeft(8));
        JPanel north = new JPanel(new BorderLayout());
        north.add(bar, BorderLayout.NORTH);
        north.add(fixesLandOn, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
        add(new JBScrollPane(rows), BorderLayout.CENTER);
        render(OutboxModel.Snapshot.EMPTY);
    }

    /** Shows {@code state}'s session lane. */
    void render(WorkbenchState state) {
        render(state.outbox());
    }

    void render(OutboxModel.Snapshot lane) {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("ReviewWorkbenchOutbox renders on the EDT");
        }
        shown = Objects.requireNonNull(lane, "lane");
        counts.setText(lane.countsText());
        fixesLandOn.setVisible(lane.prMode());
        sameBranch.setSelected(!lane.stackedSubPr());
        stackedSubPr.setSelected(lane.stackedSubPr());
        rows.removeAll();
        for (OutboxModel.Item item : lane.toFix()) {
            rows.add(new JBLabel(rowText(item)));
        }
        rows.revalidate();
        rows.repaint();
        updateButton();
    }

    /**
     * One row (pure): "title — file:line · your comment"; a removed line's
     * key ({@code o<N>}) names the old line, "file:N (removed)".
     */
    static String rowText(OutboxModel.Item item) {
        String key = item.finding().startKey();
        String line = key.length() < 2 ? "" : ":" + key.substring(1) + (key.startsWith("o") ? " (removed)" : "");
        return item.finding().displayTitle() + " — " + item.finding().file() + line
                + (item.commented() ? " · " + COMMENTED : "");
    }

    private void updateButton() {
        send.setText(sending ? SENDING : SEND);
        send.setEnabled(!sending && shown.pending() && shown.sessionBound());
        reason.setText(shown.pending() && !shown.sessionBound() ? NO_SESSION : "");
    }

    private void send() {
        if (sending || !shown.pending() || !shown.sessionBound()) {
            return;
        }
        sending = true;
        notice.setText("");
        updateButton();
        actions.send().whenComplete((message, error) -> EventQueue.invokeLater(() -> {
            sending = false;
            notice.setText(error != null ? "Sending failed: " + ReviewTabProvider.rootMessage(error)
                    : message == null ? "" : message.orElse(""));
            updateButton();
        }));
    }
}
