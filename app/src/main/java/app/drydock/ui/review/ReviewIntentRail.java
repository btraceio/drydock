package app.drydock.ui.review;

import app.drydock.review.ChangeGraph;
import app.drydock.review.ReadingPath;
import app.drydock.review.Provenance;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewVerdict;
import app.drydock.ui.PanelHeader;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The intent rail (spec §4.2): one card per intent, with its number, title,
 * kind tag, subtitle and a risk heat bar.
 *
 * <p>Settled intents dim and show their verdict; collapsed, the rail shows a
 * status dot instead of a clipped label, because a clipped label is worse
 * than no label -- it invites the reader to guess.</p>
 */
final class ReviewIntentRail extends VBox {

    static final double EXPANDED_WIDTH = 232;
    static final double NARROW_WIDTH = 196;
    static final double COLLAPSED_WIDTH = 40;
    private static final Duration COLLAPSE_ANIMATION = Duration.millis(160);

    /**
     * How much narrower a card's content is than the cards column: the
     * column's 6px side padding, the card's 8px side padding and its 1px
     * border, both sides. Matches {@code .review-intent-cards} and
     * {@code .review-intent-card} in {@code app.css}.
     */
    private static final double CARD_WIDTH_INSET = 2 * (6 + 8 + 1);

    /**
     * How much narrower a fan-in row's reason is than the rest of the card:
     * {@code .review-fanin-count}'s own 3px side padding, both sides. See
     * {@link #reasonNode}.
     */
    private static final double FANIN_TEXT_INSET = 2 * 3;

    // The handler is read at click time, so it can be installed after construction.
    private final PanelHeader header = PanelHeader.left(
            "INTENTS", "", "Collapse or expand the intents (i)",
            () -> this.onToggleCollapse.run());
    private final VBox cards = new VBox();
    private final ScrollPane scroll = new ScrollPane(cards);

    /**
     * See {@link #groupingPending}. Its own row rather than folded into the
     * header's hint: the hint already carries "{@code N/M · i}" in the same
     * ~154px the header's padding and title leave out of the rail's 232px
     * (196px narrow) width, and appending "· refining grouping…" (another
     * ~190px at 10px) either truncated the whole hint under {@code
     * ELLIPSIS} overrun or ate the settled/counted counter beside it --
     * exactly the "{@code R..}"/"{@code ...}" truncation this project has
     * shipped once already. Wrapped, on its own line, it cannot collide
     * with anything else in the header.
     */
    private final Label pendingBanner = new Label("refining grouping…");

    private final Map<String, Button> buttonsByIntentId = new LinkedHashMap<>();
    private final Map<String, Button> buttonsByHunkId = new LinkedHashMap<>();

    /**
     * The rail's two ways of listing the same diff (spec §7.1): today's
     * cards, or one row per hunk in reading order. A mode of the rail, not a
     * fourth column -- the width budget that ruled out a concept map rules
     * out a new column just as firmly, and {@link RailLayout} is untouched.
     */
    enum Mode { INTENTS, PATH }

    private Mode mode = Mode.INTENTS;

    /**
     * Whether the order the rail is listing was measured here or claimed by
     * the agent (spec §6.5). A property of the ORDER, so it belongs to the
     * rail rather than to a card: §7.1's three sources are three sources for
     * the whole sequence. PATH mode is measured by construction -- §6.4 has
     * {@link app.drydock.review.ReadingPath} order the computed grouping only.
     */
    private Provenance provenance = Provenance.MEASURED;

    /**
     * {@code PATH} mode's rows, already in reading order and already
     * numbered against {@link ReadingPath.Path#sections()} -- see {@link
     * #showPath}. Never re-sorted or renumbered here: {@link
     * ReadingPath.Step#sectionNumber} is the one authority for both, and a
     * rail that recomputed either would risk disagreeing with the entry
     * point it is handed (spec §6, Task 17).
     */
    private List<ReadingPath.Step> pathSteps = List.of();

    /** The hunk id {@code PATH} mode highlights as selected. */
    private String selectedHunkId;

    private Consumer<ReadingPath.Step> onPathSelected = step -> { };

