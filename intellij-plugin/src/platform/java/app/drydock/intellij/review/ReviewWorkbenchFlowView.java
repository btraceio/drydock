package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.FlowModel;
import app.drydock.intellij.review.workbench.model.OutboxModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.impl.DiffRequestProcessor;
import com.intellij.diff.impl.DiffRequestProcessorListener;
import com.intellij.diff.impl.DiffSettingsHolder;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.tools.fragmented.UnifiedDiffTool;
import com.intellij.diff.tools.util.base.TextDiffSettingsHolder;
import com.intellij.diff.util.DiffUserDataKeysEx;
import com.intellij.icons.AllIcons;
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.panels.HorizontalLayout;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The flow review's centre (design A): one hunk at a time. A bar names the
 * hunk's file and lines, its place in the queue, and offers Back / Next and
 * Open in Editor. The hunk's own diff fills the rest. It is a diff
 * processor on {@link #FLOW_PLACE}, whose settings fold the unchanged code
 * around the hunk, and its viewers are decorated like a section card's
 * ({@link Actions#decorator}). Below the diff are the comment composer and
 * the footer: Next (J), Comment (C), Flag (F), Back (K). After the last
 * hunk the centre shows the finish screen: its counts, Approve (an
 * approved verdict for every seen hunk not approved yet), and in a pull
 * request review Review &amp; Post…. EDT.
 */
final class ReviewWorkbenchFlowView extends JPanel implements Disposable {

    static final String FLOW_PLACE = "DrydockReviewFlow";
    static final int CONTEXT_RANGE = 4;
    static final String NEXT = "Next Hunk";
    static final String FINISH = "Finish";
    static final String COMMENT = "Comment";
    static final String FLAG = "Flag";
    static final String UNFLAG = "Unflag";
    static final String BACK = "Back";
    static final String MOVING_ON = "Moving on marks this hunk seen";
    static final String OPEN_IN_EDITOR = "Open in Editor";
    static final String FLAGGED = "Flagged";
    static final String LOADING = "Loading the diff…";
    static final String CHANGED_SINCE = "This file changed since the review was read: refresh the review.";
    static final String NOTHING = "Nothing to review.";
    static final String ADD_AND_NEXT = "Add to Outbox & Next";
    static final String CANCEL = "Cancel";
    static final String SAVING = "Saving…";
    static final String FINISH_TITLE = "Finish the review";
    static final String APPROVE = "Approve";
    static final String APPROVING = "Approving…";
    static final String NOTHING_TO_APPROVE = "Every seen hunk is approved.";
    static final String REVIEW_AND_POST = "Review & Post…";
    static final String CHECKING = "Checking GitHub…";
    static final String BACK_TO_DIFF = "Back to Diff";
    static final String GO_TO_FIRST = "Go to first";
    static final String SENDING = "Sending…";
    private static final String DIFF_CARD = "diff";
    private static final String FINISH_CARD = "finish";
    private static final String TOOLBAR_PLACE = "DrydockReviewFlow";

    /** What the centre asks of the live review. */
    interface Actions {
        /**
         * The diff of {@code path} that shows only its hunk {@code hunkIndex} as a change, scrolled to
         * {@code scrollKey}; empty when the file changed since the diff. Any thread.
         */
        CompletableFuture<Optional<ReviewDiffRequests.RequestContext>> hunkContext(String path, int hunkIndex,
                                                                                 String scrollKey);

        /** Decorates the hunk's viewers with its finding cards, marks and gutter "+" until {@code lifetime} ends. EDT. */
        Consumer<FrameDiffTool.DiffViewer> decorator(String path, int hunkIndex, Disposable lifetime);

        /** Stores the reviewer's comment on the hunk's changed lines; completes with a refusal, empty when stored. */
        CompletableFuture<Optional<String>> comment(FlowModel.Hunk hunk, String text);

        /** Opens the pull request's submit dialog; completes with the message to show when none opens. */
        CompletableFuture<Optional<String>> submitReview();

        /** Sends the session lane to the bound session; completes with the message to show. */
        CompletableFuture<Optional<String>> sendToSession();

        /**
         * Records the reviewer's approval of the hunks with {@code digests};
         * completes with the refusal to show, empty when the verdicts are written.
         */
        CompletableFuture<Optional<String>> approve(List<String> digests);
    }

    private final Project project;
    private final ReviewWorkbenchFlow flow;
    private final Actions actions;
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    final SimpleColoredComponent title = new SimpleColoredComponent();
    final JBLabel flaggedChip = new JBLabel(FLAGGED, AllIcons.Nodes.Bookmark, SwingConstants.LEFT);
    final JBLabel position = new JBLabel();
    private final JPanel holder = new JPanel(new BorderLayout());
    private final JBLabel placeholder = new JBLabel(LOADING, SwingConstants.CENTER);
    final JButton next = new JButton(NEXT);
    final JButton comment = new JButton(COMMENT, AllIcons.General.Balloon);
    final JButton flag = new JButton(FLAG, AllIcons.Nodes.Bookmark);
    final JButton back = new JButton(BACK);
    final JBLabel notice = new JBLabel();
    private final JPanel composer = new JPanel(new BorderLayout(0, JBUI.scale(6)));
    private final JBLabel composerLabel = new JBLabel();
    final JBTextArea draft = new JBTextArea(3, 40);
    final JButton addAndNext = new JButton(ADD_AND_NEXT);
    private final JBLabel seenStat = new JBLabel();
    private final JBLabel postStat = new JBLabel();
    private final JBLabel flaggedStat = new JBLabel();
    private final JPanel unseenRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
    private final JBLabel unseenText = new JBLabel();
    final JButton approve = new JButton(APPROVE);
    final JButton reviewAndPost = new JButton(REVIEW_AND_POST);
    final JButton sendToSession = new JButton();
    final JBLabel finishNotice = new JBLabel();
    private final ActionToolbar toolbar;
    /** The hunk the diff panel shows or loads: key and digest, so a changed hunk reloads. */
    private String shownHunk;
    private Disposable lifetime;
    private ReviewDiffRequests.RequestContext loaded;
    private boolean saving;
    private boolean posting;
    private boolean sending;
    private boolean approving;

    ReviewWorkbenchFlowView(Project project, ReviewWorkbenchFlow flow, Actions actions) {
        super(new BorderLayout());
        this.project = Objects.requireNonNull(project, "project");
        this.flow = Objects.requireNonNull(flow, "flow");
        this.actions = Objects.requireNonNull(actions, "actions");
        preferUnifiedOnce(FLOW_PLACE);
        TextDiffSettingsHolder.TextDiffSettings settings = TextDiffSettingsHolder.TextDiffSettings.getSettings(FLOW_PLACE);
        settings.setExpandByDefault(false);
        settings.setContextRange(CONTEXT_RANGE);

        // The bar: dir/ in muted type, the file name bold, the line span muted.
        title.setOpaque(false);
        title.setIpad(JBUI.emptyInsets());
        ReviewTypography.apply(position, ReviewTypography.Role.META);
        ReviewTypography.apply(flaggedChip, ReviewTypography.Role.META);
        DefaultActionGroup navigation = new DefaultActionGroup(
                new Step(false), new Step(true), new OpenInEditor());
        toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, navigation, true);
        toolbar.setTargetComponent(this);
        toolbar.getComponent().setOpaque(false);
        JPanel west = new JPanel(new HorizontalLayout(JBUI.scale(10)));
        west.setOpaque(false);
        west.add(title);
        west.add(flaggedChip);
        JPanel east = new JPanel(new HorizontalLayout(JBUI.scale(4)));
        east.setOpaque(false);
        east.add(position);
        east.add(toolbar.getComponent());
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()),
                JBUI.Borders.empty(4, 16, 4, 8)));
        bar.add(west, BorderLayout.WEST);
        bar.add(east, BorderLayout.EAST);

        ReviewTypography.apply(placeholder, ReviewTypography.Role.META);
        holder.add(placeholder, BorderLayout.CENTER);

        composerLabel.setForeground(UIUtil.getContextHelpForeground());
        draft.setLineWrap(true);
        draft.setWrapStyleWord(true);
        draft.getEmptyText().setText("What should change?");
        JButton cancel = new JButton(CANCEL);
        cancel.addActionListener(e -> closeComposer());
        addAndNext.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true);
        addAndNext.addActionListener(e -> submitComment());
        JPanel composerButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0));
        composerButtons.setOpaque(false);
        composerButtons.add(cancel);
        composerButtons.add(addAndNext);
        composer.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()),
                JBUI.Borders.empty(10, 16)));
        composer.add(composerLabel, BorderLayout.NORTH);
        composer.add(new JBScrollPane(draft), BorderLayout.CENTER);
        composer.add(composerButtons, BorderLayout.SOUTH);
        composer.setVisible(false);

        next.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true);
        next.setToolTipText("Mark this hunk seen and show the next one (J)");
        comment.setToolTipText("Comment on this hunk's changed lines (C)");
        flag.setToolTipText("Flag this hunk to come back to (F)");
        back.setToolTipText("Show the previous hunk (K)");
        next.addActionListener(e -> flow.next());
        comment.addActionListener(e -> openComposer());
        flag.addActionListener(e -> flow.toggleFlag());
        back.addActionListener(e -> flow.back());
        // The hint sits between the buttons and takes only the space left over:
        // in a narrow centre it shortens to an ellipsis instead of covering a button.
        JBLabel hint = new JBLabel(MOVING_ON, SwingConstants.RIGHT);
        ReviewTypography.apply(hint, ReviewTypography.Role.META);
        hint.setMinimumSize(new Dimension(0, 0));
        ReviewTypography.apply(notice, ReviewTypography.Role.META);
        JPanel left = new JPanel(new HorizontalLayout(JBUI.scale(8)));
        left.setOpaque(false);
        left.add(next);
        left.add(comment);
        left.add(flag);
        left.add(notice);
        JPanel footer = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        footer.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()),
                JBUI.Borders.empty(8, 16)));
        footer.add(left, BorderLayout.WEST);
        footer.add(hint, BorderLayout.CENTER);
        footer.add(back, BorderLayout.EAST);
        JPanel south = new JPanel(new BorderLayout());
        south.add(composer, BorderLayout.NORTH);
        south.add(footer, BorderLayout.SOUTH);

        JPanel diff = new JPanel(new BorderLayout());
        diff.add(bar, BorderLayout.NORTH);
        diff.add(holder, BorderLayout.CENTER);
        diff.add(south, BorderLayout.SOUTH);
        body.add(diff, DIFF_CARD);
        body.add(buildFinish(), FINISH_CARD);
        add(body, BorderLayout.CENTER);

        flow.addListener(this::render);
        render();
    }

    private JComponent buildFinish() {
        JBLabel heading = new JBLabel(FINISH_TITLE);
        ReviewTypography.apply(heading, ReviewTypography.Role.PAGE_TITLE);
        JPanel stats = new JPanel(new GridLayout(1, 3, JBUI.scale(8), 0));
        stats.setOpaque(false);
        stats.add(stat(seenStat, "hunks seen"));
        stats.add(stat(postStat, "comments to post"));
        stats.add(stat(flaggedStat, "flagged"));
        ReviewTypography.apply(unseenText, ReviewTypography.Role.META);
        unseenRow.setOpaque(false);
        unseenRow.add(unseenText);
        unseenRow.add(new ActionLink(GO_TO_FIRST, (ActionListener) e -> flow.backToDiff()));
        approve.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true);
        approve.addActionListener(e -> approveSeen());
        reviewAndPost.addActionListener(e -> post());
        sendToSession.addActionListener(e -> send());
        JButton backToDiff = new JButton(BACK_TO_DIFF);
        backToDiff.addActionListener(e -> flow.back());
        JPanel buttons = new JPanel(new HorizontalLayout(JBUI.scale(8)));
        buttons.setOpaque(false);
        buttons.add(approve);
        buttons.add(reviewAndPost);
        buttons.add(sendToSession);
        buttons.add(backToDiff);
        ReviewTypography.apply(finishNotice, ReviewTypography.Role.META);

        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        for (JComponent part : new JComponent[] {heading, stats, unseenRow, buttons, finishNotice}) {
            part.setAlignmentX(Component.LEFT_ALIGNMENT);
            column.add(part);
            column.add(Box.createVerticalStrut(JBUI.scale(16)));
        }
        JPanel centred = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, JBUI.scale(48)));
        centred.add(column);
        return new JBScrollPane(centred);
    }

    /** A finish-screen tile: a large number over its caption. */
    private static JComponent stat(JBLabel number, String caption) {
        ReviewTypography.apply(number, ReviewTypography.Role.PAGE_TITLE);
        JBLabel label = new JBLabel(caption);
        ReviewTypography.apply(label, ReviewTypography.Role.META);
        JPanel tile = new JPanel(new BorderLayout(0, JBUI.scale(2)));
        tile.setOpaque(false);
        tile.setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(12)));
        tile.add(number, BorderLayout.NORTH);
        tile.add(label, BorderLayout.SOUTH);
        return tile;
    }

    /**
     * Binds J, K, C and F under {@code root} until {@code parent} ends. A
     * key is left to the component while a text field or area has the focus,
     * so typing a comment never moves through the review.
     */
    void installShortcuts(JComponent root, Disposable parent) {
        bind(KeyEvent.VK_J, flow::next, root, parent);
        bind(KeyEvent.VK_K, flow::back, root, parent);
        bind(KeyEvent.VK_C, this::openComposer, root, parent);
        bind(KeyEvent.VK_F, flow::toggleFlag, root, parent);
    }

    private static void bind(int key, Runnable run, JComponent root, Disposable parent) {
        DumbAwareAction action = new DumbAwareAction() {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                run.run();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                e.getPresentation().setEnabled(!(focused instanceof JTextComponent text && text.isEditable()));
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
        action.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(key, 0)), root, parent);
    }

    /** Shows the flow's current hunk or its finish screen. */
    void render() {
        requireEdt();
        if (flow.finishing()) {
            renderFinish();
            cards.show(body, FINISH_CARD);
            return;
        }
        cards.show(body, DIFF_CARD);
        FlowModel.Queue queue = flow.queue();
        Optional<FlowModel.Hunk> hunk = flow.current();
        title.clear();
        if (hunk.isEmpty()) {
            title.append(NOTHING, ReviewTypography.MUTED);
            position.setText("");
            flaggedChip.setVisible(false);
            setFooterEnabled(false);
            showPanel(null);
            return;
        }
        FlowModel.Hunk current = hunk.get();
        title.append(current.directory(), ReviewTypography.MUTED);
        title.append(current.fileName(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
        if (!current.lines().isEmpty()) {
            title.append("  " + current.lines(), ReviewTypography.MUTED);
        }
        title.setToolTipText(current.file());
        flaggedChip.setVisible(flow.currentFlagged());
        int at = queue.indexOf(current.key());
        position.setText("Hunk " + (at + 1) + " of " + queue.order().size());
        setFooterEnabled(true);
        next.setText(at + 1 == queue.order().size() ? FINISH : NEXT);
        back.setEnabled(at > 0);
        flag.setText(flow.currentFlagged() ? UNFLAG : FLAG);
        composerLabel.setText("Comment on " + current.fileName() + " · " + current.lines());
        showPanel(current);
        toolbar.updateActionsAsync();
    }

    private void setFooterEnabled(boolean enabled) {
        next.setEnabled(enabled);
        comment.setEnabled(enabled);
        flag.setEnabled(enabled);
        back.setEnabled(enabled);
    }

    private void renderFinish() {
        FlowModel.Queue queue = flow.queue();
        WorkbenchState state = flow.state();
        OutboxModel.Snapshot outbox = state == null ? OutboxModel.Snapshot.EMPTY : state.outbox();
        int seen = queue.seenCount(flow.seen());
        int total = queue.order().size();
        seenStat.setText(seen + "/" + total);
        postStat.setText(Integer.toString(outbox.waitingForPr()));
        flaggedStat.setText(Integer.toString(queue.flaggedCount(flow.flagged())));
        int unseen = total - seen;
        unseenText.setText(unseen + (unseen == 1 ? " hunk" : " hunks") + " not seen yet.");
        unseenRow.setVisible(unseen > 0);
        int toApprove = queue.toApprove(flow.seen()).size();
        approve.setText(approving ? APPROVING : approveLabel(toApprove));
        approve.setEnabled(!approving && toApprove > 0);
        approve.setToolTipText(toApprove == 0 ? NOTHING_TO_APPROVE
                : "Record your approval of the hunks you have seen; posts nothing to GitHub");
        reviewAndPost.setVisible(outbox.prMode());
        reviewAndPost.setText(posting ? CHECKING : REVIEW_AND_POST);
        reviewAndPost.setEnabled(!posting);
        int toSend = outbox.toFix().size() + outbox.dismissed();
        sendToSession.setText(sending ? SENDING : "Send " + toSend + " to Session");
        sendToSession.setVisible(outbox.sessionBound());
        sendToSession.setEnabled(!sending && outbox.pending());
    }

    /** Builds the diff panel for {@code hunk}, unless it already shows or loads it; null shows nothing. */
    private void showPanel(FlowModel.Hunk hunk) {
        String identity = hunk == null ? null : hunk.key() + "|" + hunk.digest();
        if (Objects.equals(identity, shownHunk)) {
            return;
        }
        endPanel();
        shownHunk = identity;
        if (hunk == null) {
            placeholder.setText("");
            return;
        }
        placeholder.setText(LOADING);
        actions.hunkContext(hunk.file(), hunk.index(), hunk.scrollKey())
                .whenComplete((context, error) -> onEdt(() -> create(identity, hunk, context, error)));
    }

    private void create(String identity, FlowModel.Hunk hunk, Optional<ReviewDiffRequests.RequestContext> context,
                        Throwable error) {
        if (!Objects.equals(identity, shownHunk) || lifetime != null) {
            return; // moved on while loading
        }
        if (error != null || context == null || context.isEmpty()) {
            placeholder.setText(error != null ? "The diff could not be read: " + ReviewTabProvider.rootMessage(error)
                    : CHANGED_SINCE);
            return;
        }
        loaded = context.get();
        lifetime = Disposer.newDisposable(this, "review flow hunk " + hunk.key());
        SimpleDiffRequest request = ReviewDiffRequests.toRequest(project, lifetime, loaded);
        // The bar names the file; the editors' revision titles would repeat it.
        request.putUserData(DiffUserDataKeysEx.EDITORS_HIDE_TITLE, Boolean.TRUE);
        FlowProcessor processor = new FlowProcessor(project, request);
        Disposer.register(lifetime, processor);
        // Registered after the processor, so it is disposed first: the decorations leave
        // while the editors they sit in still exist.
        Consumer<FrameDiffTool.DiffViewer> decorate = actions.decorator(hunk.file(), hunk.index(), lifetime);
        processor.addListener((DiffRequestProcessorListener) () -> decorate.accept(processor.getActiveViewer()),
                lifetime);
        processor.updateRequest();
        holder.removeAll();
        holder.add(processor.getComponent(), BorderLayout.CENTER);
        holder.revalidate();
        holder.repaint();
    }

    private void endPanel() {
        loaded = null;
        if (lifetime == null) {
            return;
        }
        Disposable ending = lifetime;
        lifetime = null;
        holder.removeAll();
        holder.add(placeholder, BorderLayout.CENTER);
        holder.revalidate();
        holder.repaint();
        Disposer.dispose(ending);
    }

    void openComposer() {
        requireEdt();
        if (flow.finishing() || flow.current().isEmpty()) {
            return;
        }
        composer.setVisible(true);
        revalidate();
        draft.requestFocusInWindow();
    }

    private void closeComposer() {
        draft.setText("");
        composer.setVisible(false);
        revalidate();
    }

    private void submitComment() {
        Optional<FlowModel.Hunk> hunk = flow.current();
        String text = draft.getText().strip();
        if (saving || hunk.isEmpty() || text.isEmpty()) {
            return;
        }
        saving = true;
        addAndNext.setText(SAVING);
        addAndNext.setEnabled(false);
        notice.setText("");
        actions.comment(hunk.get(), text).whenComplete((refusal, error) -> onEdt(() -> {
            saving = false;
            addAndNext.setText(ADD_AND_NEXT);
            addAndNext.setEnabled(true);
            if (error != null) {
                notice.setText("The comment was not saved: " + ReviewTabProvider.rootMessage(error));
            } else if (refusal.isPresent()) {
                notice.setText(refusal.get());
            } else {
                closeComposer();
                flow.next();
            }
        }));
    }

    /** Approve's label (pure): "Approve 3 Seen Hunks", "Approve 1 Seen Hunk", or "Approve" with none to approve. */
    static String approveLabel(int count) {
        return count == 0 ? APPROVE : APPROVE + " " + count + (count == 1 ? " Seen Hunk" : " Seen Hunks");
    }

    /**
     * Records the approval of every seen hunk no approved verdict covers yet.
     * The written verdicts reach the queue with the next state, which counts
     * them as reviewed; a refusal (a blocking finding on one of the hunks,
     * code that changed since) shows in the notice line.
     */
    private void approveSeen() {
        List<String> digests = flow.queue().toApprove(flow.seen());
        if (approving || digests.isEmpty()) {
            return;
        }
        approving = true;
        finishNotice.setText("");
        renderFinish();
        actions.approve(digests).whenComplete((refusal, error) -> onEdt(() -> {
            approving = false;
            finishNotice.setText(error != null ? "Approving failed: " + ReviewTabProvider.rootMessage(error)
                    : refusal == null || refusal.isEmpty()
                            ? "Approved " + digests.size() + (digests.size() == 1 ? " hunk." : " hunks.")
                            : refusal.get());
            renderFinish();
        }));
    }

    private void post() {
        if (posting) {
            return;
        }
        posting = true;
        finishNotice.setText("");
        renderFinish();
        actions.submitReview().whenComplete((message, error) -> onEdt(() -> {
            posting = false;
            finishNotice.setText(error != null ? "Could not open the review: " + ReviewTabProvider.rootMessage(error)
                    : message == null ? "" : message.orElse(""));
            renderFinish();
        }));
    }

    private void send() {
        if (sending) {
            return;
        }
        sending = true;
        finishNotice.setText("");
        renderFinish();
        actions.sendToSession().whenComplete((message, error) -> onEdt(() -> {
            sending = false;
            finishNotice.setText(error != null ? "Sending failed: " + ReviewTabProvider.rootMessage(error)
                    : message == null ? "" : message.orElse(""));
            renderFinish();
        }));
    }

    /** Opens the shown hunk's file at its first new line, in the project's own editor. */
    private void openInEditor() {
        ReviewDiffRequests.RequestContext context = loaded;
        Optional<FlowModel.Hunk> hunk = flow.current();
        if (context == null || context.worktreeFile().isEmpty() || hunk.isEmpty()) {
            return;
        }
        Path path = context.worktreeFile().get();
        VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(path);
        if (file != null) {
            new OpenFileDescriptor(project, file, Math.max(0, firstNewLine(hunk.get()) - 1), 0).navigate(true);
        }
    }

    /** The hunk's first line in the new file (pure): its scroll key's, else the lowest new row's, else 1. */
    static int firstNewLine(FlowModel.Hunk hunk) {
        if (hunk.scrollKey().startsWith("n")) {
            return Integer.parseInt(hunk.scrollKey().substring(1));
        }
        return hunk.rowKeys().stream().filter(key -> key.startsWith("n"))
                .mapToInt(key -> Integer.parseInt(key.substring(1))).min().orElse(1);
    }

    /** Back or Next on the bar. */
    private final class Step extends DumbAwareAction {
        private final boolean forward;

        Step(boolean forward) {
            super(forward ? "Next Hunk" : "Previous Hunk", null,
                    forward ? AllIcons.Actions.NextOccurence : AllIcons.Actions.PreviousOccurence);
            this.forward = forward;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (forward) {
                flow.next();
            } else {
                flow.back();
            }
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            String key = flow.current().map(FlowModel.Hunk::key).orElse(null);
            e.getPresentation().setEnabled(key != null && !flow.finishing()
                    && (forward || flow.queue().indexOf(key) > 0));
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    private final class OpenInEditor extends DumbAwareAction {
        OpenInEditor() {
            super(OPEN_IN_EDITOR, "Open this hunk in the project's editor", AllIcons.Actions.EditSource);
            ReviewWorkbenchEditor.showTextInToolbar(this);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            openInEditor();
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            ReviewDiffRequests.RequestContext context = loaded;
            e.getPresentation().setEnabled(context != null && context.worktreeFile().isPresent());
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    @Override
    public void dispose() {
        endPanel();
        shownHunk = null;
    }

    /**
     * Makes the unified viewer {@code place}'s first choice, once per place:
     * side by side, each half of the centre is too narrow for a finding
     * card, which the platform clamps to the editor's viewport. Once only,
     * so the viewer's own Side-by-side toggle, which stores the same order,
     * keeps the reviewer's choice afterwards. EDT.
     */
    static void preferUnifiedOnce(String place) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        String applied = "drydock.review.unifiedDefault." + place;
        if (properties.getBoolean(applied)) {
            return;
        }
        DiffSettingsHolder.DiffSettings settings = DiffSettingsHolder.DiffSettings.getSettings(place);
        settings.setDiffToolsOrder(unifiedFirst(settings.getDiffToolsOrder()));
        properties.setValue(applied, true);
    }

    /** {@code order} with the unified viewer first and nothing else moved (pure). */
    static List<String> unifiedFirst(List<String> order) {
        String unified = UnifiedDiffTool.class.getCanonicalName();
        List<String> result = new ArrayList<>();
        result.add(unified);
        order.stream().filter(tool -> !tool.equals(unified)).forEach(result::add);
        return List.copyOf(result);
    }

    /** The platform's EDT hop: a diff panel builds editors, which needs the platform's write-intent lock. */
    private static void onEdt(Runnable action) {
        ApplicationManager.getApplication().invokeLater(action);
    }

    private static void requireEdt() {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("ReviewWorkbenchFlowView renders on the EDT");
        }
    }

    /** The hunk's diff: a processor on the flow place with a fixed request, so its viewer can be decorated. */
    private static final class FlowProcessor extends DiffRequestProcessor {
        private final DiffRequest request;

        FlowProcessor(Project project, DiffRequest request) {
            super(project, FLOW_PLACE);
            this.request = Objects.requireNonNull(request, "request");
        }

        @Override
        public void updateRequest(boolean scrollToChange, DiffUserDataKeysEx.ScrollToPolicy policy) {
            applyRequest(request, scrollToChange, policy);
        }
    }
}
