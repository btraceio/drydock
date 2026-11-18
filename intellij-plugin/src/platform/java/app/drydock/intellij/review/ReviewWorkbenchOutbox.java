package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.OutboxModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import app.drydock.review.ReviewAnnotation;
import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.panels.HorizontalLayout;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The workbench's outbox dock (design A, §9): full width under the panes,
 * collapsible. Collapsed it is one bar: "Outbox", the Pull request and
 * Session tabs with their counts, the last outcome, and the quick actions
 * Send to Session and Review &amp; Post…. Open, the bar keeps the tabs and
 * the body below lists the tab's items (source, severity, a file:line link
 * that shows the hunk, the text) beside its actions. <b>Pull request</b>,
 * in a pull request review only: the comments to post and Review &amp;
 * Post…, whose dialog picks the verdict. <b>Session</b>: the findings Send
 * to Session hands the bound session and, in a pull request review, where
 * the fixes land. A button says why it is disabled and shows its progress
 * until its work completes. Opening squeezes the panes above; the dock never
 * covers them. EDT.
 */
final class ReviewWorkbenchOutbox extends JPanel {

    static final String OUTBOX = "Outbox";
    static final String TITLE = "Session";
    static final String SEND = "Send to Session";
    static final String SENDING = "Sending…";
    static final String NO_SESSION = "No agent session is bound to this review.";
    static final String COMMENTED = "your comment";
    static final String FIXES_LAND_ON = "Fixes land on";
    static final String SAME_BRANCH = "Same branch";
    static final String STACKED_SUB_PR = "Stacked sub-PR";
    static final String SAME_BRANCH_HINT = "The agent commits the fixes on the branch under review.";
    static final String STACKED_HINT = "The agent commits the fixes on a new branch from the PR's head and opens a "
            + "pull request on top of it; drydock creates no branch itself.";
    static final String PR_LANE = "Pull request";
    static final String POST_HINT = "Each comment lands on its line on GitHub. The verdict goes on the review.";
    static final String SUBMIT_REVIEW = "Review & Post…";
    static final String CHECKING = "Checking GitHub…";
    static final String NOTHING_FOR_PR = "Comments for the pull request appear here.";
    static final String NOTHING_FOR_SESSION = "Findings to fix appear here.";
    /** Whether the dock is open; per user. */
    static final String OPEN_KEY = "drydock.review.outbox.open";
    /** The open body's height, unscaled. */
    static final int BODY_HEIGHT = 224;
    private static final String TOOLBAR_PLACE = "DrydockReviewWorkbenchOutbox";
    private static final String SESSION_CARD = "session";
    private static final String PR_CARD = "pr";

    /** What the dock asks of the live review. */
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

