package app.drydock.ui.nav;

import app.drydock.review.Provenance;
import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import app.drydock.ui.TestStages;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The peek card's tier-3 surfaces (usage-resolution design 2026-10-08,
 * §§7–8): the usages headline's server line -- "N resolved · M scoped ·
 * K name matches", counted from occurrence provenance -- the resolved chip,
 * and the one-per-session quiet footer hint. The usages mode's existing
 * invariants (one scroll region, rows as real Buttons through {@code
 * setOnOpenOccurrence}) are re-asserted here because this test is the one
 * that touches the render path they live in.
 */
class PeekLayerProvenanceTest extends FxTest {

    private static final String HINT = "Exact references need a language server — see Settings";

    private PeekLayer layer;

    @Override
    public void start(Stage stage) {
        layer = new PeekLayer();
        layer.setMaxSize(900, 600);
        layer.setPrefSize(900, 600);
        Scene scene = new Scene(layer, 900, 600);
        scene.getStylesheets().add(getClass().getResource("/app/drydock/ui/app.css").toExternalForm());
        TestStages.show(stage, scene);
    }

    /** The hint is once per app run; a test session is its own session. */
    @BeforeEach
    void resetSessionHint() {
        PeekLayer.resetLanguageServerHintForTest();
    }

    // ------------------------------------------------------------ the peek

    private static SymbolPeek peek(String fileName, SymbolPeek.Occurrence... occurrences) {
        Path relative = Path.of("src/" + fileName);
        return new SymbolPeek("target", "target · " + fileName, Path.of("/repo").resolve(relative),
                relative, 3, List.of("void target() {}"), Set.of(), List.of(occurrences), true);
    }

    private static SymbolPeek.Occurrence row(String file, int line, Provenance provenance) {
        boolean bound = provenance == Provenance.SCOPED || provenance == Provenance.RESOLVED;
        return new SymbolPeek.Occurrence(Path.of(file), line, "void target();", true, bound, provenance);
    }

    private void openUsages(SymbolPeek peek) {
        interact(() -> layer.push(peek));
        FxSync.waitForFxEvents();
        interact(layer::toggleUsages);
        FxSync.waitForFxEvents();
    }

    private Label serverLine() {
        return from(layer).lookup(".peek-usages-server").queryAll().stream()
                .findFirst().map(Label.class::cast).orElse(null);
    }

    private Label hint() {
        return from(layer).lookup(".peek-lsp-hint").queryAll().stream()
                .findFirst().map(Label.class::cast).orElse(null);
    }

    private List<Label> chips(String styleClass) {
        return from(layer).lookup("." + styleClass).queryAll().stream()
                .map(Label.class::cast).toList();
    }

    // ------------------------------------------------------- the server line

    @Test
    void theServerLineCountsResolvedScopedAndNameMatches() {
        openUsages(peek("Sidebar.java",
                row("src/Side.java", 3, Provenance.RESOLVED),
                row("src/Side.java", 40, Provenance.RESOLVED),
                row("src/Caller.java", 7, Provenance.SCOPED),
                row("src/Notes.java", 9, Provenance.MEASURED),
                row("src/Notes.java", 12, Provenance.MEASURED)));

        Label server = serverLine();
        assertEquals("2 resolved · 1 scoped · 2 name matches", server.getText(),
                "the server line names the counts by tier");
        assertEquals(2, chips("peek-usage-chip-resolved").size(), "one resolved chip per confirmed row");
        assertEquals(2, chips("provenance-resolved").size(),
                "the resolved rows carry the existing provenance modifier class");
        assertEquals(1, chips("peek-usage-chip-scoped").size(),
                "the scoped row keeps its chip; a resolved row shows the resolved one instead");
    }

    @Test
    void withoutAResolvedRowTheHeadlineStaysTheOrdinaryOne() {
        openUsages(peek("Sidebar.java",
                row("src/Caller.java", 7, Provenance.SCOPED),
                row("src/Notes.java", 9, Provenance.MEASURED)));

        assertNull(serverLine(), "no server participated: no server line");
        Label heading = from(layer).lookup(".peek-usages-title").query();
        assertTrue(heading.getText().contains("1 bound by scope · 1 name match"), heading.getText());
        assertTrue(chips("peek-usage-chip-resolved").isEmpty(), "nothing says resolved");
        assertEquals(1, chips("peek-usage-chip-scoped").size());
    }

    @Test
    void theOldOccurrenceArityStillRendersTheScopedChip() {
        openUsages(peek("Sidebar.java",
                new SymbolPeek.Occurrence(Path.of("src/Caller.java"), 7, "void target();", true, true),
                new SymbolPeek.Occurrence(Path.of("src/Notes.java"), 9, "void target();", true)));

        assertTrue(chips("peek-usage-chip-resolved").isEmpty(), "the compat constructor derives MEASURED/SCOPED");
        assertEquals(1, chips("peek-usage-chip-scoped").size());
        assertNull(serverLine());
    }

    // ------------------------------------------------------------- the hint

    @Test
    void theHintShowsOncePerSessionOnTheFirstJavaPeekWithoutAResolvedRow() {
        interact(() -> layer.push(peek("Sidebar.java", row("src/Side.java", 3, Provenance.MEASURED))));
        FxSync.waitForFxEvents();
        Label first = hint();
        assertEquals(HINT, first.getText());

        interact(layer::popOne);
        FxSync.waitForFxEvents();
        interact(() -> layer.push(peek("Sidebar.java", row("src/Side.java", 3, Provenance.MEASURED))));
        FxSync.waitForFxEvents();
        assertNull(hint(), "once per session, not once per peek");
    }

    @Test
    void theHintNeverShowsForNonJavaSymbols() {
        interact(() -> layer.push(peek("Sidebar.kt", row("src/Side.kt", 3, Provenance.MEASURED))));
        FxSync.waitForFxEvents();
        assertNull(hint(), "a JDT server was never eligible");
    }

    @Test
    void aResolvedRowSuppressesTheHint() {
        interact(() -> layer.push(peek("Sidebar.java", row("src/Side.java", 3, Provenance.RESOLVED))));
        FxSync.waitForFxEvents();
        assertNull(hint(),
                "the server answered: no hint, the resolved rows speak");
    }

    // ---------------------------------------------- the usages-mode invariants

    /**
     * The binding memory of the peek card's usages mode, re-asserted on this
     * render path: ONE scroll region (usages replace the code) and every row
     * a real Button routed through {@code setOnOpenOccurrence}.
     */
    @Test
    void resolvedRowsKeepTheUsagesModeInvariants() {
        AtomicReference<SymbolPeek.Occurrence> opened = new AtomicReference<>();
        interact(() -> layer.setOnOpenOccurrence(opened::set));
        SymbolPeek.Occurrence resolved = row("src/Side.java", 40, Provenance.RESOLVED);
        openUsages(peek("Sidebar.java", resolved, row("src/Notes.java", 9, Provenance.MEASURED)));

        assertTrue(from(layer).lookup(".peek-code").queryAll().isEmpty(),
                "usages replace the code: one scroll region, never a second scrollbar");
        List<Button> rows = from(layer).lookup(".peek-usage-row").queryAll().stream()
                .map(Button.class::cast).toList();
        assertEquals(2, rows.size());
        assertFalse(rows.stream().anyMatch(row -> !row.isFocusTraversable()),
                "every usage row is a focusable control");

        interact(() -> rows.getFirst().fire());
        FxSync.waitForFxEvents();
        assertEquals(resolved, opened.get(), "the row jumps through setOnOpenOccurrence, chip or no chip");
    }
}
