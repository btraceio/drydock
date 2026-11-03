package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.InboxModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import app.drydock.review.DiffSelection;
import app.drydock.review.ReviewAnnotation;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The workbench's findings inbox (spec §7): tab and scope toggles over the
 * cards of the {@link WorkbenchState#inbox()} it is handed, each card a
 * {@link FindingInlayCard} on the {@link FindingInlayCard.Surface#INBOX}
 * surface. Every card action goes through {@link Actions}; the card stays
 * busy until the action's future completes, and the outcome's text, when
 * there is one, shows in the notice line. After a decision takes a card
 * off the To Triage tab, the next card to triage is scrolled into view
 * and shown in the diff. EDT.
 */
final class ReviewWorkbenchInbox extends JPanel implements Disposable {

    static final String EMPTY_TEXT = "No findings here.";
    private static final String TOOLBAR_PLACE = "DrydockReviewWorkbenchInbox";

    /**
     * What the inbox asks the review to do. A future's value is the text to
     * show (empty when there is nothing to report); a failed future shows its
     * error.
     */
    interface Actions {
        CompletableFuture<Optional<String>> requestFix(ReviewAnnotation finding);

        CompletableFuture<Optional<String>> dismiss(ReviewAnnotation finding);

        CompletableFuture<Optional<String>> undo(ReviewAnnotation finding);

        CompletableFuture<Optional<String>> comment(ReviewAnnotation finding, String text);

        CompletableFuture<Optional<String>> challenge(ReviewAnnotation finding, String text);

        /** Shows the finding in the review diff. */
        void showInDiff(ReviewAnnotation finding);

        /** The inbox's tab or scope changed. */
        void choose(WorkbenchState.InboxChoice choice);

        /** The composer was submitted with {@code text} (never blank). */
        CompletableFuture<Optional<String>> submit(Composer composer, String text);
    }

    /**
     * What the composer writes: a comment on the selected lines, or a
     * question about them, over a range already resolved within one hunk.
     */
    record Composer(Mode mode, DiffSelection.Range range) {
        enum Mode { COMMENT, ASK }

        Composer {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(range, "range");
        }

        /** The composer's title (pure): "Comment on A.java, lines 10–12". */
        String title() {
            String verb = mode == Mode.COMMENT ? "Comment on " : "Ask about ";
            return verb + range.file() + ", " + lines(range.startKey(), range.endKey());
        }

        /** The note under the title when the selection was cut at its hunk; empty otherwise. */
        Optional<String> note() {
            return range.clamped()
                    ? Optional.of("The selection is cut at the end of its hunk: a review comment cannot span hunks.")
                    : Optional.empty();
        }

        String submitText() {
            return mode == Mode.COMMENT ? "Add Comment" : "Ask";
        }

        private static String lines(String startKey, String endKey) {
            return startKey.equals(endKey)
                    ? "line " + line(startKey)
                    : "lines " + line(startKey) + "–" + line(endKey);
        }

        /** A key as the line a reader sees: {@code n10} is 10, {@code o10} is "old 10". */
        private static String line(String key) {
            return key.startsWith("o") ? "old " + key.substring(1) : key.substring(1);
        }
    }

    private final Actions actions;
    private final Clock clock;
    private final JPanel stack = new TrackingPanel();
    private final JBLabel notice = new JBLabel();
    final DefaultActionGroup tabActions = new DefaultActionGroup();
    final DefaultActionGroup scopeActions = new DefaultActionGroup();
    private final Map<String, FindingInlayCard> cards = new LinkedHashMap<>();
    private final JPanel composerPanel = new JPanel(new BorderLayout());
    private final JBLabel composerTitle = new JBLabel();
    private final JBLabel composerNote = new JBLabel();
    private final JBTextArea composerText = new JBTextArea(3, 20);
    private final JButton composerSubmit = new JButton();
    private Composer composer;
    private InboxModel.Snapshot shown = InboxModel.Snapshot.empty(InboxModel.Tab.TO_TRIAGE, InboxModel.Scope.ALL);
    /** The To Triage order when a decision was taken, and the decided id: where auto-advance starts. */
    private List<String> advanceOrder;
    private String advanceFrom;

    ReviewWorkbenchInbox(Actions actions, Clock clock) {
        super(new BorderLayout());
        this.actions = Objects.requireNonNull(actions, "actions");
        this.clock = Objects.requireNonNull(clock, "clock");
        for (InboxModel.Tab tab : InboxModel.Tab.values()) {
            tabActions.add(new TabToggle(tab));
        }
        scopeActions.add(new ScopeToggle(InboxModel.Scope.ALL, "All"));
        scopeActions.add(new ScopeToggle(InboxModel.Scope.FILE, "This File"));
        ActionToolbar tabs = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, tabActions, true);
        ActionToolbar scopes = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, scopeActions, true);
        tabs.setTargetComponent(this);
        scopes.setTargetComponent(this);
        // Two rows: the pane is narrow, and one row cut the scope toggles off.
        JPanel top = new JPanel(new BorderLayout());
        top.add(tabs.getComponent(), BorderLayout.NORTH);
        top.add(scopes.getComponent(), BorderLayout.SOUTH);

        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.setBorder(JBUI.Borders.empty(4));
        notice.setBorder(JBUI.Borders.empty(2, 8));
        notice.setForeground(UIUtil.getContextHelpForeground());
        notice.setVisible(false);

        buildComposer();
        JPanel north = new JPanel(new BorderLayout());
        north.add(top, BorderLayout.NORTH);
        north.add(composerPanel, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);
        add(new JBScrollPane(stack), BorderLayout.CENTER);
        add(notice, BorderLayout.SOUTH);
        render(shown);
    }

    private void buildComposer() {
        composerTitle.setBorder(JBUI.Borders.empty(4, 8, 0, 8));
        composerNote.setForeground(UIUtil.getContextHelpForeground());
        composerNote.setBorder(JBUI.Borders.empty(0, 8));
        composerText.setLineWrap(true);
        composerText.setWrapStyleWord(true);
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> closeComposer());
        composerSubmit.addActionListener(e -> submitComposer());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0));
        buttons.add(cancel);
        buttons.add(composerSubmit);
        JPanel labels = new JPanel(new BorderLayout());
        labels.add(composerTitle, BorderLayout.NORTH);
        labels.add(composerNote, BorderLayout.SOUTH);
        JBScrollPane text = new JBScrollPane(composerText);
        text.setBorder(JBUI.Borders.compound(JBUI.Borders.empty(4, 8), text.getBorder()));
        composerPanel.add(labels, BorderLayout.NORTH);
        composerPanel.add(text, BorderLayout.CENTER);
        composerPanel.add(buttons, BorderLayout.SOUTH);
        composerPanel.setVisible(false);
    }

    /** Opens the composer for {@code next}, replacing any open one. EDT. */
    void openComposer(Composer next) {
        requireEdt();
        composer = Objects.requireNonNull(next, "next");
        composerTitle.setText(next.title());
        composerNote.setText(next.note().orElse(""));
        composerNote.setVisible(next.note().isPresent());
        composerSubmit.setText(next.submitText());
        composerSubmit.setEnabled(true);
        composerText.setText("");
        composerText.setEnabled(true);
        composerPanel.setVisible(true);
        revalidate();
        composerText.requestFocusInWindow();
    }

    /** The composer currently open, if any. */
    Optional<Composer> composer() {
        return Optional.ofNullable(composer);
    }

    /** Shows {@code text} in the notice line. EDT. */
    void notice(String text) {
        requireEdt();
        report(Optional.of(text));
    }

    private void submitComposer() {
        Composer current = composer;
        String text = composerText.getText().strip();
        if (current == null || text.isEmpty()) {
            return;
        }
        composerSubmit.setEnabled(false);
        composerText.setEnabled(false);
        actions.submit(current, text).whenComplete((outcome, error) -> EventQueue.invokeLater(() -> {
            if (composer != current) {
                return; // a newer composer replaced this one meanwhile
            }
            if (error == null) {
                closeComposer();
                report(outcome);
            } else {
                composerSubmit.setEnabled(true);
                composerText.setEnabled(true);
                report(Optional.of(ReviewTabProvider.rootMessage(error)));
            }
        }));
    }

    private void closeComposer() {
        composer = null;
        composerPanel.setVisible(false);
        revalidate();
    }

    /** Shows {@code state}'s inbox. */
    void render(WorkbenchState state) {
        render(state.inbox());
    }

    private void render(InboxModel.Snapshot inbox) {
        requireEdt();
        shown = inbox;
        disposeCards();
        stack.removeAll();
        if (inbox.cards().isEmpty()) {
            JBLabel empty = new JBLabel(EMPTY_TEXT, SwingConstants.CENTER);
            empty.setForeground(UIUtil.getContextHelpForeground());
            empty.setAlignmentX(LEFT_ALIGNMENT);
            stack.add(empty);
        }
        for (InboxModel.Card card : inbox.cards()) {
            FindingInlayCard view = new FindingInlayCard(card.finding(), Optional.empty(), clock,
                    new CardCallbacks(card.finding()), FindingInlayCard.Surface.INBOX);
            view.setAlignmentX(LEFT_ALIGNMENT);
            cards.put(card.finding().id(), view);
            stack.add(view);
        }
        stack.revalidate();
        stack.repaint();
        advance(inbox);
    }

    /** After a decision: the card that follows the decided one, scrolled into view and shown in the diff. */
    private void advance(InboxModel.Snapshot inbox) {
        if (advanceFrom == null || inbox.tab() != InboxModel.Tab.TO_TRIAGE) {
            return;
        }
        List<String> now = ids(inbox);
        if (now.contains(advanceFrom)) {
            return; // not applied yet: this render predates the decision's snapshot
        }
        Optional<String> next = InboxModel.nextAfter(advanceOrder, advanceFrom, now);
        advanceFrom = null;
        advanceOrder = null;
        next.ifPresent(id -> {
            FindingInlayCard card = cards.get(id);
            EventQueue.invokeLater(() -> stack.scrollRectToVisible(card.getBounds()));
            inbox.cards().stream().filter(c -> c.finding().id().equals(id)).findFirst()
                    .ifPresent(c -> actions.showInDiff(c.finding()));
        });
    }

    /** Runs one card action: the card is busy until the future completes; its text goes to the notice line. */
    private void run(String findingId, boolean decision, CompletableFuture<Optional<String>> work) {
        if (decision && shown.tab() == InboxModel.Tab.TO_TRIAGE) {
            advanceOrder = ids(shown);
            advanceFrom = findingId;
        }
        work.whenComplete((text, error) -> EventQueue.invokeLater(() -> {
            FindingInlayCard card = cards.get(findingId);
            if (card != null) {
                card.complete();
            }
            report(error != null ? Optional.of(ReviewTabProvider.rootMessage(error)) : text);
        }));
    }

    private void report(Optional<String> text) {
        notice.setText(text.orElse(""));
        notice.setVisible(text.isPresent());
    }

    private static List<String> ids(InboxModel.Snapshot inbox) {
        List<String> ids = new ArrayList<>();
        for (InboxModel.Card card : inbox.cards()) {
            ids.add(card.finding().id());
        }
        return ids;
    }

    private void disposeCards() {
        for (FindingInlayCard card : cards.values()) {
            card.dispose();
        }
        cards.clear();
    }

    @Override
    public void dispose() {
        disposeCards();
    }

    private static void requireEdt() {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("ReviewWorkbenchInbox renders on the EDT");
        }
    }

    /** One card's callbacks, routed to {@link Actions} with the card's finding. */
    private final class CardCallbacks implements FindingInlayCard.Callbacks {
        private final ReviewAnnotation finding;

        CardCallbacks(ReviewAnnotation finding) {
            this.finding = finding;
        }

        @Override public void confirm() {
            throw new IllegalStateException("Confirm is not offered in the inbox");
        }

        @Override public void requestFix() {
            run(finding.id(), true, actions.requestFix(finding));
        }

        @Override public void dismiss() {
            run(finding.id(), true, actions.dismiss(finding));
        }

        @Override public void undo() {
            run(finding.id(), false, actions.undo(finding));
        }

        @Override public void comment(String text) {
            run(finding.id(), false, actions.comment(finding, text));
        }

        @Override public void challenge(String text) {
            run(finding.id(), false, actions.challenge(finding, text));
        }

        @Override public void showInDiff() {
            actions.showInDiff(finding);
        }
    }

    /** One tab: "To Triage (3)", selected while the inbox shows it. */
    private final class TabToggle extends DumbAwareToggleAction {
        private final InboxModel.Tab tab;

        TabToggle(InboxModel.Tab tab) {
            super(tab.label());
            this.tab = tab;
            ReviewWorkbenchEditor.showTextInToolbar(this);
        }

        @Override public boolean isSelected(@NotNull AnActionEvent e) {
            return shown.tab() == tab;
        }

        @Override public void setSelected(@NotNull AnActionEvent e, boolean selected) {
            if (selected) {
                actions.choose(new WorkbenchState.InboxChoice(tab, shown.scope()));
            }
        }

        @Override public void update(@NotNull AnActionEvent e) {
            super.update(e);
            e.getPresentation().setText(tab.label() + " (" + shown.count(tab) + ")");
        }

        @Override public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    /** The scope: the whole review, or the file the diff shows. */
    private final class ScopeToggle extends DumbAwareToggleAction {
        private final InboxModel.Scope scope;

        ScopeToggle(InboxModel.Scope scope, String text) {
            super(text);
            this.scope = scope;
            ReviewWorkbenchEditor.showTextInToolbar(this);
        }

        @Override public boolean isSelected(@NotNull AnActionEvent e) {
            return shown.scope() == scope;
        }

        @Override public void setSelected(@NotNull AnActionEvent e, boolean selected) {
            if (selected) {
                actions.choose(new WorkbenchState.InboxChoice(shown.tab(), scope));
            }
        }

        @Override public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    /**
     * The card stack: as wide as the viewport, so the cards wrap instead of
     * scrolling sideways. A card reports its unwrapped height until it is
     * given a width, so the stack hands its own width to every card before
     * laying them out.
     */
    private static final class TrackingPanel extends JPanel implements Scrollable {
        /** Measured before laid out: the viewport asks for the preferred size first, so the width goes in here too. */
        @Override public Dimension getPreferredSize() {
            wrapCards(getParent() instanceof JViewport viewport ? viewport.getWidth() : getWidth());
            return super.getPreferredSize();
        }

        @Override public void doLayout() {
            wrapCards(getWidth());
            super.doLayout();
        }

        private void wrapCards(int outerWidth) {
            Insets insets = getInsets();
            int width = outerWidth - insets.left - insets.right;
            if (width <= 0) {
                return;
            }
            for (Component child : getComponents()) {
                if (child instanceof FindingInlayCard card) {
                    card.setWrapWidth(width);
                }
            }
        }

        @Override public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return JBUI.scale(16);
        }

        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(visible.height - JBUI.scale(16), JBUI.scale(16));
        }

        @Override public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
