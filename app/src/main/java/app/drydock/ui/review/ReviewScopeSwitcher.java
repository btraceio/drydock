package app.drydock.ui.review;

import app.drydock.review.ReviewScope;
import app.drydock.review.SessionReviewScopes;

import javafx.geometry.Pos;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The session Review sub-tab's scope switcher (spec §3.2): one chip for the
 * checkout's local changes, and a second for the pull request its branch
 * carries when there is one.
 *
 * <p>Two chips at most, and only ever the two {@link SessionReviewScopes}
 * measured for this one checkout -- this is not the cross-repo queue in a
 * smaller box. A session with no PR shows a single chip rather than a chip
 * plus a disabled one: a control that can never be pressed is noise.</p>
 */
final class ReviewScopeSwitcher extends HBox {

    private final ToggleGroup group = new ToggleGroup();

    private Consumer<SessionReviewScopes.Choice> onChoiceChanged = choice -> { };

    /** What the switcher is currently showing as selected; drives the same-choice guard. */
    private SessionReviewScopes.Choice selected = SessionReviewScopes.Choice.LOCAL;

    /** The scopes behind the rendered chips, in chip order, so counts can be re-read. */
    private List<ReviewScope> rendered = List.of();

    ReviewScopeSwitcher() {
        super(2);
        setAlignment(Pos.CENTER_LEFT);
        // The workspace's existing segmented-toggle idiom (see NewWorktreeModal):
        // two mutually exclusive chips in one pill, already themed, and already
        // carrying the :selected rule without which the switcher would show no
        // sign of which chip is chosen.
        getStyleClass().addAll("review-scope-switcher", "seg-toggle");
    }

    /** Called when the human picks the other chip. Never fired by {@link #show}. */
    void setOnChoiceChanged(Consumer<SessionReviewScopes.Choice> handler) {
        this.onChoiceChanged = handler == null ? choice -> { } : handler;
    }

    /**
     * Renders the chips for {@code scopes} with {@code selected} chosen.
     *
     * <p>{@code selected} is honoured only as far as the scopes allow: asking
     * for {@link SessionReviewScopes.Choice#PULL_REQUEST} on a checkout with no
     * PR selects local, exactly as {@link SessionReviewScopes.Scopes#forChoice}
     * resolves it -- a persisted choice from a session whose PR has since been
     * merged must not leave the switcher with nothing selected.</p>
     *
     * <p>Per AGENTS.md, {@link ToggleButton} has no selection guard of its own:
     * the chip is selected BEFORE its listener is installed, and the listener
     * ignores a same-choice transition. Without both, rendering the switcher --
     * or clicking the chip that is already selected -- would re-render the whole
     * board for nothing.</p>
     */
    void show(SessionReviewScopes.Scopes scopes, SessionReviewScopes.Choice selected,
              Function<ReviewScope, Optional<Integer>> findingCount) {
        SessionReviewScopes.Choice effective = scopes.pullRequest().isEmpty()
                ? SessionReviewScopes.Choice.LOCAL
                : selected;
        this.selected = effective;

        // The outgoing chips leave the group as well as the layout: a
        // ToggleGroup holds on to every toggle ever added to it, and a stale
        // member being deselected by the incoming selection is one more
        // listener firing about a control nobody can see.
        group.getToggles().clear();

        List<ReviewScope> showing = new ArrayList<>();
        showing.add(scopes.local());
        scopes.pullRequest().ifPresent(showing::add);
        this.rendered = List.copyOf(showing);

        List<ToggleButton> chips = new ArrayList<>();
        chips.add(chip(scopes.local(), SessionReviewScopes.Choice.LOCAL, effective, findingCount));
        scopes.pullRequest().ifPresent(pr ->
                chips.add(chip(pr, SessionReviewScopes.Choice.PULL_REQUEST, effective, findingCount)));
        getChildren().setAll(chips);
    }

    /**
     * Re-reads the chips' open-finding counts without rebuilding them: a
     * finding landing must relabel a chip, not replace the control the human
     * may be about to click.
     */
    void refreshCounts(Function<ReviewScope, Optional<Integer>> findingCount) {
        for (int i = 0; i < rendered.size() && i < getChildren().size(); i++) {
            if (getChildren().get(i) instanceof ToggleButton chip) {
                chip.setText(chipTextFor(rendered.get(i), findingCount.apply(rendered.get(i))));
            }
        }
    }

    /** Removes every chip: there are no scopes to switch between yet, or at all. */
    void clear() {
        rendered = List.of();
        group.getToggles().clear();
        getChildren().clear();
    }

    private ToggleButton chip(ReviewScope scope, SessionReviewScopes.Choice choice,
                              SessionReviewScopes.Choice selected,
                              Function<ReviewScope, Optional<Integer>> findingCount) {
        ToggleButton chip = new ToggleButton(chipTextFor(scope, findingCount.apply(scope)));
        chip.getStyleClass().addAll("review-scope-chip", "seg-toggle-button");
        chip.setTooltip(new Tooltip(choice == SessionReviewScopes.Choice.PULL_REQUEST
                ? "Review the pull request this branch carries"
                : "Review this checkout's own changes"));
        chip.setToggleGroup(group);
        // Selected first, listener second: a ToggleButton fires its selected
        // property on setSelected exactly as it does on a click, so installing
        // the listener first would make rendering the switcher indistinguishable
        // from the human pressing a chip.
        chip.setSelected(choice == selected);
        chip.selectedProperty().addListener((obs, was, isSelected) -> {
            if (!isSelected || choice == this.selected) {
                // Deselection is the other chip's selection arriving, and a
                // same-choice transition is the already-selected chip being
                // clicked again -- neither is a change of scope.
                return;
            }
            this.selected = choice;
            onChoiceChanged.accept(choice);
        });
        // A toggle group lets its selected member be deselected by clicking it;
        // there is no "no scope" state here, so the chip snaps back on.
        chip.setOnAction(e -> chip.setSelected(true));
        return chip;
    }

    /** What one chip says: what it is, and its open findings when a reviewer has run. */
    static String chipTextFor(ReviewScope scope, Optional<Integer> openFindings) {
        String label = scope.kind() == ReviewScope.Kind.PR
                ? "PR #" + scope.pr().map(ReviewScope.PullRequestRef::number).orElseThrow()
                : "Local changes";
        return openFindings.map(count -> label + " ◨" + count).orElse(label);
    }

    /** Diagnostic-only: what the chips currently read, left to right. */
    List<String> diagChipTexts() {
        return getChildren().stream()
                .filter(ToggleButton.class::isInstance)
                .map(node -> ((ToggleButton) node).getText())
                .toList();
    }

    /**
     * Diagnostic-only: presses the chip for {@code choice}, through the same
     * selection path a click takes. Inert when no such chip is showing -- a
     * PULL_REQUEST press on a session with no PR is exactly the case the
     * switcher refuses to render a chip for.
     */
    void diagSelectChoice(SessionReviewScopes.Choice choice) {
        int index = choice == SessionReviewScopes.Choice.PULL_REQUEST ? 1 : 0;
        if (index < getChildren().size()
                && getChildren().get(index) instanceof ToggleButton chip) {
            chip.setSelected(true);
        }
    }
}
