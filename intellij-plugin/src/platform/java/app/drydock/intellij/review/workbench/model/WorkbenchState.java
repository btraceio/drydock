package app.drydock.intellij.review.workbench.model;

import app.drydock.intellij.review.ReviewTabModel;
import app.drydock.review.ReviewAnnotation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the review workbench shows (spec §3): one immutable snapshot the
 * panes render, rebuilt off the EDT whenever the review's snapshot or one of
 * the workbench's own choices changes, then pushed to the panes on the EDT.
 *
 * @param subject  the review's subject in the header and tab title ("PR #930", a folder name)
 * @param mode     what is being reviewed; the header's mode chip
 * @param review   the whole review's snapshot (its header counts)
 * @param files    the navigator's files: {@code review} under {@code filter}
 * @param filter   the navigator's file filter
 * @param selected the path the diff shows, if any
 * @param view     how much of the file the diff shows
 * @param inbox    the findings inbox over the scope's full finding list (dismissed and resolved
 *                 included, which the file rows leave out)
 * @param navigator which navigator tab shows
 * @param sections the Sections tab and the section view
 * @param section  the selected section's key, when it is one of {@code sections}
 * @param outbox   the outbox's session lane
 */
public record WorkbenchState(String subject, Mode mode, ReviewTabModel.Snapshot review,
                             ReviewTabModel.Snapshot files, Filter filter, Optional<String> selected,
                             View view, InboxModel.Snapshot inbox, Navigator navigator,
                             SectionsModel.Snapshot sections, Optional<String> section,
                             OutboxModel.Snapshot outbox) {

    /** What is being reviewed: a scope that carries a pull request is reviewed as the pull request. */
    public enum Mode {
        LOCAL("Local changes"),
        PR("Pull request");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** The navigator's file filter. */
    public enum Filter { ALL, WITH_FINDINGS }

    /** How much of a file the diff shows: the changed regions only, or the whole file. */
    public enum View { CHANGES_ONLY, FULL_FILE }

    /** The navigator's tabs. */
    public enum Navigator { SECTIONS, FILES }

    public WorkbenchState {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(review, "review");
        Objects.requireNonNull(files, "files");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(inbox, "inbox");
        Objects.requireNonNull(navigator, "navigator");
        Objects.requireNonNull(sections, "sections");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(outbox, "outbox");
    }

    /** This state with {@code next} as the outbox's session lane. */
    public WorkbenchState withOutbox(OutboxModel.Snapshot next) {
        return new WorkbenchState(subject, mode, review, files, filter, selected, view, inbox, navigator, sections,
                section, next);
    }

    /** The inbox choices a state is built with: which tab, under which scope. */
    public record InboxChoice(InboxModel.Tab tab, InboxModel.Scope scope) {
        public static final InboxChoice DEFAULT = new InboxChoice(InboxModel.Tab.TO_TRIAGE, InboxModel.Scope.ALL);

        public InboxChoice {
            Objects.requireNonNull(tab, "tab");
            Objects.requireNonNull(scope, "scope");
        }
    }

    /**
     * The navigator choices a state is built with: the tab the user picked, if
     * any, and the selected section's key, if any.
     */
    public record SectionsChoice(Optional<Navigator> navigator, Optional<String> section) {
        public static final SectionsChoice DEFAULT = new SectionsChoice(Optional.empty(), Optional.empty());

        public SectionsChoice {
            Objects.requireNonNull(navigator, "navigator");
            Objects.requireNonNull(section, "section");
        }
    }

    /** As the full {@link #of}, with no sections and the default navigator choice. */
    public static WorkbenchState of(String subject, Mode mode, ReviewTabModel.Snapshot review, Filter filter,
                                    Optional<String> selected, View view, List<ReviewAnnotation> findings,
                                    InboxChoice inbox) {
        return of(subject, mode, review, filter, selected, view, findings, inbox, SectionsModel.Snapshot.EMPTY,
                SectionsChoice.DEFAULT);
    }

    /**
     * Builds the state: the navigator's files are the review's under
     * {@code filter}, keeping the selected file; the inbox lists
     * {@code findings} under {@code inbox}'s tab and scope, the file scope
     * following the selected file. The navigator shows the user's tab, else
     * Sections when the agent's tour gives them, else Files; a selected
     * section that {@code sections} no longer has is dropped. The outbox
     * starts empty; {@link #withOutbox} sets it.
     */
    public static WorkbenchState of(String subject, Mode mode, ReviewTabModel.Snapshot review, Filter filter,
                                    Optional<String> selected, View view, List<ReviewAnnotation> findings,
                                    InboxChoice inbox, SectionsModel.Snapshot sections, SectionsChoice choice) {
        Objects.requireNonNull(review, "review");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(inbox, "inbox");
        Objects.requireNonNull(sections, "sections");
        Objects.requireNonNull(choice, "choice");
        ReviewTabModel.Snapshot files = filter == Filter.WITH_FINDINGS ? review.withFindingsOnly(selected) : review;
        Navigator navigator = choice.navigator().orElse(
                sections.source() == SectionsModel.Source.TOUR ? Navigator.SECTIONS : Navigator.FILES);
        Optional<String> section = choice.section().filter(key -> sections.section(key).isPresent());
        return new WorkbenchState(subject, mode, review, files, filter, selected, view,
                InboxModel.snapshot(findings, inbox.tab(), inbox.scope(), selected), navigator, sections, section,
                OutboxModel.Snapshot.EMPTY);
    }

    /** The selected section, when the Sections tab shows and one is selected. */
    public Optional<SectionsModel.Section> shownSection() {
        return navigator == Navigator.SECTIONS ? section.flatMap(sections::section) : Optional.empty();
    }

    /** The header's progress text: the whole review's {@code k / n reviewed}. */
    public String progressText() {
        return review.headerText();
    }
}
