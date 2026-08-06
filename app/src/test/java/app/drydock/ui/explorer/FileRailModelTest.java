package app.drydock.ui.explorer;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The funnel's rules (Explorer delta, part 3). The footer's wording IS the
 * feature -- it is the only thing telling the reader what the rail narrowed
 * away and how to get it back -- so it is pinned here rather than eyeballed.
 */
class FileRailModelTest {

    private static FileRailModel.Entry entry(String path, int changed, int findings, boolean contentHit) {
        return new FileRailModel.Entry(Path.of(path), changed, findings, contentHit);
    }

    private final FileRailModel.Entry sidebar = entry("ui/sidebar/Sidebar.java", 59, 1, false);
    private final FileRailModel.Entry tracker = entry("ui/sidebar/DragTracker.java", 12, 0, false);
    private final FileRailModel.Entry sizing = entry("ui/settings/SizeSetting.java", 3, 0, false);
    private final FileRailModel.Entry pane = entry("ui/settings/SettingsPane.java", 0, 0, false);
    private final FileRailModel.Entry math = entry("core/util/LayoutMath.java", 0, 0, false);

    private final List<FileRailModel.Entry> all = List.of(sidebar, tracker, sizing, pane, math);

    private static List<String> names(List<FileRailModel.Entry> entries) {
        return entries.stream().map(FileRailModel.Entry::name).toList();
    }

    @Test
    void diffScopeShowsOnlyTheChangeSortedByChurn() {
        List<FileRailModel.Entry> shown = FileRailModel.visible(all,
                FileRailModel.Scope.DIFF, FileRailModel.Sort.CHURN, "", null);
        assertEquals(List.of("Sidebar.java", "DragTracker.java", "SizeSetting.java"), names(shown));
    }

    @Test
    void repoScopeKeepsOutOfChangeFilesInTheList() {
        List<FileRailModel.Entry> shown = FileRailModel.visible(all,
                FileRailModel.Scope.REPO, FileRailModel.Sort.CHURN, "", null);
        assertEquals(5, shown.size(), "out-of-change files are dimmed by the view, never dropped here");
        assertFalse(shown.get(shown.size() - 1).inDiff(), "and they sort last under churn");
    }

    @Test
    void diffScopeNeverHidesTheFileTheReaderIsLookingAt() {
        List<FileRailModel.Entry> shown = FileRailModel.visible(all,
                FileRailModel.Scope.DIFF, FileRailModel.Sort.NAME, "", math.relative());
        assertTrue(names(shown).contains("LayoutMath.java"),
                "the open file must keep its row, or the rail dead-ends on it");
    }

    @Test
    void sortCyclesChurnFindingsAndName() {
        assertEquals(FileRailModel.Sort.FINDINGS, FileRailModel.Sort.CHURN.next());
        assertEquals(FileRailModel.Sort.NAME, FileRailModel.Sort.FINDINGS.next());
        assertEquals(FileRailModel.Sort.CHURN, FileRailModel.Sort.NAME.next());

        assertEquals("Sidebar.java", names(FileRailModel.visible(all,
                FileRailModel.Scope.REPO, FileRailModel.Sort.FINDINGS, "", null)).get(0));
        assertEquals(List.of("DragTracker.java", "LayoutMath.java", "SettingsPane.java",
                        "Sidebar.java", "SizeSetting.java"),
                names(FileRailModel.visible(all, FileRailModel.Scope.REPO, FileRailModel.Sort.NAME, "", null)));
    }

    @Test
    void churnBandsFollowHowMuchOfTheFileMoved() {
        assertEquals(FileRailModel.Churn.HIGH, sidebar.churn());
        assertEquals(FileRailModel.Churn.MED, tracker.churn());
        assertEquals(FileRailModel.Churn.LOW, sizing.churn());
        assertEquals(FileRailModel.Churn.NONE, pane.churn());
        assertEquals("untouched", pane.stat());
        assertEquals("~3 lines", sizing.stat());
    }

    @Test
    void aQueryMatchesNamesPathsAndContent() {
        assertTrue(sidebar.matches("sidebar"), "by name");
        assertTrue(sizing.matches("settings"), "by path");
        FileRailModel.Entry contentOnly = entry("core/util/LayoutMath.java", 0, 0, true);
        assertTrue(contentOnly.matches("clamp"), "by content, even though the path says nothing");
        assertFalse(math.matches("clamp"));
    }

