package app.drydock.intellij.review;

import app.drydock.review.AnnotationStatus;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Triage;

import com.intellij.collaboration.ui.codereview.CodeReviewChatItemUIUtil;
import com.intellij.collaboration.ui.codereview.CodeReviewChatItemUIUtil.ComponentType;
import com.intellij.collaboration.ui.util.CodeReviewColorUtil;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.text.HtmlBuilder;
import com.intellij.openapi.util.text.HtmlChunk;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.JBColor;
import com.intellij.ui.RoundedLineBorder;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.panels.BackgroundRoundedPanel;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.SingleComponentCenteringLayout;
import com.intellij.util.ui.UIUtil;
import kotlin.Unit;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Swing content of one finding's diff inlay (spec "Review surface and
 * diff inlays", AC1), drawn the way the IDE draws its own code-review
 * threads: a rounded bubble on the editor background, one chat item per
 * message — the severity icon where a comment shows its avatar, the author
 * and relative age as the header, state tags at the header's right — then
 * the comment box and the actions the finding's state allows. PROPOSED
 * shows Agree/Won't fix, CONFIRMED and DISMISSED keep the card, carry their
 * state as a tag and expose Undo, and a SENT finding says so instead of
 * pretending a reply.
 *
 * <p>Every piece is the platform's: {@link CodeReviewChatItemUIUtil} lays
 * out each item exactly as the bundled GitHub plugin lays out an inline
 * review comment, the text sits in the IDE's HTML editor kit (selectable,
 * word-wrapping, label font), the tags reuse the review state colours,
 * the actions are {@link ActionLink}s like a thread's Resolve, and the bubble
 * border is the review bubble's own named colour. Nothing here is a bare
 * Swing control with default fonts and borders.</p>
 *
 * <p><strong>Geometry is the owner's business.</strong> The card fills the
 * width its inlay container assigns (the editor's text width, minus the
 * indent border the host adds): every text block is a non-editable, wrapped,
 * selectable HTML pane whose preferred height is computed at the width it
 * will be laid out at, so nothing truncates and every word is selectable
 * for copy-paste. The width arrives through {@link #setWrapWidth(int)} (the
 * container, on attach and on every editor resize; the card's own
 * {@link #doLayout} re-applies it when the assigned width changes) — until
 * it is set the card reports an unwrapped preferred size, which is why the
 * container must apply the width before the inlay is measured.</p>
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

        void comment(String text);
    }

    private static final String PROGRESS_TEXT = "Working…";
    private static final String COMMENT_HINT = "Add a comment… Enter to send";
    /** The review bubble's corner radius — the platform's inlay panel uses the same. */
    private static final int BUBBLE_ARC = 10;
    /** The state tags' corner radius, a pill at the small font's height. */
    private static final int TAG_ARC = 8;
    /**
     * The review bubble's border: the platform's own named colour, with the
     * same fallbacks its (internal) inlay panel declares, read through the
     * public colour API.
     */
    private static final Color BUBBLE_BORDER = JBColor.namedColor("Review.ChatItem.BubblePanel.Border",
            JBColor.namedColor("EditorTabs.underTabsBorderColor", JBColor.border()));
    /** The compact item: the shape the GitHub plugin uses for inline diff comments. */
    private static final ComponentType ITEM = ComponentType.COMPACT;
    /** The reviewing agent's avatar — the plugin's own mark, like the tool window's. */
    private static final Icon AGENT_ICON = IconLoader.getIcon("/icons/drydock.svg", FindingInlayCard.class);
    /** The author name every human comment carries (the store's convention). */
    private static final String HUMAN_AUTHOR = "You";

    private final Clock clock;
    private final Callbacks callbacks;
    /** Every wrapped text pane currently shown, re-wrapped together when the width changes. */
    private final List<TextBlock> blocks = new ArrayList<>();
    private final JPanel items = new JPanel();
    private final JBTextField comment = new JBTextField();
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, JBUI.scale(12), 0));
    private final JBLabel progress = new JBLabel(PROGRESS_TEXT, AnimatedIcon.Default.INSTANCE, SwingConstants.LEADING);
    /**
     * The triage actions' labels: "Agree"/"Won't fix" by default — the honest
     * reading for a review with nothing to post to — and the owner switches
     * to "Confirm"/"Dismiss" when the scope carries a pull request, where
     * confirming vouches the finding into the posted review.
     */
    private String confirmLabel = "Agree";
    private String dismissLabel = "Won't fix";
    private Optional<String> driftHint;
    private ReviewAnnotation finding;
    private boolean busy;
    private boolean disposed;
    /** The content width the card wraps at; 0 until the container assigns one. */
    private int wrapWidth;

    // What the rendering said, kept as plain text for the tests' accessors.
    private String titleText = "";
    private String metaText = "";
    private final List<String> threadLines = new ArrayList<>();
    private final List<String> stateTags = new ArrayList<>();
    private TextBlock driftBlock;

    /** {@code driftHint} is present exactly when the placement fell back to the first hunk (T3). */
    public FindingInlayCard(ReviewAnnotation finding, Optional<String> driftHint,
                            Clock clock, Callbacks callbacks) {
        this.finding = Objects.requireNonNull(finding, "finding");
        this.driftHint = Objects.requireNonNull(driftHint, "driftHint");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        checkEdt();
        setLayout(new BorderLayout());
        setOpaque(false);

        items.setLayout(new BoxLayout(items, BoxLayout.Y_AXIS));
        items.setOpaque(false);

        comment.getEmptyText().setText(COMMENT_HINT);
        comment.addActionListener(e -> submitComment());
        actions.setOpaque(false);
        progress.setForeground(UIUtil.getContextHelpForeground());
        progress.setVisible(false);

        // The footer sits in the items' content column: indented past the
        // icon exactly as each item's text is, so the comment box and the
        // actions line up with the words above them.
        JPanel footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setOpaque(false);
        Insets padding = ITEM.getPaddingInsets();
        footer.setBorder(JBUI.Borders.empty(2, ITEM.getContentLeftShift(), padding.bottom, padding.right));
        comment.setAlignmentX(LEFT_ALIGNMENT);
        actions.setAlignmentX(LEFT_ALIGNMENT);
        footer.add(comment);
        footer.add(actions);

        JPanel bubble = new JPanel();
        bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
        bubble.setOpaque(false);
        bubble.setBorder(BorderFactory.createCompoundBorder(
                new RoundedLineBorder(BUBBLE_BORDER, JBUI.scale(BUBBLE_ARC), 1),
                JBUI.Borders.empty(2, 0)));
        items.setAlignmentX(LEFT_ALIGNMENT);
        footer.setAlignmentX(LEFT_ALIGNMENT);
        bubble.add(items);
        bubble.add(footer);
        add(bubble, BorderLayout.CENTER);
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
     * The triage actions' labels for this scope's shape: a pull-request
     * review confirms findings into the posted review ("Confirm"); a local
     * review only agrees the finding is valid ("Agree") — the same store
     * state either way, named for what the click means where there is no
     * submission target. The state tag follows the verb ("Confirmed",
     * "Agreed"). Re-rendering keeps the labels.
     */
    public void setTriageLabels(String confirmLabel, String dismissLabel) {
        checkEdt();
        this.confirmLabel = Objects.requireNonNull(confirmLabel, "confirmLabel");
        this.dismissLabel = Objects.requireNonNull(dismissLabel, "dismissLabel");
        render();
    }

    /**
     * The content width the card wraps at — the inlay container calls this
     * when it attaches the card and whenever the editor's text width
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
        Insets insets = getInsets();
        int assigned = getWidth() - insets.left - insets.right;
        if (assigned > 0 && assigned != wrapWidth) {
            wrapWidth = assigned;
            applyWrapWidth();
        }
        super.doLayout();
    }

    /**
     * The preferred size at the assigned width: the blocks already carry
     * their wrapped heights ({@link #applyWrapWidth} sizes each at the
     * width it will get before its height is read), so the layout's own
     * answer is the height the container and the inlay must reserve; the
     * width is pinned to the assigned one so a long unwrapped line never
     * widens the inlay past the editor.
     */
    @Override
    public Dimension getPreferredSize() {
        Dimension natural = super.getPreferredSize();
        if (wrapWidth <= 0) {
            return natural;
        }
        Insets insets = getInsets();
        return new Dimension(wrapWidth + insets.left + insets.right, natural.height);
    }

    /** Re-sizes every text block at the width its content column gets, so its preferred height is the wrapped height. */
    private void applyWrapWidth() {
        int width = blockWidth();
        for (TextBlock block : blocks) {
            block.wrapAt(width);
        }
    }

    /**
     * The width a text block gets inside its chat item: the card's content
     * width minus the bubble's border, the item's icon column and padding —
     * capped at the platform's review text width, which keeps a finding
     * readable instead of stretching one line across a wide editor.
     */
    private int blockWidth() {
        if (wrapWidth <= 0) {
            return 0;
        }
        Insets padding = ITEM.getPaddingInsets();
        int column = wrapWidth - 2 - JBUI.scale(ITEM.getContentLeftShift() + padding.right);
        return Math.min(column, CodeReviewChatItemUIUtil.INSTANCE.getTEXT_CONTENT_WIDTH());
    }

    // Package-private views for the test: what the rendering said, without a component walk.

    boolean progressVisible() {
        return progress.isVisible();
    }

    boolean stateVisible() {
        return !stateTags.isEmpty();
    }

    /** The state tags as shown, left to right, joined with " · ". */
    String stateText() {
        return String.join(" · ", stateTags);
    }

    boolean driftShowing() {
        return driftBlock != null;
    }

    String driftText() {
        return driftHint.orElse("");
    }

    /** The finding's title as rendered (its display title). */
    String headerText() {
        return titleText;
    }

    /** The first item's header: author and relative age. */
    String metaText() {
        return metaText;
    }

    /** One entry per thread message, embedded newlines intact — the accessor's contract. */
    List<String> threadLines() {
        return List.copyOf(threadLines);
    }

    /** The text blocks' wrapped preferred heights, for the wrapping test. */
    List<Integer> blockHeights() {
        return blocks.stream().map(block -> block.pane.getPreferredSize().height).toList();
    }

    // ---- rendering ------------------------------------------------------------

    private void render() {
        blocks.clear();
        driftBlock = null;
        items.removeAll();
        renderTags();
        items.add(findingItem());
        List<ReviewAnnotation.Message> thread = finding.thread();
        for (int i = 1; i < thread.size(); i++) {
            items.add(replyItem(thread.get(i)));
        }
        renderThreadLines();
        renderActions();
        applyWrapWidth();
        items.revalidate();
        items.repaint();
    }

    /**
     * The opening item: the severity icon in the avatar slot, the reviewer
     * and age as the header with the state tags at its right, then the
     * title in bold, the finding's own words, and the drift hint when the
     * anchor fell out of the current diff.
     */
    private JComponent findingItem() {
        titleText = finding.displayTitle();
        metaText = finding.author() + " · " + age(finding.at());

        JPanel body = verticalBox();
        List<ReviewAnnotation.Message> thread = finding.thread();
        String opening = thread.isEmpty() ? "" : thread.get(0).text();
        if (finding.title().filter(text -> !text.isBlank()).isPresent()) {
            body.add(block(HtmlChunk.text(titleText).bold().toString()));
            if (!opening.isBlank()) {
                body.add(block(paragraphs(opening)));
            }
        } else if (!opening.isBlank()) {
            body.add(block(paragraphs(opening)));
        } else {
            body.add(block(HtmlChunk.text(titleText).bold().toString()));
        }
        driftHint.ifPresent(hint -> {
            driftBlock = block(HtmlChunk.text(hint).italic().toString());
            driftBlock.pane.setForeground(UIUtil.getContextHelpForeground());
            body.add(driftBlock);
        });

        // The tags ride in the header row itself: the chat item's own
        // header-actions slot is hover-only (edit/delete in a review thread),
        // and a finding's state must be readable without hovering.
        JComponent header = header(finding.author(), finding.at());
        for (String tag : stateTags) {
            header.add(tag(tag));
        }
        return item(SeverityPresentation.icon(finding.effectiveSeverity()),
                SeverityPresentation.label(finding.effectiveSeverity()), header, body);
    }

    /** A reply: the author's avatar (the human's, or the agent's), author and age, the words. */
    private JComponent replyItem(ReviewAnnotation.Message message) {
        Icon avatar = HUMAN_AUTHOR.equals(message.author()) ? AllIcons.General.User : AGENT_ICON;
        JPanel body = verticalBox();
        body.add(block(paragraphs(message.text())));
        return item(avatar, message.author(), header(message.author(), message.at()), body);
    }

    /** One chat item in the platform's compact review shape: icon column, header row, content below. */
    private JComponent item(Icon icon, String iconTooltip, JComponent header, JComponent content) {
        JComponent built = CodeReviewChatItemUIUtil.INSTANCE.build(ITEM, size -> icon, content, builder -> {
            builder.setIconTooltip(iconTooltip);
            builder.withHeader(header, null);
            return Unit.INSTANCE;
        });
        built.setAlignmentX(LEFT_ALIGNMENT);
        return built;
    }

    /**
     * The item header the way a review comment names itself: the author,
     * then the age in the context-help grey — a row the caller may extend
     * with state tags.
     */
    private JComponent header(String author, Instant at) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEADING, JBUI.scale(6), 0));
        row.setOpaque(false);
        JBLabel name = new JBLabel(author);
        name.setFont(JBFont.label());
        JBLabel age = new JBLabel(age(at));
        age.setFont(JBFont.label());
        age.setForeground(UIUtil.getContextHelpForeground());
        row.add(name);
        row.add(age);
        return row;
    }

    /** The state tags: what the human decided, and whether the finding already went to the agent. */
    private void renderTags() {
        stateTags.clear();
        if (finding.triage() == Triage.CONFIRMED) {
            stateTags.add(pastTense(confirmLabel));
        } else if (finding.triage() == Triage.DISMISSED) {
            stateTags.add(dismissLabel);
        }
        if (finding.status() == AnnotationStatus.SENT) {
            stateTags.add("Sent");
        }
    }

    /** "Agree" → "Agreed", "Confirm" → "Confirmed": the tag names the decision taken, not the verb. */
    private static String pastTense(String verb) {
        return verb.endsWith("e") ? verb + "d" : verb + "ed";
    }

    /** A review state tag: the small-font pill the IDE's review views use for "Pending", "Outdated" and the like. */
    private static JComponent tag(String text) {
        JBLabel label = new JBLabel(text);
        label.setFont(JBFont.small());
        label.setForeground(CodeReviewColorUtil.Review.INSTANCE.getStateForeground());
        label.setBorder(JBUI.Borders.empty(1, 6));
        BackgroundRoundedPanel pill = new BackgroundRoundedPanel(TAG_ARC, new SingleComponentCenteringLayout());
        pill.setBackground(CodeReviewColorUtil.Review.INSTANCE.getStateBackground());
        pill.add(label);
        return pill;
    }

    private void renderThreadLines() {
        threadLines.clear();
        for (ReviewAnnotation.Message message : finding.thread()) {
            threadLines.add(message.author() + " · " + age(message.at()) + ": " + message.text());
        }
    }

    private void renderActions() {
        actions.removeAll();
        if (finding.triage() == Triage.PROPOSED) {
            ActionLink confirm = link(confirmLabel, callbacks::confirm);
            confirm.setToolTipText("The finding is valid: it is included when this review is submitted,"
                    + " and in the next decisions hand-off to the agent.");
            ActionLink dismiss = link(dismissLabel, callbacks::dismiss);
            dismiss.setToolTipText("The finding is dismissed: reported to the agent as a decision"
                    + " with the next hand-off, and left out of the review submission.");
            actions.add(confirm);
            actions.add(dismiss);
        } else {
            ActionLink undo = link("Undo", callbacks::undo);
            undo.setToolTipText("Back to proposed.");
            actions.add(undo);
        }
        actions.add(progress);
        setControlsEnabled(!busy && !disposed);
        actions.revalidate();
    }

    private ActionLink link(String text, Runnable onClick) {
        // The Swing listener overload, named: the Kotlin-function overload makes a bare lambda ambiguous.
        ActionListener listener = e -> run(onClick);
        return new ActionLink(text, listener);
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
            if (child != progress) {
                child.setEnabled(enabled);
            }
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

    // ---- text ------------------------------------------------------------------

    /** Plain text as HTML: entities escaped, line breaks kept. */
    private static String paragraphs(String text) {
        HtmlBuilder html = new HtmlBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                html.append(HtmlChunk.br());
            }
            html.append(HtmlChunk.text(lines[i]));
        }
        return html.toString();
    }

    private TextBlock block(String html) {
        TextBlock block = new TextBlock(html);
        blocks.add(block);
        return block;
    }

    private static JPanel verticalBox() {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setOpaque(false);
        return box;
    }

    /**
     * One wrapped, selectable, non-editable text block: a {@link JEditorPane}
     * on the IDE's HTML editor kit ({@link HTMLEditorKitBuilder}: the IDE's
     * stylesheet, word-wrapping views, label font), transparent, selectable
     * but not editable, so the reviewer can select and copy the finding's
     * words, never change them — the same recipe the collaboration tools'
     * own HTML pane follows, built here because that pane needs a running
     * application and the card is also constructed headless in tests. The
     * preferred height is the height of the text wrapped at the assigned
     * width ({@link #wrapAt}): sized at that width, measured, then pinned.
     */
    private static final class TextBlock extends JPanel {

        private final JEditorPane pane;
        /** The width the pane was last measured at; the layout re-measures when reality differs. */
        private int measuredWidth;

        private TextBlock(String html) {
            super(new BorderLayout());
            setOpaque(false);
            setAlignmentX(LEFT_ALIGNMENT);
            pane = new JEditorPane();
            pane.setEditorKit(new HTMLEditorKitBuilder().withWordWrapViewFactory().build());
            pane.setEditable(false);
            pane.setOpaque(false);
            pane.setMargin(JBUI.emptyInsets());
            pane.setFont(JBFont.label());
            pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            pane.putClientProperty("caretWidth", 0);
            pane.setText(HtmlChunk.raw(html).wrapWith(HtmlChunk.body()).wrapWith(HtmlChunk.html()).toString());
            pane.setCaretPosition(0);
            add(pane, BorderLayout.CENTER);
        }

        /**
         * The layout's actual width wins over the card's estimate: the chat
         * item's chrome and the container's scrollbar are not the card's to
         * predict exactly, and a pane measured one pixel too wide wraps to
         * one more line than it was given room for — the last line clipped.
         * Re-measuring here pins the right height; the pane's revalidate
         * then reaches the container's scroll pane, which lays out again.
         */
        @Override
        public void doLayout() {
            int width = getWidth();
            if (width > 0 && width != measuredWidth) {
                wrapAt(width);
            }
            super.doLayout();
        }

        /** Re-sizes the pane at {@code width} so its preferred height is the wrapped height. */
        void wrapAt(int width) {
            if (width <= 0) {
                return;
            }
            measuredWidth = width;
            pane.setPreferredSize(null); // measure the text, never a previous pin
            pane.setSize(width, Integer.MAX_VALUE);
            Dimension wrapped = pane.getPreferredSize();
            pane.setPreferredSize(new Dimension(width, wrapped.height));
        }
    }
}
