package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.SectionsModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.impl.DiffRequestProcessor;
import com.intellij.diff.impl.DiffRequestProcessorListener;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.tools.util.base.TextDiffSettingsHolder;
import com.intellij.diff.util.DiffUserDataKeysEx;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The centre's section view (spec §4, "Section view"): the selected
 * section's narrative over one card per hunk. A card is a header (file ·
 * lines · −/+ · also in §N · Mark Reviewed · Full File) and the agent's
 * take on the hunk, if any, over a diff of its file scrolled to the hunk; a
 * hunk reviewed in another section is one "reviewed in §N" row with Show
 * Again.
 *
 * <p>The S2 technique: each card's diff is its own diff processor on one
 * shared diff place whose settings collapse unchanged code, its viewers
 * decorated like the file view's ({@link Actions#decorator}). A card
 * costs two editors, so its panel is created only while the card is within
 * one card height of the viewport and disposed when it leaves; a placeholder
 * of the same height holds the scroll position. Every panel is disposed with
 * the view. EDT.</p>
 */
final class ReviewWorkbenchSectionView extends JPanel implements Disposable {

    /** The diff place every card shares: its settings collapse unchanged code around the hunk. */
    static final String CARD_PLACE = "DrydockReviewSection";
    static final int CARD_HEIGHT = 320;
    static final int CONTEXT_RANGE = 4;
    static final String LOADING = "Loading the diff…";
    static final String CHANGED_SINCE = "This file changed since the review was read: refresh the review.";
    static final String MARK_SECTION = "Mark Section Reviewed";
    static final String MARK_HUNK = "Mark Reviewed";
    static final String REVIEWED = "Reviewed";
    static final String MARKING = "Marking…";
    static final String FULL_FILE = "Full File";
    static final String SHOW_AGAIN = "Show Again";
    static final String ALL_REVIEWED = "Every section is reviewed.";

    /** What the view asks of the live review. */
    interface Actions {
        /**
         * The diff of {@code path} that shows only its hunk {@code hunkIndex} as a change, scrolled to
         * {@code scrollKey}; empty when the file changed since the diff. Any thread.
         */
        CompletableFuture<Optional<ReviewDiffRequests.RequestContext>> hunkContext(String path, int hunkIndex,
                                                                                 String scrollKey);

        /** Approves {@code digests} in {@code section}; completes with the refusal to show, empty when written. */
        CompletableFuture<Optional<String>> markReviewed(SectionsModel.Section section, List<String> digests);

        /** Shows {@code path} in the Files tab with the whole file. EDT. */
        void fullFile(String path);

        /** Shows the finding with {@code findingId} (a red flag's link) in the Files tab. EDT. */
        void showFinding(String findingId);

        /** Selects the section with {@code key}; the view shows it with the next state. EDT. */
        void showSection(String key);

        /**
         * The hook a card's diff reports its viewers to, decorating them with
         * the finding cards and marks on {@code path}'s hunk {@code hunkIndex}
         * and the gutter "+" until {@code lifetime} ends. EDT.
         */
        default Consumer<FrameDiffTool.DiffViewer> decorator(String path, int hunkIndex, Disposable lifetime) {
            return viewer -> {
            };
        }
    }

    private final Project project;
    private final Actions actions;
    private final JBLabel title = new JBLabel();
    private final JBTextArea narrative = new JBTextArea();
    private final JBLabel progress = new JBLabel();
    final JButton markSection = new JButton(MARK_SECTION);
    final JBLabel notice = new JBLabel();
    private final TrackingPanel stack = new TrackingPanel();
    private final JBScrollPane scroll = new JBScrollPane(stack);
    final List<Card> cards = new ArrayList<>();
    /** The collapsed hunks the user asked to see again, for the section shown. */
    private final Set<String> shownAgain = new HashSet<>();
    private SectionsModel.Section shown;
    /** What the cards were built for; a render with the same layout only updates them. */
    private List<String> layout = List.of();
    private boolean marking;
    /**
     * A mark from this view that was written, until a state shows its hunks
     * reviewed: the section it was in, and its digests. That state decides
     * whether the view moves on.
     */
    private String advanceFrom;
    private Set<String> advanceDigests = Set.of();