    /**
     * Which {@code PATH} rows have somewhere to click their fan-in, and what
     * to do when a reader clicks it (spec §7.4).
     *
     * <p>Asked per row rather than carried on {@link ReadingPath.Step},
     * because the scan lands after the path is first computed: the rail is
     * rebuilt on the refresh that follows it, and this reads whatever is
     * true at that moment. False by default, so a rail with nothing wired --
     * every rail test that does not care -- renders exactly today's plain
     * reason label.</p>
     */
    private Predicate<ReadingPath.Step> fanInAvailable = step -> false;

    private BiConsumer<ReadingPath.Step, Node> onFanIn = (step, anchor) -> { };

    private List<ReviewIntent> intents = List.of();
    /**
     * How a card learns what its section adds up to. A section has no verdict
     * of its own now -- overlapping sections cannot own one -- so the rail is
     * handed the derived state rather than a stored {@link ReviewVerdict}.
     */
    private Function<ReviewIntent, SectionStates.SectionState> stateLookup =
            intent -> SectionStates.SectionState.unknown();
    private Consumer<ReviewIntent> onSelected = intent -> { };
    private Runnable onToggleCollapse = () -> { };
    private String selectedId;
    private boolean collapsed;
    private boolean narrow;

    /**
     * True while the grouping shown is provisional: a computed
     * {@link ChangeGraph} is still building, and what is on screen is the
     * (kind, directory) fallback the real grouping may still replace. Shown
     * via {@link #pendingBanner} rather than silently, so a reviewer
     * mid-read is not surprised by cards changing under them with no
     * warning at all.
     */
    private boolean groupingPending;

    /** Non-zero while the narrow Browse page sizes this rail; see {@link #setSpanWidth}. */
    private double spanWidth;

    /** The collapse/expand animation currently running, so a span set can cancel it. */
    private Timeline widthAnimation;

