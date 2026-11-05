package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.OutboxModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The workbench's bottom pane, the outbox (spec §8), in two tabs as the
 * inbox's are. <b>Session</b>: the findings Send to Session would hand the
 * bound session, the dismissed ones counted, the button and, in a pull
 * request review, where the fixes land; the button says why it is disabled,
 * shows "Sending…" until the send completes, and the outcome goes to the
 * tab's notice line. <b>Pull request</b>, in a pull request review only: the
 * comments to post and Submit Review…. EDT.
 */
final class ReviewWorkbenchOutbox extends JPanel {

    static final String TITLE = "Session";
    static final String SEND = "Send to Session";
    static final String SENDING = "Sending…";
    static final String NO_SESSION = "No agent session is bound to this review.";
    static final String COMMENTED = "your comment";
    static final String FIXES_LAND_ON = "Fixes land on:";
    static final String SAME_BRANCH = "Same branch";
    static final String STACKED_SUB_PR = "Stacked sub-PR";
    static final String PR_LANE = "Pull request";
    private static final String TOOLBAR_PLACE = "DrydockReviewWorkbenchOutbox";
    private static final String SESSION_CARD = "session";
    private static final String PR_CARD = "pr";
    static final String SUBMIT_REVIEW = "Submit Review…";
    static final String CHECKING = "Checking GitHub…";

    /** What the pane asks of the live review. */
    interface Actions {
        /** Sends the lane to the bound session; completes with the message to show, empty when none. */
        CompletableFuture<Optional<String>> send();

        /** "Fixes land on" was chosen (a pull request review only): a stacked sub-PR, or the PR's own branch. */
        default void fixesLandOn(boolean stackedSubPr) {
        }

        /**
         * Opens the PR lane's submit dialog after its checks (a pull request
         * review only); completes with the message to show when no dialog
         * opens, empty when it did.
         */
        default CompletableFuture<Optional<String>> submitReview() {
            return CompletableFuture.completedFuture(Optional.empty());
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
    private final JPanel prLane = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
    private final JBLabel prCounts = new JBLabel();
    final JButton submitReview = new JButton(SUBMIT_REVIEW);
    final JBLabel prNotice = new JBLabel();
    private boolean checking;
    private OutboxModel.Snapshot shown = OutboxModel.Snapshot.EMPTY;
    private boolean sending;
    /** The tabs: Session, and Pull request in a pull request review. */
    final DefaultActionGroup tabActions = new DefaultActionGroup();
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    /** Whether the Pull request tab shows; only in a pull request review. EDT. */
    private boolean prTab;

    ReviewWorkbenchOutbox(Actions actions) {
        super(new BorderLayout());
        this.actions = Objects.requireNonNull(actions, "actions");
        counts.setForeground(UIUtil.getContextHelpForeground());
        reason.setForeground(UIUtil.getContextHelpForeground());
        notice.setForeground(UIUtil.getContextHelpForeground());
        send.addActionListener(e -> send());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
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
        prCounts.setForeground(UIUtil.getContextHelpForeground());
        prNotice.setForeground(UIUtil.getContextHelpForeground());
        submitReview.addActionListener(e -> submitReview());
        prLane.add(prCounts);
        prLane.add(submitReview);
        prLane.add(prNotice);
        prLane.setBorder(JBUI.Borders.empty(4, 4));
        JPanel sessionNorth = new JPanel(new BorderLayout());
        sessionNorth.add(bar, BorderLayout.NORTH);
        sessionNorth.add(fixesLandOn, BorderLayout.CENTER);
        JPanel session = new JPanel(new BorderLayout());
        session.add(sessionNorth, BorderLayout.NORTH);
        session.add(new JBScrollPane(rows), BorderLayout.CENTER);
        JPanel pullRequest = new JPanel(new BorderLayout());
        pullRequest.add(prLane, BorderLayout.NORTH);
        body.add(session, SESSION_CARD);
        body.add(pullRequest, PR_CARD);
        tabActions.add(new Tab(false));
        tabActions.add(new Tab(true));
        ActionToolbar tabs = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, tabActions, true);
        tabs.setTargetComponent(this);
        add(tabs.getComponent(), BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
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
        if (!lane.prMode()) {
            showPrTab(false); // a local review has no Pull request tab
        }
        prCounts.setText(prCountsText(lane));
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

    /** Shows the Pull request tab, or the Session tab; a local review only ever shows Session. EDT. */
    void showPrTab(boolean pr) {
        prTab = pr && shown.prMode();
        cards.show(body, prTab ? PR_CARD : SESSION_CARD);
    }

    /** One tab's label (pure): "Session (2)", "Pull request (1)". */
    static String tabLabel(boolean pr, OutboxModel.Snapshot lane) {
        return pr ? PR_LANE + " (" + lane.waitingForPr() + ")" : TITLE + " (" + lane.toFix().size() + ")";
    }

    /** One of the two tabs, selected while the outbox shows it; Pull request is shown in a PR review only. */
    private final class Tab extends DumbAwareToggleAction {
        private final boolean pr;

        Tab(boolean pr) {
            super(pr ? PR_LANE : TITLE);
            this.pr = pr;
            ReviewWorkbenchEditor.showTextInToolbar(this);
        }

        @Override public boolean isSelected(@NotNull AnActionEvent e) {
            return prTab == pr;
        }

        @Override public void setSelected(@NotNull AnActionEvent e, boolean selected) {
            if (selected) {
                showPrTab(pr);
            }
        }

        @Override public void update(@NotNull AnActionEvent e) {
            super.update(e);
            e.getPresentation().setVisible(!pr || shown.prMode());
            e.getPresentation().setText(tabLabel(pr, shown));
        }

        @Override public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    /** The PR lane's counts (pure): "2 comments to post", or "No comments to post". */
    static String prCountsText(OutboxModel.Snapshot lane) {
        int n = lane.waitingForPr();
        return n == 0 ? "No comments to post" : n + (n == 1 ? " comment to post" : " comments to post");
    }

    private void submitReview() {
        if (checking) {
            return;
        }
        checking = true;
        submitReview.setText(CHECKING);
        submitReview.setEnabled(false);
        prNotice.setText("");
        actions.submitReview().whenComplete((message, error) -> EventQueue.invokeLater(() -> {
            checking = false;
            submitReview.setText(SUBMIT_REVIEW);
            submitReview.setEnabled(true);
            prNotice.setText(error != null ? "Could not open the review: " + ReviewTabProvider.rootMessage(error)
                    : message == null ? "" : message.orElse(""));
        }));
    }

    /**
     * One row (pure): "title — file:line · your comment"; a removed line's
     * key ({@code o<N>}) names the old line, "file:N (removed)".
     */
    static String rowText(OutboxModel.Item item) {
        if (item.finding().wholePr()) {
            return item.finding().displayTitle() + " — the whole change"
                    + (item.commented() ? " · " + COMMENTED : "");
        }
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