    @Test
    void theFooterIsTheEscapeHatchWhenDiffScopeIsHidingMatches() {
        List<FileRailModel.Entry> withHits = List.of(sidebar, entry("ui/settings/SettingsPane.java", 0, 0, true),
                entry("core/util/LayoutMath.java", 0, 0, true));
        String footer = FileRailModel.footer(withHits, FileRailModel.Scope.DIFF,
                FileRailModel.Sort.CHURN, "clamp", null, 1247);
        assertEquals("⌕ \"clamp\" matches 2 more files in the repo — show", footer);
        assertTrue(FileRailModel.footerIsAction(FileRailModel.Scope.DIFF, "clamp", withHits, null));
    }

    @Test
    void theFooterSaysSoWhenThereIsNothingOutsideTheChange() {
        List<FileRailModel.Entry> onlyInDiff = List.of(sidebar);
        assertEquals("no further matches outside this change",
                FileRailModel.footer(onlyInDiff, FileRailModel.Scope.DIFF,
                        FileRailModel.Sort.CHURN, "Sidebar", null, 1247));
        assertFalse(FileRailModel.footerIsAction(FileRailModel.Scope.DIFF, "Sidebar", onlyInDiff, null));
    }

    @Test
    void withoutAQueryTheFooterSaysWhatTheChangeIsAndHowBigTheRepoIs() {
        assertEquals("this change · 3 files — repo has 1,247",
                FileRailModel.footer(all, FileRailModel.Scope.DIFF, FileRailModel.Sort.CHURN, "", null, 1247));
    }

    @Test
    void repoScopeReportsWhatItIsShowing() {
        assertEquals("whole repo · 1,247 files, 5 shown · sorted by churn",
                FileRailModel.footer(all, FileRailModel.Scope.REPO, FileRailModel.Sort.CHURN, "", null, 1247));
        assertEquals("2 matches across the repo · out-of-change dimmed",
                FileRailModel.footer(List.of(sidebar, entry("a/B.java", 0, 0, true)),
                        FileRailModel.Scope.REPO, FileRailModel.Sort.CHURN, "sidebar", null, 1247)
                        .replace("1 match ", "1 match "));
    }

    @Test
    void theOpenFileKeepsItsRowEvenWhenTheQueryDoesNotMatchIt() {
        Path open = Path.of("ui/Strings.java");
        List<FileRailModel.Entry> all = List.of(
                new FileRailModel.Entry(Path.of("ui/OrderService.java"), 13, 0, true),
                new FileRailModel.Entry(open, 0, 0, false));

        List<Path> shownInRepo = FileRailModel.visible(all, FileRailModel.Scope.REPO,
                        FileRailModel.Sort.NAME, "settled", open).stream()
                .map(FileRailModel.Entry::relative).toList();
        assertTrue(shownInRepo.contains(open), "the file on screen is never dropped: " + shownInRepo);

        List<Path> shownInDiff = FileRailModel.visible(all, FileRailModel.Scope.DIFF,
                        FileRailModel.Sort.NAME, "settled", open).stream()
                .map(FileRailModel.Entry::relative).toList();
        assertTrue(shownInDiff.contains(open), "…in either scope: " + shownInDiff);
    }

    @Test
    void theOpenFileDoesNotInflateTheMatchCount() {
        Path open = Path.of("ui/Strings.java");
        List<FileRailModel.Entry> all = List.of(
                new FileRailModel.Entry(Path.of("ui/OrderService.java"), 13, 0, true),
                new FileRailModel.Entry(open, 0, 0, false));
        assertEquals("1 match across the repo · out-of-change dimmed",
                FileRailModel.footer(all, FileRailModel.Scope.REPO, FileRailModel.Sort.NAME,
                        "settled", open, 6));
    }

    @Test
    void searchingContentInDiffScopeNeverDeadEnds() {
        // The delta's "done when": a content search in diff scope must never
        // leave an empty rail without the repo escape hatch in the footer.
        List<FileRailModel.Entry> hitsOutsideOnly = List.of(sidebar,
                entry("core/util/LayoutMath.java", 0, 0, true));
        List<FileRailModel.Entry> shown = FileRailModel.visible(hitsOutsideOnly,
                FileRailModel.Scope.DIFF, FileRailModel.Sort.CHURN, "lerp", null);
        assertTrue(shown.isEmpty(), "nothing in the change matches");
        assertTrue(FileRailModel.footerIsAction(FileRailModel.Scope.DIFF, "lerp", hitsOutsideOnly, null),
                "so the footer must be the way out");
        assertTrue(FileRailModel.footer(hitsOutsideOnly, FileRailModel.Scope.DIFF,
                FileRailModel.Sort.CHURN, "lerp", null, 1247).contains("— show"));
    }
}