    ReviewIntentRail() {
        getStyleClass().add("review-intent-rail");
        setMinWidth(EXPANDED_WIDTH);
        setPrefWidth(EXPANDED_WIDTH);
        setMaxWidth(EXPANDED_WIDTH);

        cards.getStyleClass().add("review-intent-cards");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("review-intent-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        pendingBanner.getStyleClass().add("review-intent-pending");
        pendingBanner.setWrapText(true);
        pendingBanner.setManaged(false);
        pendingBanner.setVisible(false);

        getChildren().setAll(header.node(), pendingBanner, scroll);
    }

    void setOnSelected(Consumer<ReviewIntent> handler) {
        this.onSelected = handler == null ? intent -> { } : handler;
    }

    /** Which of the rail's two modes is showing. Whichever of {@link #setIntents}/{@link #showPath} ran last. */
    Mode mode() {
        return mode;
    }

    void setOnPathSelected(Consumer<ReadingPath.Step> handler) {
        this.onPathSelected = handler == null ? step -> { } : handler;
    }

    /**
     * Wires {@code PATH} mode's fan-in affordance: {@code available} decides
     * which rows get one, {@code onRequested} is handed the row and the
     * control it was clicked on, so a popover can anchor to it.
     */
    void setFanIn(Predicate<ReadingPath.Step> available,
                  BiConsumer<ReadingPath.Step, Node> onRequested) {
        this.fanInAvailable = available == null ? step -> false : available;
        this.onFanIn = onRequested == null ? (step, anchor) -> { } : onRequested;
    }

    void setOnToggleCollapse(Runnable handler) {
        this.onToggleCollapse = handler == null ? () -> { } : handler;
    }

    void setSectionStateLookup(Function<ReviewIntent, SectionStates.SectionState> lookup) {
        this.stateLookup = lookup == null
                ? intent -> SectionStates.SectionState.unknown()
                : lookup;
    }

    /**
     * Why a rail is showing no intents. Four situations, four sentences: the
     * rail is told which one it is in rather than inferring it from an
     * absence, because a failed diff and an in-flight one are both "no
     * entry" and mean opposite things to a reader.
     */
    enum Empty {
        NONE(""),
        DIFFING("Diffing…"),
        NOT_CHECKED_OUT("Not checked out — check out to group changes"),
        DIFF_FAILED("Could not diff — see the message beside this"),
        NO_CHANGES("No changes");

        private final String message;

        Empty(String message) {
            this.message = message;
        }

        String message() {
            return message;
        }
    }

    private Empty emptyReason = Empty.NONE;

    /** Replaces the rail's contents and marks {@code selectedIntentId} as current. */
    void setIntents(List<ReviewIntent> newIntents, String selectedIntentId, Empty reason,
                    Provenance provenance) {
        this.mode = Mode.INTENTS;
        this.provenance = provenance == null ? Provenance.MEASURED : provenance;
        this.intents = List.copyOf(newIntents);
        this.selectedId = selectedIntentId;
        this.emptyReason = reason == null ? Empty.NONE : reason;
        rebuild();
    }

    /**
     * Switches the rail to {@code PATH} mode: one row per hunk in reading
     * order, across section boundaries (spec §7.1, Task 18). {@code steps}
     * is rendered exactly as handed in -- already the path's order, already
     * numbered against {@link ReadingPath.Path#sections()} -- so the rail
     * has nothing left to reconcile between "card 1" and the entry point
     * (see the class-level correction this task was given: rendering the
     * grouping's own order while numbering off the path's is the exact way
     * {@code START HERE} ends up on the wrong card).
     */
    void showPath(List<ReadingPath.Step> steps, String selectedHunkId, Empty reason) {
        this.mode = Mode.PATH;
        this.pathSteps = List.copyOf(steps);
        this.selectedHunkId = selectedHunkId;
        this.emptyReason = reason == null ? Empty.NONE : reason;
        rebuild();
    }

    /** See {@link #groupingPending}. */
    void setGroupingPending(boolean pending) {
        if (groupingPending == pending) {
            return;
        }
        groupingPending = pending;
        rebuild();
    }

    boolean collapsed() {
        return collapsed;
    }

    void setCollapsed(boolean newCollapsed) {
        if (collapsed == newCollapsed) {
            return;
        }
        collapsed = newCollapsed;
        resize(true);
        rebuild();
    }

    void setNarrow(boolean newNarrow) {
        if (narrow == newNarrow) {
            return;
        }
        narrow = newNarrow;
        if (!collapsed) {
            resize(true);
        }
    }

    /**
     * The narrow drill-in's Browse page (spec §4.9) sizes the rails as
     * fractions of the window rather than letting them keep their own fixed
     * width. {@code 0} hands sizing back to {@link #targetWidth()}. Never
     * animated: a width that is a fraction of the window has to track a
     * resize frame for frame, and an animation would lag every drag.
     */
    void setSpanWidth(double width) {
        if (spanWidth == width) {
            return;
        }
        spanWidth = width;
        resize(false);
    }

    private void resize(boolean animate) {
        if (spanWidth > 0) {
            applyWidth(spanWidth);
        } else if (animate) {
            animateTo(targetWidth());
        } else {
            applyWidth(targetWidth());
        }
    }

    /**
     * Pins the width now, cancelling any collapse animation still in flight.
     * Without that cancel a span set moments after a collapse/expand would be
     * silently animated back over the following 160ms -- the rail would take
     * its span for one frame and then return to its own width.
     */
    private void applyWidth(double target) {
        stopWidthAnimation();
        setMinWidth(target);
        setPrefWidth(target);
        setMaxWidth(target);
    }

    /**
     * Package-private (not just called internally): {@link SessionReviewView#close}
     * calls this too, so a rail torn down mid-collapse/expand does not leave
     * an in-flight {@link Timeline} running against a detached node.
     */
    void stopWidthAnimation() {
        if (widthAnimation != null) {
            widthAnimation.stop();
            widthAnimation = null;
        }
    }

    private double targetWidth() {
        if (collapsed) {
            return COLLAPSED_WIDTH;
        }
        return narrow ? NARROW_WIDTH : EXPANDED_WIDTH;
    }

    private void animateTo(double target) {
        stopWidthAnimation();
        widthAnimation = new Timeline(new KeyFrame(COLLAPSE_ANIMATION,
                new KeyValue(minWidthProperty(), target),
                new KeyValue(prefWidthProperty(), target),
                new KeyValue(maxWidthProperty(), target)));
        widthAnimation.play();
    }

    private void rebuild() {
        header.showCollapsed(collapsed);
        header.setTitleVisible(!collapsed);
        header.setHintVisible(!collapsed);
        header.setTitle(mode == Mode.PATH ? "PATH" : "INTENTS");

        if (mode == Mode.PATH) {
            rebuildPath();
            return;
        }
        rebuildIntents();
    }

    private void rebuildIntents() {
        // Sections, not hunks: the verdict bar below counts hunks, and two
        // counts of the same thing in two places is one of them being wrong.
        long counted = intents.stream().filter(ReviewIntent::countsTowardProgress).count();
        long settled = intents.stream()
                .filter(ReviewIntent::countsTowardProgress)
                .filter(intent -> stateLookup.apply(intent).decision().isPresent())
                .count();
        header.setHint(settled + "/" + counted + " · i");

        boolean showBanner = groupingPending && !collapsed;
        pendingBanner.setManaged(showBanner);
        pendingBanner.setVisible(showBanner);

        buttonsByIntentId.clear();
        List<Node> nodes = new ArrayList<>();
        for (ReviewIntent intent : intents) {
            Button card = buildCard(intent);
            buttonsByIntentId.put(intent.id(), card);
            nodes.add(card);
        }
        // A collapsed rail has no width for prose, so the message is only for
        // the expanded one; `nodes` is necessarily empty here, since the
        // message exists precisely when there are no cards to show.
        if (nodes.isEmpty() && emptyReason != Empty.NONE && !collapsed) {
            Label message = new Label(emptyReason.message());
            message.getStyleClass().add("review-intent-empty");
            message.setWrapText(true);
            nodes.add(message);
        }
        cards.getChildren().setAll(nodes);
        applySelection();
    }

    /**
     * {@code PATH} mode's render: one row per {@link ReadingPath.Step}, in
     * the exact order {@link #showPath} was handed -- see that method's
     * javadoc for why this never re-sorts or renumbers.
     */
    private void rebuildPath() {
        header.setHint(pathSteps.size() + (pathSteps.size() == 1 ? " hunk · i" : " hunks · i"));

        boolean showBanner = groupingPending && !collapsed;
        pendingBanner.setManaged(showBanner);
        pendingBanner.setVisible(showBanner);

        buttonsByHunkId.clear();
        List<Node> nodes = new ArrayList<>();
        String lastFile = null;
        int indexInFile = 0;
        for (int i = 0; i < pathSteps.size(); i++) {
            ReadingPath.Step step = pathSteps.get(i);
            indexInFile = step.file().equals(lastFile) ? indexInFile + 1 : 0;
            lastFile = step.file();
            int hunksInFile = hunksInFile(step.file());
            Button row = buildPathRow(step, indexInFile, hunksInFile);
            buttonsByHunkId.put(step.hunkId(), row);
            nodes.add(row);
        }
        if (nodes.isEmpty() && !collapsed) {
            Label message = new Label(emptyReason != Empty.NONE
                    ? emptyReason.message()
                    : groupingPending
                            ? "Working out the reading order…"
                            : "No reading order for this diff");
            message.getStyleClass().add("review-intent-empty");
            message.setWrapText(true);
            nodes.add(message);
        }
        cards.getChildren().setAll(nodes);
        applySelection();
    }

    private int hunksInFile(String file) {
        return (int) pathSteps.stream().filter(step -> step.file().equals(file)).count();
    }

    /**
     * One {@code PATH} row: its section badge (or {@code START HERE} for the
     * entry point -- {@link ReadingPath.Step#entryPoint}, which is exactly
     * the first row here since {@code steps} arrives in reading order), the
     * file and which of its hunks this is, WHY this file sits where it does,
     * and its links.
     *
     * <p>The reason is stated as a fact about the FILE, never the hunk: a
     * {@link ReadingPath.Step#reason} is computed once per file and copied
     * onto every hunk of it (spec's own correction on this task), so a file
     * with two hunks that do nothing structurally interesting would otherwise
     * read "builds on ①" under both -- a false statement about a hunk that
     * does not itself build on anything. Prefixing it "file " keeps the claim
     * honest regardless of which hunk of the file this row is.</p>
     *
     * <p><strong>Built from Labels, never {@code Button.setText}.</strong> A
     * plain {@code Button}'s own text has no {@code -fx-text-fill} of its
     * own in this stylesheet -- {@code .review-intent-card} sets border and
     * background only -- so it falls back to modena's default button text
     * colour, tuned for a LIGHT button face, against this rail's dark
     * background. Measured on a real screenshot: the selected row's own text
     * came out at 1.13:1 contrast, worse than the unselected 1.70:1, because
     * the lighter {@code :selected} background made a light-on-light problem
     * WORSE. {@link #buildCard}'s intents cards never hit this: their text
     * lives in child {@code Label}s carrying their own {@code -fx-text-fill}
     * (see {@code .review-intent-title} et al. in {@code app.css}), which
     * {@code :selected} brightens explicitly. Rebuilt the same way here --
     * {@code review-path-badge}/{@code -file}/{@code -reason}/{@code -links}
     * each carry an explicit fill, unselected and selected both.</p>
     */
    private Button buildPathRow(ReadingPath.Step step, int indexInFile, int hunksInFile) {
        Button row = new Button();
        // No provenance modifier, and not by omission: spec §6.4 has
        // ReadingPath order the COMPUTED grouping only, so a PATH row is
        // measured by construction and marking it would be the only place on
        // this surface where the marker could lie.
        row.getStyleClass().add("review-intent-card");
        row.setMaxWidth(Double.MAX_VALUE);
        row.setAlignment(Pos.TOP_LEFT);
        row.setOnAction(e -> onPathSelected.accept(step));

        Label badge = new Label(step.entryPoint()
                ? "START HERE " + SectionStates.sectionMark(step.sectionNumber())
                : SectionStates.sectionMark(step.sectionNumber()));
        badge.getStyleClass().add("review-path-badge");

        Label where = new Label(hunksInFile > 1
                ? step.file() + "  ·  hunk " + (indexInFile + 1) + "/" + hunksInFile
                : step.file());
        where.getStyleClass().add("review-path-file");
        where.setWrapText(true);
        HBox.setHgrow(where, Priority.ALWAYS);
        HBox headerRow = new HBox(6, badge, where);
        headerRow.setAlignment(Pos.TOP_LEFT);

        Label reason = new Label("file " + step.reason());
        reason.getStyleClass().add("review-path-reason");
        reason.setWrapText(true);

        VBox content = new VBox(4, headerRow, reasonNode(step, reason)) {
            @Override
            protected double computePrefHeight(double width) {
                // Same reason buildCard's own content VBox overrides this:
                // the Button asks for prefHeight(-1), and a wrapping Label
                // answers that at its MINIMUM width -- one word per line --
                // unless told the width it will actually render at.
                return super.computePrefHeight(width < 0 ? getPrefWidth() : width);
            }
        };
        if (!step.links().isEmpty()) {
            Label links = new Label((step.links().size() == 1 ? "→ " : "→ " + step.links().size() + " links: ")
                    + step.links().stream().map(ReadingPath.Link::label).collect(Collectors.joining("; ")));
            links.getStyleClass().add("review-path-links");
            links.setWrapText(true);
            content.getChildren().add(links);
        }
        // Bound to the CARDS COLUMN, exactly as buildCard's own content is,
        // and for the identical reason: a graphic bound back to its own
        // Button is a cycle that leaves both wrapping labels measuring at
        // zero width on the pass that fixes the height.
        content.prefWidthProperty().bind(cards.widthProperty().subtract(CARD_WIDTH_INSET));
        content.maxWidthProperty().bind(content.prefWidthProperty());
        row.setGraphic(content);
        return row;
    }

    /**
     * The reason line, as a control when there is something behind it to
     * open (spec §7.4). A fan-in reason -- "called from 7 places outside the
     * change" -- is the one reason on this rail that names evidence the
     * reader cannot see from here, and a count with nowhere to click is a
     * statistic rather than comprehension.
     *
     * <p>The very same {@code reason} Label becomes the button's graphic
     * rather than the button minting its own text: {@link ReadingPath} is
     * the one author of that sentence, so there is no second copy to drift,
     * the "file " prefix that scopes the claim to the FILE survives, and the
     * text stays on a {@code Label} carrying its own {@code -fx-text-fill}
     * -- a plain {@code Button.setText} here is the 1.13:1 contrast defect
     * {@link #buildPathRow}'s own javadoc documents.</p>
     */
    private Node reasonNode(ReadingPath.Step step, Label reason) {
        if (!fanInAvailable.test(step)) {
            return reason;
        }
        Button button = new Button();
        button.getStyleClass().add("review-fanin-count");
        // The reason has to WRAP inside the button, and a wrapping Label
        // wraps at the width it is asked to measure itself at. A Button asks
        // its graphic for prefHeight(-1), and a wrapping Label answers THAT
        // as a single line -- so the button sized itself to one line and cut
        // the rest, which the Label renders as an ellipsis. A real screenshot
        // of the running app caught exactly that: "file called from 16 places
        // outside the…" on the rail's only row. This project has shipped that
        // truncation once already ("R..", "...").
        //
        // Same fix, same shape, as buildPathRow's own content VBox: a holder
        // that substitutes its real width for the -1, bound to the CARDS
        // COLUMN and never to the button around it -- a graphic bound back to
        // its own container is the feedback loop this file documents.
        VBox holder = new VBox(reason) {
            @Override
            protected double computePrefHeight(double width) {
                return super.computePrefHeight(width < 0 ? getPrefWidth() : width);
            }
        };
        holder.prefWidthProperty().bind(
                cards.widthProperty().subtract(CARD_WIDTH_INSET + FANIN_TEXT_INSET));
        holder.maxWidthProperty().bind(holder.prefWidthProperty());
        button.setGraphic(holder);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.TOP_LEFT);
        button.setTooltip(new Tooltip("Show where this file's changed symbols are used "
                + "outside the change"));
        button.setOnAction(e -> {
            // CONSUMED, or this row's own Button catches the same
            // ActionEvent on its way up, selects the row, and rebuilds the
            // rail -- which detaches the node the popover is anchored to and
            // hides it again in the same gesture that opened it. Asking to
            // see the callers is not asking to move the cursor.
            e.consume();
            onFanIn.accept(step, button);
        });
        return button;
    }

