package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.PendingQuestions;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.SessionReviewScopes;
import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import app.drydock.ui.TestStages;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An ask's answer lands on the board that asked it: a card over the diff
 * column, kept until dismissed -- the whole point of {@code
 * review_ask_answer} is that the reader never has to leave the code they
 * asked about (spec §4).
 */
class ReviewAskAnswerTest extends FxTest {

    private final DiffService diffService = new DiffService();
    private FakeReviewHost host;
    private SessionReviewView view;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-ask")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        host.diff = new UnifiedDiff(List.of(new UnifiedDiff.FileDiff("src/Main.java", "M", 1, 0, false,
                false, List.of(new UnifiedDiff.Hunk("@@ -1 +1 @@", List.of(
                new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT,
                        OptionalInt.empty(), OptionalInt.of(1), "line")))))));
        view = new SessionReviewView(host, diffService, null);
        Scene scene = new Scene(view, 1400, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        TestStages.show(stage, scene);
    }

    @AfterEach
    void tearDown() {
        diffService.close();
        host.store.close();
    }

    /** Shows the board on one worktree scope so the diff column (and with it the answers overlay) is in the scene. */
    private void showBoard() {
        ReviewScopeRegistry registry = new ReviewScopeRegistry();
        ReviewScope minted = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKTREE,
                Path.of("/repo"), Optional.of(Path.of("/wt/feat")), "master", "feat",
                Optional.empty(), Optional.empty()));
        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(minted, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        FxSync.waitForFxEvents();
    }

    private static PendingQuestions.AnsweredAsk answered(String symbol, String answer) {
        PendingQuestions.PendingAsk ask =
                new PendingQuestions.PendingAsk("ask-1", "rs_scope", symbol,
                        Instant.parse("2026-10-08T12:00:00Z"));
        return new PendingQuestions.AnsweredAsk(ask, answer, Instant.now());
    }

    @Test
    void anAnswerShowsAsACardOverTheDiffColumn() {
        showBoard();
        interact(() -> view.showAskAnswer(answered("loadConfig", "It memoizes the parser.")));

        List<Label> bodies = lookup(".ask-answer-body").queryAll().stream()
                .map(node -> (Label) node).toList();
        assertEquals(1, bodies.size());
        assertTrue(bodies.getFirst().getText().contains("memoizes the parser."));
        assertTrue(lookup(".ask-answer-title").queryAll().stream()
                .map(node -> ((Label) node).getText()).findFirst().orElse("").contains("loadConfig"),
                "the card names the symbol it answers about");
    }

    @Test
    void answersStackAndEachDismissesAlone() {
        showBoard();
        interact(() -> {
            view.showAskAnswer(answered("loadConfig", "first"));
            view.showAskAnswer(answered("parseHeader", "second"));
        });
        assertEquals(2, lookup(".ask-answer-card").queryAll().size());

        interact(() -> ((Button) lookup(".ask-answer-close").queryAll().iterator().next()).fire());

        assertEquals(1, lookup(".ask-answer-card").queryAll().size(),
                "one ✕ closes one answer, not the stack");
    }

    @Test
    void theStackIsCapped() {
        showBoard();
        interact(() -> {
            for (int i = 0; i < 7; i++) {
                view.showAskAnswer(answered("symbol" + i, "answer " + i));
            }
        });

        Set<?> cards = lookup(".ask-answer-card").queryAll();
        assertEquals(5, cards.size(), "the cap keeps the column readable; the oldest drops off");
        assertTrue(lookup(".ask-answer-body").queryAll().stream()
                .map(node -> ((Label) node).getText()).noneMatch(text -> text.contains("answer 0")),
                "the oldest answer is the one that dropped");
    }
}
