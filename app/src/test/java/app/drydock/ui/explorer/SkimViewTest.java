package app.drydock.ui.explorer;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skim mode's rows through the headless harness: what starts open, what
 * folds away, and what a finding does to a row.
 */
class SkimViewTest extends ApplicationTest {

    private static final String SOURCE = """
            class Sidebar {

                int width() {
                    return clamp(raw);
                }

                void onRelease(MouseEvent e) {
                    widthProperty.set(tracker.raw());
                }

                private void snapToGuide() {
                    guide.snap();
                }

                private void persist() {
                    prefs.put(KEY, w);
                }
            }
            """;

    private SkimView skim;

    @Override
    public void start(Stage stage) {
        skim = new SkimView();
        // A wrapper root, so a test can size the view itself. Resizing the
        // stage instead would leak: TestFX shares one primary stage across
        // every test class, and a stage left short made a dozen assertions
        // fail over in the review package.
        javafx.scene.layout.StackPane wrapper = new javafx.scene.layout.StackPane(skim);
        Scene scene = new Scene(wrapper, 900, 600);
        scene.getStylesheets().addAll(
                SkimView.class.getResource("/app/drydock/ui/theme-dark.css").toExternalForm(),
                SkimView.class.getResource("/app/drydock/ui/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    private void show(Set<Integer> changed, Map<Integer, String> findings) {
        interact(() -> skim.show(Path.of("ui/Sidebar.java"), SOURCE,
                SourceOutline.parse(SOURCE), changed, findings));
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
    }

    private List<String> rowSignatures() {
        return lookup(".skim-signature").queryAll().stream()
                .map(node -> ((Label) node).getText())
                .toList();
    }

    private List<String> openRowSignatures() {
        return lookup(".skim-signature-open").queryAll().stream()
                .map(node -> ((Label) node).getText())
                .toList();
    }

    @Test
    void changedMembersStartOpenAndEverythingElseStartsFolded() {
        show(Set.of(8), Map.of());

        assertEquals(List.of("void onRelease(MouseEvent e) {"), openRowSignatures(),
                "only the member carrying the changed line is pre-expanded");
        assertTrue(rowSignatures().contains("int width() {"), "the untouched member is still a row");
        assertEquals(1, lookup(".skim-code").queryAll().size(), "exactly one body is rendered");
    }

    @Test
    void untouchedPrivateHelpersCollapseIntoOneGroupRow() {
        show(Set.of(8), Map.of());

        Label group = (Label) lookup(".skim-group-signature").query();
        assertEquals("private helpers (2)", group.getText());
        assertFalse(rowSignatures().contains("private void persist() {"),
                "a folded helper does not get a row of its own");
    }

    @Test
    void openingTheHelperGroupGivesEachHelperItsOwnRow() {
        show(Set.of(8), Map.of());
        Button group = lookup(".skim-header").queryAll().stream()
                .map(Button.class::cast)
                .filter(button -> button.getGraphic() instanceof HBox box
                        && box.getChildren().stream().anyMatch(node -> node instanceof Label label
                        && label.getText().startsWith("private helpers")))
                .findFirst().orElseThrow();
        clickOn(group);
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();

        assertTrue(rowSignatures().contains("private void snapToGuide() {"));
        assertTrue(rowSignatures().contains("private void persist() {"));
    }

    @Test
    void aFindingKeepsItsMemberOutOfTheFoldedGroupAndLabelsTheRow() {
        show(Set.of(), Map.of(13, "leak"));

        Label chip = (Label) lookup(".skim-finding-tag").query();
        assertEquals("· ◆1 leak", chip.getText());
        assertEquals("private helpers (1)", ((Label) lookup(".skim-group-signature").query()).getText(),
                "a private helper with a finding is not something to fold away");
        assertTrue(lookup(".skim-header").queryAll().stream()
                        .anyMatch(node -> node.getStyleClass().contains("has-finding")),
                "the row carries the faint red tint");
    }

    @Test
    void expandingAMemberIsRememberedAndReversible() {
        show(Set.of(), Map.of());
        Button first = lookup(".skim-header").queryButton();
        clickOn(first);
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, lookup(".skim-code").queryAll().size());

        clickOn(lookup(".skim-header").queryButton());
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
        assertEquals(0, lookup(".skim-code").queryAll().size(), "clicking again folds it back");
    }

    @Test
    void revealingALineOpensTheMemberThatContainsIt() {
        show(Set.of(), Map.of());
        interact(() -> skim.revealLine(8));
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();

        assertEquals(List.of("void onRelease(MouseEvent e) {"), openRowSignatures());
    }

    /**
     * Revealing has to scroll to the member, not to the top. The rows are
     * rebuilt first, so their bounds are all zero until a layout pass runs --
     * reading them too early makes every reveal compute a target of 0, which
     * is exactly "jump to the top of the file" and defeats the round-trip
     * that {@code z} and a minimap tick both depend on.
     */
    @Test
    void revealingScrollsToTheMemberRatherThanTheTopOfTheFile() {
        show(Set.of(), Map.of());
        // Short enough that the outline cannot fit: with no overflow there is
        // nowhere to scroll and the bug cannot show itself. The VIEW is sized,
        // never the shared stage.
        interact(() -> {
            skim.setMinHeight(140);
            skim.setPrefHeight(140);
            skim.setMaxHeight(140);
        });
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();

        interact(() -> skim.revealLine(8));
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();

        assertTrue(skim.getVvalue() > 0.0,
                "scrolled to the revealed member, not to the top: vvalue=" + skim.getVvalue());
    }

    @Test
    void everySkimRowIsFocusTraversable() {
        show(Set.of(8), Map.of());
        assertTrue(lookup(".skim-header").queryAll().stream().allMatch(node -> node.isFocusTraversable()));
    }
}