    private Button buildCard(ReviewIntent intent) {
        Button card = new Button();
        card.getStyleClass().add("review-intent-card");
        // Only CLAIMED adds a modifier: decorating every row would make the
        // distinction say nothing (spec §6.5).
        if (!provenance.styleClass().isEmpty()) {
            card.getStyleClass().add(provenance.styleClass());
        }
        card.setMaxWidth(Double.MAX_VALUE);
        card.setTooltip(new Tooltip(intent.number() + " · " + intent.title()
                + (intent.rationale().isBlank() ? "" : " — " + intent.rationale())
                + " · " + provenance.label()));
        card.setOnAction(e -> onSelected.accept(intent));

        Label number = new Label(String.valueOf(intent.number()));
        number.getStyleClass().add("review-intent-number");

        SectionStates.SectionState state = stateLookup.apply(intent);
        Optional<ReviewVerdict.Decision> decision = state.decision();
        boolean settled = decision.isPresent() || intent.autoApprove();
        boolean moved = state.staleness() == SectionStates.Staleness.MOVED;
        if (settled) {
            card.getStyleClass().add("settled");
        }
        if (moved) {
            card.getStyleClass().add("stale");
        }
        if (state.hunksMissing()) {
            card.getStyleClass().add("adrift");
        }

        Region heat = new Region();
        heat.getStyleClass().addAll("review-intent-heat", intent.risk().styleClass());

        if (collapsed) {
            VBox content = new VBox(4, number);
            content.setAlignment(Pos.CENTER);
            if (settled) {
                // A status dot, not a clipped label: a clipped label invites
                // the reader to guess what it said.
                Region dot = new Region();
                dot.getStyleClass().addAll("review-intent-dot",
                        decisionStyleClass(decision, intent));
                content.getChildren().add(dot);
            }
            card.setGraphic(content);
            card.getStyleClass().add("collapsed");
            return card;
        }

        Label title = new Label(intent.title());
        title.getStyleClass().add("review-intent-title");
        // Wraps rather than clipping. Every fallback title used to be a full
        // path, and at this rail's width they all clipped to the same
        // "app/src/main/java/app/dry…" -- 45 cards that could not be told
        // apart. Titles are short now, but a long one must degrade into two
        // lines, not into an ellipsis that hides what makes it distinct.
        title.setWrapText(true);
        HBox.setHgrow(title, Priority.ALWAYS);
        Label tag = new Label(intent.kind().wireName());
        tag.getStyleClass().addAll("review-intent-tag", "kind-" + intent.kind().wireName());
        tag.setMinWidth(Region.USE_PREF_SIZE);
        HBox titleRow = new HBox(6, number, title, tag);
        titleRow.setAlignment(Pos.TOP_LEFT);

        VBox content = new VBox(4, titleRow) {
            @Override
            protected double computePrefHeight(double width) {
                // The Button sizes its graphic by asking for prefHeight(-1),
                // and a wrapping Label answers that with the height it needs
                // at its MINIMUM width -- one word per line, hundreds of
                // pixels. The card's width is known and pinned below, so
                // answer at that width instead of at no width at all.
                return super.computePrefHeight(width < 0 ? getPrefWidth() : width);
            }
        };
        if (!intent.rationale().isBlank()) {
            Label rationale = new Label(intent.rationale());
            rationale.getStyleClass().add("review-intent-rationale");
            rationale.setWrapText(true);
            content.getChildren().add(rationale);
        }
        intent.collapse().ifPresent(collapse -> {
            Label note = new Label(collapse.hunkCount() + " hunks across " + collapse.fileCount()
                    + " files — collapsed");
            note.getStyleClass().add("review-intent-collapsed-note");
            content.getChildren().add(note);
        });
        content.getChildren().add(heat);
        if (settled) {
            Label label = new Label(decision.map(ReviewVerdict.Decision::label)
                    .orElse(ReviewVerdict.Decision.AUTO_APPROVED.label()));
            label.getStyleClass().addAll("review-intent-settled", decisionStyleClass(decision, intent));
            content.getChildren().add(label);
        } else if (state.hunksMissing()) {
            // Not "unread": there is nothing here to read. Said outright,
            // because such a section can never be settled and the reader
            // would otherwise hunt for the hunks it is asking about.
            Label adrift = new Label("hunks are no longer in this diff");
            adrift.getStyleClass().add("review-intent-adrift");
            adrift.setWrapText(true);
            content.getChildren().add(adrift);
        } else if (state.recordedHunks() > 0) {
            // Part-settled reads as untouched otherwise: the card looks
            // exactly like one nobody has opened, and the reader re-reads
            // hunks they already signed off. recordedHunks, not
            // settledHunks (spec correction 6a): a section with one
            // stale-approved hunk and one genuinely unread one has
            // settledHunks()==0, which would drop this whole label and
            // understate to "untouched" even though one hunk WAS recorded
            // -- ⚠ base moved is the only thing that would still say so.
            // The two agree whenever nothing here is stale, so this only
            // ever changes what the label shows in exactly that gap.
            Label progress = new Label(state.recordedHunks() + "/" + state.totalHunks() + " hunks");
            progress.getStyleClass().add("review-intent-hunk-progress");
            content.getChildren().add(progress);
        }
        // Only while the section is unsettled: on a settled card its own
        // verdict already explains the state, and the marker would be noise.
        if (!settled && !state.settledElsewhere().isEmpty()) {
            // A hunk this section shares was settled elsewhere, which moved
            // this card's count without the reader touching it. Naming where
            // it is shared is what keeps that from reading as state changing
            // on its own.
            Label elsewhere = new Label("✓ reviewed in "
                    + String.join(" ", state.settledElsewhere()));
            elsewhere.getStyleClass().add("review-intent-settled-elsewhere");
            elsewhere.setWrapText(true);
            content.getChildren().add(elsewhere);
        }
        // UNKNOWN says nothing: the delta is still in flight, or the old base
        // cannot be diffed. Neither is evidence that the base moved.
        if (moved) {
            Label stale = new Label("⚠ base moved — confirm");
            stale.getStyleClass().add("review-intent-stale");
            stale.setWrapText(true);
            content.getChildren().add(stale);
        }
        // Bound to the CARDS COLUMN, never to the card. A Button takes its
        // width from its graphic, so a graphic bound back to the button is a
        // cycle: on the pass that fixes the height the card is still 0 wide,
        // both wrapping labels above wrap at 0, and each reports the height of
        // a column of single characters -- an 1100px card, one per rail. The
        // column's width is handed down by the ScrollPane's viewport
        // (setFitToWidth) and cannot depend on what is inside it.
        content.prefWidthProperty().bind(cards.widthProperty().subtract(CARD_WIDTH_INSET));
        content.maxWidthProperty().bind(content.prefWidthProperty());
        card.setGraphic(content);
        return card;
    }

