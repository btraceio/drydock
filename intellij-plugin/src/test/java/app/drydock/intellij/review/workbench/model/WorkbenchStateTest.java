package app.drydock.intellij.review.workbench.model;

import app.drydock.git.UnifiedDiff;
import app.drydock.intellij.review.ReviewTabModel;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@link WorkbenchState}: the navigator's files under the filter, and the header's progress. */
class WorkbenchStateTest {

    private static final ReviewTabModel.Snapshot REVIEW = ReviewTabModel.snapshot(new ReviewTabModel.Inputs(
            new UnifiedDiff(List.of(file("a.txt"), file("b.txt"))),
            List.of(finding("a.txt")), List.of(), Optional.of("base")));

    @Test
    void allShowsTheWholeReview() {
        WorkbenchState state = WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL, REVIEW,
                WorkbenchState.Filter.ALL, Optional.empty(), WorkbenchState.View.CHANGES_ONLY, List.of(),
                WorkbenchState.InboxChoice.DEFAULT);
        assertSame(REVIEW, state.files());
        assertEquals("0 / 2 reviewed", state.progressText());
    }

    @Test
    void withFindingsShowsTheirFilesAndKeepsTheSelectedOne() {
        WorkbenchState filtered = WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL, REVIEW,
                WorkbenchState.Filter.WITH_FINDINGS, Optional.empty(), WorkbenchState.View.CHANGES_ONLY, List.of(),
                WorkbenchState.InboxChoice.DEFAULT);
        assertEquals(List.of("a.txt"), paths(filtered));

        WorkbenchState selected = WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL, REVIEW,
                WorkbenchState.Filter.WITH_FINDINGS, Optional.of("b.txt"), WorkbenchState.View.CHANGES_ONLY, List.of(),
                WorkbenchState.InboxChoice.DEFAULT);
        assertEquals(List.of("a.txt", "b.txt"), paths(selected));
        assertEquals("0 / 2 reviewed", selected.progressText(), "the header counts the whole review");
    }

    @Test
    void theInboxListsTheFullFindingListUnderItsChoiceFollowingTheSelectedFile() {
        ReviewAnnotation dismissed = finding("a.txt").withTriage(Triage.DISMISSED);
        WorkbenchState state = WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL, REVIEW,
                WorkbenchState.Filter.ALL, Optional.of("a.txt"), WorkbenchState.View.CHANGES_ONLY,
                List.of(dismissed, finding("b.txt")),
                new WorkbenchState.InboxChoice(InboxModel.Tab.DONE, InboxModel.Scope.FILE));

        assertEquals(List.of(dismissed), state.inbox().cards().stream().map(InboxModel.Card::finding).toList(),
                "dismissed findings, which the file rows drop, are the Done tab's");
        assertEquals(0, state.inbox().count(InboxModel.Tab.TO_TRIAGE), "b.txt is outside the file scope");
    }

    private static SectionsModel.Snapshot sections(SectionsModel.Source source) {
        return new SectionsModel.Snapshot(source, Optional.empty(), List.of(new SectionsModel.Section("step:s1", 1,
                Optional.of("s1"), "One", "", List.of(), List.of(), List.of(), 0, 0, 0)));
    }

    private static WorkbenchState withSections(SectionsModel.Snapshot sections, WorkbenchState.SectionsChoice choice) {
        return WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL, REVIEW, WorkbenchState.Filter.ALL,
                Optional.empty(), WorkbenchState.View.CHANGES_ONLY, List.of(), WorkbenchState.InboxChoice.DEFAULT,
                sections, choice);
    }

    @Test
    void theNavigatorOpensOnSectionsOnlyForATourAndKeepsTheUsersTab() {
        assertEquals(WorkbenchState.Navigator.SECTIONS,
                withSections(sections(SectionsModel.Source.TOUR), WorkbenchState.SectionsChoice.DEFAULT).navigator());
        assertEquals(WorkbenchState.Navigator.FILES,
                withSections(sections(SectionsModel.Source.COMPUTED), WorkbenchState.SectionsChoice.DEFAULT).navigator());
        assertEquals(WorkbenchState.Navigator.FILES, withSections(sections(SectionsModel.Source.TOUR),
                new WorkbenchState.SectionsChoice(Optional.of(WorkbenchState.Navigator.FILES), Optional.empty()))
                .navigator());
    }

    @Test
    void aSelectedSectionIsShownOnlyOnTheSectionsTabAndOnlyWhileItExists() {
        WorkbenchState.SectionsChoice chosen = new WorkbenchState.SectionsChoice(
                Optional.of(WorkbenchState.Navigator.SECTIONS), Optional.of("step:s1"));
        assertEquals("One", withSections(sections(SectionsModel.Source.TOUR), chosen).shownSection()
                .orElseThrow().title());

        WorkbenchState gone = withSections(sections(SectionsModel.Source.TOUR),
                new WorkbenchState.SectionsChoice(Optional.of(WorkbenchState.Navigator.SECTIONS),
                        Optional.of("step:s9")));
        assertEquals(Optional.empty(), gone.section());

        WorkbenchState onFiles = withSections(sections(SectionsModel.Source.TOUR),
                new WorkbenchState.SectionsChoice(Optional.of(WorkbenchState.Navigator.FILES), Optional.of("step:s1")));
        assertEquals(Optional.empty(), onFiles.shownSection());
    }

    @Test
    void theModeChipNamesWhatIsReviewed() {
        assertEquals("Local changes", WorkbenchState.Mode.LOCAL.label());
        assertEquals("Pull request", WorkbenchState.Mode.PR.label());
    }

    private static List<String> paths(WorkbenchState state) {
        return state.files().files().stream().map(ReviewTabModel.FileRow::path).toList();
    }

    private static UnifiedDiff.FileDiff file(String path) {
        UnifiedDiff.Line added = new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                OptionalInt.of(1), "x");
        return new UnifiedDiff.FileDiff(path, "M", 1, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -0,0 +1,1 @@", List.of(added))));
    }

    private static ReviewAnnotation finding(String path) {
        return new ReviewAnnotation("s1", "f1", path, "n1", "n1", Severity.BLOCKING, Confidence.HIGH,
                Optional.of("leak"), "tester", Instant.EPOCH, List.of(), Optional.empty(), Optional.empty(),
                List.of(), List.of(), Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false,
                Triage.CONFIRMED, Optional.empty());
    }
}