        /** Shows the hunk an item's finding sits on. EDT. */
        default void showFinding(ReviewAnnotation finding) {
        }
    }

    private final Actions actions;
    private final JButton toggle = new JButton();
    final JBLabel notice = new JBLabel();
    /** The bar's quick actions, shown while the dock is collapsed. */
    final JButton quickSend = new JButton();
    final JButton quickPost = new JButton(SUBMIT_REVIEW);
    private final JPanel quick = new JPanel(new HorizontalLayout(JBUI.scale(6)));
    private final JPanel rows = new JPanel();
    private final JPanel body = new JPanel(new BorderLayout());
    private final CardLayout sideCards = new CardLayout();
    private final JPanel side = new JPanel(sideCards);
    final JButton send = new JButton(SEND);
    final JBLabel reason = new JBLabel();
    final JRadioButton sameBranch = new JRadioButton(SAME_BRANCH);
    final JRadioButton stackedSubPr = new JRadioButton(STACKED_SUB_PR);
    private final JPanel fixesLandOn = new JPanel();
    private final JTextArea fixHint;
    final JButton submitReview = new JButton(SUBMIT_REVIEW);
    final JBLabel prNotice = new JBLabel();
    /** The tabs: Session, and Pull request in a pull request review. */
    final DefaultActionGroup tabActions = new DefaultActionGroup();
    private final ActionToolbar tabs;
    private boolean checking;
    private OutboxModel.Snapshot shown = OutboxModel.Snapshot.EMPTY;
    /** The lane and tab the item rows were last built for; null before the first build. */
    private OutboxModel.Snapshot rowsLane;
    private boolean rowsPrTab;
    private boolean sending;
    /** Whether the Pull request tab shows; only in a pull request review. EDT. */
    private boolean prTab;
    private boolean open;

    ReviewWorkbenchOutbox(Actions actions) {
        super(new BorderLayout());
        this.actions = Objects.requireNonNull(actions, "actions");
        setBorder(JBUI.Borders.customLineTop(JBColor.border()));

        toggle.setBorder(JBUI.Borders.empty(2));
        toggle.setFocusable(false);
        toggle.setContentAreaFilled(false);
        toggle.addActionListener(e -> setOpen(!open));
        JBLabel title = new JBLabel(OUTBOX);
        title.setFont(JBUI.Fonts.label().asBold());
        tabActions.add(new Tab(true));
        tabActions.add(new Tab(false));
        tabs = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, tabActions, true);
        tabs.setTargetComponent(this);
        tabs.getComponent().setOpaque(false);
        notice.setForeground(UIUtil.getContextHelpForeground());
        quickSend.addActionListener(e -> send());
        quickPost.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true);
        quickPost.addActionListener(e -> submitReview());
        quick.setOpaque(false);
        quick.add(quickSend);
        quick.add(quickPost);
        JPanel west = new JPanel(new HorizontalLayout(JBUI.scale(6)));
        west.setOpaque(false);
        west.add(toggle);
        west.add(title);
        west.add(tabs.getComponent());
        JPanel east = new JPanel(new HorizontalLayout(JBUI.scale(8)));
        east.setOpaque(false);
        east.add(notice);
        east.add(quick);
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(JBUI.Borders.empty(0, 6, 0, 8));
        bar.setPreferredSize(new Dimension(0, JBUI.scale(36)));
        bar.add(west, BorderLayout.WEST);
        bar.add(east, BorderLayout.EAST);

        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setBorder(JBUI.Borders.empty(10, 12));
        JBScrollPane list = new JBScrollPane(rows);
        list.setBorder(JBUI.Borders.empty());

        send.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true);
        send.addActionListener(e -> send());
        reason.setForeground(UIUtil.getContextHelpForeground());
        ButtonGroup group = new ButtonGroup();
        group.add(sameBranch);
        group.add(stackedSubPr);
        sameBranch.addActionListener(e -> actions.fixesLandOn(false));
        stackedSubPr.addActionListener(e -> actions.fixesLandOn(true));
        fixHint = ReviewTypography.prose(ReviewTypography.Role.META);
        JBLabel fixesLabel = new JBLabel(FIXES_LAND_ON);
        fixesLabel.setForeground(UIUtil.getContextHelpForeground());
        JPanel choices = new JPanel(new HorizontalLayout(JBUI.scale(8)));
        choices.setOpaque(false);
        choices.add(sameBranch);
        choices.add(stackedSubPr);
        fixesLandOn.setLayout(new BoxLayout(fixesLandOn, BoxLayout.Y_AXIS));
        fixesLandOn.setOpaque(false);
        fixesLandOn.add(left(fixesLabel));
        fixesLandOn.add(left(choices));
        fixesLandOn.add(left(ReviewTypography.measured(fixHint)));
        JPanel sessionSide = column(fixesLandOn, send, reason);

        submitReview.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true);
        submitReview.addActionListener(e -> submitReview());
        prNotice.setForeground(UIUtil.getContextHelpForeground());
        JTextArea postHint = ReviewTypography.prose(ReviewTypography.Role.META);
        postHint.setText(POST_HINT);
        JPanel prSide = column(ReviewTypography.measured(postHint), submitReview, prNotice);
        side.add(sessionSide, SESSION_CARD);
        side.add(prSide, PR_CARD);
        side.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineLeft(JBColor.border()),
                JBUI.Borders.empty(12, 14)));
        side.setPreferredSize(new Dimension(JBUI.scale(300), 0));

        body.setBorder(JBUI.Borders.customLineTop(JBColor.border()));
        body.setPreferredSize(new Dimension(0, JBUI.scale(BODY_HEIGHT)));
        body.add(list, BorderLayout.CENTER);
        body.add(side, BorderLayout.EAST);

        add(bar, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        open = PropertiesComponent.getInstance().getBoolean(OPEN_KEY, false);
        render(OutboxModel.Snapshot.EMPTY);
    }

    private static JComponent left(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }

    private static JPanel column(JComponent... parts) {
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        for (JComponent part : parts) {
            column.add(left(part));
            column.add(Box.createVerticalStrut(JBUI.scale(10)));
        }
        return column;
    }

    /** Shows {@code state}'s lanes. */
    void render(WorkbenchState state) {
        render(state.outbox());
    }

    void render(OutboxModel.Snapshot lane) {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("ReviewWorkbenchOutbox renders on the EDT");
        }
        shown = Objects.requireNonNull(lane, "lane");
        if (!lane.prMode()) {
            prTab = false; // a local review has no Pull request tab
        }
        fixesLandOn.setVisible(lane.prMode());
        sameBranch.setSelected(!lane.stackedSubPr());
        stackedSubPr.setSelected(lane.stackedSubPr());
        fixHint.setText(lane.stackedSubPr() ? STACKED_HINT : SAME_BRANCH_HINT);
        renderRows();
        updateButtons();
        applyOpen();
        tabs.updateActionsAsync();
    }

    /** Rebuilds the item rows, unless they already show {@link #shown}'s lane on the current tab. */
    private void renderRows() {
        if (shown.equals(rowsLane) && prTab == rowsPrTab) {
            return;
        }
        rowsLane = shown;
        rowsPrTab = prTab;
        rows.removeAll();
        List<OutboxModel.Item> items = prTab ? shown.forPr() : shown.toFix();
        if (items.isEmpty()) {
            JBLabel empty = new JBLabel(prTab ? NOTHING_FOR_PR : shown.countsText().equals("Nothing to send")
                    ? NOTHING_FOR_SESSION : shown.countsText());
            empty.setForeground(UIUtil.getContextHelpForeground());
            rows.add(left(empty));
        }
        for (OutboxModel.Item item : items) {
            rows.add(left(row(item)));
            rows.add(Box.createVerticalStrut(JBUI.scale(8)));
        }
        if (!prTab && shown.dismissed() > 0 && !items.isEmpty()) {
            JBLabel dismissed = new JBLabel(shown.dismissed() + " dismissed go along as no-action");
            dismissed.setForeground(UIUtil.getContextHelpForeground());
            rows.add(left(dismissed));
        }
        rows.revalidate();
        rows.repaint();
    }

    /** One item: who raised it, its severity, its place (a link to the hunk), and its title. */
    private JComponent row(OutboxModel.Item item) {
        ReviewAnnotation finding = item.finding();
        boolean human = "You".equals(finding.author());
        JBLabel source = new JBLabel(human ? "You" : finding.author(), human ? null : AllIcons.Actions.Lightning,
                JBLabel.LEFT);
        if (human) {
            source.setFont(JBUI.Fonts.label().asBold());
        } else {
            source.setForeground(UIUtil.getContextHelpForeground());
        }
        JBLabel severity = new JBLabel(SeverityPresentation.label(finding.effectiveSeverity()),
                SeverityPresentation.icon(finding.effectiveSeverity()), JBLabel.LEFT);
        severity.setForeground(SeverityPresentation.foreground(finding.effectiveSeverity()));
        JPanel top = new JPanel(new HorizontalLayout(JBUI.scale(8)));
        top.setOpaque(false);
        top.add(source);
        top.add(severity);
        top.add(new ActionLink(place(finding), (ActionListener) e -> actions.showFinding(finding)));
        JTextArea text = ReviewTypography.prose(ReviewTypography.Role.BODY);
        text.setText(finding.displayTitle() + (item.commented() ? " · " + COMMENTED : ""));
        JPanel card = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        card.setBackground(UIUtil.getTextFieldBackground());
        card.setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(8, 10)));
        card.add(top, BorderLayout.NORTH);
        card.add(ReviewTypography.measured(text), BorderLayout.CENTER);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        return card;
    }

    /** A finding's place (pure): "src/A.java:5", "src/A.java:9 (removed)", or "the whole change". */
    static String place(ReviewAnnotation finding) {
        if (finding.wholePr()) {
            return "the whole change";
        }
        String key = finding.startKey();
        String line = key.length() < 2 ? "" : ":" + key.substring(1) + (key.startsWith("o") ? " (removed)" : "");
        return finding.file() + line;
    }

    /** Opens or collapses the dock; the choice persists per user. EDT. */
    void setOpen(boolean next) {
        open = next;
        PropertiesComponent.getInstance().setValue(OPEN_KEY, next);
        applyOpen();
    }

    boolean isOpen() {
        return open;
    }

    private void applyOpen() {
        body.setVisible(open);
        quick.setVisible(!open);
        toggle.setIcon(open ? AllIcons.General.ChevronDown : AllIcons.General.ChevronUp);
        toggle.setToolTipText(open ? "Collapse the outbox" : "Open the outbox");
        sideCards.show(side, prTab ? PR_CARD : SESSION_CARD);
        revalidate();
        repaint();
    }

    /** Shows the Pull request tab, or the Session tab, and opens the dock; a local review only shows Session. EDT. */
    void showPrTab(boolean pr) {
        prTab = pr && shown.prMode();
        renderRows();
        if (!open) {
            setOpen(true);
        } else {
            applyOpen();
        }
    }

    /** Whether the Pull request tab shows. EDT. */
    boolean prTabShown() {
        return prTab;
    }

    /** One tab's label (pure): "Session (2)", "Pull request (1)". */
    static String tabLabel(boolean pr, OutboxModel.Snapshot lane) {
        return pr ? PR_LANE + " (" + lane.waitingForPr() + ")" : TITLE + " (" + lane.toFix().size() + ")";
    }

    /** One of the two tabs, selected while the dock shows it; Pull request is shown in a PR review only. */
    private final class Tab extends DumbAwareToggleAction {
        private final boolean pr;

        Tab(boolean pr) {
            super(pr ? PR_LANE : TITLE);
            this.pr = pr;
            ReviewWorkbenchEditor.showTextInToolbar(this);
        }

        @Override public boolean isSelected(@NotNull AnActionEvent e) {
            return open && prTab == pr;
        }

        @Override public void setSelected(@NotNull AnActionEvent e, boolean selected) {
            if (selected) {
                showPrTab(pr);
            } else {
                setOpen(false);
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

    /** The send buttons' label (pure): "Send 2 to Session", or "Send to Session" with nothing to send. */
    static String sendLabel(OutboxModel.Snapshot lane) {
        int n = lane.toFix().size() + lane.dismissed();
        return n == 0 ? SEND : "Send " + n + " to Session";
    }

    private void submitReview() {
        if (checking) {
            return;
        }
        checking = true;
        prNotice.setText("");
        updateButtons();
        actions.submitReview().whenComplete((message, error) -> EventQueue.invokeLater(() -> {
            checking = false;
            String text = error != null ? "Could not open the review: " + ReviewTabProvider.rootMessage(error)
                    : message == null ? "" : message.orElse("");
            prNotice.setText(text);
            if (!text.isEmpty()) {
                notice.setText(text);
            }
            updateButtons();
        }));
    }

    private void updateButtons() {
        String label = sending ? SENDING : sendLabel(shown);
        boolean canSend = !sending && shown.pending() && shown.sessionBound();
        send.setText(label);
        send.setEnabled(canSend);
        quickSend.setText(label);
        quickSend.setEnabled(canSend);
        quickSend.setVisible(shown.sessionBound() || shown.pending());
        reason.setText(shown.pending() && !shown.sessionBound() ? NO_SESSION : "");
        submitReview.setText(checking ? CHECKING : SUBMIT_REVIEW);
        submitReview.setEnabled(!checking);
        quickPost.setText(checking ? CHECKING : SUBMIT_REVIEW);
        quickPost.setEnabled(!checking);
        quickPost.setVisible(shown.prMode());
    }

    private void send() {
        if (sending || !shown.pending() || !shown.sessionBound()) {
            return;
        }
        sending = true;
        notice.setText("");
        updateButtons();
        actions.send().whenComplete((message, error) -> EventQueue.invokeLater(() -> {
            sending = false;
            notice.setText(error != null ? "Sending failed: " + ReviewTabProvider.rootMessage(error)
                    : message == null ? "" : message.orElse(""));
            updateButtons();
        }));
    }
}