    private static String decisionStyleClass(Optional<ReviewVerdict.Decision> decision,
                                             ReviewIntent intent) {
        return "decision-" + decision
                .orElse(intent.autoApprove() ? ReviewVerdict.Decision.AUTO_APPROVED
                        : ReviewVerdict.Decision.APPROVED)
                .wireName();
    }

    /**
     * Diagnostic-only: opens the first {@code PATH} row's fan-in popover by
     * firing that row's OWN control, never by calling the handler behind it.
     * The popover is a {@code Popup} -- a separate window a scene snapshot of
     * the primary stage cannot see and synthetic Robot input cannot reach in
     * a diag run -- so a visual pass over it needs this hook; firing the real
     * button means the hook fails if the control is ever left unwired.
     */
    String diagOpenFanIn() {
        for (Button row : buttonsByHunkId.values()) {
            Button fanIn = firstFanIn(row.getGraphic());
            if (fanIn != null) {
                fanIn.fire();
                return "fired " + labelTexts(fanIn).stream().findFirst().orElse("(no label)");
            }
        }
        return "no fan-in control on any of " + buttonsByHunkId.size() + " path rows";
    }

    private static Button firstFanIn(Node node) {
        if (node instanceof Button button
                && button.getStyleClass().contains("review-fanin-count")) {
            return button;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Button found = firstFanIn(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Diagnostic-only: how many cards the rail drew, and how tall each one is. */
    String diagCards() {
        StringBuilder sb = new StringBuilder(intents.size() + " intents · "
                + cards.getChildren().size() + " cards h=[");
        for (Node node : cards.getChildren()) {
            sb.append((int) node.getBoundsInParent().getHeight()).append(' ');
        }
        return sb.append("] cardsH=").append((int) cards.getHeight())
                .append(" scrollH=").append((int) scroll.getHeight()).toString();
    }

    private void applySelection() {
        for (Map.Entry<String, Button> entry : buttonsByIntentId.entrySet()) {
            entry.getValue().pseudoClassStateChanged(PseudoClass.getPseudoClass("selected"),
                    entry.getKey().equals(selectedId));
        }
        for (Map.Entry<String, Button> entry : buttonsByHunkId.entrySet()) {
            entry.getValue().pseudoClassStateChanged(PseudoClass.getPseudoClass("selected"),
                    entry.getKey().equals(selectedHunkId));
        }
    }

    /**
     * Test-only: PATH mode's rendered row texts, in rendered order --
     * {@code buttonsByHunkId} is a {@link LinkedHashMap} populated in the
     * same loop that renders {@link #cards}, so its values() order matches.
     * Reads every {@link Label}'s text inside the row's graphic (badge,
     * file, reason, links), joined by newlines, since {@link #buildPathRow}
     * puts the row's text on child Labels rather than the Button itself.
     */
    List<String> diagPathRowTexts() {
        return buttonsByHunkId.values().stream()
                .map(button -> String.join("\n", labelTexts(button.getGraphic())))
                .toList();
    }

    /** Every {@link Label}'s text under {@code node}, depth-first. */
    private static List<String> labelTexts(Node node) {
        List<String> texts = new ArrayList<>();
        collectLabelTexts(node, texts,
                Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>()));
        return texts;
    }

    /**
     * <strong>Reads {@code getGraphic()} explicitly, not just children.</strong>
     * A {@link Labeled}'s graphic becomes one of its children only once its
     * SKIN exists, which is a layout pulse away from the moment the row is
     * built -- and {@link #reasonNode} now hangs a fan-in row's reason Label
     * off a nested Button as exactly that graphic. Walking children alone
     * therefore reported a fan-in row with NO reason text at all for the
     * first pulse or two after a render, which makes any assertion over
     * these texts timing-dependent: an {@code assertFalse(anyMatch(...))}
     * could pass because the text had not been parented yet rather than
     * because it was absent. {@code seen} keeps the graphic from being
     * counted twice once the skin does parent it.
     */
    private static void collectLabelTexts(Node node, List<String> into, Set<Node> seen) {
        if (node == null || !seen.add(node)) {
            return;
        }
        if (node instanceof Label label) {
            into.add(label.getText());
        }
        if (node instanceof Labeled labeled) {
            collectLabelTexts(labeled.getGraphic(), into, seen);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectLabelTexts(child, into, seen);
            }
        }
    }
}