    ReviewWorkbenchSectionView(Project project, Actions actions) {
        super(new BorderLayout());
        this.project = Objects.requireNonNull(project, "project");
        this.actions = Objects.requireNonNull(actions, "actions");
        TextDiffSettingsHolder.TextDiffSettings settings = TextDiffSettingsHolder.TextDiffSettings.getSettings(CARD_PLACE);
        settings.setExpandByDefault(false);
        settings.setContextRange(CONTEXT_RANGE);

        title.setFont(JBUI.Fonts.label().asBold());
        narrative.setEditable(false);
        narrative.setLineWrap(true);
        narrative.setWrapStyleWord(true);
        narrative.setOpaque(false);
        narrative.setFont(JBUI.Fonts.label()); // a text area defaults to the editor's monospace font
        progress.setForeground(UIUtil.getContextHelpForeground());
        notice.setForeground(UIUtil.getContextHelpForeground());
        markSection.addActionListener(e -> {
            if (shown != null) {
                mark(shown, SectionsModel.unsettledDigests(shown));
            }
        });
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        bar.add(progress);
        bar.add(markSection);
        bar.add(notice);
        JPanel north = new JPanel(new BorderLayout());
        north.setBorder(JBUI.Borders.empty(6, 8));
        north.add(title, BorderLayout.NORTH);
        north.add(narrative, BorderLayout.CENTER);
        north.add(bar, BorderLayout.SOUTH);

        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        scroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(20));
        scroll.getViewport().addChangeListener(e -> updateLiveCards());
        add(north, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    /** Shows {@code state}'s selected section, if any. */
    void render(WorkbenchState state) {
        requireEdt();
        Optional<SectionsModel.Section> section = state.shownSection();
        if (section.isEmpty()) {
            shown = null;
            rebuild(List.of());
            return;
        }
        SectionsModel.Section next = section.get();
        if (shown == null || !shown.key().equals(next.key())) {
            shownAgain.clear();
            notice.setText("");
        }
        if (advanceFrom != null && !advanceFrom.equals(next.key())) {
            advanceFrom = null; // the user went elsewhere before the mark showed
        }
        shown = next;
        title.setText("§" + next.number() + "  " + next.title());
        narrative.setText(next.narrative());
        narrative.setVisible(!next.narrative().isEmpty());
        progress.setText(next.progressText() + " reviewed");
        markSection.setEnabled(!marking && !SectionsModel.unsettledDigests(next).isEmpty());
        List<String> nextLayout = layoutOf(next);
        if (nextLayout.equals(layout)) {
            for (int i = 0; i < cards.size(); i++) {
                cards.get(i).update(next.hunks().get(i));
            }
        } else {
            rebuild(nextLayout);
        }
        if (advanceFrom != null && reviewedIn(next, advanceDigests)) {
            advanceFrom = null;
            if (next.reviewed() == next.total()) {
                Optional<SectionsModel.Section> following = SectionsModel.nextUnreviewed(state.sections(), next.key());
                if (following.isPresent()) {
                    actions.showSection(following.get().key());
                } else {
                    notice.setText(ALL_REVIEWED);
                }
            }
        }
    }

    /** Whether every one of {@code digests} shows reviewed in {@code section} (pure). */
    private static boolean reviewedIn(SectionsModel.Section section, Set<String> digests) {
        Set<String> reviewed = new HashSet<>();
        for (SectionsModel.Hunk hunk : section.hunks()) {
            if (hunk.reviewed()) {
                reviewed.add(hunk.digest());
            }
        }
        return reviewed.containsAll(digests);
    }

    /** One entry per hunk: its id and whether it shows as the collapsed row. */
    private List<String> layoutOf(SectionsModel.Section section) {
        return section.hunks().stream()
                .map(hunk -> hunk.hunkId() + (collapsed(hunk) ? "|collapsed" : ""))
                .toList();
    }

    private boolean collapsed(SectionsModel.Hunk hunk) {
        return hunk.collapsed() && !shownAgain.contains(hunk.hunkId());
    }

    private void rebuild(List<String> nextLayout) {
        for (Card card : cards) {
            card.dispose();
        }
        cards.clear();
        stack.removeAll();
        layout = nextLayout;
        if (shown != null) {
            for (SectionsModel.Hunk hunk : shown.hunks()) {
                Card card = new Card(hunk, collapsed(hunk));
                cards.add(card);
                stack.add(card.component);
            }
        }
        stack.revalidate();
        stack.repaint();
        onEdt(this::updateLiveCards);
    }

    /** Creates the panels of the cards within one card height of the viewport, and disposes the others'. */
    void updateLiveCards() {
        Rectangle visible = scroll.getViewport().getViewRect();
        int margin = JBUI.scale(CARD_HEIGHT);
        Rectangle near = new Rectangle(visible.x, visible.y - margin, Math.max(1, visible.width),
                visible.height + 2 * margin);
        for (Card card : cards) {
            Rectangle bounds = card.component.getBounds();
            if (bounds.isEmpty()) {
                continue; // not laid out yet: the layout's viewport change comes back here
            }
            if (near.intersects(bounds)) {
                card.goLive();
            } else {
                card.goDormant();
            }
        }
    }

    private void mark(SectionsModel.Section section, List<String> digests) {
        if (marking || digests.isEmpty()) {
            return;
        }
        marking = true;
        markSection.setEnabled(false);
        for (Card card : cards) {
            card.markButton.setEnabled(false);
        }
        notice.setText(MARKING);
        actions.markReviewed(section, digests).whenComplete((refusal, error) -> onEdt(() -> {
            marking = false;
            notice.setText(error != null ? "Marking failed: " + ReviewTabProvider.rootMessage(error) : refusal.orElse(""));
            if (error == null && refusal.isEmpty()) {
                advanceFrom = section.key();
                advanceDigests = Set.copyOf(digests);
            }
            // The written verdicts arrive as the next state; until then the buttons follow the shown one.
            if (shown != null) {
                markSection.setEnabled(!SectionsModel.unsettledDigests(shown).isEmpty());
                for (Card card : cards) {
                    card.markButton.setEnabled(!card.hunk.reviewed());
                }
            }
        }));
    }

    /** The card header's text (pure): "src/A.java · 12–30 · −1 +3 · also in §2, §4". */
    static String headerText(SectionsModel.Hunk hunk) {
        StringBuilder text = new StringBuilder(hunk.file());
        if (!hunk.lines().isEmpty()) {
            text.append(" · ").append(hunk.lines());
        }
        text.append(" · −").append(hunk.removed()).append(" +").append(hunk.added());
        if (!hunk.alsoIn().isEmpty()) {
            text.append(" · also in ").append(hunk.alsoIn().stream().map(number -> "§" + number)
                    .collect(Collectors.joining(", ")));
        }
        return text.toString();
    }

    /** One hunk: its header and lazily created diff, or the collapsed row. */
    final class Card {
        SectionsModel.Hunk hunk;
        /** As tall as it wants and no taller: the stack's box layout would otherwise stretch it. */
        final JPanel component = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        final JBLabel header = new JBLabel();
        final HunkTakePanel take = new HunkTakePanel(id -> actions.showFinding(id));
        final JButton markButton = new JButton(MARK_HUNK);
        private final JPanel holder = new JPanel(new BorderLayout());
        private final JBLabel placeholder = new JBLabel(LOADING, SwingConstants.CENTER);
        private final boolean collapsedRow;
        private Disposable lifetime;
        private CompletableFuture<Optional<ReviewDiffRequests.RequestContext>> context;
        private boolean disposed;

        Card(SectionsModel.Hunk hunk, boolean collapsedRow) {
            this.hunk = hunk;
            this.collapsedRow = collapsedRow;
            component.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow
                    .borderColor()), JBUI.Borders.empty(4, 8)));
            JPanel top = new JPanel(new BorderLayout());
            top.add(header, BorderLayout.CENTER);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0));
            if (collapsedRow) {
                JButton again = new JButton(SHOW_AGAIN);
                again.addActionListener(e -> {
                    shownAgain.add(this.hunk.hunkId());
                    if (shown != null) {
                        rebuild(layoutOf(shown));
                    }
                });
                buttons.add(again);
            } else {
                markButton.addActionListener(e -> {
                    if (shown != null) {
                        mark(shown, List.of(this.hunk.digest()));
                    }
                });
                JButton fullFile = new JButton(FULL_FILE);
                fullFile.addActionListener(e -> actions.fullFile(this.hunk.file()));
                buttons.add(markButton);
                buttons.add(fullFile);
                placeholder.setForeground(UIUtil.getContextHelpForeground());
                holder.add(placeholder, BorderLayout.CENTER);
                Dimension size = new Dimension(JBUI.scale(400), JBUI.scale(CARD_HEIGHT));
                holder.setPreferredSize(size);
                holder.setMinimumSize(new Dimension(JBUI.scale(100), size.height));
                component.add(holder, BorderLayout.CENTER);
            }
            top.add(buttons, BorderLayout.EAST);
            JPanel north = new JPanel(new BorderLayout());
            north.add(top, BorderLayout.NORTH);
            if (!collapsedRow) {
                north.add(take, BorderLayout.CENTER);
            }
            component.add(north, BorderLayout.NORTH);
            update(hunk);
        }

        void update(SectionsModel.Hunk next) {
            hunk = next;
            if (collapsedRow) {
                header.setText(next.settledElsewhere().map(SectionsModel.Settled::text).orElse(headerText(next)));
                header.setForeground(UIUtil.getContextHelpForeground());
                return;
            }
            header.setText(headerText(next));
            markButton.setText(next.reviewed() ? REVIEWED : MARK_HUNK);
            markButton.setEnabled(!marking && !next.reviewed());
            take.render(next.take());
        }

        /** Whether the card's diff panel exists. */
        boolean live() {
            return lifetime != null;
        }

        void goLive() {
            if (collapsedRow || disposed || lifetime != null) {
                return;
            }
            // Asked once per card: a scroll event must not read the file's texts again. A card
            // that loaded, scrolled away and came back builds its panel from the same result.
            if (context == null) {
                context = actions.hunkContext(hunk.file(), hunk.index(), hunk.scrollKey());
                context.whenComplete((loaded, error) -> onEdt(() -> create(loaded, error)));
            } else if (context.isDone() && !context.isCompletedExceptionally()) {
                create(context.join(), null);
            }
        }

        private void create(Optional<ReviewDiffRequests.RequestContext> loaded, Throwable error) {
            if (disposed || lifetime != null || !cards.contains(this)) {
                return;
            }
            if (error != null || loaded == null || loaded.isEmpty()) {
                placeholder.setText(error != null ? "The diff could not be read: " + ReviewTabProvider.rootMessage(error)
                        : CHANGED_SINCE);
                return;
            }
            Rectangle visible = scroll.getViewport().getViewRect();
            int margin = JBUI.scale(CARD_HEIGHT);
            if (!new Rectangle(visible.x, visible.y - margin, Math.max(1, visible.width), visible.height + 2 * margin)
                    .intersects(component.getBounds())) {
                return; // scrolled away while loading
            }
            lifetime = Disposer.newDisposable(ReviewWorkbenchSectionView.this, "review section card " + hunk.hunkId());
            SimpleDiffRequest request = ReviewDiffRequests.toRequest(project, lifetime, loaded.get());
            // The card header names the file; the editors' revision titles would repeat it.
            request.putUserData(DiffUserDataKeysEx.EDITORS_HIDE_TITLE, Boolean.TRUE);
            CardProcessor processor = new CardProcessor(project, request);
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

        void goDormant() {
            if (lifetime == null) {
                return;
            }
            Disposable ending = lifetime;
            lifetime = null;
            holder.removeAll();
            placeholder.setText(LOADING);
            holder.add(placeholder, BorderLayout.CENTER);
            holder.revalidate();
            holder.repaint();
            Disposer.dispose(ending);
        }

        void dispose() {
            goDormant();
            disposed = true;
        }
    }

    @Override
    public void dispose() {
        for (Card card : cards) {
            card.dispose();
        }
        cards.clear();
    }

    /**
     * The platform's EDT hop, not {@link EventQueue#invokeLater}: a card's
     * diff panel builds editors, which needs the platform's write-intent
     * lock that only the platform's hop holds.
     */
    private static void onEdt(Runnable action) {
        ApplicationManager.getApplication().invokeLater(action);
    }

    private static void requireEdt() {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("ReviewWorkbenchSectionView renders on the EDT");
        }
    }

    /**
     * One card's diff: a processor on the card place with a fixed request.
     * Not a {@code DiffRequestPanel}, because a panel exposes no viewer, and
     * the card's decorations need the viewer the processor reports.
     */
    private static final class CardProcessor extends DiffRequestProcessor {
        private final DiffRequest request;

        CardProcessor(Project project, DiffRequest request) {
            super(project, CARD_PLACE);
            this.request = Objects.requireNonNull(request, "request");
        }

        @Override
        public void updateRequest(boolean scrollToChange, DiffUserDataKeysEx.ScrollToPolicy policy) {
            applyRequest(request, scrollToChange, policy);
        }
    }

    /** The card stack: as wide as the viewport, so cards never scroll sideways. */
    private static final class TrackingPanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return JBUI.scale(20);
        }

        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(visible.height - JBUI.scale(20), JBUI.scale(20));
        }

        @Override public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
